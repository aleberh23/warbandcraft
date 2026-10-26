package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.upgrade.Heraldry;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.village.Maintenance;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Pedidos de Ale: portón donde un camino cruza una muralla, pasar toda la empalizada / las torres a piedra de un click,
 * el de Mantenimiento (estandarte al lado del portón) y los colores de la facción en vidrios y estandartes.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class DecorGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);

    /** Una empalizada dibujada sobre un camino de tierra deja un portón ahí. */
    @GameTest(template = TEMPLATE)
    public static void wallOverPathGetsGate(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        try {
            TownData.Town town = TownData.get(level).getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            for (int z = 5; z <= 7; z++) h.setBlock(new BlockPos(4, 1, z), Blocks.DIRT_PATH);
            FakePlayer player = player(h);
            fill(player);
            BlockPos a = h.absolutePos(new BlockPos(0, 1, 6)), b = h.absolutePos(new BlockPos(8, 1, 6));
            Component msg = Upgrades.orderWork(player, v.center(), "wall:1:" + a.getX() + "," + a.getZ() + ";" + b.getX() + "," + b.getZ());
            check(town.job != null, "no empezó la empalizada: " + msg.getString());
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(4, 2, 6)).getBlock() instanceof FenceGateBlock,
                    "no hay portón sobre el camino: " + h.getBlockState(new BlockPos(4, 2, 6)));
            check(!town.gates.isEmpty(), "el portón del camino no quedó anotado");
            check(h.getBlockState(new BlockPos(1, 2, 6)).is(Blocks.SPRUCE_LOG), "lejos del camino no hay empalizada");
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** De un click: la empalizada a piedra (con portón) y la torre de madera a piedra. */
    @GameTest(template = TEMPLATE)
    public static void palisadeAndTowerToStone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        try {
            TownData.Town town = TownData.get(level).getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            BlockPos a = h.absolutePos(new BlockPos(0, 1, 7)), b = h.absolutePos(new BlockPos(8, 1, 7));
            Upgrades.orderWork(player, v.center(), "wall:1:" + a.getX() + "," + a.getZ() + ";" + b.getX() + "," + b.getZ());
            finishJob(level, town);
            BlockPos mid = h.absolutePos(new BlockPos(4, 1, 7));
            Upgrades.orderWork(player, v.center(), "gate:1:" + mid.getX() + "," + mid.getZ());
            finishJob(level, town);
            BlockPos c = h.absolutePos(new BlockPos(4, 1, 2));
            Component msg = Upgrades.orderWork(player, v.center(), "tower:1:" + c.getX() + "," + c.getZ());
            check(town.job != null, "no empezó la torre: " + msg.getString());
            finishJob(level, town);
            BlockPos t = town.towers.get(0);
            check(level.getBlockState(t.offset(2, 1, 2)).is(Blocks.SPRUCE_LOG), "la torre no es de troncos");

            msg = Upgrades.orderWork(player, v.center(), "stoneup:walls");
            check(town.job != null && town.job.upgrade == Upgrade.STONE_WALLS, "no empezó a pasar la empalizada a piedra: " + msg.getString());
            finishJob(level, town);
            check(h.getBlockState(new BlockPos(1, 3, 7)).is(Blocks.STONE_BRICKS), "la empalizada no pasó a ladrillo: " + h.getBlockState(new BlockPos(1, 3, 7)));
            check(h.getBlockState(new BlockPos(4, 2, 7)).is(Blocks.DARK_OAK_FENCE_GATE), "el portón no pasó al de piedra");
            check(town.has(Upgrade.WALL), "no quedó anotada la muralla");
            check(town.defense.get(h.absolutePos(new BlockPos(1, 3, 7)).asLong()).is(Blocks.STONE_BRICKS), "reparar volvería a poner troncos");
            check(Upgrades.orderWork(player, v.center(), "stoneup:walls") != null && town.job == null, "quiere volver a pasarla a piedra");

            msg = Upgrades.orderWork(player, v.center(), "stoneup:towers");
            check(town.job != null && town.job.upgrade == Upgrade.STONE_TOWERS, "no empezó a pasar la torre a piedra: " + msg.getString());
            finishJob(level, town);
            check(level.getBlockState(t.offset(2, 1, 2)).is(Blocks.STONE_BRICKS), "la torre sigue de madera: " + level.getBlockState(t.offset(2, 1, 2)));
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** El de Mantenimiento cuelga un estandarte al lado del portón y lo cobra del almacén; sin nada, no hace nada. */
    @GameTest(template = TEMPLATE)
    public static void maintenanceHangsGateBanner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        VillageData data = VillageData.get(level);
        BlockPos barrelPos = h.absolutePos(new BlockPos(1, 2, 1));
        try {
            TownData.Town town = TownData.get(level).getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = player(h);
            fill(player);
            BlockPos a = h.absolutePos(new BlockPos(0, 1, 7)), b = h.absolutePos(new BlockPos(8, 1, 7));
            Upgrades.orderWork(player, v.center(), "wall:1:" + a.getX() + "," + a.getZ() + ";" + b.getX() + "," + b.getZ());
            finishJob(level, town);
            BlockPos mid = h.absolutePos(new BlockPos(4, 1, 7));
            Upgrades.orderWork(player, v.center(), "gate:1:" + mid.getX() + "," + mid.getZ());
            finishJob(level, town);
            check(!Maintenance.hasShed(town), "tiene caseta sin haberla hecho");
            town.built.add(Upgrade.MAINTENANCE);

            level.setBlockAndUpdate(barrelPos, Blocks.BARREL.defaultBlockState());
            if (!(level.getBlockEntity(barrelPos) instanceof BarrelBlockEntity)) throw new GameTestAssertException("sin barril");
            data.setStorehouse(v.center(), barrelPos);
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no hay Campamento de la aldea");

            Maintenance.Task task = Maintenance.next(level, v, town);
            check(task != null && task.kind() == Maintenance.Kind.BANNER, "no quiere poner un estandarte en el portón: " + (task == null ? "nada" : task.what()));
            check(Maintenance.apply(level, v, town, task) != null, "lo puso sin materiales");
            camp.insert(new ItemStack(Items.OAK_PLANKS, 16));
            camp.insert(new ItemStack(Items.EMERALD, 8));
            check(Maintenance.apply(level, v, town, task) == null, "no lo puso con materiales");
            BlockPos spot = task.plan().blocks.keySet().iterator().next();
            check(level.getBlockState(spot).getBlock() instanceof WallBannerBlock, "no hay estandarte: " + level.getBlockState(spot));
            check(town.decor.contains(spot), "el estandarte no quedó anotado para cambiarle el color");
            check(camp.count(Items.EMERALD) < 8, "no cobró esmeraldas");
        } finally {
            TownData.get(level).remove(v.center());
            VillageStorehouse.forget(level, v);
            Maintenance.clear();
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Vidrios y estandartes toman el color de la facción (también los que ya estaban). */
    @GameTest(template = TEMPLATE)
    public static void heraldryTintsGlassAndBanners(GameTestHelper h) {
        BlockState pane = Heraldry.tinted(Blocks.GLASS_PANE.defaultBlockState(), DyeColor.RED);
        check(pane != null && pane.is(Blocks.RED_STAINED_GLASS_PANE), "el vidrio no pasó a rojo: " + pane);
        BlockState glass = Heraldry.tinted(Blocks.BLUE_STAINED_GLASS.defaultBlockState(), DyeColor.RED);
        check(glass != null && glass.is(Blocks.RED_STAINED_GLASS), "el vidrio teñido no cambió: " + glass);
        BlockState banner = Heraldry.tinted(Blocks.WHITE_WALL_BANNER.defaultBlockState()
                .setValue(WallBannerBlock.FACING, net.minecraft.core.Direction.EAST), DyeColor.RED);
        check(banner != null && banner.is(Blocks.RED_WALL_BANNER) && banner.getValue(WallBannerBlock.FACING) == net.minecraft.core.Direction.EAST,
                "el estandarte no cambió de color o se dio vuelta: " + banner);
        check(Heraldry.tinted(Blocks.STONE.defaultBlockState(), DyeColor.RED) == null, "tiñó una piedra");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static void finishJob(ServerLevel level, TownData.Town town) {
        TownData.Job j = town.job;
        if (j == null) throw new GameTestAssertException("no hay obra");
        TownData.Job done = new TownData.Job(j.upgrade, level.getGameTime() - j.durationTicks - 10, j.durationTicks, j.positions, j.states, j.palette);
        done.paid = j.paid;
        done.siteKey = j.siteKey;
        done.builder = j.builder;
        town.job = done;
        Upgrades.tick(level);
        if (town.job != null) throw new GameTestAssertException("la obra no terminó: " + town.job.placed + " / " + town.job.size());
    }

    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 10; x++) for (int z = -1; z <= 10; z++) for (int y = 2; y <= 20; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "prueba_decor"), 0)
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
        FakePlayer player = FakePlayerFactory.get(h.getLevel(), new GameProfile(owner(h), "ale-decor"));
        player.getInventory().clearContent();
        return player;
    }

    private static void fill(FakePlayer player) {
        for (int i = 0; i < 8; i++) player.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
        for (int i = 0; i < 12; i++) player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
        player.getInventory().add(new ItemStack(Items.EMERALD, 64));
        player.getInventory().add(new ItemStack(Items.EMERALD, 64));
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-decor@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
