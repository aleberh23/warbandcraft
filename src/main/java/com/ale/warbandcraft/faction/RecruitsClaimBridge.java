package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsClaimManager;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * Cuando el jugador conquista una aldea, reclama el terreno a su nombre en el sistema de "claims" de
 * Recruits: le crea una facción propia (gratis) si todavía no tiene una, y le pasa los chunks de la
 * aldea. Esto le da la protección normal de un claim de Recruits (bloques, cofres, etc.) sobre su
 * territorio conquistado.
 *
 * No se toca la diplomacia de Recruits (queda NEUTRAL): el asedio automático de Recruits a los claims
 * depende de que haya relación ENEMY entre facciones, así que esto no se activa solo por reclamar.
 * La guerra y los ataques siguen siendo los nuestros ({@link com.ale.warbandcraft.raid.RaidManager} / {@link Relations}).
 */
public final class RecruitsClaimBridge {
    /** Radio en chunks alrededor de la campana que se reclama al conquistar. */
    public static int CLAIM_RADIUS_CHUNKS = 2;

    private RecruitsClaimBridge() {}

    public static void claimVillage(ServerLevel level, VillageData.Village village, ServerPlayer player) {
        RecruitsFaction faction = ensurePlayerFaction(level, player);
        RecruitsClaimManager claims = ClaimEvents.recruitsClaimManager;
        if (faction == null || claims == null) return;

        ChunkPos center = new ChunkPos(village.center());
        RecruitsClaim claim = claims.getClaim(center);
        if (claim != null) {
            claim.setOwnerFaction(faction);
        } else {
            claim = new RecruitsClaim(village.center().toShortString(), faction);
            claim.setCenter(center);
            for (int dx = -CLAIM_RADIUS_CHUNKS; dx <= CLAIM_RADIUS_CHUNKS; dx++) {
                for (int dz = -CLAIM_RADIUS_CHUNKS; dz <= CLAIM_RADIUS_CHUNKS; dz++) {
                    ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
                    if (claims.getClaim(pos) == null) claim.addChunk(pos);
                }
            }
        }
        // Sin jugador, Villager Workers se cae cada vez que se actualiza el claim (ver Territory.ensurePlayer)
        claim.setPlayer(new com.talhanation.recruits.world.RecruitsPlayerInfo(player.getUUID(), player.getGameProfile().getName(), faction));
        claims.addOrUpdateClaim(level, claim);
        claims.save(level.getServer().overworld());
        WarbandCraft.LOGGER.info("Aldea {} reclamada por {} (facción Recruits {})",
                village.center().toShortString(), player.getName().getString(), faction.getStringID());
    }

    /** La facción de Recruits del jugador: la que ya tenga, o una nueva sin costo (solo para él). */
    @Nullable
    private static RecruitsFaction ensurePlayerFaction(ServerLevel level, ServerPlayer player) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        if (manager == null) return null;

        if (player.getTeam() != null) {
            RecruitsFaction existing = manager.getFactionByStringID(player.getTeam().getName());
            if (existing != null) return existing;
        }

        String teamId = player.getUUID().toString().replace("-", "");
        if (manager.getFactionByStringID(teamId) == null) {
            FactionEvents.createTeam(false, player, level, teamId,
                    "Warband de " + player.getName().getString(), player.getScoreboardName(),
                    Items.BROWN_BANNER.getDefaultInstance(), ChatFormatting.WHITE, (byte) 0);
        }
        return manager.getFactionByStringID(teamId);
    }
}
