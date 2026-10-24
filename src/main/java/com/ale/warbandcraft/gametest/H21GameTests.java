package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.Popularity;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageEconomy;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.VillageStorehouse;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Pruebas de H21 (crecimiento): la popularidad sube con buena comida y sin impuestos y baja al revés; con 50 o más y
 * una cama libre llega un aldeano sin oficio; con menos se va uno; las raciones se comen del almacén; los impuestos
 * multiplican lo que paga la aldea.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H21GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 2, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    @GameTest(template = TEMPLATE)
    public static void popularityMovesAndSettlersArrive(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            TownData.Town t = TownData.get(level).getOrCreate(v.center());
            // Buena: raciones dobles, sin impuestos, comida variada, una cama libre
            t.rations = 4;
            t.taxes = 0;
            camp.insert(new ItemStack(Items.BREAD, 32));
            camp.insert(new ItemStack(Items.BAKED_POTATO, 32));
            camp.insert(new ItemStack(Items.COOKED_BEEF, 16));
            double before = t.popularity;
            Popularity.minute(level, v, t);
            check(t.popularity > before, "la popularidad no subió: " + t.popularity);

            // El que llega es un aldeano sin oficio, marcado (sin camas en la prueba: las pruebas de al lado las usan)
            check(Popularity.arriveEvery(95) == Popularity.ARRIVE_MIN_90 && Popularity.arriveEvery(55) == Popularity.ARRIVE_MIN_50, "ritmo de llegada");
            Villager settler = Popularity.spawnSettler(level, v);
            check(settler != null && VillageManagement.isFree(settler) && settler.getPersistentData().getBoolean(Popularity.TAG_SETTLER),
                    "el que llegó no es un aldeano libre");

            // Ración del día: se come del almacén (4 habitantes como mucho × 8 puntos)
            int food = com.ale.warbandcraft.camp.Barracks.foodPoints(camp);
            check(Popularity.eat(level, v, t) && !t.hungry, "no comieron");
            check(com.ale.warbandcraft.camp.Barracks.foodPoints(camp) < food, "la ración no salió del almacén");

            // Impuestos: sin impuestos la aldea no paga; abusivos, el doble que normales
            check(VillageEconomy.taxOf(level, v) == 0, "sin impuestos pagó " + VillageEconomy.taxOf(level, v));
            t.taxes = 2;
            int normal = VillageEconomy.taxOf(level, v);
            t.taxes = 4;
            check(VillageEconomy.taxOf(level, v) == normal * 2, "abusivos no paga el doble");

            // Mala: sin comida, abusivos → baja; por debajo de 50 se va el libre
            t.rations = 0;
            before = t.popularity;
            Popularity.minute(level, v, t);
            check(t.popularity < before, "la popularidad no bajó");
            t.popularity = 10;
            int left = 0;
            for (int i = 0; i < Popularity.LEAVE_MIN_0; i++) left += Math.min(0, Popularity.minute(level, v, t));
            check(left == -1 && !settler.isAlive(), "no se fue el recién llegado");
        } finally {
            for (Villager x : level.getEntitiesOfClass(Villager.class, new AABB(v.center()).inflate(64))) x.discard();
            TownData.get(level).remove(v.center());
            VillageStorehouse.forget(level, v);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Descargada: la gente llega y se va "en papel" con lo último medido; un ataque reciente baja la popularidad. */
    @GameTest(template = TEMPLATE)
    public static void paperGrowthAndRecentAttack(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        try {
            TownData.Town t = TownData.get(level).getOrCreate(v.center());
            t.popularity = 60;
            t.lastDelta = 1;
            t.paperPop = 2;
            t.paperBeds = 1;
            for (int i = 0; i < Popularity.ARRIVE_MIN_50; i++) Popularity.paperMinute(t);
            check(t.paperIn == 1 && t.paperBeds == 0 && t.paperPop == 3, "no llegó uno en papel: " + t.paperIn);
            for (int i = 0; i < 10; i++) Popularity.paperMinute(t);
            check(t.paperIn == 1, "llegaron más sin camas libres");
            check(t.popularity > 60, "la popularidad no siguió con el último cambio");
            t.popularity = 10;
            t.lastDelta = 0;
            for (int i = 0; i < Popularity.LEAVE_MIN_0; i++) Popularity.paperMinute(t);
            check(t.paperIn == 0 && t.paperOut == 0 && t.paperPop == 2, "el que llegó en papel no fue el primero en irse");
            for (int i = 0; i < Popularity.LEAVE_MIN_0; i++) Popularity.paperMinute(t);
            check(t.paperOut == 1 && t.paperPop == 1, "no se fue uno en papel");

            Popularity.attacked(level, v.center());
            check(Popularity.factors(level, v, t).stream().anyMatch(f -> f.key().equals("recent_attack") && f.value() < 0),
                    "el ataque reciente no baja la popularidad");
        } finally {
            TownData.get(level).remove(v.center());
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    private static void bed(GameTestHelper h, BlockPos rel) {
        BlockPos foot = h.absolutePos(rel);
        var state = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        h.getLevel().setBlockAndUpdate(foot, state.setValue(BedBlock.PART, BedPart.FOOT));
        h.getLevel().setBlockAndUpdate(foot.south(), state.setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), FACTION, 0).withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h21@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
