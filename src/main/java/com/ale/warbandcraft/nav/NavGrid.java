package com.ale.warbandcraft.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * La "malla de navegación" de los asedios y batallas (como la de Bannerlord, pero de bloques). Para cada columna (x, z)
 * se guardan los lugares donde se puede estar (piso, arriba de un muro, adentro de una casa, un peldaño de escalera, la
 * superficie del agua) y de ahí salen las conexiones: caminar, subir un escalón, bajar, abrir una puerta, subir una
 * escalera, cruzar agua (en bote) y las conexiones "apagadas" que solo usa quien planea el asalto: trepar un muro con
 * una escalera que todavía no está, o romperlo (TNT, cañón). Se mide una vez y se guarda; un cambio de bloque borra esa
 * columna ({@link #invalidate}).
 */
public final class NavGrid {
    /** Qué hay en un lugar. */
    public static final byte WATER = 1, LADDER = 2, DOOR = 4;
    /** Una columna medida sirve este tiempo aunque nadie avise que cambió. */
    private static final int MAX_AGE = 20 * 60;
    /** Columnas medidas que se guardan por dimensión como mucho (después se tiran las viejas: si no, la memoria crece sin fin). */
    private static final int MAX_COLUMNS = 200_000;
    /** Muros más altos que esto no se escalan. */
    public static final int MAX_CLIMB = 12;

    /** Una columna medida: alturas de los pies donde se puede estar (de abajo hacia arriba) y qué hay en cada una. */
    record Column(long tick, int[] ys, byte[] flags) {
        int index(int y) {
            for (int i = 0; i < ys.length; i++) if (ys[i] == y) return i;
            return -1;
        }

        Column within(int lo, int hi) {
            int n = 0;
            for (int y : ys) if (y >= lo && y <= hi) n++;
            if (n == ys.length) return this;
            int[] y2 = new int[n];
            byte[] f2 = new byte[n];
            int j = 0;
            for (int i = 0; i < ys.length; i++) {
                if (ys[i] < lo || ys[i] > hi) continue;
                y2[j] = ys[i];
                f2[j++] = flags[i];
            }
            return new Column(tick, y2, f2);
        }
    }

    private static final Map<ResourceKey<Level>, Map<Long, Column>> CACHE = new HashMap<>();

    private NavGrid() {}

    public static void clear() {
        CACHE.clear();
    }

    /** Algo cambió en {@code pos}: esa franja de esa columna (y las de al lado) se vuelve a medir. */
    public static void invalidate(Level level, BlockPos pos) {
        Map<Long, Column> m = CACHE.get(level.dimension());
        if (m == null || m.isEmpty()) return;
        // Casi siempre y-1 e y+1 caen en la misma franja: 9 borrados en vez de 27 (pasa en cada cambio de bloque)
        int low = (pos.getY() - 1) >> BAND_BITS, high = (pos.getY() + 1) >> BAND_BITS;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            for (int band = low; band <= high; band++) m.remove(key(pos.getX() + dx, band, pos.getZ() + dz));
        }
    }

    /** Las columnas se miden en franjas de 32 de alto (así sirve bajo tierra, en un castillo o en una cueva). */
    private static final int BAND_BITS = 5, BAND = 1 << BAND_BITS;

    static long key(int x, int band, int z) {
        return BlockPos.asLong(x, band, z);
    }

    // ------------------------------------------------------------------ medir

    /** Los lugares de la columna entre {@code lo} y {@code hi} (inclusive). */
    static Column column(ServerLevel level, int x, int z, int lo, int hi) {
        int b0 = lo >> BAND_BITS, b1 = hi >> BAND_BITS;
        if (b0 == b1) return band(level, x, z, b0).within(lo, hi);
        int[] ys = new int[8];
        byte[] fl = new byte[8];
        int n = 0;
        for (int b = b0; b <= b1; b++) {
            Column c = band(level, x, z, b);
            for (int i = 0; i < c.ys().length; i++) {
                int y = c.ys()[i];
                if (y < lo || y > hi) continue;
                if (n == ys.length) {
                    ys = java.util.Arrays.copyOf(ys, n * 2);
                    fl = java.util.Arrays.copyOf(fl, n * 2);
                }
                ys[n] = y;
                fl[n++] = c.flags()[i];
            }
        }
        return new Column(0, java.util.Arrays.copyOf(ys, n), java.util.Arrays.copyOf(fl, n));
    }

    private static Column band(ServerLevel level, int x, int z, int band) {
        Map<Long, Column> m = CACHE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        long k = key(x, band, z);
        long now = level.getGameTime();
        Column c = m.get(k);
        if (c != null && now - c.tick() < MAX_AGE && now >= c.tick()) return c;
        c = scan(level, x, z, band << BAND_BITS, (band << BAND_BITS) + BAND - 1, now);
        if (m.size() >= MAX_COLUMNS) {
            m.values().removeIf(old -> now - old.tick() >= MAX_AGE || now < old.tick());
            if (m.size() >= MAX_COLUMNS) m.clear();
        }
        m.put(k, c);
        return c;
    }

    private static Column scan(ServerLevel level, int x, int z, int bottom, int top, long now) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, Math.max(bottom, level.getMinBuildHeight()), z);
        if (!level.isLoaded(p)) return new Column(now, new int[0], new byte[0]);
        bottom = Math.max(bottom, level.getMinBuildHeight() + 1);
        top = Math.min(top, level.getMaxBuildHeight() - 2);
        int[] ys = new int[8];
        byte[] fl = new byte[8];
        int n = 0;
        for (int y = bottom; y <= top; y++) {
            byte f = classify(level, p.set(x, y, z));
            if (f < 0) continue;
            if (n == ys.length) {
                ys = java.util.Arrays.copyOf(ys, n * 2);
                fl = java.util.Arrays.copyOf(fl, n * 2);
            }
            ys[n] = y;
            fl[n++] = f;
        }
        return new Column(now, java.util.Arrays.copyOf(ys, n), java.util.Arrays.copyOf(fl, n));
    }

    /** -1 si no se puede estar con los pies en {@code p}; si se puede, qué hay (agua, escalera, puerta). */
    private static byte classify(ServerLevel level, BlockPos p) {
        BlockState feet = level.getBlockState(p);
        BlockState head = level.getBlockState(p.above());
        if (!passable(level, p.above(), head) || dangerous(feet) || dangerous(head)) return -1;
        FluidState fluid = feet.getFluidState();
        if (!fluid.isEmpty()) {
            // Agua: solo la superficie (arriba, aire) y nada de lava
            if (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !head.getFluidState().isEmpty()) return -1;
            return WATER;
        }
        if (feet.getBlock() instanceof LadderBlock) return LADDER;
        if (!passable(level, p, feet)) return -1;
        BlockPos below = p.below();
        BlockState floor = level.getBlockState(below);
        if (!isFloor(level, below, floor)) return -1;
        return feet.getBlock() instanceof DoorBlock || feet.getBlock() instanceof FenceGateBlock ? DOOR : 0;
    }

    /** Se puede pasar por el bloque: sin colisión, o una puerta/tranquera (se abre), o una escalera. */
    static boolean passable(ServerLevel level, BlockPos p, BlockState s) {
        if (s.getBlock() instanceof DoorBlock d) return d.type().canOpenByHand();
        if (s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof LadderBlock) return true;
        if (s.getBlock() instanceof TrapDoorBlock) return s.getValue(TrapDoorBlock.OPEN);
        if (!s.getFluidState().isEmpty() && !s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
        return s.getCollisionShape(level, p).isEmpty();
    }

    private static boolean isFloor(ServerLevel level, BlockPos p, BlockState s) {
        if (s.getBlock() instanceof FenceBlock || s.getBlock() instanceof WallBlock || s.getBlock() instanceof FenceGateBlock) return false;
        if (dangerous(s)) return false;
        VoxelShape shape = s.getCollisionShape(level, p);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5 && shape.max(Direction.Axis.Y) <= 1.0;
    }

    private static boolean dangerous(BlockState s) {
        return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.WITHER_ROSE);
    }

    /** Los lugares de la columna entre {@code lo} y {@code hi}. */
    public static int[] standable(ServerLevel level, int x, int z, int lo, int hi) {
        return column(level, x, z, lo, hi).ys();
    }

    /** Qué hay en el lugar {@code (x, y, z)}, o -1 si no es un lugar. */
    public static int flagsAt(ServerLevel level, int x, int y, int z) {
        Column c = column(level, x, z, y, y);
        int i = c.index(y);
        return i < 0 ? -1 : c.flags()[i];
    }

    /** El lugar más cercano a {@code p} (misma columna o alrededor, hasta {@code radius}), o null. */
    @Nullable
    public static BlockPos nearestNode(ServerLevel level, BlockPos p, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                Column c = column(level, p.getX() + dx, p.getZ() + dz, p.getY() - 4, p.getY() + 4);
                for (int i = 0; i < c.ys().length; i++) {
                    int dy = c.ys()[i] - p.getY();
                    if (Math.abs(dy) > 4) continue;
                    double d = dx * dx + dz * dz + dy * dy * 2 + ((c.flags()[i] & WATER) != 0 ? 50 : 0);
                    if (d < bestD) {
                        bestD = d;
                        best = new BlockPos(p.getX() + dx, c.ys()[i], p.getZ() + dz);
                    }
                }
            }
            if (best != null && r >= 1) return best;
        }
        return best;
    }

    // ------------------------------------------------------------------ conexiones

    /** Una conexión: a dónde, cuánto cuesta, de qué clase y (romper / trepar) qué bloque del muro. */
    public interface EdgeSink {
        void edge(long to, float cost, Step.Kind kind, long wall);
    }

    /** Las conexiones que salen del lugar {@code (x, y, z)} para quien tiene estas capacidades. */
    static void neighbors(ServerLevel level, int x, int y, int z, NavCaps caps, EdgeSink out) {
        Column here = column(level, x, z, y - 1, y + 1);
        int hi = here.index(y);
        byte hereFlags = hi < 0 ? 0 : here.flags()[hi];
        boolean onLadder = (hereFlags & LADDER) != 0;
        boolean inWater = (hereFlags & WATER) != 0;

        // Escaleras: arriba y abajo por la misma columna
        if (onLadder || (hi >= 0 && level.getBlockState(new BlockPos(x, y + 1, z)).getBlock() instanceof LadderBlock)) {
            int ui = here.index(y + 1);
            if (ui >= 0 && (here.flags()[ui] & LADDER) != 0) out.edge(BlockPos.asLong(x, y + 1, z), caps.ladderCost(), Step.Kind.LADDER, 0);
        }
        int di = here.index(y - 1);
        if (onLadder && di >= 0) out.edge(BlockPos.asLong(x, y - 1, z), caps.ladderCost(), Step.Kind.LADDER, 0);

        for (Direction d : Direction.Plane.HORIZONTAL) walkTo(level, x, y, z, d.getStepX(), d.getStepZ(), caps, inWater, out);
        walkTo(level, x, y, z, 1, 1, caps, inWater, out);
        walkTo(level, x, y, z, 1, -1, caps, inWater, out);
        walkTo(level, x, y, z, -1, 1, caps, inWater, out);
        walkTo(level, x, y, z, -1, -1, caps, inWater, out);

        if (inWater || onLadder && hi >= 0 && !hasFloor(level, x, y, z)) return;
        if (caps.climbWalls()) for (Direction d : Direction.Plane.HORIZONTAL) climb(level, x, y, z, d, caps, out);
        if (caps.breakWalls()) for (Direction d : Direction.Plane.HORIZONTAL) breach(level, x, y, z, d, caps, out);
    }

    private static boolean hasFloor(ServerLevel level, int x, int y, int z) {
        BlockPos b = new BlockPos(x, y - 1, z);
        return isFloor(level, b, level.getBlockState(b));
    }

    /** Caminar (o nadar) a la columna de al lado: misma altura, un escalón arriba o bajando hasta {@code maxDrop}. */
    private static void walkTo(ServerLevel level, int x, int y, int z, int dx, int dz, NavCaps caps, boolean fromWater, EdgeSink out) {
        int nx = x + dx, nz = z + dz;
        Column c = column(level, nx, nz, y - caps.maxDrop(), y + 1);
        boolean diagonal = dx != 0 && dz != 0;
        int best = -1;
        for (int i = 0; i < c.ys().length; i++) {
            int ny = c.ys()[i];
            if (ny > y + 1 || ny < y - caps.maxDrop()) continue;
            if (best < 0 || Math.abs(ny - y) < Math.abs(c.ys()[best] - y)) best = i;
        }
        if (best < 0) return;
        int ny = c.ys()[best];
        byte f = c.flags()[best];
        // Subir: lugar para la cabeza al saltar. Bajar: el aire de la columna de al lado hasta donde se cae.
        if (ny == y + 1 && !free(level, x, y + 2, z)) return;
        if (ny < y) for (int yy = ny + 2; yy <= y + 1; yy++) if (!free(level, nx, yy, nz)) return;
        // Diagonal: sin cortar esquinas (los dos costados abiertos)
        if (diagonal) {
            int top = Math.max(y, ny);
            for (int yy = top; yy <= top + 1; yy++) {
                if (!free(level, nx, yy, z) || !free(level, x, yy, nz)) return;
            }
        }
        boolean water = (f & WATER) != 0;
        if (water && !caps.water()) return;
        if ((f & DOOR) != 0 && !caps.openDoors()) return;
        if (fromWater && !water && ny > y) {
            // Salir del agua: de a un escalón
            if (ny - y > 1) return;
        }
        float cost = diagonal ? 1.414F : 1.0F;
        if (ny > y) cost += 0.5F;
        if (ny < y) cost += 0.3F * (y - ny) + (y - ny > 3 ? 2.0F : 0);
        if (water) cost *= caps.waterCost();
        if ((f & DOOR) != 0) cost += 2.0F;
        Step.Kind kind = water ? Step.Kind.WATER : (f & DOOR) != 0 ? Step.Kind.DOOR : Step.Kind.WALK;
        out.edge(BlockPos.asLong(nx, ny, nz), cost, kind, 0);
    }

    /** Libre para el cuerpo (sin colisión; puertas/tranqueras cuentan como libres). */
    static boolean free(ServerLevel level, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return passable(level, p, level.getBlockState(p));
    }

    /**
     * Conexión apagada: trepar el muro de al lado con una escalera de mano (que todavía no está). Va a la parte de
     * arriba del muro, o directo al otro lado si arriba no hay dónde pararse (muro de un bloque de ancho).
     */
    private static void climb(ServerLevel level, int x, int y, int z, Direction d, NavCaps caps, EdgeSink out) {
        int wx = x + d.getStepX(), wz = z + d.getStepZ();
        BlockPos w = new BlockPos(wx, y, wz);
        if (!sturdy(level, w, d)) return;
        int t = y;
        while (t - y <= MAX_CLIMB && sturdy(level, new BlockPos(wx, t, wz), d)) t++;
        int h = t - y;
        if (h < 2 || h > MAX_CLIMB) return;
        // La columna de la escalera: libre (o con plantas) hasta arriba, y lugar para la cabeza al pasar
        for (int yy = y; yy <= t + 1; yy++) {
            BlockPos p = new BlockPos(x, yy, z);
            BlockState s = level.getBlockState(p);
            if (!s.canBeReplaced() && !(s.getBlock() instanceof LadderBlock)) return;
        }
        // Arriba del muro: lugar (una almena o una cerca se tiran)
        for (int yy = t; yy <= t + 1; yy++) if (!free(level, wx, yy, wz) && !removable(level.getBlockState(new BlockPos(wx, yy, wz)))) return;
        float cost = caps.climbCost() + h;
        Column top = column(level, wx, wz, t, t);
        if (top.index(t) >= 0 || hasFloorAt(level, wx, t, wz)) {
            out.edge(BlockPos.asLong(wx, t, wz), cost, Step.Kind.CLIMB, w.asLong());
            return;
        }
        // Muro de un bloque: del otro lado, cayendo
        int ix = wx + d.getStepX(), iz = wz + d.getStepZ();
        Column in = column(level, ix, iz, t - MAX_CLIMB - 2, t);
        for (int i = in.ys().length - 1; i >= 0; i--) {
            int iy = in.ys()[i];
            if (iy > t || iy < t - MAX_CLIMB - 2 || (in.flags()[i] & WATER) != 0) continue;
            out.edge(BlockPos.asLong(ix, iy, iz), cost + 1, Step.Kind.CLIMB, w.asLong());
            return;
        }
    }

    /** Se puede parar ahí una vez tirada la almena: libre (o almena) y con piso. */
    private static boolean hasFloorAt(ServerLevel level, int x, int y, int z) {
        boolean open = free(level, x, y, z) || removable(level.getBlockState(new BlockPos(x, y, z)));
        return open && hasFloor(level, x, y, z);
    }

    /** Una almena, una cerca o un pilar de muro arriba de la muralla: se tira para pasar. */
    static boolean removable(BlockState s) {
        return s.getBlock() instanceof WallBlock || s.getBlock() instanceof FenceBlock;
    }

    /** La cara del bloque que mira hacia nosotros aguanta una escalera. */
    static boolean sturdy(ServerLevel level, BlockPos p, Direction towardWall) {
        return level.getBlockState(p).isFaceSturdy(level, p, towardWall.getOpposite());
    }

    /** Conexión apagada: romper el muro de al lado (TNT, cañón) y salir del otro lado (hasta 4 de grueso). */
    private static void breach(ServerLevel level, int x, int y, int z, Direction d, NavCaps caps, EdgeSink out) {
        int wx = x + d.getStepX(), wz = z + d.getStepZ();
        if (free(level, wx, y, wz) && free(level, wx, y + 1, wz)) return;   // no hay muro
        for (int k = 1; k <= 4; k++) {
            int cx = x + d.getStepX() * k, cz = z + d.getStepZ() * k;
            for (int yy = y; yy <= y + 1; yy++) {
                BlockPos p = new BlockPos(cx, yy, cz);
                if (!free(level, cx, yy, cz) && !breakable(level, p)) return;
            }
            Column c = column(level, cx + d.getStepX(), cz + d.getStepZ(), y - 1, y + 1);
            for (int i = 0; i < c.ys().length; i++) {
                int ny = c.ys()[i];
                if (Math.abs(ny - y) > 1 || (c.flags()[i] & WATER) != 0) continue;
                out.edge(BlockPos.asLong(cx + d.getStepX(), ny, cz + d.getStepZ()), caps.breakCost() + 6 * k, Step.Kind.BREAK,
                        BlockPos.asLong(wx, y, wz));
                return;
            }
        }
    }

    /** Se puede romper con TNT: ni muy duro, ni con algo adentro. */
    static boolean breakable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        float hard = s.getDestroySpeed(level, p);
        return hard >= 0 && hard <= 10 && level.getBlockEntity(p) == null;
    }

    /** Para recorrer todas las columnas medidas de un mundo (pruebas). */
    static void forEachCached(Level level, LongConsumer c) {
        Map<Long, Column> m = CACHE.get(level.dimension());
        if (m != null) m.keySet().forEach(c::accept);
    }
}
