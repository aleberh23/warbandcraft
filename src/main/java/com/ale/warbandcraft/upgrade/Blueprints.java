package com.ale.warbandcraft.upgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Los bloques de cada mejora (H12), sin archivos .nbt: el anillo de la empalizada/muralla, las torres, las plazas
 * y los edificios se arman en código para que se acomoden al terreno de cada aldea.
 */
public final class Blueprints {
    /** Lado del terreno de una casa y de los edificios de antes de los estilos (los demás: {@link Upgrade#siteSize()}). */
    public static final int SITE = 9;
    public static int RING_MIN = 16, RING_MAX = 56, RING_MARGIN = 6;
    /** Aldeas de facción: la empalizada/muralla queda a tanto afuera del edificio más lejano (las casas pueden quedar afuera). */
    public static int RING_PAD = 3;
    /** Desnivel máximo del terreno de un edificio. */
    public static int MAX_SLOPE = 2;
    /** Cuánto arriba y abajo de la campana se busca el piso. */
    public static int SCAN_UP = 16, SCAN_DOWN = 24;

    private Blueprints() {}

    /** Bloques a poner, en orden. Poner dos veces el mismo lugar lo manda al final con el estado nuevo. */
    public static final class Plan {
        public final LinkedHashMap<BlockPos, BlockState> blocks = new LinkedHashMap<>();

        public void put(BlockPos p, BlockState s) {
            BlockPos key = p.immutable();
            blocks.remove(key);
            blocks.put(key, s);
        }

        public int size() {
            return blocks.size();
        }
    }

    // ------------------------------------------------------------------ terreno

    /** Lo que se puede tapar o talar: aire, plantas, nieve, hojas y troncos (árboles o nuestra empalizada). */
    public static boolean clearable(BlockState s) {
        return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SAPLINGS) || s.is(Blocks.SNOW) || ours(s);
    }

    /** Bloques que ponemos en los muros y torres (al pasar de madera a piedra se reemplazan). */
    private static boolean ours(BlockState s) {
        return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.STONE_BRICK_WALL) || s.is(Blocks.SPRUCE_LOG) || s.is(Blocks.LADDER)
                || s.getBlock() instanceof FenceGateBlock;
    }

    /** Piso natural (no el piso de una casa). */
    public static boolean natural(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.PACKED_ICE);
    }

    /**
     * El bloque de piso de la columna (debajo de árboles, plantas y nieve) cerca de la altura {@code hintY} (la de la
     * campana): baja desde {@link #SCAN_UP} más arriba y el piso es el primer bloque firme con aire (o plantas) encima.
     * Así un techo de roca no se confunde con el piso. Null si no está cargada o no hay.
     */
    @Nullable
    public static Integer groundY(ServerLevel level, int x, int z, int hintY) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, hintY, z);
        if (!level.isLoaded(p)) return null;
        int minY = Math.max(level.getMinBuildHeight(), hintY - SCAN_DOWN);
        boolean open = false;
        for (int y = Math.min(level.getMaxBuildHeight() - 1, hintY + SCAN_UP); y > minY; y--) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().isEmpty() || !clearable(s)) {
                if (open) return y;
            } else {
                open = true;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ anillo

    /**
     * Radio del anillo: donde están las camas y los oficios de la aldea (puntos de interés de vanilla) + margen.
     * El 90% más cercano, para que una casa suelta lejos no lo agrande de más.
     */
    public static int ringRadius(ServerLevel level, BlockPos center) {
        List<Double> d = level.getPoiManager().getInRange(t -> true, center, 64, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).map(p -> Math.sqrt(horizontalDistSq(p, center))).sorted().collect(Collectors.toList());
        double r = d.isEmpty() ? 0 : d.get(Math.min(d.size() - 1, (int) Math.floor(d.size() * 0.9)));
        return (int) Math.max(RING_MIN, Math.min(RING_MAX, Math.round(r) + RING_MARGIN));
    }

    private static double horizontalDistSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Las columnas del anillo (x, z) en orden, sin repetir: un círculo continuo de radio {@code r}. */
    public static List<int[]> ringColumns(BlockPos center, int r) {
        List<int[]> out = new ArrayList<>();
        int steps = (int) Math.ceil(2 * Math.PI * r * 2);
        int[] last = null;
        for (int i = 0; i < steps; i++) {
            double a = 2 * Math.PI * i / steps;
            int x = center.getX() + (int) Math.round(r * Math.cos(a));
            int z = center.getZ() + (int) Math.round(r * Math.sin(a));
            if (last != null && last[0] == x && last[1] == z) continue;
            int[] col = {x, z};
            if (!out.isEmpty() && Arrays.equals(out.get(0), col)) break;
            out.add(col);
            last = col;
        }
        return out;
    }

    /** Una columna del anillo donde se puede poner muro: piso natural, sin agua ni casa encima, y su altura. */
    @Nullable
    static Integer wallBase(ServerLevel level, int x, int z, int height, int hintY) {
        Integer g = groundY(level, x, z, hintY);
        if (g == null) return null;
        BlockPos ground = new BlockPos(x, g, z);
        BlockState gs = level.getBlockState(ground);
        if (!gs.getFluidState().isEmpty() || !natural(gs)) return null;
        for (int dy = 1; dy <= height; dy++) {
            BlockPos p = ground.above(dy);
            if (!clearable(level.getBlockState(p)) || level.getBlockEntity(p) != null) return null;
        }
        return g;
    }

    /** Cuánto se corre el muro hacia afuera (y si no, hacia adentro) para rodear una casa en vez de dejar un hueco. */
    public static int RING_DETOUR_OUT = 10, RING_DETOUR_IN = 5;
    /** Agua más honda que esto: el muro no la cruza (queda hueco). Menos honda: pilotes desde el fondo. */
    public static int RING_WATER_DEPTH = 12;

    /** Una columna de muro: bloques de {@code bottom + 1} a {@code top} (en el agua, desde el fondo hasta arriba del agua). */
    private record Post(int x, int z, int bottom, int top) {}

    /**
     * Dónde va el muro en esa columna, o null si no se puede (casa, block entity, agua muy honda, sin cargar). En el
     * agua (o al lado) el muro sube desde el fondo hasta {@code height} arriba de la superficie, como pilotes.
     */
    @Nullable
    private static Post post(ServerLevel level, int x, int z, int height, int extra, int hintY) {
        Integer g = groundY(level, x, z, hintY);
        if (g == null) return null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, g, z);
        BlockState gs = level.getBlockState(p);
        if (!gs.getFluidState().isEmpty()) {
            if (!gs.getFluidState().is(FluidTags.WATER)) return null;
            int bed = g;
            while (bed > g - RING_WATER_DEPTH - 1 && !level.getFluidState(p.setY(bed)).isEmpty()) bed--;
            BlockState b = level.getBlockState(p.setY(bed));
            if (!b.getFluidState().isEmpty() || clearable(b) && !natural(b)) return null;   // muy hondo
            g = bed;
        } else if (!natural(gs)) {
            return null;
        }
        // Agua en la columna o pegada: el muro tiene que asomar arriba de ella
        int surface = Integer.MIN_VALUE;
        int[][] around = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            for (int y = g + 1; y <= g + RING_WATER_DEPTH + 1; y++) {
                if (level.getFluidState(p.set(x + d[0], y, z + d[1])).is(FluidTags.WATER)) surface = Math.max(surface, y);
            }
        }
        int top = Math.max(g + height, surface + height);
        for (int y = g + 1; y <= top + extra; y++) {
            p.set(x, y, z);
            if (!clearable(level.getBlockState(p)) || level.getBlockEntity(p) != null) return null;
        }
        return new Post(x, z, g, top);
    }

    /**
     * Empalizada (troncos de 3) o muralla (ladrillo de piedra de 4 con almenas) que cierra la aldea entera (pedido de
     * Ale): en cada camino un portón (puertas de cerco de 2 de alto con dintel, en línea recta), en el agua pilotes desde
     * el fondo, y donde hay una casa el muro se corre para rodearla. Solo queda hueco si no hay forma (agua muy honda).
     */
    public static Plan ring(ServerLevel level, BlockPos center, int r, boolean stone) {
        Plan plan = new Plan();
        int height = stone ? 4 : 3, extra = stone ? 1 : 0, hint = center.getY();
        BlockState body = stone ? Blocks.STONE_BRICKS.defaultBlockState()
                : Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        List<int[]> cols = ringColumns(center, r);
        int n = cols.size();
        boolean[] path = new boolean[n];
        for (int i = 0; i < n; i++) {
            Integer g = groundY(level, cols.get(i)[0], cols.get(i)[1], hint);
            path[i] = g != null && level.getBlockState(new BlockPos(cols.get(i)[0], g, cols.get(i)[1])).is(Blocks.DIRT_PATH);
        }
        boolean[] gate = new boolean[n];
        int paths = 0;
        for (int i = 0; i < n; i++) {
            if (!path[i]) continue;
            paths++;
            gate[(i + n - 1) % n] = gate[i] = gate[(i + 1) % n] = true;   // portón de 3 por lo menos
        }
        if (paths * 2 > n) Arrays.fill(gate, false);   // un anillo que es casi todo camino: sin portones raros

        // Columnas del muro (fuera de los portones): en el anillo o corridas para rodear lo que estorba
        Post[] posts = new Post[n];
        for (int i = 0; i < n; i++) {
            if (gate[i]) continue;
            int x = cols.get(i)[0], z = cols.get(i)[1];
            posts[i] = post(level, x, z, height, extra, hint);
            if (posts[i] != null) continue;
            double a = Math.atan2(z - center.getZ(), x - center.getX());
            for (int k = 1; k <= RING_DETOUR_OUT + RING_DETOUR_IN && posts[i] == null; k++) {
                int rr = k <= RING_DETOUR_OUT ? r + k : r - (k - RING_DETOUR_OUT);
                posts[i] = post(level, center.getX() + (int) Math.round(rr * Math.cos(a)),
                        center.getZ() + (int) Math.round(rr * Math.sin(a)), height, extra, hint);
            }
        }
        for (int i = 0; i < n; i++) {
            if (posts[i] == null) continue;
            wall(plan, posts[i], body, stone, i);
            if (!gate[(i + 1) % n]) connect(level, plan, posts[i], posts[(i + 1) % n], height, extra, hint, body, stone);
        }

        // Portones: cada tramo de camino, derecho, con un poste en cada punta pegado al muro de al lado
        for (int i = 0; i < n; i++) {
            if (!gate[i] || gate[(i + n - 1) % n]) continue;   // el comienzo de un tramo
            int len = 0;
            while (len < n && gate[(i + len) % n]) len++;
            List<int[]> run = new ArrayList<>();
            for (int k = 0; k < len; k++) run.add(cols.get((i + k) % n));
            gate(level, plan, run, posts[(i + n - 1) % n], posts[(i + len) % n], height, extra, hint, body, stone);
        }
        return plan;
    }

    private static void wall(Plan plan, Post p, BlockState body, boolean stone, int i) {
        for (int y = p.bottom() + 1; y <= p.top(); y++) plan.put(new BlockPos(p.x(), y, p.z()), body);
        if (stone && i % 2 == 0) plan.put(new BlockPos(p.x(), p.top() + 1, p.z()), Blocks.STONE_BRICK_WALL.defaultBlockState());
    }

    /** Une dos columnas que no se tocan (un desvío): una línea de muro entre las dos (en diagonal ya cierra). */
    private static void connect(ServerLevel level, Plan plan, Post a, @Nullable Post b, int height, int extra, int hint,
                                BlockState body, boolean stone) {
        if (b == null) return;
        int dx = b.x() - a.x(), dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 1 || steps > 2 * (RING_DETOUR_OUT + RING_DETOUR_IN)) return;
        for (int k = 1; k < steps; k++) {
            int x = a.x() + (int) Math.round((double) dx * k / steps), z = a.z() + (int) Math.round((double) dz * k / steps);
            Post p = post(level, x, z, height, extra, hint);
            if (p != null) wall(plan, p, body, stone, k);
        }
    }

    /** Un portón derecho sobre el tramo {@code run} del anillo, con postes en las puntas unidos al muro de cada lado. */
    private static void gate(ServerLevel level, Plan plan, List<int[]> run, @Nullable Post before, @Nullable Post after,
                             int height, int extra, int hint, BlockState body, boolean stone) {
        int[] a = run.get(0), b = run.get(run.size() - 1);
        boolean xAxis = Math.abs(b[0] - a[0]) >= Math.abs(b[1] - a[1]);   // para dónde corre el muro ahí
        double sum = 0;
        for (int[] c : run) sum += xAxis ? c[1] : c[0];
        int line = (int) Math.round(sum / run.size());
        int a0 = xAxis ? a[0] : a[1], b0 = xAxis ? b[0] : b[1];
        int lo = Math.min(a0, b0), hi = Math.max(a0, b0);
        while (hi - lo < 2) {   // 3 de ancho por lo menos
            if ((hi - lo) % 2 == 0) hi++;
            else lo--;
        }
        BlockState gateBlock = (stone ? Blocks.DARK_OAK_FENCE_GATE : Blocks.SPRUCE_FENCE_GATE).defaultBlockState()
                .setValue(FenceGateBlock.FACING, xAxis ? Direction.NORTH : Direction.EAST).setValue(FenceGateBlock.OPEN, false);
        for (int u = lo; u <= hi; u++) {
            int x = xAxis ? u : line, z = xAxis ? line : u;
            Post p = post(level, x, z, height, extra, hint);
            if (p == null) continue;
            plan.put(new BlockPos(x, p.bottom() + 1, z), gateBlock);
            plan.put(new BlockPos(x, p.bottom() + 2, z), gateBlock);
            for (int y = p.bottom() + 3; y <= p.top(); y++) plan.put(new BlockPos(x, y, z), body);   // dintel
            if (stone && (u - lo) % 2 == 0) plan.put(new BlockPos(x, p.top() + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
        }
        // Postes de las puntas, cada uno unido al muro de su lado
        Post low = post(level, xAxis ? lo - 1 : line, xAxis ? line : lo - 1, height, extra, hint);
        Post high = post(level, xAxis ? hi + 1 : line, xAxis ? line : hi + 1, height, extra, hint);
        boolean lowFirst = Math.abs(lo - a0) <= Math.abs(hi - a0);
        Post toBefore = lowFirst ? low : high, toAfter = lowFirst ? high : low;
        if (toBefore != null) {
            wall(plan, toBefore, body, stone, 0);
            connect(level, plan, toBefore, before, height, extra, hint, body, stone);
        }
        if (toAfter != null) {
            wall(plan, toAfter, body, stone, 0);
            connect(level, plan, toAfter, after, height, extra, hint, body, stone);
        }
    }

    /** Las puertas de cerco de abajo de cada portón del plan (las que abren y cierran, {@code Works.tickGates}). */
    public static List<BlockPos> gatesOf(Plan plan) {
        List<BlockPos> out = new ArrayList<>();
        for (var e : plan.blocks.entrySet()) {
            if (!(e.getValue().getBlock() instanceof FenceGateBlock)) continue;
            BlockState below = plan.blocks.get(e.getKey().below());
            if (below == null || !(below.getBlock() instanceof FenceGateBlock)) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ torres

    /** Cuatro torres sobre el anillo (en diagonal, para no tapar los caminos que salen de la campana). */
    public static Plan towers(ServerLevel level, BlockPos center, int r) {
        Plan plan = new Plan();
        for (int k = 0; k < 4; k++) {
            for (int tryN = 0; tryN < 5; tryN++) {
                double deg = 45 + 90 * k + (tryN == 0 ? 0 : (tryN % 2 == 1 ? 1 : -1) * 8 * ((tryN + 1) / 2));
                double a = Math.toRadians(deg);
                BlockPos c = new BlockPos(center.getX() + (int) Math.round(r * Math.cos(a)), 0, center.getZ() + (int) Math.round(r * Math.sin(a)));
                if (tower(level, plan, c, center, true) != null) break;
            }
        }
        return plan;
    }

    /**
     * Una torre de 5×5 con centro en la columna {@code c}: de piedra (la de H12) o de madera (H22c, troncos). La puerta
     * mira a {@code center}. Devuelve la altura del piso, o null si no entra (casa, agua o más de 3 de desnivel).
     */
    @Nullable
    static Integer tower(ServerLevel level, Plan plan, BlockPos c, BlockPos center, boolean stone) {
        int base = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        int[][] grounds = new int[5][5];
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Integer g = wallBase(level, c.getX() + dx, c.getZ() + dz, 4, center.getY());
            if (g == null) return null;
            grounds[dx + 2][dz + 2] = g;
            base = Math.min(base, g);
            top = Math.max(top, g);
        }
        if (top - base > 3) return null;
        BlockState brick = stone ? Blocks.STONE_BRICKS.defaultBlockState()
                : Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState roofBlock = stone ? brick : Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState crenel = stone ? Blocks.STONE_BRICK_WALL.defaultBlockState() : brick;
        int floor = top;   // la torre arranca en el piso más alto; abajo, cimientos
        // Puerta hacia el centro
        Direction toCenter = Direction.getNearest(center.getX() - c.getX(), 0, center.getZ() - c.getZ());
        if (toCenter.getAxis() == Direction.Axis.Y) toCenter = Direction.NORTH;
        Direction ladderWall = toCenter.getOpposite();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = c.getX() + dx, z = c.getZ() + dz;
            for (int y = grounds[dx + 2][dz + 2] + 1; y <= floor; y++) plan.put(new BlockPos(x, y, z), brick);
        }
        for (int dy = 1; dy <= 8; dy++) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                BlockPos p = new BlockPos(c.getX() + dx, floor + dy, c.getZ() + dz);
                boolean door = dy <= 2 && dx == toCenter.getStepX() * 2 && dz == toCenter.getStepZ() * 2;
                if (edge && !door) plan.put(p, brick);
                else if (dy == 8) plan.put(p, roofBlock);   // techo
                else plan.put(p, Blocks.AIR.defaultBlockState());
            }
        }
        // Escalera contra la pared de enfrente de la puerta, hasta el techo (con su hueco)
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos p = new BlockPos(c.getX() + ladderWall.getStepX(), floor + dy, c.getZ() + ladderWall.getStepZ());
            plan.put(p, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toCenter));
        }
        // Almenas
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && (dx + dz) % 2 == 0) {
                plan.put(new BlockPos(c.getX() + dx, floor + 9, c.getZ() + dz), crenel);
            }
        }
        return floor;
    }

    // ------------------------------------------------------------------ plazas

    /** Pueblo: plaza de adoquín y faroles. Ciudad: plaza grande de piedra pulida y más faroles. */
    public static Plan plaza(ServerLevel level, BlockPos center, boolean city) {
        Plan plan = new Plan();
        int r = city ? 7 : 4;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r + 1 || (dx == 0 && dz == 0)) continue;
            Integer g = groundY(level, center.getX() + dx, center.getZ() + dz, center.getY());
            if (g == null) continue;
            BlockPos p = new BlockPos(center.getX() + dx, g, center.getZ() + dz);
            BlockState s = level.getBlockState(p);
            if (!(s.is(BlockTags.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE))) continue;
            if (!level.getBlockState(p.above()).isAir() && !level.getBlockState(p.above()).canBeReplaced()) continue;
            BlockState top = city ? ((dx + dz) % 2 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS).defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState();
            plan.put(p, top);
            if (!level.getBlockState(p.above()).isAir()) plan.put(p.above(), Blocks.AIR.defaultBlockState());
        }
        int posts = city ? 8 : 4;
        for (int k = 0; k < posts; k++) {
            double a = 2 * Math.PI * k / posts + Math.PI / 4;
            int x = center.getX() + (int) Math.round((r + 1) * Math.cos(a)), z = center.getZ() + (int) Math.round((r + 1) * Math.sin(a));
            Integer g = groundY(level, x, z, center.getY());
            if (g == null) continue;
            BlockPos p = new BlockPos(x, g + 1, z);
            if (!level.getBlockState(p).canBeReplaced() || !level.getBlockState(p.above()).isAir() || !level.getBlockState(p.above(2)).isAir()) continue;
            plan.put(p, Blocks.COBBLESTONE_WALL.defaultBlockState());
            plan.put(p.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
            plan.put(p.above(2), Blocks.LANTERN.defaultBlockState());
        }
        return plan;
    }

    // ------------------------------------------------------------------ edificios

    /** Un terreno ocupado: esquina mínima (y = piso) y lado. */
    public record Lot(BlockPos corner, int size) {}

    /** Hasta dónde arriba del piso se despeja el terreno de un edificio (la Taberna 3 llega a 15). */
    public static int CLEAR_HEIGHT = 16;

    /**
     * Busca un terreno de {@code size}×{@code size} casi plano entre {@code minR} y {@code maxR} bloques del centro, sin
     * caminos, casas, agua ni cosas con datos, y que no pise los terrenos ya usados. Devuelve la esquina mínima con y = el
     * piso (el más alto del terreno), o null.
     */
    @Nullable
    public static BlockPos findSite(ServerLevel level, BlockPos center, int minR, int maxR, int size, Collection<Lot> taken) {
        for (int r = Math.max(minR, 0); r <= maxR; r += 3) {
            int steps = Math.max(8, (int) (2 * Math.PI * r / 6));
            for (int i = 0; i < steps; i++) {
                double a = 2 * Math.PI * i / steps;
                int cx = center.getX() + (int) Math.round(r * Math.cos(a)), cz = center.getZ() + (int) Math.round(r * Math.sin(a));
                BlockPos corner = new BlockPos(cx - size / 2, 0, cz - size / 2);
                if (overlaps(corner, size, taken)) continue;
                Integer floor = siteFloor(level, corner, size, center.getY());
                if (floor != null) return new BlockPos(corner.getX(), floor, corner.getZ());
            }
        }
        return null;
    }

    /**
     * Aldeas de facción (pedido de Ale: "el mercado y la taberna quedan afuera de la muralla"): en una aldea de vanilla casi
     * no hay 13×13 parejos y sin camino, así que se acepta más desnivel ({@link #NPC_MAX_SLOPE}, el piso es la mediana: lo
     * de más se corta y lo de menos se rellena) y que una parte del terreno sea camino ({@link #NPC_PATH_SHARE}).
     */
    public static int NPC_MAX_SLOPE = 5;
    public static double NPC_PATH_SHARE = 0.2;

    /** Como {@link #findSite(ServerLevel, BlockPos, int, int, int, Collection)} pero con las reglas de las aldeas de facción. */
    @Nullable
    public static BlockPos findSiteRelaxed(ServerLevel level, BlockPos center, int minR, int maxR, int size, Collection<Lot> taken) {
        for (int r = Math.max(minR, 0); r <= maxR; r += 3) {
            int steps = Math.max(8, (int) (2 * Math.PI * r / 6));
            for (int i = 0; i < steps; i++) {
                double a = 2 * Math.PI * i / steps;
                int cx = center.getX() + (int) Math.round(r * Math.cos(a)), cz = center.getZ() + (int) Math.round(r * Math.sin(a));
                BlockPos corner = new BlockPos(cx - size / 2, 0, cz - size / 2);
                if (overlaps(corner, size, taken)) continue;
                Integer floor = relaxedFloor(level, corner, size, center.getY());
                if (floor != null) return new BlockPos(corner.getX(), floor, corner.getZ());
            }
        }
        return null;
    }

    @Nullable
    private static Integer relaxedFloor(ServerLevel level, BlockPos corner, int size, int hintY) {
        int[] ys = new int[size * size];
        int n = 0, paths = 0, maxPaths = (int) Math.floor(size * size * NPC_PATH_SHARE);
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
            int x = corner.getX() + dx, z = corner.getZ() + dz;
            Integer g = columnGround(level, x, z, hintY, true);
            if (g == null) return null;
            if (level.getBlockState(new BlockPos(x, g, z)).is(Blocks.DIRT_PATH) && ++paths > maxPaths) return null;
            ys[n++] = g;
        }
        Arrays.sort(ys);
        return ys[n - 1] - ys[0] <= NPC_MAX_SLOPE ? ys[n / 2] : null;
    }

    /** Lo mismo para un terreno de {@link #SITE} con terrenos ocupados de {@link #SITE}. */
    @Nullable
    public static BlockPos findSite(ServerLevel level, BlockPos center, int minR, int maxR, Iterable<BlockPos> taken) {
        List<Lot> lots = new ArrayList<>();
        for (BlockPos t : taken) lots.add(new Lot(t, SITE));
        return findSite(level, center, minR, maxR, SITE, lots);
    }

    /** El terreno de {@code size} con esquina {@code corner} se pisa (o queda pegado) con alguno de {@code taken}. */
    public static boolean overlaps(BlockPos corner, int size, Collection<Lot> taken) {
        for (Lot t : taken) {
            BlockPos c = t.corner();
            if (corner.getX() < c.getX() + t.size() + 1 && c.getX() < corner.getX() + size + 1
                    && corner.getZ() < c.getZ() + t.size() + 1 && c.getZ() < corner.getZ() + size + 1) return true;
        }
        return false;
    }

    /** El piso del terreno si sirve, o null. */
    @Nullable
    public static Integer siteFloor(ServerLevel level, BlockPos corner, int size, int hintY) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
            Integer g = columnGround(level, corner.getX() + dx, corner.getZ() + dz, hintY);
            if (g == null) return null;
            min = Math.min(min, g);
            max = Math.max(max, g);
        }
        return max - min <= MAX_SLOPE ? max : null;
    }

    /** El piso de una columna donde se puede construir (natural, sin camino ni agua, libre arriba), o null. */
    @Nullable
    private static Integer columnGround(ServerLevel level, int x, int z, int hintY) {
        return columnGround(level, x, z, hintY, false);
    }

    @Nullable
    private static Integer columnGround(ServerLevel level, int x, int z, int hintY, boolean path) {
        Integer g = groundY(level, x, z, hintY);
        if (g == null) return null;
        BlockPos ground = new BlockPos(x, g, z);
        BlockState gs = level.getBlockState(ground);
        boolean isPath = gs.is(Blocks.DIRT_PATH);
        if (!gs.getFluidState().isEmpty() || (!natural(gs) && !(path && isPath)) || (isPath && !path)) return null;
        for (int dy = 1; dy <= Placement.CLEAR_ABOVE; dy++) {
            BlockPos p = ground.above(dy);
            if (!clearable(level.getBlockState(p)) || level.getBlockEntity(p) != null) return null;
        }
        return g;
    }

    /**
     * Un terreno de antes de los estilos (de {@link #SITE}) que se agranda a {@code size} para el nivel siguiente,
     * alrededor del mismo medio: lo de adentro del terreno viejo (el edificio de antes) no cuenta. La esquina nueva con el
     * piso de siempre, o null si no entra.
     */
    @Nullable
    public static BlockPos enlarge(ServerLevel level, BlockPos old, int oldSize, int size, Collection<Lot> others, int hintY) {
        int d = (size - oldSize) / 2;
        BlockPos corner = new BlockPos(old.getX() - d, old.getY(), old.getZ() - d);
        if (overlaps(new BlockPos(corner.getX(), 0, corner.getZ()), size, others)) return null;
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
            int x = corner.getX() + dx, z = corner.getZ() + dz;
            if (x >= old.getX() && x < old.getX() + oldSize && z >= old.getZ() && z < old.getZ() + oldSize) continue;
            Integer g = columnGround(level, x, z, hintY);
            if (g == null || Math.abs(g - old.getY()) > Placement.MAX_LEVEL) return null;
        }
        return corner;
    }

    /** Nivela el terreno: tierra hasta el piso, el piso, y aire arriba (tala lo que haya). */
    private static void level(ServerLevel level, Plan plan, BlockPos site, int size, BlockState floor) {
        int fy = site.getY();
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
            int x = site.getX() + dx, z = site.getZ() + dz;
            Integer g = groundY(level, x, z, fy);
            int from = g == null ? fy : g + 1;
            for (int y = from; y < fy; y++) plan.put(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
            for (int y = fy + 1; y <= fy + CLEAR_HEIGHT; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (!level.getBlockState(p).isAir()) plan.put(p, Blocks.AIR.defaultBlockState());
            }
        }
        for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) {
            plan.put(new BlockPos(site.getX() + dx, fy, site.getZ() + dz), floor);
        }
    }

    /** El edificio de {@code u} en el terreno {@code site} (esquina mínima, y = piso), sin girar y en el estilo del Reino. */
    public static Plan building(ServerLevel level, Upgrade u, BlockPos site) {
        return building(level, u, site, 0, Style.REINO);
    }

    /**
     * El edificio de {@code u} en el estilo {@code style}, girado {@code rot} × 90° en el sentido del reloj (0..3) dentro
     * de su terreno de {@link Upgrade#siteSize()}: la puerta, que sin girar mira al norte (z mínimo), pasa a mirar al
     * este, al sur o al oeste.
     */
    public static Plan building(ServerLevel level, Upgrade u, BlockPos site, int rot, Style style) {
        Plan plan = new Plan();
        int size = u.siteSize();
        Block ground = u.trainingLevel() > 0 ? Blocks.COARSE_DIRT
                : u.marketLevel() > 0 ? Blocks.DIRT_PATH
                : style == Style.ARENA ? Blocks.SAND : Blocks.GRASS_BLOCK;
        level(level, plan, site, size, ground.defaultBlockState());
        Buildings.Canvas c = new Buildings.Canvas(plan, site, net.minecraft.world.level.block.Rotation.values()[Math.floorMod(rot, 4)], size, style);
        Buildings.draw(c, u);
        return plan;
    }

    /**
     * Dónde se para quien trabaja en el edificio {@code built} del terreno {@code key} (el Tabernero detrás de la barra,
     * el Mercader, los del cuartel), a la altura del piso + 1; el medio si el edificio no tiene uno o es de antes.
     */
    @Nullable
    public static BlockPos anchor(TownData.Town t, String key, @Nullable Upgrade built) {
        BlockPos site = t.sites.get(key);
        if (site == null) return null;
        int size = t.siteSize(key);
        int[] a = built == null || t.legacySite(key) ? null : Buildings.anchor(built);
        if (a == null) return site.offset(size / 2, 1, size / 2);
        Buildings.Canvas c = new Buildings.Canvas(new Plan(), site,
                net.minecraft.world.level.block.Rotation.values()[Math.floorMod(t.rotations.getOrDefault(key, 0), 4)], size, Style.REINO);
        return c.at(a[0], 1, a[1]);
    }
}
