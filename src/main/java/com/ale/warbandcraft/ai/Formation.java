package com.ale.warbandcraft.ai;

import com.ale.warbandcraft.util.Ground;
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
        return out;
    }

    /** Todos los destinos iguales (formación Libre): conviene repartirlos. */
    public static boolean allSame(List<Vec3> targets) {
        for (Vec3 t : targets) if (t.distanceToSqr(targets.get(0)) > 0.01) return false;
        return true;
    }
}
