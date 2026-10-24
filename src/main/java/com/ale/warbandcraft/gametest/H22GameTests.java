package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.BuildCost;
import com.ale.warbandcraft.upgrade.Placement;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.upgrade.WallRepair;
import com.ale.warbandcraft.upgrade.Works;
import com.ale.warbandcraft.village.Quarters;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.FenceGateBlock;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Pruebas del Hito 22 (construir desde el modo Gestión): plantar un edificio donde se elige y girado, cobrar
 * materiales, no plantar sin materiales ni encima de cosas, cancelar devuelve, y la cuenta de cada bloque.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H22GameTests {
    private static final String TEMPLATE = "empty";
    /** 17×17 con aire hasta 17 de alto: los edificios con estilo ocupan hasta 13×13. */
    private static final String BIG = "empty_big";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);

    /** Una taberna girada 90°: la puerta queda al este, se cobra madera y al cancelar vuelve. */
    @GameTest(template = BIG)
    public static void plantRotatedTavernAndCancel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            int woodBefore = BuildCost.available(BuildCost.Material.WOOD, null, player);
            BlockPos corner = h.absolutePos(BlockPos.ZERO);

            Component msg = Upgrades.order(player, v.center(), Upgrade.TAVERN_1, corner, 1);
            TownData.Job job = town.job;
            check(job != null && job.upgrade == Upgrade.TAVERN_1, "no empezó la taberna: " + msg.getString());
            check("tavern".equals(job.siteKey) && town.rotations.getOrDefault("tavern", 0) == 1, "no anotó el terreno girado");
            BlockPos site = town.sites.get("tavern");
            check(site != null && site.getX() == corner.getX() && site.getZ() == corner.getZ(), "el terreno no está donde se eligió");
            int wood = job.paid.get(BuildCost.Material.WOOD);
            check(wood > 0 && BuildCost.available(BuildCost.Material.WOOD, null, player) == woodBefore - wood,
                    "no cobró la madera: pagó " + wood + ", antes " + woodBefore + ", ahora " + BuildCost.available(BuildCost.Material.WOOD, null, player));

            // Sin girar la puerta está en (6, 1, 4) del terreno de 13 mirando al sur; girada 90° queda en (8, 1, 6) mirando al oeste
            BlockState door = null;
            BlockPos doorPos = site.offset(8, 1, 6);
            for (int i = 0; i < job.size(); i++) if (job.pos(i).equals(doorPos)) door = job.state(i);
            check(door != null && door.getBlock() instanceof DoorBlock && door.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && door.getValue(DoorBlock.FACING) == Direction.WEST, "la puerta girada no está al este: " + door);

            Upgrades.cancel(player, v.center());
            check(town.job == null, "la obra no se canceló");
            check(!town.sites.containsKey("tavern"), "el terreno de la taberna cancelada sigue ocupado");
            int back = BuildCost.available(BuildCost.Material.WOOD, null, player);
            check(back >= woodBefore - 1, "no devolvió la madera: antes " + woodBefore + ", ahora " + back);
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Sin materiales no se planta (y avisa qué falta); encima de un cofre tampoco. */
    @GameTest(template = BIG)
    public static void noMaterialsOrBadPlace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            player.getInventory().add(new ItemStack(Items.EMERALD, 64));
            BlockPos corner = h.absolutePos(BlockPos.ZERO);
            Component msg = Upgrades.order(player, v.center(), Upgrade.CHAPEL, corner, 0);
            check(town.job == null, "empezó la capilla sin materiales");
            check(msg.getString().contains("Faltan materiales"), "no avisó qué falta: " + msg.getString());

            BlockPos chest = h.absolutePos(new BlockPos(3, 2, 3));
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            Placement.Spot spot = Placement.check(level, v, town, corner, "chapel", Upgrade.CHAPEL.siteSize());
            check(spot.problem() != null, "dejó plantar encima de un cofre");
            level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
            spot = Placement.check(level, v, town, corner, "chapel", Upgrade.CHAPEL.siteSize());
            check(spot.problem() == null && spot.site() != null, "sin el cofre no se puede: " + spot.problem());

            fill(player);
            msg = Upgrades.order(player, v.center(), Upgrade.CHAPEL, corner, 2);
            check(town.job != null && town.job.upgrade == Upgrade.CHAPEL, "con materiales no empezó la capilla: " + msg.getString());
            check(Upgrades.order(player, v.center(), Upgrade.HOUSE, corner.offset(12, 0, 0), 0).getString().length() > 0
                    && town.job.upgrade == Upgrade.CHAPEL, "empezó dos obras a la vez");
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** La cuenta: una cama = 3 de lana + 3 de madera, un tronco = 4 de madera, lo que ya está no se cobra. */
    @GameTest(template = TEMPLATE)
    public static void billCountsEachBlock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        BlockPos a = h.absolutePos(new BlockPos(2, 3, 2));
        Blueprints.Plan plan = new Blueprints.Plan();
        plan.put(a, Blocks.OAK_LOG.defaultBlockState());
        plan.put(a.east(), Blocks.WHITE_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        plan.put(a.east(2), Blocks.WHITE_BED.defaultBlockState());   // el pie no cuenta
        plan.put(a.south(), Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(a.south(), Blocks.COBBLESTONE.defaultBlockState());   // ya está: gratis
        BuildCost.Bill bill = BuildCost.of(level, plan, 3);
        check(bill.get(BuildCost.Material.WOOD) == 7, "madera: " + bill.get(BuildCost.Material.WOOD));
        check(bill.get(BuildCost.Material.WOOL) == 3, "lana: " + bill.get(BuildCost.Material.WOOL));
        check(bill.get(BuildCost.Material.STONE) == 0, "cobró la piedra que ya estaba");
        check(bill.emeralds() == 3, "esmeraldas: " + bill.emeralds());

        // Pagar con troncos da vuelto en tablones
        FakePlayer player = player(h);
        player.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
        player.getInventory().add(new ItemStack(Items.WHITE_WOOL, 3));
        check(BuildCost.missing(bill, null, player) == null, "con 2 troncos y 3 de lana no alcanza");
        BuildCost.pay(bill, null, player);
        check(player.getInventory().countItem(Items.OAK_LOG) == 0, "no usó los troncos");
        check(player.getInventory().countItem(Items.OAK_PLANKS) == 1, "el vuelto no es 1 tablón: " + player.getInventory().countItem(Items.OAK_PLANKS));
        level.setBlockAndUpdate(a.south(), Blocks.AIR.defaultBlockState());
        h.succeed();
    }

    /** Muralla dibujada de un tramo: una obra, quedan los troncos, la empalizada cuenta y un tronco roto se repara. Portón encima. */
    @GameTest(template = TEMPLATE)
    public static void drawnWallGateAndRepair(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        net.minecraft.world.entity.npc.Villager villager = null;
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            BlockPos a = h.absolutePos(new BlockPos(1, 1, 6)), b = h.absolutePos(new BlockPos(7, 1, 6));
            Component msg = Upgrades.orderWork(player, v.center(), "wall:1:" + a.getX() + "," + a.getZ() + ";" + b.getX() + "," + b.getZ());
            check(town.job != null && town.job.upgrade == Upgrade.WALL_1, "no empezó la empalizada: " + msg.getString());
            finishJob(level, town);
            check(town.has(Upgrade.PALISADE), "la empalizada no quedó anotada");
            int logs = 0;
            for (int x = 1; x <= 7; x++) for (int y = 2; y <= 4; y++) if (h.getBlockState(new BlockPos(x, y, 6)).is(Blocks.SPRUCE_LOG)) logs++;
            check(logs == 21, "la empalizada no tiene 7 columnas de 3: " + logs);
            check(town.defense.size() == 21, "no guardó los bloques para reparar: " + town.defense.size());

            h.setBlock(new BlockPos(2, 3, 6), Blocks.AIR);
            WallRepair.forget(v.center());
            check(WallRepair.missing(level, v).size() == 1, "no ve el tronco roto de la muralla dibujada");
            h.setBlock(new BlockPos(2, 3, 6), Blocks.SPRUCE_LOG);

            BlockPos mid = h.absolutePos(new BlockPos(4, 1, 6));
            msg = Upgrades.orderWork(player, v.center(), "gate:1:" + mid.getX() + "," + mid.getZ());
            check(town.job != null && town.job.upgrade == Upgrade.GATE_1, "no empezó el portón: " + msg.getString());
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(4, 2, 6)).getBlock() instanceof FenceGateBlock, "no hay puerta de cerco en el portón");
            check(town.gates.size() == 3, "el portón no anotó sus 3 puertas: " + town.gates.size());

            villager = EntityType.VILLAGER.create(level);
            BlockPos near = h.absolutePos(new BlockPos(4, 2, 7));
            villager.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0, 0);
            level.addFreshEntity(villager);
            Works.tickGates(level);
            check(h.getBlockState(new BlockPos(4, 2, 6)).getValue(FenceGateBlock.OPEN), "el portón no se abrió para un aldeano");
            villager.discard();
            villager = null;
            Works.tickGates(level);
            check(!h.getBlockState(new BlockPos(4, 2, 6)).getValue(FenceGateBlock.OPEN), "el portón no se cerró solo");
        } finally {
            if (villager != null) villager.discard();
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Demoler un tramo de muralla dibujada: se saca lo que está cerca del click y deja de contar para reparar. */
    @GameTest(template = TEMPLATE)
    public static void demolishWallStretch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            BlockPos a = h.absolutePos(new BlockPos(1, 1, 6)), b = h.absolutePos(new BlockPos(7, 1, 6));
            Upgrades.orderWork(player, v.center(), "wall:1:" + a.getX() + "," + a.getZ() + ";" + b.getX() + "," + b.getZ());
            finishJob(level, town);
            check(town.defense.size() == 21, "la empalizada no quedó entera: " + town.defense.size());
            BlockPos cut = h.absolutePos(new BlockPos(2, 1, 6));
            Component msg = Upgrades.orderWork(player, v.center(), "demolish:wall:" + cut.getX() + "," + cut.getZ());
            check(town.job != null && town.job.upgrade == Upgrade.DEMOLISH, "no empezó a demoler la muralla: " + msg.getString());
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(2, 3, 6)).isAir() && h.getBlockState(new BlockPos(6, 3, 6)).isAir(), "el tramo sigue en pie");
            check(h.getBlockState(new BlockPos(7, 3, 6)).is(Blocks.SPRUCE_LOG), "se llevó también lo que estaba lejos del click");
            check(town.defense.size() == 3, "lo demolido sigue anotado para reparar: " + town.defense.size());
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Torre de madera suelta: queda anotada (arriba van los arqueros) y demolerla la saca. */
    @GameTest(template = TEMPLATE)
    public static void towerAndDemolish(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            BlockPos c = h.absolutePos(new BlockPos(4, 1, 4));
            Component msg = Upgrades.orderWork(player, v.center(), "tower:1:" + c.getX() + "," + c.getZ());
            check(town.job != null && town.job.upgrade == Upgrade.TOWER_1, "no empezó la torre: " + msg.getString());
            finishJob(level, town);
            check(town.towers.size() == 1 && town.has(Upgrade.TOWERS), "la torre no quedó anotada");
            BlockPos t = town.towers.get(0);
            check(level.getBlockState(t.offset(2, 4, 2)).is(Blocks.SPRUCE_LOG), "la torre no es de troncos: " + level.getBlockState(t.offset(2, 4, 2)));
            check(!Quarters.drawnTowerPosts(level, town).isEmpty(), "no hay puestos arriba de la torre");

            msg = Upgrades.orderWork(player, v.center(), "demolish:tower:" + t.asLong());
            check(town.job != null && town.job.upgrade == Upgrade.DEMOLISH, "no empezó a demoler: " + msg.getString());
            finishJob(level, town);
            check(town.towers.isEmpty(), "la torre demolida sigue anotada");
            check(level.getBlockState(t.offset(2, 4, 2)).isAir(), "la torre sigue en pie");
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Allanar corta una loma; rellenar pone tierra (y la cobra) hasta la altura marcada. */
    @GameTest(template = TEMPLATE)
    public static void levelAndFillGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            FakePlayer player = player(h);
            fill(player);
            for (int x = 2; x <= 3; x++) for (int z = 2; z <= 3; z++) for (int y = 2; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.DIRT);
            BlockPos a = h.absolutePos(new BlockPos(1, 1, 1)), b = h.absolutePos(new BlockPos(4, 1, 4));
            Component msg = Upgrades.orderWork(player, v.center(), "earth:0:" + a.getX() + "," + a.getZ() + "," + b.getX() + "," + b.getZ() + "," + a.getY());
            check(town.job != null && town.job.upgrade == Upgrade.EARTH_LEVEL, "no empezó a allanar: " + msg.getString());
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(2, 2, 2)).isAir() && h.getBlockState(new BlockPos(3, 3, 3)).isAir(), "la loma sigue ahí");

            int dirt = player.getInventory().countItem(Items.DIRT);
            BlockPos c = h.absolutePos(new BlockPos(6, 1, 6)), d = h.absolutePos(new BlockPos(7, 1, 7));
            msg = Upgrades.orderWork(player, v.center(), "earth:1:" + c.getX() + "," + c.getZ() + "," + d.getX() + "," + d.getZ() + "," + (c.getY() + 1));
            check(town.job != null && town.job.upgrade == Upgrade.EARTH_FILL, "no empezó a rellenar: " + msg.getString());
            check(player.getInventory().countItem(Items.DIRT) == dirt - 4, "no cobró 4 de tierra: " + (dirt - player.getInventory().countItem(Items.DIRT)));
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(6, 2, 6)).is(Blocks.GRASS_BLOCK), "no rellenó");
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Termina la obra de golpe (como si hubiera pasado su tiempo). */
    private static void finishJob(ServerLevel level, TownData.Town town) {
        TownData.Job j = town.job;
        TownData.Job done = new TownData.Job(j.upgrade, level.getGameTime() - j.durationTicks - 10, j.durationTicks, j.positions, j.states, j.palette);
        done.paid = j.paid;
        done.siteKey = j.siteKey;
        done.builder = j.builder;
        town.job = done;
        Upgrades.tick(level);
        if (town.job != null) throw new GameTestAssertException("la obra no terminó: " + town.job.placed + " / " + town.job.size());
    }

    // ------------------------------------------------------------------ ayudas

    /** Las pruebas vienen con un techo de barreras: el edificio necesita lugar arriba. */
    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 17; x++) for (int z = -1; z <= 17; z++) for (int y = 2; y <= 20; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "prueba_h22"), 0)
                .withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static void cleanup(GameTestHelper h, VillageData.Village v) {
        TownData.Town t = TownData.get(h.getLevel()).at(v.center());
        if (t != null && t.job != null && t.job.builder != null) {
            var e = h.getLevel().getEntity(t.job.builder);
            if (e != null) e.discard();
        }
        TownData.get(h.getLevel()).remove(v.center());
        VillageData.get(h.getLevel()).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    private static FakePlayer player(GameTestHelper h) {
        FakePlayer player = FakePlayerFactory.get(h.getLevel(), new GameProfile(owner(h), "ale-h22"));
        player.getInventory().clearContent();
        return player;
    }

    /** Materiales de sobra en el inventario. */
    private static void fill(FakePlayer player) {
        for (int i = 0; i < 8; i++) player.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
        for (int i = 0; i < 12; i++) player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 3; i++) player.getInventory().add(new ItemStack(Items.DIRT, 64));
        player.getInventory().add(new ItemStack(Items.GLASS, 64));
        player.getInventory().add(new ItemStack(Items.WHITE_WOOL, 64));
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().add(new ItemStack(Items.EMERALD, 64));
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h22@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
