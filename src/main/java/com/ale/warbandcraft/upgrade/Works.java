package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * H22b/H22c: las obras que no son un edificio de 9×9, armadas desde lo que se marcó en el modo Gestión:
 * <ul>
 *   <li>{@code wall:<nivel>:x,z;x,z;...} muralla dibujada por tramos (1 = empalizada de troncos, 2 = piedra con almenas);</li>
 *   <li>{@code gate:<nivel>:x,z} portón (puesto de control) sobre la muralla, con puertas de cerco que se abren solas para los tuyos;</li>
 *   <li>{@code tower:<nivel>:x,z} torre suelta de 5×5 (madera o piedra);</li>
 *   <li>{@code earth:<0 allanar | 1 rellenar>:x0,z0,x1,z1,y} terreno a la altura {@code y};</li>
 *   <li>{@code demolish:<clave>} demoler un edificio (clave de su terreno) o una torre ({@code tower:<pos>}).</li>
 * </ul>
 * Lo que se saca del mundo va al almacén ({@link Upgrades}); la cuenta en materiales es la de {@link BuildCost}.
 */
public final class Works {
    /** Muralla: puntos y columnas máximos, esmeraldas (1 cada tantas columnas, con mínimo). */
    public static int MAX_POINTS = 32, MAX_COLUMNS = 512;
    public static int WALL_1_COLUMNS_PER_EMERALD = 8, WALL_2_COLUMNS_PER_EMERALD = 4, WALL_MIN_EMERALDS = 2;
    /** Terreno: lado máximo, desnivel máximo de una columna y esmeraldas (1 cada tantos bloques, mín. 1). */
    public static int EARTH_SIDE = 32, EARTH_DEPTH = 8, EARTH_BLOCKS_PER_EMERALD = 64;
    /** Portones: se abren con uno de los tuyos a esta distancia y ningún enemigo a la otra. */
    public static int GATE_OPEN_RANGE = 3, GATE_ENEMY_RANGE = 8;
    /** Cuánto se puede salir del territorio al dibujar la muralla. */
    public static int WALL_MARGIN = 2;

    private Works() {}

    /** El plano de la obra (o por qué no se puede), sus esmeraldas, la clave que se guarda en la obra y un aviso. */
    public record Spec(Upgrade upgrade, @Nullable Blueprints.Plan plan, @Nullable String problem, int emeralds, String key, String note) {
        static Spec no(Upgrade u, String why) {
            return new Spec(u, null, why, 0, "", "");
        }
    }

    /** Qué obra es el texto (null si no es una de estas). */
    @Nullable
    public static Upgrade upgradeOf(String text) {
        String[] p = text.split(":", 3);
        if (p.length < 2) return null;
        if (p[0].equals("demolish")) return Upgrade.DEMOLISH;
        int lvl = p[1].equals("2") ? 2 : p[1].equals("1") ? 1 : p[1].equals("0") ? 0 : -1;
        return switch (p[0]) {
            case "wall" -> lvl == 1 ? Upgrade.WALL_1 : lvl == 2 ? Upgrade.WALL_2 : null;
            case "gate" -> lvl == 1 ? Upgrade.GATE_1 : lvl == 2 ? Upgrade.GATE_2 : null;
            case "tower" -> lvl == 1 ? Upgrade.TOWER_1 : lvl == 2 ? Upgrade.TOWER_2 : null;
            case "earth" -> lvl == 0 ? Upgrade.EARTH_LEVEL : lvl == 1 ? Upgrade.EARTH_FILL : null;
            default -> null;
        };
    }

    /** Arma la obra del texto para la aldea {@code v}; null si el texto no se entiende. */
    @Nullable
    public static Spec spec(ServerLevel level, VillageData.Village v, TownData.Town town, String text) {
        Upgrade u = upgradeOf(text);
        if (u == null) return null;
        String[] p = text.split(":", 3);
        try {
            return switch (u) {
                case WALL_1, WALL_2 -> wall(level, v, u, points(p[2]));
                case GATE_1, GATE_2 -> gate(level, v, town, u, points(p[2]).get(0));
                case TOWER_1, TOWER_2 -> tower(level, v, u, points(p[2]).get(0));
                case EARTH_LEVEL, EARTH_FILL -> {
                    String[] n = p[2].split(",");
                    yield earth(level, v, u, Integer.parseInt(n[0]), Integer.parseInt(n[1]), Integer.parseInt(n[2]),
                            Integer.parseInt(n[3]), Integer.parseInt(n[4]));
                }
                case DEMOLISH -> demolish(level, town, text.substring("demolish:".length()));
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "x,z;x,z;..." → puntos. */
    static List<int[]> points(String s) {
        List<int[]> out = new ArrayList<>();
        for (String part : s.split(";")) {
            if (part.isBlank()) continue;
            String[] xz = part.split(",");
            out.add(new int[]{Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())});
        }
        if (out.isEmpty()) throw new IllegalArgumentException("sin puntos");
        return out;
    }

    // ------------------------------------------------------------------ muralla

    /** Las columnas de los tramos, sin cortar esquinas (cada paso es en x o en z: la muralla no tiene rendijas). */
    public static List<int[]> columns(List<int[]> pts) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < pts.size(); i++) {
            int x = pts.get(i)[0], z = pts.get(i)[1], x1 = pts.get(i + 1)[0], z1 = pts.get(i + 1)[1];
            int dx = Math.abs(x1 - x), dz = Math.abs(z1 - z), sx = Integer.signum(x1 - x), sz = Integer.signum(z1 - z);
            if (out.isEmpty() || out.get(out.size() - 1)[0] != x || out.get(out.size() - 1)[1] != z) out.add(new int[]{x, z});
            int ix = 0, iz = 0;
            while (ix < dx || iz < dz) {
                if ((0.5 + ix) / dx < (0.5 + iz) / dz) {
                    x += sx;
                    ix++;
                } else {
                    z += sz;
                    iz++;
                }
                out.add(new int[]{x, z});
            }
        }
        return out;
    }

    private static boolean nearInside(ServerLevel level, VillageData.Village v, int x, int z) {
        int m = WALL_MARGIN;
        return Placement.inside(level, v, x, z) || Placement.inside(level, v, x + m, z) || Placement.inside(level, v, x - m, z)
                || Placement.inside(level, v, x, z + m) || Placement.inside(level, v, x, z - m);
    }

    static Spec wall(ServerLevel level, VillageData.Village v, Upgrade u, List<int[]> pts) {
        if (pts.size() < 2) return Spec.no(u, "Marcá al menos dos puntos");
        if (pts.size() > MAX_POINTS) return Spec.no(u, "Demasiados puntos (máximo " + MAX_POINTS + ")");
        for (int[] p : pts) if (!nearInside(level, v, p[0], p[1])) return Spec.no(u, "Un punto queda afuera del territorio");
        List<int[]> cols = columns(pts);
        if (cols.size() > MAX_COLUMNS) return Spec.no(u, "Muy larga (máximo " + MAX_COLUMNS + " bloques)");
        boolean stone = u == Upgrade.WALL_2;
        int height = stone ? 4 : 3, hint = v.center().getY();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState body = stone ? Blocks.STONE_BRICKS.defaultBlockState() : log;
        int n = cols.size();
        boolean[] path = new boolean[n];
        for (int i = 0; i < n; i++) {
            Integer g = Blueprints.groundY(level, cols.get(i)[0], cols.get(i)[1], hint);
            path[i] = g != null && level.getBlockState(new BlockPos(cols.get(i)[0], g, cols.get(i)[1])).is(Blocks.DIRT_PATH);
        }
        Blueprints.Plan plan = new Blueprints.Plan();
        int gaps = 0, paths = 0;
        for (int i = 0; i < n; i++) {
            if (path[i] || i > 0 && path[i - 1] || i + 1 < n && path[i + 1]) {   // caminos: hueco de 3 para un portón
                paths++;
                continue;
            }
            int x = cols.get(i)[0], z = cols.get(i)[1];
            Integer g = Blueprints.wallBase(level, x, z, stone ? height + 1 : height, hint);
            if (g == null) {   // agua o casa
                gaps++;
                continue;
            }
            for (int dy = 1; dy <= height; dy++) plan.put(new BlockPos(x, g + dy, z), body);
            if (stone && i % 2 == 0) plan.put(new BlockPos(x, g + height + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
        }
        if (plan.size() == 0) return Spec.no(u, "No hay dónde poner muralla ahí");
        int per = stone ? WALL_2_COLUMNS_PER_EMERALD : WALL_1_COLUMNS_PER_EMERALD;
        int emeralds = Math.max(WALL_MIN_EMERALDS, (n + per - 1) / per);
        String note = n + " bloques de largo" + (paths > 0 ? " · " + paths + " en caminos quedan abiertos (poné un portón)" : "")
                + (gaps > 0 ? " · " + gaps + " huecos (agua o casas)" : "");
        return new Spec(u, plan, null, emeralds, "", note);
    }

    // ------------------------------------------------------------------ portón

    /** Las columnas (x, z) con muralla nuestra: lo dibujado y, si la aldea tiene el anillo de H12, sus bloques. */
    private static Set<Long> wallColumns(TownData.Town town) {
        Set<Long> out = new HashSet<>();
        for (long l : town.defense.keySet()) {
            BlockPos p = BlockPos.of(l);
            out.add(BlockPos.asLong(p.getX(), 0, p.getZ()));
        }
        return out;
    }

    private static boolean ringWallAt(ServerLevel level, int x, int z, int hint) {
        Integer g = Blueprints.groundY(level, x, z, hint);
        if (g == null) return false;
        for (int dy = 1; dy <= 3; dy++) {
            BlockState s = level.getBlockState(new BlockPos(x, g + dy, z));
            if (s.is(Blocks.STONE_BRICKS) || s.is(Blocks.SPRUCE_LOG) || s.getBlock() instanceof FenceGateBlock) return true;
        }
        return false;
    }

    /** El piso natural de la columna (debajo de muros, puertas y troncos), cerca de {@code hint}. */
    @Nullable
    private static Integer naturalY(ServerLevel level, int x, int z, int hint) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, hint, z);
        if (!level.isLoaded(p)) return null;
        for (int y = hint + Blueprints.SCAN_UP; y > hint - Blueprints.SCAN_DOWN; y--) {
            p.setY(y);
            BlockState s = level.getBlockState(p);
            if (Blueprints.natural(s) && s.getFluidState().isEmpty()) return y;
            if (!s.getFluidState().isEmpty()) return null;
        }
        return null;
    }

    static Spec gate(ServerLevel level, VillageData.Village v, TownData.Town town, Upgrade u, int[] click) {
        int hint = v.center().getY();
        Set<Long> walls = wallColumns(town);
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = click[0] + dx, z = click[1] + dz;
            boolean wall = walls.contains(BlockPos.asLong(x, 0, z)) || town.ringWalls && ringWallAt(level, x, z, hint);
            int d = dx * dx + dz * dz;
            if (wall && d < bestD) {
                bestD = d;
                best = new int[]{x, z};
            }
        }
        if (best == null) return Spec.no(u, "El portón va sobre una muralla: hacé click en la muralla");
        int alongX = 0, alongZ = 0;   // para qué lado corre la muralla
        for (int k = -3; k <= 3; k++) {
            if (k == 0) continue;
            if (walls.contains(BlockPos.asLong(best[0] + k, 0, best[1])) || town.ringWalls && ringWallAt(level, best[0] + k, best[1], hint)) alongX++;
            if (walls.contains(BlockPos.asLong(best[0], 0, best[1] + k)) || town.ringWalls && ringWallAt(level, best[0], best[1] + k, hint)) alongZ++;
        }
        boolean xAxis = alongX >= alongZ;
        int cx = xAxis ? click[0] : best[0], cz = xAxis ? best[1] : click[1];
        Direction facing = xAxis ? Direction.NORTH : Direction.EAST;   // la puerta de cerco cruza la muralla
        boolean stone = u == Upgrade.GATE_2;
        BlockState gateBlock = (stone ? Blocks.DARK_OAK_FENCE_GATE : Blocks.SPRUCE_FENCE_GATE).defaultBlockState()
                .setValue(FenceGateBlock.FACING, facing).setValue(FenceGateBlock.OPEN, false);
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState body = stone ? Blocks.STONE_BRICKS.defaultBlockState() : log;
        Blueprints.Plan plan = new Blueprints.Plan();
        for (int k = -2; k <= 2; k++) {
            int x = cx + (xAxis ? k : 0), z = cz + (xAxis ? 0 : k);
            Integer g = naturalY(level, x, z, hint);
            if (g == null) return Spec.no(u, "No se ve el piso o hay agua");
            if (Math.abs(k) <= 1) {   // la abertura: 2 puertas de cerco y el dintel
                plan.put(new BlockPos(x, g + 1, z), gateBlock);
                plan.put(new BlockPos(x, g + 2, z), gateBlock);
                plan.put(new BlockPos(x, g + 3, z), body);
                if (stone) {
                    plan.put(new BlockPos(x, g + 4, z), body);
                    if (k != 0) plan.put(new BlockPos(x, g + 5, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
                }
            } else {   // los postes, más altos que la muralla
                int top = stone ? 6 : 4;
                for (int dy = 1; dy <= top; dy++) plan.put(new BlockPos(x, g + dy, z), body);
                if (stone) plan.put(new BlockPos(x, g + top + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
            }
        }
        return new Spec(u, plan, null, u.cost, "", "Se abre solo para los tuyos (soldados, aldeanos, trabajadores)");
    }

    // ------------------------------------------------------------------ torre

    static Spec tower(ServerLevel level, VillageData.Village v, Upgrade u, int[] click) {
        if (!nearInside(level, v, click[0], click[1])) return Spec.no(u, "Afuera del territorio");
        Blueprints.Plan plan = new Blueprints.Plan();
        BlockPos c = new BlockPos(click[0], 0, click[1]);
        Integer floor = Blueprints.tower(level, plan, c, v.center(), u == Upgrade.TOWER_2);
        if (floor == null) return Spec.no(u, "La torre no entra ahí (casa, agua o más de 3 de desnivel)");
        return new Spec(u, plan, null, u.cost, "tower:" + new BlockPos(click[0], floor, click[1]).asLong(),
                "Arriba van los arqueros del cuartel");
    }

    // ------------------------------------------------------------------ terreno

    static Spec earth(ServerLevel level, VillageData.Village v, Upgrade u, int x0, int z0, int x1, int z1, int y) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        if (bx - ax + 1 > EARTH_SIDE || bz - az + 1 > EARTH_SIDE) return Spec.no(u, "Muy grande (máximo " + EARTH_SIDE + "×" + EARTH_SIDE + ")");
        if (!Placement.inside(level, v, ax, az) || !Placement.inside(level, v, bx, az) || !Placement.inside(level, v, ax, bz)
                || !Placement.inside(level, v, bx, bz)) return Spec.no(u, "Afuera del territorio");
        boolean fillOnly = u == Upgrade.EARTH_FILL;
        Blueprints.Plan plan = new Blueprints.Plan();
        int skipped = 0;
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                Integer g = Blueprints.groundY(level, x, z, y);
                if (g == null) {
                    skipped++;
                    continue;
                }
                BlockState gs = level.getBlockState(new BlockPos(x, g, z));
                if (!gs.getFluidState().isEmpty() || !Blueprints.natural(gs) || Math.abs(g - y) > EARTH_DEPTH) {
                    skipped++;
                    continue;
                }
                if (g < y) {   // rellenar con tierra; arriba, pasto
                    boolean ok = true;
                    for (int yy = g + 1; yy <= y && ok; yy++) {
                        BlockPos p = new BlockPos(x, yy, z);
                        ok = level.getBlockEntity(p) == null && Blueprints.clearable(level.getBlockState(p));
                    }
                    if (!ok) {
                        skipped++;
                        continue;
                    }
                    for (int yy = g + 1; yy < y; yy++) plan.put(new BlockPos(x, yy, z), Blocks.DIRT.defaultBlockState());
                    plan.put(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState());
                } else if (g > y && !fillOnly) {   // cortar: de arriba para abajo (los árboles de encima también)
                    boolean ok = true;
                    for (int yy = y + 1; yy <= g && ok; yy++) ok = level.getBlockEntity(new BlockPos(x, yy, z)) == null;
                    if (!ok) {
                        skipped++;
                        continue;
                    }
                    for (int yy = g + 10; yy > g; yy--) {
                        BlockPos p = new BlockPos(x, yy, z);
                        BlockState s = level.getBlockState(p);
                        if (!s.isAir() && Blueprints.clearable(s) && level.getBlockEntity(p) == null) plan.put(p, Blocks.AIR.defaultBlockState());
                    }
                    for (int yy = g; yy > y; yy--) plan.put(new BlockPos(x, yy, z), Blocks.AIR.defaultBlockState());
                    if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT)) plan.put(new BlockPos(x, y, z), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        }
        if (plan.size() == 0) return Spec.no(u, "Ya está a esa altura" + (skipped > 0 ? " (" + skipped + " columnas no se tocan)" : ""));
        int emeralds = Math.max(1, (plan.size() + EARTH_BLOCKS_PER_EMERALD - 1) / EARTH_BLOCKS_PER_EMERALD);
        String note = "Altura " + y + (skipped > 0 ? " · " + skipped + " columnas no se tocan (casas, agua o más de " + EARTH_DEPTH + ")" : "");
        return new Spec(u, plan, null, emeralds, "", note);
    }

    // ------------------------------------------------------------------ demoler

    /** Demoler muralla: se saca lo dibujado a esta distancia (horizontal) del click, sin tocar las torres. */
    public static int WALL_CUT = 4;

    /** Las piezas de muralla/portón dibujadas a ≤ {@link #WALL_CUT} de (x, z), fuera de las torres. */
    static List<BlockPos> wallNear(TownData.Town town, int x, int z) {
        List<BlockPos> out = new ArrayList<>();
        for (long l : town.defense.keySet()) {
            BlockPos p = BlockPos.of(l);
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz > (double) WALL_CUT * WALL_CUT) continue;
            boolean tower = false;
            for (BlockPos t : town.towers) if (Math.abs(p.getX() - t.getX()) <= 2 && Math.abs(p.getZ() - t.getZ()) <= 2) tower = true;
            if (!tower) out.add(p);
        }
        return out;
    }

    static Spec demolish(ServerLevel level, TownData.Town town, String key) {
        Upgrade u = Upgrade.DEMOLISH;
        Blueprints.Plan plan = new Blueprints.Plan();
        List<BlockPos> remove = new ArrayList<>();
        if (key.startsWith("wall:")) {   // un tramo de muralla dibujada
            String[] n = key.substring(5).split(",");
            remove.addAll(wallNear(town, Integer.parseInt(n[0]), Integer.parseInt(n[1])));
            if (remove.isEmpty()) return Spec.no(u, "Ahí no hay muralla dibujada");
        } else if (key.startsWith("tower:")) {
            BlockPos t = BlockPos.of(Long.parseLong(key.substring(6)));
            if (!town.towers.contains(t)) return Spec.no(u, "Ahí no hay una torre");
            for (int dy = 1; dy <= 9; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                remove.add(t.offset(dx, dy, dz));
            }
        } else {
            BlockPos site = town.sites.get(key);
            if (site == null) return Spec.no(u, "Ahí no hay un edificio");
            Upgrade built = null;
            if (key.startsWith("house_")) built = Upgrade.HOUSE;
            else for (Upgrade b : town.built) if (b.siteKey().equals(key) && (built == null || b.ordinal() > built.ordinal())) built = b;
            if (built == null) return Spec.no(u, "No hay nada terminado ahí");
            if (town.legacySite(key)) {   // de antes de los estilos: todo lo que hay arriba de su terreno
                int n = town.siteSize(key);
                for (int dx = 0; dx < n; dx++) for (int dz = 0; dz < n; dz++) for (int dy = 1; dy <= 10; dy++) {
                    remove.add(site.offset(dx, dy, dz));
                }
            } else {
                Blueprints.Plan b = Blueprints.building(level, built, site, town.rotations.getOrDefault(key, 0), Style.byId(town.style));
                for (Map.Entry<BlockPos, BlockState> e : b.blocks.entrySet()) {
                    if (e.getKey().getY() > site.getY() && !e.getValue().isAir()) remove.add(e.getKey());
                }
            }
        }
        remove.sort(Comparator.comparingInt((BlockPos b) -> b.getY()).reversed());   // de arriba para abajo
        for (BlockPos p : remove) if (!level.getBlockState(p).isAir()) plan.put(p, Blocks.AIR.defaultBlockState());
        if (plan.size() == 0) return Spec.no(u, "No queda nada para sacar");
        return new Spec(u, plan, null, 0, "demolish:" + key, "Lo que se saca va al almacén");
    }

    // ------------------------------------------------------------------ al terminar

    /** Anota lo que dejó la obra: muralla/portón/torre (para reparar y defender) o lo demolido. */
    static void finish(TownData.Town t, TownData.Job job) {
        Upgrade u = job.upgrade;
        if (u.defenseLevel() > 0) {
            for (int i = 0; i < job.size(); i++) {
                BlockPos p = job.pos(i);
                BlockState s = job.state(i);
                if (s.isAir()) t.defense.remove(p.asLong());
                else t.defense.put(p.asLong(), s);
                if (s.getBlock() instanceof FenceGateBlock && !t.gates.contains(p)) {
                    boolean lower = true;
                    for (int k = 0; k < job.size(); k++) {
                        if (job.pos(k).equals(p.below()) && job.state(k).getBlock() instanceof FenceGateBlock) lower = false;
                    }
                    if (lower) t.gates.add(p);
                }
            }
            if (u == Upgrade.WALL_1 || u == Upgrade.WALL_2) t.built.add(Upgrade.PALISADE);
            if (u == Upgrade.WALL_2) t.built.add(Upgrade.WALL);
            if (u == Upgrade.TOWER_1 || u == Upgrade.TOWER_2) {
                t.built.add(Upgrade.TOWERS);
                if (job.siteKey.startsWith("tower:")) {
                    BlockPos c = BlockPos.of(Long.parseLong(job.siteKey.substring(6)));
                    t.towers.removeIf(o -> o.getX() == c.getX() && o.getZ() == c.getZ());
                    t.towers.add(c);
                }
            }
        } else if (u == Upgrade.DEMOLISH && job.siteKey.startsWith("demolish:")) {
            String key = job.siteKey.substring("demolish:".length());
            if (key.startsWith("wall:")) {   // el tramo ya no es muralla (ni se repara)
                String[] n = key.substring(5).split(",");
                int x = Integer.parseInt(n[0]), z = Integer.parseInt(n[1]);
                for (BlockPos p : wallNear(t, x, z)) t.defense.remove(p.asLong());
                t.gates.removeIf(g -> {
                    double dx = g.getX() - x, dz = g.getZ() - z;
                    return dx * dx + dz * dz <= (double) WALL_CUT * WALL_CUT;
                });
                if (t.defense.isEmpty() && !t.ringWalls) {
                    t.built.remove(Upgrade.PALISADE);
                    t.built.remove(Upgrade.WALL);
                }
            } else if (key.startsWith("tower:")) {
                BlockPos c = BlockPos.of(Long.parseLong(key.substring(6)));
                t.towers.remove(c);
                t.defense.keySet().removeIf(l -> {
                    BlockPos p = BlockPos.of(l);
                    return Math.abs(p.getX() - c.getX()) <= 2 && Math.abs(p.getZ() - c.getZ()) <= 2;
                });
                if (t.towers.isEmpty()) t.built.remove(Upgrade.TOWERS);
            } else {
                if (key.startsWith("house_")) t.houses = Math.max(0, t.houses - 1);
                t.built.removeIf(b -> b.siteKey().equals(key));
                t.removeSite(key);
            }
        }
    }

    /** Radio aproximado de la muralla dibujada (distancia media al centro; 0 si no hay). */
    public static int radius(TownData.Town t) {
        if (t.defense.isEmpty()) return 0;
        double sum = 0;
        for (long l : t.defense.keySet()) {
            BlockPos p = BlockPos.of(l);
            sum += Math.sqrt((double) (p.getX() - t.center.getX()) * (p.getX() - t.center.getX())
                    + (double) (p.getZ() - t.center.getZ()) * (p.getZ() - t.center.getZ()));
        }
        return (int) Math.round(sum / t.defense.size());
    }

    // ------------------------------------------------------------------ portones

    /** Cada medio segundo: los portones de tus aldeas se abren con uno de los tuyos cerca y sin enemigos. */
    public static void tickGates(ServerLevel level) {
        for (TownData.Town t : TownData.get(level).all()) {
            if (t.gates.isEmpty() || !level.isPositionEntityTicking(t.center)) continue;
            VillageData.Village v = VillageData.get(level).at(t.center);
            UUID owner = v == null ? null : v.owner();
            for (BlockPos g : t.gates) {
                if (!level.isLoaded(g)) continue;
                BlockState s = level.getBlockState(g);
                if (!(s.getBlock() instanceof FenceGateBlock)) continue;
                boolean open = owner != null && !level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(g).inflate(GATE_OPEN_RANGE, 2, GATE_OPEN_RANGE), e -> friendly(e, owner)).isEmpty();
                if (open) open = level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(g).inflate(GATE_ENEMY_RANGE, 3, GATE_ENEMY_RANGE), Works::hostile).isEmpty();
                if (s.getValue(FenceGateBlock.OPEN) == open) continue;
                level.setBlock(g, s.setValue(FenceGateBlock.OPEN, open), 10);
                BlockState up = level.getBlockState(g.above());
                if (up.getBlock() instanceof FenceGateBlock) level.setBlock(g.above(), up.setValue(FenceGateBlock.OPEN, open), 10);
                level.playSound(null, g, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 0.6F, 1F);
            }
        }
    }

    private static boolean friendly(LivingEntity e, UUID owner) {
        if (!e.isAlive()) return false;
        if (e instanceof Player p) return owner.equals(p.getUUID());
        if (e instanceof AbstractRecruitEntity r) return Objects.equals(r.getOwnerUUID(), owner);
        return e instanceof AbstractVillager;
    }

    private static boolean hostile(LivingEntity e) {
        if (!e.isAlive()) return false;
        if (e instanceof Enemy) return true;
        var d = e.getPersistentData();
        return d.contains("warbandcraft:invader") || d.contains("warbandcraft:ai_attack") || d.contains("warbandcraft:field_army");
    }
}
