package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * H25: arma una guarida o un túmulo bajo tierra, por código y con bloques de vanilla (como los edificios de H12).
 *
 * Todo en coordenadas locales: {@code u} = hacia adelante (la dirección de la escalera), {@code v} = a la derecha.
 * Arriba solo queda la boca (un arco con piedras paradas, o una empalizada); abajo, a {@link #DEPTH_BELOW} bloques bajo el
 * punto más bajo del terreno de encima, una fila de salas:
 * <pre>
 *  escalera ─ A antesala ─ pasillo (trampas) ─ B cripta / campamento ─ puerta (garras) ─ pasillo ─ T trono ─ X tesoro
 *                                                     └─ S cuarto del costado (la Garra / los presos)
 * </pre>
 * Primero se ponen todas las cáscaras (paredes, piso, techo) y después se vacían todos los interiores: así un pasillo
 * corta la pared de la sala a la que llega. Nada de lo de abajo se pone por encima del terreno.
 */
public final class DungeonBuilder {
    /** Cuánto más abajo del terreno más bajo de encima va el techo de las salas. */
    public static int DEPTH_BELOW = 4;
    /** Largo total de las salas (sin la escalera) y ancho: para medir el terreno y separar mazmorras. */
    static final int LENGTH = 52, HALF_WIDTH = 8, SIDE = 14;

    /** Una sala: u0..u1 × v0..v1, {@code h} de alto (interior). */
    private record Room(int u0, int u1, int v0, int v1, int h) {}

    /** Cómo pasar de (u, dy, v) al mundo. */
    private record Frame(BlockPos origin, Direction dir) {
        BlockPos at(int u, int y, int v) {
            Direction right = dir.getClockWise();
            return new BlockPos(origin.getX() + dir.getStepX() * u + right.getStepX() * v, y,
                    origin.getZ() + dir.getStepZ() * u + right.getStepZ() * v);
        }
    }

    private DungeonBuilder() {}

    /** ¿Se puede hacer una mazmorra con la boca en {@code entrance} mirando a {@code dir}? (agua, terreno muy roto). */
    public static boolean siteOk(ServerLevel level, BlockPos entrance, Direction dir) {
        Frame f = new Frame(entrance, dir);
        int ys = entrance.getY();
        for (int u = -6; u <= 70; u += 6) {
            for (int v = -HALF_WIDTH; v <= SIDE; v += 6) {
                BlockPos c = f.at(u, 0, v);
                level.getChunk(c.getX() >> 4, c.getZ() >> 4);
                int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, c.getX(), c.getZ());
                int water = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, c.getX(), c.getZ());
                if (water - y > 1 && u <= 4) return false;   // la boca, adentro del agua
                if (Math.abs(y - ys) > 24) return false;     // un acantilado o un cañón encima
            }
        }
        return level.getFluidState(entrance.below()).isEmpty();
    }

    /** Arma la mazmorra y la devuelve (sin la gente: eso lo hace {@link Dungeons#populate}). */
    public static DungeonData.Dungeon build(ServerLevel level, DungeonData.Kind kind, BlockPos entrance, Direction dir, int id,
                                            RandomSource random) {
        Frame f = new Frame(entrance, dir);
        int ys = entrance.getY();

        // El piso de las salas: bajo el terreno más bajo que tengan encima
        int lowest = ys;
        for (int u = 0; u <= 70; u += 3) {
            for (int v = -HALF_WIDTH; v <= SIDE; v += 3) {
                BlockPos c = f.at(u, 0, v);
                lowest = Math.min(lowest, level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, c.getX(), c.getZ()));
            }
        }
        int floor = Math.max(level.getMinBuildHeight() + 8, Math.min(ys - 10, lowest - DEPTH_BELOW - 8));
        int stair = ys - floor;   // escalones (1 de bajada por bloque)
        int s = stair + 1;        // donde empieza la antesala

        Room a = new Room(s, s + 8, -4, 4, 5);
        Room c1 = new Room(s + 9, s + 15, -1, 1, 3);
        Room b = new Room(s + 15, s + 27, -6, 6, 5);
        Room link = new Room(s + 19, s + 21, 6, 8, 3);
        Room side = new Room(s + 17, s + 23, 8, 13, 4);
        Room c3 = new Room(s + 28, s + 33, -1, 1, 4);
        Room t = new Room(s + 33, s + 47, -7, 7, 7);
        Room x = new Room(s + 47, s + 50, -2, 2, 3);
        List<Room> rooms = List.of(a, c1, b, link, side, c3, t, x);

        Palette p = kind == DungeonData.Kind.BARROW ? Palette.BARROW : Palette.LAIR;

        // 1) Cáscaras: escalera y salas (nunca por encima del terreno)
        for (int u = 1; u <= stair; u++) {
            int y = ys - u;
            for (int v = -2; v <= 2; v++) {
                for (int dy = -1; dy <= 4; dy++) shell(level, f.at(u, y + dy, v), p, random, dy == -1, dy == 4);
            }
        }
        for (Room r : rooms) {
            for (int u = r.u0 - 1; u <= r.u1 + 1; u++) {
                for (int v = r.v0 - 1; v <= r.v1 + 1; v++) {
                    for (int dy = -1; dy <= r.h; dy++) shell(level, f.at(u, floor + dy, v), p, random, dy == -1, dy == r.h);
                }
            }
        }
        // 2) Interiores
        for (int u = 1; u <= stair; u++) {
            int y = ys - u;
            for (int v = -1; v <= 1; v++) {
                for (int dy = 1; dy <= 3; dy++) air(level, f.at(u, y + dy, v));
                set(level, f.at(u, y, v), p.stairs.setValue(StairBlock.FACING, dir.getOpposite()));
            }
        }
        for (Room r : rooms) {
            for (int u = r.u0; u <= r.u1; u++) {
                for (int v = r.v0; v <= r.v1; v++) {
                    for (int dy = 0; dy < r.h; dy++) air(level, f.at(u, floor + dy, v));
                }
            }
        }

        // Caja y medio
        BlockPos p0 = f.at(-1, floor - 1, -HALF_WIDTH), p1 = f.at(s + 51, ys + 4, SIDE);
        int[] box = {Math.min(p0.getX(), p1.getX()), floor - 1, Math.min(p0.getZ(), p1.getZ()),
                Math.max(p0.getX(), p1.getX()), ys + 4, Math.max(p0.getZ(), p1.getZ())};
        BlockPos center = f.at(s + 21, floor, 0);
        DungeonData.Dungeon d = new DungeonData.Dungeon(id, kind, entrance, center, box);

        if (kind == DungeonData.Kind.BARROW) decorateBarrow(level, f, d, floor, ys, stair, a, c1, b, side, c3, t, x, random);
        else decorateLair(level, f, d, floor, ys, stair, a, c1, b, side, c3, t, x, random);
        WarbandCraft.LOGGER.info("{} {} en {} (piso y={}, mirando al {})", kind == DungeonData.Kind.BARROW ? "Túmulo" : "Guarida",
                id, entrance.toShortString(), floor, dir.getName());
        return d;
    }

    // ------------------------------------------------------------------ túmulo

    private static void decorateBarrow(ServerLevel level, Frame f, DungeonData.Dungeon d, int floor, int ys, int stair, Room a, Room c1,
                                       Room b, Room side, Room c3, Room t, Room x, RandomSource random) {
        Direction dir = f.dir;
        // Boca: arco de piedra con dos faroles de alma y cuatro piedras paradas
        for (int v : new int[]{-2, 2}) {
            for (int dy = 0; dy < 3; dy++) set(level, f.at(0, ys + dy, v), Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
            set(level, f.at(0, ys + 3, v), Blocks.SOUL_LANTERN.defaultBlockState());
        }
        for (int v = -2; v <= 2; v++) set(level, f.at(0, ys + 3, v), v == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                : Blocks.STONE_BRICK_SLAB.defaultBlockState());
        set(level, f.at(0, ys + 4, -2), Blocks.SOUL_LANTERN.defaultBlockState());
        set(level, f.at(0, ys + 4, 2), Blocks.SOUL_LANTERN.defaultBlockState());
        int[][] stones = {{-4, -4}, {-4, 4}, {-7, -2}, {-7, 2}};
        for (int[] st : stones) {
            BlockPos base = ground(level, f.at(st[0], ys, st[1]));
            int hgt = 2 + random.nextInt(2);
            for (int dy = 0; dy < hgt; dy++) set(level, base.above(dy), (dy == hgt - 1 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState());
        }
        // Escalera: antorchas de alma cada 4
        for (int u = 3; u <= stair; u += 4) wallTorch(level, f.at(u, ys - u + 2, -1), dir.getClockWise(), true);

        // A, antesala: columnas, telarañas, urnas y dos draugr de pie
        for (int u : new int[]{a.u0 + 2, a.u1 - 2}) {
            for (int v : new int[]{-3, 3}) for (int dy = 0; dy < a.h; dy++) set(level, f.at(u, floor + dy, v), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        }
        set(level, f.at(a.u0, floor + a.h - 1, a.v0), Blocks.COBWEB.defaultBlockState());
        set(level, f.at(a.u1, floor + a.h - 1, a.v1), Blocks.COBWEB.defaultBlockState());
        set(level, f.at(a.u0 + 1, floor, a.v1), Blocks.DECORATED_POT.defaultBlockState());
        set(level, f.at(a.u1 - 1, floor, a.v0), Blocks.DECORATED_POT.defaultBlockState());
        hang(level, f.at((a.u0 + a.u1) / 2, floor + a.h - 1, 0), Blocks.SOUL_LANTERN);
        d.spots.add(f.at(a.u0 + 4, floor, -2));
        d.spots.add(f.at(a.u0 + 4, floor, 2));

        // Pasillo con trampas: placa → dispensador de flechas al lado
        for (int u : new int[]{c1.u0 + 2, c1.u0 + 4}) {
            set(level, f.at(u, floor, -1), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
            BlockPos disp = f.at(u, floor, -2);
            set(level, disp, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, dir.getClockWise()));
            if (level.getBlockEntity(disp) instanceof DispenserBlockEntity be) be.setItem(0, new ItemStack(Items.ARROW, 16));
        }

        // B, cripta: ataúdes en las paredes (nicho 2 de largo con tapa), velas, telarañas
        for (int side2 : new int[]{-1, 1}) {
            int wallV = side2 < 0 ? b.v0 - 1 : b.v1 + 1;
            for (int u = b.u0 + 1; u + 1 <= b.u1 - 1; u += 3) {
                if (side2 > 0 && u + 1 >= side.u0 + 2 && u <= side.u1 - 2) continue;   // ahí está la puerta al costado
                for (int du = 0; du < 2; du++) {
                    set(level, f.at(u + du, floor - 1, wallV), Blocks.SPRUCE_PLANKS.defaultBlockState());
                    air(level, f.at(u + du, floor, wallV));
                    air(level, f.at(u + du, floor + 1, wallV));
                    BlockPos lid = f.at(u + du, floor, wallV - side2);
                    Direction facing = side2 < 0 ? dir.getCounterClockWise() : dir.getClockWise();
                    set(level, lid, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true)
                            .setValue(TrapDoorBlock.FACING, facing.getOpposite()).setValue(TrapDoorBlock.HALF, Half.BOTTOM));
                    set(level, lid.above(), Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true)
                            .setValue(TrapDoorBlock.FACING, facing.getOpposite()).setValue(TrapDoorBlock.HALF, Half.TOP));
                }
                d.coffins.add(f.at(u, floor, wallV));
            }
        }
        for (int i = 0; i < 6; i++) {
            BlockPos c = f.at(b.u0 + 1 + random.nextInt(b.u1 - b.u0 - 1), floor, b.v0 + 1 + random.nextInt(b.v1 - b.v0 - 1));
            if (level.getBlockState(c).isAir()) set(level, c, Blocks.CANDLE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 1 + random.nextInt(4))
                    .setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        }
        set(level, f.at(b.u0, floor + b.h - 1, b.v0), Blocks.COBWEB.defaultBlockState());
        set(level, f.at(b.u1, floor + b.h - 1, b.v1), Blocks.COBWEB.defaultBlockState());
        hang(level, f.at(b.u0 + 3, floor + b.h - 1, 0), Blocks.SOUL_LANTERN);
        hang(level, f.at(b.u1 - 3, floor + b.h - 1, 0), Blocks.SOUL_LANTERN);

        // S, cuarto del costado: el guardián con la Garra de oro, un sarcófago y un cofre
        for (int u = side.u0 + 2; u <= side.u0 + 4; u++) set(level, f.at(u, floor, side.v1 - 1), Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState());
        chest(level, f.at(side.u1, floor, side.v1), dir.getOpposite(), loot("chests/barrow"), random);
        hang(level, f.at((side.u0 + side.u1) / 2, floor + side.h - 1, (side.v0 + side.v1) / 2), Blocks.SOUL_LANTERN);
        d.spots.add(f.at((side.u0 + side.u1) / 2, floor, (side.v0 + side.v1) / 2));   // el último lugar: el guardián

        // La puerta de las garras (u = principio del pasillo), y los tres marcos en la pared de la cripta, a la izquierda
        int du = c3.u0 + 1;
        for (int v = -1; v <= 1; v++) {
            for (int dy = 0; dy < c3.h; dy++) {
                BlockPos db = f.at(du, floor + dy, v);
                set(level, db, (v == 0 && dy == 1 ? Blocks.CHISELED_POLISHED_BLACKSTONE : Blocks.POLISHED_BLACKSTONE_BRICKS).defaultBlockState());
                d.door.add(db);
            }
        }
        d.answer = new int[]{random.nextInt(Barrows.SYMBOLS.length), random.nextInt(Barrows.SYMBOLS.length), random.nextInt(Barrows.SYMBOLS.length)};
        int[] shown = new int[3];
        boolean same = true;
        while (same) {
            same = true;
            for (int i = 0; i < 3; i++) {
                shown[i] = random.nextInt(Barrows.SYMBOLS.length);
                if (shown[i] != d.answer[i]) same = false;
            }
        }
        for (int i = 0; i < 3; i++) {
            BlockPos wall = f.at(b.u1 + 1, floor + 1, -5 + i);
            set(level, wall, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            BlockPos at = f.at(b.u1, floor + 1, -5 + i);
            Barrows.placePillar(level, at, dir.getOpposite(), shown[i], d.id);
            d.pillars.add(at);
        }

        // T, sala del trono: columnas, braseros de alma, alfombra, el trono
        for (int u = t.u0 + 2; u <= t.u1 - 3; u += 4) {
            for (int v : new int[]{-4, 4}) for (int dy = 0; dy < t.h; dy++) set(level, f.at(u, floor + dy, v), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            for (int v : new int[]{-6, 6}) set(level, f.at(u, floor, v), Blocks.SOUL_CAMPFIRE.defaultBlockState());
        }
        for (int u = t.u0; u <= t.u1 - 3; u++) set(level, f.at(u, floor, 0), Blocks.GRAY_CARPET.defaultBlockState());
        BlockPos throne = f.at(t.u1 - 2, floor, 0);
        set(level, throne, Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dir));
        set(level, throne.relative(dir), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
        set(level, throne.relative(dir).above(), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
        set(level, throne.relative(dir).above(2), Blocks.WITHER_SKELETON_SKULL.defaultBlockState());
        d.throne = throne.relative(dir.getOpposite()).immutable();

        // X, tesoro
        chest(level, f.at(x.u1, floor, -1), dir.getOpposite(), loot("chests/barrow_treasure"), random);
        chest(level, f.at(x.u1, floor, 1), dir.getOpposite(), loot("chests/barrow"), random);
        set(level, f.at(x.u1, floor, 0), Blocks.GOLD_BLOCK.defaultBlockState());
        hang(level, f.at(x.u0 + 1, floor + x.h - 1, 0), Blocks.SOUL_LANTERN);
        d.treasure = f.at(x.u1, floor, -1).immutable();
    }

    // ------------------------------------------------------------------ guarida

    private static void decorateLair(ServerLevel level, Frame f, DungeonData.Dungeon d, int floor, int ys, int stair, Room a, Room c1,
                                     Room b, Room side, Room c3, Room t, Room x, RandomSource random) {
        Direction dir = f.dir;
        // Boca: empalizada de troncos con un farol
        for (int v : new int[]{-2, 2}) {
            for (int u = -1; u <= 2; u++) {
                BlockPos base = ground(level, f.at(u, ys, v));
                for (int dy = 0; dy < 2; dy++) set(level, base.above(dy), Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
        BlockPos post = ground(level, f.at(-1, ys, 2)).above(2);
        set(level, post, Blocks.LANTERN.defaultBlockState());
        // Escalera: vigas cada 4 y antorchas
        for (int u = 2; u <= stair; u += 4) {
            int y = ys - u;
            for (int v : new int[]{-2, 2}) for (int dy = 1; dy <= 3; dy++) set(level, f.at(u, y + dy, v), Blocks.SPRUCE_LOG.defaultBlockState());
            for (int v = -1; v <= 1; v++) if (y + 4 < ys) set(level, f.at(u, y + 4, v), Blocks.SPRUCE_PLANKS.defaultBlockState());
            if (u + 2 <= stair) wallTorch(level, f.at(u + 2, ys - u, -1), dir.getClockWise(), false);
        }

        // A: fogón, troncos para sentarse, barriles
        BlockPos fire = f.at((a.u0 + a.u1) / 2, floor, 0);
        set(level, fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        for (int v : new int[]{-2, 2}) set(level, f.at((a.u0 + a.u1) / 2, floor, v), Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, dir.getAxis()));
        set(level, f.at(a.u0, floor, a.v0), Blocks.BARREL.defaultBlockState());
        set(level, f.at(a.u0, floor, a.v1), Blocks.BARREL.defaultBlockState());
        support(level, f, a, floor);
        d.spots.add(f.at(a.u0 + 2, floor, -2));
        d.spots.add(f.at(a.u0 + 2, floor, 2));
        d.spots.add(f.at(a.u1 - 1, floor, 3));

        // Pasillo: vigas
        support(level, f, c1, floor);

        // B: el campamento: mantas (alfombras), mesa, barriles, un cofre
        DyeColorCarpets.place(level, f, b.u0 + 1, b.u1 - 1, b.v0 + 1, floor, random);
        set(level, f.at(b.u1 - 1, floor, b.v0), Blocks.CRAFTING_TABLE.defaultBlockState());
        set(level, f.at(b.u0, floor, b.v1), Blocks.BARREL.defaultBlockState());
        set(level, f.at(b.u0 + 1, floor, b.v1), Blocks.BARREL.defaultBlockState());
        chest(level, f.at(b.u1, floor, b.v0), dir.getOpposite(), loot("chests/lair"), random);
        support(level, f, b, floor);
        for (int i = 0; i < 4; i++) d.spots.add(f.at(b.u0 + 2 + i * 3, floor, i % 2 == 0 ? -3 : 3));

        // S: la celda de los presos, cerrada con una puerta de abeto y barrotes
        for (int u = side.u0; u <= side.u1; u++) {
            if (u == (side.u0 + side.u1) / 2) continue;
            for (int dy = 0; dy < side.h; dy++) set(level, f.at(u, floor + dy, side.v0), Blocks.IRON_BARS.defaultBlockState());
        }
        BlockPos door = f.at((side.u0 + side.u1) / 2, floor, side.v0);
        Direction doorFacing = dir.getClockWise();
        set(level, door, Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(level, door.above(), Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (int dy = 2; dy < side.h; dy++) set(level, door.above(dy), Blocks.IRON_BARS.defaultBlockState());
        for (int i = 0; i < 4; i++) d.cells.add(f.at(side.u0 + 1 + i * 2 % (side.u1 - side.u0), floor, side.v1 - 1 - (i % 2)));
        set(level, f.at(side.u1, floor, side.v1), Blocks.HAY_BLOCK.defaultBlockState());
        hang(level, f.at((side.u0 + side.u1) / 2, floor + side.h - 1, side.v1 - 2), Blocks.LANTERN);

        // Pasillo hacia el jefe y la sala del jefe: mesa, estandarte, cofre
        support(level, f, c3, floor);
        support(level, f, t, floor);
        for (int u = t.u0 + 4; u <= t.u0 + 6; u++) set(level, f.at(u, floor, 0), Blocks.SPRUCE_PLANKS.defaultBlockState());
        set(level, f.at(t.u0 + 5, floor + 1, 0), Blocks.LANTERN.defaultBlockState());
        set(level, f.at(t.u1, floor + 1, 0), Blocks.BLACK_WALL_BANNER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, dir.getOpposite()));
        chest(level, f.at(t.u1, floor, -2), dir.getOpposite(), loot("chests/lair_treasure"), random);
        d.treasure = f.at(t.u1, floor, -2).immutable();
        d.throne = f.at(t.u1 - 3, floor, 0).immutable();   // donde espera el Jefe de la banda
        d.spots.add(f.at(t.u0 + 3, floor, -4));
        d.spots.add(f.at(t.u0 + 3, floor, 4));

        // X: el depósito
        set(level, f.at(x.u1, floor, -2), Blocks.BARREL.defaultBlockState());
        set(level, f.at(x.u1, floor, 2), Blocks.BARREL.defaultBlockState());
        chest(level, f.at(x.u1, floor, 0), dir.getOpposite(), loot("chests/lair"), random);
    }

    /** Vigas de abeto cada 4 bloques y una antorcha cada 8, en una sala de la guarida. */
    private static void support(ServerLevel level, Frame f, Room r, int floor) {
        for (int u = r.u0; u <= r.u1; u += 4) {
            for (int v : new int[]{r.v0, r.v1}) {
                for (int dy = 0; dy < r.h; dy++) {
                    BlockPos p = f.at(u, floor + dy, v);
                    if (level.getBlockState(p).isAir()) set(level, p, Blocks.SPRUCE_LOG.defaultBlockState());
                }
            }
            for (int v = r.v0; v <= r.v1; v++) set(level, f.at(u, floor + r.h - 1, v), Blocks.SPRUCE_PLANKS.defaultBlockState());
            if ((u - r.u0) % 8 == 0 && r.v1 - r.v0 >= 2) wallTorch(level, f.at(u + 1, floor + 2, r.v0), f.dir.getClockWise(), false);
        }
    }

    /** Alfombras de colores como mantas para dormir. */
    private static final class DyeColorCarpets {
        private static final Block[] CARPETS = {Blocks.BROWN_CARPET, Blocks.GRAY_CARPET, Blocks.LIGHT_GRAY_CARPET, Blocks.GREEN_CARPET};

        static void place(ServerLevel level, Frame f, int u0, int u1, int v, int floor, RandomSource random) {
            for (int u = u0; u <= u1; u += 2) {
                Block c = CARPETS[random.nextInt(CARPETS.length)];
                set(level, f.at(u, floor, v), c.defaultBlockState());
                set(level, f.at(u, floor, v + 1), c.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ ayudas

    private enum Palette {
        BARROW(new Block[]{Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS},
                new Block[]{Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES},
                new Block[]{Blocks.DEEPSLATE_BRICKS}, Blocks.STONE_BRICK_STAIRS.defaultBlockState()),
        LAIR(new Block[]{Blocks.STONE, Blocks.STONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.DIRT, Blocks.MOSSY_COBBLESTONE},
                new Block[]{Blocks.COARSE_DIRT, Blocks.COARSE_DIRT, Blocks.DIRT, Blocks.PACKED_MUD},
                new Block[]{Blocks.STONE, Blocks.COBBLESTONE}, Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        final Block[] walls, floors, ceilings;
        final BlockState stairs;

        Palette(Block[] walls, Block[] floors, Block[] ceilings, BlockState stairs) {
            this.walls = walls;
            this.floors = floors;
            this.ceilings = ceilings;
            this.stairs = stairs;
        }
    }

    /** Un bloque de cáscara: no pisa lo que está por encima del terreno ni lo que tiene block entity. */
    private static void shell(ServerLevel level, BlockPos p, Palette pal, RandomSource random, boolean isFloor, boolean isCeiling) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        if (p.getY() >= top - 1 && !isFloor) return;   // al aire libre (la boca de la escalera): nada
        if (level.getBlockEntity(p) != null) return;
        Block[] from = isFloor ? pal.floors : isCeiling ? pal.ceilings : pal.walls;
        set(level, p, from[random.nextInt(from.length)].defaultBlockState());
    }

    private static void air(ServerLevel level, BlockPos p) {
        if (level.getBlockEntity(p) != null) return;
        set(level, p, Blocks.AIR.defaultBlockState());
    }

    private static void set(ServerLevel level, BlockPos p, BlockState state) {
        level.setBlock(p, state, Block.UPDATE_CLIENTS);
    }

    private static void hang(ServerLevel level, BlockPos p, Block lantern) {
        set(level, p, lantern.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private static void wallTorch(ServerLevel level, BlockPos p, Direction facing, boolean soul) {
        if (!level.getBlockState(p).isAir()) return;
        set(level, p, (soul ? Blocks.SOUL_WALL_TORCH : Blocks.WALL_TORCH).defaultBlockState().setValue(WallTorchBlock.FACING, facing));
    }

    /** El piso (primer bloque firme con aire encima) bajando desde {@code p}. */
    private static BlockPos ground(ServerLevel level, BlockPos p) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    private static ResourceLocation loot(String path) {
        return new ResourceLocation(WarbandCraft.MODID, path);
    }

    private static void chest(ServerLevel level, BlockPos p, Direction facing, ResourceLocation table, RandomSource random) {
        set(level, p, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(p);
        if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(table, random.nextLong());
    }

    /** Para las pruebas: lo que mide una mazmorra a lo largo, con la escalera de {@code stair} escalones. */
    public static int length(int stair) {
        return stair + 1 + LENGTH;
    }

}
