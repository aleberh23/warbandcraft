package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.RoutePostBlock;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.route.Travel;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 13 (rutas y viaje): registrar rutas (extremos y terreno explorado), destinos encadenados,
 * envíos de soldados e ítems (a Campamentos y a aldeas), capacidad y el viaje con escolta.
 * Los envíos no se entregan solos en el servidor de pruebas: acá se adelanta la llegada y se entrega a mano.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H13GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 1);
    private static final BlockPos B = new BlockPos(1, 1, 13);
    private static final BlockPos C = new BlockPos(13, 1, 13);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 2, 4);

    // ------------------------------------------------------------------ registrar

    @GameTest(template = TEMPLATE)
    public static void registerNeedsTwoOwnEndpoints(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos();
        BlockPos far = h.absolutePos(new BlockPos(1, 1, 60));   // a 59 bloques: lejos de todo punto tuyo

        check(Routes.register(level, owner, UUID.randomUUID(), "x", List.of(far, far.east(3))) != null, "aceptó una ruta sin extremos tuyos");
        check(Routes.register(level, owner, UUID.randomUUID(), "x", List.of(a, far)) != null, "aceptó una ruta con un solo extremo tuyo");
        check(Routes.register(level, owner, UUID.randomUUID(), "x", List.of(a, a.east(2))) != null, "aceptó una ruta que vuelve al mismo punto");

        BlockPos b = placeCamp(h, B).getBlockPos();
        UUID id = UUID.randomUUID();
        Component error = Routes.register(level, owner, id, "Camino Real", List.of(a.east(2), a.east(2).south(6), b.east(2)));
        check(error == null, "rechazó una ruta válida: " + (error == null ? "" : error.getString()));
        RouteData.Route r = RouteData.get(level).route(id);
        check(r != null && r.touches(a) && r.touches(b), "la ruta no quedó guardada uniendo los dos Campamentos");
        check(r.length() == 12, "largo mal calculado: " + r.length());
        check(!r.road(), "una ruta nueva no tiene camino construido");

        // Otro jugador no ve tus puntos
        check(Routes.register(level, UUID.randomUUID(), UUID.randomUUID(), "x", List.of(a, b)) != null, "otro jugador registró una ruta entre tus Campamentos");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void registerRejectsUnexplored(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos();
        BlockPos far = a.offset(100_000, 0, 0);
        CampRegistry.get(level).register(owner, far);   // un Campamento "suyo" en tierra nunca vista
        try {
            Component error = Routes.register(level, owner, UUID.randomUUID(), "x", List.of(a, far));
            check(error != null, "aceptó una ruta por terreno sin explorar");
            check(Routes.firstUnexplored(level, List.of(a, a.east(10))) == null, "el área de la prueba debería estar explorada");
        } finally {
            CampRegistry.get(level).unregister(owner, far);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ destinos

    @GameTest(template = TEMPLATE)
    public static void chainedDestinationsSumLegs(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos(), b = placeCamp(h, B).getBlockPos(), c = placeCamp(h, C).getBlockPos();
        RouteData.Route ab = route(h, a, b), bc = route(h, b, c);

        Routes.Destination toC = Routes.destination(level, owner, a, c);
        check(toC != null, "C no se alcanza desde A pasando por B");
        check(toC.legs() == 2, "debería pasar por 2 rutas, pasa por " + toC.legs());
        check(toC.ticks() == Routes.ticks(ab) + Routes.ticks(bc), "el tiempo no es la suma de los tramos");

        // Se pierde B: la cadena se corta
        CampRegistry.get(level).unregister(owner, b);
        check(Routes.destination(level, owner, a, c) == null, "C se sigue alcanzando sin B");
        check(!Routes.active(level, ab), "la ruta A-B debería quedar inactiva");
        h.succeed();
    }

    // ------------------------------------------------------------------ envíos

    @GameTest(template = TEMPLATE)
    public static void shipmentTabBuilds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity campA = placeCamp(h, A), campB = placeCamp(h, B);
        BlockPos a = campA.getBlockPos(), b = campB.getBlockPos();
        route(h, a, b);
        storeSoldiers(h, a, 3);
        check(Routes.send(level, owner(h), a, b, Map.of("", 1), List.of(new ItemStack(Items.BREAD, 4))) == null, "no salió el envío");
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner(h), "ale-h13-envios"));
        player.moveTo(a.getX() + 2.5, a.getY(), a.getZ() + 2.5);

        com.ale.warbandcraft.camp.ShipmentMenu.Info info = com.ale.warbandcraft.camp.ShipmentMenu.Info.of(player, campA);
        net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        info.write(buf);
        com.ale.warbandcraft.camp.ShipmentMenu.Info back = com.ale.warbandcraft.camp.ShipmentMenu.Info.read(buf);
        check(back.dests().size() == 1 && back.dests().get(0).pos().equals(b), "la pestaña no muestra el destino B");
        check(back.transit().size() == 1, "la pestaña no muestra el envío en camino");
        new com.ale.warbandcraft.camp.ShipmentMenu(0, player.getInventory(), info);
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void shipSoldiersToCamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity campA = placeCamp(h, A), campB = placeCamp(h, B);
        BlockPos a = campA.getBlockPos(), b = campB.getBlockPos();
        route(h, a, b);
        storeSoldiers(h, a, 3);

        Component error = Routes.send(level, owner(h), a, b, Map.of("", 2), List.of());
        check(error == null, "no salió el envío: " + (error == null ? "" : error.getString()));
        BarracksData data = BarracksData.get(level);
        check(data.count(a) == 1, "deberían quedar 1 en el barracón de origen, hay " + data.count(a));
        check(data.count(b) == 0, "llegaron antes de tiempo");

        RouteData.Shipment s = shipment(h);
        check(s.arriveTick() > level.getGameTime(), "el envío no tarda nada");
        Routes.tickShipments(level);
        check(data.count(b) == 0, "se entregó antes de la hora de llegada");

        arriveNow(level, s);
        Routes.tickShipments(level);
        check(data.count(b) == 2, "en el destino deberían haber 2, hay " + data.count(b));
        check(data.at(b).soldiers.stream().noneMatch(x -> x.guard), "a un Campamento llegan sin puesto");
        check(RouteData.get(level).shipmentsOf(owner(h)).isEmpty(), "el envío entregado sigue en camino");

        // Al sacarlos quedan atados al Campamento nuevo
        check(Barracks.deploy(level, b, null, 1) == 1, "no salió del barracón de destino");
        AbstractRecruitEntity out = level.getEntitiesOfClass(AbstractRecruitEntity.class, new net.minecraft.world.phys.AABB(b).inflate(8),
                r -> owner(h).equals(r.getOwnerUUID())).stream().findFirst().orElse(null);
        check(out != null && out.getPersistentData().getLong(Barracks.TAG_CAMP) == b.asLong(), "el soldado no quedó atado al Campamento de destino");
        out.discard();
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void shipItemsToCamp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CampBlockEntity campA = placeCamp(h, A), campB = placeCamp(h, B);
        route(h, campA.getBlockPos(), campB.getBlockPos());

        check(Routes.send(level, owner(h), campA.getBlockPos(), campB.getBlockPos(), Map.of(),
                List.of(new ItemStack(Items.BREAD, 10), new ItemStack(Items.IRON_INGOT, 3))) == null, "no salió el envío de ítems");
        arriveNow(level, shipment(h));
        Routes.tickShipments(level);
        check(campB.count(Items.BREAD) == 10 && campB.count(Items.IRON_INGOT) == 3, "los ítems no llegaron al almacén de destino");
        check(RouteData.get(level).shipmentsOf(owner(h)).isEmpty(), "el envío entregado sigue en camino");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void shipSoldiersToVillageAsGuards(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos();
        BlockPos center = h.absolutePos(C);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h13"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        try {
            route(h, a, center);
            storeSoldiers(h, a, 3);
            check(Routes.send(level, owner, a, center, Map.of(), List.of(new ItemStack(Items.BREAD))) != null,
                    "aceptó mandar ítems a una aldea");
            check(Routes.send(level, owner, a, center, Map.of("", 3), List.of()) == null, "no salió el envío a la aldea");
            arriveNow(level, shipment(h));
            Routes.tickShipments(level);

            BarracksData.Barracks home = BarracksData.get(level).at(a);
            check(home != null && home.soldiers.size() == 3, "los soldados deberían anotarse en el barracón más cercano");
            for (BarracksData.Soldier s : home.soldiers) {
                check(s.guard && s.post != null, "llegaron sin puesto de guardia");
                check(s.post.distanceTo(Vec3.atCenterOf(center)) <= 10, "el puesto quedó lejos del centro de la aldea");
            }
        } finally {
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void shipmentRespectsCapacity(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = placeCamp(h, A).getBlockPos(), b = placeCamp(h, B).getBlockPos();
        route(h, a, b);
        storeSoldiers(h, a, 2);
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks full = data.getOrCreate(b, owner(h));
        for (int i = 0; i < BarracksData.CAPACITY - 1; i++) {
            full.soldiers.add(new BarracksData.Soldier(new CompoundTag(), null, false, null, 0, 0, "relleno " + i, null, 0, 0));
        }
        data.changed();
        check(Routes.send(level, owner(h), a, b, Map.of("", 2), List.of()) != null, "mandó más de los que entran");
        check(data.count(a) == 2, "un envío rechazado sacó soldados del barracón");
        check(Routes.send(level, owner(h), a, b, Map.of("", 1), List.of()) == null, "rechazó un envío que entra justo");
        check(Routes.send(level, owner(h), a, b, Map.of("", 1), List.of()) != null, "no contó al que ya va en camino");
        data.remove(b);
        h.succeed();
    }

    // ------------------------------------------------------------------ viaje

    @GameTest(template = TEMPLATE)
    public static void travelBringsFollowers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = placeCamp(h, A).getBlockPos(), b = placeCamp(h, B).getBlockPos();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner(h), "ale-h13"));
        player.moveTo(a.getX() + 2.5, a.getY(), a.getZ() + 2.5);

        AbstractRecruitEntity follower = spawnRecruit(h, SOLDIER_POS);
        follower.setFollowState(1);
        AbstractRecruitEntity holder = spawnRecruit(h, SOLDIER_POS.east(2));
        holder.setFollowState(2);
        Vec3 holderAt = holder.position();

        int moved = Travel.arrive(player, b);
        check(moved == 1, "deberían viajar 1 soldado, viajaron " + moved);
        check(follower.position().distanceTo(Vec3.atCenterOf(b)) <= 10, "el que te sigue no llegó al destino");
        check(holder.position().distanceTo(holderAt) < 1, "el que estaba quieto viajó igual");
        follower.discard();
        holder.discard();
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void routePostTakesNearestNode(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = placeCamp(h, A).getBlockPos();
        BlockPos postRel = A.east(4);
        h.setBlock(postRel, ModBlocks.ROUTE_POST.get());
        BlockPos post = h.absolutePos(postRel);
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner(h), "ale-h13-poste"));

        player.moveTo(post.getX() + 40.5, post.getY(), post.getZ() + 0.5);
        check(!RoutePostBlock.usable(player, post), "el poste se puede usar desde 40 bloques");
        check(OpenRoutesMessage.of(player, null).here == null, "lejos de todo, sin poste, no deberías estar en ningún punto");
        OpenRoutesMessage fromPost = OpenRoutesMessage.of(player, null, post);
        check(fromPost.here != null && fromPost.here.pos().equals(a), "desde el poste deberías estar en el Campamento de al lado");

        player.moveTo(post.getX() + 2.5, post.getY(), post.getZ() + 0.5);
        check(RoutePostBlock.usable(player, post), "el poste no se puede usar estando al lado");
        check(!RoutePostBlock.usable(player, a), "un Campamento no es un poste");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static RouteData.Route route(GameTestHelper h, BlockPos from, BlockPos to) {
        UUID id = UUID.randomUUID();
        Component error = Routes.register(h.getLevel(), owner(h), id, "r", List.of(from, to));
        check(error == null, "no se registró la ruta: " + (error == null ? "" : error.getString()));
        return RouteData.get(h.getLevel()).route(id);
    }

    private static RouteData.Shipment shipment(GameTestHelper h) {
        List<RouteData.Shipment> list = RouteData.get(h.getLevel()).shipmentsOf(owner(h));
        check(list.size() == 1, "debería haber 1 envío en camino, hay " + list.size());
        return list.get(0);
    }

    /** Adelanta la llegada del envío a ahora. */
    private static void arriveNow(ServerLevel level, RouteData.Shipment s) {
        RouteData.get(level).replace(new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(),
                level.getGameTime(), s.soldiers(), s.items()));
    }

    private static void storeSoldiers(GameTestHelper h, BlockPos camp, int n) {
        List<AbstractRecruitEntity> made = new ArrayList<>();
        for (int i = 0; i < n; i++) made.add(spawnRecruit(h, SOLDIER_POS.east(i)));
        for (AbstractRecruitEntity r : made) {
            check(Barracks.store(h.getLevel(), r, camp, false) == Barracks.StoreResult.OK, "no se guardó un soldado");
        }
    }

    private static CampBlockEntity placeCamp(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(rel) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h, BlockPos pos) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, pos);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) recruit.setItemSlot(slot, ItemStack.EMPTY);
        recruit.getInventory().clearContent();
        recruit.setOwnerUUID(Optional.of(owner(h)));
        recruit.setIsOwned(true);
        return recruit;
    }

    /** Un "jugador" distinto por prueba (las pruebas corren en paralelo, una al lado de la otra). */
    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h13@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
