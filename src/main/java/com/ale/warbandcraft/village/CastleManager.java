package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Los castillos (H4): una estructura más que puede aparecer explorando, como una mazmorra — no dependen
 * de ninguna aldea ni están limitados a una por facción. Algunas aldeas van a tener uno cerca por pura
 * coincidencia (las dos cosas se buscan cerca del jugador); la mayoría no.
 *
 * La estructura ({@code castle_default.nbt}, 43x28x45, sin jigsaw/spawners/entidades) se pega entera
 * sobre terreno allanado; no tiene un punto de "puerta" marcado, se trata como una caja única. La
 * guarnición es más grande que la de una aldea (15-30) y tiene un {@code recruits:captain} fijo: si
 * cae, el asedio termina aunque sigan vivos otros defensores, que quedan libres para reclutar.
 */
public final class CastleManager {
    /** Marca (además de {@link VillageManager#TAG_VILLAGE}) a todo soldado de un castillo: usa otro radio de patrulla. */
    public static final String TAG_CASTLE = "warbandcraft:castle";
    /** Marca al capitán fijo del castillo. */
    public static final String TAG_CAPTAIN = "warbandcraft:captain";

    public static int GARRISON_MIN = 15;
    public static int GARRISON_MAX = 30;
    /** Hasta dónde patrulla la guarnición: la estructura es mucho más grande que una aldea. */
    public static int PATROL_RADIUS = 40;

    /** No se vuelve a tirar el dado cerca de un lugar ya explorado (aunque no haya salido castillo). */
    public static int ATTEMPT_SPACING = 300;
    /** Tampoco se planta uno si ya hay otro castillo cerca. */
    public static int CASTLE_SPACING = 300;
    /** Probabilidad de que un lugar nuevo tenga castillo: 1 de cada N. */
    public static int SPAWN_CHANCE_1_IN = 4;
    /** A qué distancia del punto explorado puede caer el castillo. */
    private static int CANDIDATE_MIN_DIST = 48;
    private static int CANDIDATE_MAX_DIST = 200;
    /** Cuánto se mueve buscando un lugar plano, alrededor del punto elegido. */
    private static int SITE_JITTER = 40;
    private static int SEARCH_ATTEMPTS = 20;
    /** Si la diferencia de altura en la huella de la estructura es menor a esto, el lugar ya sirve. */
    private static int FLAT_ENOUGH = 6;

    private static final ResourceLocation STRUCTURE = new ResourceLocation(WarbandCraft.MODID, "castle_default");

    private CastleManager() {}

    /** Tira el dado por un castillo nuevo cerca de {@code around} (lo llama el tick de cada jugador, como las aldeas). */
    public static void scan(ServerLevel level, BlockPos around) {
        scan(level, around, FactionManager.INSTANCE::forBiome);
    }

    /** Igual, pero eligiendo la facción con {@code factionFor} (las pruebas lo usan para no depender del bioma). */
    public static void scan(ServerLevel level, BlockPos around, Function<Holder<Biome>, Faction> factionFor) {
        CastleData attempts = CastleData.get(level);
        if (attempts.nearest(around, ATTEMPT_SPACING) != null) return; // zona ya explorada, con o sin castillo
        attempts.add(around);
        RandomSource random = level.getRandom();
        if (random.nextInt(SPAWN_CHANCE_1_IN) != 0) return;

        double angle = random.nextDouble() * Math.PI * 2;
        int dist = CANDIDATE_MIN_DIST + random.nextInt(CANDIDATE_MAX_DIST - CANDIDATE_MIN_DIST + 1);
        BlockPos candidate = around.offset((int) Math.round(Math.cos(angle) * dist), 0, (int) Math.round(Math.sin(angle) * dist));
        if (hasCastleNear(level, candidate, CASTLE_SPACING)) return;

        Faction faction = factionFor.apply(level.getBiome(candidate));
        if (faction == null) return;
        try {
            place(level, candidate, faction);
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("No se pudo levantar un castillo de {} cerca de {}", faction.id(), candidate.toShortString(), e);
        }
    }

    /** True si ya hay un castillo (de cualquier facción) a menos de {@code spacing} bloques de {@code pos}. */
    public static boolean hasCastleNear(ServerLevel level, BlockPos pos, double spacing) {
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (v.castle() && VillageData.horizontalDistSq(v.center(), pos) <= spacing * spacing) return true;
        }
        return false;
    }

    /** Planta el castillo de {@code faction} cerca de {@code candidate} (sin tirar el dado: lo usan el scan y las pruebas). */
    public static void place(ServerLevel level, BlockPos candidate, Faction faction) {
        StructureTemplateManager templates = level.getServer().getStructureManager();
        Optional<StructureTemplate> maybe = templates.get(STRUCTURE);
        if (maybe.isEmpty()) {
            WarbandCraft.LOGGER.warn("Falta la estructura {}, no se levanta el castillo de {}", STRUCTURE, faction.id());
            return;
        }
        StructureTemplate template = maybe.get();
        Vec3i size = template.getSize();
        RandomSource random = level.getRandom();

        BlockPos origin = findSite(level, candidate, size, random);
        if (origin == null) {   // H25: todo el lugar está ocupado por mazmorras de otros mods o nuestras
            WarbandCraft.LOGGER.info("Castillo de {} cerca de {}: sin lugar libre de estructuras", faction.id(), candidate.toShortString());
            return;
        }
        flatten(level, origin, size);
        template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), random, Block.UPDATE_CLIENTS);
        fillLoot(level, origin, size, random);

        BlockPos anchor = origin.offset(size.getX() / 2, 1, size.getZ() / 2);
        VillageData.get(level).add(new VillageData.Village(anchor, faction.id(), level.getGameTime()).asCastle());

        spawnCaptain(level, anchor, faction, random);
        int spread = Math.max(4, Math.min(size.getX(), size.getZ()) / 2 - 2);
        int count = GARRISON_MIN + random.nextInt(GARRISON_MAX - GARRISON_MIN + 1);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Troop troop = faction.pickTroop(random);
            if (troop == null) break;
            AbstractRecruitEntity soldier = VillageManager.spawnTroop(level, anchor, faction, troop, random, spread, PATROL_RADIUS);
            if (soldier == null) continue;
            soldier.getPersistentData().putBoolean(TAG_CASTLE, true);
            spawned++;
        }
        WarbandCraft.LOGGER.info("Castillo de {} en {} (guarnición: {} + capitán)", faction.id(), origin.toShortString(), spawned);
    }

    /** Entrar a los muros de un castillo de facción (pedido de Ale): aviso y, si no salís en esto (segundos), te atacan. 0 = en el acto. */
    public static int TRESPASS_GRACE_SECONDS = 5;
    /** Adentro de los muros, en coordenadas del plano (origen = esquina): x, z de/hasta, y desde el piso hacia abajo/arriba. */
    public static int INSIDE_X0 = 8, INSIDE_X1 = 38, INSIDE_Z0 = 2, INSIDE_Z1 = 41, INSIDE_BELOW = 4, INSIDE_ABOVE = 26;
    /** Jugador → (castillo, segundos adentro). */
    private static final java.util.Map<java.util.UUID, long[]> TRESPASS = new java.util.HashMap<>();

    /** ¿{@code pos} está adentro de los muros del castillo {@code v}? (el plano no se gira: {@code anchor} = esquina + 21, 1, 22). */
    public static boolean insideWalls(VillageData.Village v, BlockPos pos) {
        if (!v.castle()) return false;
        int x = pos.getX() - v.center().getX() + 21, z = pos.getZ() - v.center().getZ() + 22, dy = pos.getY() - v.center().getY();
        return x >= INSIDE_X0 && x <= INSIDE_X1 && z >= INSIDE_Z0 && z <= INSIDE_Z1 && dy >= -INSIDE_BELOW && dy <= INSIDE_ABOVE;
    }

    /** Cada 1 s por jugador: adentro de un castillo de facción con guarnición = aviso, y a los segundos de gracia, ataque. */
    public static void trespassTick(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = player.isCreative() || player.isSpectator() || com.ale.warbandcraft.route.Travel.isTraveling(player.getUUID())
                ? null : VillageData.get(level).nearest(player.blockPosition(), 40);
        if (v == null || !v.castle() || v.isConquered() || VillageData.get(level).isTest(v.center()) || !insideWalls(v, player.blockPosition())
                || com.ale.warbandcraft.raid.RaidManager.get(level, v.center()) != null
                || VillageManager.garrison(level, v.center()).isEmpty()) {
            TRESPASS.remove(player.getUUID());
            return;
        }
        long[] t = TRESPASS.get(player.getUUID());
        if (t == null || t[0] != v.center().asLong()) TRESPASS.put(player.getUUID(), t = new long[]{v.center().asLong(), 0});
        Faction faction = FactionManager.INSTANCE.get(v.faction());
        net.minecraft.network.chat.Component of = net.minecraft.network.chat.Component.literal(faction != null ? faction.ofName() : "?")
                .withStyle(faction != null ? faction.color() : net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD);
        long left = TRESPASS_GRACE_SECONDS - t[1];
        t[1]++;
        if (left > 0) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.warbandcraft.trespass.warn", of, left)
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        TRESPASS.remove(player.getUUID());
        if (com.ale.warbandcraft.raid.RaidManager.start(level, v, player.getUUID(), player) == null) return;
        for (AbstractRecruitEntity g : VillageManager.garrison(level, v.center())) {   // la guarnición sale a pelear
            if (g.getTarget() == null || !g.getTarget().isAlive()) g.setTarget(player);
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("msg.warbandcraft.trespass.attack", of)
                .withStyle(net.minecraft.ChatFormatting.RED));
    }

    /** Botín de los cofres (pedido de Ale): barriles = armería, cofres = tesoro. */
    public static final ResourceLocation LOOT_ARMORY = new ResourceLocation(WarbandCraft.MODID, "chests/castle");
    public static final ResourceLocation LOOT_TREASURE = new ResourceLocation(WarbandCraft.MODID, "chests/castle_treasure");
    /** El plano del castillo vino de otro mod con tablas de botín que no existen ("eugene:..."): salían vacíos. */
    private static final String BROKEN_LOOT = "eugene:";

    /** Les pone nuestro botín a todos los contenedores del castillo recién pegado (y saca lo que traía el plano). */
    private static void fillLoot(ServerLevel level, BlockPos origin, Vec3i size, RandomSource random) {
        for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity c) {
                c.clearContent();
                c.setLootTable(lootFor(level.getBlockState(p)), random.nextLong());
            }
        }
    }

    private static ResourceLocation lootFor(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock ? LOOT_TREASURE : LOOT_ARMORY;
    }

    /**
     * Castillos de antes: un contenedor que todavía tiene la tabla rota recibe la nuestra justo antes de abrirse o romperse
     * (desde {@code VillageEvents}).
     */
    public static void fixOldLoot(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity c)) return;
        String table = c.saveWithoutMetadata().getString("LootTable");
        if (!table.startsWith(BROKEN_LOOT)) return;
        c.setLootTable(lootFor(level.getBlockState(pos)), level.random.nextLong());
        c.setChanged();
    }

    /** Busca un lugar razonablemente plano cerca de {@code candidate}, forzando a que el terreno exista. */
    private static BlockPos findSite(ServerLevel level, BlockPos candidate, Vec3i size, RandomSource random) {
        BlockPos best = null;
        int bestSpread = Integer.MAX_VALUE;
        for (int i = 0; i < SEARCH_ATTEMPTS; i++) {
            int cx = candidate.getX() + random.nextInt(SITE_JITTER * 2 + 1) - SITE_JITTER;
            int cz = candidate.getZ() + random.nextInt(SITE_JITTER * 2 + 1) - SITE_JITTER;

            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            int[][] samples = {{0, 0}, {size.getX() / 2, size.getZ() / 2}, {-size.getX() / 2, -size.getZ() / 2},
                    {size.getX() / 2, -size.getZ() / 2}, {-size.getX() / 2, size.getZ() / 2}};
            for (int[] s : samples) {
                int sx = cx + s[0];
                int sz = cz + s[1];
                level.getChunk(sx >> 4, sz >> 4); // fuerza a que el chunk exista (lejos de cualquier jugador)
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
            int spread = max - min;
            int x0 = cx - size.getX() / 2, z0 = cz - size.getZ() / 2;
            // H25: no aplastar una estructura de otro mod (ni una guarida o túmulo nuestro)
            if (com.ale.warbandcraft.dungeon.Compat.surfaceStructureIn(level, x0, z0, x0 + size.getX(), z0 + size.getZ())
                    || com.ale.warbandcraft.dungeon.DungeonData.get(level).near(new BlockPos(cx, 0, cz),
                    Math.max(size.getX(), size.getZ()) / 2 + com.ale.warbandcraft.dungeon.Dungeons.FOOTPRINT) != null) continue;
            if (spread < bestSpread) {
                bestSpread = spread;
                best = new BlockPos(cx - size.getX() / 2, min, cz - size.getZ() / 2);
            }
            if (spread <= FLAT_ENOUGH) break;
        }
        return best;
    }

    /** Allana todo el rectángulo de la estructura (y un poco de aire arriba) antes de pegarla. */
    private static void flatten(ServerLevel level, BlockPos origin, Vec3i size) {
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < size.getX(); x++) {
            for (int z = 0; z < size.getZ(); z++) {
                int wx = origin.getX() + x;
                int wz = origin.getZ() + z;
                for (int y = origin.getY(); y < origin.getY() + size.getY() + 3; y++) {
                    level.setBlock(new BlockPos(wx, y, wz), air, Block.UPDATE_CLIENTS);
                }
                for (int y = origin.getY() - 1; y > origin.getY() - 6; y--) {
                    BlockPos p = new BlockPos(wx, y, wz);
                    BlockState state = level.getBlockState(p);
                    FluidState fluid = level.getFluidState(p);
                    if (state.isAir() || !fluid.isEmpty()) level.setBlock(p, dirt, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Hace aparecer solo el capitán fijo del castillo en {@code anchor} (las pruebas de H4 arman el resto a mano). */
    public static void spawnCaptain(ServerLevel level, BlockPos anchor, Faction faction, RandomSource random) {
        AbstractRecruitEntity captain = VillageManager.spawnFixed(level, anchor, faction,
                new ResourceLocation("recruits", "captain"), captainEquipment(), List.of(), false,
                "Capitán " + faction.soldierSuffix(), random, 4, PATROL_RADIUS);
        if (captain == null) {
            WarbandCraft.LOGGER.warn("No se pudo crear el capitán del castillo de {}", faction.id());
            return;
        }
        captain.getPersistentData().putBoolean(TAG_CASTLE, true);
        captain.getPersistentData().putBoolean(TAG_CAPTAIN, true);
        captain.setItemSlot(EquipmentSlot.OFFHAND, faction.banner().copy());
    }

    public static Map<EquipmentSlot, Item> captainEquipment() {
        Map<EquipmentSlot, Item> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.MAINHAND, Items.IRON_SWORD);
        gear.put(EquipmentSlot.HEAD, Items.IRON_HELMET);
        gear.put(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE);
        gear.put(EquipmentSlot.LEGS, Items.IRON_LEGGINGS);
        gear.put(EquipmentSlot.FEET, Items.IRON_BOOTS);
        return gear;
    }

    /** ¿Sigue vivo el capitán, en esta lista de soldados de una guarnición de castillo? */
    public static boolean captainAlive(List<AbstractRecruitEntity> garrison) {
        for (AbstractRecruitEntity e : garrison) {
            if (e.getPersistentData().getBoolean(TAG_CAPTAIN)) return true;
        }
        return false;
    }

    /**
     * Cayó el capitán: a los defensores que quedan vivos se les sacan las marcas de guarnición (dejan de
     * ser hostiles y de patrullar) y quedan parados ahí, para reclutarlos como prisioneros con la
     * interacción normal de Recruits.
     */
    public static void freeDefenders(List<AbstractRecruitEntity> garrison) {
        for (AbstractRecruitEntity soldier : garrison) {
            soldier.getPersistentData().remove(VillageManager.TAG_FACTION);
            soldier.getPersistentData().remove(VillageManager.TAG_VILLAGE);
            soldier.getPersistentData().remove(TAG_CASTLE);
            soldier.getPersistentData().remove(TAG_CAPTAIN);
            soldier.setTarget(null);
            soldier.clearRestriction();
        }
    }

    /** True si este soldado pertenece a la guarnición de un castillo (para elegir el radio de patrulla al recargar). */
    public static boolean isCastleSoldier(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG_CASTLE);
    }
}
