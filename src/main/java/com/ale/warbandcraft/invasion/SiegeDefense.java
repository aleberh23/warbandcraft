package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.SiegeDefenders;
import com.ale.warbandcraft.battle.SiegeLanes;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * La defensa de tu aldea durante una invasión: los soldados del cuartel (H16) defienden con {@link SiegeDefenders}
 * (repartirse por carriles, arqueros arriba, tumbar escaleras, esperar adentro). Si caen, salen refuerzos: primero los
 * guardados del cuartel, después los del barracón de la aldea; al terminar, los del barracón vuelven a él.
 * Tus soldados que andan por la aldea (a {@link Invasions#DEFENDER_RANGE}) sin ser del cuartel ni seguirte se suman a
 * la defensa y al terminar vuelven a lo que estaban haciendo.
 */
public final class SiegeDefense {
    /** Marca del refuerzo que salió del barracón (vuelve al terminar). */
    public static final String TAG_REINFORCEMENT = "warbandcraft:reinforcement";
    /** Cada cuánto sale un refuerzo (ticks). */
    public static int REINFORCE_EVERY = 20 * 3;
    /** Marca del soldado tuyo que se sumó a la defensa: centro de la aldea + la orden que tenía (para devolvérsela). */
    public static final String TAG_HELPER = "warbandcraft:defense_helper";

    private static final int FOLLOW = 1, BACK_TO_POS = 3, WANDER = 0, PROTECT = 5;

    private static final class Memory {
        int target = -1;
        long lastReinforce;
    }

    private static final Map<String, Memory> MEMORY = new HashMap<>();

    private SiegeDefense() {}

    private static String key(ServerLevel level, BlockPos center) {
        return "invasion:" + level.dimension().location() + "@" + center.asLong();
    }

    public static void clear() {
        MEMORY.clear();
        SiegeDefenders.clear();
    }

    public static void tick(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v) {
        BlockPos center = v.center();
        Memory m = MEMORY.computeIfAbsent(key(level, center), k -> new Memory());
        List<AbstractRecruitEntity> defenders = defenders(level, center);
        reinforce(level, inv, v, m, defenders, level.getGameTime());
        defenders.addAll(helpers(level, inv.owner, center));
        List<LivingEntity> enemies = new ArrayList<>(Invasions.invaders(level, inv.target));
        if (Assault.pushing(level, center)) {   // trabado: todos a buscar al enemigo
            com.ale.warbandcraft.battle.SiegePush.huntAll(level, defenders, enemies, center, NavCaps.SOLDIER);
            return;
        }
        SiegeDefenders.tick(level, key(level, center), host(level, inv), defenders, enemies, Assault.lanes(level, center), Breach.walls(level, v),
                NavCaps.SOLDIER);
    }

    /** Los del cuartel, de a pie (el caballo queda ahí). */
    private static List<AbstractRecruitEntity> defenders(ServerLevel level, BlockPos center) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (AbstractRecruitEntity s : Quarters.loaded(level, center)) {
            if (!Soldiers.isSoldier(s) || Surrender.is(s)) continue;
            SiegeLanes.dismount(s, true);
            out.add(s);
        }
        return out;
    }

    /**
     * Tus soldados cerca de la aldea que no son del cuartel: se suman (menos los que te siguen, los que están en una
     * Batalla tuya y los trabajadores). La primera vez se anota la orden que tenían.
     */
    private static List<AbstractRecruitEntity> helpers(ServerLevel level, UUID owner, BlockPos center) {
        double r = Invasions.DEFENDER_RANGE;
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(center).inflate(r, 48, r),
                e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), owner) && Soldiers.isSoldier(e) && !Surrender.is(e))) {
            CompoundTag d = s.getPersistentData();
            if (d.contains(Quarters.TAG_QUARTERS)) continue;
            CompoundTag tag = d.getCompound(TAG_HELPER);
            if (!d.contains(TAG_HELPER) || tag.getLong("Center") != center.asLong()) {
                if (VillageData.horizontalDistSq(s.blockPosition(), center) > r * r) continue;
                int state = s.getFollowState();
                if (state == FOLLOW || state == PROTECT || d.getBoolean(BattleGeneral.TAG_BATTLE)) continue;
                tag = new CompoundTag();
                tag.putLong("Center", center.asLong());
                tag.putInt("State", state);
                Vec3 hold = s.getHoldPos();
                if (hold != null) {
                    tag.putDouble("X", hold.x);
                    tag.putDouble("Y", hold.y);
                    tag.putDouble("Z", hold.z);
                }
                d.put(TAG_HELPER, tag);
            }
            SiegeLanes.dismount(s, true);
            out.add(s);
        }
        return out;
    }

    /** Un soldado que se sumó a una defensa que ya terminó (se cargó del disco): vuelve a lo suyo. */
    public static void releaseStale(ServerLevel level, AbstractRecruitEntity s) {
        if (!s.getPersistentData().contains(TAG_HELPER)) return;
        InvasionData.Invasion inv = InvasionData.get(level).at(BlockPos.of(s.getPersistentData().getCompound(TAG_HELPER).getLong("Center")));
        if (inv == null || inv.state != InvasionData.State.FIGHTING) restore(s);
    }

    /** Vuelve a la orden que tenía antes de sumarse a la defensa. */
    private static void restore(AbstractRecruitEntity s) {
        CompoundTag tag = s.getPersistentData().getCompound(TAG_HELPER);
        s.getPersistentData().remove(TAG_HELPER);
        s.setTarget(null);
        s.setFollowState(tag.getInt("State"));   // antes que el puesto: el 2 lo pisa
        if (tag.contains("X")) s.setHoldPos(new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")));
    }

    private static SiegeDefenders.Host host(ServerLevel level, InvasionData.Invasion inv) {
        return new SiegeDefenders.Host() {
            @Override
            public BlockPos center() {
                return inv.target;
            }

            @Override
            public @Nullable UUID listener() {
                return inv.owner;
            }

            @Override
            public void ladderPushed(BlockPos base) {
                inv.ladders.remove(base.asLong());
                InvasionData.get(level).changed();
            }
        };
    }

    /** Si cayó alguno, sale otro: primero del cuartel guardado, después del barracón de la aldea. */
    private static void reinforce(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, Memory m,
                                  List<AbstractRecruitEntity> defenders, long now) {
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        if (m.target < 0) m.target = Math.max(defenders.size() + (b == null ? 0 : b.quarters.size()), Quarters.capacity(level, v));
        if (camp == null || b == null || defenders.size() >= m.target || now - m.lastReinforce < REINFORCE_EVERY) return;
        if (!level.isPositionEntityTicking(camp)) return;
        boolean fromQuarters = !b.quarters.isEmpty();
        List<BarracksData.Soldier> pool = fromQuarters ? b.quarters : b.soldiers;
        BarracksData.Soldier sheet = null;
        for (BarracksData.Soldier x : pool) if (sheet == null || x.xpLevel > sheet.xpLevel) sheet = x;   // el mejor primero
        if (sheet == null) return;
        pool.remove(sheet);
        Vec3 at = Barracks.standable(level, camp, null, camp);
        AbstractRecruitEntity s = Barracks.create(level, sheet, at, camp, BACK_TO_POS, b.hungryDays);
        BarracksData.get(level).changed();
        m.lastReinforce = now;
        if (s == null) return;
        s.getPersistentData().putLong(Quarters.TAG_QUARTERS, v.center().asLong());
        if (!fromQuarters) s.getPersistentData().putBoolean(TAG_REINFORCEMENT, true);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null) {
            p.displayClientMessage(Component.translatable(fromQuarters ? "msg.warbandcraft.defense.from_quarters" : "msg.warbandcraft.defense.reinforcement",
                    s.getName()).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Terminó la invasión: todos sueltos (el cuartel vuelve a lo suyo) y los refuerzos, al barracón. */
    public static void end(ServerLevel level, InvasionData.Invasion inv) {
        MEMORY.remove(key(level, inv.target));
        if (!level.isPositionEntityTicking(inv.target)) {
            SiegeDefenders.end(key(level, inv.target), List.of());
            return;
        }
        List<AbstractRecruitEntity> all = Quarters.loaded(level, inv.target);
        double r = Invasions.DEFENDER_RANGE + 64;
        List<AbstractRecruitEntity> helpers = level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(inv.target).inflate(r, 64, r),
                e -> e.getPersistentData().getCompound(TAG_HELPER).getLong("Center") == inv.target.asLong()
                        && e.getPersistentData().contains(TAG_HELPER));
        List<AbstractRecruitEntity> ended = new ArrayList<>(all);
        ended.addAll(helpers);
        SiegeDefenders.end(key(level, inv.target), ended);
        for (AbstractRecruitEntity s : helpers) restore(s);
        VillageData.Village v = VillageData.get(level).at(inv.target);
        BlockPos camp = v == null ? null : VillageCamps.campOf(level, v);
        for (AbstractRecruitEntity s : all) {
            if (!s.getPersistentData().getBoolean(TAG_REINFORCEMENT) || camp == null) continue;
            s.getPersistentData().remove(TAG_REINFORCEMENT);
            s.getPersistentData().remove(Quarters.TAG_QUARTERS);
            s.setTarget(null);
            if (Barracks.store(level, s, camp, true) != Barracks.StoreResult.OK) s.setFollowState(WANDER);
        }
    }
}
