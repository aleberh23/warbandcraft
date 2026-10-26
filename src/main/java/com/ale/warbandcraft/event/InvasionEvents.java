package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Listeners de H15a (IA viva): cada segundo, las invasiones (llegadas, peleas, autoresolución); los invasores que
 * sobran no se cargan; al entrar te recuerdan las invasiones en camino.
 */
public class InvasionEvents {
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        // En las pruebas H15GameTests maneja las invasiones a mano
        if (level.getServer() instanceof GameTestServer || level.getGameTime() % 20 != 12) return;
        try {
            Invasions.tick(level);
            com.ale.warbandcraft.invasion.AiWars.tick(level);   // H15b
            com.ale.warbandcraft.invasion.Hosts.tick(level);   // H23: huestes
            com.ale.warbandcraft.invasion.Ambush.tick(level);   // H24: ejércitos del campo con su general
            com.ale.warbandcraft.village.FactionTowns.tick(level);   // aldeas de facción con lo de su tamaño
            com.ale.warbandcraft.village.GarrisonReserve.tick(level);   // la reserva sale a defender
            if (level.getGameTime() % 100 == 12) com.ale.warbandcraft.market.FactionMarket.tick(level);   // mercaderes de las aldeas de facción
            if (level.getGameTime() % 100 == 37) com.ale.warbandcraft.village.FactionInn.tick(level);   // taberneros de las aldeas de facción
            if (level.getGameTime() % 40 == 12) com.ale.warbandcraft.village.GarrisonLife.tick(level);   // guarniciones con puestos y patrullas
            if (level.getGameTime() % 200 == 52) com.ale.warbandcraft.upgrade.Heraldry.tick(level);   // vidrios y estandartes del dueño
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                com.ale.warbandcraft.command.TestArmyCommand.perfTick(level.getServer());   // H23: medidor de rendimiento
            }
            for (long c : InvasionData.get(level).pendingCenters()) {
                com.ale.warbandcraft.invasion.AiWars.spawnPending(level, net.minecraft.core.BlockPos.of(c));
            }
            if (level.getGameTime() % 200 == 12) com.ale.warbandcraft.invasion.Ambush.giveUp(level);   // H15b: ejércitos del campo sin nadie
            if (level.getGameTime() % 40 == 12) com.ale.warbandcraft.invasion.Patrols.tick(level);   // H15b: patrullas
            if (level.getGameTime() % 1200 == 12) com.ale.warbandcraft.invasion.Patrols.spawnNearPlayers(level);
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Error en el tick de invasiones", e);
        }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.loadedFromDisk() && com.ale.warbandcraft.invasion.Patrols.isPatrol(event.getEntity())) {
            event.setCanceled(true);   // H15b: las patrullas no se guardan (salen de nuevo con un jugador cerca)
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk()
                && (!com.ale.warbandcraft.invasion.AiWars.keepAttacker(level, event.getEntity()) || foreignGarrison(level, event.getEntity()))) {
            event.setCanceled(true);   // H15b: atacante de una batalla que ya terminó, o guarnición de una aldea que cambió de facción
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk()
                && !com.ale.warbandcraft.invasion.Assault.keepCannon(level, event.getEntity())) {
            event.setCanceled(true);   // cañón de un asedio que ya terminó
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk()
                && !com.ale.warbandcraft.invasion.Hosts.keepSoldier(level, event.getEntity())) {
            event.setCanceled(true);   // H23: de una hueste cuya batalla ya terminó (lo que quedaba ya se contó)
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk() && !Invasions.keepInvader(level, event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk()
                && event.getEntity() instanceof com.talhanation.recruits.entities.AbstractRecruitEntity s) {
            com.ale.warbandcraft.invasion.SiegeDefense.releaseStale(level, s);   // se sumó a una defensa que terminó descargada
            com.ale.warbandcraft.invasion.AiWars.releaseStale(level, s);   // H23: se metió en una batalla que ya terminó
        }
    }

    /**
     * H15b: un soldado de guarnición de una aldea/castillo que ahora es de otra facción (se la tomaron mientras estaba
     * descargada): ya no tiene casa. Los invasores, patrullas y ejércitos del campo se manejan aparte.
     */
    private static boolean foreignGarrison(ServerLevel level, net.minecraft.world.entity.Entity e) {
        var pd = e.getPersistentData();
        if (!com.ale.warbandcraft.village.VillageManager.isGarrison(e) || !pd.contains(com.ale.warbandcraft.village.VillageManager.TAG_VILLAGE)) return false;
        if (pd.contains(Invasions.TAG_INVADER) || pd.contains(com.ale.warbandcraft.invasion.AiWars.TAG_AI_ATTACK)
                || pd.getBoolean(com.ale.warbandcraft.invasion.Ambush.TAG_FIELD)) return false;
        var v = com.ale.warbandcraft.village.VillageData.get(level).at(net.minecraft.core.BlockPos.of(pd.getLong(com.ale.warbandcraft.village.VillageManager.TAG_VILLAGE)));
        return v != null && !v.isConquered() && !v.faction().toString().equals(pd.getString(com.ale.warbandcraft.village.VillageManager.TAG_FACTION));
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        com.ale.warbandcraft.invasion.WarReports.onLogin(player);   // lo que pasó mientras no estabas
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (InvasionData.Invasion inv : InvasionData.get(level).all()) {
                if (com.ale.warbandcraft.faction.Allies.same(inv.owner, player.getUUID()) && inv.state == InvasionData.State.MARCHING) Invasions.warn(level, inv);
            }
            for (InvasionData.Host h : InvasionData.get(level).hosts()) {   // H23
                if (com.ale.warbandcraft.faction.Allies.same(h.owner, player.getUUID())) com.ale.warbandcraft.invasion.Hosts.warn(level, h);
            }
        }
    }
}
