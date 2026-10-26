package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.upgrade.TownData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aldeas quemadas (pedido de Ale: "atacar una aldea cercana y QUEMARLA: deja de ser aldea, aparece toda rota, mueren todos
 * los aldeanos, queda abandonada"). {@link #burn}: mueren aldeanos y gólems, se van los soldados y comerciantes de la
 * facción, la aldea se borra de {@link VillageData} (y de lo que se guarda aparte: mejoras, claim) y queda anotada acá
 * como ruina: {@code VillageManager.scan} no vuelve a encontrar una aldea en ese lugar. Los bloques se rompen de a
 * {@link #COLUMNS_PER_TICK} columnas por tick ({@link #tick}): madera quemada o caída (troncos a basalto), sin
 * campanas, camas, puertas, vidrios, antorchas, lana ni cultivos; piedra con musgo y rajada, caminos de tierra gruesa,
 * cofres vacíos, telarañas adentro, enredaderas en las paredes, arbustos secos y calaveras. Unas fogatas humean
 * {@link #SMOKE_DAYS} días y se apagan solas. Al entrar: "Ruinas de ...".
 */
public final class Ruins extends SavedData {
    private static final String FILE = "warbandcraft_ruins";

    /** Radio de lo que se quema (sin anillo de mejoras); con anillo, el anillo + {@link #RING_PAD}, hasta {@link #MAX_RADIUS}. */
    public static int RADIUS = 48, RING_PAD = 6, MAX_RADIUS = 64;
    /** Cuánto abajo y arriba del centro se recorre. */
    public static int DEPTH = 10, HEIGHT = 28;
    public static int COLUMNS_PER_TICK = 250;
    /** Fogatas que humean, y por cuántos días. */
    public static int SMOKE_FIRES = 6, SMOKE_DAYS = 2;
    /** Ninguna aldea nueva a esta distancia de una ruina. */
    public static int NO_VILLAGE_RADIUS = 64;
    /** Cartel al entrar. */
    public static int ENTER_RADIUS = 48;

    // Chances de cada cambio (0..1)
    public static double PLANK_GONE = 0.45, PLANK_DARK = 0.25, WOOD_PIECE_GONE = 0.5, LOG_BASALT = 0.4, LOG_GONE = 0.2,
            LEAVES_GONE = 0.7, STONE_MOSSY = 0.25, STONE_CRACKED = 0.3, STONE_GONE = 0.07, PATH_COARSE = 0.45, PATH_GRAVEL = 0.15,
            GRASS_COARSE = 0.3, FLOWER_GONE = 0.6, TRAPDOOR_GONE = 0.6, WORKSTATION_GONE = 0.5, COBWEB = 0.06, VINE = 0.05,
            DEAD_BUSH = 0.025, SKULL = 0.004, RUBBLE_FIRE = 0.005;

    public record Ruin(BlockPos center, String name, long day, List<Long> smoke) {}

    private final Map<Long, Ruin> ruins = new HashMap<>();

    /** Quemas en curso (en memoria: si se cierra el servidor en el medio, lo que faltaba queda como estaba). */
    private static final List<Job> JOBS = new ArrayList<>();
    /** Quién ya vio el cartel de qué ruina (jugador → centro), mientras siga adentro. */
    private static final Map<UUID, Long> INSIDE = new HashMap<>();

    public static Ruins get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Ruins::load, Ruins::new, FILE);
    }

    public java.util.Collection<Ruin> all() {
        return ruins.values();
    }

    /** La ruina a ≤{@code radius} de {@code pos}, o null. */
    public Ruin near(BlockPos pos, double radius) {
        for (Ruin r : ruins.values()) if (VillageData.horizontalDistSq(r.center, pos) <= radius * radius) return r;
        return null;
    }

    /** Saca la ruina de {@code center} de la lista (los bloques quedan; lo usan las pruebas). */
    public void forget(BlockPos center) {
        if (ruins.remove(center.asLong()) != null) setDirty();
    }

    public static void clear() {
        JOBS.clear();
        INSIDE.clear();
    }

    // ------------------------------------------------------------------ quemar

    /** Quema la aldea {@code v} (no castillos). Devuelve cuántos aldeanos murieron. */
    public static int burn(ServerLevel level, VillageData.Village v) {
        TownData.Town town = TownData.get(level).at(v.center());
        return burn(level, v, town != null && town.ringRadius > 0 ? Math.min(MAX_RADIUS, town.ringRadius + RING_PAD) : RADIUS);
    }

    /** Igual, con otro radio (las pruebas queman chiquito para no tocar las de al lado). */
    public static int burn(ServerLevel level, VillageData.Village v, int radius) {
        BlockPos c = v.center();
        String name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v))).getString();

        int dead = 0;
        AABB box = new AABB(c).inflate(radius, HEIGHT, radius);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, Entity::isAlive)) {
            if (e instanceof AbstractVillager av && !(av instanceof WanderingTrader)) {
                fireDeath(level, e);
                ((LivingEntity) e).kill();
                dead++;
            } else if (e instanceof IronGolem g && !g.isPlayerCreated()) {
                fireDeath(level, e);
                g.kill();
            } else if (belongsTo(e, c)) {
                e.discard();   // soldados que quedaron, mercaderes, tabernero, constructor de la facción
            }
        }

        VillageData.get(level).remove(c);
        TownData.get(level).remove(c);
        FactionClaims.get(level).release(level, c);
        com.ale.warbandcraft.invasion.InvasionData.get(level).setPendingGarrison(c, 0);

        Ruins data = get(level);
        Ruin ruin = new Ruin(c, name, level.getDayTime() / 24000L, new ArrayList<>());
        data.ruins.put(c.asLong(), ruin);
        data.setDirty();
        JOBS.add(new Job(level, ruin, radius));
        level.playSound(null, c, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 2.0F, 0.6F);
        WarbandCraft.LOGGER.info("Aldea quemada en {} ({}): {} aldeanos muertos, radio {}", c.toShortString(), name, dead, radius);
        return dead;
    }

    private static boolean belongsTo(Entity e, BlockPos center) {
        CompoundTag pd = e.getPersistentData();
        long key = center.asLong();
        if (pd.contains(VillageManager.TAG_VILLAGE) && pd.getLong(VillageManager.TAG_VILLAGE) == key) return true;
        if (pd.contains(com.ale.warbandcraft.market.FactionMarket.TAG) && pd.getLong(com.ale.warbandcraft.market.FactionMarket.TAG) == key) return true;
        if (pd.contains(FactionInn.TAG) && pd.getLong(FactionInn.TAG) == key) return true;
        return pd.getString(com.ale.warbandcraft.upgrade.BuilderNpc.TAG).equals("town:" + key);
    }

    private static void fireDeath(ServerLevel level, Entity e) {
        level.sendParticles(ParticleTypes.FLAME, e.getX(), e.getY() + 1, e.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, e.getX(), e.getY() + 1, e.getZ(), 6, 0.3, 0.6, 0.3, 0.02);
    }

    /** Termina en el acto todas las quemas en curso (lo usan las pruebas). */
    public static void finishAll() {
        for (Job j : JOBS) while (j.step(Integer.MAX_VALUE)) { }
        JOBS.clear();
    }

    /** Cada tick: avanza las quemas; cada 2 s, apaga fogatas viejas y muestra el cartel al entrar. */
    public static void tick(ServerLevel level) {
        for (Iterator<Job> it = JOBS.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.level != level) continue;
            if (!j.step(COLUMNS_PER_TICK)) it.remove();
        }
        if (level.getGameTime() % 40 != 7 || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        Ruins data = get(level);
        if (data.ruins.isEmpty()) return;
        long day = level.getDayTime() / 24000L;
        for (Ruin r : data.ruins.values()) {
            if (r.smoke.isEmpty() || day < r.day + SMOKE_DAYS || !level.isLoaded(r.center)) continue;
            for (long p : r.smoke) {
                BlockPos pos = BlockPos.of(p);
                BlockState s = level.getBlockState(pos);
                if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) level.setBlock(pos, s.setValue(CampfireBlock.LIT, false), 3);
            }
            r.smoke.clear();
            data.setDirty();
        }
        for (ServerPlayer p : level.players()) {
            Ruin r = data.near(p.blockPosition(), ENTER_RADIUS);
            Long was = INSIDE.get(p.getUUID());
            if (r == null) {
                INSIDE.remove(p.getUUID());
            } else if (was == null || was != r.center.asLong()) {
                INSIDE.put(p.getUUID(), r.center.asLong());
                p.displayClientMessage(Component.translatable("msg.warbandcraft.ruins.enter", r.name, day - r.day).withStyle(ChatFormatting.DARK_GRAY), true);
            }
        }
    }

    /** La quema de una aldea, columna por columna. */
    private static final class Job {
        final ServerLevel level;
        final Ruin ruin;
        final List<int[]> columns = new ArrayList<>();
        final Set<Long> smokeColumns = new HashSet<>();
        int next;
        final RandomSource random;

        Job(ServerLevel level, Ruin ruin, int radius) {
            this.level = level;
            this.ruin = ruin;
            this.random = RandomSource.create(ruin.center.asLong());
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) if (dx * dx + dz * dz <= radius * radius) columns.add(new int[]{dx, dz});
            }
            for (int i = 0; i < SMOKE_FIRES; i++) {
                int[] col = columns.get(random.nextInt(columns.size()));
                if (col[0] * col[0] + col[1] * col[1] <= (radius * 0.7) * (radius * 0.7)) smokeColumns.add(BlockPos.asLong(col[0], 0, col[1]));
            }
        }

        /** Hace hasta {@code n} columnas; false = terminó. */
        boolean step(int n) {
            for (int i = 0; i < n && next < columns.size(); i++, next++) {
                int[] col = columns.get(next);
                int x = ruin.center.getX() + col[0], z = ruin.center.getZ() + col[1];
                if (!level.isLoaded(new BlockPos(x, ruin.center.getY(), z))) continue;
                burnColumn(x, z);
                decorate(x, z, smokeColumns.contains(BlockPos.asLong(col[0], 0, col[1])));
            }
            if (next < columns.size()) return true;
            get(level).setDirty();
            return false;
        }

        private void burnColumn(int x, int z) {
            int top = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), ruin.center.getY());
            int from = ruin.center.getY() - DEPTH, to = Math.min(top + 1, ruin.center.getY() + HEIGHT);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = from; y <= to; y++) {
                pos.set(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir()) {
                    if (y > ruin.center.getY() - 3) agedAir(pos.immutable());
                    continue;
                }
                BlockState out = burnt(pos, s);
                if (out != null && out != s) level.setBlock(pos, out, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }

        /** Lo que queda de un bloque quemado; null = igual. */
        private BlockState burnt(BlockPos pos, BlockState s) {
            Block b = s.getBlock();
            RandomSource r = random;
            if (b instanceof BellBlock) return Blocks.AIR.defaultBlockState();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && !(be instanceof com.ale.warbandcraft.camp.CampBlockEntity)) {
                c.clearContent();   // saqueado
                return null;
            }
            if (b instanceof BedBlock || b instanceof DoorBlock || s.is(BlockTags.BANNERS)) return Blocks.AIR.defaultBlockState();
            if (be != null) return null;   // lo demás con block entity (spawners, mods...) no se toca
            if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || b == Blocks.HAY_BLOCK || b instanceof TorchBlock
                    || b instanceof LanternBlock || s.is(BlockTags.FLOWER_POTS) || s.is(Tags.Blocks.GLASS) || s.is(Tags.Blocks.GLASS_PANES)
                    || b instanceof CropBlock || b instanceof StemBlock) {
                return Blocks.AIR.defaultBlockState();
            }
            if (b instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) return s.setValue(CampfireBlock.LIT, false);
            if (s.is(BlockTags.FLOWERS)) return r.nextDouble() < FLOWER_GONE ? Blocks.AIR.defaultBlockState() : null;
            if (s.is(BlockTags.TRAPDOORS)) return r.nextDouble() < TRAPDOOR_GONE ? Blocks.AIR.defaultBlockState() : null;
            if (s.is(BlockTags.LEAVES)) return r.nextDouble() < LEAVES_GONE ? Blocks.AIR.defaultBlockState() : null;
            if (s.is(BlockTags.LOGS_THAT_BURN)) {
                double d = r.nextDouble();
                if (d < LOG_BASALT) return s.hasProperty(BlockStateProperties.AXIS)
                        ? Blocks.BASALT.defaultBlockState().setValue(BlockStateProperties.AXIS, s.getValue(BlockStateProperties.AXIS)) : Blocks.BASALT.defaultBlockState();
                return d < LOG_BASALT + LOG_GONE ? Blocks.AIR.defaultBlockState() : null;
            }
            if (s.is(BlockTags.PLANKS)) {
                double d = r.nextDouble();
                if (d < PLANK_GONE) return Blocks.AIR.defaultBlockState();
                return d < PLANK_GONE + PLANK_DARK ? Blocks.DARK_OAK_PLANKS.defaultBlockState() : null;
            }
            if (s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES)
                    || s.is(BlockTags.WOODEN_PRESSURE_PLATES) || s.is(BlockTags.WOODEN_BUTTONS)) {
                return r.nextDouble() < WOOD_PIECE_GONE ? Blocks.AIR.defaultBlockState() : null;
            }
            if (isWorkstation(b)) return r.nextDouble() < WORKSTATION_GONE ? Blocks.AIR.defaultBlockState() : null;
            if (b == Blocks.COBBLESTONE) return stone(Blocks.MOSSY_COBBLESTONE.defaultBlockState(), null);
            if (b == Blocks.COBBLESTONE_STAIRS) return stone(copy(s, Blocks.MOSSY_COBBLESTONE_STAIRS), null);
            if (b == Blocks.COBBLESTONE_SLAB) return stone(copy(s, Blocks.MOSSY_COBBLESTONE_SLAB), null);
            if (b == Blocks.COBBLESTONE_WALL) return stone(copy(s, Blocks.MOSSY_COBBLESTONE_WALL), null);
            if (b == Blocks.STONE_BRICKS) return stone(Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.CRACKED_STONE_BRICKS.defaultBlockState());
            if (b == Blocks.SANDSTONE || b == Blocks.CUT_SANDSTONE || b == Blocks.SMOOTH_SANDSTONE || b == Blocks.MUD_BRICKS || b == Blocks.TERRACOTTA)
                return r.nextDouble() < STONE_GONE * 2 ? Blocks.AIR.defaultBlockState() : null;
            if (b == Blocks.DIRT_PATH) {
                double d = r.nextDouble();
                if (d < PATH_COARSE) return Blocks.COARSE_DIRT.defaultBlockState();
                return d < PATH_COARSE + PATH_GRAVEL ? Blocks.GRAVEL.defaultBlockState() : null;
            }
            if (b == Blocks.FARMLAND) return Blocks.COARSE_DIRT.defaultBlockState();
            if (b == Blocks.GRASS_BLOCK && level.getBlockState(pos.above()).isAir()) return r.nextDouble() < GRASS_COARSE ? Blocks.COARSE_DIRT.defaultBlockState() : null;
            return null;
        }

        /** Piedra: con musgo, rajada o caída. */
        private BlockState stone(BlockState mossy, BlockState cracked) {
            double d = random.nextDouble();
            if (d < STONE_GONE) return Blocks.AIR.defaultBlockState();
            if (d < STONE_GONE + STONE_MOSSY) return mossy;
            if (cracked != null && d < STONE_GONE + STONE_MOSSY + STONE_CRACKED) return cracked;
            return null;
        }

        /** Telarañas bajo techo y enredaderas sobre las paredes. */
        private void agedAir(BlockPos pos) {
            BlockState above = level.getBlockState(pos.above());
            boolean roof = !above.isAir() && above.isSolidRender(level, pos.above());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(d);
                BlockState wall = level.getBlockState(side);
                if (!wall.isFaceSturdy(level, side, d.getOpposite())) continue;
                if (roof && random.nextDouble() < COBWEB) {
                    level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_CLIENTS);
                    return;
                }
                if (!roof && random.nextDouble() < VINE) {
                    level.setBlock(pos, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d), true), Block.UPDATE_CLIENTS);
                    return;
                }
                return;
            }
        }

        /** Arriba de todo: arbustos secos, calaveras, escombros y las fogatas que humean. */
        private void decorate(int x, int z, boolean smoke) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos at = new BlockPos(x, y, z);
            BlockState ground = level.getBlockState(at.below());
            if (!level.getBlockState(at).isAir() || !ground.isFaceSturdy(level, at.below(), Direction.UP)) return;
            if (Math.abs(y - ruin.center.getY()) > HEIGHT) return;
            if (smoke) {
                level.setBlock(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true), Block.UPDATE_ALL);
                ruin.smoke.add(at.asLong());
                return;
            }
            double d = random.nextDouble();
            if (d < DEAD_BUSH && (ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND))) {
                level.setBlock(at, Blocks.DEAD_BUSH.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (d < DEAD_BUSH + SKULL) {
                level.setBlock(at, Blocks.SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, random.nextInt(16)), Block.UPDATE_ALL);
            } else if (d < DEAD_BUSH + SKULL + RUBBLE_FIRE) {
                level.setBlock(at, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
            }
        }
    }

    private static BlockState copy(BlockState from, Block to) {
        BlockState out = to.defaultBlockState();
        for (var p : from.getProperties()) {
            if (out.hasProperty(p)) out = with(out, from, p);
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState out, BlockState from, net.minecraft.world.level.block.state.properties.Property<T> p) {
        return out.setValue(p, from.getValue(p));
    }

    private static boolean isWorkstation(Block b) {
        return b == Blocks.COMPOSTER || b == Blocks.LECTERN || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE || b == Blocks.CARTOGRAPHY_TABLE
                || b == Blocks.FLETCHING_TABLE || b == Blocks.GRINDSTONE || b == Blocks.LOOM || b == Blocks.SMITHING_TABLE
                || b == Blocks.STONECUTTER || b == Blocks.CAULDRON || b == Blocks.WATER_CAULDRON;
    }

    // ------------------------------------------------------------------ guardado

    public static Ruins load(CompoundTag tag) {
        Ruins data = new Ruins();
        for (Tag t : tag.getList("Ruins", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            BlockPos center = BlockPos.of(c.getLong("Center"));
            List<Long> smoke = new ArrayList<>();
            for (long p : c.getLongArray("Smoke")) smoke.add(p);
            data.ruins.put(center.asLong(), new Ruin(center, c.getString("Name"), c.getLong("Day"), smoke));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Ruin r : ruins.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", r.center.asLong());
            c.putString("Name", r.name);
            c.putLong("Day", r.day);
            c.putLongArray("Smoke", r.smoke.stream().mapToLong(Long::longValue).toArray());
            list.add(c);
        }
        tag.put("Ruins", list);
        return tag;
    }
}
