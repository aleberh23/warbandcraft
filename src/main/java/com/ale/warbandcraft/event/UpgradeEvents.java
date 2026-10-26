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

    /** Un portón de muralla no lo abre con la mano un enemigo de la aldea (ni un jugador ajeno en una aldea de otro). */
    @SubscribeEvent
    public void onUseGate(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) return;
        if (!com.ale.warbandcraft.upgrade.Works.gateLocked(level, event.getPos(), event.getEntity())) return;
        event.setCanceled(true);
        event.getEntity().displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.warbandcraft.gate_locked")
                .withStyle(net.minecraft.ChatFormatting.RED), true);
    }

    /** Pedido de Ale: muralla rota entera a mano deja de ser muralla; bloques puestos arriba pasan a serlo. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onBreakWall(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer p) {
            com.ale.warbandcraft.upgrade.WallRepair.onPlayerBreak(level, p, event.getPos(), event.getState());
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onPlaceOnWall(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            com.ale.warbandcraft.upgrade.WallRepair.onPlayerPlace(level, p, event.getPos(), event.getPlacedBlock());
        }
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
