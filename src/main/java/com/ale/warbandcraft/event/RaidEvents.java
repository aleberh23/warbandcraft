package com.ale.warbandcraft.event;

import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageEconomy;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;

/** Eventos de H3: empezar ataques, juntar el botín de los caídos, victoria, impuestos y refuerzos. */
public class RaidEvents {

    /** Alguien lastimó a un soldado de guarnición: si fue un jugador o su tropa, empieza el ataque. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onHurt(LivingHurtEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof AbstractRecruitEntity soldier)) return;
        if (!(soldier.level() instanceof ServerLevel level) || !VillageManager.isGarrison(soldier)) return;
        Entity source = event.getSource().getEntity(); // el que disparó la flecha, no la flecha
        UUID player = RaidManager.responsiblePlayer(source);
        if (player == null || !(source instanceof LivingEntity attacker)) return;
        if (com.ale.warbandcraft.invasion.AiWars.onHit(level, soldier, player, attacker)) return;   // H15b: te metiste en una batalla entre facciones
        if (com.ale.warbandcraft.invasion.Patrols.isPatrol(soldier)) {   // H15b: pegarle a una patrulla no ataca la aldea
            com.ale.warbandcraft.invasion.Patrols.onHit(level, soldier, player);
            return;
        }
        RaidManager.onGarrisonAttacked(level, soldier, attacker, player);
    }

    /** Un soldado de guarnición cayó en medio de un ataque: su equipo pasa al botín. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof AbstractRecruitEntity soldier)) return;
        if (!(soldier.level() instanceof ServerLevel level) || !VillageManager.isGarrison(soldier)) return;
        RaidManager.onGarrisonDeath(level, soldier);
        com.ale.warbandcraft.battle.BattleLoot.onDeath(level, soldier, event.getSource().getEntity());   // H23: botín de batalla
        com.ale.warbandcraft.invasion.Patrols.dropEmeralds(level, soldier, event.getSource().getEntity());   // patrullas: esmeraldas
        com.ale.warbandcraft.dungeon.Bandits.dropEmerald(level, soldier, event.getSource().getEntity());   // bandidos de guarida: a veces 1
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        long t = level.getGameTime();
        try {
            if (t % 20 == 0) RaidManager.tick(level);
            if (t % 20 == 3) com.ale.warbandcraft.battle.BattleLoot.tick(level);   // H23: batallas que terminaron
            if (t % 20 == 5) VillageEconomy.checkNewDay(level);
            if (t % 100 == 10) CampRegistry.get(level).flushPending(level);
            // H10/H16: el Campamento de tus aldeas (en las pruebas lo maneja cada una a mano)
            if (t % 100 == 60 && !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer)) VillageStorehouse.tick(level);
            // H18: los trabajadores de tus aldeas: equipo, comida, cama, lo que juntan y la veta del minero
            if (t % com.ale.warbandcraft.village.WorkerSupply.EVERY_TICKS == 35
                    && !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer)) com.ale.warbandcraft.village.WorkerSupply.tick(level);
            if (t % 600 == 15) VillageEconomy.reinforceAll(level);
            // H22: las casas automáticas (Housing.autoTick) quedaron apagadas: se plantan a mano en Gestión → Construir
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Error en el tick de aldeas/ataques", e);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RaidManager.clear();
    }
}
