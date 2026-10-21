package com.ale.warbandcraft.upgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Los bloques de cada mejora (H12), sin archivos .nbt: el anillo de la empalizada/muralla, las torres, las plazas
 * y los edificios se arman en código para que se acomoden al terreno de cada aldea.
 */
public final class Blueprints {
    /** Lado del terreno de un edificio. */
    public static int SITE = 9;
    public static int RING_MIN = 16, RING_MAX = 56, RING_MARGIN = 6;
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
        return s.is(Blocks.STONE_BRICKS) || s.is(Blocks.STONE_BRICK_WALL) || s.is(Blocks.SPRUCE_LOG) || s.is(Blocks.LADDER);
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

    /** Empalizada (troncos de 3) o muralla (ladrillo de piedra de 4 con almenas). Puertas en los caminos. */
    public static Plan ring(ServerLevel level, BlockPos center, int r, boolean stone) {
        Plan plan = new Plan();
        List<int[]> cols = ringColumns(center, r);
        int n = cols.size();
        boolean[] path = new boolean[n];
        for (int i = 0; i < n; i++) {
            Integer g = groundY(level, cols.get(i)[0], cols.get(i)[1], center.getY());
            path[i] = g != null && level.getBlockState(new BlockPos(cols.get(i)[0], g, cols.get(i)[1])).is(Blocks.DIRT_PATH);
        }
        int height = stone ? 4 : 3;
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int i = 0; i < n; i++) {
            if (path[i] || path[(i + 1) % n] || path[(i + n - 1) % n]) continue;   // puerta de 3
            int x = cols.get(i)[0], z = cols.get(i)[1];
            Integer g = wallBase(level, x, z, stone ? height + 1 : height, center.getY());
            if (g == null) continue;   // agua o casa: hueco
            for (int dy = 1; dy <= height; dy++) {
                plan.put(new BlockPos(x, g + dy, z), stone ? Blocks.STONE_BRICKS.defaultBlockState() : log);
            }
            if (stone && i % 2 == 0) plan.put(new BlockPos(x, g + height + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
        }
        return plan;
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

    /**
     * Busca un terreno de {@link #SITE}×{@link #SITE} casi plano entre {@code minR} y {@code maxR} bloques del centro,
     * sin caminos, casas, agua ni cosas con datos, y que no pise los terrenos ya usados. Devuelve la esquina mínima
     * con y = el piso (el más alto del terreno), o null.
     */
    @Nullable
    public static BlockPos findSite(ServerLevel level, BlockPos center, int minR, int maxR, Iterable<BlockPos> taken) {
        for (int r = Math.max(minR, 0); r <= maxR; r += 3) {
            int steps = Math.max(8, (int) (2 * Math.PI * r / 6));
            for (int i = 0; i < steps; i++) {
                double a = 2 * Math.PI * i / steps;
                int cx = center.getX() + (int) Math.round(r * Math.cos(a)), cz = center.getZ() + (int) Math.round(r * Math.sin(a));
                BlockPos corner = new BlockPos(cx - SITE / 2, 0, cz - SITE / 2);
                if (overlaps(corner, taken)) continue;
                Integer floor = siteFloor(level, corner, center.getY());
                if (floor != null) return new BlockPos(corner.getX(), floor, corner.getZ());
            }
        }
        return null;
    }

    public static boolean overlaps(BlockPos corner, Iterable<BlockPos> taken) {
        for (BlockPos t : taken) {
            if (corner.getX() < t.getX() + SITE + 1 && t.getX() < corner.getX() + SITE + 1
                    && corner.getZ() < t.getZ() + SITE + 1 && t.getZ() < corner.getZ() + SITE + 1) return true;
        }
        return false;
    }

    /** El piso del terreno si sirve, o null. */
    @Nullable
    public static Integer siteFloor(ServerLevel level, BlockPos corner, int hintY) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = 0; dx < SITE; dx++) for (int dz = 0; dz < SITE; dz++) {
            int x = corner.getX() + dx, z = corner.getZ() + dz;
            Integer g = groundY(level, x, z, hintY);
            if (g == null) return null;
            BlockPos ground = new BlockPos(x, g, z);
            BlockState gs = level.getBlockState(ground);
            if (!gs.getFluidState().isEmpty() || !natural(gs) || gs.is(Blocks.DIRT_PATH)) return null;
            for (int dy = 1; dy <= 7; dy++) {
                BlockPos p = ground.above(dy);
                if (!clearable(level.getBlockState(p)) || level.getBlockEntity(p) != null) return null;
            }
            min = Math.min(min, g);
            max = Math.max(max, g);
        }
        return max - min <= MAX_SLOPE ? max : null;
    }

    /** Nivela el terreno: tierra hasta el piso, el piso, y aire arriba (tala lo que haya). */
    /** H24: un puesto de mercado de 3×3 desde (x0, z0): postes, toldo, mostrador, barril y mercadería. */
    private static void stall(Box b, int x0, int z0, Block awning, Block good, boolean back) {
        b.fill(x0, 1, z0, x0, 2, z0, Blocks.OAK_FENCE);
        b.fill(x0 + 2, 1, z0, x0 + 2, 2, z0, Blocks.OAK_FENCE);
        b.fill(x0, 1, z0 + 2, x0, 2, z0 + 2, Blocks.OAK_FENCE);
        b.fill(x0 + 2, 1, z0 + 2, x0 + 2, 2, z0 + 2, Blocks.OAK_FENCE);
        b.fill(x0, 3, z0, x0 + 2, 3, z0 + 2, awning);
        b.set(x0 + 1, 1, back ? z0 : z0 + 2, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        b.set(x0 + 1, 1, z0 + 1, Blocks.BARREL.defaultBlockState());
        b.set(x0 + 1, 2, z0 + 1, good.defaultBlockState());
    }

    private static void level(ServerLevel level, Plan plan, BlockPos site, BlockState floor) {
        int fy = site.getY();
        for (int dx = 0; dx < SITE; dx++) for (int dz = 0; dz < SITE; dz++) {
            int x = site.getX() + dx, z = site.getZ() + dz;
            Integer g = groundY(level, x, z, fy);
            int from = g == null ? fy : g + 1;
            for (int y = from; y < fy; y++) plan.put(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
            for (int y = fy + 1; y <= fy + 8; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (!level.getBlockState(p).isAir()) plan.put(p, Blocks.AIR.defaultBlockState());
            }
        }
        for (int dx = 0; dx < SITE; dx++) for (int dz = 0; dz < SITE; dz++) {
            plan.put(new BlockPos(site.getX() + dx, fy, site.getZ() + dz), floor);
        }
    }

    /** El edificio de {@code u} en el terreno {@code site} (esquina mínima, y = piso), sin girar. */
    public static Plan building(ServerLevel level, Upgrade u, BlockPos site) {
        return building(level, u, site, 0);
    }

    /**
     * H22: el edificio girado {@code rot} × 90° en el sentido del reloj (0..3) dentro de su terreno: la puerta, que sin
     * girar mira al norte (z mínimo), pasa a mirar al este, al sur o al oeste.
     */
    public static Plan building(ServerLevel level, Upgrade u, BlockPos site, int rot) {
        Plan plan = new Plan();
        Box b = new Box(plan, site, net.minecraft.world.level.block.Rotation.values()[Math.floorMod(rot, 4)]);
        switch (u) {
            case BUILDER_1, BUILDER_2, BUILDER_3 -> {
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                int size = u == Upgrade.BUILDER_1 ? 5 : u == Upgrade.BUILDER_2 ? 7 : 9;
                int o = (SITE - size) / 2;
                int h = u == Upgrade.BUILDER_3 ? 7 : 4;
                b.house(o, o, size, size, h, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE, Blocks.OAK_SLAB);
                if (u == Upgrade.BUILDER_3) b.fill(o + 1, 4, o + 1, o + size - 2, 4, o + size - 2, Blocks.SPRUCE_PLANKS);   // 2º piso
                if (u == Upgrade.BUILDER_3) b.set(o + 1, 4, o + 1, Blocks.AIR.defaultBlockState());
                if (u == Upgrade.BUILDER_3) for (int y = 1; y <= 4; y++) b.set(o + 1, y, o + 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
                b.set(o + 1, 1, o + size - 2, Blocks.CRAFTING_TABLE.defaultBlockState());
                if (u != Upgrade.BUILDER_1) b.set(o + 2, 1, o + size - 2, Blocks.STONECUTTER.defaultBlockState());
                if (u != Upgrade.BUILDER_1) b.set(o + size - 2, 1, o + 1, Blocks.SCAFFOLDING.defaultBlockState());
                b.set(o + size / 2, h - 1, o + size / 2, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            }
            case BARRACKS -> {
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                b.house(0, 1, 9, 7, 4, Blocks.OAK_PLANKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB);
                for (int x = 1; x <= 7; x += 2) b.bed(x, 1, 2, Direction.SOUTH, Blocks.RED_BED);
                b.set(4, 3, 4, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            }
            case SMITHY -> {
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                b.fill(1, 0, 1, 7, 0, 7, Blocks.COBBLESTONE);
                for (int[] c : new int[][]{{1, 1}, {7, 1}, {1, 7}, {7, 7}}) b.fill(c[0], 1, c[1], c[0], 3, c[1], Blocks.OAK_LOG);
                b.fill(2, 1, 7, 6, 1, 7, Blocks.COBBLESTONE);   // pared del fondo, baja
                b.fill(1, 4, 1, 7, 4, 7, Blocks.DARK_OAK_SLAB);
                b.set(2, 1, 6, Blocks.BLAST_FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.BlastFurnaceBlock.FACING, Direction.NORTH));
                b.set(3, 1, 6, Blocks.LAVA_CAULDRON.defaultBlockState());
                b.set(4, 1, 4, Blocks.ANVIL.defaultBlockState());
                b.set(5, 1, 6, Blocks.SMITHING_TABLE.defaultBlockState());
                b.set(6, 1, 6, Blocks.GRINDSTONE.defaultBlockState());
            }
            case MARKET, MARKET_2, MARKET_3 -> {   // H24: puestos con toldo; la Plaza suma puestos atrás y la Lonja, piso de piedra y faroles
                int lvl = u.marketLevel();
                level(level, plan, site, (lvl == 3 ? Blocks.STONE_BRICKS : Blocks.DIRT_PATH).defaultBlockState());
                Block[] awnings = {Blocks.RED_WOOL, Blocks.YELLOW_WOOL, Blocks.BLUE_WOOL, Blocks.GREEN_WOOL, Blocks.ORANGE_WOOL};
                Block[] goods = {Blocks.PUMPKIN, Blocks.MELON, Blocks.HAY_BLOCK, Blocks.IRON_BLOCK, Blocks.COAL_BLOCK};
                for (int k = 0; k < 3; k++) stall(b, k * 3, 1, awnings[k], goods[k], false);
                if (lvl >= 2) {
                    stall(b, 0, 6, awnings[3], goods[2], true);
                    stall(b, 6, 6, awnings[4], lvl == 3 ? goods[3] : goods[0], true);
                } else {
                    b.set(4, 1, 6, Blocks.COMPOSTER.defaultBlockState());
                    b.set(2, 1, 6, Blocks.LANTERN.defaultBlockState());
                }
                if (lvl >= 2) b.set(4, 1, 7, Blocks.COMPOSTER.defaultBlockState());
                if (lvl == 3) {   // faroles en postes y un puesto central con la balanza (yunque) y la caja (barril)
                    for (int[] c : new int[][]{{0, 5}, {8, 5}}) {
                        b.fill(c[0], 1, c[1], c[0], 2, c[1], Blocks.SPRUCE_FENCE);
                        b.set(c[0], 3, c[1], Blocks.LANTERN.defaultBlockState());
                    }
                    b.set(3, 1, 5, Blocks.BARREL.defaultBlockState());
                    b.set(5, 1, 5, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
                }
            }
            case HOUSE -> {   // casa de 5×5 adentro con 2 camas, puerta al norte y farol
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                b.house(1, 1, 7, 7, 4, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.OAK_SLAB);
                b.set(4, 0, 0, Blocks.DIRT_PATH.defaultBlockState());
                b.bed(2, 1, 4, Direction.SOUTH, Blocks.WHITE_BED);
                b.bed(6, 1, 4, Direction.SOUTH, Blocks.WHITE_BED);
                b.set(4, 3, 4, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
                BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
                b.set(4, 1, 1, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
                b.set(4, 2, 1, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
            }
            case TAVERN_1, TAVERN_2, TAVERN_3 -> {   // H19: barra, mesas, barriles y 2 camas; más grande y adornada por nivel
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                int lvl = u.tavernLevel();
                Block wall = lvl == 1 ? Blocks.OAK_PLANKS : lvl == 2 ? Blocks.SPRUCE_PLANKS : Blocks.DARK_OAK_PLANKS;
                Block corner = lvl == 3 ? Blocks.STONE_BRICKS : Blocks.OAK_LOG;
                b.house(0, 0, 9, 9, lvl == 3 ? 5 : 4, wall, corner, Blocks.SPRUCE_PLANKS, Blocks.OAK_SLAB);
                BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
                b.set(4, 1, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
                b.set(4, 2, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                b.fill(1, 1, 6, 5, 1, 6, Blocks.BARREL);   // la barra
                b.set(1, 1, 7, Blocks.BARREL.defaultBlockState());
                b.set(2, 1, 7, Blocks.SMOKER.defaultBlockState());
                b.bed(6, 1, 6, Direction.SOUTH, Blocks.BROWN_BED);
                b.bed(7, 1, 6, Direction.SOUTH, Blocks.BROWN_BED);
                int tables = lvl;   // 1 / 2 / 3 mesas con sus bancos
                for (int k = 0; k < tables; k++) {
                    int x = 2 + k * 2;
                    b.set(x, 1, 3, Blocks.OAK_FENCE.defaultBlockState());
                    b.set(x, 2, 3, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
                    b.set(x, 1, 2, Blocks.OAK_STAIRS.defaultBlockState());
                }
                b.set(4, 3, 4, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
                if (lvl >= 2) b.set(7, 1, 2, Blocks.CAMPFIRE.defaultBlockState());
            }
            case TRAINING_1, TRAINING_2, TRAINING_3 -> {   // H19: patio con muñecos (cerco + calabaza) y blancos
                level(level, plan, site, Blocks.COARSE_DIRT.defaultBlockState());
                int lvl = u.trainingLevel();
                for (int x = 0; x < SITE; x++) {
                    b.set(x, 1, 0, Blocks.OAK_FENCE.defaultBlockState());
                    b.set(x, 1, SITE - 1, Blocks.OAK_FENCE.defaultBlockState());
                }
                for (int z = 1; z < SITE - 1; z++) {
                    b.set(0, 1, z, Blocks.OAK_FENCE.defaultBlockState());
                    b.set(SITE - 1, 1, z, Blocks.OAK_FENCE.defaultBlockState());
                }
                b.set(4, 1, 0, Blocks.OAK_FENCE_GATE.defaultBlockState());
                for (int k = 0; k < lvl + 1; k++) {   // muñecos
                    int x = 2 + k * 2;
                    b.set(x, 1, 3, Blocks.OAK_FENCE.defaultBlockState());
                    b.set(x, 2, 3, Blocks.HAY_BLOCK.defaultBlockState());
                    b.set(x, 3, 3, Blocks.CARVED_PUMPKIN.defaultBlockState());
                }
                for (int k = 0; k < lvl; k++) b.set(2 + k * 2, 1, 7, Blocks.TARGET.defaultBlockState());   // blancos
                if (lvl >= 2) b.set(7, 1, 5, Blocks.GRINDSTONE.defaultBlockState());
                if (lvl >= 3) b.set(1, 1, 5, Blocks.ANVIL.defaultBlockState());
            }
            case CHAPEL -> {   // H22: nave de piedra con vitrales, bancos, atril y campanario
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                b.house(1, 0, 7, 9, 5, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICK_SLAB);
                for (int z = 2; z <= 6; z += 2) {   // vitrales a los costados
                    b.set(1, 2, z, Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState());
                    b.set(7, 2, z, Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState());
                    b.set(1, 3, z, Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState());
                    b.set(7, 3, z, Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState());
                }
                BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
                b.set(4, 1, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
                b.set(4, 2, 0, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
                for (int z = 2; z <= 5; z++) {   // bancos mirando al altar
                    b.set(3, 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH));
                    b.set(5, 1, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH));
                }
                b.set(4, 1, 7, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, Direction.NORTH));
                b.set(3, 1, 7, Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
                b.set(5, 1, 7, Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
                b.set(4, 4, 4, Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
                b.fill(3, 6, 7, 5, 8, 7, Blocks.STONE_BRICKS);   // campanario sobre el altar
                b.set(4, 7, 7, Blocks.AIR.defaultBlockState());
                b.set(4, 9, 7, Blocks.STONE_BRICK_WALL.defaultBlockState());
            }
            case GRANARY -> {
                level(level, plan, site, Blocks.GRASS_BLOCK.defaultBlockState());
                b.house(1, 1, 7, 7, 5, Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_SLAB);
                b.fill(2, 1, 4, 6, 2, 6, Blocks.HAY_BLOCK);
                b.fill(2, 3, 5, 6, 3, 6, Blocks.HAY_BLOCK);
                b.set(2, 1, 2, Blocks.COMPOSTER.defaultBlockState());
            }
            default -> {
            }
        }
        return plan;
    }

    /**
     * Ayuda para dibujar en coordenadas del terreno (x, z desde la esquina; y desde el piso), girado {@code rot} adentro
     * del terreno (como {@code StructureTemplate}: horario = (x, z) → (−z, x)); los bloques se giran con él.
     */
    private record Box(Plan plan, BlockPos site, net.minecraft.world.level.block.Rotation rot) {
        BlockPos at(int x, int y, int z) {
            int m = SITE - 1;
            return switch (rot) {
                case CLOCKWISE_90 -> site.offset(m - z, y, x);
                case CLOCKWISE_180 -> site.offset(m - x, y, m - z);
                case COUNTERCLOCKWISE_90 -> site.offset(z, y, m - x);
                default -> site.offset(x, y, z);
            };
        }

        void set(int x, int y, int z, BlockState s) {
            plan.put(at(x, y, z), s.rotate(rot));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                set(x, y, z, block.defaultBlockState());
            }
        }

        /** Casa de paredes {@code wall}, esquinas {@code corner}, piso, techo plano de losas y puerta al sur (z mínimo). */
        void house(int x0, int z0, int w, int d, int h, Block wall, Block corner, Block floor, Block roof) {
            int x1 = x0 + w - 1, z1 = z0 + d - 1;
            fill(x0, 0, z0, x1, 0, z1, floor);
            for (int y = 1; y < h; y++) {
                for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                    boolean edgeX = x == x0 || x == x1, edgeZ = z == z0 || z == z1;
                    if (!edgeX && !edgeZ) continue;
                    Block b = edgeX && edgeZ ? corner : wall;
                    boolean window = y == 2 && !(edgeX && edgeZ) && ((edgeX && z == (z0 + z1) / 2) || (edgeZ && z == z1 && x == (x0 + x1) / 2));
                    set(x, y, z, window ? Blocks.GLASS_PANE.defaultBlockState() : b.defaultBlockState());
                }
            }
            int door = (x0 + x1) / 2;
            set(door, 1, z0, Blocks.AIR.defaultBlockState());
            set(door, 2, z0, Blocks.AIR.defaultBlockState());
            fill(x0 - (x0 > 0 ? 1 : 0), h, z0 - (z0 > 0 ? 1 : 0), x1 + (x1 < SITE - 1 ? 1 : 0), h, z1 + (z1 < SITE - 1 ? 1 : 0), roof);
        }

        void bed(int x, int y, int z, Direction facing, Block bed) {
            BlockState foot = bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
            set(x, y, z, foot);
            set(x + facing.getStepX(), y, z + facing.getStepZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
        }
    }
}
