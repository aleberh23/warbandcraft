package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Asedios (invasiones con muros): dónde está la muralla mirando desde afuera, por dónde se entra (puertas y
 * brechas), y las escaleras de mano. Todo se mide caminando por el piso en línea recta desde afuera hacia el
 * centro: así sirve para la empalizada/muralla de H12 y para los muros del castillo, sin saber cómo son.
 */
public final class Breach {
    /** Castillo: sus muros están a 21-31 del centro (esquinas); afuera se cuenta desde acá. */
    public static int CASTLE_RADIUS = 31;
    /** Muros más altos que esto no se escalan. */
    public static int MAX_LADDER = 12;
    /** Cuánto se busca una puerta/brecha a los costados del lado de ataque (grados). */
    public static int GATE_SEARCH_DEG = 48, GATE_STEP_DEG = 6;

    private Breach() {}

    /**
     * Los muros de una aldea/castillo. {@code radius}: de dónde se cuenta lo de afuera (formar, arqueros); un
     * obstáculo entre {@code bandMin} y {@code bandMax} del centro es el muro (más afuera es un árbol, más adentro una
     * casa); a menos de {@code inside} ya entró; {@code inner}: a dónde va al entrar.
     */
    public record Walls(int radius, boolean walled, double bandMin, double bandMax, double inside, double inner) {
        public double probeFrom() {
            return radius + 8;
        }

        public double probeTo() {
            return Math.max(2, bandMin - 1);
        }

        /** ¿El obstáculo que encontró la medición es el muro? */
        public boolean isWall(Probe p) {
            return p.blocked() && p.distance() >= bandMin && p.distance() <= bandMax;
        }
    }

    public static Walls walls(ServerLevel level, VillageData.Village v) {
        if (v.castle()) return new Walls(CASTLE_RADIUS, true, 10, CASTLE_RADIUS + 3, 19, 10);
        TownData.Town t = TownData.get(level).at(v.center());
        if (t != null && !t.defense.isEmpty()) {   // H22c: muralla dibujada: su radio medio
            int r = Math.max(6, com.ale.warbandcraft.upgrade.Works.radius(t));
            return new Walls(r, true, r - 4, r + 2.5, r - 1.5, Math.max(3, r - 8));
        }
        if (t != null && (t.has(Upgrade.PALISADE) || t.has(Upgrade.WALL)) && t.ringRadius > 0) {
            int r = t.ringRadius;
            return new Walls(r, true, r - 4, r + 2.5, r - 1.5, Math.max(3, r - 8));
        }
        int r = t != null && t.ringRadius > 0 ? t.ringRadius : Blueprints.ringRadius(level, v.center());
        return new Walls(r, false, 0, 0, r * 0.6, r * 0.3);
    }

    /** Punto horizontal a {@code dist} del centro en el ángulo {@code angle} (radianes). */
    public static Vec3 at(BlockPos center, double angle, double dist) {
        return new Vec3(center.getX() + 0.5 + Math.cos(angle) * dist, center.getY(), center.getZ() + 0.5 + Math.sin(angle) * dist);
    }

    /** Lugar para pararse cerca de {@code p} (a la altura del piso), o {@code p} si no hay. */
    public static Vec3 ground(ServerLevel level, Vec3 p, int yHint) {
        Integer y = Ground.surfaceY(level, (int) Math.floor(p.x), (int) Math.floor(p.z), yHint, 16, 24);
        return new Vec3(p.x, y != null ? y : yHint, p.z);
    }

    // ------------------------------------------------------------------ medir

    /**
     * Lo que hay caminando desde afuera hacia el centro. {@code blocked}: el primer obstáculo que no se sube de un
     * paso; {@code outside} = la última columna libre (donde va la escalera), con los pies en {@code feetY};
     * {@code wall} = la columna del muro, pegada por un lado.
     */
    public record Probe(boolean blocked, BlockPos outside, BlockPos wall, int feetY, double distance) {
        public static Probe open() {
            return new Probe(false, BlockPos.ZERO, BlockPos.ZERO, 0, 0);
        }

        /** No se pudo medir (sin cargar, sin piso): no es ni abierto ni muro. */
        public static Probe unknown() {
            return new Probe(true, BlockPos.ZERO, BlockPos.ZERO, 0, -1);
        }
    }

    /** Camina por el piso de {@code from} a {@code to} bloques del centro, en el ángulo {@code angle}. */
    public static Probe probe(ServerLevel level, BlockPos center, double angle, double from, double to) {
        Vec3 start = at(center, angle, from);
        int x0 = (int) Math.floor(start.x), z0 = (int) Math.floor(start.z);
        Integer y0 = Ground.surfaceY(level, x0, z0, center.getY(), 16, 24);
        if (y0 == null || !level.getFluidState(new BlockPos(x0, y0, z0)).isEmpty()
                || !level.getFluidState(new BlockPos(x0, y0 - 1, z0)).isEmpty()) return Probe.unknown();
        BlockPos prev = new BlockPos(x0, y0, z0);
        int y = y0;
        for (double d = from - 0.5; d >= to; d -= 0.5) {
            Vec3 p = at(center, angle, d);
            int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
            if (x == prev.getX() && z == prev.getZ()) continue;
            if (!level.isLoaded(new BlockPos(x, y, z))) return Probe.unknown();
            Integer next = step(level, x, z, y);
            // En diagonal hay que pasar por uno de los dos costados: dos bloques que se tocan por la esquina (los
            // anillos de muro están hechos así) no dejan pasar a nadie
            if (next != null && x != prev.getX() && z != prev.getZ()
                    && step(level, x, prev.getZ(), y) == null && step(level, prev.getX(), z, y) == null) {
                BlockPos a = new BlockPos(x, y, prev.getZ()), b = new BlockPos(prev.getX(), y, z);
                return new Probe(true, prev, solid(level, a) ? a : b, y, d);
            }
            if (next == null) {
                BlockPos wall = new BlockPos(x, y, z);
                BlockPos outside = prev;
                // La escalera tiene que quedar pegada por un lado (no en diagonal)
                if (x != prev.getX() && z != prev.getZ()) {
                    BlockPos a = new BlockPos(x, y, prev.getZ()), b = new BlockPos(prev.getX(), y, z);
                    if (solid(level, a)) wall = a;
                    else if (solid(level, b)) wall = b;
                    else if (Ground.canStand(level, a)) outside = a;
                    else if (Ground.canStand(level, b)) outside = b;
                    else return new Probe(true, prev, wall, y, d);
                }
                return new Probe(true, outside, wall, y, d);
            }
            y = next;
            prev = new BlockPos(x, y, z);
        }
        return Probe.open();
    }

    /**
     * La altura de los pies en la columna siguiente: igual, un escalón arriba o hasta 3 abajo. Null = obstáculo. El
     * agua también es obstáculo: los invasores no nadan (la rodean).
     */
    @Nullable
    private static Integer step(ServerLevel level, int x, int z, int y) {
        BlockPos same = new BlockPos(x, y, z);
        if (!level.getFluidState(same).isEmpty()) return null;
        if (Ground.canStand(level, same) && dry(level, same)) return y;
        if (Ground.canStand(level, same.above()) && free(level, same.above(2)) && dry(level, same.above())) return y + 1;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos down = same.below(dy);
            if (!free(level, down.above()) || !level.getFluidState(down.above()).isEmpty()) break;
            if (!level.getFluidState(down).isEmpty()) return null;
            if (Ground.canStand(level, down)) return y - dy;
        }
        return null;
    }

    /** Sin agua en los pies ni abajo. */
    public static boolean dry(ServerLevel level, BlockPos feet) {
        return level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty();
    }

    /** Un lugar seco para pararse cerca de {@code p} (hasta 6 bloques), o {@code p} si no hay. */
    public static Vec3 dryNear(ServerLevel level, Vec3 p) {
        BlockPos b = BlockPos.containing(p);
        if (Ground.canStand(level, b) && dry(level, b)) return p;
        for (int r = 1; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos q = b.offset(dx, dy, dz);
                    if (Ground.canStand(level, q) && dry(level, q)) return Vec3.atBottomCenterOf(q);
                }
            }
        }
        return p;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        return !free(level, p);
    }

    /**
     * El ángulo abierto (puerta o brecha) más cercano a {@code angle}, o null si todo está cerrado. Se prueba cada
     * ~2 bloques de muro, para no saltearse una puerta de 3.
     */
    @Nullable
    public static Double openAngle(ServerLevel level, BlockPos center, Walls w, double angle, java.util.List<Double> bad) {
        if (!w.walled()) return angle;
        double step = Math.min(Math.toRadians(GATE_STEP_DEG), 2.0 / Math.max(8, w.radius()));
        int n = (int) Math.ceil(Math.toRadians(GATE_SEARCH_DEG) / step);
        for (int k = 0; k <= n; k++) {
            for (int sign : k == 0 ? new int[]{1} : new int[]{1, -1}) {
                double a = angle + sign * k * step;
                if (near(a, bad, 4.0 / Math.max(8, w.radius()))) continue;   // por ahí ya no pudieron pasar
                if (!probe(level, center, a, w.probeFrom(), w.probeTo()).blocked()) return a;
            }
        }
        return null;
    }

    /** ¿{@code a} está a menos de {@code tolerance} radianes de alguno de {@code list}? */
    public static boolean near(double a, java.util.List<Double> list, double tolerance) {
        for (double b : list) if (Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))) < tolerance) return true;
        return false;
    }

    // ------------------------------------------------------------------ túneles

    /** Un tramo del túnel: los pies y cuántos bloques de alto hay que cavar (3 donde cambia de nivel). */
    public record TunnelCell(BlockPos feet, int height) {}

    /** Túnel: cuánto baja, cuánto puede medir como mucho y cuán duro puede ser lo que cava. */
    public static int TUNNEL_DEPTH = 3, TUNNEL_MAX = 64, TUNNEL_RISE = 16;
    public static float TUNNEL_HARDNESS = 20;

    /**
     * Un túnel para pasar por debajo del muro: arranca 2 bloques afuera del pie del muro ({@code foot}, pegado a
     * {@code wall}), baja {@link #TUNNEL_DEPTH}, sigue derecho por abajo hasta estar adentro y sube hasta salir a la
     * superficie. Null si no se puede (agua, algo que no se rompe, muy largo). El primero de la lista es la entrada
     * (donde se para el zapador, no se cava).
     */
    @Nullable
    public static List<TunnelCell> tunnel(ServerLevel level, BlockPos foot, BlockPos wall, BlockPos center, Walls w) {
        Direction d = Direction.getNearest(wall.getX() - foot.getX(), 0, wall.getZ() - foot.getZ());
        if (d.getAxis() == Direction.Axis.Y) return null;
        int y = foot.getY();
        BlockPos start = foot.relative(d.getOpposite(), 2).atY(y);
        if (!Ground.canStand(level, start) || !dry(level, start)) return null;
        List<TunnelCell> cells = new ArrayList<>();
        cells.add(new TunnelCell(start, 0));
        BlockPos cur = start;
        for (int k = 1; k <= TUNNEL_DEPTH; k++) {   // bajando, de a un escalón
            cur = cur.relative(d).atY(--y);
            cells.add(new TunnelCell(cur, 3));
        }
        for (int n = 0; ; n++) {   // derecho por abajo hasta estar adentro
            cur = cur.relative(d);
            cells.add(new TunnelCell(cur, 2));
            double along = (cur.getX() - wall.getX()) * d.getStepX() + (cur.getZ() - wall.getZ()) * d.getStepZ();
            double dx = cur.getX() - center.getX(), dz = cur.getZ() - center.getZ();
            if (along >= 3 && Math.sqrt(dx * dx + dz * dz) <= w.inside() - 1) break;
            if (cells.size() > TUNNEL_MAX) return null;
        }
        // El último tramo de abajo, de 3 de alto: para subir el primer escalón hace falta lugar para la cabeza
        TunnelCell last = cells.remove(cells.size() - 1);
        cells.add(new TunnelCell(last.feet(), 3));
        for (int up = 0; up < TUNNEL_RISE; up++) {   // subiendo hasta la superficie (adentro puede estar más alto que afuera)
            cur = cur.relative(d).atY(++y);
            boolean open = level.getBlockState(cur).isAir() && level.getBlockState(cur.above()).isAir();
            cells.add(new TunnelCell(cur, open ? 2 : 3));
            if (open) return valid(level, cells) ? cells : null;
        }
        return null;
    }

    /** Todo lo que hay que cavar se puede cavar (sin agua al lado, sin cofres, nada muy duro). */
    private static boolean valid(ServerLevel level, List<TunnelCell> cells) {
        for (TunnelCell c : cells) {
            if (!level.isLoaded(c.feet())) return false;
            for (int h = 0; h < c.height(); h++) {
                BlockPos p = c.feet().above(h);
                if (!diggable(level, p)) return false;
                for (Direction dir : Direction.values()) {
                    if (dir == Direction.DOWN) continue;
                    if (!level.getFluidState(p.relative(dir)).isEmpty()) return false;
                }
            }
        }
        return true;
    }

    /** Se puede cavar (o ya está vacío). */
    public static boolean diggable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty() || level.getBlockEntity(p) != null) return false;
        float hard = s.getDestroySpeed(level, p);
        return hard >= 0 && hard <= TUNNEL_HARDNESS;
    }

    // ------------------------------------------------------------------ escaleras

    /** Una escalera puesta: columna de afuera, piso, hacia dónde queda el muro y a qué altura se pasa por arriba. */
    public record Ladder(BlockPos base, Direction intoWall, int topFeet) {
        public BlockPos wallTop() {
            return base.relative(intoWall).atY(topFeet);
        }

        /** La columna de adentro, al pie del muro. */
        public BlockPos inside() {
            return base.relative(intoWall, 2).atY(topFeet);
        }

        public long key() {
            return base.asLong();
        }
    }

    /**
     * Pone una escalera contra el muro que encontró {@code probe}: de los pies de la columna de afuera hasta arriba.
     * Si hay una almena arriba, la tiran. Null si no se puede (muro muy alto, algo en el medio).
     */
    @Nullable
    public static Ladder placeLadder(ServerLevel level, Probe probe) {
        if (!probe.blocked()) return null;
        BlockPos base = probe.outside().atY(probe.feetY());
        BlockPos wall = probe.wall().atY(probe.feetY());
        Direction into = Direction.getNearest(wall.getX() - base.getX(), 0, wall.getZ() - base.getZ());
        if (into.getAxis() == Direction.Axis.Y || !base.relative(into).equals(wall)) return null;
        int top = probe.feetY();
        // Hasta el último bloque donde se apoya una escalera (una almena o una cerca arriba no: se tira)
        while (top - probe.feetY() <= MAX_LADDER && level.getBlockState(wall.atY(top)).isFaceSturdy(level, wall.atY(top), into.getOpposite())) top++;
        if (top - probe.feetY() > MAX_LADDER || top == probe.feetY()) return null;
        // Arriba del muro: lugar para pasar (una almena se tira)
        BlockPos over = wall.atY(top);
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = over.above(dy);
            if (free(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (level.getBlockEntity(p) != null || s.getDestroySpeed(level, p) < 0) return null;
            level.destroyBlock(p, false);
        }
        if (solid(level, base.atY(top)) || solid(level, base.atY(top + 1))) return null;
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, into.getOpposite());
        for (int y = probe.feetY(); y < top; y++) {
            BlockPos p = base.atY(y);
            BlockState old = level.getBlockState(p);
            if (old.is(Blocks.LADDER)) continue;
            if (!old.canBeReplaced() || !ladder.canSurvive(level, p)) return null;
        }
        for (int y = probe.feetY(); y < top; y++) {
            BlockPos p = base.atY(y);
            if (!level.getBlockState(p).is(Blocks.LADDER)) level.setBlock(p, ladder, 3);
        }
        level.playSound(null, base, SoundEvents.LADDER_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ladder), base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5,
                8, 0.3, 1.0, 0.3, 0.05);
        return new Ladder(base, into, top);
    }

    /** Saca una escalera puesta por los invasores (toda la columna, de abajo hacia arriba). */
    public static void removeLadder(ServerLevel level, BlockPos base) {
        if (!level.isLoaded(base)) return;
        for (int y = base.getY(); y <= base.getY() + MAX_LADDER + 1; y++) {
            BlockPos p = base.atY(y);
            if (!level.getBlockState(p).is(Blocks.LADDER)) {
                if (y > base.getY()) break;
                continue;
            }
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
