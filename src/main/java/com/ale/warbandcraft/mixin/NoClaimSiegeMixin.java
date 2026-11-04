package com.ale.warbandcraft.mixin;

import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Los asedios de claims de Recruits están apagados (pedido de Ale, 4/11: "el asedio ganado es lo que te da el claim").
 * Recruits pone ENEMY solo entre equipos que se pegan y con unos cuantos soldados enemigos adentro de un claim lo
 * "capturaba" (así una aldea conquistada a Valdren quedó a nombre de Valdren, sin poder abrir el Campamento ni los
 * portones). Para Recruits nadie es atacante ni defensor de un claim: sus asedios no empiezan y los que estaban en curso
 * se cortan. Las aldeas se ganan con el asedio de Warband ({@code RaidManager}, también entre jugadores).
 */
@Mixin(value = ClaimEvents.class, remap = false)
public abstract class NoClaimSiegeMixin {
    @Inject(method = "classifyEntities", at = @At("HEAD"), cancellable = true)
    private void warbandcraft$noClaimSieges(List<LivingEntity> entities, RecruitsClaim claim, List<LivingEntity> attackers,
                                            List<LivingEntity> defenders, CallbackInfo ci) {
        ci.cancel();
    }
}
