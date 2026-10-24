package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Servidor → cliente: tus Campamentos, aldeas y castillos, para dibujarlos con su nombre en el mapa de Recruits
 * (tecla M). El cliente lo pide con {@link Request} cada vez que abre el mapa.
 */
public class MapPointsMessage {
    public record Point(BlockPos pos, Routes.Kind kind, Component label) {}

    /** H15b: un ejército en marcha (se dibuja donde va según el tiempo); {@code hostile} = viene contra vos o tu enemigo. */
    public record Army(BlockPos from, BlockPos to, long start, long arrive, Component label, boolean hostile) {}

    /** H23: dónde invocar tu ejército para pelear contra una hueste (un círculo de {@code radius} bloques). */
    public record Muster(BlockPos pos, int radius, Component label) {}

    public final List<Point> points;
    public final List<Army> armies;
    public final List<Muster> musters;

    public MapPointsMessage(List<Point> points, List<Army> armies, List<Muster> musters) {
        this.points = points;
        this.armies = armies;
        this.musters = musters;
    }

    public static MapPointsMessage of(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<Point> out = new ArrayList<>();
        CampRegistry camps = CampRegistry.get(level);
        for (BlockPos p : camps.campsOf(player.getUUID())) {
            if (com.ale.warbandcraft.village.VillageCamps.villageOf(level, p) == null) out.add(new Point(p, Routes.Kind.CAMP, camps.displayName(p)));
        }
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (!player.getUUID().equals(v.owner())) continue;
            Faction f = FactionManager.INSTANCE.get(v.faction());
            String of = f == null ? v.faction().getPath() : f.ofName();
            out.add(new Point(v.center(), v.castle() ? Routes.Kind.CASTLE : Routes.Kind.VILLAGE,
                    com.ale.warbandcraft.village.VillageCamps.displayName(level, v,
                            Component.translatable(v.castle() ? "gui.warbandcraft.map.castle" : "gui.warbandcraft.map.village", of))));
        }
        List<Muster> musters = new ArrayList<>();
        for (com.ale.warbandcraft.invasion.InvasionData.Host h : com.ale.warbandcraft.invasion.InvasionData.get(level).hosts()) {
            if (h.owner.equals(player.getUUID()) && h.state == com.ale.warbandcraft.invasion.InvasionData.Host.State.CAMPED) {
                musters.add(new Muster(h.camp, com.ale.warbandcraft.invasion.Hosts.ARMY_RANGE, Component.translatable("gui.warbandcraft.map.muster")));
            }
        }
        // H25: las mazmorras que conocés (rumor, contrato o ya entraste) y los rumores de mazmorras de otros mods
        com.ale.warbandcraft.dungeon.DungeonData dd = com.ale.warbandcraft.dungeon.DungeonData.get(level);
        for (com.ale.warbandcraft.dungeon.DungeonData.Dungeon d : dd.all()) {
            if (d.cleared() || !d.known.contains(player.getUUID())) continue;
            musters.add(new Muster(d.entrance, 16, Component.literal(d.name)));
        }
        for (com.ale.warbandcraft.dungeon.DungeonData.Rumor r : dd.rumorsOf(player.getUUID())) {
            musters.add(new Muster(r.pos(), 24, Component.translatable("gui.warbandcraft.map.rumor", r.name())));
        }
        return new MapPointsMessage(out, armies(player), musters);
    }


    /** H15b: los ejércitos en marcha de esta dimensión (invasiones y guerras entre facciones). */
    public static List<Army> armies(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        com.ale.warbandcraft.invasion.InvasionData data = com.ale.warbandcraft.invasion.InvasionData.get(level);
        com.ale.warbandcraft.faction.Relations rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
        List<Army> out = new ArrayList<>();
        for (com.ale.warbandcraft.invasion.InvasionData.Invasion inv : data.all()) {
            if (inv.state != com.ale.warbandcraft.invasion.InvasionData.State.MARCHING) continue;
            out.add(new Army(inv.from, inv.target, inv.startTick, inv.arriveTick, armyLabel(inv.faction, inv.size),
                    inv.owner.equals(player.getUUID())));
        }
        for (com.ale.warbandcraft.invasion.InvasionData.March m : data.marches()) {
            out.add(new Army(m.from, m.target, m.startTick, m.arriveTick, armyLabel(m.attacker, m.size),
                    rel.atWar(player.getUUID(), m.attacker)));
        }
        for (com.ale.warbandcraft.invasion.InvasionData.Host h : data.hosts()) {   // H23: la hueste acampada (rombo quieto)
            if (!h.owner.equals(player.getUUID()) || h.state != com.ale.warbandcraft.invasion.InvasionData.Host.State.CAMPED) continue;
            Faction f = FactionManager.INSTANCE.get(h.faction);
            BlockPos tents = com.ale.warbandcraft.invasion.Hosts.tents(h);
            out.add(new Army(tents, tents, h.createdTick, h.deadline,
                    Component.translatable("gui.warbandcraft.map.host", f == null ? h.faction.getPath() : f.name(), h.size), true));
        }
        return out;
    }

    private static Component armyLabel(net.minecraft.resources.ResourceLocation faction, int size) {
        Faction f = FactionManager.INSTANCE.get(faction);
        return Component.translatable("gui.warbandcraft.map.army", f == null ? faction.getPath() : f.name(), size);
    }

    public static void encode(MapPointsMessage msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.points, (b, p) -> {
            b.writeBlockPos(p.pos());
            b.writeEnum(p.kind());
            b.writeComponent(p.label());
        });
        buf.writeCollection(msg.armies, (b, a) -> {
            b.writeBlockPos(a.from());
            b.writeBlockPos(a.to());
            b.writeLong(a.start());
            b.writeLong(a.arrive());
            b.writeComponent(a.label());
            b.writeBoolean(a.hostile());
        });
        buf.writeCollection(msg.musters, (b, m) -> {
            b.writeBlockPos(m.pos());
            b.writeVarInt(m.radius());
            b.writeComponent(m.label());
        });
    }

    public static MapPointsMessage decode(FriendlyByteBuf buf) {
        List<Point> points = buf.readList(b -> new Point(b.readBlockPos(), b.readEnum(Routes.Kind.class), b.readComponent()));
        List<Army> armies = buf.readList(b -> new Army(b.readBlockPos(), b.readBlockPos(), b.readLong(), b.readLong(),
                b.readComponent(), b.readBoolean()));
        return new MapPointsMessage(points, armies, buf.readList(b -> new Muster(b.readBlockPos(), b.readVarInt(), b.readComponent())));
    }

    public static void handle(MapPointsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.route.MapMarkers.receive(msg.points, msg.armies, msg.musters)));
        ctx.get().setPacketHandled(true);
    }

    /** Cliente → servidor: "mandame mis puntos". */
    public static class Request {
        public static void encode(Request msg, FriendlyByteBuf buf) {}

        public static Request decode(FriendlyByteBuf buf) {
            return new Request();
        }

        public static void handle(Request msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (player != null) ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), of(player));
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
