package com.ale.warbandcraft.market;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * H24: envíos programados del mercado (estilo Age of Empires). Cada uno sale de la aldea del mercado al amanecer del día
 * que toca, hacia otra aldea/castillo tuyo o un Campamento conectado por tus rutas, como un envío de H13. Cada línea es
 * "N de algo" (fijo) o "que el destino tenga al menos N" (reponer). El origen nunca baja de sus mínimos.
 */
public final class Schedules {
    /** Ítems por envío programado y cada cuántos días como mucho. */
    public static int MAX_LINES = 6, MAX_EVERY = 7;

    private Schedules() {}

    /** Día nuevo: salen los que tocan (con la aldea del mercado todavía tuya y con mercado). */
    public static void onNewDay(ServerLevel level, long day) {
        MarketData data = MarketData.get(level);
        for (MarketData.Market m : data.all()) {
            VillageData.Village v = VillageData.get(level).at(m.center);
            if (v == null || v.owner() == null || !v.owner().equals(m.owner)) continue;
            int lvl = Market.level(level, v);
            if (lvl == 0) continue;
            int i = 0;
            for (MarketData.Schedule s : m.schedules) {
                if (i++ >= Market.maxSchedules(lvl)) break;   // si bajó de nivel, los de más quedan quietos
                if (s.paused || s.to == null || day < s.nextDay) continue;
                Component msg = run(level, v, s);
                s.nextDay = day + s.every;
                data.changed();
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(v.owner());
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("msg.warbandcraft.market.schedule_day", Market.placeName(level, v))
                            .withStyle(ChatFormatting.GOLD).append(" ").append(msg));
                }
            }
        }
    }

    /** Manda el envío programado ahora. Devuelve el mensaje (verde si salió). */
    public static Component run(ServerLevel level, VillageData.Village v, MarketData.Schedule s) {
        if (s.to == null) return Component.translatable("msg.warbandcraft.market.no_dest").withStyle(ChatFormatting.RED);
        UUID owner = v.owner();
        boolean refill = s.lines.stream().anyMatch(l -> l.refill);
        CampBlockEntity dest = refill ? Market.store(level, s.to, true) : null;
        Map<Item, Integer> want = new LinkedHashMap<>();
        for (MarketData.Line l : s.lines) {
            Item item = Market.item(l.item);
            if (item == null || l.amount <= 0) continue;
            int n = l.amount;
            if (l.refill) n = Math.max(0, l.amount - (dest == null ? 0 : Market.count(dest, item)) - inTransit(level, owner, s.to, item));
            if (n > 0) want.merge(item, n, Integer::sum);
        }
        if (want.isEmpty()) return Component.translatable("msg.warbandcraft.market.schedule_full").withStyle(ChatFormatting.GRAY);
        Market.Sent sent = Market.ship(level, owner, v.center(), s.to, want, Market.speed(Market.level(level, v)));
        if (!sent.route()) return Component.translatable("msg.warbandcraft.shipments.no_route").withStyle(ChatFormatting.RED);
        if (sent.count() == 0) return Component.translatable("msg.warbandcraft.market.schedule_empty").withStyle(ChatFormatting.RED);
        Routes.Node n = Routes.node(level, owner, s.to);
        return Component.translatable("msg.warbandcraft.market.schedule_sent", sent.count(), n == null ? Component.literal("?") : n.name())
                .withStyle(ChatFormatting.GREEN);
    }

    /** Cuántos de {@code item} van en camino a {@code to}. */
    public static int inTransit(ServerLevel level, UUID owner, BlockPos to, Item item) {
        int n = 0;
        for (RouteData.Shipment s : RouteData.get(level).shipmentsOf(owner)) {
            if (!s.to().equals(to)) continue;
            for (ItemStack st : s.items()) if (st.is(item)) n += st.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ órdenes de la pantalla

    public static Component create(ServerLevel level, VillageData.Village v) {
        int lvl = Market.level(level, v);
        if (lvl == 0) return Component.translatable("msg.warbandcraft.market.no_market").withStyle(ChatFormatting.RED);
        MarketData data = MarketData.get(level);
        MarketData.Market m = data.getOrCreate(v.center(), v.owner());
        if (m.schedules.size() >= Market.maxSchedules(lvl)) {
            return Component.translatable("msg.warbandcraft.market.schedules_full", Market.maxSchedules(lvl)).withStyle(ChatFormatting.RED);
        }
        MarketData.Schedule s = new MarketData.Schedule(UUID.randomUUID());
        s.nextDay = Barracks.today(level) + 1;
        m.schedules.add(s);
        data.changed();
        return Component.translatable("msg.warbandcraft.market.schedule_created").withStyle(ChatFormatting.GREEN);
    }

    @Nullable
    private static MarketData.Schedule find(ServerLevel level, VillageData.Village v, UUID id) {
        MarketData.Market m = Market.market(level, v);
        return m == null ? null : m.schedule(id);
    }

    /** Destino elegido (tiene que ser uno de los que muestra la pantalla). */
    public static Component setDest(ServerLevel level, VillageData.Village v, UUID id, BlockPos to) {
        MarketData.Schedule s = find(level, v, id);
        if (s == null) return Component.empty();
        Routes.Destination d = Routes.destination(level, v.owner(), v.center(), to);
        if (d == null || !Market.takesItems(level, d)) {
            return Component.translatable("msg.warbandcraft.shipments.no_route").withStyle(ChatFormatting.RED);
        }
        s.to = to;
        MarketData.get(level).changed();
        return Component.empty();
    }

    public static Component setEvery(ServerLevel level, VillageData.Village v, UUID id, int every) {
        MarketData.Schedule s = find(level, v, id);
        if (s == null) return Component.empty();
        s.every = Math.max(1, Math.min(MAX_EVERY, every));
        long today = Barracks.today(level);
        s.nextDay = Math.max(today + 1, Math.min(s.nextDay, today + s.every));
        MarketData.get(level).changed();
        return Component.empty();
    }

    /** Pone una línea ({@code amount} 0 = la saca; {@code refill}: 0 fijo, 1 reponer). */
    public static Component setLine(ServerLevel level, VillageData.Village v, UUID id, String itemId, int amount, boolean refill) {
        MarketData.Schedule s = find(level, v, id);
        if (s == null || Market.item(itemId) == null) return Component.empty();
        MarketData.Line line = null;
        for (MarketData.Line l : s.lines) if (l.item.equals(itemId)) line = l;
        if (amount <= 0) {
            if (line != null) s.lines.remove(line);
        } else if (line != null) {
            line.amount = Math.min(amount, 100_000);
            line.refill = refill;
        } else {
            if (s.lines.size() >= MAX_LINES) {
                return Component.translatable("msg.warbandcraft.market.lines_full", MAX_LINES).withStyle(ChatFormatting.RED);
            }
            s.lines.add(new MarketData.Line(itemId, Math.min(amount, 100_000), refill));
        }
        MarketData.get(level).changed();
        return Component.empty();
    }

    public static Component togglePause(ServerLevel level, VillageData.Village v, UUID id) {
        MarketData.Schedule s = find(level, v, id);
        if (s == null) return Component.empty();
        s.paused = !s.paused;
        if (!s.paused) s.nextDay = Math.max(s.nextDay, Barracks.today(level) + 1);
        MarketData.get(level).changed();
        return Component.empty();
    }

    public static Component delete(ServerLevel level, VillageData.Village v, UUID id) {
        MarketData.Market m = Market.market(level, v);
        if (m != null && m.schedules.removeIf(s -> s.id.equals(id))) MarketData.get(level).changed();
        return Component.empty();
    }

    /** "Mandar ahora": sale ya y el próximo queda a {@code every} días. */
    public static Component sendNow(ServerLevel level, VillageData.Village v, UUID id) {
        MarketData.Schedule s = find(level, v, id);
        if (s == null) return Component.empty();
        Component msg = run(level, v, s);
        s.nextDay = Barracks.today(level) + s.every;
        MarketData.get(level).changed();
        return msg;
    }
}
