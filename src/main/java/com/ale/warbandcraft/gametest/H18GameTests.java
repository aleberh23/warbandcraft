package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.village.WorkerSupply;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.MinerEntity;
import com.talhanation.workers.entities.workarea.HomeArea;
import com.talhanation.workers.entities.workarea.MiningArea;
import com.talhanation.workers.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas de H18 (trabajadores automáticos): el minero recibe del almacén pala, antorchas y relleno; lo que junta el
 * pescador pasa al almacén; sin algo en el almacén el cartelito dice qué falta; la veta del minero; la cama.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H18GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 2, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    @GameTest(template = TEMPLATE)
    public static void minerGetsKitFromStorehouse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        MinerEntity miner = worker(h, ModEntityTypes.MINER.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            camp.insert(new ItemStack(Items.STONE_PICKAXE));
            camp.insert(new ItemStack(Items.STONE_SHOVEL));
            camp.insert(new ItemStack(Items.TORCH, 20));
            camp.insert(new ItemStack(Items.COBBLESTONE, 32));
            WorkerSupply.serve(level, v, camp, miner);
            check(miner.countMatchingItems(s -> s.is(ItemTags.PICKAXES)) == 1, "no le dio el pico");
            check(miner.countMatchingItems(s -> s.is(ItemTags.SHOVELS)) == 1, "no le dio la pala");
            check(miner.countMatchingItems(s -> s.is(Items.TORCH)) == 16, "antorchas: " + miner.countMatchingItems(s -> s.is(Items.TORCH)));
            check(miner.countMatchingItems(s -> s.is(Items.COBBLESTONE)) == 16, "relleno: " + miner.countMatchingItems(s -> s.is(Items.COBBLESTONE)));
            check(camp.count(Items.TORCH) == 4 && camp.count(Items.COBBLESTONE) == 16, "no salió del almacén lo justo");

            // Otro minero, sin nada en el almacén: con zona, el cartelito dice qué falta
            MinerEntity other = worker(h, ModEntityTypes.MINER.get(), new BlockPos(3, 2, 2));
            MiningArea area = ModEntityTypes.MININGAREA.get().create(level);
            check(area != null, "sin zona de mina");
            area.moveTo(h.absolutePos(new BlockPos(6, 1, 6)).getCenter());
            area.setPlayerUUID(v.owner());
            level.addFreshEntity(area);
            other.currentMiningArea = area;
            WorkerSupply.serve(level, v, camp, other);
            WorkerSupply.Status st = WorkerSupply.status(level, other);
            check(key(st).equals("status.warbandcraft.worker.missing"), "el cartelito no dice qué falta: " + key(st) + " " + st.text().getString());
            check(st.color() == WorkerSupply.RED, "lo que falta no está en rojo");
        } finally {
            cleanup(level, v, around);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void catchGoesToStorehouse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        AbstractWorkerEntity fisher = worker(h, ModEntityTypes.FISHERMAN.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            fisher.addItem(new ItemStack(Items.FISHING_ROD));
            fisher.addItem(new ItemStack(Items.COD, 20));
            fisher.addItem(new ItemStack(Items.SALMON, 3));
            fisher.farmedItems = 23;
            WorkerSupply.serve(level, v, camp, fisher);
            check(fisher.countMatchingItems(s -> s.is(Items.FISHING_ROD)) == 1, "se quedó sin caña");
            int kept = fisher.countMatchingItems(s -> s.is(Items.COD) || s.is(Items.SALMON));
            check(kept <= WorkerSupply.KEEP_FOOD, "se quedó con demasiado pescado: " + kept);
            check(camp.count(Items.COD) + camp.count(Items.SALMON) + kept == 23, "el pescado no llegó al almacén");
            check(fisher.farmedItems == 0, "Workers todavía cree que tiene que ir a dejar");
        } finally {
            cleanup(level, v, around);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void veinAndBed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        AABB around = new AABB(v.center()).inflate(32);
        // La veta: casi todo piedra, carbón y hierro; diamantes, pocos
        Map<Item, Integer> count = new HashMap<>();
        for (int i = 0; i < 2000; i++) count.merge(WorkerSupply.vein(level, 40).getItem(), 1, Integer::sum);
        check(count.getOrDefault(Items.COBBLESTONE, 0) > 1000, "poca piedra: " + count);
        check(count.getOrDefault(Items.COAL, 0) > 200 && count.getOrDefault(Items.RAW_IRON, 0) > 150, "poco carbón/hierro: " + count);
        check(count.getOrDefault(Items.DIAMOND, 0) < 60, "demasiados diamantes: " + count);
        check(WorkerSupply.vein(level, -30).getItem() != Items.COBBLESTONE, "abajo de y=0 no sale piedra común");

        // La cama: una cama libre de la aldea pasa a ser la suya
        BlockPos foot = h.absolutePos(new BlockPos(7, 2, 3));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlockAndUpdate(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD));
        AbstractWorkerEntity farmer = worker(h, ModEntityTypes.FARMER.get(), new BlockPos(5, 2, 3));
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            check(!WorkerSupply.hasHome(farmer), "ya tenía casa");
            WorkerSupply.serve(level, v, camp, farmer);
            check(WorkerSupply.hasHome(farmer), "no le dio la cama libre");
            Optional<HomeArea> home = level.getEntitiesOfClass(HomeArea.class, around).stream().findFirst();
            check(home.isPresent() && home.get().isResidentOf(farmer.getUUID()), "no se armó su zona Hogar");
            check(foot.north().equals(home.get().assignedBedPos), "la zona Hogar no tiene su cama: " + home.get().assignedBedPos);
        } finally {
            level.removeBlock(foot.north(), false);
            level.removeBlock(foot, false);
            cleanup(level, v, around);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static <T extends AbstractWorkerEntity> T worker(GameTestHelper h, EntityType<T> type, BlockPos pos) {
        T w = h.spawn(type, pos);
        w.setNoAi(true);
        w.setOwnerUUID(Optional.of(owner(h)));
        w.setIsOwned(true);
        w.setFollowState(0);
        return w;
    }

    private static String key(WorkerSupply.Status s) {
        return s.text().getContents() instanceof TranslatableContents t ? t.getKey() : s.text().getString();
    }

    private static void cleanup(ServerLevel level, VillageData.Village v, AABB around) {
        VillageStorehouse.forget(level, v);
        for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, around)) w.discard();
        for (var a : level.getEntitiesOfClass(com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity.class, around)) a.discard();
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        WorkerSupply.clear();
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), FACTION, 0).withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h18@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
