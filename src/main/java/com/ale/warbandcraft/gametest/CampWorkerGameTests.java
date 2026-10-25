package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampWorkers;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.village.TavernWorkers;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.worker.SmithEntity;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Trabajador de un Campamento común (contratado en la taberna) y el tope de los que esperan en la taberna. */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class CampWorkerGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);

    /** Llega al Campamento y espera quieto; sin yunque no es Herrero; con cama y yunque sí (atado al Campamento); un segundo, no Herrero; despedir. */
    @GameTest(template = TEMPLATE)
    public static void campWorkerBecomesSmith(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.nameUUIDFromBytes(("warbandcraft-cw@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        check(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity, "el bloque Campamento no creó su cofre");
        CampBlockEntity camp = (CampBlockEntity) h.getBlockEntity(CAMP_POS);
        camp.setOwner(owner);
        BlockPos campAbs = camp.getBlockPos();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner, "ale-cw"));
        check(CampWorkers.canTake(level, owner, campAbs), "un Campamento común sin trabajador tendría que aceptar uno");

        CampWorkers.hire(player, campAbs, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 2, 2))));
        CampWorkers.Rec r = CampWorkers.Data.get(level).at(campAbs);
        check(r != null && !r.away, "el contratado no quedó anotado caminando");
        check(CampWorkers.canTake(level, owner, campAbs), "tiene lugar para un segundo (Herrero + Granjero)");
        check(level.getEntity(r.worker) instanceof Villager, "no apareció el aldeano");
        CampWorkers.tick(level);
        Villager v = (Villager) level.getEntity(r.worker);
        check(v.isNoAi(), "al llegar tendría que quedar quieto");

        h.setBlock(new BlockPos(4, 2, 6), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        BlockState head = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD);
        h.setBlock(new BlockPos(4, 2, 7), head);
        check(CampWorkers.freeBed(level, campAbs) != null, "no ve la cama");
        if (CampWorkers.anvil(level, campAbs) == null) {   // (un yunque de una prueba vecina también sirve)
            CampWorkers.assign(player, camp, VillageManagement.Job.SMITH);
            check(level.getEntity(r.worker) instanceof Villager, "sin yunque no tendría que ser Herrero");
        }
        h.setBlock(new BlockPos(6, 2, 2), Blocks.ANVIL);
        CampWorkers.assign(player, camp, VillageManagement.Job.SMITH);
        Entity e = level.getEntity(CampWorkers.Data.get(level).at(campAbs).worker);
        check(e instanceof SmithEntity, "no se hizo Herrero");
        SmithEntity smith = (SmithEntity) e;
        check(campAbs.equals(smith.getCamp()) && smith.getWorkplace() != null, "el Herrero no quedó atado al Campamento y al yunque");
        check(CampWorkers.Data.get(level).at(campAbs).bed != null, "no se le reservó la cama");
        check(e.getPersistentData().contains(CampWorkers.TAG), "le falta la marca de trabajador de Campamento");

        // Un día entero sin cargar: come y hace sus 8 vueltas (8 lingotes + 8 carbones → 16 de armamento)
        CampWorkers.tick(level);
        com.ale.warbandcraft.village.WorkerOutput.Rec out = com.ale.warbandcraft.village.WorkerOutput.get(level).of(e.getUUID());
        check(out != null, "no se anotó su producción");
        camp.insert(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 10));
        camp.insert(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 10));
        camp.insert(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4));
        out.lastTick -= com.ale.warbandcraft.village.WorkerOutput.DAY;
        CampWorkers.tick(level);
        int armament = camp.count(com.ale.warbandcraft.kit.Armament.item());
        check(armament == 16, "un día sin cargar tendría que dar 16 de armamento, dio " + armament);
        check(camp.count(net.minecraft.world.item.Items.IRON_INGOT) == 2, "tendrían que quedar 2 lingotes");
        check(camp.count(net.minecraft.world.item.Items.BREAD) < 4, "no comió");

        // El segundo: no puede ser otro Herrero; con él ya no hay lugar para un tercero
        CampWorkers.hire(player, campAbs, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 2, 3))));
        check(!CampWorkers.canTake(level, owner, campAbs), "un Campamento no tiene tres trabajadores");
        CampWorkers.tick(level);
        CampWorkers.assign(player, camp, VillageManagement.Job.SMITH);
        check(CampWorkers.Data.get(level).of(campAbs).stream().filter(x -> CampWorkers.SMITH.equals(x.job)).count() == 1,
                "no puede haber dos Herreros");

        for (CampWorkers.Rec x : java.util.List.copyOf(CampWorkers.Data.get(level).of(campAbs))) CampWorkers.dismiss(player, camp, x);
        check(CampWorkers.Data.get(level).at(campAbs) == null && !e.isAlive(), "despedir no los sacó");
        h.succeed();
    }

    /** Fundar aldea: el que esperaba queda como aldeano libre y el Herrero sigue, ahora de la aldea (sin la marca). */
    @GameTest(template = TEMPLATE)
    public static void campWorkersJoinFoundedVillage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.nameUUIDFromBytes(("warbandcraft-cwv@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        BlockPos campAbs = h.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(campAbs, ModBlocks.CAMP.get().defaultBlockState());
        check(level.getBlockEntity(campAbs) instanceof CampBlockEntity, "sin Campamento");
        CampBlockEntity camp = (CampBlockEntity) level.getBlockEntity(campAbs);
        camp.setOwner(owner);
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner, "ale-cwv"));
        h.setBlock(new BlockPos(1, 2, 6), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        h.setBlock(new BlockPos(1, 2, 7), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        h.setBlock(new BlockPos(7, 2, 2), Blocks.ANVIL);
        CampWorkers.hire(player, campAbs, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 2, 5))));
        CampWorkers.tick(level);
        CampWorkers.assign(player, camp, VillageManagement.Job.SMITH);
        CampWorkers.Rec smithRec = CampWorkers.Data.get(level).withJob(campAbs, CampWorkers.SMITH);
        check(smithRec != null, "no se hizo Herrero");
        Entity smith = level.getEntity(smithRec.worker);
        CampWorkers.hire(player, campAbs, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(5, 2, 3))));
        UUID waitingId = CampWorkers.Data.get(level).of(campAbs).get(1).worker;
        camp.insert(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, com.ale.warbandcraft.village.Founding.COST));
        com.ale.warbandcraft.village.Founding.found(level, owner, campAbs, null);
        try {
            check(com.ale.warbandcraft.village.VillageCamps.villageOf(level, campAbs) != null, "no se fundó la aldea");
            check(CampWorkers.Data.get(level).of(campAbs).isEmpty(), "el Campamento sigue con trabajadores propios");
            check(smith.isAlive() && smith instanceof SmithEntity && !smith.getPersistentData().contains(CampWorkers.TAG),
                    "el Herrero tendría que seguir, sin la marca de Campamento");
            check(level.getEntity(waitingId) == null, "el que esperaba tendría que haberse cambiado por un aldeano libre");
            check(!level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(campAbs).inflate(4),
                    x -> !x.isNoAi() && !x.getPersistentData().contains(CampWorkers.TAG)).isEmpty(), "no quedó un aldeano libre");
        } finally {
            for (Villager x : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(campAbs).inflate(16))) x.discard();
            if (smith != null) smith.discard();
            com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageCamps.villageOf(level, campAbs);
            if (v != null) {
                com.ale.warbandcraft.village.VillageStorehouse.forget(level, v);
                com.ale.warbandcraft.village.VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
            }
        }
        h.succeed();
    }

    /** La taberna: el primer día hay uno, nunca más que el tope del nivel. */
    @GameTest(template = TEMPLATE)
    public static void tavernWorkersCapped(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        long key = h.absolutePos(BlockPos.ZERO).asLong();
        check(TavernWorkers.available(level, key, 1) == 1, "el primer día tendría que esperar uno");
        check(TavernWorkers.available(level, key, 0) == 0, "sin taberna no hay trabajadores");
        check(TavernWorkers.max(3) == TavernWorkers.MAX_3, "tope del nivel 3");
        h.succeed();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
