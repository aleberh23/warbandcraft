package com.ale.warbandcraft.market;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.network.OpenMarketMessage;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.Territory;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.worker.MerchantEntity;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * H24: el Mercado de una aldea/castillo tuyo (3 niveles). El Mercader le vende a jugadores de otras facciones lo que sobra
 * en el almacén (por encima del mínimo de cada ítem) por esmeraldas, que entran al almacén. El dueño, hablándole (o desde
 * Gestión), elige mínimo, precio y qué se vende ({@link MarketView}); los envíos programados están en {@link Schedules} y
 * la Lonja en {@link Exchange}. Solo se toman ítems sin datos (nada encantado ni gastado).
 */
public final class Market {
    /** Hasta dónde de su puesto atiende el mercader. */
    public static double ATTEND_RANGE = 6;
    /** Mínimo de partida de cada ítem: no se vende ni se manda por debajo de esto (el dueño lo cambia). */
    public static int DEFAULT_MIN = 32;
    /** Lote y precio (esmeraldas por lote) de lo que no está en la tabla de precios; precio máximo por lote. */
    public static int DEFAULT_LOT = 16, DEFAULT_PRICE = 2, MAX_PRICE = 64;
    /** Ofertas a la vez según el nivel del mercado. */
    public static int OFFERS_1 = 8, OFFERS_2 = 16, OFFERS_3 = 24;
    /** Envíos programados según el nivel. */
    public static int SCHEDULES_1 = 2, SCHEDULES_2 = 5, SCHEDULES_3 = 10;
    /** Mercaderes según el nivel. */
    public static int MERCHANTS_1 = 1, MERCHANTS_2 = 2, MERCHANTS_3 = 3;
    /** Desde la Plaza (nivel 2) los envíos del mercado tardan esto del tiempo normal (0,75 = 25% más rápidos). */
    public static double FAST_FACTOR = 0.75;
    /** 1 = los de la facción del dueño también le compran al mercader (0 = solo otras facciones). */
    public static int OWN_FACTION_BUYS = 0;
    /** Ventas que se recuerdan por mercado. */
    public static int SALES_KEPT = 20;
    /** Hasta dónde del mercader puede alejarse el que compra (más lejos, se cierra la pantalla). */
    public static double TRADE_RANGE = 8;

    private Market() {}

    // ------------------------------------------------------------------ niveles

    /** Nivel del mercado de la aldea (0 = no tiene). */
    public static int level(ServerLevel level, VillageData.Village v) {
        TownData.Town t = TownData.get(level).at(v.center());
        return t == null ? 0 : t.marketLevel();
    }

    private static int byLevel(int lvl, int a, int b, int c) {
        return lvl >= 3 ? c : lvl == 2 ? b : lvl == 1 ? a : 0;
    }

    public static int maxOffers(int lvl) {
        return byLevel(lvl, OFFERS_1, OFFERS_2, OFFERS_3);
    }

    public static int maxSchedules(int lvl) {
        return byLevel(lvl, SCHEDULES_1, SCHEDULES_2, SCHEDULES_3);
    }

    public static int maxMerchants(int lvl) {
        return byLevel(lvl, MERCHANTS_1, MERCHANTS_2, MERCHANTS_3);
    }

    /** Lo que tarda un envío del mercado respecto del normal. */
    public static double speed(int lvl) {
        return lvl >= 2 ? FAST_FACTOR : 1;
    }

    // ------------------------------------------------------------------ el mercader

    /** Dónde atiende el Mercader: el medio del mercado (null si la aldea no tiene mercado). */
    @Nullable
    public static BlockPos workplace(ServerLevel level, VillageData.Village v) {
        TownData.Town t = TownData.get(level).at(v.center());
        if (t == null || t.marketLevel() == 0) return null;
        int lvl = t.marketLevel();
        return com.ale.warbandcraft.upgrade.Blueprints.anchor(t, Upgrade.MARKET.siteKey(),
                lvl >= 3 ? Upgrade.MARKET_3 : lvl == 2 ? Upgrade.MARKET_2 : Upgrade.MARKET);
    }

    /** Tus mercaderes de esta aldea que están cargados. */
    public static List<MerchantEntity> merchants(ServerLevel level, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null) return List.of();
        return level.getEntitiesOfClass(MerchantEntity.class, new AABB(v.center()).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS),
                m -> m.isAlive() && camp.equals(m.getCamp()) && Objects.equals(m.getOwnerUUID(), v.owner()));
    }

    /** Null si se puede sumar otro mercader; si no, el motivo. */
    @Nullable
    public static Component canHire(ServerLevel level, VillageData.Village v) {
        int lvl = level(level, v);
        if (lvl == 0) return Component.translatable("msg.warbandcraft.market.no_market").withStyle(ChatFormatting.RED);
        if (merchants(level, v).size() >= maxMerchants(lvl)) {
            return Component.translatable("msg.warbandcraft.market.merchants_full", maxMerchants(lvl)).withStyle(ChatFormatting.RED);
        }
        return null;
    }

    /** Click derecho al Mercader: al dueño, la pantalla Mercado; a otro, comprar (si puede). */
    public static void interact(ServerPlayer player, MerchantEntity merchant) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = merchant.getCamp() == null ? null : VillageCamps.villageOf(level, merchant.getCamp());
        if (v == null || !Objects.equals(v.owner(), merchant.getOwnerUUID())) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.market.closed").withStyle(ChatFormatting.RED));
            return;
        }
        if (player.getUUID().equals(v.owner())) {
            OpenMarketMessage.send(player, view(level, v), Component.empty());
            return;
        }
        Component problem = buyerProblem(level, v, player);
        if (problem == null && level(level, v) == 0) problem = Component.translatable("msg.warbandcraft.market.no_market");
        if (problem == null && !merchant.attending()) problem = Component.translatable("msg.warbandcraft.market.not_attending");
        MerchantOffers offers = problem == null ? offers(level, v) : new MerchantOffers();
        if (problem == null && offers.isEmpty()) problem = Component.translatable("msg.warbandcraft.market.nothing");
        if (problem != null) {
            player.sendSystemMessage(problem.copy().withStyle(ChatFormatting.RED));
            return;
        }
        Trader trader = new Trader(level, v.center(), merchant, offers);
        trader.setTradingPlayer(player);
        trader.openTradingScreen(player, Component.translatable("gui.warbandcraft.market.trade_title", placeName(level, v)), 0);
    }

    /** Null si {@code buyer} le puede comprar al mercado de la aldea; si no, el motivo. */
    @Nullable
    public static Component buyerProblem(ServerLevel level, VillageData.Village v, ServerPlayer buyer) {
        if (buyer.getUUID().equals(v.owner())) return Component.translatable("msg.warbandcraft.market.own_faction");
        String ownerTeam = Territory.factionOf(level, v.owner());
        String buyerTeam = buyer.getTeam() == null ? null : buyer.getTeam().getName();
        if (ownerTeam != null && ownerTeam.equals(buyerTeam) && OWN_FACTION_BUYS == 0) {
            return Component.translatable("msg.warbandcraft.market.own_faction");
        }
        if (ownerTeam != null && buyerTeam != null && !ownerTeam.equals(buyerTeam) && atWar(ownerTeam, buyerTeam)) {
            return Component.translatable("msg.warbandcraft.market.enemy");
        }
        return null;
    }

    /** Guerra entre dos facciones de Recruits (su diplomacia: ENEMY en cualquiera de los dos sentidos). */
    private static boolean atWar(String a, String b) {
        RecruitsDiplomacyManager d = FactionEvents.recruitsDiplomacyManager;
        if (d == null) return false;
        try {
            return d.getRelation(a, b) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY
                    || d.getRelation(b, a) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ofertas y ventas

    /** Lo que se le puede vender a otro jugador: por encima del mínimo, con la venta prendida, de a lotes; lo que más hay primero. */
    public static MerchantOffers offers(ServerLevel level, VillageData.Village v) {
        MerchantOffers out = new MerchantOffers();
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp == null) return out;
        MarketData.Market m = market(level, v);
        List<Map.Entry<Item, Integer>> items = new ArrayList<>(counts(camp).entrySet());
        items.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        int max = maxOffers(level(level, v));
        for (Map.Entry<Item, Integer> e : items) {
            if (out.size() >= max) break;
            Item item = e.getKey();
            if (!sellable(item)) continue;
            MarketData.Rule r = rule(m, item);
            if (!r.sell) continue;
            int lot = Prices.INSTANCE.lot(item);
            int lots = (e.getValue() - r.min) / lot;
            if (lots <= 0) continue;
            int price = Math.max(1, Math.min(Math.min(MAX_PRICE, 64), r.price));
            out.add(new MerchantOffer(new ItemStack(Items.EMERALD, price), ItemStack.EMPTY, new ItemStack(item, lot), 0, lots, 0, 0F));
        }
        return out;
    }

    /** Las esmeraldas no se venden (son la moneda). */
    public static boolean sellable(Item item) {
        return item != Items.AIR && item != Items.EMERALD && item != Items.EMERALD_BLOCK;
    }

    /** Se cerró una venta: sale del almacén, entran las esmeraldas, se anota y se le avisa al dueño. */
    public static void sold(ServerLevel level, BlockPos center, @Nullable Player buyer, ItemStack result, int emeralds) {
        VillageData.Village v = VillageData.get(level).at(center);
        if (v == null || v.owner() == null) return;
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp != null) {
            take(camp, result.getItem(), result.getCount());
            ItemStack left = camp.insert(new ItemStack(Items.EMERALD, emeralds));
            if (!left.isEmpty()) CampRegistry.get(level).deliver(level, v.owner(), List.of(left), center);
        } else {
            CampRegistry.get(level).deliver(level, v.owner(), List.of(new ItemStack(Items.EMERALD, emeralds)), center);
        }
        String who = buyer == null ? "?" : buyer.getGameProfile().getName();
        MarketData data = MarketData.get(level);
        MarketData.Market m = data.getOrCreate(center, v.owner());
        m.sales.add(0, new MarketData.Sale(who, id(result.getItem()), result.getCount(), emeralds, Barracks.today(level)));
        while (m.sales.size() > SALES_KEPT) m.sales.remove(m.sales.size() - 1);
        data.changed();
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(v.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("msg.warbandcraft.market.sold", who, result.getCount(), result.getHoverName(),
                    emeralds, placeName(level, v)).withStyle(ChatFormatting.GOLD));
        }
    }

    /** La tienda de vanilla para un comprador: las ofertas armadas al abrir; cada venta pasa por {@link #sold}. */
    static final class Trader implements Merchant {
        private final ServerLevel level;
        private final BlockPos center;
        private final MerchantEntity merchant;
        private final MerchantOffers offers;
        @Nullable
        private Player buyer;

        Trader(ServerLevel level, BlockPos center, MerchantEntity merchant, MerchantOffers offers) {
            this.level = level;
            this.center = center;
            this.merchant = merchant;
            this.offers = offers;
        }

        @Override
        public void setTradingPlayer(@Nullable Player player) {
            this.buyer = player;
        }

        @Nullable
        @Override
        public Player getTradingPlayer() {
            // Si el mercader se murió o el comprador se fue lejos, la pantalla se cierra sola.
            if (buyer != null && (!merchant.isAlive() || buyer.distanceToSqr(merchant) > TRADE_RANGE * TRADE_RANGE)) return null;
            return buyer;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
            offer.increaseUses();
            sold(level, center, buyer, offer.getResult(), offer.getCostA().getCount() + offer.getCostB().getCount());
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
        }

        @Override
        public int getVillagerXp() {
            return 0;
        }

        @Override
        public void overrideXp(int xp) {
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }

    // ------------------------------------------------------------------ reglas

    /** El mercado guardado de la aldea (null si no hay o era de otro dueño). */
    @Nullable
    public static MarketData.Market market(ServerLevel level, VillageData.Village v) {
        MarketData.Market m = MarketData.get(level).at(v.center());
        return m != null && v.owner() != null && v.owner().equals(m.owner) ? m : null;
    }

    /** La regla del ítem (la de partida si el dueño no la tocó). */
    public static MarketData.Rule rule(@Nullable MarketData.Market m, Item item) {
        MarketData.Rule r = m == null ? null : m.rules.get(id(item));
        return r != null ? r : new MarketData.Rule(DEFAULT_MIN, Prices.INSTANCE.price(item), true);
    }

    /** Hasta dónde se puede sacar de un almacén: el mínimo de su mercado si es de una aldea/castillo; un Campamento común, 0. */
    public static int minimum(ServerLevel level, BlockPos node, Item item) {
        VillageData.Village v = VillageCamps.villageOf(level, VillageCamps.campFor(level, node));
        if (v == null) return 0;
        return rule(market(level, v), item).min;
    }

    /** Cambia mínimo / precio / vender de un ítem ({@code min} o {@code price} &lt; 0 = no se toca; {@code sell}: -1 no se toca). */
    public static Component setRule(ServerLevel level, VillageData.Village v, String itemId, int min, int price, int sell) {
        Item item = item(itemId);
        if (item == null || !sellable(item)) return Component.empty();
        MarketData data = MarketData.get(level);
        MarketData.Market m = data.getOrCreate(v.center(), v.owner());
        MarketData.Rule r = m.rules.computeIfAbsent(itemId, k -> rule(null, item));
        if (min >= 0) r.min = Math.min(min, 100_000);
        if (price >= 0) r.price = Math.max(1, Math.min(Math.min(MAX_PRICE, 64), price));
        if (sell >= 0) r.sell = sell == 1;
        data.changed();
        return Component.empty();
    }

    // ------------------------------------------------------------------ almacenes

    /** El Campamento-almacén de un punto tuyo (aldea → el de la aldea; Campamento → ese). Con {@code load} carga su chunk. */
    @Nullable
    public static CampBlockEntity store(ServerLevel level, BlockPos node, boolean load) {
        BlockPos pos = VillageCamps.campFor(level, node);
        if (load) level.getChunk(pos);
        else if (!level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof CampBlockEntity c ? c : null;
    }

    /** Lo que hay en el almacén por ítem (solo stacks sin datos). */
    public static Map<Item, Integer> counts(CampBlockEntity camp) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        camp.forEachStack(s -> {
            if (!s.hasTag()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        });
        return out;
    }

    public static int count(CampBlockEntity camp, Item item) {
        return counts(camp).getOrDefault(item, 0);
    }

    /** Saca hasta {@code amount} de {@code item} (sin datos). Devuelve cuántos sacó. */
    public static int take(CampBlockEntity camp, Item item, int amount) {
        int remaining = amount;
        for (Container c : camp.storages()) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = c.getItem(i);
                if (stack.is(item) && !stack.hasTag()) {
                    int n = Math.min(remaining, stack.getCount());
                    stack.shrink(n);
                    remaining -= n;
                    if (stack.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                    changed = true;
                }
            }
            if (changed) c.setChanged();
            if (remaining == 0) break;
        }
        return amount - remaining;
    }

    /** Lo que salió en un envío: si había ruta y los ítems. */
    public record Sent(boolean route, int count, List<ItemStack> items) {}

    /**
     * Saca del almacén de {@code from} (sin bajar de su mínimo) lo pedido y lo manda por tus rutas a {@code to} como un
     * envío de H13, tardando {@code speed} del tiempo normal. Carga los chunks de los almacenes si hace falta.
     */
    public static Sent ship(ServerLevel level, UUID owner, BlockPos from, BlockPos to, Map<Item, Integer> want, double speed) {
        Routes.Destination dest = Routes.destination(level, owner, from, to);
        if (dest == null) return new Sent(false, 0, List.of());
        CampBlockEntity store = store(level, from, true);
        if (store == null) return new Sent(true, 0, List.of());
        Map<Item, Integer> have = counts(store);
        List<ItemStack> cargo = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item item = e.getKey();
            int n = Math.min(e.getValue(), have.getOrDefault(item, 0) - minimum(level, from, item));
            if (n <= 0) continue;
            n = take(store, item, n);
            total += n;
            int max = new ItemStack(item).getMaxStackSize();
            while (n > 0) {
                int k = Math.min(n, max);
                cargo.add(new ItemStack(item, k));
                n -= k;
            }
        }
        snapshot(level, VillageCamps.campFor(level, from), store);
        if (cargo.isEmpty()) return new Sent(true, 0, List.of());
        boolean toCamp = dest.node().kind() == Routes.Kind.CAMP || !VillageCamps.campFor(level, to).equals(to);
        long now = level.getGameTime();
        long ticks = Math.max(Routes.MIN_LEG_TICKS, Math.round(dest.ticks() * speed));
        RouteData.get(level).add(new RouteData.Shipment(UUID.randomUUID(), owner, from, to, toCamp, now, now + ticks, List.of(), cargo));
        return new Sent(true, total, cargo);
    }

    /** ¿Se le pueden mandar ítems a este destino? (un Campamento, o una aldea/castillo con Campamento). */
    public static boolean takesItems(ServerLevel level, Routes.Destination d) {
        return d.node().kind() == Routes.Kind.CAMP || !VillageCamps.campFor(level, d.node().pos()).equals(d.node().pos());
    }

    /** Anota la cuenta de un almacén cargado (la Lonja la muestra cuando está descargado). */
    static void snapshot(ServerLevel level, BlockPos campPos, CampBlockEntity camp) {
        Map<String, Integer> m = new LinkedHashMap<>();
        counts(camp).forEach((item, n) -> m.put(id(item), n));
        MarketData.get(level).snapshots.put(campPos.asLong(), m);
    }

    // ------------------------------------------------------------------ pantalla

    /** Lo que ve el dueño en la pantalla Mercado. */
    public static MarketView view(ServerLevel level, VillageData.Village v) {
        UUID owner = v.owner();
        int lvl = level(level, v);
        long today = Barracks.today(level);
        MarketData.Market m = market(level, v);
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        Map<Item, Integer> counts = camp == null ? new LinkedHashMap<>() : counts(camp);
        if (camp != null) snapshot(level, camp.getBlockPos(), camp);
        if (m != null) {
            for (String k : m.rules.keySet()) {
                Item item = item(k);
                if (item != null) counts.putIfAbsent(item, 0);
            }
        }
        List<MarketView.SaleRow> rows = new ArrayList<>();
        counts.entrySet().stream()
                .filter(e -> sellable(e.getKey()))
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed().thenComparing(e -> id(e.getKey())))
                .forEach(e -> {
                    MarketData.Rule r = rule(m, e.getKey());
                    rows.add(new MarketView.SaleRow(id(e.getKey()), e.getValue(), r.min, Prices.INSTANCE.lot(e.getKey()), r.price, r.sell));
                });

        List<Component> sales = new ArrayList<>();
        if (m != null) {
            for (MarketData.Sale s : m.sales) {
                Item item = item(s.item());
                sales.add(Component.translatable("gui.warbandcraft.market.sale", s.buyer(), s.count(),
                        item == null ? Component.literal(s.item()) : new ItemStack(item).getHoverName(), s.emeralds(), today - s.day()));
            }
        }

        List<Routes.Destination> reach = Routes.reachable(level, owner, v.center());
        List<MarketView.Dest> dests = new ArrayList<>();
        for (Routes.Destination d : reach) {
            if (takesItems(level, d)) dests.add(new MarketView.Dest(d.node().pos(), d.node().name(), (int) Math.round(d.ticks() * speed(lvl))));
        }

        List<MarketView.ScheduleRow> schedules = new ArrayList<>();
        if (m != null) {
            for (MarketData.Schedule s : m.schedules) {
                Component to = Component.literal("—");
                if (s.to != null) {
                    Routes.Node n = Routes.node(level, owner, s.to);
                    to = n != null ? n.name() : Component.translatable("gui.warbandcraft.market.lost_dest");
                }
                List<MarketView.LineRow> lines = new ArrayList<>();
                for (MarketData.Line l : s.lines) lines.add(new MarketView.LineRow(l.item, l.amount, l.refill));
                schedules.add(new MarketView.ScheduleRow(s.id, s.to, to, s.every, (int) Math.max(0, s.nextDay - today), s.paused, lines));
            }
        }

        List<MarketView.StoreRow> stores = new ArrayList<>();
        if (lvl >= 3) {
            for (Routes.Destination d : reach) {
                if (!takesItems(level, d)) continue;
                BlockPos campPos = VillageCamps.campFor(level, d.node().pos());
                CampBlockEntity other = store(level, d.node().pos(), false);
                if (other != null) snapshot(level, campPos, other);
                Map<String, Integer> snap = MarketData.get(level).snapshots.get(campPos.asLong());
                List<MarketView.ItemCount> items = new ArrayList<>();
                if (snap != null) {
                    snap.forEach((k, n) -> {
                        Item item = item(k);
                        if (item == null) return;
                        int avail = n - minimum(level, d.node().pos(), item);
                        if (avail > 0) items.add(new MarketView.ItemCount(k, avail));
                    });
                }
                items.sort(Comparator.comparingInt(MarketView.ItemCount::available).reversed());
                stores.add(new MarketView.StoreRow(d.node().pos(), d.node().name(), other != null, items));
            }
        }
        return new MarketView(v.center(), placeName(level, v), lvl, merchants(level, v).size(), maxMerchants(lvl), maxOffers(lvl),
                maxSchedules(lvl), rows, sales, schedules, dests, stores);
    }

    // ------------------------------------------------------------------ utilidades

    public static String id(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }

    @Nullable
    public static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? null : item;
    }

    public static Component placeName(ServerLevel level, VillageData.Village v) {
        return VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
    }
}
