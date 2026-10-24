package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.dungeon.Bandits;
import com.ale.warbandcraft.dungeon.Compat;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido de Ale: las tabernas que se encuentran por el mundo (las de Dungeons and Taverns, las del logro "Safe Haven")
 * tienen vida. No llama código del otro mod: mira las estructuras de la etiqueta {@code warbandcraft:world_taverns}.
 * <ul>
 *   <li>Un <b>Tabernero</b> nuestro sin dueño (el de H19) que cuenta rumores de mazmorras y tesoros y ofrece trabajos
 *       ({@link com.ale.warbandcraft.dungeon.Rumors}). Está siempre en su lugar, de día y de noche, y no se lo puede herir.</li>
 *   <li><b>Mercenarios</b> para contratar con la pantalla de Recruits (pagás esmeraldas): de nivel {@link #MERC_LEVEL_MIN}
 *       a {@link #MERC_LEVEL_MAX}, más caros cuanto más nivel. Se reponen {@link #MERC_PER_DAY} por día.</li>
 *   <li><b>Guardias</b> (y con ellos los mercenarios y los reclutas sueltos de cerca) que la cuidan: pelean con los bichos y bandidos que se acercan y con quien le pegue a alguien de
 *       la taberna (también a vos). Los que mueren vuelven de a {@link #GUARD_REFILL_PER_DAY} por día.</li>
 * </ul>
 * Las tabernas se anotan cuando un jugador pasa cerca ({@link #scan}); la gente es una copia de los datos: no se guarda
 * y se vuelve a armar al cargar ({@link #onLoad}).
 */
public final class WorldTaverns extends SavedData {
    /** Marca de la gente de una taberna del mundo (= id de la taberna). */
    public static final String TAG = "warbandcraft:world_tavern";
    /** Qué es: {@link #KEEPER}, {@link #GUARD} o {@link #MERC}. */
    public static final String TAG_ROLE = "warbandcraft:world_tavern_role";
    public static final String KEEPER = "keeper", GUARD = "guard", MERC = "merc";
    /** Las estructuras que son tabernas. */
    public static final TagKey<Structure> STRUCTURES = TagKey.create(Registries.STRUCTURE, new ResourceLocation(WarbandCraft.MODID, "world_taverns"));
    private static final String NAME = "warbandcraft_world_taverns";

    /** Radio (en chunks) alrededor de cada jugador donde se buscan tabernas nuevas. */
    public static int SCAN_CHUNKS = 6;
    /** La gente aparece con un jugador a esta distancia de la taberna. */
    public static int ACTIVE_RANGE = 96;
    /** Guardias de cada taberna. */
    public static int GUARDS = 5;
    /** Nivel de Recruits de los guardias. */
    public static int GUARD_LEVEL = 4;
    /** Los guardias pelean con lo que está a esta distancia de la taberna. */
    public static int GUARD_RANGE = 32;
    /** Guardias muertos que vuelven por día. */
    public static int GUARD_REFILL_PER_DAY = 2;
    /** Mercenarios para contratar: tope y cuántos llegan por día. */
    public static int MERC_MAX = 6;
    public static int MERC_PER_DAY = 2;
    /** Con cuántos arranca una taberna recién encontrada. */
    public static int MERC_START = 4;
    /** Nivel de Recruits de los mercenarios. */
    public static int MERC_LEVEL_MIN = 1, MERC_LEVEL_MAX = 4;
    /** Esmeraldas de más por cada nivel (encima del precio de Recruits). */
    public static int MERC_COST_PER_LEVEL = 3;
    /** Chances de cada tipo de mercenario (el resto, de a pie). */
    public static double MERC_BOWMAN = 0.25, MERC_CROSSBOW = 0.15, MERC_SHIELD = 0.25;
    /** Quien le pegó a alguien de la taberna hace menos de esto es enemigo de los guardias. */
    public static int GRUDGE_TICKS = 20 * 10;

    /** Una taberna: id = chunk donde empieza la estructura; {@code house} = la caja de la casa. */
    public static final class Inn {
        public final long id;
        public final BoundingBox house;
        public final String structure;
        public int guardsDown;
        public int mercs;
        public long day;
        public final Set<UUID> visitors = new HashSet<>();

        Inn(long id, BoundingBox house, String structure, int mercs, long day) {
            this.id = id;
            this.house = house;
            this.structure = structure;
            this.mercs = mercs;
            this.day = day;
        }

        public BlockPos center() {
            return new BlockPos((house.minX() + house.maxX()) / 2, house.minY(), (house.minZ() + house.maxZ()) / 2);
        }
    }

    private final Map<Long, Inn> inns = new LinkedHashMap<>();

    /** Lugares de adentro (cache): id → tiempo y lugares; el primero es el del Tabernero. */
    private record Spots(long at, List<BlockPos> inside, List<BlockPos> posts) {}
    private static final Map<Long, Spots> SPOTS = new HashMap<>();
    private static final Map<Long, Long> SCANNED = new HashMap<>();

    public static WorldTaverns get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(WorldTaverns::load, WorldTaverns::new, NAME);
    }

    public java.util.Collection<Inn> all() {
        return inns.values();
    }

    @Nullable
    public Inn byId(long id) {
        return inns.get(id);
    }

    public void changed() {
        setDirty();
    }

    public void remove(long id) {
        if (inns.remove(id) != null) setDirty();
        SPOTS.remove(id);
    }

    // ------------------------------------------------------------------ encontrarlas

    /** Cada 5 s por jugador: anota las tabernas cuyo comienzo está en un chunk cargado a {@link #SCAN_CHUNKS}. */
    public static void scan(ServerLevel level, ServerPlayer player) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        ChunkPos at = player.chunkPosition();
        Long last = SCANNED.get(at.toLong());
        if (last != null && level.getGameTime() - last < 20 * 60) return;   // ese lugar ya se miró hace poco
        SCANNED.put(at.toLong(), level.getGameTime());
        if (SCANNED.size() > 4096) SCANNED.clear();
        WorldTaverns data = get(level);
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (int dx = -SCAN_CHUNKS; dx <= SCAN_CHUNKS; dx++) {
            for (int dz = -SCAN_CHUNKS; dz <= SCAN_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(at.x + dx, at.z + dz);
                if (chunk == null) continue;
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    if (!e.getValue().isValid() || !reg.wrapAsHolder(e.getKey()).is(STRUCTURES)) continue;
                    long id = e.getValue().getChunkPos().toLong();
                    if (data.inns.containsKey(id)) continue;
                    ResourceLocation key = reg.getKey(e.getKey());
                    data.register(level, id, e.getValue(), key == null ? "?" : key.toString());
                }
            }
        }
    }

    /** Anota una taberna (la caja es la de su casa; si no se la reconoce, la pieza más grande). */
    public Inn register(ServerLevel level, long id, StructureStart start, String structure) {
        return register(level, id, houseBox(start), structure);
    }

    public Inn register(ServerLevel level, long id, BoundingBox house, String structure) {
        Inn inn = new Inn(id, house, structure, MERC_START, today(level));
        inns.put(id, inn);
        setDirty();
        WarbandCraft.LOGGER.info("Taberna del mundo ({}) en {}", structure, inn.center());
        return inn;
    }

    private static BoundingBox houseBox(StructureStart start) {
        StructurePiece best = null;
        long bestVol = -1;
        for (StructurePiece p : start.getPieces()) {
            if (p instanceof PoolElementStructurePiece pool && pool.getElement().toString().contains("tavern_house")) return p.getBoundingBox();
            BoundingBox b = p.getBoundingBox();
            long vol = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
            if (vol > bestVol) {
                bestVol = vol;
                best = p;
            }
        }
        return best == null ? start.getBoundingBox() : best.getBoundingBox();
    }

    // ------------------------------------------------------------------ la gente

    /** Cada 2 s: en cada taberna cargada con un jugador cerca, la gente que falta, los guardias y los contratados. */
    public static void tick(ServerLevel level) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        WorldTaverns data = get(level);
        long today = today(level);
        for (Inn inn : new ArrayList<>(data.inns.values())) {
            BlockPos c = inn.center();
            if (!level.isPositionEntityTicking(c)) continue;
            refill(inn, today, data);
            ServerPlayer near = nearestPlayer(level, c, ACTIVE_RANGE);
            if (near == null) continue;
            tickInn(level, data, inn);
        }
    }

    /** Una taberna: saludo a los que llegan, gente que falta, contratados, guardias. */
    public static void tickInn(ServerLevel level, WorldTaverns data, Inn inn) {
        Spots spots = spots(level, inn);
        if (spots.inside().isEmpty()) return;
        greet(level, data, inn);
        List<AbstractRecruitEntity> people = people(level, inn);
        int keepers = 0, guards = 0, mercs = 0;
        for (AbstractRecruitEntity r : people) {
            String role = r.getPersistentData().getString(TAG_ROLE);
            if (MERC.equals(role) && r.getIsOwned()) {
                hired(level, data, inn, r);
                continue;
            }
            switch (role) {
                case KEEPER -> keepers++;
                case GUARD -> guards++;
                case MERC -> mercs++;
                default -> { }
            }
        }
        RandomSource rnd = level.getRandom();
        if (keepers == 0) spawnKeeper(level, inn, spots.inside().get(0));
        for (int i = guards; i < GUARDS - inn.guardsDown; i++) spawnGuard(level, inn, spots, i);
        for (int i = mercs; i < inn.mercs && spots.inside().size() > 1; i++) {
            spawnMerc(level, inn, spots.inside().get(1 + rnd.nextInt(spots.inside().size() - 1)), rnd);
        }
        guard(level, inn, people);
    }

    /** Lo primero que ve un jugador que entra por primera vez. */
    private static void greet(ServerLevel level, WorldTaverns data, Inn inn) {
        AABB box = AABB.of(inn.house).inflate(4);
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, box)) {
            if (p.isSpectator() || !inn.visitors.add(p.getUUID())) continue;
            data.setDirty();
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.world_tavern.welcome").withStyle(ChatFormatting.GOLD));
        }
    }

    /** Un mercenario que contrataste con la pantalla de Recruits: deja de ser de la taberna. */
    private static void hired(ServerLevel level, WorldTaverns data, Inn inn, AbstractRecruitEntity r) {
        strip(r);
        inn.mercs = Math.max(0, inn.mercs - 1);
        data.setDirty();
        UUID owner = r.getOwnerUUID();
        if (owner != null) com.ale.warbandcraft.camp.Barracks.recount(level, owner);
    }

    /** Le saca todo lo de la taberna a un mercenario contratado. */
    private static void strip(AbstractRecruitEntity r) {
        r.getPersistentData().remove(TAG);
        r.getPersistentData().remove(TAG_ROLE);
        r.setInvulnerable(false);
        r.setAggroState(0);
        r.setCustomName(null);
        r.setPersistenceRequired();
    }

    private static void spawnKeeper(ServerLevel level, Inn inn, BlockPos spot) {
        InnkeeperEntity k = ModEntities.INNKEEPER.get().create(level);
        if (k == null) return;
        k.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
        try {
            k.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException ignored) {
            // como con los reclutas: si Workers falla acá, se sigue igual
        }
        k.setIsOwned(false);
        k.setWorkplace(spot);
        k.setInvulnerable(true);
        mark(k, inn, KEEPER);
        level.addFreshEntity(k);
    }

    private static void spawnGuard(ServerLevel level, Inn inn, Spots spots, int index) {
        List<BlockPos> posts = spots.posts().isEmpty() ? spots.inside() : spots.posts();
        BlockPos post = posts.get(index % posts.size());
        String type = index % 3 == 2 ? "crossbowman" : "recruit_shieldman";
        AbstractRecruitEntity g = create(level, type, GUARD_LEVEL);
        if (g == null) return;
        g.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
        g.setCustomName(Component.translatable("entity.warbandcraft.tavern_guard"));
        mark(g, inn, GUARD);
        g.setAggroState(0);
        g.setHoldPos(Vec3.atBottomCenterOf(post));
        g.setFollowState(3);
        level.addFreshEntity(g);
    }

    private static void spawnMerc(ServerLevel level, Inn inn, BlockPos spot, RandomSource r) {
        double roll = r.nextDouble();
        String type = roll < MERC_BOWMAN ? "bowman" : roll < MERC_BOWMAN + MERC_CROSSBOW ? "crossbowman"
                : roll < MERC_BOWMAN + MERC_CROSSBOW + MERC_SHIELD ? "recruit_shieldman" : "recruit";
        int lvl = MERC_LEVEL_MIN + r.nextInt(Math.max(1, MERC_LEVEL_MAX - MERC_LEVEL_MIN + 1));
        AbstractRecruitEntity m = create(level, type, lvl);
        if (m == null) return;
        m.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, r.nextFloat() * 360F, 0F);
        m.setCost(m.getCost() + MERC_COST_PER_LEVEL * lvl);
        m.setCustomName(Component.translatable("entity.warbandcraft.mercenary", lvl));
        m.setInvulnerable(true);
        mark(m, inn, MERC);
        m.setAggroState(3);
        m.setHoldPos(Vec3.atBottomCenterOf(spot));
        m.setFollowState(3);
        level.addFreshEntity(m);
    }

    private static void mark(AbstractRecruitEntity e, Inn inn, String role) {
        e.getPersistentData().putLong(TAG, inn.id);
        e.getPersistentData().putString(TAG_ROLE, role);
        e.despawnTimer = -1;
    }

    @Nullable
    private static AbstractRecruitEntity create(ServerLevel level, String type, int xpLevel) {
        EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", type));
        Entity e = t == null ? null : t.create(level);
        if (!(e instanceof AbstractRecruitEntity s)) {
            if (e != null) e.discard();
            return null;
        }
        try {
            s.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException ignored) {
            // como en las guarniciones: Recruits a veces falla acá y no importa
        }
        s.setIsOwned(false);
        if (xpLevel > 0) Tavern.levelUp(s, xpLevel);
        return s;
    }

    /** La gente de la taberna que está cargada (también la que salió a pelear). */
    public static List<AbstractRecruitEntity> people(ServerLevel level, Inn inn) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, AABB.of(inn.house).inflate(GUARD_RANGE + 24),
                e -> e.isAlive() && e.getPersistentData().contains(TAG) && e.getPersistentData().getLong(TAG) == inn.id);
    }

    // ------------------------------------------------------------------ los guardias

    /**
     * Pedido de Ale: todos defienden la taberna, no solo los guardias. Guardias, mercenarios que esperan y reclutas sueltos
     * que andan por ahí van contra el enemigo más cercano; los mercenarios se pueden lastimar mientras pelean y, sin enemigos,
     * vuelven a esperar (pasivos e invulnerables). Todos vuelven solos a su lugar ({@code setHoldPos}).
     */
    private static void guard(ServerLevel level, Inn inn, List<AbstractRecruitEntity> people) {
        List<AbstractRecruitEntity> defenders = new ArrayList<>();
        for (AbstractRecruitEntity r : people) {
            String role = r.getPersistentData().getString(TAG_ROLE);
            if (GUARD.equals(role) || (MERC.equals(role) && !r.getIsOwned())) defenders.add(r);
        }
        AABB area = AABB.of(inn.house).inflate(GUARD_RANGE, 8, GUARD_RANGE);
        defenders.addAll(level.getEntitiesOfClass(AbstractRecruitEntity.class, area, r -> !r.getPersistentData().contains(TAG) && Tavern.isHireable(r)));
        if (defenders.isEmpty()) return;
        List<LivingEntity> foes = enemies(level, inn, people);
        for (AbstractRecruitEntity d : defenders) {
            boolean merc = MERC.equals(d.getPersistentData().getString(TAG_ROLE));
            LivingEntity t = d.getTarget();
            if (t instanceof Creeper) {
                d.setTarget(null);
                t = null;
            }
            if (t != null && t.isAlive()) continue;
            LivingEntity best = null;
            double bestSq = Double.MAX_VALUE;
            for (LivingEntity f : foes) {
                double sq = f.distanceToSqr(d);
                if (sq < bestSq) {
                    bestSq = sq;
                    best = f;
                }
            }
            if (best != null) {
                if (merc) d.setInvulnerable(false);
                d.setAggroState(0);
                d.setTarget(best);
            } else if (merc && foes.isEmpty()) {
                d.setAggroState(3);
                d.setInvulnerable(true);
            }
        }
    }

    /** Bichos (no creepers ni jefes) y bandidos cerca de la taberna, y quien le pegó hace poco a alguien de ella. */
    public static List<LivingEntity> enemies(ServerLevel level, Inn inn, List<AbstractRecruitEntity> people) {
        int floor = inn.house.minY();
        Set<LivingEntity> out = new HashSet<>();
        AABB area = AABB.of(inn.house).inflate(GUARD_RANGE, 8, GUARD_RANGE);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (e.getPersistentData().contains(TAG)) continue;
            boolean foe = (e instanceof Enemy && !(e instanceof Creeper) && !Compat.isBoss(e)) || Bandits.isBandit(e);
            if (foe && e.getY() > floor - 6) out.add(e);
        }
        List<LivingEntity> victims = new ArrayList<>(people);
        victims.addAll(level.getEntitiesOfClass(Villager.class, area));
        for (LivingEntity v : victims) {
            LivingEntity by = v.getLastHurtByMob();
            if (by == null || !by.isAlive() || by.getPersistentData().contains(TAG)) continue;
            if (v.tickCount - v.getLastHurtByMobTimestamp() > GRUDGE_TICKS) continue;   // la marca es en ticks de la víctima
            if (by instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
            out.add(by);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ lugares

    /**
     * Lugares de adentro de la casa (piso firme, dos de aire y techo), del piso más bajo primero; el del Tabernero es el
     * más cerca del medio. Puestos de guardia: afuera, uno en cada costado de la casa, y dos adentro. Cache de 5 min.
     */
    private static Spots spots(ServerLevel level, Inn inn) {
        Spots s = SPOTS.get(inn.id);
        if (s != null && level.getGameTime() - s.at() < 20 * 60 * 5) return s;
        BoundingBox b = inn.house;
        List<BlockPos> inside = new ArrayList<>();
        for (int y = b.minY(); y <= b.maxY() - 1 && inside.size() < 8; y++) {
            inside.clear();
            for (int x = b.minX() + 1; x < b.maxX(); x++) {
                for (int z = b.minZ() + 1; z < b.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (com.ale.warbandcraft.util.Ground.canStand(level, p) && !level.canSeeSky(p)) inside.add(p);
                }
            }
        }
        BlockPos mid = new BlockPos((b.minX() + b.maxX()) / 2, inside.isEmpty() ? b.minY() : inside.get(0).getY(), (b.minZ() + b.maxZ()) / 2);
        inside.sort(Comparator.comparingDouble(p -> p.distSqr(mid)));
        List<BlockPos> posts = new ArrayList<>();
        int[][] sides = {{(b.minX() + b.maxX()) / 2, b.minZ() - 3}, {(b.minX() + b.maxX()) / 2, b.maxZ() + 3},
                {b.minX() - 3, (b.minZ() + b.maxZ()) / 2}, {b.maxX() + 3, (b.minZ() + b.maxZ()) / 2}};
        for (int[] xz : sides) {
            BlockPos g = new BlockPos(xz[0], level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xz[0], xz[1]), xz[1]);
            if (Math.abs(g.getY() - b.minY()) <= 6 && level.getFluidState(g).isEmpty() && com.ale.warbandcraft.util.Ground.canStand(level, g)) posts.add(g);
        }
        if (inside.size() > 4) {
            posts.add(inside.get(inside.size() - 1));
            posts.add(inside.get(inside.size() - 2));
        }
        s = new Spots(level.getGameTime(), inside, posts);
        SPOTS.put(inn.id, s);
        return s;
    }

    // ------------------------------------------------------------------ días, muertes, guardado

    private static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** Días nuevos: vuelven guardias y llegan mercenarios (máx. 7 días atrasados). */
    private static void refill(Inn inn, long today, WorldTaverns data) {
        if (inn.day >= today) return;
        long days = Math.min(7, today - inn.day);
        inn.day = today;
        inn.guardsDown = (int) Math.max(0, inn.guardsDown - days * GUARD_REFILL_PER_DAY);
        inn.mercs = (int) Math.min(MERC_MAX, inn.mercs + days * MERC_PER_DAY);
        data.setDirty();
    }

    /** Murió alguien de una taberna: un guardia tarda en volver; un mercenario, se perdió. */
    public static void onDeath(ServerLevel level, LivingEntity e) {
        if (!e.getPersistentData().contains(TAG)) return;
        WorldTaverns data = get(level);
        Inn inn = data.byId(e.getPersistentData().getLong(TAG));
        if (inn == null) return;
        String role = e.getPersistentData().getString(TAG_ROLE);
        if (GUARD.equals(role)) inn.guardsDown = Math.min(GUARDS, inn.guardsDown + 1);
        else if (MERC.equals(role)) inn.mercs = Math.max(0, inn.mercs - 1);
        data.setDirty();
    }

    /** Al cargarse del disco: la gente de la taberna no vuelve (true = cancelar); un mercenario ya contratado, sí. */
    public static boolean onLoad(Entity e) {
        if (!e.getPersistentData().contains(TAG)) return false;
        if (e instanceof AbstractRecruitEntity r && MERC.equals(e.getPersistentData().getString(TAG_ROLE)) && r.getIsOwned()) {
            if (e.level() instanceof ServerLevel level) {
                Inn inn = get(level).byId(e.getPersistentData().getLong(TAG));
                if (inn != null) {
                    inn.mercs = Math.max(0, inn.mercs - 1);
                    get(level).setDirty();
                }
            }
            strip(r);
            return false;
        }
        return true;
    }

    public static boolean isTavernPerson(Entity e) {
        return e.getPersistentData().contains(TAG);
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos c, int range) {
        ServerPlayer best = null;
        double bestSq = (double) range * range;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double sq = p.distanceToSqr(Vec3.atCenterOf(c));
            if (sq < bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Inn inn : inns.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Id", inn.id);
            t.putIntArray("House", new int[]{inn.house.minX(), inn.house.minY(), inn.house.minZ(), inn.house.maxX(), inn.house.maxY(), inn.house.maxZ()});
            t.putString("Structure", inn.structure);
            t.putInt("GuardsDown", inn.guardsDown);
            t.putInt("Mercs", inn.mercs);
            t.putLong("Day", inn.day);
            ListTag visitors = new ListTag();
            for (UUID u : inn.visitors) visitors.add(net.minecraft.nbt.NbtUtils.createUUID(u));
            t.put("Visitors", visitors);
            list.add(t);
        }
        tag.put("Inns", list);
        return tag;
    }

    private static WorldTaverns load(CompoundTag tag) {
        WorldTaverns data = new WorldTaverns();
        for (Tag raw : tag.getList("Inns", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            int[] h = t.getIntArray("House");
            if (h.length != 6) continue;
            Inn inn = new Inn(t.getLong("Id"), new BoundingBox(h[0], h[1], h[2], h[3], h[4], h[5]), t.getString("Structure"), t.getInt("Mercs"), t.getLong("Day"));
            inn.guardsDown = t.getInt("GuardsDown");
            for (Tag u : t.getList("Visitors", Tag.TAG_INT_ARRAY)) inn.visitors.add(net.minecraft.nbt.NbtUtils.loadUUID(u));
            data.inns.put(inn.id, inn);
        }
        return data;
    }

    /** Para las pruebas: olvida los lugares medidos. */
    public static void forgetSpots(long id) {
        SPOTS.remove(id);
    }
}
