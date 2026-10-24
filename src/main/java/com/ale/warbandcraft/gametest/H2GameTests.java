package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;

/**
 * Pruebas automáticas del Hito 2 (facciones NPC y aldeas con guarnición).
 * Corren con {@code gradlew runGameTestServer} (o probar.bat), junto con las de H1.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H2GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);
    private static final String[] FACTIONS = {"valdren", "norte", "sultanato", "pantanos"};

    // ------------------------------------------------------------------ carga de los JSON

    /** Las 4 facciones se cargan, todas sus tropas se pueden armar y el estandarte tiene dibujo. */
    @GameTest(template = TEMPLATE)
    public static void h2FaccionesCargadas(GameTestHelper h) {
        List<Faction> all = FactionManager.INSTANCE.ordered();
        check(all.size() == FACTIONS.length, "se esperaban " + FACTIONS.length + " facciones y hay " + all.size());
        for (String name : FACTIONS) {
            Faction f = faction(name);
            for (Troop t : f.troops()) {
                check(t.resolve() != null, f.name() + ": la tropa " + t.name() + " no se puede armar (kit, ítem o entidad inexistente)");
            }
            check(f.banner().getItem() instanceof BannerItem, f.name() + ": el estandarte no es un estandarte");
            CompoundTag be = f.banner().getTagElement("BlockEntityTag");
            check(be != null && !be.getList("Patterns", 10).isEmpty(), f.name() + ": el estandarte quedó liso (patrones inválidos)");
            check(f.garrisonMin() >= 1 && f.garrisonMax() >= f.garrisonMin(), f.name() + ": guarnición mal definida");
        }
        h.succeed();
    }

    /** Cada bioma con aldeas va a la facción que corresponde. */
    @GameTest(template = TEMPLATE)
    public static void h2BiomaDecideFaccion(GameTestHelper h) {
        expectBiome(h, Biomes.PLAINS, "valdren");
        expectBiome(h, Biomes.MEADOW, "valdren");
        expectBiome(h, Biomes.TAIGA, "norte");
        expectBiome(h, Biomes.SNOWY_PLAINS, "norte");
        expectBiome(h, Biomes.DESERT, "sultanato");
        expectBiome(h, Biomes.BADLANDS, "sultanato");
        expectBiome(h, Biomes.SAVANNA, "pantanos");
        expectBiome(h, Biomes.SWAMP, "pantanos");
        expectBiome(h, Biomes.JUNGLE, "pantanos");
        expectBiome(h, Biomes.OCEAN, null);
        h.succeed();
    }

    // ------------------------------------------------------------------ guarniciones

    @GameTest(template = TEMPLATE) public static void h2GuarnicionValdren(GameTestHelper h)   { checkGarrison(h, "valdren"); }
    @GameTest(template = TEMPLATE) public static void h2GuarnicionNorte(GameTestHelper h)     { checkGarrison(h, "norte"); }
    @GameTest(template = TEMPLATE) public static void h2GuarnicionSultanato(GameTestHelper h) { checkGarrison(h, "sultanato"); }
    @GameTest(template = TEMPLATE) public static void h2GuarnicionPantanos(GameTestHelper h)  { checkGarrison(h, "pantanos"); }

    /**
     * Una guarnición completa tiene entre min y max soldados, y además cada tipo de tropa aparece bien:
     * entidad correcta, sin dueño, con la marca de su facción, en el equipo del marcador, con equipo,
     * cuero teñido, estandarte propio, sin desaparecer y atada a la aldea.
     */
    private static void checkGarrison(GameTestHelper h, String name) {
        Faction f = faction(name);
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        RandomSource random = RandomSource.create(42);

        List<AbstractRecruitEntity> garrison = VillageManager.spawnGarrison(level, center, f, random, 2);
        check(garrison.size() >= f.garrisonMin() && garrison.size() <= f.garrisonMax(),
                f.name() + ": la guarnición tiene " + garrison.size() + " soldados (esperado " + f.garrisonMin() + "-" + f.garrisonMax() + ")");
        for (AbstractRecruitEntity s : garrison) checkSoldier(h, f, s, null);

        for (Troop troop : f.troops()) {
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, center, f, troop, random, 2);
            check(s != null, f.name() + ": no apareció la tropa " + troop.name());
            checkSoldier(h, f, s, troop);
        }
        cleanup(level, center);
        h.succeed();
    }

    private static void checkSoldier(GameTestHelper h, Faction f, AbstractRecruitEntity s, Troop troop) {
        String who = f.name() + " / " + (troop != null ? troop.name() : s.getName().getString());
        check(s.isAlive(), who + ": el soldado no está vivo");
        check(!s.getIsOwned(), who + ": el soldado tiene dueño");
        check(VillageManager.isGarrison(s), who + ": no quedó marcado como guarnición");
        check(f.id().toString().equals(s.getPersistentData().getString(VillageManager.TAG_FACTION)), who + ": marca de facción incorrecta");
        check(s.despawnTimer < 0, who + ": va a desaparecer (despawnTimer=" + s.despawnTimer + ")");
        check(s.isPersistenceRequired(), who + ": no es persistente");
        check(s.hasRestriction(), who + ": no está atado a la aldea");
        PlayerTeam team = h.getLevel().getScoreboard().getPlayersTeam(s.getStringUUID());
        check(team != null && team.getName().equals(f.teamName()), who + ": no está en el equipo " + f.teamName());

        boolean anyGear = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = s.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            anyGear = true;
            if (stack.getItem() instanceof DyeableLeatherItem dyeable) {
                check(dyeable.getColor(stack) == f.armorColor(), who + ": el cuero de " + slot.getName() + " no tiene el color de la facción");
            }
            if (stack.getItem() instanceof BannerItem) {
                check(ItemStack.isSameItemSameTags(stack, f.banner()), who + ": el estandarte no es el de la facción");
            }
        }
        check(anyGear, who + ": apareció sin equipo");

        if (troop != null) {
            Troop.Resolved r = troop.resolve();
            check(r != null, who + ": la tropa no se resuelve");
            check(r.entity().equals(ForgeRegistries.ENTITY_TYPES.getKey(s.getType())),
                    who + ": es " + ForgeRegistries.ENTITY_TYPES.getKey(s.getType()) + " y debería ser " + r.entity());
            r.equipment().forEach((slot, item) -> check(s.getItemBySlot(slot).is(item) || item instanceof BannerItem,
                    who + ": en " + slot.getName() + " debería tener " + ForgeRegistries.ITEMS.getKey(item)));
            if (r.horse()) {
                check(s.getVehicle() instanceof Horse, who + ": debería estar a caballo");
            }
        }
    }

    // ------------------------------------------------------------------ aldeas

    /**
     * Una campana es una aldea: al buscar, se registra con la facción que toca y aparece la guarnición.
     * Buscar de nuevo no la duplica ni hace aparecer otra guarnición. En un bioma sin facción no pasa nada.
     * (El bioma del mundo de prueba es al azar, así que la facción se elige acá; el reparto por bioma
     * se prueba aparte en h2BiomaDecideFaccion.)
     */
    @GameTest(template = TEMPLATE)
    public static void h2CampanaEsAldea(GameTestHelper h) {
        h.setBlock(CENTER, Blocks.BELL);
        h.runAfterDelay(2, () -> {
            ServerLevel level = h.getLevel();
            BlockPos bell = h.absolutePos(CENTER);
            Faction expected = faction("norte");

            VillageData data = new VillageData(); // datos aparte: no se mezcla con las otras pruebas
            check(VillageManager.scan(level, bell, 4, data, biome -> null).isEmpty(), "se creó una aldea en un bioma sin facción");
            check(VillageManager.garrison(level, bell).isEmpty(), "apareció guarnición en un bioma sin facción");

            List<VillageData.Village> added = VillageManager.scan(level, bell, 4, data, biome -> expected);
            check(added.size() == 1, "se esperaba 1 aldea nueva y hubo " + added.size());
            VillageData.Village v = added.get(0);
            check(v.center().equals(bell), "el centro de la aldea no es la campana");
            check(v.faction().equals(expected.id()), "la aldea es de " + v.faction() + " y debería ser de " + expected.id());

            int alive = VillageManager.garrison(level, bell).size();
            check(alive >= expected.garrisonMin() && alive <= expected.garrisonMax(), "guarnición de " + alive + " soldados");

            check(VillageManager.scan(level, bell, 4, data, biome -> expected).isEmpty(), "la misma campana se registró dos veces");
            check(VillageManager.garrison(level, bell).size() == alive, "apareció otra guarnición en la misma aldea");
            cleanup(level, bell);
            h.succeed();
        });
    }

    /** Las aldeas sobreviven a guardar y cargar el mundo. */
    @GameTest(template = TEMPLATE)
    public static void h2AldeasSeGuardan(GameTestHelper h) {
        VillageData data = new VillageData();
        BlockPos a = new BlockPos(100, 64, -200);
        BlockPos b = new BlockPos(-500, 70, 900);
        data.add(new VillageData.Village(a, new ResourceLocation(WarbandCraft.MODID, "norte"), 123L));
        data.add(new VillageData.Village(b, new ResourceLocation(WarbandCraft.MODID, "sultanato"), 456L));

        VillageData loaded = VillageData.load(data.save(new CompoundTag()));
        check(loaded.all().size() == 2, "se cargaron " + loaded.all().size() + " aldeas de 2");
        VillageData.Village la = loaded.at(a);
        check(la != null && la.faction().getPath().equals("norte") && la.foundedTick() == 123L, "la aldea A no se cargó bien");
        check(loaded.nearest(new BlockPos(110, 64, -190), 48) == la, "nearest no encuentra la aldea A");
        check(loaded.nearest(new BlockPos(0, 64, 0), 48) == null, "nearest encontró una aldea demasiado lejos");
        h.succeed();
    }

    /** A un soldado de guarnición no se lo puede contratar; a un recluta común sí (se abre el menú de Recruits). */
    @GameTest(template = TEMPLATE)
    public static void h2GuarnicionNoSeContrata(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        Faction f = faction("valdren");
        Troop infantry = f.troops().stream().filter(t -> t.resolve() != null && !t.resolve().horse()).findFirst().orElse(null);
        check(infantry != null, "Valdren no tiene tropas a pie");
        AbstractRecruitEntity soldier = VillageManager.spawnTroop(level, center, f, infantry, RandomSource.create(1), 1);
        check(soldier != null, "no apareció el soldado");

        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        player.moveTo(soldier.getX() + 1, soldier.getY(), soldier.getZ());
        InteractionResult blocked = ForgeHooks.onInteractEntity(player, soldier, InteractionHand.MAIN_HAND);
        check(blocked != null, "el click al soldado de guarnición no se bloqueó");

        Entity plain = h.spawn(recruitType(), new BlockPos(4, 1, 4));
        InteractionResult free = ForgeHooks.onInteractEntity(player, plain, InteractionHand.MAIN_HAND);
        check(free == null, "también se bloqueó el click a un recluta común");
        cleanup(level, center);
        h.succeed();
    }

    // ------------------------------------------------------------------ defensa contra illagers

    /** Un pillager cerca de la aldea: el soldado de guarnición lo ve, va y le pega. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void h2GuarnicionAtacaPillager(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        AbstractRecruitEntity soldier = footSoldier(h, "valdren", 7);
        Entity e = h.spawn(EntityType.PILLAGER, new BlockPos(6, 1, 6));
        check(e instanceof net.minecraft.world.entity.monster.Pillager, "no apareció el pillager");
        net.minecraft.world.entity.monster.Pillager pillager = (net.minecraft.world.entity.monster.Pillager) e;
        pillager.setNoAi(true); // quieto y sin disparar: solo queremos ver si el soldado lo ataca
        float full = pillager.getHealth();
        h.succeedWhen(() -> {
            check(soldier.isAlive(), "el soldado murió");
            check(pillager.getHealth() < full || !pillager.isAlive(),
                    "el soldado no le pegó al pillager (objetivo actual: " + (soldier.getTarget() == null ? "ninguno" : soldier.getTarget().getName().getString()) + ")");
            cleanup(level, h.absolutePos(CENTER));
        });
    }

    /** Si un soldado va por un illager, los compañeros de su aldea que estaban sin pelear van también. */
    @GameTest(template = TEMPLATE)
    public static void h2GuarnicionAvisaCompaneros(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        AbstractRecruitEntity a = footSoldier(h, "norte", 8);
        AbstractRecruitEntity b = footSoldier(h, "norte", 9);
        Entity e = h.spawn(EntityType.PILLAGER, new BlockPos(6, 1, 6));
        net.minecraft.world.entity.monster.Pillager pillager = (net.minecraft.world.entity.monster.Pillager) e;
        pillager.setNoAi(true);
        a.setTarget(pillager);
        check(b.getTarget() == pillager, "el compañero no fue a ayudar");
        cleanup(level, h.absolutePos(CENTER));
        pillager.discard();
        h.succeed();
    }

    /** Un soldado a pie de la facción, de la "aldea" con centro en CENTER. */
    private static AbstractRecruitEntity footSoldier(GameTestHelper h, String factionName, long seed) {
        Faction f = faction(factionName);
        Troop troop = f.troops().stream().filter(t -> t.resolve() != null && !t.resolve().horse()
                && !t.resolve().inventory().stream().anyMatch(st -> st.is(net.minecraft.world.item.Items.ARROW)))
                .findFirst().orElse(null);
        check(troop != null, f.name() + " no tiene tropas cuerpo a cuerpo a pie");
        AbstractRecruitEntity s = VillageManager.spawnTroop(h.getLevel(), h.absolutePos(CENTER), f, troop, RandomSource.create(seed), 1);
        check(s != null, "no apareció el soldado");
        return s;
    }

    // ------------------------------------------------------------------ utilidades

    private static void expectBiome(GameTestHelper h, ResourceKey<Biome> biome, String expectedFaction) {
        Holder<Biome> holder = h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(biome);
        Faction f = FactionManager.INSTANCE.forBiome(holder);
        String got = f == null ? null : f.id().getPath();
        check(java.util.Objects.equals(got, expectedFaction),
                biome.location() + " debería ser de " + expectedFaction + " y es de " + got);
    }

    private static Faction faction(String name) {
        Faction f = FactionManager.INSTANCE.get(new ResourceLocation(WarbandCraft.MODID, name));
        check(f != null, "no se cargó la facción " + name + " (¿falla el JSON?)");
        return f;
    }

    private static EntityType<?> recruitType() {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        return type;
    }

    private static String biomeName(Holder<Biome> holder) {
        Optional<ResourceKey<Biome>> key = holder.unwrapKey();
        return key.map(k -> k.location().toString()).orElse("?");
    }

    /** Borra soldados y caballos de guarnición que hayan quedado fuera del área de la prueba. */
    private static void cleanup(ServerLevel level, BlockPos center) {
        for (AbstractRecruitEntity s : VillageManager.garrison(level, center)) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
