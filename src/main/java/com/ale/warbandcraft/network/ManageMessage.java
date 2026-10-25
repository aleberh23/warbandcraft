package com.ale.warbandcraft.network;

import com.ale.warbandcraft.village.VillageManagement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Servidor → cliente (H16): los datos del modo Gestión de una aldea/castillo (null = cerrarlo: ya no estás en una
 * aldea tuya). {@code feedback} = el resultado de la última orden (vacío si no hubo).
 */
public class ManageMessage {
    @Nullable
    public final VillageManagement.Data data;
    public final Component feedback;

    public ManageMessage(@Nullable VillageManagement.Data data, Component feedback) {
        this.data = data;
        this.feedback = feedback;
    }

    public static void encode(ManageMessage msg, FriendlyByteBuf buf) {
        buf.writeComponent(msg.feedback);
        buf.writeNullable(msg.data, (b, d) -> {
            b.writeBlockPos(d.center());
            b.writeComponent(d.name());
            b.writeBoolean(d.castle());
            b.writeNullable(d.storehouse(), FriendlyByteBuf::writeBlockPos);
            b.writeNullable(d.houseMin(), FriendlyByteBuf::writeBlockPos);
            b.writeNullable(d.houseMax(), FriendlyByteBuf::writeBlockPos);
            b.writeNullable(d.stats(), FriendlyByteBuf::writeVarIntArray);
            OpenPanelMessage.writeStock(b, d.daily());
            b.writeVarInt(d.soldiers());
            b.writeVarInt(d.capacity());
            b.writeVarInt(d.guards());
            b.writeVarInt(d.invasionTicks() + 1);
            b.writeComponent(d.invader());
            b.writeCollection(d.free(), (bb, v) -> {
                bb.writeUUID(v.id());
                bb.writeComponent(v.name());
                bb.writeBoolean(v.employed());
            });
            b.writeVarInt(d.employed());
            b.writeCollection(d.workers(), (bb, w) -> {
                bb.writeUUID(w.id());
                bb.writeComponent(w.name());
                bb.writeComponent(w.job());
                bb.writeBoolean(w.hasArea());
                bb.writeVarInt(w.jobIndex() + 1);
                bb.writeComponent(w.status());
                bb.writeInt(w.statusColor());
            });
            b.writeNullable(d.upgrades(), OpenPanelMessage::writeVillageDetail);
            b.writeCollection(d.routes(), (bb, r) -> {
                bb.writeComponent(r.name());
                bb.writeVarInt(r.ticks());
            });
            b.writeComponent(d.sendTo());
            b.writeCollection(d.areas(), (bb, a) -> {
                bb.writeUUID(a.id());
                bb.writeComponent(a.kind());
                bb.writeBlockPos(a.min());
                bb.writeBlockPos(a.max());
                bb.writeInt(a.color());
                bb.writeComponent(a.status());
                bb.writeInt(a.statusColor());
                bb.writeComponent(a.production());
            });
            b.writeLongArray(d.territory());
            d.housing().write(b);
            b.writeVarInt(d.quarters());
            b.writeVarInt(d.quartersCapacity());
            b.writeVarInt(d.patrolling());
            b.writeVarInt(d.towerPosts());
            b.writeVarInt(d.onTowers());
            b.writeCollection(d.troops(), (bb, t) -> {
                bb.writeUtf(t.key());
                bb.writeComponent(t.label());
                bb.writeVarInt(t.barracks());
                bb.writeVarInt(t.quarters());
            });
            d.growth().write(b);
            b.writeCollection(d.sites(), (bb, s) -> {
                bb.writeUtf(s.key());
                bb.writeBlockPos(s.corner());
                bb.writeVarInt(s.rot());
                bb.writeVarInt(s.size());
            });
            b.writeComponent(d.production());
        });
    }

    public static ManageMessage decode(FriendlyByteBuf buf) {
        Component feedback = buf.readComponent();
        VillageManagement.Data data = buf.readNullable(b -> {
            BlockPos center = b.readBlockPos();
            Component name = b.readComponent();
            boolean castle = b.readBoolean();
            BlockPos storehouse = b.readNullable(FriendlyByteBuf::readBlockPos);
            BlockPos houseMin = b.readNullable(FriendlyByteBuf::readBlockPos);
            BlockPos houseMax = b.readNullable(FriendlyByteBuf::readBlockPos);
            int[] stats = b.readNullable(FriendlyByteBuf::readVarIntArray);
            var daily = OpenPanelMessage.readStock(b);
            int soldiers = b.readVarInt(), capacity = b.readVarInt(), guards = b.readVarInt(), invasion = b.readVarInt() - 1;
            Component invader = b.readComponent();
            var free = b.readList(bb -> new VillageManagement.VillagerRow(bb.readUUID(), bb.readComponent(), bb.readBoolean()));
            int employed = b.readVarInt();
            var workers = b.readList(bb -> new VillageManagement.WorkerRow(bb.readUUID(), bb.readComponent(), bb.readComponent(), bb.readBoolean(), bb.readVarInt() - 1,
                    bb.readComponent(), bb.readInt()));
            var upgrades = b.readNullable(OpenPanelMessage::readVillageDetail);
            var routes = b.readList(bb -> new VillageManagement.RouteRow(bb.readComponent(), bb.readVarInt()));
            Component sendTo = b.readComponent();
            var areas = b.readList(bb -> new com.ale.warbandcraft.village.WorkZones.AreaRow(bb.readUUID(), bb.readComponent(), bb.readBlockPos(),
                    bb.readBlockPos(), bb.readInt(), bb.readComponent(), bb.readInt(), bb.readComponent()));
            long[] territory = b.readLongArray();
            var housing = com.ale.warbandcraft.village.Housing.Data.read(b);
            int quarters = b.readVarInt(), quartersCapacity = b.readVarInt(), patrolling = b.readVarInt();
            int towerPosts = b.readVarInt(), onTowers = b.readVarInt();
            var troops = b.readList(bb -> new com.ale.warbandcraft.village.Quarters.TroopRow(bb.readUtf(), bb.readComponent(),
                    bb.readVarInt(), bb.readVarInt()));
            var growth = com.ale.warbandcraft.village.Popularity.Info.read(b);
            var sites = b.readList(bb -> new VillageManagement.SiteRow(bb.readUtf(), bb.readBlockPos(), bb.readVarInt(), bb.readVarInt()));
            Component production = b.readComponent();
            return new VillageManagement.Data(center, name, castle, storehouse, houseMin, houseMax, stats, daily, soldiers, capacity,
                    guards, invasion, invader, free, employed, workers, upgrades, routes, sendTo, areas, territory, housing,
                    quarters, quartersCapacity, patrolling, towerPosts, onTowers, troops, growth, sites, production);
        });
        return new ManageMessage(data, feedback);
    }

    public static void handle(ManageMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.aerial.AerialScreen.receiveManage(msg.data, msg.feedback)));
        ctx.get().setPacketHandled(true);
    }
}
