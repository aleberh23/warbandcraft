package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.camp.WorkerZone;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.WorkZones;
import com.ale.warbandcraft.village.WorkerOutput;
import com.ale.warbandcraft.village.WorkerSupply;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.LumberjackEntity;
import com.talhanation.workers.entities.MinerEntity;
import com.talhanation.workers.entities.workarea.LumberArea;
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
            // pedido de Ale: del almacén solo el pico; pala, antorchas y relleno le aparecen solos
            check(camp.count(Items.TORCH) == 20 && camp.count(Items.COBBLESTONE) == 32 && camp.count(Items.STONE_SHOVEL) == 1,
                    "sacó del almacén lo que es gratis");

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

    /** Pedido de Ale: al leñador solo se le pide el hacha (los retoños le aparecen); lo esperado sale también cargado. */
    @GameTest(template = TEMPLATE)
    public static void lumberjackOnlyNeedsAxe(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        AbstractWorkerEntity lumberjack = worker(h, ModEntityTypes.LUMBERJACK.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            camp.insert(new ItemStack(Items.STONE_AXE));
            com.talhanation.workers.entities.workarea.LumberArea area = ModEntityTypes.LUMBERAREA.get().create(level);
            check(area != null, "sin zona de tala");
            area.moveTo(h.absolutePos(new BlockPos(6, 1, 6)).getCenter());
            area.setPlayerUUID(v.owner());
            area.setSaplingStack(new ItemStack(Items.SPRUCE_SAPLING));
            level.addFreshEntity(area);
            ((com.talhanation.workers.entities.LumberjackEntity) lumberjack).currentLumberArea = area;
            WorkerSupply.serve(level, v, camp, lumberjack);
            check(lumberjack.countMatchingItems(s -> s.is(ItemTags.AXES)) == 1, "no le dio el hacha");
            check(lumberjack.countMatchingItems(s -> s.is(ItemTags.SAPLINGS)) >= 8, "no le aparecieron los retoños");
            check(lumberjack.neededItems.stream().noneMatch(n -> n.matcher.test(new ItemStack(Items.SPRUCE_SAPLING))), "sigue pidiendo retoños");
            check(!key(WorkerSupply.status(level, lumberjack)).equals("status.warbandcraft.worker.missing"), "dice que le falta algo");
            WorkerOutput.Rec rec = WorkerOutput.get(level).of(lumberjack.getUUID());
            check(rec != null && rec.working, "no cuenta como trabajando");
            rec.floor.put("minecraft:spruce_log", 5.5);
            WorkerSupply.serve(level, v, camp, lumberjack);
            check(camp.count(Items.SPRUCE_LOG) == 5, "no salió lo esperado: " + camp.count(Items.SPRUCE_LOG));
            long day = level.getDayTime() / WorkerOutput.DAY;
            check(rec.history.getOrDefault(day, java.util.Map.of()).getOrDefault("minecraft:spruce_log", 0) == 5, "no quedó anotado");
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

    /** Pedido de Ale: lo producido sin cargar se paga de golpe al volver (sin medir: lo esperado del oficio); lo medido manda. */
    @GameTest(template = TEMPLATE)
    public static void outputWhileUnloaded(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        AbstractWorkerEntity lumberjack = worker(h, ModEntityTypes.LUMBERJACK.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            lumberjack.addItem(new ItemStack(Items.STONE_AXE));
            WorkerSupply.serve(level, v, camp, lumberjack);
            WorkerOutput.Rec rec = WorkerOutput.get(level).of(lumberjack.getUUID());
            check(rec != null, "no quedó anotado el leñador");
            check(rec.estimate.getOrDefault("minecraft:oak_log", 0D) == WorkerSupply.EST_LOGS, "lo esperado del leñador: " + rec.estimate);
            int before = camp.count(Items.OAK_LOG);
            rec.lastTick = level.getGameTime() - WorkerOutput.DAY;   // un día sin cargar
            WorkerSupply.serve(level, v, camp, lumberjack);
            check(camp.count(Items.OAK_LOG) - before == WorkerSupply.EST_LOGS, "no llegó el día de troncos: " + (camp.count(Items.OAK_LOG) - before));
            // Medido de verdad: 100 troncos en un día cargado → eso es lo que da por día
            rec.loadedTicks = WorkerOutput.DAY;
            rec.made.clear();
            rec.made.put("minecraft:oak_log", 100D);
            check(Math.round(rec.rate().get("minecraft:oak_log")) == 100, "lo medido no manda: " + rec.rate());
            rec.lastTick = level.getGameTime() - WorkerOutput.DAY / 2;   // medio día
            before = camp.count(Items.OAK_LOG);
            WorkerSupply.serve(level, v, camp, lumberjack);
            check(camp.count(Items.OAK_LOG) - before == 50, "medio día no dio 50: " + (camp.count(Items.OAK_LOG) - before));
        } finally {
            cleanup(level, v, around);
        }
        h.succeed();
    }

    /** El trigo del granjero llega al almacén como pan (la aldea no come trigo) y cuenta como producción. */
    @GameTest(template = TEMPLATE)
    public static void wheatBecomesBread(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        AbstractWorkerEntity farmer = worker(h, ModEntityTypes.FARMER.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            farmer.addItem(new ItemStack(Items.STONE_HOE));
            farmer.addItem(new ItemStack(Items.WHEAT, 30));
            farmer.addItem(new ItemStack(Items.WHEAT_SEEDS, 60));
            WorkerSupply.serve(level, v, camp, farmer);
            check(camp.count(Items.WHEAT_SEEDS) == 0, "las semillas fueron al almacén: " + camp.count(Items.WHEAT_SEEDS));
            check(farmer.countMatchingItems(s -> s.is(Items.WHEAT_SEEDS)) <= 32, "se quedó con todas las semillas");
            int kept = farmer.countMatchingItems(s -> s.is(Items.BREAD));
            check(camp.count(Items.WHEAT) == 0 && farmer.countMatchingItems(s -> s.is(Items.WHEAT)) == 0, "quedó trigo");
            check(camp.count(Items.BREAD) + kept == 10, "30 de trigo no dieron 10 panes: " + camp.count(Items.BREAD) + " + " + kept);
            WorkerOutput.Rec rec = WorkerOutput.get(level).of(farmer.getUUID());
            check(rec != null && rec.made.getOrDefault("minecraft:bread", 0D) == camp.count(Items.BREAD), "el pan no se anotó: "
                    + (rec == null ? null : rec.made));
            check(!rec.rate().containsKey("minecraft:wheat_seeds"), "las semillas cuentan como producción: " + rec.rate());
        } finally {
            cleanup(level, v, around);
        }
        h.succeed();
    }

    /** La mina es la escalera de Workers; terminada la escalera, sigue un pasillo desde abajo. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void stairMineThenCorridor(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        MinerEntity miner = worker(h, ModEntityTypes.MINER.get(), new BlockPos(2, 2, 2));
        AABB around = new AABB(v.center()).inflate(40);
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner(h), "ale-h18m"));
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            camp.insert(new ItemStack(Items.IRON_PICKAXE));
            camp.insert(new ItemStack(Items.CHEST));
            BlockPos o = h.absolutePos(new BlockPos(1, 2, 5));
            var msg = WorkZones.mark(player, v, miner.getUUID(), o.getX(), o.getZ(), o.getX() + 7, o.getZ() + 2);
            check(miner.getCurrentWorkArea() instanceof MiningArea, "no se creó la mina: " + msg.getString());
            MiningArea mine = (MiningArea) miner.getCurrentWorkArea();
            check(mine.getMode() == MiningArea.MiningMode.STAIRS_DOWN, "la mina no es en escalera: " + mine.getMode());
            AABB box = mine.getArea();
            check(box.minX == o.getX() && box.maxX == o.getX() + 8 && box.minZ == o.getZ() && box.maxZ == o.getZ() + 3,
                    "la escalera no es lo marcado: " + box);
            check(box.maxY - box.minY >= 8, "la escalera no baja: " + box);
            check(mine.getPersistentData().getInt(WorkerSupply.TAG_STAGE) == 0, "no quedó anotada como escalera");

            WorkerSupply.advance(level, v, mine);
            check(mine.getMode() == MiningArea.MiningMode.STAIRS_DOWN, "pasó a pasillos sin terminar la escalera");
            // Workers la termina (acá a mano): todo lo que es escalera, aire
            for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
                if (mine.isStairsAir(p) && level.getBlockState(p).getDestroySpeed(level, p) >= 0) level.removeBlock(p, false);
            }
            WorkerSupply.advance(level, v, mine);
            check(mine.getPersistentData().getInt(WorkerSupply.TAG_STAGE) == 1, "no arrancó el primer pasillo: "
                    + mine.getPersistentData().getInt(WorkerSupply.TAG_STAGE));
            check(mine.getMode() == MiningArea.MiningMode.CUSTOM, "el pasillo no es una caja común");
            AABB corridor = mine.getArea();
            // Al pie de la escalera (en el mundo de prueba la roca madre corta la escalera: no se mide contra la boca)
            check(corridor.maxY - corridor.minY == 3 && corridor.minY <= box.minY + 3 && corridor.minY < box.maxY - 3,
                    "el pasillo no está abajo: " + corridor + " escalera " + box);
        } finally {
            cleanup(level, v, around);
        }
        h.succeed();
    }

    /** Un puesto de afuera: lo que el leñador deja en su cofre pasa en el acto al almacén de la aldea. */
    @GameTest(template = TEMPLATE)
    public static void outpostChestGoesToStorehouse(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        WorkerSupply.clear();
        LumberjackEntity lumberjack = worker(h, ModEntityTypes.LUMBERJACK.get(), new BlockPos(6, 2, 6));
        AABB around = new AABB(v.center()).inflate(32);
        BlockPos chestPos = h.absolutePos(new BlockPos(1, 2, 7));
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            LumberArea area = ModEntityTypes.LUMBERAREA.get().create(level);
            check(area != null, "sin zona de tala");
            BlockPos z = h.absolutePos(new BlockPos(5, 2, 5));
            area.setWidthSize(4);
            area.setDepthSize(4);
            area.setHeightSize(4);
            area.setPlayerUUID(v.owner());
            check(WorkerZone.place(area, new WorkerZone.BoundingBox(z.getX(), z.getY(), z.getZ(), z.getX() + 2, z.getY() + 3, z.getZ() + 2)),
                    "no se pudo poner la zona");
            area.getPersistentData().putLong(WorkZones.TAG_OUTPOST, v.center().asLong());
            area.getPersistentData().putLong("warbandcraft:zone_chest", chestPos.asLong());
            level.addFreshEntity(area);
            lumberjack.currentLumberArea = area;
            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            check(level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity, "sin cofre");
            ((net.minecraft.world.Container) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.OAK_LOG, 20));
            WorkerOutput.get(level).ensure(lumberjack.getUUID(), v.center(), lumberjack.blockPosition(), level.getGameTime()).outpost = true;
            WorkerSupply.serve(level, v, camp, lumberjack);
            check(camp.count(Items.OAK_LOG) == 20, "los troncos del cofre no llegaron al almacén: " + camp.count(Items.OAK_LOG));
            check(((net.minecraft.world.Container) level.getBlockEntity(chestPos)).isEmpty(), "el cofre del puesto no se vació");
            check(WorkerOutput.get(level).of(lumberjack.getUUID()).made.getOrDefault("minecraft:oak_log", 0D) == 20, "no se anotaron los troncos");
            check(VillageManagement.workers(level, v.center(), v.owner()).contains(lumberjack), "el del puesto no cuenta como trabajador de la aldea");
        } finally {
            level.removeBlock(chestPos, false);
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
        WorkerOutput out = WorkerOutput.get(level);
        for (WorkerOutput.Rec r : out.of(v.center())) out.remove(r.worker);
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
