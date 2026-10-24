package com.ale.warbandcraft.tutorial;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salón del tutorial (pedido de Ale): en cada mundo nuevo, en el spawn, un salón de piedra con un atril por tema
 * (libro escrito + cartel) y uno en el medio para empezar. El jugador aparece adentro. Los chunks de alrededor
 * ({@link #PROTECT_RADIUS_CHUNKS}) no se pueden romper ni construir (salvo en creativo con permisos de operador).
 * Los libros salen de {@code warband_tutorial/} ({@link TutorialBooks}); si alguien se lleva uno, vuelve a aparecer.
 */
public final class Tutorial {
    /** Radio de la zona protegida alrededor del chunk del medio del salón, en chunks (2 = 5×5 chunks). */
    public static int PROTECT_RADIUS_CHUNKS = 2;
    /** Cada cuántos ticks vuelve a poner los libros que se llevaron. */
    public static int RESTOCK_TICKS = 100;

    /** Medio ancho del salón: las paredes están a ±HALF del medio. */
    private static final int HALF = 18;
    /** Hasta dónde se limpia y se pone piso alrededor (plaza afuera de las paredes). */
    private static final int PLAZA = HALF + 3;
    private static final int HEIGHT = 8;
    private static final int[] OFFSETS = {-15, -9, -3, 3, 9, 15};

    private static boolean freshWorld;
    @Nullable private static BlockPos cachedCenter;
    @Nullable private static Map<String, TutorialBooks.Topic> topics;
    private static boolean refreshed;

    private Tutorial() {}

    // ------------------------------------------------------------------ arranque

    public static void onAboutToStart(MinecraftServer server) {
        freshWorld = !server.getWorldData().overworldData().isInitialized();
    }

    public static void onStarted(MinecraftServer server) {
        ServerLevel level = server.overworld();
        TutorialData data = TutorialData.get(level);
        cachedCenter = data.center();
        refreshed = false;
        if (freshWorld && cachedCenter == null && !(server instanceof GameTestServer)) {
            try {
                build(level, level.getSharedSpawnPos());
            } catch (Exception e) {
                WarbandCraft.LOGGER.error("Tutorial: no se pudo armar el salón", e);
            }
        }
        // Salón armado sin libros (p. ej. el mod no cargó sus datos al crear el mundo): se vuelve a armar en el mismo lugar.
        if (cachedCenter != null && data.lecterns.isEmpty() && !topics(server).isEmpty() && !(server instanceof GameTestServer)) {
            WarbandCraft.LOGGER.warn("Tutorial: el salón no tiene atriles; se vuelve a armar");
            build(level, cachedCenter);
        }
        freshWorld = false;
    }

    public static void onStopped() {
        cachedCenter = null;
        topics = null;
        refreshed = false;
    }

    private static Map<String, TutorialBooks.Topic> topics(MinecraftServer server) {
        if (topics == null) {
            topics = new LinkedHashMap<>();
            for (TutorialBooks.Topic t : TutorialBooks.load(server)) topics.put(t.id(), t);
        }
        return topics;
    }

    // ------------------------------------------------------------------ protección

    /** ¿Está en la zona del tutorial (overworld, chunks alrededor del salón)? */
    public static boolean isProtected(LevelAccessor level, BlockPos pos) {
        BlockPos c = cachedCenter;
        if (c == null || !(level instanceof Level l) || l.dimension() != Level.OVERWORLD || l.isClientSide) return false;
        return Math.abs((pos.getX() >> 4) - (c.getX() >> 4)) <= PROTECT_RADIUS_CHUNKS
                && Math.abs((pos.getZ() >> 4) - (c.getZ() >> 4)) <= PROTECT_RADIUS_CHUNKS;
    }

    /** ¿Algún bloque del rectángulo cae en la zona del tutorial? */
    public static boolean touches(LevelAccessor level, int x0, int z0, int x1, int z1) {
        BlockPos c = cachedCenter;
        if (c == null || !(level instanceof Level l) || l.dimension() != Level.OVERWORLD) return false;
        int cx = c.getX() >> 4, cz = c.getZ() >> 4, r = PROTECT_RADIUS_CHUNKS;
        return (Math.max(x0, x1) >> 4) >= cx - r && (Math.min(x0, x1) >> 4) <= cx + r
                && (Math.max(z0, z1) >> 4) >= cz - r && (Math.min(z0, z1) >> 4) <= cz + r;
    }

    /** Creativo y operador: puede tocar el salón (para arreglarlo a mano). */
    public static boolean exempt(@Nullable Entity e) {
        return e instanceof Player p && p.isCreative() && p.hasPermissions(2);
    }

    public static void warn(@Nullable Entity e) {
        if (e instanceof Player p) {
            p.displayClientMessage(Component.literal("Zona del tutorial: acá no se puede construir ni romper.")
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    /**
     * Vanilla hace aparecer al jugador arriba de todo lo de la columna del spawn (el techo del salón). Al entrar al mundo
     * o reaparecer: si quedó arriba del salón (o adentro de las paredes), va al lugar de aparecer de adentro.
     */
    public static void fixSpawn(net.minecraft.server.level.ServerPlayer player) {
        BlockPos c = cachedCenter;
        if (c == null || player.level().dimension() != Level.OVERWORLD) return;
        double dx = player.getX() - (c.getX() + 0.5), dz = player.getZ() - (c.getZ() + 0.5);
        if (Math.abs(dx) > PLAZA + 1 || Math.abs(dz) > PLAZA + 1 || player.getY() < c.getY() + HEIGHT - 1) return;
        player.stopRiding();
        player.teleportTo(player.serverLevel(), c.getX() + 0.5, c.getY() + 1, c.getZ() + 3.5, 180.0F, 0.0F);
    }

    // ------------------------------------------------------------------ libros

    /** Cada {@link #RESTOCK_TICKS}: vuelve a poner los libros que faltan (al arrancar, todos: así llegan los textos nuevos). */
    public static void tick(ServerLevel level) {
        if (cachedCenter == null || level.getGameTime() % RESTOCK_TICKS != 0) return;
        TutorialData data = TutorialData.get(level);
        Map<String, TutorialBooks.Topic> all = topics(level.getServer());
        boolean all0 = !refreshed;
        boolean done = true;
        for (Map.Entry<BlockPos, String> e : data.lecterns.entrySet()) {
            BlockPos pos = e.getKey();
            if (!level.hasChunkAt(pos)) {
                done = false;
                continue;
            }
            TutorialBooks.Topic t = all.get(e.getValue());
            if (t == null) continue;
            BlockState s = level.getBlockState(pos);
            if (!(s.getBlock() instanceof LecternBlock) || !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) continue;
            if (!all0 && lectern.hasBook()) continue;
            putBook(level, pos, s, lectern, t.book());
        }
        if (done) refreshed = true;
    }

    private static void putBook(ServerLevel level, BlockPos pos, BlockState s, LecternBlockEntity lectern, ItemStack book) {
        lectern.setBook(book);
        lectern.setChanged();
        if (!s.getValue(LecternBlock.HAS_BOOK)) LecternBlock.resetBookState(null, level, pos, s, true);
        else level.sendBlockUpdated(pos, s, s, 3);
    }

    // ------------------------------------------------------------------ el salón

    /** Arma el salón alrededor de {@code around} (en el piso de ahí), pone el spawn adentro y protege la zona. */
    public static BlockPos build(ServerLevel level, BlockPos around) {
        return build(level, around, false);
    }

    /** {@code test}: solo los bloques y los libros (sin spawn, sin guardar, sin proteger): para las pruebas. */
    public static BlockPos build(ServerLevel level, BlockPos around, boolean test) {
        int cx = around.getX(), cz = around.getZ();
        for (int x = -PLAZA; x <= PLAZA; x += 16) for (int z = -PLAZA; z <= PLAZA; z += 16) level.getChunk((cx + x) >> 4, (cz + z) >> 4);
        level.getChunk((cx + PLAZA) >> 4, (cz + PLAZA) >> 4);

        List<Integer> hs = new ArrayList<>();
        for (int x = -HALF; x <= HALF; x += 3) {
            for (int z = -HALF; z <= HALF; z += 3) hs.add(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + x, cz + z));
        }
        hs.sort(Integer::compare);
        int floor = hs.get(hs.size() / 2) - 1;   // en el mar, el heightmap da el agua: el salón queda sobre el agua
        floor = Math.min(floor, level.getMaxBuildHeight() - 40);
        BlockPos prev = test ? null : TutorialData.get(level).center();
        if (prev != null && prev.getX() == cx && prev.getZ() == cz) floor = prev.getY();   // rearmar el mismo: mismo piso (el techo engaña al heightmap)
        BlockPos center = new BlockPos(cx, floor, cz);

        level.getEntities((Entity) null, new AABB(cx - PLAZA, floor - 2, cz - PLAZA, cx + PLAZA + 1, floor + 30, cz + PLAZA + 1),
                e -> !(e instanceof Player)).forEach(Entity::discard);

        // Terreno: aire arriba, piso, cimientos hasta tocar algo firme.
        for (int x = -PLAZA; x <= PLAZA; x++) {
            for (int z = -PLAZA; z <= PLAZA; z++) {
                for (int y = floor + 1; y <= floor + 28; y++) set(level, cx + x, y, cz + z, Blocks.AIR.defaultBlockState());
                boolean edge = Math.abs(x) == PLAZA || Math.abs(z) == PLAZA;
                set(level, cx + x, floor, cz + z, (edge ? Blocks.STONE_BRICKS : inside(x, z) ? floorBlock(x, z)
                        : Blocks.POLISHED_ANDESITE).defaultBlockState());
                for (int y = floor - 1; y >= floor - 30; y--) {
                    BlockPos p = new BlockPos(cx + x, y, cz + z);
                    BlockState old = level.getBlockState(p);
                    if (!old.canBeReplaced() && old.getFluidState().isEmpty() && !old.is(net.minecraft.tags.BlockTags.LEAVES)
                            && !old.is(net.minecraft.tags.BlockTags.LOGS)) break;
                    set(level, cx + x, y, cz + z, (edge ? Blocks.STONE_BRICKS : Blocks.STONE).defaultBlockState());
                }
            }
        }

        // Paredes, ventanas, techo y almenas.
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                boolean wall = Math.abs(x) == HALF || Math.abs(z) == HALF;
                if (wall) {
                    int along = Math.abs(x) == HALF ? z : x;
                    boolean pillar = Math.abs(along) % 6 == 0;
                    boolean window = !pillar && Math.abs(along) % 6 == 3;
                    for (int y = 1; y < HEIGHT; y++) {
                        BlockState s = pillar ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                                : window && (y == 5 || y == 6) ? Blocks.GLASS.defaultBlockState()
                                : y == 1 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
                        set(level, cx + x, floor + y, cz + z, s);
                    }
                    if ((x + z) % 2 == 0) set(level, cx + x, floor + HEIGHT + 1, cz + z, Blocks.STONE_BRICKS.defaultBlockState());
                }
                boolean light = !wall && Math.floorMod(x, 4) == 2 && Math.floorMod(z, 4) == 2;
                set(level, cx + x, floor + HEIGHT, cz + z, (light ? Blocks.SEA_LANTERN : Blocks.STONE_BRICKS).defaultBlockState());
            }
        }

        // Puerta al sur (sin hojas: abierta) y cartel de afuera.
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) set(level, cx + x, floor + y, cz + HALF, Blocks.AIR.defaultBlockState());
        sign(level, new BlockPos(cx, floor + 5, cz + HALF + 1), Direction.SOUTH,
                List.of("", "WARBAND CRAFT", "Tutorial", ""), ChatFormatting.DARK_RED);
        // Alfombra de la puerta al medio.
        for (int z = 5; z < HALF; z++) set(level, cx, floor + 1, cz + z, Blocks.RED_CARPET.defaultBlockState());

        // Atriles.
        List<TutorialBooks.Topic> list = new ArrayList<>(topics(level.getServer()).values());
        LinkedHashMap<BlockPos, String> ordered = new LinkedHashMap<>();
        int n = 0;
        if (!list.isEmpty()) {   // el del medio
            TutorialBooks.Topic t = list.get(0);
            BlockPos lec = new BlockPos(cx, floor + 1, cz);
            set(level, cx, floor + 1, cz - 1, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            set(level, cx, floor + 2, cz - 1, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            set(level, cx, floor + 3, cz - 1, Blocks.LANTERN.defaultBlockState());
            lectern(level, lec, Direction.SOUTH);
            sign(level, new BlockPos(cx, floor + 2, cz), Direction.SOUTH, signLines("¡Empezá acá!", t), ChatFormatting.DARK_GREEN);
            ordered.put(lec, t.id());
        }
        for (Station st : stations()) {
            n++;
            if (n >= list.size()) break;
            TutorialBooks.Topic t = list.get(n);
            BlockPos lec = st.pos(cx, floor, cz);
            lectern(level, lec, st.facing());
            sign(level, lec.above(2), st.facing(), signLines(n + ".", t), ChatFormatting.DARK_BLUE);
            ordered.put(lec, t.id());
        }
        if (list.isEmpty()) WarbandCraft.LOGGER.error("Tutorial: no hay libros (¿no cargaron los datos del mod?)");
        if (list.size() > 25) WarbandCraft.LOGGER.warn("Tutorial: hay {} libros y el salón tiene 25 atriles", list.size());

        BlockPos spawn = new BlockPos(cx, floor + 1, cz + 3);
        if (!test) {
            level.setDefaultSpawnPos(spawn, 180.0F);
            level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer());
            TutorialData.get(level).set(center, ordered);
            cachedCenter = center;
            refreshed = true;
        }
        for (Map.Entry<BlockPos, String> e : ordered.entrySet()) {
            TutorialBooks.Topic t = topics(level.getServer()).get(e.getValue());
            BlockState s = level.getBlockState(e.getKey());
            if (t != null && level.getBlockEntity(e.getKey()) instanceof LecternBlockEntity lectern) putBook(level, e.getKey(), s, lectern, t.book());
        }
        WarbandCraft.LOGGER.info("Tutorial: salón armado en {} ({} libros)", center, ordered.size());
        return spawn;
    }

    private record Station(Direction wall, int offset) {
        /** Hacia dónde mira el atril (al medio del salón). */
        Direction facing() {
            return wall.getOpposite();
        }

        BlockPos pos(int cx, int floor, int cz) {
            int in = HALF - 1;
            return switch (wall) {
                case WEST -> new BlockPos(cx - in, floor + 1, cz + offset);
                case EAST -> new BlockPos(cx + in, floor + 1, cz + offset);
                case NORTH -> new BlockPos(cx + offset, floor + 1, cz - in);
                default -> new BlockPos(cx + offset, floor + 1, cz + in);
            };
        }
    }

    /** En orden de lectura: pared oeste de la puerta hacia el fondo, el fondo, la pared este, y la de la puerta. */
    private static List<Station> stations() {
        List<Station> out = new ArrayList<>();
        for (int i = OFFSETS.length - 1; i >= 0; i--) out.add(new Station(Direction.WEST, OFFSETS[i]));
        for (int o : OFFSETS) out.add(new Station(Direction.NORTH, o));
        for (int o : OFFSETS) out.add(new Station(Direction.EAST, o));
        for (int i = OFFSETS.length - 1; i >= 0; i--) out.add(new Station(Direction.SOUTH, OFFSETS[i]));
        return out;
    }

    private static boolean inside(int x, int z) {
        return Math.abs(x) < HALF && Math.abs(z) < HALF;
    }

    private static net.minecraft.world.level.block.Block floorBlock(int x, int z) {
        if (Math.abs(x) == HALF - 1 || Math.abs(z) == HALF - 1) return Blocks.POLISHED_ANDESITE;
        return Math.floorMod(x + z, 2) == 0 ? Blocks.STONE_BRICKS : Blocks.SMOOTH_STONE;
    }

    private static List<String> signLines(String first, TutorialBooks.Topic t) {
        List<String> lines = new ArrayList<>();
        lines.add(first);
        lines.addAll(t.sign().isEmpty() ? List.of(t.title()) : t.sign());
        return lines;
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState s) {
        level.setBlock(new BlockPos(x, y, z), s, 2);
    }

    private static void lectern(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlock(pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing), 3);
    }

    private static void sign(ServerLevel level, BlockPos pos, Direction facing, List<String> lines, ChatFormatting firstColor) {
        level.setBlock(pos, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), 3);
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        SignText text = new SignText();
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            Component c = Component.literal(lines.get(i));
            text = text.setMessage(i, i == 0 ? c.copy().withStyle(firstColor, ChatFormatting.BOLD) : c);
        }
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
        level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), 3);
    }
}
