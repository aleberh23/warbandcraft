package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Las aldeas de las facciones NPC.
 *
 * Cómo funciona: cada tanto se buscan campanas de aldea (el punto de reunión de los aldeanos) cerca de los
 * jugadores. Una campana nueva es una aldea nueva: se le asigna la facción de su bioma y aparece su
 * guarnición, que vive y patrulla dentro de la aldea. Todo queda guardado con el mundo.
 */
public final class VillageManager {
    /** Datos guardados en cada soldado de guarnición. */
    public static final String TAG_FACTION = "warbandcraft:faction";
    public static final String TAG_VILLAGE = "warbandcraft:village";
    /** En el soldado de guarnición: la clase y el nivel de kit de su tropa (H9, para el prisionero reclutado). */
    public static final String TAG_TROOP_KIT = "warbandcraft:troop_kit";
    public static final String TAG_TROOP_TIER = "warbandcraft:troop_tier";

    /** Radio alrededor del jugador en que se buscan campanas. */
    public static int SCAN_RADIUS = 96;
    /** Dos campanas a menos de esta distancia son la misma aldea. */
    public static int MERGE_RADIUS = 48;
    /** Hasta dónde se aleja la guarnición de la campana. */
    public static int PATROL_RADIUS = 32;
    /** Desde qué distancia se considera que el jugador está "en" la aldea. */
    public static int ENTER_RADIUS = 56;

    private VillageManager() {}

    /**
     * Busca campanas cerca de {@code around} y registra las aldeas nuevas (con su guarnición).
     * Devuelve las aldeas que se agregaron.
     */
    public static List<VillageData.Village> scan(ServerLevel level, BlockPos around, int radius, VillageData data) {
        return scan(level, around, radius, data, FactionManager.INSTANCE::forBiome);
    }

    /** Igual, pero eligiendo la facción con {@code factionFor} (las pruebas lo usan para no depender del bioma). */
    public static List<VillageData.Village> scan(ServerLevel level, BlockPos around, int radius, VillageData data,
                                                 Function<Holder<Biome>, Faction> factionFor) {
        List<VillageData.Village> added = new ArrayList<>();
        List<BlockPos> bells = level.getPoiManager()
                .findAll(type -> type.is(PoiTypes.MEETING), pos -> true, around, radius, PoiManager.Occupancy.ANY)
                .map(BlockPos::immutable)
                .toList();
        for (BlockPos bell : bells) {
            if (!level.isPositionEntityTicking(bell)) continue;      // la aldea todavía no está cargada del todo
            if (data.nearest(bell, MERGE_RADIUS) != null) continue;   // ya conocida
            if (com.ale.warbandcraft.dungeon.Compat.foreignStructureAt(level, bell)) continue;   // H25: campana de una mazmorra, no aldea
            Faction faction = factionFor.apply(level.getBiome(bell));
            if (faction == null) continue;                            // bioma que no reclama ninguna facción

            VillageData.Village village = new VillageData.Village(bell, faction.id(), level.getGameTime());
            data.add(village);
            List<AbstractRecruitEntity> garrison = spawnGarrison(level, bell, faction, level.getRandom(), 10);
            WarbandCraft.LOGGER.info("Aldea en {} → {} (guarnición: {})", bell.toShortString(), faction.name(), garrison.size());
            added.add(village);
        }
        return added;
    }

    /** Hace aparecer la guarnición de una aldea: entre min y max soldados de la facción, alrededor de {@code center}. */
    public static List<AbstractRecruitEntity> spawnGarrison(ServerLevel level, BlockPos center, Faction faction,
                                                            RandomSource random, int spread) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        int count = faction.garrisonMin() + random.nextInt(faction.garrisonMax() - faction.garrisonMin() + 1);
        for (int i = 0; i < count; i++) {
            Troop troop = faction.pickTroop(random);
            if (troop == null) break;
            AbstractRecruitEntity soldier = spawnTroop(level, center, faction, troop, random, spread);
            if (soldier != null) out.add(soldier);
        }
        return out;
    }

    @Nullable
    public static AbstractRecruitEntity spawnTroop(ServerLevel level, BlockPos center, Faction faction, Troop troop,
                                                   RandomSource random, int spread) {
        return spawnTroop(level, center, faction, troop, random, spread, PATROL_RADIUS);
    }

    /** Igual, pero con un radio de patrulla propio (los castillos de H4 son más grandes que una aldea). */
    @Nullable
    public static AbstractRecruitEntity spawnTroop(ServerLevel level, BlockPos center, Faction faction, Troop troop,
                                                   RandomSource random, int spread, int patrolRadius) {
        Troop.Resolved t = troop.resolve();
        if (t == null) return null;
        AbstractRecruitEntity soldier = spawnResolved(level, center, faction, t.entity(), t.equipment(), t.inventory(), t.horse(),
                troop.name() + " " + faction.soldierSuffix(), random, spread, patrolRadius);
        // H9: si cae prisionero y lo reclutás, sigue siendo de su clase y nivel
        if (soldier != null && troop.kit() != null) {
            soldier.getPersistentData().putString(TAG_TROOP_KIT, troop.kit().toString());
            soldier.getPersistentData().putInt(TAG_TROOP_TIER, troop.tier());
        }
        return soldier;
    }

    /** Hace aparecer un soldado "a medida" (equipo fijo, no viene de una {@link Troop}): lo usa el capitán de H4. */
    @Nullable
    public static AbstractRecruitEntity spawnFixed(ServerLevel level, BlockPos center, Faction faction, ResourceLocation entity,
                                            Map<EquipmentSlot, Item> equipment, List<ItemStack> inventory, boolean horse,
                                            String name, RandomSource random, int spread, int patrolRadius) {
        return spawnResolved(level, center, faction, entity, equipment, inventory, horse, name, random, spread, patrolRadius);
    }

    @Nullable
    private static AbstractRecruitEntity spawnResolved(ServerLevel level, BlockPos center, Faction faction,
                                                       ResourceLocation entityId, Map<EquipmentSlot, Item> equipment,
                                                       List<ItemStack> inventory, boolean horse, String name,
                                                       RandomSource random, int spread, int patrolRadius) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entityId);
        if (type == null) return null;
        Entity created = type.create(level);
        if (!(created instanceof AbstractRecruitEntity soldier)) {
            if (created != null) created.discard();
            WarbandCraft.LOGGER.warn("Facción {}: {} no es un soldado de Recruits", faction.id(), entityId);
            return null;
        }

        BlockPos pos = findSpawnPos(level, center, random, spread);
        soldier.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360F, 0F);
        try {
            soldier.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
        } catch (ClassCastException e) {
            // Recruits 1.15.2: el Zapador (y el Explorador) asumen una navegación que no es la suya y fallan acá.
            // No pasa nada: el equipo lo ponemos nosotros abajo.
            WarbandCraft.LOGGER.debug("finalizeSpawn de {} falló (bug de Recruits), se sigue igual", entityId);
        }

        // Lo que Recruits le haya puesto al aparecer se reemplaza por el equipo de la facción
        for (EquipmentSlot slot : EquipmentSlot.values()) soldier.setItemSlot(slot, ItemStack.EMPTY);
        soldier.getInventory().clearContent();
        for (Map.Entry<EquipmentSlot, Item> e : equipment.entrySet()) {
            soldier.setItemSlot(e.getKey(), faction.style(new ItemStack(e.getValue())));
        }
        for (ItemStack stack : inventory) {
            ClassAssigner.putInBackpack(soldier.getInventory(), stack.copy());
        }

        // Sin dueño (no responde a ningún jugador), no desaparece, no se puede contratar (ver VillageEvents)
        soldier.setIsOwned(false);
        soldier.despawnTimer = -1;
        soldier.setPersistenceRequired();
        soldier.setCustomName(Component.literal(name).withStyle(faction.color()));
        soldier.getPersistentData().putString(TAG_FACTION, faction.id().toString());
        soldier.getPersistentData().putLong(TAG_VILLAGE, center.asLong());
        soldier.restrictTo(center, patrolRadius);
        GarrisonDefense.install(soldier);

        level.addFreshEntityWithPassengers(soldier);
        joinTeam(level, soldier, faction);
        if (horse) mountOnHorse(level, soldier, random, patrolRadius);
        return soldier;
    }

    /**
     * H9: un soldado tuyo capturado (rendido en un ataque que perdiste) pasa a la guarnición de {@code village}:
     * sin dueño, con las marcas de la facción, sus colores y su nivel y equipo de siempre.
     */
    public static void joinGarrison(ServerLevel level, AbstractRecruitEntity soldier, VillageData.Village village, Faction faction) {
        java.util.UUID former = soldier.getIsOwned() ? soldier.getOwnerUUID() : null;
        BattleGeneral.setBattle(soldier, false);
        soldier.getPersistentData().remove(Barracks.TAG_CAMP);
        soldier.getPersistentData().remove(Barracks.TAG_TO_BARRACKS);
        soldier.clearUpkeepPos();
        soldier.setOwnerUUID(java.util.Optional.empty());
        soldier.setIsOwned(false);
        soldier.despawnTimer = -1;
        soldier.setPersistenceRequired();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = soldier.getItemBySlot(slot);
            if (!stack.isEmpty()) soldier.setItemSlot(slot, faction.style(stack));
        }
        soldier.setCustomName(Component.literal(soldier.getName().getString()).withStyle(faction.color()));
        soldier.getPersistentData().putString(TAG_FACTION, faction.id().toString());
        soldier.getPersistentData().putLong(TAG_VILLAGE, village.center().asLong());
        if (village.castle()) soldier.getPersistentData().putBoolean(CastleManager.TAG_CASTLE, true);
        soldier.restrictTo(village.center(), village.castle() ? CastleManager.PATROL_RADIUS : PATROL_RADIUS);
        GarrisonDefense.install(soldier);
        PlayerTeam old = level.getScoreboard().getPlayersTeam(soldier.getStringUUID());
        if (old != null) level.getScoreboard().removePlayerFromTeam(soldier.getStringUUID(), old);
        joinTeam(level, soldier, faction);
        if (Surrender.is(soldier)) Surrender.standUp(soldier);
        if (former != null) Barracks.recount(level, former);
    }

    /** Soldados vivos (y cargados) de la guarnición de la aldea con centro {@code center}. Los rendidos (H9) no cuentan. */
    public static List<AbstractRecruitEntity> garrison(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        AABB box = new AABB(center).inflate(PATROL_RADIUS + 64, 64, PATROL_RADIUS + 64);
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, box,
                e -> e.isAlive() && e.getPersistentData().contains(TAG_VILLAGE) && e.getPersistentData().getLong(TAG_VILLAGE) == key
                        && !Surrender.is(e));
    }

    /** True si es un soldado de guarnición que todavía sirve a su facción (nadie lo contrató). */
    public static boolean isGarrison(Entity entity) {
        return entity instanceof AbstractRecruitEntity r && !r.getIsOwned() && r.getPersistentData().contains(TAG_FACTION);
    }

    /** Equipo del marcador de la facción: da el color al nombre y evita que se lastimen entre ellos. */
    public static PlayerTeam team(ServerLevel level, Faction faction) {
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.getPlayerTeam(faction.teamName());
        if (team == null) {
            team = board.addPlayerTeam(faction.teamName());
            team.setDisplayName(Component.literal(faction.name()));
            team.setColor(faction.color());
            team.setAllowFriendlyFire(false);
        }
        return team;
    }

    private static void joinTeam(ServerLevel level, AbstractRecruitEntity soldier, Faction faction) {
        level.getScoreboard().addPlayerToTeam(soldier.getStringUUID(), team(level, faction));
    }

    private static void mountOnHorse(ServerLevel level, AbstractRecruitEntity soldier, RandomSource random, int patrolRadius) {
        Horse horse = EntityType.HORSE.create(level);
        if (horse == null) return;
        horse.moveTo(soldier.getX(), soldier.getY(), soldier.getZ(), soldier.getYRot(), 0F);
        horse.finalizeSpawn(level, level.getCurrentDifficultyAt(soldier.blockPosition()), MobSpawnType.EVENT, null, null);
        horse.setTamed(true);
        horse.equipSaddle(null);
        horse.setPersistenceRequired();
        horse.restrictTo(BlockPos.of(soldier.getPersistentData().getLong(TAG_VILLAGE)), patrolRadius);
        level.addFreshEntity(horse);
        soldier.startRiding(horse, true);
    }

    /**
     * Un lugar donde pararse cerca del centro: suelo firme y dos bloques libres, lo más cerca posible de la altura
     * de la campana (hasta 6 arriba o abajo). Se busca cerca de esa altura y no con el mapa de alturas del mundo para que no
     * aparezcan arriba de los techos ni, si la aldea está en un pozo o bajo tierra, en la superficie.
     */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos center, RandomSource random, int spread) {
        for (int i = 0; i < 24; i++) {
            int x = center.getX() + random.nextInt(spread * 2 + 1) - spread;
            int z = center.getZ() + random.nextInt(spread * 2 + 1) - spread;
            for (int step = 0; step <= 12; step++) {            // 0, +1, -1, +2, -2 ... hasta ±6
                int dy = (step % 2 == 0 ? 1 : -1) * ((step + 1) / 2);
                BlockPos p = new BlockPos(x, center.getY() + dy, z);
                if (canStandAt(level, p)) return p;
            }
        }
        return center.above();
    }

    private static boolean canStandAt(ServerLevel level, BlockPos p) {
        BlockPos below = p.below();
        if (!level.getFluidState(below).isEmpty() || !level.getFluidState(p).isEmpty()) return false;
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty();
    }
}
