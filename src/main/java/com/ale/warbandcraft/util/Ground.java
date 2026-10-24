package com.ale.warbandcraft.util;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Buscar piso donde pararse, bloque por bloque. Sirve del lado servidor (dónde aparece o a dónde salta
 * un soldado) y del cliente (dibujar la jurisdicción en la vista aérea: el cliente no tiene todos los
 * mapas de alturas, y el que falta da la bedrock).
 */
public final class Ground {
    private Ground() {}

    /** Bloque donde se puede estar parado: abajo hay piso (no hojas), y los pies y la cabeza están libres. */
    public static boolean canStand(Level level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        return isFree(level, p) && isFree(level, p.above()) && level.getFluidState(p).isEmpty() && isFloor(level, p.below());
    }

    private static boolean isFree(BlockGetter level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static boolean isFloor(BlockGetter level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.is(BlockTags.LEAVES) && !s.getCollisionShape(level, p).isEmpty();
    }

    /**
     * La altura de los pies sobre el piso en la columna (x, z), buscando de {@code yHint + up} hacia
     * abajo hasta {@code yHint - down}. Toma el agua como piso (para dibujar sobre un lago). Null si no hay.
     */
    @Nullable
    public static Integer surfaceY(Level level, int x, int z, int yHint, int up, int down) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = yHint + up; y >= yHint - down; y--) {
            p.set(x, y, z);
            if (!level.isLoaded(p)) return null;
            if (!isFree(level, p) || !level.getFluidState(p).isEmpty()) continue;
            BlockPos below = p.below();
            if (isFloor(level, below) || !level.getFluidState(below).isEmpty()) return y;
        }
        return null;
    }

    /** El lugar libre más cercano a {@code center} (hasta {@code radius} bloques), o null. */
    @Nullable
    public static Vec3 nearestStandable(Level level, BlockPos center, int radius) {
        int[] dys = {0, 1, -1, 2, -2};
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy : dys) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (canStand(level, p)) return Vec3.atBottomCenterOf(p);
                    }
                }
            }
        }
        return null;
    }
}
