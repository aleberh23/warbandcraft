package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.market.Exchange;
import com.ale.warbandcraft.market.Market;
import com.ale.warbandcraft.market.MarketData;
import com.ale.warbandcraft.market.MarketView;
import com.ale.warbandcraft.market.Schedules;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas de H24 (el Mercado): se vende lo que sobra arriba del mínimo y las esmeraldas entran al almacén; los envíos
 * programados (fijo, reponer, el mínimo del origen, el día que toca); la Lonja pide de otro almacén y la Plaza acelera.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class MarketGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CAMP_POS = new BlockPos(1, 1, 1);
    private static final BlockPos CENTER = new BlockPos(13, 1, 13);

    /** Ofertas = lotes por encima del mínimo; vender saca del almacén y mete las esmeraldas; el dueño no se compra a sí mismo. */
    @GameTest(template = TEMPLATE)
    public static void marketSellsSurplus(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h, Upgrade.MARKET);
        try {
            check(Upgrade.MARKET_2.siteKey().equals(Upgrade.MARKET.siteKey()) && Upgrade.MARKET_3.isLevelUp(), "los niveles no comparten terreno");
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            camp.insert(new ItemStack(Items.BREAD, 64));
            camp.insert(new ItemStack(Items.BREAD, 36));
            camp.insert(new ItemStack(Items.EMERALD, 10));

            MerchantOffers offers = Market.offers(level, v);
            MerchantOffer bread = find(offers, Items.BREAD);
            check(bread != null, "no ofrece pan");
            check(find(offers, Items.EMERALD) == null, "vende esmeraldas");
            int lot = bread.getResult().getCount();
            check(bread.getMaxUses() == (100 - Market.DEFAULT_MIN) / lot, "lotes de pan: " + bread.getMaxUses());

            Market.setRule(level, v, "minecraft:bread", 100 - lot, -1, -1);
            check(find(Market.offers(level, v), Items.BREAD).getMaxUses() == 1, "el mínimo no cuenta");
            Market.setRule(level, v, "minecraft:bread", 100, -1, -1);
            check(find(Market.offers(level, v), Items.BREAD) == null, "vende por debajo del mínimo");
            Market.setRule(level, v, "minecraft:bread", 0, 5, 0);
            check(find(Market.offers(level, v), Items.BREAD) == null, "vende con la venta apagada");
            Market.setRule(level, v, "minecraft:bread", -1, -1, 1);
            bread = find(Market.offers(level, v), Items.BREAD);
            check(bread != null && bread.getCostA().getCount() == 5, "no tomó el precio nuevo");

            ServerPlayer buyer = player(level, h, "h24-buyer");
            Market.sold(level, v.center(), buyer, bread.getResult(), bread.getCostA().getCount());
            check(camp.count(Items.BREAD) == 100 - lot, "no salió el pan del almacén: " + camp.count(Items.BREAD));
            check(camp.count(Items.EMERALD) == 15, "no entraron las esmeraldas: " + camp.count(Items.EMERALD));
            MarketView view = Market.view(level, v);
            check(view.sales().size() == 1, "no anotó la venta");

            check(Market.buyerProblem(level, v, buyer) == null, "un jugador de afuera no puede comprar");
            ServerPlayer self = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(owner(h), "h24-owner"));
            check(Market.buyerProblem(level, v, self) != null, "el dueño se compra a sí mismo");
        } finally {
            cleanup(level, v);
        }
        h.succeed();
    }

    /** Envío programado: fijo, respeta el mínimo del origen, reponer cuenta lo que hay y lo que va en camino, y sale el día que toca. */
    @GameTest(template = TEMPLATE)
    public static void scheduledShipments(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        CampBlockEntity dest = placeCamp(h);
        VillageData.Village v = village(h, Upgrade.MARKET);
        UUID route = UUID.randomUUID();
        try {
            CampBlockEntity origin = VillageStorehouse.ensure(level, v);
            check(origin != null, "no se puso el Campamento de la aldea");
            Component error = Routes.register(level, owner, route, "r", List.of(dest.getBlockPos(), v.center()));
            check(error == null, "no se registró la ruta: " + (error == null ? "" : error.getString()));
            origin.insert(new ItemStack(Items.BREAD, 64));
            origin.insert(new ItemStack(Items.BREAD, 36));
            dest.insert(new ItemStack(Items.BREAD, 10));
            Market.setRule(level, v, "minecraft:bread", 0, -1, -1);

            check(Schedules.create(level, v).getStyle().getColor() != null, "no respondió al crear");
            MarketData.Market m = MarketData.get(level).at(v.center());
            check(m != null && m.schedules.size() == 1, "no se creó el envío programado");
            MarketData.Schedule s = m.schedules.get(0);
            Schedules.setDest(level, v, s.id, dest.getBlockPos());
            check(dest.getBlockPos().equals(s.to), "no tomó el destino");
            Schedules.setLine(level, v, s.id, "minecraft:bread", 16, false);

            Schedules.sendNow(level, v, s.id);
            check(origin.count(Items.BREAD) == 84, "fijo: no salieron 16 (quedan " + origin.count(Items.BREAD) + ")");
            check(shipped(level, owner) == 16, "fijo: el envío lleva " + shipped(level, owner));
            arriveAll(level, owner);
            check(dest.count(Items.BREAD) == 26, "no llegó al destino: " + dest.count(Items.BREAD));

            // Reponer hasta 40: faltan 14
            Schedules.setLine(level, v, s.id, "minecraft:bread", 40, true);
            Schedules.sendNow(level, v, s.id);
            check(shipped(level, owner) == 14, "reponer: mandó " + shipped(level, owner));
            // Con eso en camino no hace falta nada más
            Schedules.sendNow(level, v, s.id);
            check(shipped(level, owner) == 14, "reponer no contó lo que va en camino: " + shipped(level, owner));
            arriveAll(level, owner);

            // El mínimo del origen: quedan 70, mínimo 65 → solo 5
            Market.setRule(level, v, "minecraft:bread", 65, -1, -1);
            Schedules.setLine(level, v, s.id, "minecraft:bread", 16, false);
            Schedules.sendNow(level, v, s.id);
            check(shipped(level, owner) == 5 && origin.count(Items.BREAD) == 65, "no respetó el mínimo: " + origin.count(Items.BREAD));
            arriveAll(level, owner);

            // El día que toca sale solo; en pausa no
            Market.setRule(level, v, "minecraft:bread", 0, -1, -1);
            long today = Barracks.today(level);
            s.nextDay = today + 1;
            Schedules.togglePause(level, v, s.id);
            Schedules.onNewDay(level, today + 1);
            check(shipped(level, owner) == 0, "salió en pausa");
            Schedules.togglePause(level, v, s.id);
            s.nextDay = today + 1;
            Schedules.onNewDay(level, today + 1);
            check(shipped(level, owner) == 16, "no salió el día que tocaba");
            check(s.nextDay == today + 1 + s.every, "no quedó programado el próximo");
        } finally {
            RouteData.get(level).remove(route, owner);
            for (RouteData.Shipment x : RouteData.get(level).shipmentsOf(owner)) RouteData.get(level).removeShipment(x.id());
            cleanup(level, v);
        }
        h.succeed();
    }

    /** Lonja: ve el otro almacén y pide de él (sale un envío hacia la aldea, más rápido); sin Lonja no se puede. */
    @GameTest(template = TEMPLATE)
    public static void exchangeOrders(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        CampBlockEntity store = placeCamp(h);
        VillageData.Village v = village(h, Upgrade.MARKET, Upgrade.MARKET_2);
        UUID route = UUID.randomUUID();
        try {
            check(VillageStorehouse.ensure(level, v) != null, "no se puso el Campamento de la aldea");
            Component error = Routes.register(level, owner, route, "r", List.of(store.getBlockPos(), v.center()));
            check(error == null, "no se registró la ruta: " + (error == null ? "" : error.getString()));
            store.insert(new ItemStack(Items.IRON_INGOT, 50));

            check(Market.view(level, v).stores().isEmpty(), "la Plaza (nivel 2) ve los otros almacenes");
            Exchange.order(level, v, store.getBlockPos(), "minecraft:iron_ingot=20");
            check(store.count(Items.IRON_INGOT) == 50, "pidió sin Lonja");

            TownData.get(level).getOrCreate(v.center()).built.add(Upgrade.MARKET_3);
            MarketView view = Market.view(level, v);
            check(view.stores().size() == 1 && view.stores().get(0).items().stream()
                    .anyMatch(i -> i.item().equals("minecraft:iron_ingot") && i.available() == 50), "la Lonja no ve el hierro del otro almacén");
            Exchange.order(level, v, store.getBlockPos(), "minecraft:iron_ingot=20");
            check(store.count(Items.IRON_INGOT) == 30, "no salió el pedido: quedan " + store.count(Items.IRON_INGOT));
            List<RouteData.Shipment> list = RouteData.get(level).shipmentsOf(owner);
            check(list.size() == 1 && list.get(0).to().equals(v.center()), "el envío no va a la aldea");
            Routes.Destination d = Routes.destination(level, owner, v.center(), store.getBlockPos());
            long took = list.get(0).arriveTick() - list.get(0).departTick();
            check(d != null && took == Math.max(Routes.MIN_LEG_TICKS, Math.round(d.ticks() * Market.FAST_FACTOR)),
                    "no es más rápido: " + took + " de " + (d == null ? -1 : d.ticks()));
            arriveAll(level, owner);
            CampBlockEntity mine = VillageStorehouse.camp(level, v);
            check(mine != null && mine.count(Items.IRON_INGOT) == 20, "no llegó el hierro a la aldea");
        } finally {
            RouteData.get(level).remove(route, owner);
            for (RouteData.Shipment x : RouteData.get(level).shipmentsOf(owner)) RouteData.get(level).removeShipment(x.id());
            cleanup(level, v);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static MerchantOffer find(MerchantOffers offers, net.minecraft.world.item.Item item) {
        for (MerchantOffer o : offers) if (o.getResult().is(item)) return o;
        return null;
    }

    private static int shipped(ServerLevel level, UUID owner) {
        int n = 0;
        for (RouteData.Shipment s : RouteData.get(level).shipmentsOf(owner)) for (ItemStack st : s.items()) n += st.getCount();
        return n;
    }

    /** Todos los envíos en camino llegan ya. */
    private static void arriveAll(ServerLevel level, UUID owner) {
        for (RouteData.Shipment s : RouteData.get(level).shipmentsOf(owner)) {
            RouteData.get(level).replace(new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(),
                    level.getGameTime(), s.soldiers(), s.items()));
        }
        Routes.tickShipments(level);
    }

    private static VillageData.Village village(GameTestHelper h, Upgrade... built) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "prueba_h24"), 0)
                .withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        TownData.Town t = TownData.get(h.getLevel()).getOrCreate(v.center());
        t.built.addAll(List.of(built));
        return v;
    }

    private static void cleanup(ServerLevel level, VillageData.Village v) {
        TownData.get(level).remove(v.center());
        MarketData.get(level).remove(v.center());
        VillageStorehouse.forget(level, v);
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    private static CampBlockEntity placeCamp(GameTestHelper h) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) {
            throw new GameTestAssertException("el bloque Campamento no creó su cofre");
        }
        camp.setOwner(owner(h));
        return camp;
    }

    private static ServerPlayer player(ServerLevel level, GameTestHelper h, String name) {
        UUID id = UUID.nameUUIDFromBytes((name + "@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        return net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(id, name));
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h24@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
