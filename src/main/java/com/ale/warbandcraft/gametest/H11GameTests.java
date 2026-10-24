package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Prisoners;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.network.OpenPanelMessage;
import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.village.VillageData;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * Pruebas del Hito 11 (Panel de control): qué muestra (Campamentos, aldeas, rutas, envíos, detalle con
 * presos y destinos) y qué órdenes se pueden dar desde lejos (reclutar sí, sacar soldados no).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H11GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 1);
    private static final BlockPos B = new BlockPos(1, 1, 13);
    private static final BlockPos C = new BlockPos(13, 1, 13);
    private static final BlockPos SOLDIER_POS = new BlockPos(4, 2, 4);

    @GameTest(template = TEMPLATE)
    public static void panelShowsTheRealm(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        CampBlockEntity campA = placeCamp(h, A);
        BlockPos a = campA.getBlockPos(), b = placeCamp(h, B).getBlockPos();
        BlockPos center = h.absolutePos(C);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h11"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        try {
            route(h, a, b);
            route(h, a, center);
            storeSoldiers(h, a, 3);
            imprison(h, a);
            campA.insert(new ItemStack(Items.EMERALD, 7));
            campA.insert(new ItemStack(Items.BREAD, 10));   // 50 puntos
            check(Routes.send(level, owner, a, b, Map.of("", 1), List.of()) == null, "no salió el envío");

            FakePlayer player = player(h, "ale-h11-panel");
            player.moveTo(a.getX() + 2.5, a.getY(), a.getZ() + 2.5);
            OpenPanelMessage msg = roundTrip(OpenPanelMessage.of(player, Panel.TAB_CAMPS, a));

            check(msg.camps.size() == 2, "deberían verse 2 Campamentos, hay " + msg.camps.size());
            Panel.CampInfo infoA = msg.camps.stream().filter(c -> c.pos().equals(a)).findFirst().orElse(null);
            check(infoA != null, "no aparece el Campamento A");
            check(infoA.soldiers() == 2, "A debería tener 2 soldados (1 salió de envío), tiene " + infoA.soldiers());
            check(infoA.prisoners() == 1, "A debería tener 1 preso");
            check(infoA.emeralds() == 7, "esmeraldas mal contadas: " + infoA.emeralds());
            check(infoA.foodDays() == 50 / (2 * BarracksData.FOOD_PER_SOLDIER), "días de comida mal: " + infoA.foodDays());
            check(infoA.near(), "el jugador está al lado: debería poder sacar soldados");

            check(msg.villages.size() == 1 && msg.villages.get(0).pos().equals(center), "no aparece la aldea");
            check(a.equals(msg.villages.get(0).sendTo()), "el almacén de la aldea debería ir al Campamento A (el único conectado)");
            check(msg.routes.size() == 2, "deberían verse 2 rutas, hay " + msg.routes.size());
            check(msg.shipments.size() == 1 && msg.shipments.get(0).soldiers() == 1, "no aparece el envío en camino");

            check(msg.detail != null, "falta el detalle de A");
            check(msg.detail.rows().size() == 1 && msg.detail.rows().get(0).count() == 2, "el detalle no muestra los 2 reclutas");
            check(msg.detail.prisoners().size() == 1, "el detalle no muestra al preso");
            check(msg.detail.destinations().size() == 2, "desde A se debería poder mandar a B y a la aldea");

            int[] totals = msg.totals();
            check(totals[0] == 2 && totals[1] == 1 && totals[5] == 1, "el resumen está mal");

            // Otro jugador no ve nada de esto, ni el detalle de un Campamento ajeno
            FakePlayer other = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "otro-h11"));
            OpenPanelMessage foreign = OpenPanelMessage.of(other, Panel.TAB_CAMPS, a);
            check(foreign.camps.isEmpty() && foreign.villages.isEmpty() && foreign.routes.isEmpty() && foreign.shipments.isEmpty()
                    && foreign.detail == null, "otro jugador ve tu reino");
        } finally {
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void remoteOrders(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = placeCamp(h, A).getBlockPos();
        storeSoldiers(h, a, 2);
        imprison(h, a);
        FakePlayer player = player(h, "ale-h11-lejos");
        player.moveTo(a.getX() + 500.5, a.getY(), a.getZ() + 0.5);

        BarracksData.Barracks b = BarracksData.get(level).at(a);
        Panel.deploy(player, a, "", 1);
        check(b.soldiers.size() == 2, "sacó un soldado estando a 500 bloques");
        Panel.recruit(player, a, b.prisoners.isEmpty() ? "" : b.prisoners.get(0).rowKey());
        check(b.prisoners.isEmpty() && b.soldiers.size() == 3, "no reclutó al preso desde lejos");

        player.moveTo(a.getX() + 3.5, a.getY(), a.getZ() + 0.5);
        Panel.deploy(player, a, "", 1);
        check(b.soldiers.size() == 2, "no sacó un soldado estando al lado");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void sendFromPanel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos(), b = placeCamp(h, B).getBlockPos();
        BlockPos center = h.absolutePos(C);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h11b"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        try {
            route(h, a, b);
            storeSoldiers(h, a, 2);
            FakePlayer player = player(h, "ale-h11-mandar");
            player.moveTo(a.getX() + 800.5, a.getY(), a.getZ() + 0.5);   // lejos: mandar se puede igual

            Panel.sendSoldiers(player, a, "", 5, b);
            List<RouteData.Shipment> list = RouteData.get(level).shipmentsOf(owner);
            check(list.size() == 1 && list.get(0).soldiers().size() == 2, "no salieron los 2 soldados (se pidieron 5)");
            check(BarracksData.get(level).count(a) == 0, "los soldados siguen en el barracón");

            // Almacén de la aldea: sin ruta a un Campamento no sale; con ruta va al conectado
            VillageData.get(level).setStock(center, new com.ale.warbandcraft.village.VillageStock.Stock(5, 3, 0));
            Panel.sendStock(player, center);
            check(RouteData.get(level).shipmentsOf(owner).size() == 1, "salió el almacén sin ruta");
            route(h, b, center);
            Panel.sendStock(player, center);
            List<RouteData.Shipment> after = RouteData.get(level).shipmentsOf(owner);
            check(after.size() == 2, "no salió el almacén de la aldea");
            check(after.stream().anyMatch(s -> s.from().equals(center) && s.to().equals(b) && !s.items().isEmpty()),
                    "el almacén no va al Campamento B (el conectado)");
        } finally {
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void villageGuardsCounted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = placeCamp(h, A).getBlockPos();
        BlockPos center = h.absolutePos(C);
        VillageData.Village v = new VillageData.Village(center, new ResourceLocation(WarbandCraft.MODID, "prueba_h11c"), 0).withOwner(owner);
        VillageData.get(level).add(v);
        try {
            route(h, a, center);
            storeSoldiers(h, a, 3);
            check(Routes.send(level, owner, a, center, Map.of("", 3), List.of()) == null, "no salió el envío a la aldea");
            RouteData.Shipment s = RouteData.get(level).shipmentsOf(owner).get(0);
            RouteData.get(level).replace(new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(),
                    level.getGameTime(), s.soldiers(), s.items()));
            Routes.tickShipments(level);
            check(Panel.guardsAt(level, owner, center) == 3, "deberían contarse 3 guardias, hay " + Panel.guardsAt(level, owner, center));
            check(Panel.guardsAt(level, UUID.randomUUID(), center) == 0, "cuenta guardias de otro jugador");
        } finally {
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static OpenPanelMessage roundTrip(OpenPanelMessage msg) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        OpenPanelMessage.encode(msg, buf);
        OpenPanelMessage back = OpenPanelMessage.decode(buf);
        check(buf.readableBytes() == 0, "el mensaje del panel no se leyó entero");
        return back;
    }

    private static FakePlayer player(GameTestHelper h, String name) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(owner(h), name));
    }

    private static void route(GameTestHelper h, BlockPos from, BlockPos to) {
        Component error = Routes.register(h.getLevel(), owner(h), UUID.randomUUID(), "r", List.of(from, to));
        check(error == null, "no se registró la ruta: " + (error == null ? "" : error.getString()));
    }

    private static void storeSoldiers(GameTestHelper h, BlockPos camp, int n) {
        List<AbstractRecruitEntity> made = new ArrayList<>();
        for (int i = 0; i < n; i++) made.add(spawnRecruit(h, SOLDIER_POS.east(i), true));
        for (AbstractRecruitEntity r : made) {
            check(Barracks.store(h.getLevel(), r, camp, false) == Barracks.StoreResult.OK, "no se guardó un soldado");
        }
    }

    private static void imprison(GameTestHelper h, BlockPos camp) {
        AbstractRecruitEntity enemy = spawnRecruit(h, SOLDIER_POS.south(2), false);
        check(Prisoners.imprison(h.getLevel(), enemy, owner(h)), "no se pudo llevar preso");
        check(BarracksData.get(h.getLevel()).at(camp).prisoners.size() == 1, "el preso no quedó en el Campamento");
    }

    private static CampBlockEntity placeCamp(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(rel) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h, BlockPos pos, boolean owned) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, pos);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) recruit.setItemSlot(slot, ItemStack.EMPTY);
        recruit.getInventory().clearContent();
        if (owned) {
            recruit.setOwnerUUID(Optional.of(owner(h)));
            recruit.setIsOwned(true);
        }
        return recruit;
    }

    /** Un "jugador" distinto por prueba (las pruebas corren en paralelo, una al lado de la otra). */
    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h11@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
