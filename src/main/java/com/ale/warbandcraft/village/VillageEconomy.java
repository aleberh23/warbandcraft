package com.ale.warbandcraft.village;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.raid.RaidManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lo que pasa con las aldeas a medida que corre el tiempo (H3):
 * - las aldeas conquistadas pagan impuestos una vez por día de juego (H10: a su propio almacén, {@link VillageStock}),
 * - las guarniciones que perdieron soldados reciben refuerzos de a uno,
 * - las facciones se van olvidando de lo que les hiciste.
 */
public final class VillageEconomy {
    public static int BASE_TAX = 3;
    public static int MAX_VILLAGER_BONUS = 5;
    /** Cada cuánto llega un refuerzo a una guarnición incompleta: 5 minutos. */
    public static long REINFORCE_EVERY_TICKS = 20 * 60 * 5;
    /** Los refuerzos no aparecen si hay un jugador tan cerca de la campana (no aparecen "de la nada" a la vista). */
    public static int REINFORCE_NO_PLAYER_RADIUS = 24;

    /** Último refuerzo por aldea (no se guarda: al reiniciar se espera de nuevo el intervalo). */
    private static final Map<Long, Long> lastReinforce = new HashMap<>();

    private VillageEconomy() {}

    // ------------------------------------------------------------------ cada día

    /** Si empezó un día nuevo de juego: las aldeas juntan lo suyo (H10) y las facciones se calman un poco. */
    public static void checkNewDay(ServerLevel level) {
        VillageData data = VillageData.get(level);
        long day = level.getDayTime() / 24000L;
        if (data.lastTaxDay() < 0) {          // primera vez: no se cobra el día en curso
            data.setLastTaxDay(day);
            return;
        }
        if (day == data.lastTaxDay()) return;
        data.setLastTaxDay(day);
        VillageStock.produceAll(level);
        com.ale.warbandcraft.market.Schedules.onNewDay(level, day);   // H24: envíos programados del mercado
        // H19: llegan reclutas a las tabernas (y pasan solos al cuartel / barracón)
        if (!(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer)) {
            Tavern.onNewDay(level, day);
            Popularity.onNewDay(level);   // H21: las raciones
            FactionTowns.onNewDay(level);   // las aldeas de facción crecen
            GarrisonReserve.onNewDay(level);   // y reponen su reserva
        }
        // H15a: las facciones juntan su almacén y su leva, y las que están en guerra mandan ejércitos
        com.ale.warbandcraft.invasion.Invasions.onNewDay(level, !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer));
        if (level == level.getServer().overworld()) {
            for (Relations.Calmed c : Relations.get(level.getServer()).dailyRecovery()) {   // guerras que se calmaron solas
                Faction f = FactionManager.INSTANCE.get(c.faction());
                if (f == null) continue;
                com.ale.warbandcraft.WarbandCraft.LOGGER.info("{} ya no está en guerra con {}", f.id(), c.player());
                net.minecraft.server.level.ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.player());
                if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.translatable("msg.warbandcraft.war_calmed",
                        net.minecraft.network.chat.Component.literal(f.name()).withStyle(f.color()), Relations.WAR_THRESHOLD));
            }
        }
    }

    /** Impuesto diario de una aldea: base + medio por aldeano (si la aldea está cargada para contarlos). */
    public static int taxOf(ServerLevel level, VillageData.Village v) {
        int bonus = 0;
        if (level.isPositionEntityTicking(v.center())) {
            int villagers = level.getEntitiesOfClass(Villager.class, new AABB(v.center()).inflate(48, 24, 48)).size();
            bonus = Math.min(MAX_VILLAGER_BONUS, villagers / 2);
        }
        int tax = BASE_TAX + bonus;
        if (v.isConquered() && !v.castle()) {   // H21: el nivel de impuestos que eligió el dueño
            com.ale.warbandcraft.upgrade.TownData.Town t = com.ale.warbandcraft.upgrade.TownData.get(level).at(v.center());
            if (t != null) tax = (int) Math.round(tax * Popularity.taxFactor(t.taxes));
        }
        return tax;
    }

    // ------------------------------------------------------------------ refuerzos

    /** Revisa las aldeas cargadas de facciones y les manda refuerzos si les faltan soldados. */
    public static void reinforceAll(ServerLevel level) {
        long now = level.getGameTime();
        for (VillageData.Village v : VillageData.get(level).all()) {
            long last = lastReinforce.computeIfAbsent(v.center().asLong(), k -> now);
            if (now - last < REINFORCE_EVERY_TICKS) continue;
            if (reinforce(level, v, false) != null) lastReinforce.put(v.center().asLong(), now);
        }
    }

    /**
     * Agrega un soldado a la guarnición de {@code v} si le falta para llegar al mínimo de su facción.
     * No pasa en aldeas conquistadas, saqueadas, atacadas, sin cargar o con un jugador muy cerca
     * ({@code force} saltea esto último, para las pruebas).
     */
    @Nullable
    public static AbstractRecruitEntity reinforce(ServerLevel level, VillageData.Village v, boolean force) {
        if (v.isConquered() || v.isLooted(level.getGameTime())) return null;
        if (VillageData.get(level).isTest(v.center())) return null;
        if (RaidManager.get(level, v.center()) != null) return null;
        if (!level.isPositionEntityTicking(v.center())) return null;
        Faction faction = FactionManager.INSTANCE.get(v.faction());
        if (faction == null) return null;
        if (VillageManager.garrison(level, v.center()).size() >= faction.garrisonMin()) return null;
        BlockPos c = v.center();
        if (!force && level.getNearestPlayer(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, REINFORCE_NO_PLAYER_RADIUS, false) != null) {
            return null;
        }
        Troop troop = faction.pickTroop(level.getRandom());
        if (troop == null) return null;
        return VillageManager.spawnTroop(level, c, faction, troop, level.getRandom(), 10);
    }
}
