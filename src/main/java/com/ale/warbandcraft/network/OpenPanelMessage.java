package com.ale.warbandcraft.network;

import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.village.VillageStock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/** Servidor → cliente: abre (o refresca) el Panel de control (H11) con todo tu reino en esta dimensión. */
public class OpenPanelMessage {
    public final int tab;
    public final List<Panel.CampInfo> camps;
    public final List<Panel.VillageInfo> villages;
    public final List<Panel.RouteInfo> routes;
    public final List<Panel.ShipmentInfo> shipments;
    /** El Campamento abierto en detalle, o null (la lista). */
    @Nullable
    public final Panel.CampDetail detail;
    /** H12: la aldea/castillo abierto en Mejoras, o null. */
    @Nullable
    public final Panel.VillageDetail village;

    public OpenPanelMessage(int tab, List<Panel.CampInfo> camps, List<Panel.VillageInfo> villages, List<Panel.RouteInfo> routes,
                            List<Panel.ShipmentInfo> shipments, @Nullable Panel.CampDetail detail,
                            @Nullable Panel.VillageDetail village) {
        this.tab = tab;
        this.camps = camps;
        this.villages = villages;
        this.routes = routes;
        this.shipments = shipments;
        this.detail = detail;
        this.village = village;
    }

    /** {@code detailCamp}: el Campamento a mostrar en detalle (se ignora si no es tuyo). */
    public static OpenPanelMessage of(ServerPlayer player, int tab, @Nullable BlockPos detailCamp) {
        return of(player, tab, detailCamp, null);
    }

    /** {@code village}: la aldea/castillo a mostrar en Mejoras (H12), o null. */
    public static OpenPanelMessage of(ServerPlayer player, int tab, @Nullable BlockPos detailCamp, @Nullable BlockPos village) {
        Panel.CampDetail detail = detailCamp == null ? null : Panel.detail(player, detailCamp);
        Panel.VillageDetail vd = village == null ? null : Panel.villageDetail(player, village);
        return new OpenPanelMessage(tab, Panel.camps(player), Panel.villages(player), Panel.routes(player),
                Panel.shipments(player), detail, vd);
    }

    public int[] totals() {
        return Panel.totals(camps, villages, shipments);
    }

    public static void encode(OpenPanelMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.tab);
        buf.writeCollection(msg.camps, (b, c) -> {
            b.writeBlockPos(c.pos());
            b.writeComponent(c.name());
            b.writeVarInt(c.soldiers());
            b.writeVarInt(c.capacity());
            b.writeVarInt(c.guards());
            b.writeVarInt(c.prisoners());
            b.writeVarInt(c.foodDays() + 2);
            b.writeVarInt(c.emeralds() + 1);
            b.writeVarInt(c.armament() + 1);
            b.writeVarInt(c.smiths() + 1);
            b.writeBoolean(c.near());
        });
        buf.writeCollection(msg.villages, (b, v) -> {
            b.writeBlockPos(v.pos());
            b.writeComponent(v.name());
            b.writeBoolean(v.castle());
            b.writeVarInt(v.guards());
            writeStock(b, v.daily());
            writeStock(b, v.stock());
            writeStock(b, v.cap());
            b.writeBoolean(v.loaded());
            b.writeNullable(v.sendTo(), FriendlyByteBuf::writeBlockPos);
            b.writeComponent(v.sendToName());
            b.writeVarInt(v.sendTicks());
            b.writeVarInt(v.level());
            b.writeComponent(v.building());
            b.writeVarInt(v.percent());
            b.writeVarInt(v.invasionTicks() + 1);
            b.writeComponent(v.invader());
            b.writeVarInt(v.popularity() + 1);
            b.writeVarInt(v.stuckWorkers());
        });
        buf.writeCollection(msg.routes, (b, r) -> {
            b.writeUUID(r.id());
            b.writeUtf(r.name());
            b.writeComponent(r.a());
            b.writeComponent(r.b());
            b.writeVarInt(r.length());
            b.writeVarInt(r.ticks());
            b.writeBoolean(r.active());
            b.writeBoolean(r.road());
            b.writeVarInt(r.cost());
            b.writeVarInt(r.percent() + 1);
        });
        buf.writeCollection(msg.shipments, (b, s) -> {
            b.writeComponent(s.from());
            b.writeComponent(s.to());
            b.writeVarInt(s.soldiers());
            b.writeVarInt(s.stacks());
            b.writeVarInt(s.ticksLeft());
        });
        buf.writeNullable(msg.detail, (b, d) -> {
            b.writeBlockPos(d.pos());
            b.writeComponent(d.name());
            b.writeVarInt(d.soldiers());
            b.writeVarInt(d.capacity());
            b.writeVarInt(d.nearby());
            b.writeBoolean(d.near());
            b.writeCollection(d.rows(), OpenBarracksMessage::writeRow);
            b.writeCollection(d.prisoners(), OpenBarracksMessage::writePrisonerRow);
            b.writeCollection(d.destinations(), (bb, x) -> {
                bb.writeBlockPos(x.pos());
                bb.writeComponent(x.name());
                bb.writeVarInt(x.ticks());
                bb.writeVarInt(x.free());
            });
            b.writeBoolean(d.found());
            b.writeComponent(d.foundBlocked());
        });
        buf.writeNullable(msg.village, OpenPanelMessage::writeVillageDetail);
    }

    /** H12: las mejoras de una aldea (también las usa el modo Gestión de H16). */
    public static void writeVillageDetail(FriendlyByteBuf b, Panel.VillageDetail v) {
        b.writeBlockPos(v.pos());
        b.writeComponent(v.name());
        b.writeBoolean(v.castle());
        b.writeVarInt(v.level());
        b.writeVarInt(v.builder());
        b.writeVarInt(v.emeralds());
        b.writeCollection(v.upgrades(), (bb, u) -> {
            bb.writeUtf(u.id());
            bb.writeComponent(u.name());
            bb.writeComponent(u.effect());
            bb.writeVarInt(u.cost());
            bb.writeVarInt(u.state());
            bb.writeComponent(u.reason());
            bb.writeVarInt(u.percent());
        });
    }

    public static OpenPanelMessage decode(FriendlyByteBuf buf) {
        int tab = buf.readVarInt();
        List<Panel.CampInfo> camps = buf.readList(b -> new Panel.CampInfo(b.readBlockPos(), b.readComponent(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readVarInt() - 2, b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt() - 1,
                b.readBoolean()));
        List<Panel.VillageInfo> villages = buf.readList(b -> new Panel.VillageInfo(b.readBlockPos(), b.readComponent(),
                b.readBoolean(), b.readVarInt(), readStock(b), readStock(b), readStock(b), b.readBoolean(),
                b.readNullable(FriendlyByteBuf::readBlockPos), b.readComponent(), b.readVarInt(), b.readVarInt(), b.readComponent(), b.readVarInt(), b.readVarInt() - 1, b.readComponent(),
                b.readVarInt() - 1, b.readVarInt()));
        List<Panel.RouteInfo> routes = buf.readList(b -> new Panel.RouteInfo(b.readUUID(), b.readUtf(), b.readComponent(), b.readComponent(),
                b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt() - 1));
        List<Panel.ShipmentInfo> shipments = buf.readList(b -> new Panel.ShipmentInfo(b.readComponent(), b.readComponent(),
                b.readVarInt(), b.readVarInt(), b.readVarInt()));
        Panel.CampDetail detail = buf.readNullable(b -> new Panel.CampDetail(b.readBlockPos(), b.readComponent(), b.readVarInt(), b.readVarInt(),
                b.readVarInt(), b.readBoolean(), b.readList(OpenBarracksMessage::readRow),
                b.readList(OpenBarracksMessage::readPrisonerRow),
                b.readList(bb -> new Panel.Destination(bb.readBlockPos(), bb.readComponent(), bb.readVarInt(), bb.readVarInt())),
                b.readBoolean(), b.readComponent()));
        Panel.VillageDetail village = buf.readNullable(OpenPanelMessage::readVillageDetail);
        return new OpenPanelMessage(tab, camps, villages, routes, shipments, detail, village);
    }

    public static Panel.VillageDetail readVillageDetail(FriendlyByteBuf b) {
        return new Panel.VillageDetail(b.readBlockPos(), b.readComponent(), b.readBoolean(),
                b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readList(bb -> new Panel.UpgradeRow(bb.readUtf(), bb.readComponent(),
                bb.readComponent(), bb.readVarInt(), bb.readVarInt(), bb.readComponent(), bb.readVarInt())));
    }

    public static void writeStock(FriendlyByteBuf b, VillageStock.Stock s) {
        b.writeVarInt(s.emeralds());
        b.writeVarInt(s.food());
        b.writeVarInt(s.armament());
    }

    public static VillageStock.Stock readStock(FriendlyByteBuf b) {
        return new VillageStock.Stock(b.readVarInt(), b.readVarInt(), b.readVarInt());
    }

    public static void handle(OpenPanelMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.panel.PanelClientHandler.open(msg)));
        ctx.get().setPacketHandled(true);
    }
}
