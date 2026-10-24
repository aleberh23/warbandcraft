package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.village.Tavern;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** H19: la taberna: los reclutas que se ven (se acomodan, no se guardan) y contratarlos con click derecho. */
public class TavernEvents {
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getServer() instanceof GameTestServer) return;
        try {
            if (level.getGameTime() % com.ale.warbandcraft.village.Popularity.EVERY_TICKS == 75) com.ale.warbandcraft.village.Popularity.tick(level);   // H21
            if (level.getGameTime() % Tavern.KEEP_TICKS == 7) Tavern.keepTick(level);   // reclutas adentro, Tabernero en su lugar
            if (level.getGameTime() % 40 == 21) com.ale.warbandcraft.village.WorldTaverns.tick(level);   // tabernas del mundo
            if (level.getGameTime() % 200 == 33) com.ale.warbandcraft.village.Wanderers.tick(level);   // reclutas errantes
            if (level.getGameTime() % 1200 == 333) com.ale.warbandcraft.village.Wanderers.spawnNearPlayers(level);
            if (level.getGameTime() % Tavern.EVERY_TICKS != 45) return;
            Tavern.tick(level);
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Error en el tick de las tabernas", e);
        }
    }

    /** Los de la taberna son una copia de los datos: no se guardan (se vuelven a armar al cargar). */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || !event.loadedFromDisk()) return;
        if (Tavern.isVisual(event.getEntity()) || com.ale.warbandcraft.village.WorldTaverns.onLoad(event.getEntity())) event.setCanceled(true);
        else if (event.getEntity() instanceof AbstractRecruitEntity r && com.ale.warbandcraft.village.Wanderers.onLoad(r)) event.setCanceled(true);
    }

    /** Tabernas del mundo: un guardia muerto tarda en volver, un mercenario muerto se pierde. */
    @SubscribeEvent
    public void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!event.isCanceled() && event.getEntity().level() instanceof ServerLevel level) {
            com.ale.warbandcraft.village.WorldTaverns.onDeath(level, event.getEntity());
        }
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (handle(event.getEntity(), event.getTarget(), event.getHand())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (handle(event.getEntity(), event.getTarget(), event.getHand())) event.setCanceled(true);
    }

    /** True si era un recluta de la taberna (el click no sigue a Recruits: nada de pantalla de contratar). */
    private static boolean handle(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.Entity target, InteractionHand hand) {
        if (!Tavern.isVisual(target)) return false;
        if (hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp && target instanceof AbstractRecruitEntity r) {
            Component msg = Tavern.hire(sp, r);
            if (msg != null) sp.displayClientMessage(msg, true);
        }
        return true;
    }
}
