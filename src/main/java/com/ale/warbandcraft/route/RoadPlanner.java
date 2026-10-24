package com.ale.warbandcraft.route;

import com.ale.warbandcraft.upgrade.Blueprints;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * El trazado de un camino (H14) sobre una ruta registrada: grava de 3 de ancho que sigue el terreno sin escalones de
 * 2, cortes y túneles con antorchas, terraplenes, puentes de madera sobre agua corta y un muelle en cada costa del
 * agua larga. Esquiva casas, caminos de aldea y todo lo que tenga datos. Los números están acá arriba.
 */
public final class RoadPlanner {
    /** Agua de hasta esto (columnas seguidas) = puente; más = muelles y se cruza navegando. */
    public static int MAX_BRIDGE = 24;
    /** Largo de cada muelle, separación de pilotes y de antorchas. */
    public static int DOCK_LENGTH = 7, PILE_EVERY = 6, TORCH_EVERY = 8;
    /** Terreno 3 o más arriba del camino = túnel (si no, corte a cielo abierto). */
    public static int TUNNEL_DEPTH = 3;
    /** Hasta cuánto se busca el fondo del agua para los pilotes. */
    private static int MAX_PILE = 16;

    private RoadPlanner() {}

    /** El resultado: los bloques y qué tiene (para el aviso). */
    public static final class Result {
        public final Blueprints.Plan plan = new Blueprints.Plan();
        public int bridges, tunnels, docks;
    }

    /** Una columna del centro del camino y hacia dónde queda el costado. */
    private record Col(int x, int z, double px, double pz) {}

    /** Las columnas del centro, de a un bloque, siguiendo los puntos (sin repetir). */
    static List<Col> centerline(List<BlockPos> points) {
        List<Col> out = new ArrayList<>();
        for (int s = 1; s < points.size(); s++) {
            BlockPos a = points.get(s - 1), b = points.get(s);
            double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) continue;
            double px = -dz / len, pz = dx / len;
            int steps = (int) Math.ceil(len * 2);
            for (int i = 0; i <= steps; i++) {
                int x = (int) Math.round(a.getX() + dx * i / steps), z = (int) Math.round(a.getZ() + dz * i / steps);
                if (!out.isEmpty()) {
                    Col last = out.get(out.size() - 1);
                    if (last.x == x && last.z == z) continue;
                }
                out.add(new Col(x, z, px, pz));
            }
        }
        return out;
    }

    /** Arma el camino de la ruta. Carga los chunks del trazado (ya explorados: lo pide el registro de la ruta). */
    public static Result plan(ServerLevel level, List<BlockPos> points) {
        Result res = new Result();
        List<Col> cols = centerline(points);
        int n = cols.size();
        if (n == 0) return res;
        int[] h = new int[n];
        boolean[] water = new boolean[n];
        int hint = points.get(0).getY();
        for (int i = 0; i < n; i++) {
            Col c = cols.get(i);
            level.getChunk(c.x >> 4, c.z >> 4);   // se mide una vez, al encargarlo
            Integer g = Blueprints.groundY(level, c.x, c.z, hint);
            h[i] = g == null ? hint : g;
            water[i] = g != null && level.getFluidState(new BlockPos(c.x, g, c.z)).is(FluidTags.WATER);
            hint = h[i];
        }
        int[] road = smooth(h, water);

        int i = 0;
        while (i < n) {
            if (!water[i]) {
                land(level, res, cols, i, h, road);
                i++;
                continue;
            }
            int j = i;
            while (j < n && water[j]) j++;
            int span = j - i;
            int surface = h[i];
            if (span <= MAX_BRIDGE) {
                for (int k = i; k < j; k++) bridge(level, res, cols.get(k), surface + 1, (k - i) % PILE_EVERY == 0);
                res.bridges++;
            } else {
                for (int k = 0; k < Math.min(DOCK_LENGTH, span); k++) dock(level, res, cols.get(i + k), surface + 1, k, k == DOCK_LENGTH - 1);
                for (int k = 0; k < Math.min(DOCK_LENGTH, span); k++) dock(level, res, cols.get(j - 1 - k), surface + 1, k, k == DOCK_LENGTH - 1);
                res.docks += 2;
            }
            i = j;
        }
        // Túneles (para el aviso): tramos seguidos con el terreno muy arriba
        boolean inTunnel = false;
        for (int k = 0; k < n; k++) {
            boolean t = !water[k] && h[k] - road[k] >= TUNNEL_DEPTH;
            if (t && !inTunnel) res.tunnels++;
            inTunnel = t;
        }
        return res;
    }

    /**
     * Altura del camino: mediana de 5 (saca los pocitos y montículos de 1) y después nunca más de 1 bloque entre
     * pasos, hacia adelante y hacia atrás. El agua no cuenta.
     */
    public static int[] smooth(int[] h, boolean[] water) {
        int n = h.length;
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            List<Integer> w = new ArrayList<>();
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) if (!water[k]) w.add(h[k]);
            if (w.isEmpty()) {
                r[i] = h[i];
                continue;
            }
            int[] a = w.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(a);
            r[i] = a[a.length / 2];
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < n; i++) if (!water[i] && !water[i - 1]) r[i] = Math.max(r[i - 1] - 1, Math.min(r[i - 1] + 1, r[i]));
            for (int i = n - 2; i >= 0; i--) if (!water[i] && !water[i + 1]) r[i] = Math.max(r[i + 1] - 1, Math.min(r[i + 1] + 1, r[i]));
        }
        return r;
    }

    /** Una columna en tierra: grava de 3 de ancho, con relleno abajo o corte/túnel arriba. */
    private static void land(ServerLevel level, Result res, List<Col> cols, int i, int[] h, int[] road) {
        Col c = cols.get(i);
        int r = road[i];
        boolean tunnel = h[i] - r >= TUNNEL_DEPTH;
        for (int k = -1; k <= 1; k++) {
            int x = c.x + (int) Math.round(c.px * k), z = c.z + (int) Math.round(c.pz * k);
            BlockPos surface = new BlockPos(x, r, z);
            if (!usable(level, surface)) continue;
            Integer gl = Blueprints.groundY(level, x, z, r);
            int ground = gl == null ? r : gl;
            for (int y = ground + 1; y < r - 1; y++) res.plan.put(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
            BlockState below = level.getBlockState(surface.below());
            if (r - 1 > ground || Blueprints.clearable(below) || !below.getFluidState().isEmpty()) {
                res.plan.put(surface.below(), Blocks.COBBLESTONE.defaultBlockState());
            }
            res.plan.put(surface, Blocks.GRAVEL.defaultBlockState());
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos p = surface.above(dy);
                if (!level.getBlockState(p).isAir()) res.plan.put(p, Blocks.AIR.defaultBlockState());
            }
            if (tunnel && k == 1 && i % TORCH_EVERY == 0) res.plan.put(surface.above(), Blocks.TORCH.defaultBlockState());
        }
    }

    /**
     * Se puede poner camino en esta columna: ni camino de aldea, ni casa (bloques que no son naturales a la altura
     * del camino o justo arriba), ni nada con datos.
     */
    private static boolean usable(ServerLevel level, BlockPos surface) {
        if (com.ale.warbandcraft.tutorial.Tutorial.isProtected(level, surface)) return false;   // zona del tutorial
        for (int dy = -1; dy <= 3; dy++) {
            BlockPos p = surface.above(dy);
            BlockState s = level.getBlockState(p);
            if (level.getBlockEntity(p) != null) return false;
            if (s.is(Blocks.DIRT_PATH)) return false;   // camino de aldea: se deja como está
            if (!Blueprints.clearable(s) && !Blueprints.natural(s) && s.getFluidState().isEmpty()
                    && !s.is(Blocks.COBBLESTONE) && !s.is(Blocks.DIRT)) return false;
        }
        return true;
    }

    /** Una columna de puente: tablones a 1 sobre el agua, barandas y (cada tanto) pilotes hasta el fondo. */
    private static void bridge(ServerLevel level, Result res, Col c, int deck, boolean piles) {
        for (int k = -2; k <= 2; k++) {
            int x = c.x + (int) Math.round(c.px * k), z = c.z + (int) Math.round(c.pz * k);
            BlockPos d = new BlockPos(x, deck, z);
            if (level.getBlockEntity(d) != null) continue;
            if (Math.abs(k) == 2) {
                res.plan.put(d, Blocks.OAK_PLANKS.defaultBlockState());
                res.plan.put(d.above(), Blocks.OAK_FENCE.defaultBlockState());
                if (piles) pile(level, res, d.below());
            } else {
                res.plan.put(d, Blocks.OAK_PLANKS.defaultBlockState());
                for (int dy = 1; dy <= 2; dy++) {
                    if (!level.getBlockState(d.above(dy)).isAir()) res.plan.put(d.above(dy), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Una columna de muelle: tablones de abeto a 1 sobre el agua, pilotes en las esquinas y farol en la punta. */
    private static void dock(ServerLevel level, Result res, Col c, int deck, int k, boolean end) {
        for (int lane = -1; lane <= 1; lane++) {
            int x = c.x + (int) Math.round(c.px * lane), z = c.z + (int) Math.round(c.pz * lane);
            BlockPos d = new BlockPos(x, deck, z);
            if (level.getBlockEntity(d) != null) continue;
            res.plan.put(d, Blocks.SPRUCE_PLANKS.defaultBlockState());
            if (lane != 0 && (k % 3 == 0 || end)) pile(level, res, d.below());
        }
        if (end) {
            BlockPos tip = new BlockPos(c.x, deck + 1, c.z);
            res.plan.put(tip, Blocks.OAK_FENCE.defaultBlockState());
            res.plan.put(tip.above(), Blocks.LANTERN.defaultBlockState());
        }
    }

    /** Tronco desde {@code top} hacia abajo hasta tocar el fondo (como mucho {@link #MAX_PILE}). */
    private static void pile(ServerLevel level, Result res, BlockPos top) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int dy = 0; dy < MAX_PILE; dy++) {
            BlockPos p = top.below(dy);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() || s.isAir() || s.canBeReplaced()) res.plan.put(p, log);
            else break;
        }
    }
}
