package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * H25: nuestras mazmorras (guaridas de bandidos y túmulos nórdicos). Aparecen como los castillos de H4: cada 5 s se tira
 * un dado por cada zona nueva que explora un jugador. Se arman con {@link DungeonBuilder}; la gente de adentro la pone
 * {@link #populate}.
 * <ul>
 *   <li>Guarida: soldados de la facción {@code warbandcraft:bandidos} ({@code outlaw}: en guerra con todos), marcados como
 *       guarnición con centro = el medio de la guarida, más el Jefe de la banda y los presos (se liberan con click).
 *       Limpia = todos los bandidos muertos. Vuelve a llenarse a los {@link #REFILL_DAYS} días si no hay nadie cerca.</li>
 *   <li>Túmulo: draugr ({@link Barrows}); limpio = el Señor del Túmulo muerto. No vuelve a llenarse.</li>
 * </ul>
 * Entrás solo o con los soldados que quieras.
 */
public final class Dungeons {
    /** En cada habitante: el id de su mazmorra. */
    public static final String TAG_DUNGEON = "warbandcraft:dungeon";
    /** En un preso: el id de su guarida. */
    public static final String TAG_CAPTIVE = "warbandcraft:captive";
    public static final String TAG_CHIEF = "warbandcraft:bandit_chief";
    public static final ResourceLocation BANDITS = new ResourceLocation(WarbandCraft.MODID, "bandidos");

    /** Cuánto ocupa una mazmorra alrededor de su entrada/medio (para separar castillos, campos y obras). */
    public static int FOOTPRINT = 48;
    /** Como los castillos: no se vuelve a tirar el dado cerca de una zona ya explorada. */
    public static int ZONE_SPACING = 160;
    /**
     * Chance de que una zona nueva tenga una mazmorra, según el bioma donde estás (pedido de Ale: muchas donde no se
     * puede pelear una hueste, casi nada en lo llano). Biomas: las etiquetas de {@link StructureDensity}.
     */
    public static double ZONE_CHANCE_DENSE = 0.7;
    public static double ZONE_CHANCE_OTHER = 0.35;
    public static double ZONE_CHANCE_SPARSE = 0.08;
    /** A qué distancia del jugador puede caer. */
    public static int CANDIDATE_MIN_DIST = 64;
    public static int CANDIDATE_MAX_DIST = 180;
    /** Ni dos mazmorras ni una mazmorra y una aldea/castillo tan cerca. */
    public static int MIN_DUNGEON_DISTANCE = 200;
    public static int VILLAGE_DISTANCE = 112;
    /** Chance de túmulo (si no, guarida) en taiga, nieve y montañas; en el resto. */
    public static double BARROW_CHANCE_NORTH = 0.7;
    public static double BARROW_CHANCE_ELSEWHERE = 0.2;
    /** Una guarida limpia vuelve a llenarse a los N días (si no hay nadie a {@link #REFILL_SAFE} bloques). */
    public static int REFILL_DAYS = 5;
    public static int REFILL_SAFE = 48;
    /** Con un jugador a esta distancia se miran los ataúdes, el jefe, si quedó limpia... */
    public static int ACTIVE_RANGE = 80;
    /** Presos por guarida. */
    public static int CAPTIVES_MIN = 2;
    public static int CAPTIVES_MAX = 4;
    /** Si no se ve a nadie de adentro durante N segundos (con todo cargado), la mazmorra se da por limpia. */
    public static int LOST_SECONDS = 15;

    private static final String[] LAIR_NAMES = {"los Cuervos", "la Garra Roja", "los Sin Nombre", "el Tuerto", "los Lobos Grises",
            "la Cuchilla", "los Desollados", "la Hiena", "los Sin Tierra", "el Barbanegra"};
    private static final String[] BARROW_NAMES = {"Valdgard", "Hrolfstad", "Skjoldhaug", "Eldhjem", "Ormhaug", "Kaldvik", "Grimsfell",
            "Ulfheim", "Svartmyr", "Isvald"};

    /** Segundos seguidos sin ver a nadie de adentro (en memoria). */
    private static final Map<Integer, Integer> LOST = new HashMap<>();

    private Dungeons() {}

    // ------------------------------------------------------------------ aparecer

    /** Tira el dado por una mazmorra nueva cerca de {@code around} (cada 5 s por jugador, junto a los castillos). */
    public static void scan(ServerLevel level, BlockPos around) {
        if (level.dimension() != Level.OVERWORLD) return;
        DungeonData data = DungeonData.get(level);
        if (data.nearestAttempt(around, ZONE_SPACING) != null) return;
        data.addAttempt(around);
        RandomSource random = level.getRandom();
        Holder<Biome> biome = level.getBiome(around);
        double chance = biome.is(StructureDensity.DENSE) ? ZONE_CHANCE_DENSE
                : biome.is(StructureDensity.SPARSE) ? ZONE_CHANCE_SPARSE : ZONE_CHANCE_OTHER;
        if (random.nextDouble() >= chance) return;
        try {
            place(level, around, CANDIDATE_MIN_DIST, CANDIDATE_MAX_DIST, null, random);
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("No se pudo armar una mazmorra cerca de {}", around.toShortString(), e);
        }
    }

    /**
     * Busca lugar a {@code minDist}-{@code maxDist} de {@code around} (8 intentos) y arma una mazmorra ({@code kind} null =
     * según el bioma). Devuelve null si no encontró lugar.
     */
    @Nullable
    public static DungeonData.Dungeon place(ServerLevel level, BlockPos around, int minDist, int maxDist,
                                            @Nullable DungeonData.Kind kind, RandomSource random) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int dist = minDist + random.nextInt(Math.max(1, maxDist - minDist + 1));
            int x = around.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = around.getZ() + (int) Math.round(Math.sin(angle) * dist);
            DungeonData.Dungeon d = tryAt(level, new BlockPos(x, 0, z), Direction.Plane.HORIZONTAL.getRandomDirection(random), kind, random);
            if (d != null) return d;
        }
        return null;
    }

    /** Arma una mazmorra con la boca en la columna {@code at} mirando a {@code dir}, si el lugar sirve. */
    @Nullable
    public static DungeonData.Dungeon tryAt(ServerLevel level, BlockPos at, Direction dir, @Nullable DungeonData.Kind kind, RandomSource random) {
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos entrance = new BlockPos(at.getX(), y, at.getZ());
        DungeonData data = DungeonData.get(level);
        BlockPos mid = entrance.relative(dir, 40);
        if (data.near(mid, MIN_DUNGEON_DISTANCE) != null) return null;
        if (VillageData.get(level).nearest(mid, VILLAGE_DISTANCE) != null) return null;
        if (CastleManager.hasCastleNear(level, mid, VILLAGE_DISTANCE)) return null;
        Holder<Biome> biome = level.getBiome(entrance);
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.SWAMP)
                || biome.is(Biomes.MANGROVE_SWAMP)) return null;
        if (!DungeonBuilder.siteOk(level, entrance, dir)) return null;
        // Nada de otros mods arriba (la boca) ni abajo (las salas)
        if (Compat.surfaceStructureIn(level, entrance.getX() - 10, entrance.getZ() - 10, entrance.getX() + 10, entrance.getZ() + 10)) return null;
        for (int u = 10; u <= 70; u += 10) {
            BlockPos p = entrance.relative(dir, u);
            for (int dy = -24; dy <= -8; dy += 8) if (Compat.foreignStructureAt(level, p.above(dy))) return null;
        }
        if (kind == null) {
            boolean north = biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(Biomes.SNOWY_PLAINS)
                    || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.SNOWY_SLOPES) || biome.is(Biomes.FROZEN_PEAKS);
            kind = random.nextDouble() < (north ? BARROW_CHANCE_NORTH : BARROW_CHANCE_ELSEWHERE) ? DungeonData.Kind.BARROW : DungeonData.Kind.LAIR;
        }
        DungeonData.Dungeon d = DungeonBuilder.build(level, kind, entrance, dir, data.newId(), random);
        d.name = (kind == DungeonData.Kind.BARROW ? "Túmulo de " + BARROW_NAMES[random.nextInt(BARROW_NAMES.length)]
                : "Guarida de " + LAIR_NAMES[random.nextInt(LAIR_NAMES.length)]);
        data.add(d);
        populate(level, d, random);
        return d;
    }

    // ------------------------------------------------------------------ la gente de adentro

    /** Pone a los de adentro: bandidos + jefe + presos, o los draugr de pie + el guardián de la Garra. */
    public static void populate(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        if (d.kind == DungeonData.Kind.LAIR) populateLair(level, d, random);
        else populateBarrow(level, d, random);
        d.populated = true;
        DungeonData.get(level).changed();
    }

    private static void populateLair(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        Faction bandits = FactionManager.INSTANCE.get(BANDITS);
        int spawned = 0;
        if (bandits != null && !d.spots.isEmpty()) {
            int count = bandits.garrisonMin() + random.nextInt(bandits.garrisonMax() - bandits.garrisonMin() + 1);
            for (int i = 0; i < count; i++) {
                Troop troop = bandits.pickTroop(random);
                if (troop == null) break;
                AbstractRecruitEntity s = VillageManager.spawnTroop(level, d.spots.get(i % d.spots.size()), bandits, troop, random, 1);
                if (s == null) continue;
                bandit(s, d);
                spawned++;
            }
            if (d.throne != null) {
                AbstractRecruitEntity chief = VillageManager.spawnFixed(level, d.throne, bandits, new ResourceLocation("recruits", "recruit"),
                        CastleManager.captainEquipment(), List.of(), false, "Jefe de la banda", random, 1, 32);
                if (chief != null) {
                    bandit(chief, d);
                    chief.getPersistentData().putBoolean(TAG_CHIEF, true);
                    chief.setItemSlot(EquipmentSlot.OFFHAND, bandits.banner().copy());
                    var hp = chief.getAttribute(Attributes.MAX_HEALTH);
                    if (hp != null) {
                        hp.setBaseValue(hp.getBaseValue() * 2);
                        chief.setHealth(chief.getMaxHealth());
                    }
                    spawned++;
                }
            }
        }
        d.remaining = spawned;
        int captives = CAPTIVES_MIN + random.nextInt(CAPTIVES_MAX - CAPTIVES_MIN + 1);
        d.captives = 0;
        for (int i = 0; i < captives && i < d.cells.size(); i++) if (captive(level, d, d.cells.get(i), random) != null) d.captives++;
        if (spawned == 0) d.clearedTick = level.getGameTime();   // sin facción de bandidos: no queda trabada
    }

    /** Marca a un bandido como de la guarida: patrulla desde el medio de ella. */
    private static void bandit(AbstractRecruitEntity s, DungeonData.Dungeon d) {
        s.getPersistentData().putInt(TAG_DUNGEON, d.id);
        s.getPersistentData().putLong(VillageManager.TAG_VILLAGE, d.center.asLong());
        s.restrictTo(d.center, VillageManager.PATROL_RADIUS);
    }

    /** Un preso: recluta de Recruits sin dueño, quieto, pasivo e invulnerable, en la celda. */
    @Nullable
    private static AbstractRecruitEntity captive(ServerLevel level, DungeonData.Dungeon d, BlockPos at, RandomSource random) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        Entity e = type == null ? null : type.create(level);
        if (!(e instanceof AbstractRecruitEntity r)) {
            if (e != null) e.discard();
            return null;
        }
        r.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360F, 0F);
        for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
        r.getInventory().clearContent();
        r.setIsOwned(false);
        r.despawnTimer = -1;
        r.setPersistenceRequired();
        r.setInvulnerable(true);
        r.setAggroState(3);
        r.setFollowState(2);
        r.setCustomName(Component.translatable("entity.warbandcraft.captive").withStyle(ChatFormatting.YELLOW));
        r.getPersistentData().putInt(TAG_CAPTIVE, d.id);
        r.restrictTo(at, 2);
        level.addFreshEntity(r);
        return r;
    }

    private static void populateBarrow(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        for (int i = 0; i < d.spots.size(); i++) {
            boolean last = i == d.spots.size() - 1;
            Barrows.draugr(level, d.spots.get(i), last ? Barrows.Type.GUARDIAN : Barrows.Type.WARRIOR, d, random);
        }
        d.remaining = 1;   // el Señor del Túmulo
    }

    /** Click a un preso: se une a vos gratis y te sigue. */
    public static Component free(ServerPlayer player, AbstractRecruitEntity r) {
        ServerLevel level = player.serverLevel();
        DungeonData data = DungeonData.get(level);
        DungeonData.Dungeon d = data.byId(r.getPersistentData().getInt(TAG_CAPTIVE));
        r.getPersistentData().remove(TAG_CAPTIVE);
        r.setInvulnerable(false);
        r.clearRestriction();
        r.setCustomName(null);
        r.setOwnerUUID(Optional.of(player.getUUID()));
        r.setIsOwned(true);
        r.setAggroState(0);
        r.setFollowState(1);
        r.updateTeam();
        Barracks.recount(level, player.getUUID());
        if (d != null) {
            d.captives = Math.max(0, d.captives - 1);
            data.changed();
        }
        level.playSound(null, r.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.NEUTRAL, 1F, 1F);
        return Component.translatable("msg.warbandcraft.dungeon.freed").withStyle(ChatFormatting.GREEN);
    }

    public static boolean isCaptive(Entity e) {
        return e instanceof AbstractRecruitEntity && e.getPersistentData().contains(TAG_CAPTIVE);
    }

    // ------------------------------------------------------------------ muertes y limpieza

    /** Murió alguien de una mazmorra: cuenta para dejarla limpia. */
    public static void onDeath(ServerLevel level, LivingEntity dead, @Nullable Entity killer) {
        if (!dead.getPersistentData().contains(TAG_DUNGEON)) return;
        DungeonData data = DungeonData.get(level);
        DungeonData.Dungeon d = data.byId(dead.getPersistentData().getInt(TAG_DUNGEON));
        if (d == null || d.cleared()) return;
        boolean counts = d.kind == DungeonData.Kind.LAIR ? VillageManager.isGarrison(dead) : dead.getPersistentData().getBoolean(Barrows.TAG_LORD);
        if (!counts) return;
        if (d.kind == DungeonData.Kind.BARROW) d.bossDead = true;
        d.remaining = Math.max(0, d.remaining - 1);
        data.changed();
        if (d.remaining == 0) clear(level, d);
        else if (d.kind == DungeonData.Kind.LAIR && dead.getPersistentData().getBoolean(TAG_CHIEF)) {
            for (ServerPlayer p : playersNear(level, d, ACTIVE_RANGE)) {
                p.displayClientMessage(Component.translatable("msg.warbandcraft.dungeon.chief_dead", d.remaining).withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    /** La mazmorra quedó limpia: lo robado al cofre del jefe, contratos pagados, aviso. */
    public static void clear(ServerLevel level, DungeonData.Dungeon d) {
        DungeonData data = DungeonData.get(level);
        d.clearedTick = level.getGameTime();
        d.refillTick = d.kind == DungeonData.Kind.LAIR ? d.clearedTick + REFILL_DAYS * 24000L : -1;
        LOST.remove(d.id);
        if (!d.stolen.isEmpty() && d.treasure != null && level.isLoaded(d.treasure)) {
            if (level.getBlockEntity(d.treasure) instanceof ChestBlockEntity chest) {
                for (ItemStack s : d.stolen) {
                    ItemStack left = s.copy();
                    for (int i = 0; i < chest.getContainerSize() && !left.isEmpty(); i++) {
                        if (chest.getItem(i).isEmpty()) {
                            chest.setItem(i, left);
                            left = ItemStack.EMPTY;
                        }
                    }
                    if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, d.treasure.getX(), d.treasure.getY() + 1, d.treasure.getZ(), left);
                }
                d.stolen.clear();
            }
        }
        for (ServerPlayer p : playersNear(level, d, ACTIVE_RANGE * 2)) {
            p.sendSystemMessage(Component.translatable(d.kind == DungeonData.Kind.LAIR ? "msg.warbandcraft.dungeon.lair_cleared"
                    : "msg.warbandcraft.dungeon.barrow_cleared", d.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            if (d.captives > 0) p.sendSystemMessage(Component.translatable("msg.warbandcraft.dungeon.captives", d.captives).withStyle(ChatFormatting.YELLOW));
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1F);
        }
        Rumors.onCleared(level, d);
        d.known.clear();
        data.changed();
        WarbandCraft.LOGGER.info("{} ({}) quedó limpia", d.name, d.id);
    }

    // ------------------------------------------------------------------ cada segundo

    public static void tick(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        DungeonData data = DungeonData.get(level);
        long now = level.getGameTime();
        for (DungeonData.Dungeon d : new ArrayList<>(data.all())) {
            List<ServerPlayer> players = playersNear(level, d, ACTIVE_RANGE);
            if (players.isEmpty()) continue;
            for (ServerPlayer p : players) {
                if (d.inside(p.blockPosition(), 0) && !d.cleared() && d.known.add(p.getUUID())) {
                    data.changed();
                    p.displayClientMessage(Component.literal(d.name).withStyle(d.kind == DungeonData.Kind.BARROW ? ChatFormatting.DARK_AQUA
                            : ChatFormatting.GRAY, ChatFormatting.BOLD), true);
                }
            }
            if (d.kind == DungeonData.Kind.BARROW && !d.cleared()) {
                List<LivingEntity> near = new ArrayList<>();
                AABB box = box(d).inflate(2);
                for (ServerPlayer p : players) if (!p.isSpectator() && box.contains(p.position())) near.add(p);
                near.addAll(level.getEntitiesOfClass(AbstractRecruitEntity.class, box, r -> r.isAlive() && r.getIsOwned()));
                Barrows.tick(level, d, near, level.getRandom());
            }
            if (d.cleared() && d.kind == DungeonData.Kind.LAIR && d.refillTick >= 0 && now >= d.refillTick
                    && playersNear(level, d, REFILL_SAFE).isEmpty() && level.isPositionEntityTicking(d.center)) {
                d.clearedTick = -1;
                d.refillTick = -1;
                populate(level, d, level.getRandom());
                WarbandCraft.LOGGER.info("{} se volvió a llenar de bandidos", d.name);
            }
            checkLost(level, d);
        }
        Rumors.tickContracts(level);
    }

    /** Si con todo cargado no queda nadie de los que cuentan (se fueron, /kill...), la mazmorra se da por limpia. */
    private static void checkLost(ServerLevel level, DungeonData.Dungeon d) {
        if (d.cleared() || !d.populated) return;
        if (d.kind == DungeonData.Kind.BARROW && !d.bossAwake) return;
        BlockPos[] corners = {new BlockPos(d.box[0], d.center.getY(), d.box[2]), new BlockPos(d.box[3], d.center.getY(), d.box[5]), d.center};
        for (BlockPos c : corners) if (!level.isPositionEntityTicking(c)) return;
        boolean any = !level.getEntitiesOfClass(Mob.class, box(d).inflate(16), m -> m.isAlive() && m.getPersistentData().getInt(TAG_DUNGEON) == d.id
                && (d.kind == DungeonData.Kind.LAIR ? VillageManager.isGarrison(m) : m.getPersistentData().getBoolean(Barrows.TAG_LORD))).isEmpty();
        if (any) {
            LOST.remove(d.id);
            return;
        }
        int n = LOST.merge(d.id, 1, Integer::sum);
        if (n < LOST_SECONDS) return;
        LOST.remove(d.id);
        if (d.kind == DungeonData.Kind.BARROW) {   // el Señor se perdió (no murió): que pueda volver a despertar
            d.bossAwake = false;
            DungeonData.get(level).changed();
            return;
        }
        clear(level, d);
    }

    public static AABB box(DungeonData.Dungeon d) {
        return new AABB(d.box[0], d.box[1], d.box[2], d.box[3] + 1, d.box[4] + 1, d.box[5] + 1);
    }

    static List<ServerPlayer> playersNear(ServerLevel level, DungeonData.Dungeon d, int range) {
        return level.getPlayers(p -> !p.isSpectator() && VillageData.horizontalDistSq(p.blockPosition(), d.center) <= (double) range * range);
    }

    /** Un bandido de una guarida fue atacado: los de su guarida que estén sin pelea van contra el atacante. */
    public static void onBanditHurt(ServerLevel level, AbstractRecruitEntity bandit, LivingEntity attacker) {
        int id = bandit.getPersistentData().getInt(TAG_DUNGEON);
        DungeonData.Dungeon d = DungeonData.get(level).byId(id);
        if (d == null) return;
        for (AbstractRecruitEntity mate : level.getEntitiesOfClass(AbstractRecruitEntity.class, box(d).inflate(8),
                m -> m != bandit && m.isAlive() && m.getPersistentData().getInt(TAG_DUNGEON) == id && VillageManager.isGarrison(m))) {
            LivingEntity t = mate.getTarget();
            if (t == null || !t.isAlive()) mate.setTarget(attacker);
        }
    }

    /** Para mensajes: "(x, z)". */
    public static String where(BlockPos p) {
        return "(" + p.getX() + ", " + p.getZ() + ")";
    }

    /** Recompensa de un contrato: al inventario del jugador, o al Campamento más cercano si no está conectado. */
    static void pay(ServerLevel level, UUID player, int emeralds, BlockPos near) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = emeralds; left > 0; left -= 64) stacks.add(new ItemStack(Items.EMERALD, Math.min(64, left)));
        if (p != null) {
            for (ItemStack s : stacks) if (!p.getInventory().add(s)) p.drop(s, false);
        } else {
            CampRegistry.get(level).deliver(level, player, stacks, near);
        }
    }

    static void opinion(ServerLevel level, UUID player, ResourceLocation faction, int delta) {
        Relations.get(level.getServer()).add(player, faction, delta);
    }
}
