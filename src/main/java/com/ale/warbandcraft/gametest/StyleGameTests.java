package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.Placement;
import com.ale.warbandcraft.upgrade.Style;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Pruebas de los edificios con estilo (pedido de Ale): la Taberna 3 es de dos pisos con el dormitorio arriba, cada estilo
 * usa sus materiales y plantar la Taberna 1 ya reserva el terreno de la 3.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class StyleGameTests {
    private static final String BIG = "empty_big";
    private static final BlockPos CENTER = new BlockPos(8, 1, 8);

    /** La Taberna 3 levantada de una: puerta, escalera, nueve camas arriba, techo del Norte y el Tabernero detrás de la barra. */
    @GameTest(template = BIG)
    public static void tavern3HasTwoFloorsAndDorm(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        BlockPos center = h.absolutePos(CENTER);
        TownData data = TownData.get(level);
        TownData.Town town = data.getOrCreate(center);
        try {
            BlockPos site = h.absolutePos(new BlockPos(2, 1, 2));   // el piso de piedra
            Blueprints.Plan plan = Blueprints.building(level, Upgrade.TAVERN_3, site, 0, Style.NORTE);
            town.putSite(Upgrade.TAVERN_3.siteKey(), site, Upgrade.TAVERN_3.siteSize(), 0);
            Upgrades.buildNow(level, town, Upgrade.TAVERN_3, plan);
            check(town.has(Upgrade.TAVERN_3), "la taberna no quedó hecha");

            int upstairs = 0, downstairs = 0, stairs = 0, roof = 0;
            for (BlockPos p : BlockPos.betweenClosed(site, site.offset(12, 16, 12))) {
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) {
                    if (p.getY() == site.getY() + 6) upstairs++;
                    else downstairs++;
                }
                if (s.is(Blocks.SPRUCE_STAIRS) && p.getX() == site.getX() + 10) stairs++;
                if (s.is(Blocks.DARK_OAK_STAIRS)) roof++;
            }
            check(upstairs == 9, "arriba hay " + upstairs + " camas (abajo " + downstairs + ")");
            check(stairs >= 5, "la escalera tiene " + stairs + " escalones");
            check(roof > 40, "el techo del Norte tiene " + roof + " escalones de roble oscuro");
            BlockState door = level.getBlockState(site.offset(6, 1, 1));
            check(door.getBlock() instanceof DoorBlock && door.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && level.getBlockState(site.offset(6, 2, 1)).getBlock() instanceof DoorBlock, "la puerta no quedó entera: " + door);
            check(level.getBlockState(site.offset(6, 8, 4)).is(Blocks.LANTERN), "el farol del dormitorio se cayó");
            BlockPos keeper = Blueprints.anchor(town, Upgrade.TAVERN_3.siteKey(), Upgrade.TAVERN_3);
            check(keeper != null && Ground.canStand(level, keeper) && !level.canSeeSky(keeper),
                    "el Tabernero no tiene dónde pararse detrás de la barra: " + keeper);
        } finally {
            data.remove(center);
        }
        h.succeed();
    }

    /** El mismo plano con cada estilo: paredes, techo y piso cambian (Arena = techo plano). */
    @GameTest(template = BIG)
    public static void stylesChangeMaterials(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        BlockPos site = h.absolutePos(new BlockPos(2, 1, 2));   // el piso de piedra
        Map<Style, Block> walls = Map.of(Style.REINO, Blocks.WHITE_TERRACOTTA, Style.NORTE, Blocks.SPRUCE_PLANKS, Style.ARENA, Blocks.CUT_SANDSTONE,
                Style.PANTANO, Blocks.PACKED_MUD, Style.SABANA, Blocks.ACACIA_PLANKS);
        for (Style st : Style.values()) {
            Blueprints.Plan plan = Blueprints.building(level, Upgrade.TAVERN_2, site, 0, st);
            boolean wall = false, gable = false;
            for (BlockState s : plan.blocks.values()) {
                if (s.is(walls.get(st))) wall = true;
                if (s.getBlock() instanceof StairBlock && s.is(st.roofStairs) && s.getValue(StairBlock.FACING).getAxis().isHorizontal()) gable = true;
            }
            check(wall, st.label + ": las paredes no son de " + walls.get(st));
            if (st.flat) check(plan.blocks.values().stream().anyMatch(s -> s.is(st.roofSlab)), st.label + ": el techo plano no tiene pretil");
            else check(gable, st.label + ": no tiene techo a dos aguas");
        }
        h.succeed();
    }

    /** Plantar la Taberna 1 anota un terreno de 13 (el de la 3): una casa pegada al edificio chico no entra. */
    @GameTest(template = BIG)
    public static void tavernReservesLevel3Site(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        UUID owner = new UUID(0x5717L, h.absolutePos(BlockPos.ZERO).asLong());
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "prueba_estilo"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            town.built.add(Upgrade.BUILDER_1);
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner, "ale-estilo"));
            player.getInventory().clearContent();
            for (int i = 0; i < 8; i++) player.getInventory().add(new ItemStack(Items.OAK_LOG, 64));
            for (int i = 0; i < 12; i++) player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            for (int i = 0; i < 4; i++) player.getInventory().add(new ItemStack(Items.DIRT, 64));
            player.getInventory().add(new ItemStack(Items.GLASS, 64));
            player.getInventory().add(new ItemStack(Items.WHITE_WOOL, 64));
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
            player.getInventory().add(new ItemStack(Items.EMERALD, 64));
            BlockPos corner = h.absolutePos(BlockPos.ZERO);
            Component msg = Upgrades.order(player, v.center(), Upgrade.TAVERN_1, corner, 0);
            check(town.job != null && town.job.upgrade == Upgrade.TAVERN_1, "no empezó la taberna: " + msg.getString());
            check(town.siteSize("tavern") == 13 && !town.legacySite("tavern"), "el terreno no es de 13: " + town.siteSize("tavern"));
            check(!town.style.isEmpty(), "no quedó elegido el estilo de la aldea");
            // La taberna 1 ocupa de x=2 a 10; una casa en x=11..19 entraría con un terreno de 9, pero pisa la reserva de 13
            Placement.Spot spot = Placement.check(level, v, town, corner.offset(11, 0, 0), "house_0");
            check(spot.problem() != null && spot.problem().contains("Se pisa"), "una casa entró en el terreno reservado: " + spot.problem());
        } finally {
            TownData.Town t = data.at(v.center());
            if (t != null && t.job != null && t.job.builder != null) {
                var e = level.getEntity(t.job.builder);
                if (e != null) e.discard();
            }
            data.remove(v.center());
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Aldea de facción con taberna: aparece su Tabernero (sin dueño, invulnerable) y la empalizada abraza a los edificios. */
    @GameTest(template = BIG)
    public static void factionTavernHasKeeperAndRingCoversIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "valdren"), 0);
        VillageData.get(level).add(v);
        VillageData.get(level).markTest(v.center());   // que FactionTowns, la guarnición y demás no la toquen (está al lado de otras pruebas)
        TownData data = TownData.get(level);
        try {
            TownData.Town town = data.getOrCreate(v.center());
            BlockPos site = h.absolutePos(new BlockPos(2, 1, 2));
            town.putSite(Upgrade.TAVERN_1.siteKey(), site, Upgrade.TAVERN_1.siteSize(), 0);
            Upgrades.buildNow(level, town, Upgrade.TAVERN_1, Blueprints.building(level, Upgrade.TAVERN_1, site, 0, Style.REINO));
            com.ale.warbandcraft.village.FactionInn.serve(level, v, town);
            var keepers = level.getEntitiesOfClass(com.ale.warbandcraft.worker.InnkeeperEntity.class, new net.minecraft.world.phys.AABB(v.center()).inflate(16),
                    k -> com.ale.warbandcraft.village.FactionInn.is(k));
            check(keepers.size() == 1, "hay " + keepers.size() + " taberneros de facción");
            check(keepers.get(0).isInvulnerable() && keepers.get(0).getOwnerUUID() == null, "el tabernero de facción tiene dueño o se puede matar");
            com.ale.warbandcraft.village.FactionInn.serve(level, v, town);
            check(level.getEntitiesOfClass(com.ale.warbandcraft.worker.InnkeeperEntity.class, new net.minecraft.world.phys.AABB(v.center()).inflate(16),
                    k -> com.ale.warbandcraft.village.FactionInn.is(k)).size() == 1, "apareció un segundo tabernero");
            // un terreno lejos (a ~40) y una casa más lejos: la empalizada deja adentro al edificio, la casa no cuenta
            town.ringRadius = Blueprints.RING_MIN;
            town.putSite("market", v.center().offset(30, 0, 20), 13, 0);
            town.putSite("house_0", v.center().offset(50, 0, 0), 9, 0);
            Blueprints.Plan ring = Upgrades.plan(level, v, town, Upgrade.PALISADE);
            check(ring != null, "no hay plano de empalizada");
            double far = Math.sqrt(42 * 42 + 32 * 32);
            check(town.ringRadius >= far && town.ringRadius <= Blueprints.RING_MAX, "la empalizada quedó en " + town.ringRadius + " (el edificio llega a " + far + ")");
            keepers.forEach(net.minecraft.world.entity.Entity::discard);
        } finally {
            data.remove(v.center());
            VillageData.get(level).remove(v.center());
        }
        h.succeed();
    }

    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 17; x++) for (int z = -1; z <= 17; z++) for (int y = 1; y <= 20; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new GameTestAssertException(why);
    }
}
