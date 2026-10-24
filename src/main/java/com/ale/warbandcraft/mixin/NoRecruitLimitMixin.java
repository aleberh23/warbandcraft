package com.ale.warbandcraft.mixin;

import com.talhanation.recruits.world.RecruitsPlayerUnitManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Sin tope de soldados: Recruits limita por jugador ({@code MaxRecruitsForPlayer}) y por facción
 * ({@code maxNPCs}, {@code maxNPCsPerPlayer}). Toda contratación (recluta, noble, cambio de dueño) pasa por
 * {@code canPlayerRecruit}; {@code getRemainingRecruitSlots} es lo que ve la pantalla.
 */
@Mixin(value = RecruitsPlayerUnitManager.class, remap = false)
public abstract class NoRecruitLimitMixin {
    private static final int UNLIMITED = 1_000_000_000;

    @Inject(method = "canPlayerRecruit", at = @At("HEAD"), cancellable = true)
    private void warbandcraft$canRecruit(String team, UUID player, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "getRemainingRecruitSlots", at = @At("HEAD"), cancellable = true)
    private void warbandcraft$remaining(String team, UUID player, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(UNLIMITED);
    }
}
