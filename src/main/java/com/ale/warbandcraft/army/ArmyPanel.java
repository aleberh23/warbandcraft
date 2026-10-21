package com.ale.warbandcraft.army;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Los datos de la pantalla Ejércitos (H20): tus ejércitos y lo que hay en cada barracón para sumarles. */
public final class ArmyPanel {
    private ArmyPanel() {}

    public record PickInfo(BlockPos camp, String row, int count) {}

    /**
     * Un ejército. {@code state}/{@code mode} = ordinales de {@link ArmyData.State}/{@link ArmyData.Mode};
     * {@code ready} = los que saldrían hoy; {@code power} = fuerza estimada (vida × nivel); {@code food} = puntos de
     * comida por día que comen afuera.
     */
    public record ArmyInfo(UUID id, String name, int state, int mode, int ticksLeft, int total, int ready, int inField,
                           int power, int food, List<PickInfo> picks) {}

    /** Un Campamento con las filas de su barracón. */
    public record CampRows(BlockPos pos, Component name, List<OpenBarracksMessage.Row> rows) {}

    public record Data(List<ArmyInfo> armies, List<CampRows> camps, Component here, int max, int maxArmies) {}

    public static Data of(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        long now = level.getGameTime();
        List<ArmyInfo> armies = new ArrayList<>();
        for (ArmyData.Army a : ArmyData.get(level).of(owner)) {
            List<PickInfo> picks = new ArrayList<>();
            for (ArmyData.Pick p : a.picks) picks.add(new PickInfo(p.camp, p.row, p.count));
            armies.add(new ArmyInfo(a.id, a.name, a.state.ordinal(), a.mode.ordinal(),
                    a.state == ArmyData.State.COMING ? (int) Math.max(0, a.arriveTick - now) : 0,
                    a.total(), Armies.ready(level, a), a.members.size(), power(level, a),
                    Armies.ready(level, a) * BarracksData.FOOD_PER_SOLDIER, picks));
        }
        CampRegistry reg = CampRegistry.get(level);
        BarracksData data = BarracksData.get(level);
        List<BlockPos> camps = new ArrayList<>(reg.campsOf(owner));
        camps.sort(Comparator.comparingDouble(p -> p.distSqr(player.blockPosition())));
        List<CampRows> rows = new ArrayList<>();
        for (BlockPos c : camps) {
            List<OpenBarracksMessage.Row> r = OpenBarracksMessage.rows(level.getServer(), owner, data.at(c));
            if (!r.isEmpty()) rows.add(new CampRows(c, reg.displayName(c), r));
        }
        Component problem = Armies.fieldProblem(level, player.blockPosition(), Armies.flat(player.getLookAngle()));
        return new Data(armies, rows, problem == null ? Component.empty() : problem, Armies.MAX_SOLDIERS, Armies.MAX_ARMIES);
    }

    /** Fuerza estimada desde las fichas: vida × (1 + 0,15 × nivel), ×1,5 con caballo. */
    private static int power(ServerLevel level, ArmyData.Army a) {
        BarracksData data = BarracksData.get(level);
        double total = 0;
        for (ArmyData.Pick p : a.picks) {
            BarracksData.Barracks b = data.at(p.camp);
            if (b == null) continue;
            int n = 0;
            for (BarracksData.Soldier s : b.soldiers) {
                if (n >= p.count) break;
                if (!s.rowKey().equals(p.row)) continue;
                n++;
                double hp = s.entity.contains("Health") ? s.entity.getFloat("Health") : 20;
                total += hp * (1 + 0.15 * s.xpLevel) * (s.horse != null ? 1.5 : 1);
            }
        }
        return (int) Math.round(total);
    }

    // ------------------------------------------------------------------ red

    public static void write(FriendlyByteBuf b, Data d) {
        b.writeCollection(d.armies(), (bb, a) -> {
            bb.writeUUID(a.id());
            bb.writeUtf(a.name());
            bb.writeVarInt(a.state());
            bb.writeVarInt(a.mode());
            bb.writeVarInt(a.ticksLeft());
            bb.writeVarInt(a.total());
            bb.writeVarInt(a.ready());
            bb.writeVarInt(a.inField());
            bb.writeVarInt(a.power());
            bb.writeVarInt(a.food());
            bb.writeCollection(a.picks(), (b3, p) -> {
                b3.writeBlockPos(p.camp());
                b3.writeUtf(p.row());
                b3.writeVarInt(p.count());
            });
        });
        b.writeCollection(d.camps(), (bb, c) -> {
            bb.writeBlockPos(c.pos());
            bb.writeComponent(c.name());
            bb.writeCollection(c.rows(), OpenBarracksMessage::writeRow);
        });
        b.writeComponent(d.here());
        b.writeVarInt(d.max());
        b.writeVarInt(d.maxArmies());
    }

    public static Data read(FriendlyByteBuf b) {
        List<ArmyInfo> armies = b.readList(bb -> new ArmyInfo(bb.readUUID(), bb.readUtf(), bb.readVarInt(), bb.readVarInt(), bb.readVarInt(),
                bb.readVarInt(), bb.readVarInt(), bb.readVarInt(), bb.readVarInt(), bb.readVarInt(),
                bb.readList(b3 -> new PickInfo(b3.readBlockPos(), b3.readUtf(), b3.readVarInt()))));
        List<CampRows> camps = b.readList(bb -> new CampRows(bb.readBlockPos(), bb.readComponent(), bb.readList(OpenBarracksMessage::readRow)));
        return new Data(armies, camps, b.readComponent(), b.readVarInt(), b.readVarInt());
    }
}
