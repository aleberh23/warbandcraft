package com.ale.warbandcraft.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Busca el Campamento del jugador más cercano a una posición. */
public final class CampFinder {
    public static int RADIUS = CampBlockEntity.WORK_RADIUS;

    private CampFinder() {}

    @Nullable
    public static CampBlockEntity findNearest(ServerLevel level, BlockPos center, UUID player) {
        return findNearest(level, center, player, RADIUS);
    }

    @Nullable
    public static CampBlockEntity findNearest(ServerLevel level, BlockPos center, UUID player, int radius) {
        // H16: en el territorio de una aldea tuya, manda el Campamento de la aldea (aunque esté lejos)
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.Territory.villageAt(level, center, player);
        if (v != null) {
            CampBlockEntity vc = com.ale.warbandcraft.village.VillageStorehouse.camp(level, v);
            if (vc != null && vc.canBeUsedBy(player)) return vc;
        }
        CampBlockEntity best = null;
        double bestDist = (double) radius * radius;

        int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof CampBlockEntity camp && camp.canBeUsedBy(player)) {
                        double d = be.getBlockPos().distSqr(center);
                        if (d <= bestDist) {
                            bestDist = d;
                            best = camp;
                        }
                    }
                }
            }
        }
        return best;
    }
}
