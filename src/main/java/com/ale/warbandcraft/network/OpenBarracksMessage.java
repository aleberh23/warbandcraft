package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Prisoners;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.PlayerKits;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Servidor → cliente: abre (o refresca) la pestaña Barracón del Campamento (H8). */
public class OpenBarracksMessage {
    /** Una fila: misma clase y nivel. */
    public record Row(String key, Component label, int count, int guards) {}

    /** Una fila de prisioneros (H9): mismo nombre y nivel; {@code ransom} = esmeraldas por cada uno. */
    public record PrisonerRow(String key, Component label, int count, int ransom) {}

    private record Sorted(int order, int tier, Row row) {}

    public final BlockPos camp;
    public final String name;
    public final int count, capacity, nearby, guards;
    /** Para cuántos días alcanza la comida (-1 = barracón vacío). */
    public final int foodDays;
    public final int hungryDays;
    public final List<Row> rows;
    public final List<PrisonerRow> prisoners;
    public final int prisonerCount;
    /** H19: campo de entrenamiento: nivel que vale hoy, nivel encargado y costo del próximo (-1 = no se mejora desde acá). */
    public int training, trainingOrdered, trainingCost = -1;

    public OpenBarracksMessage(BlockPos camp, String name, int count, int capacity, int nearby, int guards,
                               int foodDays, int hungryDays, List<Row> rows, List<PrisonerRow> prisoners, int prisonerCount) {
        this.camp = camp;
        this.name = name;
        this.count = count;
        this.capacity = capacity;
        this.nearby = nearby;
        this.guards = guards;
        this.foodDays = foodDays;
        this.hungryDays = hungryDays;
        this.rows = rows;
        this.prisoners = prisoners;
        this.prisonerCount = prisonerCount;
    }

    public static OpenBarracksMessage of(ServerPlayer player, CampBlockEntity camp) {
        BlockPos pos = camp.getBlockPos();
        BarracksData.Barracks b = BarracksData.get(player.serverLevel()).at(pos);
        List<BarracksData.Soldier> soldiers = b == null ? List.of() : b.soldiers;
        int nearby = Barracks.storableNear(player.serverLevel(), pos, player.getUUID()).size();
        OpenBarracksMessage msg = new OpenBarracksMessage(pos, camp.nameString(), soldiers.size(), Barracks.capacity(player.serverLevel(), pos), nearby,
                b == null ? 0 : b.guards(), Barracks.foodDays(camp, b), b == null ? 0 : b.hungryDays,
                rows(player.getServer(), player.getUUID(), b), prisonerRows(b), b == null ? 0 : b.prisoners.size());
        msg.training = com.ale.warbandcraft.camp.Training.level(player.serverLevel(), pos);
        boolean village = com.ale.warbandcraft.village.VillageCamps.villageOf(player.serverLevel(), pos) != null;
        msg.trainingOrdered = village ? msg.training : com.ale.warbandcraft.camp.Training.ordered(player.serverLevel(), pos);
        msg.trainingCost = village || msg.trainingOrdered >= 3 ? -1 : com.ale.warbandcraft.camp.Training.campCost(msg.trainingOrdered + 1);
        return msg;
    }

    /** Filas por clase y nivel, ordenadas como en el Bastón (los reclutas sin clase al final). También las usa el Panel (H11). */
    public static List<Row> rows(MinecraftServer server, UUID owner, @Nullable BarracksData.Barracks b) {
        return rows(server, owner, b == null ? List.of() : b.soldiers);
    }

    /** Lo mismo para cualquier lista de fichas (el cuartel de una aldea, por ejemplo). */
    public static List<Row> rows(MinecraftServer server, UUID owner, List<BarracksData.Soldier> soldiers) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BarracksData.Soldier> sample = new LinkedHashMap<>();
        for (BarracksData.Soldier s : soldiers) {
            int[] c = counts.computeIfAbsent(s.rowKey(), k -> new int[2]);
            c[0]++;
            if (s.guard) c[1]++;
            sample.putIfAbsent(s.rowKey(), s);
        }
        List<Sorted> sorted = new ArrayList<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            BarracksData.Soldier s = sample.get(e.getKey());
            ClassKit kit = s.kit == null ? null : PlayerKits.resolve(server, owner, s.kit);
            Component label;
            int order = Integer.MAX_VALUE;
            if (kit == null) {
                label = Component.translatable("gui.warbandcraft.barracks.recruit");
            } else {
                order = kit.order();
                String tierName = s.tier >= 0 && s.tier < kit.tiers().size() ? kit.tiers().get(s.tier).name() : "?";
                label = Component.literal(kit.name() + " (" + tierName + ")");
            }
            sorted.add(new Sorted(order, s.tier, new Row(e.getKey(), label, e.getValue()[0], e.getValue()[1])));
        }
        sorted.sort(Comparator.comparingInt(Sorted::order).thenComparingInt(Sorted::tier));
        List<Row> rows = new ArrayList<>();
        for (Sorted s : sorted) rows.add(s.row());
        return rows;
    }

    /** Prisioneros (H9): una fila por nombre y nivel, los de más nivel primero. También las usa el Panel (H11). */
    public static List<PrisonerRow> prisonerRows(@Nullable BarracksData.Barracks b) {
        Map<String, List<BarracksData.Prisoner>> byRow = new LinkedHashMap<>();
        List<BarracksData.Prisoner> prisonerList = b == null ? List.of() : new ArrayList<>(b.prisoners);
        prisonerList.sort(Comparator.comparingInt((BarracksData.Prisoner p) -> -p.xpLevel).thenComparing(p -> p.name));
        for (BarracksData.Prisoner p : prisonerList) byRow.computeIfAbsent(p.rowKey(), k -> new ArrayList<>()).add(p);
        List<PrisonerRow> prisoners = new ArrayList<>();
        for (Map.Entry<String, List<BarracksData.Prisoner>> e : byRow.entrySet()) {
            BarracksData.Prisoner p = e.getValue().get(0);
            Faction f = p.faction == null ? null : FactionManager.INSTANCE.get(p.faction);
            Component label = Component.literal(p.name).withStyle(f == null ? ChatFormatting.WHITE : f.color())
                    .append(Component.translatable("gui.warbandcraft.barracks.prisoner_level", p.xpLevel).withStyle(ChatFormatting.GRAY));
            prisoners.add(new PrisonerRow(e.getKey(), label, e.getValue().size(), Prisoners.ransomOf(p)));
        }
        return prisoners;
    }

    public static void writeRow(FriendlyByteBuf buf, Row r) {
        buf.writeUtf(r.key());
        buf.writeComponent(r.label());
        buf.writeVarInt(r.count());
        buf.writeVarInt(r.guards());
    }

    public static Row readRow(FriendlyByteBuf buf) {
        return new Row(buf.readUtf(), buf.readComponent(), buf.readVarInt(), buf.readVarInt());
    }

    public static void writePrisonerRow(FriendlyByteBuf buf, PrisonerRow r) {
        buf.writeUtf(r.key());
        buf.writeComponent(r.label());
        buf.writeVarInt(r.count());
        buf.writeVarInt(r.ransom());
    }

    public static PrisonerRow readPrisonerRow(FriendlyByteBuf buf) {
        return new PrisonerRow(buf.readUtf(), buf.readComponent(), buf.readVarInt(), buf.readVarInt());
    }

    public static void encode(OpenBarracksMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeUtf(msg.name);
        buf.writeVarInt(msg.count);
        buf.writeVarInt(msg.capacity);
        buf.writeVarInt(msg.nearby);
        buf.writeVarInt(msg.guards);
        buf.writeVarInt(msg.foodDays + 1);
        buf.writeVarInt(msg.hungryDays);
        buf.writeCollection(msg.rows, OpenBarracksMessage::writeRow);
        buf.writeVarInt(msg.prisonerCount);
        buf.writeCollection(msg.prisoners, OpenBarracksMessage::writePrisonerRow);
        buf.writeVarInt(msg.training);
        buf.writeVarInt(msg.trainingOrdered);
        buf.writeVarInt(msg.trainingCost + 1);
    }

    public static OpenBarracksMessage decode(FriendlyByteBuf buf) {
        BlockPos camp = buf.readBlockPos();
        String name = buf.readUtf();
        int count = buf.readVarInt(), capacity = buf.readVarInt(), nearby = buf.readVarInt(), guards = buf.readVarInt();
        int foodDays = buf.readVarInt() - 1, hungry = buf.readVarInt();
        List<Row> rows = buf.readList(OpenBarracksMessage::readRow);
        int prisonerCount = buf.readVarInt();
        List<PrisonerRow> prisoners = buf.readList(OpenBarracksMessage::readPrisonerRow);
        OpenBarracksMessage msg = new OpenBarracksMessage(camp, name, count, capacity, nearby, guards, foodDays, hungry, rows, prisoners, prisonerCount);
        msg.training = buf.readVarInt();
        msg.trainingOrdered = buf.readVarInt();
        msg.trainingCost = buf.readVarInt() - 1;
        return msg;
    }

    public static void handle(OpenBarracksMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.camp.CampClientHandler.openBarracks(msg)));
        ctx.get().setPacketHandled(true);
    }
}
