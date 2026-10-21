package com.ale.warbandcraft.event;

import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavGrid;
import com.ale.warbandcraft.nav.RouteGoal;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * La malla de navegación ({@code nav/}): a cada soldado de Recruits se le suma el objetivo que lo lleva por un camino;
 * los cambios de bloques borran lo medido de esa columna; los botes de los soldados que quedaron sueltos se borran.
 */
public class NavEvents {
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        if (event.getEntity() instanceof AbstractRecruitEntity s) {
            s.goalSelector.addGoal(0, new RouteGoal(s));
        } else if (event.loadedFromDisk() && event.getEntity() instanceof Boat b && (b.getPersistentData().getBoolean(Mover.TAG_BOAT) || b.getPersistentData().getBoolean(com.ale.warbandcraft.ai.Escort.TAG_BOAT))
                && b.getPassengers().isEmpty()) {
            event.setCanceled(true);
        }
    }

    /** Cualquier cambio de bloque que avisa a los vecinos (romper, poner, puertas, explosiones, obras). */
    @SubscribeEvent
    public void onNeighbor(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) NavGrid.invalidate(level, event.getPos());
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            for (var p : event.getAffectedBlocks()) NavGrid.invalidate(level, p);
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level && level.getGameTime() % 600 == 0) {
            Mover.cleanup(level);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        NavGrid.clear();
        Mover.clear();
        com.ale.warbandcraft.ai.Escort.clear();
    }
}
