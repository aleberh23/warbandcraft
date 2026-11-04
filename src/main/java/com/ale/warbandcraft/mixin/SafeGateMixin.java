package com.ale.warbandcraft.mixin;

import com.talhanation.recruits.entities.ai.navigation.RecruitsDoorInteractGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crash del 4/11 (asedio): un soldado de Recruits abrió un portón y, al cerrarlo, ya lo habían roto a golpes
 * ({@code Works.tickGates}): Recruits le pide {@code open} al aire y el servidor se cae. Si ya no hay un bloque con
 * {@code open} ahí, no se hace nada.
 */
@Mixin(value = RecruitsDoorInteractGoal.class, remap = false)
public abstract class SafeGateMixin {
    @Inject(method = "useGate", at = @At("HEAD"), cancellable = true)
    private void warbandcraft$safeGate(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (state == null || level == null || pos == null || !state.hasProperty(BlockStateProperties.OPEN)
                || !level.getBlockState(pos).hasProperty(BlockStateProperties.OPEN)) ci.cancel();
    }
}
