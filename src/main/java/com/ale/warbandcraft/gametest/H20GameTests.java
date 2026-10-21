package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.army.ArmyData;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.invasion.Ambush;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Registry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 20 (ejércitos de campaña): armar con dos barracones, llegar formados (infantería adelante, arqueros
 * atrás), licenciar de vuelta a los barracones, biomas aptos y la carga contra una marcha enemiga.
 * La formación se achica para que entre en el área de 9×9 de la prueba.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H20GameTests {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");
    private static final String INFANTRY = "warbandcraft:infante#0", ARCHERS = "warbandcraft:arquero#0";

    @GameTest(template = TEMPLATE, batch = "h20_army")
    public static void armyFormsAndGoesHome(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        CampBlockEntity a = camp(h, new BlockPos(0, 2, 8));
        CampBlockEntity b = camp(h, new BlockPos(8, 2, 8));
        fill(h, a, "warbandcraft:infante", 6, false);
        fill(h, a, "warbandcraft:arquero", 4, true);
        fill(h, b, "warbandcraft:infante", 4, false);
        check(BarracksData.get(level).count(a.getBlockPos()) == 10 && BarracksData.get(level).count(b.getBlockPos()) == 4, "no se llenaron los barracones");

        Armies.create(level, owner, "Hueste");
        ArmyData.Army army = ArmyData.get(level).of(owner).get(0);
        Armies.assign(level, army, a.getBlockPos(), INFANTRY, Integer.MAX_VALUE);
        Armies.assign(level, army, a.getBlockPos(), ARCHERS, 2);
        Armies.assign(level, army, a.getBlockPos(), ARCHERS, 10);   // se recorta a los que hay
        Armies.assign(level, army, b.getBlockPos(), INFANTRY, 4);
        check(army.total() == 14, "el ejército debería tener 14, tiene " + army.total());
        check(Armies.ready(level, army) == 14, "deberían salir 14");

        double spacing = Armies.SPACING, rank = Armies.RANK_GAP, behind = Armies.RANGED_BEHIND;
        Armies.SPACING = 1.0;
        Armies.RANK_GAP = 1.0;
        Armies.RANGED_BEHIND = 1.0;
        try {
            Vec3 anchor = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 2, 1)));
            Vec3 facing = new Vec3(0, 0, 1);
            List<AbstractRecruitEntity> out = Armies.arrive(level, army, anchor, facing);
            check(out.size() == 14, "aparecieron " + out.size() + " en vez de 14");
            check(army.state == ArmyData.State.FIELD && army.members.size() == 14, "el ejército no quedó en el campo");
            check(BarracksData.get(level).count(a.getBlockPos()) == 0 && BarracksData.get(level).count(b.getBlockPos()) == 0,
                    "los barracones no quedaron vacíos");
            double worstInfantry = Double.MAX_VALUE, bestArcher = -Double.MAX_VALUE;
            for (AbstractRecruitEntity s : out) {
                check(s.getPersistentData().contains(Armies.TAG_ARMY), "un soldado no tiene la marca del ejército");
                double depth = s.position().dot(facing);
                if (s.getMainHandItem().is(Items.BOW)) bestArcher = Math.max(bestArcher, depth);
                else worstInfantry = Math.min(worstInfantry, depth);
                check(s.getFollowState() == Barracks.BACK_TO_POS && s.getHoldPos() != null, "un soldado no está manteniendo su puesto");
            }
            check(worstInfantry > bestArcher, "la infantería no quedó delante de los arqueros (" + worstInfantry + " vs " + bestArcher + ")");

            // Licenciar: vuelven cada uno a su barracón
            Armies.dismiss(level, army);
            check(army.state == ArmyData.State.IDLE && army.members.isEmpty(), "el ejército no volvió a casa");
            check(BarracksData.get(level).count(a.getBlockPos()) == 10, "el barracón A no recuperó a sus 10");
            check(BarracksData.get(level).count(b.getBlockPos()) == 4, "el barracón B no recuperó a sus 4");
            check(level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16),
                    s -> s.getPersistentData().contains(Armies.TAG_ARMY)).isEmpty(), "quedaron soldados del ejército sueltos");
        } finally {
            Armies.SPACING = spacing;
            Armies.RANK_GAP = rank;
            Armies.RANGED_BEHIND = behind;
            ArmyData.get(level).remove(army.id);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void battlefieldBiomes(GameTestHelper h) {
        Registry<Biome> biomes = h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        check(Armies.biomeOk(biomes.getHolderOrThrow(Biomes.PLAINS)), "las llanuras deberían servir");
        check(Armies.biomeOk(biomes.getHolderOrThrow(Biomes.DESERT)), "el desierto debería servir");
        check(!Armies.biomeOk(biomes.getHolderOrThrow(Biomes.OCEAN)), "el océano no debería servir");
        check(!Armies.biomeOk(biomes.getHolderOrThrow(Biomes.SWAMP)), "el pantano no debería servir");
        check(!Armies.biomeOk(biomes.getHolderOrThrow(Biomes.JUNGLE)), "la jungla no debería servir");
        h.succeed();
    }

    /** Cargar con una marcha enemiga cerca: aparece y se vuelve una batalla de campo. */
    @GameTest(template = TEMPLATE, batch = "h20_charge")
    public static void chargeSpringsEnemyMarch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        CampBlockEntity a = camp(h, new BlockPos(0, 2, 8));
        fill(h, a, "warbandcraft:infante", 2, false);
        Armies.create(level, owner, "Vanguardia");
        ArmyData.Army army = ArmyData.get(level).of(owner).get(0);
        Armies.assign(level, army, a.getBlockPos(), INFANTRY, Integer.MAX_VALUE);
        Relations rel = Relations.get(level.getServer());
        rel.add(owner, FACTION, -200);
        InvasionData data = InvasionData.get(level);
        BlockPos center = h.absolutePos(new BlockPos(4, 2, 4));
        long now = level.getGameTime();
        InvasionData.March m = new InvasionData.March(UUID.randomUUID(), FACTION, center.east(400), center.east(10), 3, now, now + 1_000_000);
        data.putMarch(m);
        List<Entity> spawned = new java.util.ArrayList<>();
        try {
            Armies.arrive(level, army, Vec3.atBottomCenterOf(center), new Vec3(0, 0, 1));
            Armies.charge(level, army, null);
            check(army.mode == ArmyData.Mode.CHARGE, "el ejército no está cargando");
            check(!army.foes.isEmpty(), "la marcha no se volvió batalla");
            check(Ambush.fieldNear(level, center) > 0, "no apareció el ejército enemigo");
            for (UUID id : army.foes) {
                Entity e = level.getEntity(id);
                if (e != null) spawned.add(e);
            }
        } finally {
            data.removeMarch(m.id);
            rel.add(owner, FACTION, 200);
            for (Entity e : spawned) {
                if (e.getVehicle() != null) e.getVehicle().discard();
                e.discard();
            }
            Armies.dismiss(level, army);
            ArmyData.get(level).remove(army.id);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static CampBlockEntity camp(GameTestHelper h, BlockPos pos) {
        h.setBlock(pos, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(pos) instanceof CampBlockEntity camp)) throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        camp.setOwner(owner(h));
        return camp;
    }

    /** Guarda {@code n} soldados de la clase {@code kit} (nivel 0) en el barracón del Campamento. */
    private static void fill(GameTestHelper h, CampBlockEntity camp, String kit, int n, boolean bow) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        for (int i = 0; i < n; i++) {
            Entity e = h.spawn(type, new BlockPos(4, 2, 4));
            check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
            AbstractRecruitEntity r = (AbstractRecruitEntity) e;
            for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
            r.getInventory().clearContent();
            r.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bow ? Items.BOW : Items.IRON_SWORD));
            r.setOwnerUUID(Optional.of(owner(h)));
            r.setIsOwned(true);
            r.getPersistentData().putString(ClassAssigner.TAG_CLASS, kit);
            r.getPersistentData().putInt(ClassAssigner.TAG_TIER, 0);
            check(Barracks.store(h.getLevel(), r, camp.getBlockPos(), true) == Barracks.StoreResult.OK, "no se pudo guardar un soldado");
        }
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h20@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
