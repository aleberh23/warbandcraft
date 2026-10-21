package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleSize;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La reserva de las aldeas de facción grandes (pedido de Ale: "si no, es re fácil"). Un pueblo o una ciudad (y más con
 * empalizada o muralla) tiene más soldados que los que se ven patrullando: están en sus casas y en el cuartel. Cuando
 * alguien los ataca (vos, o una batalla entre facciones) salen de a poco del cuartel (o de la plaza) a defender; mientras
 * quede reserva, la aldea no cae. Pasada la pelea, los que salieron y siguen vivos vuelven adentro (dejan de verse).
 * Los caídos se reponen de a poco cada día.
 * <p>
 * Lo único que se guarda es cuántos se perdieron ({@link TownData.Town#npcReserveUsed}); el tamaño sale del tamaño de la
 * aldea ({@link TownData.Town#npcTier}) y de sus muros.
 */
public final class GarrisonReserve {
    /** Marca de los soldados que salieron de la reserva (centro de la aldea). */
    public static final String TAG = "warbandcraft:reserve";

    /** Reserva según el tamaño: aldea / pueblo / ciudad. */
    public static int RESERVE_VILLAGE = 4, RESERVE_TOWN = 10, RESERVE_CITY = 18;
    /** Más reserva con empalizada / con muralla. */
    public static int PALISADE_BONUS = 3, WALL_BONUS = 6;
    /** Cuántos salen por tanda y cada cuántos ticks, mientras se pelea. */
    public static int WAVE = 2, WAVE_TICKS = 20 * 3;
    /** Reserva que se repone por día. */
    public static int DAILY_REFILL = 3;
    /** Sin pelea durante tantos ticks, los de la reserva que quedan afuera vuelven adentro. */
    public static int RETURN_TICKS = 20 * 60;

    /** Último tick con pelea en cada aldea (en memoria). */
    private static final Map<Long, Long> LAST_FIGHT = new HashMap<>();
    private static final Map<Long, Long> LAST_WAVE = new HashMap<>();

    private GarrisonReserve() {}

    public static void clear() {
        LAST_FIGHT.clear();
        LAST_WAVE.clear();
    }

    /** Reserva entera de una aldea de facción (0 para castillos, aldeas tuyas o sin tamaño todavía). */
    public static int max(VillageData.Village v, TownData.Town t) {
        if (v.castle() || v.isConquered() || t == null || t.npcTier <= 0) return 0;
        int n = t.npcTier >= 3 ? RESERVE_CITY : t.npcTier == 2 ? RESERVE_TOWN : RESERVE_VILLAGE;
        if (t.has(Upgrade.WALL)) n += WALL_BONUS;
        else if (t.has(Upgrade.PALISADE)) n += PALISADE_BONUS;
        return n;
    }

    /** Los que quedan adentro, listos para salir. */
    public static int remaining(ServerLevel level, VillageData.Village v) {
        if (v == null || VillageData.get(level).isTest(v.center())) return 0;
        TownData.Town t = TownData.get(level).at(v.center());
        return t == null ? 0 : Math.max(0, max(v, t) - t.npcReserveUsed);
    }

    public static int remaining(ServerLevel level, BlockPos center) {
        return remaining(level, VillageData.get(level).at(center));
    }

    /** Día nuevo: se reponen los caídos. */
    public static void onNewDay(ServerLevel level) {
        TownData towns = TownData.get(level);
        for (VillageData.Village v : VillageData.get(level).all()) {
            TownData.Town t = towns.at(v.center());
            if (t == null || t.npcReserveUsed <= 0) continue;
            if (RaidManager.get(level, v.center()) != null || InvasionData.get(level).battleAt(v.center()) != null) continue;
            t.npcReserveUsed = Math.max(0, t.npcReserveUsed - DAILY_REFILL);
            towns.changed();
        }
    }

    /** Cada segundo: en las peleas, sale la reserva; sin pelea, los que salieron vuelven adentro. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        VillageData data = VillageData.get(level);
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            BlockPos c = v.center();
            if (v.isConquered() || data.isTest(c) || !level.isPositionEntityTicking(c)) continue;
            long key = c.asLong();
            RaidManager.Raid raid = RaidManager.get(level, c);
            boolean fight = (raid != null && raid.state == RaidManager.State.FIGHTING) || InvasionData.get(level).battleAt(c) != null;
            if (fight) {
                LAST_FIGHT.put(key, now);
                TownData.Town t = towns.at(c);
                if (t == null || remaining(level, v) <= 0) continue;
                if (now - LAST_WAVE.getOrDefault(key, 0L) < WAVE_TICKS) continue;
                LAST_WAVE.put(key, now);
                wave(level, v, t, raid);
            } else {
                long last = LAST_FIGHT.computeIfAbsent(key, k -> now);
                if (now - last >= RETURN_TICKS) goBackInside(level, v, towns);
            }
        }
    }

    /** Sale una tanda (si no hay ya demasiados en el campo). */
    private static void wave(ServerLevel level, VillageData.Village v, TownData.Town t, RaidManager.Raid raid) {
        Faction f = FactionManager.INSTANCE.get(v.faction());
        if (f == null) return;
        int onField = AiWars.defenders(level, v.center(), v.faction()).size();
        int n = Math.min(Math.min(WAVE, remaining(level, v)), BattleSize.MAX_PER_SIDE - onField);
        if (n <= 0) return;
        BlockPos from = musterPoint(t, v);
        Player attacker = raid == null ? null : RaidManager.attackerEntity(level, raid);
        int out = 0;
        for (int i = 0; i < n; i++) {
            Troop troop = f.pickTroop(level.getRandom());
            if (troop == null) break;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, from, f, troop, level.getRandom(), 3);
            if (s == null) continue;
            s.getPersistentData().putLong(TAG, v.center().asLong());
            if (attacker != null && attacker.isAlive() && !attacker.isCreative() && !attacker.isSpectator()) s.setTarget(attacker);
            t.npcReserveUsed++;
            out++;
        }
        if (out > 0) {
            TownData.get(level).changed();
            WarbandCraft.LOGGER.debug("Aldea {}: salen {} de la reserva (quedan {})", v.center().toShortString(), out, remaining(level, v));
        }
    }

    /** De dónde salen: el cuartel si tiene, si no la plaza. */
    private static BlockPos musterPoint(TownData.Town t, VillageData.Village v) {
        BlockPos site = t.sites.get(Upgrade.BARRACKS.siteKey());
        return site != null && t.has(Upgrade.BARRACKS) ? site.offset(4, 1, 4) : v.center();
    }

    /** Pasada la pelea: los que salieron de la reserva y siguen vivos vuelven a la reserva. */
    private static void goBackInside(ServerLevel level, VillageData.Village v, TownData towns) {
        long key = v.center().asLong();
        List<AbstractRecruitEntity> out = level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(v.center()).inflate(128, 64, 128),
                e -> e.isAlive() && e.getPersistentData().contains(TAG) && e.getPersistentData().getLong(TAG) == key
                        && VillageManager.isGarrison(e) && v.faction().toString().equals(e.getPersistentData().getString(VillageManager.TAG_FACTION)));
        if (out.isEmpty()) return;
        TownData.Town t = towns.at(v.center());
        for (AbstractRecruitEntity s : out) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
            if (t != null && t.npcReserveUsed > 0) t.npcReserveUsed--;
        }
        towns.changed();
    }
}
