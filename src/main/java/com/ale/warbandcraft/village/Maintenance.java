package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.BuildCost;
import com.ale.warbandcraft.upgrade.Heraldry;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pedido de Ale: el trabajador de Mantenimiento (vive en la caseta de mantenimiento, {@link Upgrade#MAINTENANCE})
 * embellece la aldea de a una tarea por vez: estandartes de la facción a los costados de cada portón y en las torres,
 * caminitos de tierra entre el centro, los edificios y los portones (con alguna flor al costado) y faroles a lo largo de
 * los caminitos donde está oscuro. Nunca pisa un edificio, una casa, una zona de Villager Workers, la muralla ni una
 * estructura de otro mod: solo trabaja sobre piso natural a cielo abierto. Gasta madera, piedra y esmeraldas del almacén de
 * la aldea; sin eso, deja de embellecer hasta que haya.
 */
public final class Maintenance {
    /** Hasta dónde trabaja (desde el centro, y siempre adentro del territorio). */
    public static int RANGE = 56;
    /** Luz de bloque por debajo de esto = hace falta un farol; separación mínima entre faroles a lo largo del caminito. */
    public static int LAMP_LIGHT = 8, LAMP_EVERY = 8;
    /** Bloques de caminito por tarea, y cada cuántos va una flor al costado. */
    public static int PATH_STEP = 12, FLOWER_EVERY = 5;
    /** Lo que cuesta cada cosa (materiales del almacén y esmeraldas). */
    public static int BANNER_WOOD = 2, BANNER_EMERALDS = 1, LAMP_STONE = 2, LAMP_EMERALDS = 1, PATH_STONE = 1, PATH_WOOD = 1;
    /** Lugares que prueba el buscador de caminitos (por destino). */
    public static int PATH_SEARCH = 6000;
    /** Cada cuánto se vuelven a medir los caminitos de una aldea (ticks). */
    public static int REPLAN_TICKS = 20 * 300;

    private Maintenance() {}

    public enum Kind { BANNER, LAMP, PATH }

    /** Una tarea: dónde se para, los bloques, lo que cuesta y qué es (para el cartelito). */
    public record Task(Kind kind, BlockPos stand, Blueprints.Plan plan, BuildCost.Bill bill, String what) {}

    /** Los caminitos medidos de cada aldea (en memoria): columnas de cada uno, desde el centro. */
    private record Routes(long tick, int sites, List<List<BlockPos>> paths) {}

    private static final Map<String, Routes> ROUTES = new HashMap<>();

    private static String key(ServerLevel level, BlockPos center) {
        return level.dimension().location() + "@" + center.asLong();
    }

    // ------------------------------------------------------------------ qué hacer

    /** La próxima tarea de la aldea (null = está todo lindo). */
    @Nullable
    public static Task next(ServerLevel level, VillageData.Village v, TownData.Town t) {
        Task task = gateBanner(level, v, t);
        if (task == null) task = towerBanner(level, v, t);
        Area area = null;
        if (task == null) {
            area = new Area(level, v, t);
            task = lamp(level, area, routes(level, area));
        }
        if (task == null) task = path(level, area, routes(level, area));
        return task;
    }

    /** Hace la tarea: cobra y pone los bloques. Null = hecha; si no, lo que falta en el almacén. */
    @Nullable
    public static String apply(ServerLevel level, VillageData.Village v, TownData.Town t, Task task) {
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp == null) return "sin almacén";
        String lack = BuildCost.missing(task.bill(), camp, null);
        int em = task.bill().emeralds();
        if (em > 0 && VillageStock.total(level, v).emeralds() < em) lack = (lack == null ? "" : lack + " · ") + em + " esm.";
        if (lack != null) return lack;
        BuildCost.pay(task.bill(), camp, null);
        if (em > 0) VillageStock.takeEmeralds(level, v, em);
        Heraldry.Colors colors = Heraldry.of(v);
        for (Map.Entry<BlockPos, BlockState> e : task.plan().blocks.entrySet()) {
            BlockPos p = e.getKey();
            BlockState s = e.getValue();
            if (level.getBlockEntity(p) != null) continue;
            BlockState old = level.getBlockState(p);
            if (!old.isAir() && old.canBeReplaced() && s.isAir()) {
                level.destroyBlock(p, false);
                continue;
            }
            if (s.getBlock() instanceof net.minecraft.world.level.block.WallBlock) s = Block.updateFromNeighbourShapes(s, level, p);
            level.setBlock(p, s, 3);
            if (s.getBlock() instanceof WallBannerBlock || s.is(Blocks.LANTERN)) {
                t.decor.add(p.immutable());
                if (colors != null) Heraldry.paint(level, p, colors);
            }
        }
        if (task.kind() == Kind.PATH) {
            level.playSound(null, task.stand(), net.minecraft.sounds.SoundEvents.SHOVEL_FLATTEN, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1F);
        }
        TownData.get(level).changed();
        return null;
    }

    private static BuildCost.Bill bill(int wood, int stone, int emeralds) {
        int[] a = new int[BuildCost.Material.values().length];
        a[BuildCost.Material.WOOD.ordinal()] = wood;
        a[BuildCost.Material.STONE.ordinal()] = stone;
        return new BuildCost.Bill(a, emeralds, 0);
    }

    // ------------------------------------------------------------------ estandartes

    private static DyeColor dye(VillageData.Village v) {
        Heraldry.Colors c = Heraldry.of(v);
        return c == null ? DyeColor.WHITE : c.dye();
    }

    private static BlockState wallBanner(DyeColor dye, Direction facing) {
        BlockState s = Heraldry.tinted(Blocks.WHITE_WALL_BANNER.defaultBlockState(), dye);
        return (s == null ? Blocks.WHITE_WALL_BANNER.defaultBlockState() : s).setValue(WallBannerBlock.FACING, facing);
    }

    /** Lugar libre para colgar un estandarte en {@code p} de la pared {@code wall} (atrás). */
    private static boolean bannerSpot(ServerLevel level, BlockPos p, BlockPos wall) {
        if (!level.isLoaded(p) || !level.getBlockState(p).isAir() || level.getBlockEntity(p) != null) return false;
        if (!level.getBlockState(p.below()).isAir() && !level.getBlockState(p.below()).canBeReplaced()) return false;   // cuelga 2 de alto
        BlockState w = level.getBlockState(wall);
        return w.isFaceSturdy(level, wall, Direction.getNearest(p.getX() - wall.getX(), 0, p.getZ() - wall.getZ()));
    }

    /** A los costados de cada portón, del lado de afuera, un estandarte en cada poste. */
    @Nullable
    private static Task gateBanner(ServerLevel level, VillageData.Village v, TownData.Town t) {
        for (BlockPos g : t.gates) {
            if (!level.isLoaded(g)) continue;
            BlockState s = level.getBlockState(g);
            if (!(s.getBlock() instanceof FenceGateBlock)) continue;
            Direction pass = s.getValue(FenceGateBlock.FACING);
            if ((g.getX() - v.center().getX()) * pass.getStepX() + (g.getZ() - v.center().getZ()) * pass.getStepZ() < 0) pass = pass.getOpposite();
            Direction along = pass.getClockWise();
            for (Direction side : new Direction[]{along, along.getOpposite()}) {
                BlockPos post = g;
                for (int k = 0; k < 6 && level.getBlockState(post).getBlock() instanceof FenceGateBlock; k++) post = post.relative(side);
                if (level.getBlockState(post).getBlock() instanceof FenceGateBlock || level.getBlockState(post).isAir()) continue;
                BlockPos wall = post.above();
                BlockPos spot = wall.relative(pass);
                if (t.decor.contains(spot) || !bannerSpot(level, spot, wall)) continue;
                Blueprints.Plan plan = new Blueprints.Plan();
                plan.put(spot, wallBanner(dye(v), pass));
                return new Task(Kind.BANNER, spot.below(), plan, bill(BANNER_WOOD, 0, BANNER_EMERALDS), "estandarte en un portón");
            }
        }
        return null;
    }

    /** En las cuatro caras de cada torre, arriba, un estandarte. */
    @Nullable
    private static Task towerBanner(ServerLevel level, VillageData.Village v, TownData.Town t) {
        for (BlockPos c : t.towers) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos wall = c.offset(d.getStepX() * 2, 6, d.getStepZ() * 2);
                BlockPos spot = wall.relative(d);
                if (t.decor.contains(spot) || !level.isLoaded(spot) || !bannerSpot(level, spot, wall)) continue;
                Blueprints.Plan plan = new Blueprints.Plan();
                plan.put(spot, wallBanner(dye(v), d));
                return new Task(Kind.BANNER, c.offset(d.getStepX() * 4, 1, d.getStepZ() * 4), plan,
                        bill(BANNER_WOOD, 0, BANNER_EMERALDS), "estandarte en una torre");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ dónde se puede tocar

    /** Lo que no se pisa: terrenos, zonas de Workers, muralla y torres; y el piso de cada columna (cacheado). */
    static final class Area {
        final ServerLevel level;
        final VillageData.Village v;
        final TownData.Town t;
        final List<Blueprints.Lot> lots;
        final List<AABB> zones = new ArrayList<>();
        final Set<Long> walls = new HashSet<>();
        private final Map<Long, Integer> ground = new HashMap<>();

        Area(ServerLevel level, VillageData.Village v, TownData.Town t) {
            this.level = level;
            this.v = v;
            this.t = t;
            this.lots = t.lots(null);
            for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class,
                    new AABB(v.center()).inflate(RANGE + 32, 64, RANGE + 32), a -> a.isAlive() && a.getArea() != null)) {
                zones.add(a.getArea());
            }
            for (long l : t.defense.keySet()) walls.add(BlockPos.asLong(BlockPos.getX(l), 0, BlockPos.getZ(l)));
            for (BlockPos g : t.gates) walls.add(BlockPos.asLong(g.getX(), 0, g.getZ()));
        }

        /** La columna está ocupada por algo que no se toca. */
        boolean blocked(int x, int z) {
            for (Blueprints.Lot l : lots) {
                BlockPos c = l.corner();
                if (x >= c.getX() && x < c.getX() + l.size() && z >= c.getZ() && z < c.getZ() + l.size()) return true;
            }
            for (AABB b : zones) if (x + 0.5 >= b.minX && x + 0.5 <= b.maxX && z + 0.5 >= b.minZ && z + 0.5 <= b.maxZ) return true;
            if (walls.contains(BlockPos.asLong(x, 0, z))) return true;
            for (BlockPos tw : t.towers) if (Math.abs(x - tw.getX()) <= 2 && Math.abs(z - tw.getZ()) <= 2) return true;
            int dx = x - v.center().getX(), dz = z - v.center().getZ();
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) return true;   // la campana
            return dx * dx + dz * dz > RANGE * RANGE || !com.ale.warbandcraft.upgrade.Placement.inside(level, v, x, z);
        }

        /** El piso de la columna si se puede andar (natural o caminito, cielo abierto, nada arriba); null si no. */
        @Nullable
        Integer floor(int x, int z) {
            long k = BlockPos.asLong(x, 0, z);
            if (ground.containsKey(k)) return ground.get(k);
            Integer out = null;
            if (!blocked(x, z)) {
                Integer g = Blueprints.groundY(level, x, z, v.center().getY());
                if (g != null) {
                    BlockPos p = new BlockPos(x, g, z);
                    BlockState s = level.getBlockState(p);
                    BlockState up = level.getBlockState(p.above()), up2 = level.getBlockState(p.above(2));
                    boolean free = (up.isAir() || up.canBeReplaced() && up.getFluidState().isEmpty() && !up.is(BlockTags.LEAVES)) && up2.isAir();
                    if (s.getFluidState().isEmpty() && (Blueprints.natural(s) || isPath(s)) && free && level.canSeeSky(p.above())
                            && level.getBlockEntity(p) == null && !com.ale.warbandcraft.dungeon.Compat.foreignStructureAt(level, p)) out = g;
                }
            }
            ground.put(k, out);
            return out;
        }
    }

    static boolean isPath(BlockState s) {
        return s.is(Blocks.DIRT_PATH) || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.GRAVEL);
    }

    /** El bloque de caminito para ese piso (null = no se cambia). */
    @Nullable
    private static BlockState pathOf(BlockState s) {
        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.ROOTED_DIRT)) return Blocks.DIRT_PATH.defaultBlockState();
        if (s.is(BlockTags.SAND)) return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        return null;
    }

    // ------------------------------------------------------------------ caminitos

    /** Los destinos: la puerta de cada edificio y casa (el lado de la puerta, afuera) y el lado de adentro de cada portón. */
    private static List<int[]> targets(Area a) {
        List<int[]> out = new ArrayList<>();
        TownData.Town t = a.t;
        for (Map.Entry<String, BlockPos> e : t.sites.entrySet()) {
            BlockPos c = e.getValue();
            int size = t.siteSize(e.getKey()), m = size - 1, h = size / 2;
            int[] d = switch (Math.floorMod(t.rotations.getOrDefault(e.getKey(), 0), 4)) {   // sin girar la puerta mira al norte
                case 1 -> new int[]{m + 1, h};
                case 2 -> new int[]{m - h, m + 1};
                case 3 -> new int[]{-1, m - h};
                default -> new int[]{h, -1};
            };
            out.add(new int[]{c.getX() + d[0], c.getZ() + d[1]});
        }
        BlockPos center = a.v.center();
        for (BlockPos g : t.gates) {
            double dx = center.getX() - g.getX(), dz = center.getZ() - g.getZ(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            out.add(new int[]{g.getX() + (int) Math.round(dx / len * 2), g.getZ() + (int) Math.round(dz / len * 2)});
        }
        out.sort((p, q) -> Double.compare(distSq(p, center), distSq(q, center)));
        return out;
    }

    private static double distSq(int[] p, BlockPos c) {
        double dx = p[0] - c.getX(), dz = p[1] - c.getZ();
        return dx * dx + dz * dz;
    }

    /** Los caminitos de la aldea, medidos de nuevo cada {@link #REPLAN_TICKS} o cuando cambian los edificios. */
    private static List<List<BlockPos>> routes(ServerLevel level, Area a) {
        String k = key(level, a.v.center());
        Routes r = ROUTES.get(k);
        int sites = a.t.sites.size() + a.t.gates.size();
        if (r != null && r.sites == sites && level.getGameTime() - r.tick < REPLAN_TICKS) return r.paths;
        List<List<BlockPos>> paths = new ArrayList<>();
        int[] start = startOf(a);
        if (start != null) {
            for (int[] target : targets(a)) {
                List<BlockPos> p = search(a, start, target);
                if (p != null && p.size() > 1) paths.add(p);
            }
        }
        ROUTES.put(k, new Routes(level.getGameTime(), sites, paths));
        return paths;
    }

    /** Dónde arrancan: el lugar libre más cerca de la campana. */
    @Nullable
    private static int[] startOf(Area a) {
        BlockPos c = a.v.center();
        for (int r = 2; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                if (a.floor(c.getX() + dx, c.getZ() + dz) != null) return new int[]{c.getX() + dx, c.getZ() + dz};
            }
        }
        return null;
    }

    private record Node(int x, int z, int y, double g, double f) {}

    /**
     * El caminito de {@code from} a {@code to} (A* en 4 direcciones por piso andable, escalones de 1 como mucho; lo que ya
     * es camino cuesta menos). Termina en el lugar andable más cerca del destino. Null si no hay.
     */
    @Nullable
    static List<BlockPos> search(Area a, int[] from, int[] to) {
        Integer y0 = a.floor(from[0], from[1]);
        if (y0 == null) return null;
        PriorityQueue<Node> open = new PriorityQueue<>((p, q) -> Double.compare(p.f, q.f));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Integer> ys = new HashMap<>();
        long startKey = BlockPos.asLong(from[0], 0, from[1]);
        open.add(new Node(from[0], from[1], y0, 0, h(from[0], from[1], to)));
        best.put(startKey, 0.0);
        ys.put(startKey, y0);
        long end = startKey;
        double endH = h(from[0], from[1], to);
        int n = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty() && n++ < PATH_SEARCH) {
            Node cur = open.poll();
            long ck = BlockPos.asLong(cur.x, 0, cur.z);
            if (cur.g > best.getOrDefault(ck, Double.MAX_VALUE) + 1e-9) continue;
            double hh = h(cur.x, cur.z, to);
            if (hh < endH) {
                endH = hh;
                end = ck;
            }
            if (hh < 1.01) break;
            for (int[] d : dirs) {
                int x = cur.x + d[0], z = cur.z + d[1];
                Integer y = a.floor(x, z);
                if (y == null || Math.abs(y - cur.y) > 1) continue;
                BlockState s = a.level.getBlockState(new BlockPos(x, y, z));
                double g = cur.g + (isPath(s) ? 0.4 : 1.0) + (y != cur.y ? 1.5 : 0);
                long k = BlockPos.asLong(x, 0, z);
                if (g >= best.getOrDefault(k, Double.MAX_VALUE)) continue;
                best.put(k, g);
                parent.put(k, ck);
                ys.put(k, y);
                open.add(new Node(x, z, y, g, g + h(x, z, to) * 0.4));
            }
        }
        if (endH > 3) return null;   // no llega
        List<BlockPos> out = new ArrayList<>();
        for (Long k = end; k != null; k = parent.get(k)) out.add(0, new BlockPos(BlockPos.getX(k), ys.get(k), BlockPos.getZ(k)));
        return out;
    }

    private static double h(int x, int z, int[] to) {
        return Math.abs(x - to[0]) + Math.abs(z - to[1]);
    }

    /** El próximo tramo de caminito sin hacer (con alguna flor al costado). */
    @Nullable
    private static Task path(ServerLevel level, Area a, List<List<BlockPos>> routes) {
        for (List<BlockPos> route : routes) {
            Blueprints.Plan plan = new Blueprints.Plan();
            BlockPos stand = null;
            int done = 0;
            for (int i = 0; i < route.size() && done < PATH_STEP; i++) {
                BlockPos p = route.get(i);
                if (a.floor(p.getX(), p.getZ()) == null) continue;   // cambió algo: se salta
                BlockState s = level.getBlockState(p);
                BlockState to = pathOf(s);
                if (to == null) continue;   // ya es caminito (o piedra)
                plan.put(p, to);
                BlockState up = level.getBlockState(p.above());
                if (!up.isAir()) plan.put(p.above(), Blocks.AIR.defaultBlockState());   // pasto, flores
                if (stand == null) stand = p.above();
                done++;
                if (done % FLOWER_EVERY == 0) flower(level, a, route, i, plan);
            }
            if (done > 0) return new Task(Kind.PATH, stand, plan, bill(PATH_WOOD, PATH_STONE, 0), "caminito");
        }
        return null;
    }

    private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER,
            Blocks.RED_TULIP, Blocks.WHITE_TULIP, Blocks.ALLIUM};

    /** Una flor en el pasto al costado del caminito en {@code i}. */
    private static void flower(ServerLevel level, Area a, List<BlockPos> route, int i, Blueprints.Plan plan) {
        BlockPos p = route.get(i);
        BlockPos prev = route.get(Math.max(0, i - 1));
        Direction along = Direction.getNearest(p.getX() - prev.getX(), 0, p.getZ() - prev.getZ());
        if (along.getAxis() == Direction.Axis.Y) return;
        for (Direction side : new Direction[]{along.getClockWise(), along.getCounterClockWise()}) {
            BlockPos q = p.relative(side);
            Integer y = a.floor(q.getX(), q.getZ());
            if (y == null || route.contains(new BlockPos(q.getX(), y, q.getZ()))) continue;
            BlockPos g = new BlockPos(q.getX(), y, q.getZ());
            if (!level.getBlockState(g).is(Blocks.GRASS_BLOCK) || !level.getBlockState(g.above()).isAir()) continue;
            plan.put(g.above(), FLOWERS[Math.floorMod(g.getX() * 31 + g.getZ() * 17, FLOWERS.length)].defaultBlockState());
            return;
        }
    }

    // ------------------------------------------------------------------ faroles

    /** Un farol (dos muros de adoquín y un farol arriba) al costado de un caminito hecho donde está oscuro. */
    @Nullable
    private static Task lamp(ServerLevel level, Area a, List<List<BlockPos>> routes) {
        for (List<BlockPos> route : routes) {
            for (int i = LAMP_EVERY / 2; i < route.size(); i += LAMP_EVERY) {
                BlockPos p = route.get(i);
                if (!isPath(level.getBlockState(p))) continue;   // primero el caminito
                BlockPos prev = route.get(i - 1);
                Direction along = Direction.getNearest(p.getX() - prev.getX(), 0, p.getZ() - prev.getZ());
                if (along.getAxis() == Direction.Axis.Y) continue;
                for (Direction side : new Direction[]{along.getClockWise(), along.getCounterClockWise()}) {
                    BlockPos q = p.relative(side);
                    Integer y = a.floor(q.getX(), q.getZ());
                    if (y == null) continue;
                    BlockPos base = new BlockPos(q.getX(), y, q.getZ());
                    if (isPath(level.getBlockState(base)) || routeHas(routes, base)) continue;   // no tapa ningún caminito
                    if (level.getBrightness(LightLayer.BLOCK, base.above()) >= LAMP_LIGHT) break;   // ya hay luz
                    if (!level.getBlockState(base.above(3)).isAir()) continue;
                    Blueprints.Plan plan = new Blueprints.Plan();
                    plan.put(base.above(), Blocks.COBBLESTONE_WALL.defaultBlockState());
                    plan.put(base.above(2), Blocks.COBBLESTONE_WALL.defaultBlockState());
                    plan.put(base.above(3), Blocks.LANTERN.defaultBlockState());
                    return new Task(Kind.LAMP, p.above(), plan, bill(0, LAMP_STONE, LAMP_EMERALDS), "farol");
                }
            }
        }
        return null;
    }

    private static boolean routeHas(List<List<BlockPos>> routes, BlockPos p) {
        for (List<BlockPos> r : routes) if (r.contains(p)) return true;
        return false;
    }

    /** Para las pruebas: se olvidan los caminitos medidos. */
    public static void clear() {
        ROUTES.clear();
    }

    /** ¿La aldea tiene la caseta? */
    public static boolean hasShed(@Nullable TownData.Town t) {
        return t != null && t.has(Upgrade.MAINTENANCE);
    }
}
