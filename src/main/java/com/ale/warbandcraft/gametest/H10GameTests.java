package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Forge;
import com.ale.warbandcraft.camp.WorkerZone;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStock;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.workers.entities.workarea.StorageArea;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 10 (economía): armamento que reemplaza piezas de metal, herreros, almacén de aldeas y
 * castillos (producción, retirar, mandar por ruta) y Villager Workers (no son soldados, zona de almacén).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H10GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);
    private static final BlockPos CHEST_POS = new BlockPos(3, 1, 1);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 2, 4);
    private static final BlockPos SMITH_POS = new BlockPos(11, 2, 1);
    private static final BlockPos CENTER = new BlockPos(13, 1, 13);

    // ------------------------------------------------------------------ armamento

    /** Al Ballestero le falta el peto de hierro: 8 de armamento lo cubren; con 7 no alcanza y el mensaje lo dice. */
    @GameTest(template = TEMPLATE)
    public static void armamentCoversMissingMetal(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        camp.insert(new ItemStack(Items.CROSSBOW));
        camp.insert(new ItemStack(Items.ARROW, 24));
        camp.insert(new ItemStack(Armament.item(), 7));
        ClassAssigner.Result fail = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit("ballestero"));
        check(!fail.ok(), "con 7 de armamento no debería alcanzar para el peto (8)");
        check(fail.message().getString().contains("8"), "el mensaje no dice cuánto armamento haría falta: " + fail.message().getString());

        camp.insert(new ItemStack(Armament.item(), 1));
        ClassAssigner.Result ok = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit("ballestero"));
        check(ok.ok(), "con 8 de armamento debería armarse: " + ok.message().getString());
        check(ok.soldier().getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "no recibió el peto de hierro");
        check(camp.count(Armament.item()) == 0, "quedó armamento: " + camp.count(Armament.item()));
        h.succeed();
    }

    /** Con el peto de verdad en el almacén, se usa el peto y el armamento queda. */
    @GameTest(template = TEMPLATE)
    public static void armamentPrefersRealPieces(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        camp.insert(new ItemStack(Items.CROSSBOW));
        camp.insert(new ItemStack(Items.ARROW, 24));
        camp.insert(new ItemStack(Items.IRON_CHESTPLATE));
        camp.insert(new ItemStack(Armament.item(), 8));
        ClassAssigner.Result ok = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit("ballestero"));
        check(ok.ok(), "no se armó: " + ok.message().getString());
        check(camp.count(Items.IRON_CHESTPLATE) == 0, "no usó el peto del almacén");
        check(camp.count(Armament.item()) == 8, "gastó armamento teniendo el peto: quedan " + camp.count(Armament.item()));
        h.succeed();
    }

    /** Un arco no es de metal: por más armamento que haya, sin arco no se arma un Arquero. */
    @GameTest(template = TEMPLATE)
    public static void armamentDoesNotReplaceBows(GameTestHelper h) {
        CampBlockEntity camp = placeCamp(h);
        camp.insert(new ItemStack(Items.ARROW, 64));
        camp.insert(new ItemStack(Items.LEATHER_HELMET));
        camp.insert(new ItemStack(Items.LEATHER_CHESTPLATE));
        camp.insert(new ItemStack(Items.LEATHER_LEGGINGS));
        camp.insert(new ItemStack(Items.LEATHER_BOOTS));
        camp.insert(new ItemStack(Armament.item(), 5));
        check(Armament.INSTANCE.value(Items.BOW) == 0 && Armament.INSTANCE.value(Items.LEATHER_CHESTPLATE) == 0,
                "el arco o el cuero tienen valor de armamento de metal");
        // H19: el básico sí se paga con solo armamento, pero el arco es caro (6): con 5 no alcanza y no gasta nada
        ClassAssigner.Result r = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit("arquero"));
        check(!r.ok(), "se armó un Arquero sin arco con 5 de armamento");
        check(camp.count(Armament.item()) == 5, "gastó armamento en un intento fallido");
        camp.insert(new ItemStack(Armament.item(), 59));
        r = ClassAssigner.assign(h.getLevel(), owner(h), spawnRecruit(h), kit("arquero"));
        check(r.ok(), "no se armó el Arquero básico con armamento");
        check(camp.count(Armament.item()) == 64 - Armament.INSTANCE.cost(Items.BOW, 1, true), "el arco no costó lo que vale");
        h.succeed();
    }

    // ------------------------------------------------------------------ herreros

    /** Un armero a 10 bloques convierte hierro + carbón en armamento (2 por lingote); un granjero no cuenta. */
    @GameTest(template = TEMPLATE)
    public static void smithMakesArmament(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        villager(h, SMITH_POS, VillagerProfession.FARMER);
        // Solo los de esta prueba: el radio del Campamento (24) llega a los herreros de las pruebas vecinas.
        check(ownSmiths(h, camp) == 0, "un granjero contó como herrero");
        villager(h, SMITH_POS.south(2), VillagerProfession.ARMORER);
        int smiths = ownSmiths(h, camp);
        check(smiths == 1, "debería haber 1 herrero, hay " + smiths);

        camp.insert(new ItemStack(Items.IRON_INGOT, 5));
        camp.insert(new ItemStack(Items.COAL, 2));
        camp.insert(new ItemStack(Items.CHARCOAL, 1));
        int made = Forge.workDay(level, camp, smiths);
        check(made == 6, "debería hacer 6 de armamento (3 carbones), hizo " + made);
        check(camp.count(Armament.item()) == 6, "el armamento no entró al almacén");
        check(camp.count(Items.IRON_INGOT) == 2, "deberían quedar 2 lingotes, quedan " + camp.count(Items.IRON_INGOT));
        check(camp.count(Items.COAL) + camp.count(Items.CHARCOAL) == 0, "sobró carbón");
        h.succeed();
    }

    /** Días atrasados: 3 días con 1 herrero = 24 lingotes → 48 de armamento (tope de 8 por día). */
    @GameTest(template = TEMPLATE)
    public static void smithCatchesUpMissedDays(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        villager(h, SMITH_POS, VillagerProfession.WEAPONSMITH);
        camp.insert(new ItemStack(Items.IRON_INGOT, 30));
        camp.insert(new ItemStack(Items.COAL, 30));
        long today = Barracks.today(level);
        check(Forge.catchUp(level, camp, today) == 0, "la primera vez no debería cobrar nada");
        camp.setLastForgeDay(today - 3);
        int made = Forge.catchUp(level, camp, today);
        check(made == 48, "3 días deberían dar 48 de armamento, dio " + made);
        check(camp.count(Items.IRON_INGOT) == 6, "deberían quedar 6 lingotes, quedan " + camp.count(Items.IRON_INGOT));
        check(Forge.catchUp(level, camp, today) == 0, "cobró dos veces el mismo día");
        h.succeed();
    }

    // ------------------------------------------------------------------ almacén de aldeas

    /** Un día: la aldea junta impuesto y comida; el castillo, además armamento; el tope se respeta. */
    @GameTest(template = TEMPLATE)
    public static void villageStockProduction(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village v = ownedVillage(h, false);
        try {
            VillageStock.Stock day = VillageStock.produce(level, v);
            VillageStock.Stock s = VillageStock.total(level, v);
            check(s.equals(day), "el almacén no tiene lo producido: " + s);
            check(s.emeralds() >= 3 && s.food() >= VillageStock.VILLAGE_FOOD && s.armament() == 0, "producción de aldea rara: " + s);
            // Con la aldea cargada, lo producido está en un barril de verdad
            BlockPos barrelPos = data.storehouse(v.center());
            check(barrelPos != null && level.getBlockState(barrelPos).is(ModBlocks.CAMP.get()), "no se puso el Campamento de la aldea");
            check(data.stock(v.center()).isEmpty(), "lo producido no entró al barril");
            check(VillageStorehouse.inBarrel(level, v).equals(day), "el barril no tiene lo producido");

            // Lo que espera (aldea descargada) tiene tope; después entra al barril
            data.setStock(v.center(), new VillageStock.Stock(9999, 9999, 9999));
            VillageStock.produce(level, v);
            check(VillageStock.total(level, v).equals(day.plus(VillageStock.cap(level, v))), "no respetó el tope: " + VillageStock.total(level, v));

            VillageData.Village castle = v.asCastle();
            data.add(castle);
            VillageStock.withdrawAllForTest(level, castle);
            VillageStock.produce(level, castle);
            check(VillageStock.total(level, castle).armament() == VillageStock.CASTLE_ARMAMENT, "el castillo no juntó armamento");

            data.add(castle.withOwner(UUID.randomUUID()));
            check(data.stock(castle.center()).isEmpty(), "al cambiar de dueño el almacén debería vaciarse");
        } finally {
            forget(level, v);
        }
        h.succeed();
    }

    /** Retirar: a ≤48 bloques todo pasa al jugador; lejos no deja. */
    @GameTest(template = TEMPLATE)
    public static void withdrawStock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village v = ownedVillage(h, false);
        try {
            data.setStock(v.center(), new VillageStock.Stock(12, 20, 4));
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner(h), "ale-h10-r"));
            player.getInventory().clearContent();
            BlockPos far = v.center().east(60);
            player.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5);
            VillageStock.withdraw(player);
            check(data.stock(v.center()).emeralds() == 12, "retiró estando lejos");

            player.moveTo(v.center().getX() + 0.5, v.center().getY() + 1, v.center().getZ() + 0.5);
            Component msg = VillageStock.withdraw(player);
            check(VillageStock.total(level, v).isEmpty(), "el almacén no quedó vacío: " + msg.getString());
            check(player.getInventory().countItem(Items.EMERALD) == 12, "no recibió las esmeraldas");
            check(player.getInventory().countItem(Items.BREAD) == 20, "no recibió el pan");
            check(player.getInventory().countItem(Armament.item()) == 4, "no recibió el armamento");
        } finally {
            forget(level, v);
        }
        h.succeed();
    }

    /** Mandar el almacén por ruta: sale un envío, la aldea queda vacía y al llegar entra al Campamento. */
    @GameTest(template = TEMPLATE)
    public static void sendStockByRoute(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData data = VillageData.get(level);
        CampBlockEntity camp = placeCamp(h);
        VillageData.Village v = ownedVillage(h, true);
        try {
            UUID id = UUID.randomUUID();
            Component error = Routes.register(level, owner(h), id, "r", List.of(camp.getBlockPos(), v.center()));
            check(error == null, "no se registró la ruta: " + (error == null ? "" : error.getString()));
            data.setStock(v.center(), new VillageStock.Stock(5, 10, 3));

            Component sent = VillageStock.send(level, owner(h), v.center(), camp.getBlockPos());
            check(sent == null, "no salió: " + (sent == null ? "" : sent.getString()));
            check(VillageStock.total(level, v).isEmpty(), "la aldea no quedó vacía");
            List<RouteData.Shipment> list = RouteData.get(level).shipmentsOf(owner(h));
            check(list.size() == 1, "debería haber 1 envío, hay " + list.size());
            RouteData.Shipment s = list.get(0);
            RouteData.get(level).replace(new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(),
                    level.getGameTime(), s.soldiers(), s.items()));
            Routes.tickShipments(level);
            check(camp.count(Items.EMERALD) == 5 && camp.count(Items.BREAD) == 10 && camp.count(Armament.item()) == 3,
                    "el almacén no llegó al Campamento");
            check(VillageStock.send(level, owner(h), v.center(), camp.getBlockPos()) != null, "mandó un almacén vacío");
        } finally {
            forget(level, v);
        }
        h.succeed();
    }

    /** El barril va adentro de la casa: con techo, sobre el piso y contra una pared. */
    @GameTest(template = TEMPLATE)
    public static void storehouseGoesIndoors(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // Casa de 5×5 (paredes de y=2 a 4, techo en y=5) con una puerta abierta
        for (int x = 6; x <= 10; x++) for (int z = 6; z <= 10; z++) {
            h.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
            for (int y = 2; y <= 4; y++) {
                boolean edge = x == 6 || x == 10 || z == 6 || z == 10;
                h.setBlock(new BlockPos(x, y, z), edge ? Blocks.OAK_PLANKS : Blocks.AIR);
            }
        }
        h.setBlock(new BlockPos(8, 2, 6), Blocks.AIR);
        h.setBlock(new BlockPos(8, 3, 6), Blocks.AIR);
        BlockPos a = h.absolutePos(new BlockPos(6, 2, 6)), b = h.absolutePos(new BlockPos(10, 5, 10));
        net.minecraft.world.level.levelgen.structure.BoundingBox box = net.minecraft.world.level.levelgen.structure.BoundingBox
                .fromCorners(a, b);
        BlockPos spot = VillageStorehouse.findSpot(level, new VillageStorehouse.Area(box, true));
        check(spot != null, "no encontró lugar en la casa");
        BlockPos rel = spot.subtract(h.absolutePos(BlockPos.ZERO));   // relativePos de vanilla da la posición espejada
        check(rel.getX() > 6 && rel.getX() < 10 && rel.getZ() > 6 && rel.getZ() < 10 && rel.getY() == 2,
                "el barril quedó fuera de la casa o en el aire: " + rel);
        check(rel.getX() == 7 || rel.getX() == 9 || rel.getZ() == 7 || rel.getZ() == 9, "el barril no quedó contra una pared: " + rel);
        h.succeed();
    }

    // ------------------------------------------------------------------ Villager Workers

    /** Un trabajador tuyo no es un soldado: no se guarda en el barracón ni aparece entre los de cerca. */
    @GameTest(template = TEMPLATE)
    public static void workersAreNotSoldiers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("workers", "farmer"));
        check(type != null, "Villager Workers no está cargado");
        Entity e = h.spawn(type, SOLDIER_POS);
        check(e instanceof AbstractRecruitEntity, "workers:farmer no es un AbstractRecruitEntity");
        AbstractRecruitEntity worker = (AbstractRecruitEntity) e;
        worker.setOwnerUUID(Optional.of(owner(h)));
        worker.setIsOwned(true);
        check(Soldiers.isWorker(worker), "no se reconoce como trabajador");
        check(!Soldiers.isWorker(spawnRecruit(h)), "un recluta se reconoce como trabajador");
        check(Barracks.storableNear(level, camp.getBlockPos(), owner(h)).stream().noneMatch(Soldiers::isWorker),
                "el trabajador aparece entre los que se pueden guardar");
        check(Barracks.store(level, worker, camp.getBlockPos(), false) != Barracks.StoreResult.OK, "el trabajador se guardó en el barracón");
        check(!worker.isRemoved(), "el trabajador desapareció");
        check(WorkerZone.workersNear(level, camp.getBlockPos(), owner(h)) == 1, "no lo cuenta como trabajador del Campamento");
        h.succeed();
    }

    /** "Zona de trabajo": crea una zona de almacén de Workers que cubre al Campamento y su cofre; la 2ª vez no. */
    @GameTest(template = TEMPLATE)
    public static void workerZoneCoversCamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity camp = placeCamp(h);
        h.setBlock(CHEST_POS, Blocks.CHEST);
        check(camp.toggleLink(h.absolutePos(CHEST_POS)) == CampBlockEntity.LinkResult.LINKED, "no se vinculó el cofre");
        StorageArea area = null;
        try {
            check(WorkerZone.create(level, camp, owner(h), "ale-h10") == WorkerZone.Result.CREATED, "no se creó la zona");
            List<StorageArea> areas = level.getEntitiesOfClass(StorageArea.class, new AABB(camp.getBlockPos()).inflate(40),
                    a -> a.getArea() != null && contains(a.getArea(), camp.getBlockPos()));   // las de pruebas vecinas, no
            check(areas.size() == 1, "debería haber 1 zona, hay " + areas.size());
            area = areas.get(0);
            AABB box = area.getArea();
            check(box != null && contains(box, camp.getBlockPos()) && contains(box, h.absolutePos(CHEST_POS)),
                    "la zona no cubre el Campamento y su cofre: " + box);
            check(owner(h).equals(area.getPlayerUUID()), "la zona no es del jugador");
            check(WorkerZone.create(level, camp, owner(h), "ale-h10") == WorkerZone.Result.OVERLAPPING, "creó una segunda zona encima");
        } finally {
            if (area != null) area.discard();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** El bloque {@code pos} está dentro de la caja de Workers (de esquina a esquina de bloques). */
    private static boolean contains(AABB box, BlockPos pos) {
        return pos.getX() >= box.minX && pos.getX() <= box.maxX && pos.getY() >= box.minY && pos.getY() <= box.maxY
                && pos.getZ() >= box.minZ && pos.getZ() <= box.maxZ;
    }

    private static VillageData.Village ownedVillage(GameTestHelper h, boolean castle) {
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h10"), 0).withOwner(owner(h));
        if (castle) v = v.asCastle();
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static void forget(ServerLevel level, VillageData.Village v) {
        VillageStorehouse.forget(level, v);   // H16: saca el Campamento de la aldea del registro
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    /** Herreros de {@link Forge#smiths} que son de esta prueba (a ≤4 bloques de donde los pone). */
    private static int ownSmiths(GameTestHelper h, CampBlockEntity camp) {
        BlockPos here = h.absolutePos(SMITH_POS);
        return (int) Forge.smiths(h.getLevel(), camp.getBlockPos()).stream().filter(v -> v.blockPosition().closerThan(here, 4)).count();
    }

    private static void villager(GameTestHelper h, BlockPos pos, VillagerProfession profession) {
        Villager v = h.spawn(EntityType.VILLAGER, pos);
        v.setNoAi(true);
        v.setVillagerData(v.getVillagerData().setProfession(profession).setLevel(2));
    }

    private static ResourceLocation kit(String name) {
        return new ResourceLocation(WarbandCraft.MODID, name);
    }

    private static CampBlockEntity placeCamp(GameTestHelper h) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, SOLDIER_POS);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) recruit.setItemSlot(slot, ItemStack.EMPTY);
        recruit.getInventory().clearContent();
        recruit.setOwnerUUID(Optional.of(owner(h)));
        recruit.setIsOwned(true);
        return recruit;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h10@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
