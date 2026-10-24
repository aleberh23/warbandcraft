package com.ale.warbandcraft.event;

import com.ale.warbandcraft.village.Territory;
import com.talhanation.recruits.ClaimEvent;
import com.talhanation.recruits.world.RecruitsClaim;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Antes que Villager Workers: un claim de Recruits sin jugador hace que Workers tire NullPointerException al
 * actualizarse (le pasa ese jugador a las zonas de trabajo de adentro), y Recruits no termina de guardar el cambio.
 * Se le pone el líder de su facción.
 */
public class ClaimFixEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClaimUpdated(ClaimEvent.Updated event) {
        RecruitsClaim claim = event.getClaim();
        if (claim == null || claim.getOwnerFaction() == null) return;
        Territory.ensurePlayer(event.getLevel(), claim, claim.getOwnerFaction().getTeamLeaderUUID());
    }
}
