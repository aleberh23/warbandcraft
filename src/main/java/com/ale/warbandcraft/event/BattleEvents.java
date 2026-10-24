package com.ale.warbandcraft.event;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.raid.RaidManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;

/** Eventos de H9: rendirse en vez de morir, nadie ataca a un rendido, ataques guardados y rendidos sueltos. */
public class BattleEvents {
    /** Cada cuánto se revisan los rendidos cuyo ataque ya no existe (5 s). */
    private static int ORPHAN_CHECK_TICKS = 100;
    /** Cada cuánto sangran los rendidos (1 s). */
    private static int BLEED_TICKS = 20;

    /** Golpe mortal en medio de un ataque: puede rendirse (se cancela el golpe). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDamage(LivingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof AbstractRecruitEntity soldier)) return;
        if (!(soldier.level() instanceof ServerLevel level) || Soldiers.isWorker(soldier)) return;
        if (Surrender.tryOnLethalHit(level, soldier, event.getAmount())) event.setCanceled(true);
    }

    /** Nadie elige como objetivo a un rendido. */
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewTarget() != null && Surrender.is(event.getNewTarget())) event.setCanceled(true);
    }

    /** Un rendido que se vuelve a cargar sigue quieto (el modificador de velocidad no se guarda). */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractRecruitEntity soldier)) return;
        if (Surrender.is(soldier)) Surrender.freeze(soldier);
    }

    /** Rendidos cuyo ataque terminó mientras no estaban cargados: se levantan o se los lleva la guarnición. */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % BLEED_TICKS == 7 && !level.players().isEmpty()) {
            for (AbstractRecruitEntity s : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                    r -> r.isAlive() && Surrender.is(r))) {
                Surrender.bleed(level, s);
            }
        }
        if (level.getGameTime() % ORPHAN_CHECK_TICKS != 35) return;
        try {
            for (AbstractRecruitEntity s : new ArrayList<>(level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                    r -> r.isAlive() && Surrender.is(r)))) {
                BlockPos center = Surrender.raidCenter(s);
                RaidManager.Raid raid = center == null ? null : RaidManager.get(level, center);
                if (raid != null && raid.state == RaidManager.State.FIGHTING) {
                    if (raid.surrendered.add(s.getUUID())) RaidManager.changed();
                } else if (raid != null) {
                    Surrender.standUp(s);          // ataque ganado esperando decisión: ya no se toman prisioneros
                } else {
                    RaidManager.capture(level, s); // ataque perdido o terminado
                }
            }
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Error revisando rendidos", e);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        RaidManager.load(event.getServer());
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RaidManager.onLogout(event.getEntity().getUUID());
    }
}
