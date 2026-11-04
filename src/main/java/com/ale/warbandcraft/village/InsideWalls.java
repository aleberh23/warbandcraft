package com.ale.warbandcraft.village;

import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Pedido de Ale: la guarnición, el cuartel y los aldeanos se quedan adentro de las murallas esperando al enemigo.
 * "Adentro" se mide por ángulo: alrededor del centro, en {@link #BINS} tajadas, la distancia a la muralla más cercana de
 * cada una (la dibujada de H22c, o el anillo de H12; un castillo, su forma). Una tajada sin muralla (un portón, un
 * hueco) toma la de sus vecinas. Sin muralla, "adentro" es la aldea (territorio o {@link Quarters#PATROL_RADIUS}).
 */
public final class InsideWalls {
    /** Tajadas de ángulo (de 5°) y cuánto más adentro de la muralla cuenta como adentro. */
    private static final int BINS = 72;
    public static double MARGIN = 1.5;
    /** Una muralla dibujada que cubre menos de esta parte de la vuelta no encierra nada: como sin muralla. */
    public static double MIN_COVER = 0.6;
    private static final long REFRESH = 20 * 30;

    private record Shape(long at, boolean walled, double[] radius) {}

    private static final Map<String, Shape> CACHE = new HashMap<>();

    private InsideWalls() {}

    public static void clear() {
        CACHE.clear();
    }

    /** La aldea tiene muralla (o empalizada) que la encierra. */
    public static boolean walled(ServerLevel level, VillageData.Village v) {
        return shape(level, v).walled();
    }

    /** {@code pos} está adentro de las murallas de la aldea (sin muralla: adentro de la aldea). */
    public static boolean inside(ServerLevel level, VillageData.Village v, BlockPos pos) {
        return inside(level, v, pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    public static boolean inside(ServerLevel level, VillageData.Village v, Vec3 pos) {
        return inside(level, v, pos.x, pos.z);
    }

    private static boolean inside(ServerLevel level, VillageData.Village v, double x, double z) {
        Shape s = shape(level, v);
        BlockPos c = v.center();
        double dx = x - (c.getX() + 0.5), dz = z - (c.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (!s.walled()) {
            if (Territory.get(level).contains(c, BlockPos.containing(x, c.getY(), z))) return true;
            return d <= Quarters.PATROL_RADIUS + 8.0;
        }
        return d <= s.radius()[bin(dx, dz)] - MARGIN;
    }

    /** Adentro, o arriba de la muralla misma (para los arqueros: el camino de ronda de piedra cuenta). */
    public static boolean insideOrOnWall(ServerLevel level, VillageData.Village v, BlockPos pos) {
        Shape s = shape(level, v);
        if (!s.walled()) return inside(level, v, pos);
        BlockPos c = v.center();
        double dx = pos.getX() - c.getX(), dz = pos.getZ() - c.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= s.radius()[bin(dx, dz)] + 1.0;
    }

    /** Hasta dónde llega "adentro" en la dirección de {@code pos} (sin muralla: el radio de patrulla). */
    public static double radiusToward(ServerLevel level, VillageData.Village v, Vec3 pos) {
        Shape s = shape(level, v);
        if (!s.walled()) return Quarters.PATROL_RADIUS;
        return s.radius()[bin(pos.x - (v.center().getX() + 0.5), pos.z - (v.center().getZ() + 0.5))] - MARGIN;
    }

    /**
     * Un punto adentro hacia donde está {@code from}: sobre la recta al centro, {@code back} bloques antes de la muralla
     * (para mandar adentro a alguien que quedó afuera).
     */
    public static Vec3 pointInside(ServerLevel level, VillageData.Village v, Vec3 from, double back) {
        Vec3 c = Vec3.atBottomCenterOf(v.center());
        Vec3 dir = new Vec3(from.x - c.x, 0, from.z - c.z);
        double len = dir.length();
        if (len < 1e-3) return c;
        double r = Math.max(0, Math.min(len, radiusToward(level, v, from) - back));
        return c.add(dir.scale(r / len));
    }

    /** Pedido de Ale: nadie camina por la empalizada: parado sobre troncos, cercos o almenas no es un lugar. */
    public static boolean onPalisade(ServerLevel level, BlockPos feet) {
        BlockState floor = level.getBlockState(feet.below());
        return floor.is(BlockTags.LOGS) || floor.is(BlockTags.FENCES) || floor.is(BlockTags.WALLS) || floor.is(BlockTags.FENCE_GATES);
    }

    /**
     * La distancia del centro a la muralla en cada tajada ({@link #binOf}), o null si no tiene muralla o es un castillo
     * (redondo). Para los asedios: una muralla dibujada no es un círculo.
     */
    public static double[] radii(ServerLevel level, VillageData.Village v) {
        if (v.castle()) return null;
        Shape s = shape(level, v);
        return s.walled() ? s.radius().clone() : null;
    }

    public static int binOf(double dx, double dz) {
        return bin(dx, dz);
    }

    private static int bin(double dx, double dz) {
        double a = Math.atan2(dz, dx);
        int b = (int) Math.floor((a + Math.PI) / (2 * Math.PI) * BINS);
        return Math.floorMod(b, BINS);
    }

    private static Shape shape(ServerLevel level, VillageData.Village v) {
        String key = level.dimension().location() + "@" + v.center().asLong();
        long now = level.getGameTime();
        Shape s = CACHE.get(key);
        if (s != null && now >= s.at() && now - s.at() < REFRESH) return s;
        s = measure(level, v, now);
        CACHE.put(key, s);
        return s;
    }

    private static Shape measure(ServerLevel level, VillageData.Village v, long now) {
        double[] r = new double[BINS];
        java.util.Arrays.fill(r, -1);
        BlockPos c = v.center();
        if (v.castle()) {
            com.ale.warbandcraft.invasion.Breach.Walls w = com.ale.warbandcraft.invasion.Breach.walls(level, v);
            java.util.Arrays.fill(r, w.inside() + MARGIN);
            return new Shape(now, true, r);
        }
        TownData.Town t = TownData.get(level).at(c);
        if (t == null) return new Shape(now, false, r);
        if (!t.defense.isEmpty()) {   // H22c: muralla dibujada
            for (long l : t.defense.keySet()) {
                BlockPos p = BlockPos.of(l);
                double dx = p.getX() + 0.5 - (c.getX() + 0.5), dz = p.getZ() + 0.5 - (c.getZ() + 0.5);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 3) continue;
                int b = bin(dx, dz);
                if (r[b] < 0 || d < r[b]) r[b] = d;
            }
            for (BlockPos g : t.gates) {   // los portones también cierran (no son un hueco)
                double dx = g.getX() + 0.5 - (c.getX() + 0.5), dz = g.getZ() + 0.5 - (c.getZ() + 0.5);
                double d = Math.sqrt(dx * dx + dz * dz);
                int b = bin(dx, dz);
                if (d >= 3 && (r[b] < 0 || d < r[b])) r[b] = d;
            }
            int covered = 0;
            for (double x : r) if (x > 0) covered++;
            if (covered >= BINS * MIN_COVER) {
                fill(r);
                return new Shape(now, true, r);
            }
            java.util.Arrays.fill(r, -1);
        }
        if (t.ringRadius > 0 && (t.ringWalls || t.has(Upgrade.PALISADE) || t.has(Upgrade.WALL))) {   // anillo de H12
            java.util.Arrays.fill(r, t.ringRadius);
            return new Shape(now, true, r);
        }
        return new Shape(now, false, r);
    }

    /** Las tajadas sin muralla (portones, huecos) toman la menor de las vecinas con muralla más cercanas. */
    private static void fill(double[] r) {
        double[] out = r.clone();
        for (int i = 0; i < BINS; i++) {
            if (r[i] > 0) continue;
            double left = -1, right = -1;
            for (int k = 1; k < BINS && left < 0; k++) left = r[Math.floorMod(i - k, BINS)];
            for (int k = 1; k < BINS && right < 0; k++) right = r[Math.floorMod(i + k, BINS)];
            out[i] = left > 0 && right > 0 ? Math.min(left, right) : Math.max(left, right);
        }
        System.arraycopy(out, 0, r, 0, BINS);
    }
}
