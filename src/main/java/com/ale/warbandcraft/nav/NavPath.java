package com.ale.warbandcraft.nav;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Buscar el camino sobre la malla ({@link NavGrid}) con A*: de un lugar a otro (o hasta quedar a {@code reach} bloques),
 * dentro de un rectángulo. Devuelve los pasos con su clase (caminar, puerta, escalera, agua, trepar, romper).
 */
public final class NavPath {
    /** Cuántos lugares mira como mucho (después devuelve lo mejor que tenga, si se pidió). */
    public static final int MAX_NODES = 20000;

    private NavPath() {}

    /** El rectángulo donde se busca. */
    public record Bounds(int minX, int maxX, int minZ, int maxZ) {
        public static Bounds around(BlockPos c, int r) {
            return new Bounds(c.getX() - r, c.getX() + r, c.getZ() - r, c.getZ() + r);
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public Bounds include(BlockPos p, int margin) {
            return new Bounds(Math.min(minX, p.getX() - margin), Math.max(maxX, p.getX() + margin), Math.min(minZ, p.getZ() - margin),
                    Math.max(maxZ, p.getZ() + margin));
        }
    }

    private record Open(float f, long pos) {}

    /**
     * El camino de {@code start} hasta quedar a {@code reach} bloques (horizontal, y a 3 de alto) de {@code goal}.
     * Null si no hay; con {@code partial}, si no llega, el camino hasta el lugar más cercano al que sí llega.
     */
    @Nullable
    public static List<Step> find(ServerLevel level, BlockPos start, Vec3 goal, double reach, NavCaps caps, Bounds bounds, boolean partial) {
        BlockPos from = NavGrid.nearestNode(level, start, 3);
        if (from == null) return null;
        long s = from.asLong();
        Long2FloatOpenHashMap g = new Long2FloatOpenHashMap();
        g.defaultReturnValue(Float.MAX_VALUE);
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        Long2ByteOpenHashMap kind = new Long2ByteOpenHashMap();
        Long2LongOpenHashMap wall = new Long2LongOpenHashMap();
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));
        g.put(s, 0);
        kind.put(s, (byte) Step.Kind.START.ordinal());
        open.add(new Open(h(from, goal, reach), s));
        long best = s;
        float bestH = h(from, goal, reach);
        int expanded = 0;
        long found = Long.MIN_VALUE;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Open o = open.poll();
            long cur = o.pos;
            float gc = g.get(cur);
            int x = BlockPos.getX(cur), y = BlockPos.getY(cur), z = BlockPos.getZ(cur);
            float hc = h(x, y, z, goal, reach);
            if (o.f > gc + hc + 1e-3) continue;   // ya se llegó más barato
            expanded++;
            if (hc <= 0) {
                found = cur;
                break;
            }
            if (hc < bestH) {
                bestH = hc;
                best = cur;
            }
            NavGrid.neighbors(level, x, y, z, caps, (to, cost, k, w) -> {
                int tx = BlockPos.getX(to), tz = BlockPos.getZ(to);
                if (!bounds.contains(tx, tz)) return;
                float ng = gc + cost;
                if (ng >= g.get(to)) return;
                g.put(to, ng);
                parent.put(to, cur);
                kind.put(to, (byte) k.ordinal());
                if (w != 0) wall.put(to, w);
                else wall.remove(to);
                open.add(new Open(ng + h(tx, BlockPos.getY(to), tz, goal, reach), to));
            });
        }
        if (found == Long.MIN_VALUE) {
            if (!partial || best == s) return null;
            found = best;
        }
        List<Step> out = new ArrayList<>();
        Step.Kind[] kinds = Step.Kind.values();
        for (long c = found; ; c = parent.get(c)) {
            out.add(new Step(c, kinds[kind.get(c)], wall.getOrDefault(c, 0L)));
            if (c == s) break;
        }
        Collections.reverse(out);
        return out;
    }

    private static float h(BlockPos p, Vec3 goal, double reach) {
        return h(p.getX(), p.getY(), p.getZ(), goal, reach);
    }

    /** Lo que falta (horizontal menos el alcance; y la altura si está lejos de arriba/abajo). */
    private static float h(int x, int y, int z, Vec3 goal, double reach) {
        double dx = x + 0.5 - goal.x, dz = z + 0.5 - goal.z;
        double d = Math.sqrt(dx * dx + dz * dz) - reach;
        double dy = Math.abs(y - goal.y);
        if (d <= 0 && dy <= 3) return 0;
        return (float) Math.max(0.01, Math.max(d, 0) + Math.max(0, dy - 3));
    }

    /** El primer paso de esa clase, o -1. */
    public static int first(List<Step> path, Step.Kind k) {
        for (int i = 0; i < path.size(); i++) if (path.get(i).kind() == k) return i;
        return -1;
    }

    /** Cuántos pasos de agua tiene. */
    public static int water(List<Step> path) {
        int n = 0;
        for (Step s : path) if (s.kind() == Step.Kind.WATER) n++;
        return n;
    }
}
