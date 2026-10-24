package com.ale.warbandcraft.event;

import com.ale.warbandcraft.tutorial.Tutorial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Salón del tutorial: se arma en los mundos nuevos y su zona no se puede romper ni construir. */
public class TutorialEvents {
    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent event) {
        Tutorial.onAboutToStart(event.getServer());
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        Tutorial.onStarted(event.getServer());
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        Tutorial.onStopped();
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            Tutorial.tick(level);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (Tutorial.isProtected(event.getLevel(), event.getPos()) && !Tutorial.exempt(event.getPlayer())) {
            event.setCanceled(true);
            Tutorial.warn(event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (Tutorial.isProtected(event.getLevel(), event.getPos()) && !Tutorial.exempt(event.getEntity())) {
            event.setCanceled(true);
            Tutorial.warn(event.getEntity());
        }
    }

    /** Usar un ítem contra un bloque (poner, encender, abonar, labrar...): no. Usar el bloque (atriles) sí. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        BlockPos pos = event.getPos();
        Direction face = event.getFace();
        if (event.getItemStack().isEmpty() || Tutorial.exempt(event.getEntity())) return;
        if (Tutorial.isProtected(event.getLevel(), pos) || face != null && Tutorial.isProtected(event.getLevel(), pos.relative(face))) {
            event.setUseItem(Event.Result.DENY);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBucket(FillBucketEvent event) {
        if (Tutorial.exempt(event.getEntity()) || !(event.getTarget() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (Tutorial.isProtected(event.getLevel(), hit.getBlockPos()) || Tutorial.isProtected(event.getLevel(), hit.getBlockPos().relative(hit.getDirection()))) {
            event.setCanceled(true);
            Tutorial.warn(event.getEntity());
        }
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(p -> Tutorial.isProtected(event.getLevel(), p));
    }

    @SubscribeEvent
    public void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (Tutorial.isProtected(event.getLevel(), event.getPos())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onPiston(PistonEvent.Pre event) {
        Direction dir = event.getDirection();
        for (int k = 0; k <= 13; k++) {
            if (Tutorial.isProtected(event.getLevel(), event.getPos().relative(dir, k))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** /spawn: anota el último golpe recibido o dado (getEntity = el dueño de la flecha). */
    @SubscribeEvent
    public void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        com.ale.warbandcraft.command.SpawnCommand.onHurt(event.getEntity(), event.getSource().getEntity());
    }

    /** Vanilla te pone arriba del techo: adentro. */
    @SubscribeEvent
    public void onLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) Tutorial.fixSpawn(p);
    }

    @SubscribeEvent
    public void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered() && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) Tutorial.fixSpawn(p);
    }

    @SubscribeEvent
    public void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        com.ale.warbandcraft.command.SpawnCommand.forget(event.getEntity());
    }

    /** Endermen, ravagers y compañía no tocan nada en la zona. */
    @SubscribeEvent
    public void onMobGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() != null && Tutorial.isProtected(event.getEntity().level(), event.getEntity().blockPosition())) {
            event.setResult(Event.Result.DENY);
        }
    }
}
