package com.ale.warbandcraft.ai;

import com.ale.warbandcraft.util.Ground;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Reparto de destinos. Mandar a varios soldados al mismo punto exacto los amontona: se empujan entre
 * ellos y ninguno termina de llegar (se "traban"). Con la formación Libre se reparten en una grilla
 * apretada alrededor del click, cada uno sobre el piso.
 */
public final class Formation {
    /** Separación entre soldados de la grilla (bloques). */
    public static double SPACING = 1.75;

    private Formation() {}

    /** {@code count} puntos en grilla alrededor de {@code center} (el primero, el más cercano al centro). */
    public static List<Vec3> spread(ServerLevel level, Vec3 center, int count) {
        List<Vec3> out = new ArrayList<>(count);
        if (count <= 1) {
            out.add(center);
            return out;
        }
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil(count / (double) cols);
        for (int i = 0; i < count; i++) {
            int row = i / cols, col = i % cols;
            double x = center.x + (col - (cols - 1) / 2.0) * SPACING;
            double z = center.z + (row - (rows - 1) / 2.0) * SPACING;
            Integer y = Ground.surfaceY(level, (int) Math.floor(x), (int) Math.floor(z), (int) Math.floor(center.y), 3, 6);
            out.add(new Vec3(x, y != null ? y : center.y, z));
        }
        // Arriba de una torre o un muro la grilla se sale del borde y cae al piso: esos van a un lugar libre a la misma
        // altura que el click (pedido de Ale: mandados a una torre, suben todos)
        List<BlockPos> free = new ArrayList<>();
        BlockPos c = BlockPos.containing(center);
        for (int dx = -TOP_RADIUS; dx <= TOP_RADIUS; dx++) {
            for (int dz = -TOP_RADIUS; dz <= TOP_RADIUS; dz++) {
                BlockPos q = c.offset(dx, 0, dz);
                if (!q.equals(c) && Ground.canStand(level, q)) free.add(q);
            }
        }
        free.sort(java.util.Comparator.comparingDouble(q -> q.distSqr(c)));
        int next = 0;
        for (int i = 0; i < out.size(); i++) {
            if (Math.abs(out.get(i).y - center.y) <= 1) continue;
            out.set(i, next < free.size() ? Vec3.atBottomCenterOf(free.get(next++)) : center);
        }
        return out;
    }

    /** Hasta dónde se buscan lugares a la altura del click cuando la grilla se cae del borde. */
    public static int TOP_RADIUS = 4;

    /** Todos los destinos iguales (formación Libre): conviene repartirlos. */
    public static boolean allSame(List<Vec3> targets) {
        for (Vec3 t : targets) if (t.distanceToSqr(targets.get(0)) > 0.01) return false;
        return true;
    }
}
