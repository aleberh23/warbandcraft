package com.ale.warbandcraft.mixin;

import com.ale.warbandcraft.faction.FactionManager;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Bug de Ale (4/11): conquistó una aldea de Valdren y a los 2 s Recruits empezó SU asedio al claim ("is now under siege
 * by [wb_valdren]"): Recruits pone ENEMY entre equipos que se pelean ({@code RecruitEvents.setTeamsAsEnemies}) y las
 * facciones de la IA están registradas en Recruits ({@code RecruitsFactionBridge}), así que los soldados de Valdren que
 * quedaban ahí (rendidos, presos, patrullas) "capturaron" el claim: quedó a nombre de Valdren, sin poder abrir el
 * Campamento ni los portones. Los asedios de las facciones de la IA son los nuestros: para Recruits, sus soldados no
 * cuentan ni como atacantes ni como defensores de un claim.
 */
@Mixin(value = ClaimEvents.class, remap = false)
public abstract class NoNpcClaimSiegeMixin {
    @Redirect(method = "classifyEntities", at = @At(value = "INVOKE",
            target = "Lcom/talhanation/recruits/world/RecruitsFactionManager;getFactionByStringID(Ljava/lang/String;)Lcom/talhanation/recruits/world/RecruitsFaction;"))
    private RecruitsFaction warbandcraft$skipNpcFactions(RecruitsFactionManager manager, String team) {
        if (FactionManager.INSTANCE.isFactionTeam(team)) return null;
        return manager.getFactionByStringID(team);
    }
}
