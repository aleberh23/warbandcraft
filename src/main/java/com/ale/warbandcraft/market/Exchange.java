package com.ale.warbandcraft.market;

import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H24: la Lonja (mercado nivel 3). Muestra lo que hay en cada uno de tus almacenes conectados por ruta
 * ({@link MarketView#stores()}) y desde ahí se pide: sale un envío normal de H13 desde ese almacén hacia esta aldea
 * (con la rapidez del mercado). Nada es instantáneo y el origen no baja de sus mínimos.
 */
public final class Exchange {
    private Exchange() {}

    /** Pedido a {@code from}: {@code spec} = "minecraft:bread=32,minecraft:iron_ingot=8". */
    public static Component order(ServerLevel level, VillageData.Village v, BlockPos from, String spec) {
        int lvl = Market.level(level, v);
        if (lvl < 3) return Component.translatable("msg.warbandcraft.market.needs_exchange").withStyle(ChatFormatting.RED);
        Routes.Destination d = Routes.destination(level, v.owner(), v.center(), from);
        if (d == null || !Market.takesItems(level, d)) {
            return Component.translatable("msg.warbandcraft.shipments.no_route").withStyle(ChatFormatting.RED);
        }
        Map<Item, Integer> want = parse(spec);
        if (want.isEmpty()) return Component.translatable("msg.warbandcraft.market.order_empty").withStyle(ChatFormatting.RED);
        Market.Sent sent = Market.ship(level, v.owner(), from, v.center(), want, Market.speed(lvl));
        if (!sent.route()) return Component.translatable("msg.warbandcraft.shipments.no_route").withStyle(ChatFormatting.RED);
        if (sent.count() == 0) return Component.translatable("msg.warbandcraft.market.schedule_empty").withStyle(ChatFormatting.RED);
        int ticks = (int) Math.round(d.ticks() * Market.speed(lvl));
        return Component.translatable("msg.warbandcraft.market.ordered", sent.count(), d.node().name(), Math.max(1, ticks / 20))
                .withStyle(ChatFormatting.GREEN);
    }

    static Map<Item, Integer> parse(String spec) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            Item item = Market.item(part.substring(0, eq).trim());
            int n;
            try {
                n = Integer.parseInt(part.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (item != null && n > 0) out.merge(item, Math.min(n, 100_000), Integer::sum);
        }
        return out;
    }
}
