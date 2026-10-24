package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.FactionClaims;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsClaimManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido de Ale: las aldeas de facción están reclamadas enteras por su facción y ahí nadie rompe nada con la mano; TNT
 * sí. Si la toma otra facción de la IA, el claim pasa a esa; si la conquista un jugador, la maneja él.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class ClaimGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(8, 1, 8);

    @GameTest(template = TEMPLATE)
    public static void factionVillageClaimedAndProtected(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<Faction> factions = FactionManager.INSTANCE.ordered();
        check(factions.size() >= 2, "hacen falta dos facciones");
        Faction f = factions.get(0), g = factions.get(1);
        BlockPos center = h.absolutePos(CENTER);
        VillageData data = VillageData.get(level);
        VillageData.Village v = new VillageData.Village(center, f.id(), 0);
        data.add(v);
        FactionClaims fc = FactionClaims.get(level);
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        try {
            // Toda la aldea: una muralla a 40 del centro queda adentro, con margen
            TownData.get(level).getOrCreate(center).ringRadius = 40;
            FactionClaims.sync(level, v);
            Set<Long> area = fc.chunksOf(center);
            int reach = 40 + FactionClaims.MARGIN - 1;
            for (BlockPos corner : new BlockPos[]{center.offset(reach, 0, reach), center.offset(-reach, 0, -reach),
                    center.offset(reach, 0, -reach), center.offset(-reach, 0, reach)}) {
                check(area.contains(new ChunkPos(corner).toLong()), "la muralla queda fuera de la zona: " + corner.toShortString());
            }
            check(m != null, "Recruits no tiene claims");
            RecruitsClaim claim = m.getClaim(fc.claimOf(center));
            check(claim != null && !claim.isRemoved, "no se creó el claim de la facción");
            check(f.teamName().equals(claim.getOwnerFactionStringID()), "el claim no es de la facción: " + claim.getOwnerFactionStringID());
            check(claim.isAdmin && !claim.isBlockBreakingAllowed() && claim.isBlockPlacementAllowed() && claim.isBlockInteractionAllowed(),
                    "permisos del claim mal puestos");
            check(claim.containsChunk(new ChunkPos(center.offset(reach, 0, reach))), "el claim no tiene toda la zona");
            check(claim.getPlayerInfo() != null && FactionClaims.leaderOf(f).equals(claim.getPlayerInfo().getUUID()), "claim sin jugador");

            // Con la mano no se rompe; con TNT sí
            BlockPos block = h.absolutePos(new BlockPos(3, 2, 3));
            level.setBlockAndUpdate(block, Blocks.DIRT.defaultBlockState());
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("claim-test".getBytes()), "claim-test"));
            player.setGameMode(GameType.SURVIVAL);
            check(FactionClaims.blocksBreaking(level, player, block), "la protección propia no frena al jugador");
            player.gameMode.destroyBlock(block);
            check(level.getBlockState(block).is(Blocks.DIRT), "el jugador rompió un bloque de la aldea con la mano");
            level.explode(null, block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
            check(level.getBlockState(block).isAir(), "la TNT no rompió el bloque");

            // La toma otra facción de la IA: el claim pasa a esa
            VillageData.Village taken = new VillageData.Village(center, g.id(), 0);
            data.add(taken);
            FactionClaims.sync(level, taken);
            claim = m.getClaim(fc.claimOf(center));
            check(claim != null && g.teamName().equals(claim.getOwnerFactionStringID()), "el claim no pasó a la facción que la tomó");

            // La conquista un jugador: la zona de la facción se suelta y se puede romper
            UUID id = fc.claimOf(center);
            VillageData.Village mine = taken.withOwner(player.getUUID());
            data.add(mine);
            FactionClaims.sync(level, mine);
            check(fc.chunksOf(center).isEmpty(), "una aldea conquistada sigue protegida por la facción");
            RecruitsClaim old = m.getClaim(id);
            check(old == null || old.isRemoved, "quedó el claim de la facción en una aldea conquistada");
            level.setBlockAndUpdate(block, Blocks.DIRT.defaultBlockState());
            check(!FactionClaims.blocksBreaking(level, player, block), "en una aldea conquistada no deja romper");
            player.gameMode.destroyBlock(block);
            check(level.getBlockState(block).isAir(), "el dueño no pudo romper en su aldea");
        } finally {
            fc.release(level, center);
            TownData.get(level).remove(center);
            data.remove(center);
        }
        h.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
