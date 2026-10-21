package com.ale.warbandcraft.event;

import com.ale.warbandcraft.upgrade.Upgrades;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Listeners de H12 (mejoras visibles): las obras avanzan cada tick, el aldeano constructor no comercia y uno que
 * quedó sin obra (de un guardado viejo) no se carga.
 */
public class UpgradeEvents {
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        Upgrades.tick(level);
        if (level.getGameTime() % 10 == 3) com.ale.warbandcraft.upgrade.Works.tickGates(level);   // H22c: portones
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget().getPersistentData().contains(Upgrades.TAG_BUILDER)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getTarget().getPersistentData().contains(Upgrades.TAG_BUILDER)) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.loadedFromDisk() && Upgrades.isOrphanBuilder(level, event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
