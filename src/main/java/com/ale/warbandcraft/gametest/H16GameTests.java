package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.Founding;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.village.WorkZones;
import com.ale.warbandcraft.worker.SmithEntity;
import com.ale.warbandcraft.worker.SmithWorkGoal;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.workarea.CropArea;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas de H16 (aldeas como Campamentos grandes): el almacén pasa a ser un Campamento (con lo del barril), su
 * barracón tiene 80/100 (castillo 40/60), en las rutas es la aldea, perderla lo saca; darle oficio a un aldeano libre
 * (y la huerta del Granjero); el barracón de la aldea defiende en las invasiones.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H16GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 2, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    @GameTest(template = TEMPLATE)
    public static void storehouseBecomesACamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        VillageData data = VillageData.get(level);
        // Un barril de antes (H10) con cosas adentro
        BlockPos barrelPos = h.absolutePos(new BlockPos(6, 2, 6));
        level.setBlockAndUpdate(barrelPos, Blocks.BARREL.defaultBlockState());
        if (!(level.getBlockEntity(barrelPos) instanceof BarrelBlockEntity barrel)) throw new GameTestAssertException("sin barril");
        barrel.setItem(0, new ItemStack(Items.EMERALD, 5));
        barrel.setItem(1, new ItemStack(Items.IRON_INGOT, 3));
        data.setStorehouse(v.center(), barrelPos);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null && camp.getBlockPos().equals(barrelPos), "el barril no pasó a ser un Campamento");
            check(level.getBlockState(barrelPos).is(ModBlocks.CAMP.get()), "no hay bloque de Campamento");
            check(camp.count(Items.EMERALD) == 5 && camp.count(Items.IRON_INGOT) == 3, "lo del barril no pasó al Campamento");
            check(owner.equals(camp.getOwner()) && CampRegistry.get(level).isCampOf(owner, barrelPos), "el Campamento no es del dueño");
            VillageData.Village of = VillageCamps.villageOf(level, barrelPos);
            check(of != null && of.center().equals(v.center()), "no se reconoce como el Campamento de la aldea");

            // Barracón: 80 sin Cuartel, 100 con; castillo 40 / 60
            check(Barracks.capacity(level, barrelPos) == 80, "aldea sin Cuartel: " + Barracks.capacity(level, barrelPos));
            TownData.get(level).getOrCreate(v.center()).built.add(Upgrade.BARRACKS);
            check(Barracks.capacity(level, barrelPos) == 100, "aldea con Cuartel: " + Barracks.capacity(level, barrelPos));
            data.add(v.asCastle());
            check(Barracks.capacity(level, barrelPos) == 60, "castillo con Cuartel: " + Barracks.capacity(level, barrelPos));
            TownData.get(level).remove(v.center());
            check(Barracks.capacity(level, barrelPos) == 40, "castillo sin Cuartel: " + Barracks.capacity(level, barrelPos));
            data.add(v);

            // En las rutas es la aldea
            Routes.Node node = Routes.node(level, owner, barrelPos);
            check(node != null && node.pos().equals(v.center()), "en las rutas el Campamento no es la aldea");
            check(Routes.nodes(level, owner).stream().noneMatch(n -> n.pos().equals(barrelPos)), "el Campamento aparece como otro punto");
            check(barrelPos.equals(Routes.homeCamp(level, owner, v.center())), "los soldados que van a la aldea no van a su barracón");

            // Perder la aldea: el Campamento se va y lo suyo queda como almacén de la facción
            VillageStorehouse.forget(level, v);
            check(!level.getBlockState(barrelPos).is(ModBlocks.CAMP.get()), "el Campamento sigue después de perder la aldea");
            check(!CampRegistry.get(level).isCampOf(owner, barrelPos), "el Campamento sigue en el registro");
            check(data.stock(v.center()).emeralds() == 5, "las esmeraldas no quedaron en el almacén: " + data.stock(v.center()));
        } finally {
            TownData.get(level).remove(v.center());
            VillageStorehouse.forget(level, v);
            data.add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Fundar una aldea en un Campamento común: cobra, pone la campana, la aldea es tuya y el Campamento es el suyo. */
    @GameTest(template = TEMPLATE)
    public static void campBecomesVillage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos campPos = h.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(campPos, ModBlocks.CAMP.get().defaultBlockState());
        if (!(level.getBlockEntity(campPos) instanceof CampBlockEntity camp)) throw new GameTestAssertException("sin Campamento");
        camp.setOwner(owner);
        VillageData data = VillageData.get(level);
        VillageData.Village v = null;
        try {
            camp.insert(new ItemStack(Items.EMERALD, Founding.COST - 1));
            Founding.found(level, owner, campPos, null);
            check(VillageCamps.villageOf(level, campPos) == null, "fundó sin plata");
            check(camp.count(Items.EMERALD) == Founding.COST - 1, "cobró sin fundar");

            camp.insert(new ItemStack(Items.EMERALD, 6));
            Founding.found(level, owner, campPos, null);
            v = VillageCamps.villageOf(level, campPos);
            check(v != null && owner.equals(v.owner()) && !v.castle(), "el Campamento no pasó a ser una aldea tuya");
            check(camp.count(Items.EMERALD) == 5, "no cobró " + Founding.COST + ": quedan " + camp.count(Items.EMERALD));
            check(level.getBlockState(v.center()).is(Blocks.BELL), "no hay campana en el centro");
            check(v.center().distSqr(campPos) <= 6 * 6 * 3, "la campana quedó lejos del Campamento");
            check(data.isFounded(v.center()), "no quedó anotada como fundada");
            check(Invasions.takenFrom(level, owner, v.faction()).isEmpty(), "la facción la puede reconquistar");
            check(Barracks.capacity(level, campPos) == 80, "barracón de la aldea: " + Barracks.capacity(level, campPos));
            check(com.ale.warbandcraft.village.Territory.get(level).has(v.center()), "no tiene territorio");
            AABB box = new AABB(v.center()).inflate(8);
            check(level.getEntitiesOfClass(Villager.class, box).size() >= Founding.SETTLERS, "no llegaron los aldeanos");
            check(Founding.blocked(level, owner, campPos) != null, "se puede fundar dos veces");
        } finally {
            for (Villager villager : level.getEntitiesOfClass(Villager.class, new AABB(campPos).inflate(16))) villager.discard();
            if (v != null) {
                VillageStorehouse.forget(level, v);
                data.add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
            }
        }
        h.succeed();
    }

    /** Cuartel: pasar del barracón al cuartel y al revés, un soldado suelto del cuartel se guarda en el cuartel, comen y defienden. */
    @GameTest(template = TEMPLATE)
    public static void quartersTransfer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            BlockPos campPos = camp.getBlockPos();
            BarracksData.Barracks b = BarracksData.get(level).getOrCreate(campPos, owner);
            for (int i = 0; i < 3; i++) b.soldiers.add(sheet(i));
            check(Quarters.capacity(level, v) == Quarters.VILLAGE_CAPACITY, "lugar del cuartel sin Cuartel: " + Quarters.capacity(level, v));

            Quarters.toQuarters(level, v, null, 2);
            check(b.soldiers.size() == 1 && b.quarters.size() == 2, "no pasaron 2 al cuartel: " + b.soldiers.size() + "/" + b.quarters.size());
            check(b.eaters() == 3, "el cuartel no come");
            check(Invasions.guardsAt(level, owner, v.center()).size() == 3, "el cuartel no defiende la aldea");

            // Un soldado del cuartel que anda suelto se guarda en el cuartel, no en el barracón
            EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
            check(type != null, "Recruits no está cargado");
            if (!(h.spawn(type, new BlockPos(2, 2, 2)) instanceof com.talhanation.recruits.entities.AbstractRecruitEntity s)) {
                throw new GameTestAssertException("recruits:recruit no es un AbstractRecruitEntity");
            }
            s.setOwnerUUID(java.util.Optional.of(owner));
            s.setIsOwned(true);
            s.getPersistentData().putLong(Quarters.TAG_QUARTERS, v.center().asLong());
            check(Quarters.count(level, v) == 3, "el suelto no cuenta en el cuartel: " + Quarters.count(level, v));
            check(Barracks.store(level, s, campPos, false) == Barracks.StoreResult.OK, "no se guardó el del cuartel");
            check(b.quarters.size() == 3 && b.soldiers.size() == 1, "se guardó en el barracón: " + b.soldiers.size() + "/" + b.quarters.size());

            // Todos de vuelta al barracón, sin la marca del cuartel
            Quarters.toBarracks(level, v, null, 1000);
            check(b.quarters.isEmpty() && b.soldiers.size() == 4, "no volvieron todos al barracón: " + b.soldiers.size() + "/" + b.quarters.size());
            check(b.soldiers.stream().noneMatch(x -> x.entity.getCompound("ForgeData").contains(Quarters.TAG_QUARTERS)),
                    "quedó la marca del cuartel en una ficha del barracón");
        } finally {
            VillageStorehouse.forget(level, v);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Cuartel: encuentra el techo de una torre (escalera hasta el techo de ladrillos) y le da 4 puestos de arquero. */
    @GameTest(template = TEMPLATE)
    public static void quartersFindsTowers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        // Techo 3×3 de ladrillos a y=6, con la escalera (mirando hacia el centro) en el borde de afuera
        for (int x = 5; x <= 7; x++) for (int z = 3; z <= 5; z++) h.setBlock(new BlockPos(x, 6, z), Blocks.STONE_BRICKS);
        BlockPos ladder = h.absolutePos(new BlockPos(7, 6, 4));
        level.setBlock(ladder, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING,
                net.minecraft.core.Direction.WEST), 2);
        List<BlockPos> posts = Quarters.towerPosts(level, center, 3);
        check(posts.size() == Quarters.PER_TOWER, "puestos de la torre: " + posts.size());
        check(posts.get(0).equals(h.absolutePos(new BlockPos(6, 7, 4))), "el primer puesto no es el centro del techo: " + posts.get(0));
        check(posts.stream().noneMatch(p -> p.getX() == ladder.getX() && p.getZ() == ladder.getZ()), "un puesto quedó sobre el hueco de la escalera");
        h.succeed();
    }

    /** Cuartel: un zombi en la aldea es enemigo; una vaca, un cerdo y un creeper, no. */
    @GameTest(template = TEMPLATE)
    public static void quartersSeesEnemies(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        try {
            var zombie = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
            zombie.setNoAi(true);
            h.spawn(EntityType.COW, new BlockPos(5, 2, 5)).setNoAi(true);
            h.spawn(EntityType.PIG, new BlockPos(6, 2, 3)).setNoAi(true);
            h.spawn(EntityType.CREEPER, new BlockPos(3, 2, 6)).setNoAi(true);
            List<net.minecraft.world.entity.LivingEntity> enemies = Quarters.enemiesIn(level, v);
            // (otras pruebas corren al lado: solo se mira que esté el zombi y que no haya vacas ni cerdos)
            check(enemies.contains(zombie), "el zombi no es enemigo");
            check(enemies.stream().noneMatch(e -> e instanceof net.minecraft.world.entity.animal.Cow || e instanceof net.minecraft.world.entity.animal.Pig
                    || e instanceof net.minecraft.world.entity.monster.Creeper),
                    "una vaca, un cerdo o un creeper quedaron como enemigos");
        } finally {
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void villageBarracksDefends(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            BarracksData.Barracks b = BarracksData.get(level).getOrCreate(camp.getBlockPos(), owner);
            for (int i = 0; i < 3; i++) b.soldiers.add(sheet(i));
            check(Invasions.guardsAt(level, owner, v.center()).size() == 3, "el barracón de la aldea no cuenta para defenderla: "
                    + Invasions.guardsAt(level, owner, v.center()).size());
        } finally {
            VillageStorehouse.forget(level, v);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void assignAFreeVillager(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        openSky(h);
        // Una huerta: 3×3 de tierra de cultivo con agua en el medio
        for (int x = 8; x <= 10; x++) for (int z = 8; z <= 10; z++) {
            h.setBlock(new BlockPos(x, 1, z), x == 9 && z == 9 ? Blocks.WATER : Blocks.FARMLAND);
        }
        Villager villager = h.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 3));
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        Villager smith = h.spawn(EntityType.VILLAGER, new BlockPos(5, 2, 3));
        smith.setNoAi(true);
        smith.setVillagerData(smith.getVillagerData().setProfession(VillagerProfession.ARMORER).setLevel(2));
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner, "ale-h16"));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            check(VillageManagement.isFree(villager) && !VillageManagement.isFree(smith), "libre / con oficio mal reconocidos");
            var farm = VillageManagement.findFarm(level, v.center());
            check(farm != null && farm.getXSpan() == 3 && farm.getZSpan() == 3, "no encontró la huerta de 3×3: " + farm);

            VillageManagement.Data d = VillageManagement.data(player, v);
            long free = d.free().stream().filter(r -> !r.employed()).count();
            check(free == 1 && d.employed() == 1, "aldeanos: " + free + " libres, " + d.employed() + " con oficio");
            check(d.capacity() == 80, "lugar en el barracón de la aldea: " + d.capacity());

            CampBlockEntity farmCamp = VillageStorehouse.ensure(level, v);
            check(farmCamp != null, "no se puso el Campamento de la aldea");
            farmCamp.insert(new ItemStack(Items.IRON_HOE));
            farmCamp.insert(new ItemStack(Items.WHEAT_SEEDS, 8));
            farmCamp.insert(new ItemStack(Items.CHEST));
            VillageManagement.assign(player, v, villager.getUUID(), VillageManagement.Job.FARMER);
            check(villager.isRemoved(), "el aldeano sigue siendo aldeano");
            List<AbstractWorkerEntity> workers = VillageManagement.workers(level, v.center(), owner);
            check(workers.size() == 1 && workers.get(0).getType() == com.talhanation.workers.init.ModEntityTypes.FARMER.get(),
                    "no apareció el granjero tuyo");
            check(workers.get(0).getIsOwned() && workers.get(0).getFollowState() == 0, "el granjero no es tuyo o no está para trabajar");
            check(level.getEntitiesOfClass(CropArea.class, around).size() == 1, "no se armó el campo de cultivo sobre la huerta");
            check(VillageManagement.findFarm(level, v.center()) == null, "la huerta sigue sin zona");

            // H21: a un aldeano con oficio de vanilla también se le puede dar uno de Workers (pierde sus tratos)
            VillageManagement.assign(player, v, smith.getUUID(), VillageManagement.Job.MINER);
            check(smith.isRemoved() && !level.getEntitiesOfClass(com.talhanation.workers.entities.MinerEntity.class, around).isEmpty(),
                    "no convirtió al aldeano con oficio");
        } finally {
            VillageStorehouse.forget(level, v);
            for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, around)) w.discard();
            for (CropArea a : level.getEntitiesOfClass(CropArea.class, around)) a.discard();
            villager.discard();
            smith.discard();
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void assignASmith(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        openSky(h);
        Villager villager = h.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner, "ale-h16b"));
        AABB around = new AABB(v.center()).inflate(32);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            camp.insert(new ItemStack(Items.ANVIL));
            VillageManagement.assign(player, v, villager.getUUID(), VillageManagement.Job.SMITH);
            check(villager.isRemoved(), "el aldeano sigue siendo aldeano");
            List<SmithEntity> smiths = level.getEntitiesOfClass(SmithEntity.class, around);
            check(smiths.size() == 1, "no apareció el Herrero");
            SmithEntity smith = smiths.get(0);
            check(smith.getIsOwned() && owner.equals(smith.getOwnerUUID()), "el Herrero no es tuyo");
            check(camp.getBlockPos().equals(smith.getCamp()), "el Herrero no quedó atado al almacén de la aldea");
            check(smith.getWorkplace() != null && SmithWorkGoal.anvilNear(level, smith.getWorkplace()) != null,
                    "el Herrero no tiene su lugar al lado de un yunque");
            check(com.ale.warbandcraft.ai.Soldiers.isWorker(smith), "el Herrero se toma como soldado");
            check(VillageManagement.smithsOf(level, camp.getBlockPos()) == 1, "no cuenta como herrero del almacén");

            // Trabaja: 1 lingote + 1 carbón del almacén → 2 de armamento
            camp.insert(new ItemStack(Items.IRON_INGOT, 2));
            camp.insert(new ItemStack(Items.COAL, 2));
            SmithWorkGoal goal = new SmithWorkGoal(smith);
            check(goal.forge(level), "no forjó con hierro y carbón en el almacén");
            check(camp.count(Items.IRON_INGOT) == 1 && camp.count(Items.COAL) == 1, "no gastó un lingote y un carbón");
            check(camp.count(com.ale.warbandcraft.kit.Armament.item()) == SmithEntity.ARMAMENT_PER_IRON, "no dejó el armamento");

            // Pedido de Ale: con el tope del día gastado, lo marcado para desguazar lo sigue haciendo (y no gasta el tope)
            long day = level.getDayTime() / 24000L;
            while (smith.ironLeftToday(day) > 0) smith.forged();
            camp.insert(new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            camp.toggleScrap(Items.CHAINMAIL_CHESTPLATE);
            check(goal.forge(level), "con el tope gastado no desguazó lo marcado");
            check(camp.count(Items.CHAINMAIL_CHESTPLATE) == 0, "la pieza marcada sigue en el almacén");
            check(camp.count(com.ale.warbandcraft.kit.Armament.item()) > SmithEntity.ARMAMENT_PER_IRON, "el desguace no dejó armamento");
            check(!goal.forge(level), "sin piezas y con el tope gastado no tendría que trabajar");
        } finally {
            for (SmithEntity s : level.getEntitiesOfClass(SmithEntity.class, around)) s.discard();
            villager.discard();
            VillageStorehouse.forget(level, v);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void markAndDeleteZones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        openSky(h);
        Villager a = h.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        a.setNoAi(true);
        a.setVillagerData(a.getVillagerData().setProfession(VillagerProfession.NONE));
        Villager b = h.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 2));
        b.setNoAi(true);
        b.setVillagerData(b.getVillagerData().setProfession(VillagerProfession.NONE));
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner, "ale-h16c"));
        AABB around = new AABB(v.center()).inflate(40);
        try {
            VillageManagement.assign(player, v, a.getUUID(), VillageManagement.Job.MINER);
            VillageManagement.assign(player, v, b.getUUID(), VillageManagement.Job.FISHERMAN);
            com.talhanation.workers.entities.MinerEntity miner = level.getEntitiesOfClass(com.talhanation.workers.entities.MinerEntity.class, around).get(0);
            com.talhanation.workers.entities.FishermanEntity fisher = level.getEntitiesOfClass(com.talhanation.workers.entities.FishermanEntity.class, around).get(0);
            check(miner.getCurrentWorkArea() == null, "el minero tiene zona sin marcarla");

            BlockPos o = h.absolutePos(new BlockPos(1, 2, 1));
            CampBlockEntity zc = VillageStorehouse.ensure(level, v);
            check(zc != null, "no se puso el Campamento de la aldea");
            var lacking = WorkZones.mark(player, v, miner.getUUID(), o.getX(), o.getZ(), o.getX() + 7, o.getZ() + 2);
            check(lacking.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t0
                    && t0.getKey().equals("msg.warbandcraft.zone.missing") && miner.getCurrentWorkArea() == null,
                    "creó la mina sin pico ni cofre en el almacén: " + lacking.getString());
            zc.insert(new ItemStack(Items.IRON_PICKAXE));
            zc.insert(new ItemStack(Items.CHEST));
            check(!WorkZones.mark(player, v, miner.getUUID(), o.getX(), o.getZ(), o.getX() + 40, o.getZ() + 4).getString().isEmpty()
                    && miner.getCurrentWorkArea() == null, "aceptó una mina de 41 de lado");
            WorkZones.mark(player, v, miner.getUUID(), o.getX(), o.getZ(), o.getX() + 7, o.getZ() + 2);   // escalera de 8×3
            check(miner.getCurrentWorkArea() instanceof com.talhanation.workers.entities.workarea.MiningArea, "no se creó la mina enganchada al minero");
            AABB mineBox = miner.getCurrentWorkArea().getArea();
            check(mineBox.minX <= o.getX() && mineBox.maxX >= o.getX() + 8 && mineBox.minZ <= o.getZ() && mineBox.maxZ >= o.getZ() + 3,
                    "la mina no cubre los 8×3 marcados: " + mineBox);
            check(WorkZones.areas(level, v.center(), owner).stream().anyMatch(z -> z.id().equals(miner.getCurrentWorkArea().getUUID())),
                    "la mina no aparece en la lista de zonas");

            zc.insert(new ItemStack(Items.FISHING_ROD));
            zc.insert(new ItemStack(Items.CHEST));
            BlockPos f = h.absolutePos(new BlockPos(1, 2, 7));
            var noWater = WorkZones.mark(player, v, fisher.getUUID(), f.getX(), f.getZ(), f.getX() + 3, f.getZ() + 3);
            check(noWater.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().equals("msg.warbandcraft.zone.no_water"), "creó una zona de pesca sin agua: " + noWater.getString());

            // Al lado de la mina, su cofre (con su zona de almacén) para dejar lo que saca
            BlockPos chest = net.minecraft.core.BlockPos.betweenClosedStream(o.offset(-2, -3, -2), o.offset(9, 3, 7))
                    .filter(p -> level.getBlockState(p).is(Blocks.CHEST)).map(BlockPos::immutable).findFirst().orElse(null);
            check(chest != null, "no se puso el cofre de la mina");

            UUID mine = miner.getCurrentWorkArea().getUUID();

            // H21, Auto-asignar: la mina se queda sin minero y hay un aldeano libre → pasa a trabajar ahí solo
            miner.discard();
            Villager c = h.spawn(EntityType.VILLAGER, new BlockPos(4, 2, 2));
            c.setNoAi(true);
            c.setVillagerData(c.getVillagerData().setProfession(VillagerProfession.NONE));
            AbstractWorkerEntity auto = WorkZones.autoAssign(level, v);
            check(auto instanceof com.talhanation.workers.entities.MinerEntity && c.isRemoved(), "Auto-asignar no le dio la mina vacía al libre");
            check(auto.getCurrentWorkArea() != null && auto.getCurrentWorkArea().getUUID().equals(mine), "el minero nuevo no quedó en la mina vacía");
            check(WorkZones.autoAssign(level, v) == null, "Auto-asignar siguió sin zonas vacías ni libres");

            WorkZones.delete(player, v, mine);
            check(level.getEntitiesOfClass(com.talhanation.workers.entities.workarea.MiningArea.class, around).isEmpty(), "la mina no se borró");
            check(!level.getBlockState(chest).is(Blocks.CHEST), "el cofre de la mina quedó después de borrarla");
        } finally {
            VillageStorehouse.forget(level, v);
            for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, around)) w.discard();
            for (var z : level.getEntitiesOfClass(com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity.class, around)) z.discard();
            a.discard();
            b.discard();
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void markFarmAndPen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        openSky(h);
        Villager a = h.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        a.setNoAi(true);
        a.setVillagerData(a.getVillagerData().setProfession(VillagerProfession.NONE));
        Villager b = h.spawn(EntityType.VILLAGER, new BlockPos(3, 2, 2));
        b.setNoAi(true);
        b.setVillagerData(b.getVillagerData().setProfession(VillagerProfession.NONE));
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner, "ale-h16f"));
        AABB around = new AABB(v.center()).inflate(40);
        try {
            CampBlockEntity zc = VillageStorehouse.ensure(level, v);
            check(zc != null, "no se puso el Campamento de la aldea");
            VillageManagement.assign(player, v, a.getUUID(), VillageManagement.Job.FARMER);   // sin materiales: Auto no crea nada
            VillageManagement.assign(player, v, b.getUUID(), VillageManagement.Job.ANIMAL_FARMER);
            var farmer = level.getEntitiesOfClass(com.talhanation.workers.entities.FarmerEntity.class, around).get(0);
            var herder = level.getEntitiesOfClass(com.talhanation.workers.entities.AnimalFarmerEntity.class, around).get(0);
            check(herder.getCurrentWorkArea() == null, "Auto creó un corral sin trigo ni cofre");
            zc.insert(new ItemStack(Items.IRON_HOE));
            zc.insert(new ItemStack(Items.WHEAT_SEEDS, 8));
            zc.insert(new ItemStack(Items.WHEAT, 8));
            zc.insert(new ItemStack(Items.CHEST, 2));

            BlockPos o = h.absolutePos(new BlockPos(1, 2, 11));   // lejos del almacén (su zona de almacén ocupa el centro)
            var farm = WorkZones.mark(player, v, farmer.getUUID(), o.getX(), o.getZ(), o.getX() + 6, o.getZ() + 3);   // rectángulo: antes no entraba nunca
            check(farmer.getCurrentWorkArea() instanceof com.talhanation.workers.entities.workarea.CropArea, "no se creó la huerta: " + farm.getString());
            BlockPos p = h.absolutePos(new BlockPos(11, 2, 1));
            var pen = WorkZones.mark(player, v, herder.getUUID(), p.getX(), p.getZ(), p.getX() + 3, p.getZ() + 5);
            check(herder.getCurrentWorkArea() instanceof com.talhanation.workers.entities.workarea.AnimalPenArea, "no se creó el corral: " + pen.getString());
            AABB penBox = herder.getCurrentWorkArea().getArea();
            check(penBox.minX == p.getX() && penBox.maxX == p.getX() + 4 && penBox.minZ == p.getZ() && penBox.maxZ == p.getZ() + 6,
                    "el corral no es el rectángulo marcado: " + penBox);
            var kind = ((com.talhanation.workers.entities.workarea.AnimalPenArea) herder.getCurrentWorkArea()).getAnimalType();
            check(kind == WorkZones.biomeAnimal(level, p), "el corral no tiene el animal del bioma: " + kind);
            check(level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, penBox.inflate(1)).size() >= WorkZones.PEN_START_ANIMALS,
                    "el corral arrancó sin animales");
        } finally {
            VillageStorehouse.forget(level, v);
            for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, around)) w.discard();
            for (var z : level.getEntitiesOfClass(com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity.class, around)) z.discard();
            for (var an : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, around)) an.discard();
            a.discard();
            b.discard();
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void territoryAndSmithy(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        openSky(h);
        net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(owner, "ale-h16d"));
        com.ale.warbandcraft.village.Territory t = com.ale.warbandcraft.village.Territory.get(level);
        try {
            com.ale.warbandcraft.village.Territory.ensure(level, v);
            List<net.minecraft.world.level.ChunkPos> chunks = t.of(v.center());
            net.minecraft.world.level.ChunkPos home = new net.minecraft.world.level.ChunkPos(v.center());
            check(chunks.contains(home) && chunks.size() >= 9, "territorio de arranque raro: " + chunks.size() + " chunks");

            // Jurisdicción del Campamento de la aldea = el territorio
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            BlockPos far = new BlockPos(home.getMinBlockX() + 8, v.center().getY(), home.getMinBlockZ() + 8);
            for (net.minecraft.world.level.ChunkPos c : chunks) {
                if (Math.abs(c.x - home.x) + Math.abs(c.z - home.z) >= 2) {
                    far = new BlockPos(c.getMinBlockX() + 8, v.center().getY(), c.getMinBlockZ() + 8);
                    break;
                }
            }
            check(CampBlockEntity.covers(level, camp.getBlockPos(), far), "un punto del territorio no es jurisdicción del Campamento");
            check(!CampBlockEntity.covers(level, camp.getBlockPos(), v.center().offset(300, 0, 0)), "lejos del territorio cuenta como jurisdicción");

            // Agrandar: un chunk pegado, pagando
            net.minecraft.world.level.ChunkPos next = null;
            for (net.minecraft.world.level.ChunkPos c : chunks) {
                net.minecraft.world.level.ChunkPos n = new net.minecraft.world.level.ChunkPos(c.x + 1, c.z);
                if (!chunks.contains(n) && t.centerAt(n) == null) {
                    next = n;
                    break;
                }
            }
            check(next != null, "no hay chunk libre pegado");
            player.getInventory().add(new ItemStack(Items.EMERALD, com.ale.warbandcraft.village.Territory.CHUNK_COST));
            int before = chunks.size();
            com.ale.warbandcraft.village.Territory.expand(player, v, next);
            check(t.of(v.center()).size() == before + 1, "no agrandó el territorio");
            check(player.getInventory().countItem(Items.EMERALD) == 0, "no cobró las esmeraldas");
            com.ale.warbandcraft.village.Territory.expand(player, v, new net.minecraft.world.level.ChunkPos(home.x + 40, home.z));
            check(t.of(v.center()).size() == before + 1, "agrandó con un chunk lejos");

            // Herrería (H12): el herrero trabaja al lado de su yunque
            BlockPos site = h.absolutePos(new BlockPos(1, 1, 1));
            TownData.Town town = TownData.get(level).getOrCreate(v.center());
            town.built.add(Upgrade.SMITHY);
            town.sites.put(Upgrade.SMITHY.siteKey(), site);
            BlockPos anvil = site.offset(4, 1, 4);
            level.setBlockAndUpdate(anvil, Blocks.ANVIL.defaultBlockState());
            BlockPos place = WorkZones.smithyPlace(level, v);
            check(place != null && place.distManhattan(anvil) == 1, "el lugar en la Herrería no está al lado del yunque: " + place);
        } finally {
            TownData.get(level).remove(v.center());
            VillageStorehouse.forget(level, v);
            com.ale.warbandcraft.village.Territory.remove(level, v.center());
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Las pruebas vienen con un techo de barreras: las zonas miden el piso desde arriba. */
    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 12; x++) for (int z = -1; z <= 12; z++) for (int y = 2; y <= 10; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    // ------------------------------------------------------------------ ayudas

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), FACTION, 0).withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static BarracksData.Soldier sheet(int i) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "recruits:recruit");
        return new BarracksData.Soldier(tag, null, false, null, 0F, 1, "Soldado " + i, null, 0, 1);
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h16@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
