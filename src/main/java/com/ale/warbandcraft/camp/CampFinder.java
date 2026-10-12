package com.ale.warbandcraft.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Busca el Campamento del jugador más cercano a una posición. */
public final class CampFinder {
    public static final int RADIUS = 24;

    private CampFinder() {}

    @Nullable
    public static CampBlockEntity findNearest(ServerLevel level, BlockPos center, UUID player) {
        CampBlockEntity best = null;
        double bestDist = (double) RADIUS * RADIUS;

        int minCx = (center.getX() - RADIUS) >> 4, maxCx = (center.getX() + RADIUS) >> 4;
        int minCz = (center.getZ() - RADIUS) >> 4, maxCz = (center.getZ() + RADIUS) >> 4;

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
