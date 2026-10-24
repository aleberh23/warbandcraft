package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageEconomy;
import com.ale.warbandcraft.village.VillageStock;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pruebas del Hito 12 (mejoras visibles): el anillo se acomoda al terreno (puertas en caminos, huecos en casas),
 * una obra avanza con el tiempo y termina con su constructor, requisitos y costo, y los efectos de las mejoras.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H12GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);

    @GameTest(template = TEMPLATE)
    public static void ringLeavesGatesAndGaps(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        int r = 4;
        // Las pruebas vienen con un techo de barreras: el muro necesita cielo
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 9; z++) for (int y = 2; y <= 8; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        List<int[]> cols = Blueprints.ringColumns(center, r);
        check(cols.size() >= 16, "el anillo tiene pocas columnas: " + cols.size());

        int[] gate = cols.get(0);
        int[] house = cols.get(cols.size() / 2);
        Integer gg = Blueprints.groundY(level, gate[0], gate[1], center.getY());
        Integer hg = Blueprints.groundY(level, house[0], house[1], center.getY());
        check(gg != null && hg != null, "no encontró el piso");
        level.setBlockAndUpdate(new BlockPos(gate[0], gg, gate[1]), Blocks.DIRT_PATH.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(house[0], hg + 1, house[1]), Blocks.OAK_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(house[0], hg + 2, house[1]), Blocks.OAK_PLANKS.defaultBlockState());

        Blueprints.Plan plan = Blueprints.ring(level, center, r, false);
        check(!hasColumn(plan, gate[0], gate[1]), "puso empalizada sobre el camino");
        int[] next = cols.get(1);
        check(!hasColumn(plan, next[0], next[1]), "la puerta no tiene 3 de ancho");
        check(!hasColumn(plan, house[0], house[1]), "puso empalizada adentro de una casa");
        int[] wall = cols.get(cols.size() / 4);
        Integer wg = Blueprints.groundY(level, wall[0], wall[1], center.getY());
        check(wg != null, "sin piso en el muro");
        for (int dy = 1; dy <= 3; dy++) {
            BlockState s = plan.blocks.get(new BlockPos(wall[0], wg + dy, wall[1]));
            check(s != null && s.is(Blocks.SPRUCE_LOG), "falta un tronco de la empalizada a " + dy + " del piso; plan " + plan.size()
                    + ", piso " + h.relativePos(new BlockPos(wall[0], wg, wall[1])) + " = " + level.getBlockState(new BlockPos(wall[0], wg, wall[1]))
                    + ", arriba " + level.getBlockState(new BlockPos(wall[0], wg + 1, wall[1])) + " / " + level.getBlockState(new BlockPos(wall[0], wg + 4, wall[1])));
        }

        Blueprints.Plan stone = Blueprints.ring(level, center, r, true);
        BlockState top = stone.blocks.get(new BlockPos(wall[0], wg + 4, wall[1]));
        check(top != null && top.is(Blocks.STONE_BRICKS), "la muralla no tiene 4 de alto");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void jobBuildsAndFinishes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        TownData data = TownData.get(level);
        TownData.Town town = data.getOrCreate(center);
        try {
            BlockPos site = site(h);
            Blueprints.Plan plan = Blueprints.building(level, Upgrade.GRANARY, site);
            check(plan.size() > 50, "el granero tiene muy pocos bloques: " + plan.size());
            Upgrades.start(level, town, Upgrade.GRANARY, plan);
            TownData.Job job = town.job;
            check(job != null && job.durationTicks >= Upgrades.MIN_TICKS && job.durationTicks <= Upgrades.MAX_TICKS,
                    "la obra no dura entre 1 y 2 minutos");

            // A mitad de camino: la mitad de los bloques y el constructor en la obra
            town.job = shifted(level, job, job.durationTicks / 2);
            Upgrades.tick(level);
            TownData.Job half = town.job;
            check(half != null && half.placed > 0 && half.placed < half.size(), "a mitad de obra puso " + (half == null ? "todo" : half.placed));
            check(half.builder != null && level.getEntity(half.builder) != null, "no apareció el constructor");
            UUID builder = half.builder;

            // Terminada: todo puesto, la mejora anotada y el constructor se fue
            town.job = shifted(level, half, half.durationTicks + 1);
            town.job.placed = half.placed;
            town.job.builder = builder;
            Upgrades.tick(level);
            check(town.job == null, "la obra no terminó");
            check(town.has(Upgrade.GRANARY), "el granero no quedó anotado");
            Entity e = level.getEntity(builder);
            check(e == null || e.isRemoved(), "el constructor sigue ahí");
            check(level.getBlockState(site.offset(2, 1, 4)).is(Blocks.HAY_BLOCK), "no hay heno en el granero");
        } finally {
            data.remove(center);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void orderChecksRulesAndPays(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h12"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData data = TownData.get(level);
        try {
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner, "ale-h12"));
            player.getInventory().clearContent();
            Upgrades.order(player, center, Upgrade.WALL);
            check(data.at(center) == null || data.at(center).job == null, "empezó una muralla sin empalizada");
            check(Upgrades.blocker(null, Upgrade.GRANARY) != null, "se puede un granero sin taller del constructor");

            TownData.Town town = data.getOrCreate(center);
            town.built.add(Upgrade.BUILDER_1);
            town.sites.put(Upgrade.GRANARY.siteKey(), site(h));
            Upgrades.order(player, center, Upgrade.GRANARY);
            check(town.job == null, "empezó sin esmeraldas");

            VillageData.get(level).setStock(center, new VillageStock.Stock(10, 0, 0));
            player.getInventory().add(new ItemStack(Items.EMERALD, 20));
            for (int i = 0; i < 4; i++) player.getInventory().add(new ItemStack(Items.OAK_LOG, 64));   // H22: los materiales
            for (int i = 0; i < 5; i++) player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // estilos: pizarra y terracota = piedra
            player.getInventory().add(new ItemStack(Items.DIRT, 64));
            player.getInventory().add(new ItemStack(Items.GLASS, 16));
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 4));   // los faroles
            Component msg = Upgrades.order(player, center, Upgrade.GRANARY);
            check(town.job != null && town.job.upgrade == Upgrade.GRANARY, "no empezó el granero: " + msg.getString());
            check(VillageStock.total(level, v).emeralds() == 0, "no usó las esmeraldas del almacén primero");
            check(player.getInventory().countItem(Items.EMERALD) == 20 - (Upgrade.GRANARY.cost - 10), "no cobró el resto del inventario");
            Upgrades.order(player, center, Upgrade.BARRACKS);
            check(town.job.upgrade == Upgrade.GRANARY, "empezó dos obras a la vez");
        } finally {
            data.remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void upgradesHaveEffects(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h12b"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData data = TownData.get(level);
        h.setBlock(new BlockPos(1, 1, 1), ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(new BlockPos(1, 1, 1)) instanceof CampBlockEntity camp)) throw new GameTestAssertException("sin Campamento");
        camp.setOwner(owner);
        try {
            VillageStock.Stock before = VillageStock.daily(level, v);
            check(Barracks.capacity(level, camp.getBlockPos()) == BarracksData.CAPACITY, "capacidad sin cuartel");

            TownData.Town town = data.getOrCreate(center);
            town.built.addAll(List.of(Upgrade.TOWN, Upgrade.MARKET, Upgrade.GRANARY, Upgrade.SMITHY, Upgrade.BARRACKS));
            VillageStock.Stock after = VillageStock.daily(level, v);
            int tax = VillageEconomy.taxOf(level, v);
            check(after.emeralds() == Math.round(tax * 1.5) + Upgrades.MARKET_EMERALDS, "esmeraldas de pueblo + mercado: " + after.emeralds());
            check(after.food() == Math.round(before.food() * 1.5) + Upgrades.GRANARY_FOOD, "comida de pueblo + granero: " + after.food());
            check(after.armament() == Upgrades.SMITHY_ARMAMENT, "armamento de la herrería: " + after.armament());
            VillageStock.Stock cap = VillageStock.cap(level, v);
            check(cap.food() > cap.emeralds(), "el granero no agranda el tope de comida");
            check(Barracks.capacity(level, camp.getBlockPos()) == BarracksData.CAPACITY + Upgrades.BARRACKS_BONUS, "el cuartel no suma lugar");
        } finally {
            data.remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** El terreno de 9×9 de la prueba (su esquina, con y = el piso). */
    private static BlockPos site(GameTestHelper h) {
        BlockPos corner = h.absolutePos(new BlockPos(0, 0, 0));
        Integer g = Blueprints.groundY(h.getLevel(), corner.getX() + 4, corner.getZ() + 4, h.absolutePos(CENTER).getY());
        check(g != null, "sin piso en la prueba");
        return new BlockPos(corner.getX(), g, corner.getZ());
    }

    /** La misma obra pero empezada {@code ago} ticks antes. */
    private static TownData.Job shifted(ServerLevel level, TownData.Job job, int ago) {
        TownData.Job j = new TownData.Job(job.upgrade, level.getGameTime() - ago, job.durationTicks, job.positions, job.states, job.palette);
        j.placed = job.placed;
        j.builder = job.builder;
        return j;
    }

    private static boolean hasColumn(Blueprints.Plan plan, int x, int z) {
        for (Map.Entry<BlockPos, BlockState> e : plan.blocks.entrySet()) {
            if (e.getKey().getX() == x && e.getKey().getZ() == z) return true;
        }
        return false;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h12@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
