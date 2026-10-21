package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.dungeon.Bandits;
import com.ale.warbandcraft.dungeon.Barrows;
import com.ale.warbandcraft.dungeon.Compat;
import com.ale.warbandcraft.dungeon.DungeonBuilder;
import com.ale.warbandcraft.dungeon.DungeonData;
import com.ale.warbandcraft.dungeon.Dungeons;
import com.ale.warbandcraft.dungeon.FlatLands;
import com.ale.warbandcraft.dungeon.StructureDensity;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas del Hito 25 (mazmorras): los Bandidos son forajidos, los biomas llanos corren la erosión, una guarida se arma,
 * se llena y queda limpia al matar a todos, la puerta de las garras se abre con la combinación y el Señor del Túmulo
 * cuenta, los bandidos asaltan un envío que pasa cerca y tus soldados no eligen solos a un jefe.
 * Las mazmorras se arman lejos del área de pruebas (a 3000 bloques) para no pisar nada.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H25GameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, batch = "h25_dungeons")
    public static void banditsAreOutlaws(GameTestHelper h) {
        Faction f = FactionManager.INSTANCE.get(Dungeons.BANDITS);
        check(f != null, "no se cargó la facción de bandidos");
        check(f.outlaw(), "los bandidos no son forajidos");
        check(!FactionManager.INSTANCE.ordered().contains(f), "los bandidos no deberían estar en la lista de facciones con aldeas");
        check(Relations.get(h.getLevel().getServer()).atWar(UUID.randomUUID(), f.id()), "los bandidos deberían estar en guerra con todos");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "h25_dungeons")
    public static void flatBiasShiftsErosion(GameTestHelper h) {
        double before = FlatLands.shift();
        try {
            FlatLands.setShift(0.2);
            FlatLands.Bias bias = new FlatLands.Bias(DensityFunctions.constant(0.1));
            check(Math.abs(bias.compute(null) - 0.3) < 1e-6, "0,1 + 0,2 debería dar 0,3, dio " + bias.compute(null));
            check(new FlatLands.Bias(DensityFunctions.constant(0.95)).compute(null) == 1.0, "no se pasa de 1");
            FlatLands.setShift(0);
            check(bias.compute(null) == 0.1, "sin corrimiento queda como vanilla");
        } finally {
            FlatLands.setShift(before);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "h25_lair", timeoutTicks = 400)
    public static void lairFillsAndClears(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DungeonData data = DungeonData.get(level);
        DungeonData.Dungeon d = build(level, h, DungeonData.Kind.LAIR, 3000);
        try {
            Dungeons.populate(level, d, level.getRandom());
            check(d.remaining >= 7, "debería haber al menos 6 bandidos + el jefe, hay " + d.remaining);
            check(d.captives >= Dungeons.CAPTIVES_MIN, "faltan presos: " + d.captives);
            check(d.treasure != null && level.getBlockState(d.treasure).is(net.minecraft.world.level.block.Blocks.CHEST), "falta el cofre del jefe");
            check(d.activeLair(), "la guarida debería estar activa");
            AABB box = Dungeons.box(d).inflate(4);
            List<AbstractRecruitEntity> bandits = level.getEntitiesOfClass(AbstractRecruitEntity.class, box,
                    r -> r.getPersistentData().getInt(Dungeons.TAG_DUNGEON) == d.id && VillageManager.isGarrison(r));
            check(bandits.size() == d.remaining, "se ven " + bandits.size() + " bandidos y deberían ser " + d.remaining);
            for (AbstractRecruitEntity b : bandits) {
                check(b.getY() < d.entrance.getY() - 4, "un bandido apareció arriba, no adentro de la guarida");
                b.kill();
            }
            check(d.cleared(), "matando a todos debería quedar limpia (quedan " + d.remaining + ")");
            check(d.refillTick > d.clearedTick, "una guarida limpia vuelve a llenarse más adelante");
        } finally {
            cleanup(level, d);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "h25_barrow", timeoutTicks = 400)
    public static void barrowDoorAndLord(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DungeonData.Dungeon d = build(level, h, DungeonData.Kind.BARROW, -3000);
        try {
            Dungeons.populate(level, d, level.getRandom());
            check(d.coffins.size() >= 6, "pocos ataúdes: " + d.coffins.size());
            check(d.pillars.size() == 3 && d.answer.length == 3, "faltan las columnas");
            check(!d.door.isEmpty() && !level.getBlockState(d.door.get(0)).isAir(), "la puerta debería estar cerrada");
            // La Garra la lleva el guardián
            List<Mob> guardians = level.getEntitiesOfClass(Mob.class, Dungeons.box(d).inflate(4),
                    m -> m.getPersistentData().getInt(Dungeons.TAG_DUNGEON) == d.id && m.getOffhandItem().is(Items.GOLD_NUGGET));
            check(guardians.size() == 1, "el guardián con la Garra no está");
            // Girar cada columna hasta la combinación
            for (int i = 0; i < 3; i++) {
                BlockPos p = d.pillars.get(i);
                ItemFrame frame = Barrows.pillarAt(level, d, p);
                check(frame != null, "la columna " + i + " no tiene su marco");
                for (int turns = 0; turns < 3 && !frame.getItem().is(Barrows.SYMBOLS[d.answer[i]]); turns++) Barrows.turn(level, frame);
            }
            check(d.doorOpen && level.getBlockState(d.door.get(0)).isAir(), "con la combinación la puerta tendría que abrirse");
            // Un ataúd que se abre y el Señor que despierta y cae
            Barrows.wakeCoffin(level, d, 0, level.getRandom());
            check(d.risen.contains(0), "el ataúd no se abrió");
            Barrows.wakeLord(level, d, level.getRandom());
            List<Mob> lords = level.getEntitiesOfClass(Mob.class, Dungeons.box(d).inflate(8), m -> m.getPersistentData().getBoolean(Barrows.TAG_LORD));
            check(lords.size() == 1, "el Señor del Túmulo no apareció");
            check(lords.get(0).getMaxHealth() >= Barrows.LORD_HEALTH - 1, "el Señor tiene poca vida");
            lords.get(0).kill();
            check(d.bossDead && d.cleared(), "matando al Señor el túmulo debería quedar limpio");
        } finally {
            cleanup(level, d);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "h25_ambush")
    public static void banditsAmbushShipmentNearTheirLair(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DungeonData data = DungeonData.get(level);
        BlockPos base = h.absolutePos(BlockPos.ZERO).offset(6000, 0, 0);
        DungeonData.Dungeon lair = new DungeonData.Dungeon(data.newId(), DungeonData.Kind.LAIR, base.offset(0, 0, 30), base.offset(0, -12, 50),
                new int[]{base.getX() - 8, base.getY() - 14, base.getZ() + 20, base.getX() + 8, base.getY(), base.getZ() + 80});
        lair.name = "Guarida de prueba";
        lair.populated = true;
        lair.remaining = 5;
        data.add(lair);
        double chance = Bandits.AMBUSH_CHANCE;
        UUID owner = UUID.randomUUID();
        RouteData.Shipment s = new RouteData.Shipment(UUID.randomUUID(), owner, base.offset(-200, 0, 0), base.offset(200, 0, 0), true,
                level.getGameTime(), level.getGameTime(), List.of(), List.of(new ItemStack(Items.BREAD, 10), new ItemStack(Items.IRON_INGOT, 5)));
        RouteData.Shipment far = new RouteData.Shipment(UUID.randomUUID(), owner, base.offset(-200, 0, 400), base.offset(200, 0, 400), true,
                level.getGameTime(), level.getGameTime(), List.of(), List.of(new ItemStack(Items.BREAD, 10)));
        try {
            Bandits.AMBUSH_CHANCE = 1.0;
            RouteData.Shipment after = Bandits.ambush(level, s);
            int left = after.items().stream().mapToInt(ItemStack::getCount).sum();
            check(left < 15, "el envío que pasa al lado de la guarida debería perder algo (le quedan " + left + ")");
            check(!lair.stolen.isEmpty(), "lo robado tendría que ir a la guarida");
            RouteData.Shipment safe = Bandits.ambush(level, far);
            check(safe == far, "un envío lejos de toda guarida no se asalta");
            lair.clearedTick = level.getGameTime();
            check(!lair.activeLair(), "limpia, la guarida no asalta más");
        } finally {
            Bandits.AMBUSH_CHANCE = chance;
            data.remove(lair);
            RouteData.get(level).removeShipment(s.id());
            RouteData.get(level).removeShipment(far.id());
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "h25_dungeons")
    public static void soldiersDoNotPickBosses(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var wither = EntityType.WITHER.create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        check(wither != null && zombie != null, "no se pudieron crear");
        check(Compat.isBoss(wither), "el Wither debería contar como jefe (forge:bosses)");
        check(!Compat.isBoss(zombie), "un zombi no es jefe");
        UUID owner = UUID.randomUUID();
        check(!Quarters.isEnemy(level, owner, wither), "tus soldados no eligen solos a un jefe");
        check(Quarters.isEnemy(level, owner, zombie), "un zombi sí es enemigo");
        wither.discard();
        zombie.discard();
        check(!Compat.foreignStructureAt(level, h.absolutePos(new BlockPos(1, 2, 1))), "el área de pruebas no es una estructura");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** Arma una mazmorra a {@code dx} bloques del área de pruebas, mirando al este. */
    private static DungeonData.Dungeon build(ServerLevel level, GameTestHelper h, DungeonData.Kind kind, int dx) {
        BlockPos at = h.absolutePos(BlockPos.ZERO).offset(dx, 0, 0);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        DungeonData data = DungeonData.get(level);
        DungeonData.Dungeon d = DungeonBuilder.build(level, kind, new BlockPos(at.getX(), y, at.getZ()), Direction.EAST, data.newId(), level.getRandom());
        d.name = kind == DungeonData.Kind.LAIR ? "Guarida de prueba" : "Túmulo de prueba";
        data.add(d);
        return d;
    }

    /** Saca a los de adentro y borra la mazmorra (no queda para otras pruebas). */
    private static void cleanup(ServerLevel level, DungeonData.Dungeon d) {
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, Dungeons.box(d).inflate(16),
                e -> e.getPersistentData().getInt(Dungeons.TAG_DUNGEON) == d.id || e.getPersistentData().getInt(Dungeons.TAG_CAPTIVE) == d.id
                        || e.getPersistentData().getInt(Barrows.TAG_PILLAR) == d.id)) e.discard();
        DungeonData.get(level).remove(d);
    }

    /**
     * Estructuras de otros mods por bioma: con el generador de biomas del overworld de verdad, en una grilla de 10 chunks
     * achicada a 6, en bosques/junglas se quedan todos los lugares y en lo llano ~15%. Solo se tocan los mods de la lista.
     */
    @GameTest(template = TEMPLATE, batch = "h25_dungeons", timeoutTicks = 400)
    public static void structuresFollowBiomes(GameTestHelper h) throws Exception {
        check(StructureDensity.touches("nova_structures:taverns"), "las tabernas se tocan");
        check(StructureDensity.touches("structory_towers:towers"), "las torres se tocan");
        check(!StructureDensity.touches("nova_structures:villages_swamp"), "las aldeas no se tocan");
        check(!StructureDensity.touches("nova_structures:nether_camps"), "lo del Nether no se toca");
        check(!StructureDensity.touches("minecraft:villages"), "lo de vanilla no se toca");
        StructureDensity.apply(h.getLevel());   // sin esos mods no cambia nada, pero no tiene que romper

        RegistryAccess ra = h.getLevel().registryAccess();
        long seed = 424242L;
        BiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(
                ra.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        RandomState rs = RandomState.create(ra.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(),
                ra.lookupOrThrow(Registries.NOISE), seed);
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(rs, seed, biomes, ra.lookupOrThrow(Registries.STRUCTURE_SET));
        RandomSpreadStructurePlacement base = new RandomSpreadStructurePlacement(10, 4, RandomSpreadType.LINEAR, 777);
        StructureDensity.Weighted w = StructureDensity.Weighted.of(base, biomes, rs);
        check(w.spacing() == 6 && w.separation() == 2, "grilla achicada: " + w.spacing() + "/" + w.separation());

        int[] seen = new int[3], kept = new int[3];   // 0 denso, 1 llano, 2 resto
        for (int x = -240; x < 240; x++) {
            for (int z = -240; z < 240; z++) {
                var c = w.getPotentialStructureChunk(seed, x, z);
                if (c.x != x || c.z != z) continue;
                Holder<Biome> b = StructureDensity.biomeAt(biomes, rs, x, z);
                int k = b.is(StructureDensity.DENSE) ? 0 : b.is(StructureDensity.SPARSE) ? 1 : 2;
                seen[k]++;
                if (w.isStructureChunk(state, x, z)) kept[k]++;
            }
        }
        String info = "denso " + kept[0] + "/" + seen[0] + ", llano " + kept[1] + "/" + seen[1] + ", resto " + kept[2] + "/" + seen[2];
        WarbandCraft.LOGGER.info("Estructuras por bioma (prueba): {}", info);
        check(seen[0] > 100 && seen[1] > 100, "pocos lugares para medir: " + info);
        check(kept[0] == seen[0], "en bosques/junglas se quedan todos: " + info);
        double sparse = kept[1] / (double) seen[1];
        check(sparse > 0.08 && sparse < 0.25, "en lo llano ~15%: " + info);
        double other = seen[2] == 0 ? 0.5 : kept[2] / (double) seen[2];
        check(other > 0.35 && other < 0.65, "en el resto ~50%: " + info);
        h.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
