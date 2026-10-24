package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Almacén de las aldeas y castillos tuyos (H10): esmeraldas, comida (pan) y armamento que se juntan una vez por
 * día. Viven en un barril de verdad en la casa más grande ({@link VillageStorehouse}); con la aldea descargada
 * esperan como números ({@link Stock}) y entran al barril al cargarse. No llegan solos al Campamento: se abre el
 * barril, se retira con {@link #withdraw} o se mandan por una ruta ({@link #send}).
 */
public final class VillageStock {
    /** Comida de una aldea por día: base + 1 pan por cada 2 aldeanos (hasta +6). */
    public static int VILLAGE_FOOD = 4;
    public static int MAX_FOOD_BONUS = 6;
    /** Un castillo: comida fija y armamento (tiene su herrería). */
    public static int CASTLE_FOOD = 4;
    public static int CASTLE_ARMAMENT = 4;
    /** Cada recurso guarda hasta tantos días de producción (lo que sobra se pierde; H12: granero). */
    public static int CAP_DAYS = 7;
    /** Hasta dónde del centro se puede retirar en mano. */
    public static int WITHDRAW_RADIUS = 48;

    /** Lo que tiene guardado una aldea. */
    public record Stock(int emeralds, int food, int armament) {
        public static final Stock EMPTY = new Stock(0, 0, 0);

        public boolean isEmpty() {
            return emeralds <= 0 && food <= 0 && armament <= 0;
        }

        public Stock plus(Stock o) {
            return new Stock(emeralds + o.emeralds, food + o.food, armament + o.armament);
        }

        public Stock min(Stock cap) {
            return new Stock(Math.min(emeralds, cap.emeralds), Math.min(food, cap.food), Math.min(armament, cap.armament));
        }

        /** Los ítems que salen del almacén: esmeraldas, pan y armamento, en pilas. */
        public List<ItemStack> items() {
            List<ItemStack> out = new ArrayList<>();
            addStacks(out, Items.EMERALD, emeralds);
            addStacks(out, Items.BREAD, food);
            addStacks(out, Armament.item(), armament);
            return out;
        }

        /** "12 esmeraldas, 20 panes y 4 de armamento" (corto, para el chat). */
        public Component describe() {
            return Component.translatable("msg.warbandcraft.stock.describe", emeralds, food, armament);
        }
    }

    private VillageStock() {}

    private static void addStacks(List<ItemStack> out, Item item, int count) {
        while (count > 0) {
            int n = Math.min(count, item.getMaxStackSize());
            out.add(new ItemStack(item, n));
            count -= n;
        }
    }

    // ------------------------------------------------------------------ producción

    /** Aldeanos alrededor del centro (solo si está cargada; si no, 0). */
    public static int villagers(ServerLevel level, VillageData.Village v) {
        if (!level.isPositionEntityTicking(v.center())) return 0;
        return level.getEntitiesOfClass(Villager.class, new AABB(v.center()).inflate(48, 24, 48)).size();
    }

    /** Lo que produce en un día (H12: con las mejoras de la aldea). */
    public static Stock daily(ServerLevel level, VillageData.Village v) {
        int emeralds = VillageEconomy.taxOf(level, v);
        int food = v.castle() ? CASTLE_FOOD : VILLAGE_FOOD + Math.min(MAX_FOOD_BONUS, villagers(level, v) / 2);
        return com.ale.warbandcraft.upgrade.Upgrades.apply(level, v, new Stock(emeralds, food, v.castle() ? CASTLE_ARMAMENT : 0));
    }

    /** Lo máximo que guarda: {@link #CAP_DAYS} días de su mejor producción (H12: el granero guarda el doble de comida). */
    public static Stock cap(ServerLevel level, VillageData.Village v) {
        int emeralds = VillageEconomy.BASE_TAX + VillageEconomy.MAX_VILLAGER_BONUS;
        int food = v.castle() ? CASTLE_FOOD : VILLAGE_FOOD + MAX_FOOD_BONUS;
        Stock best = com.ale.warbandcraft.upgrade.Upgrades.apply(level, v, new Stock(emeralds, food, v.castle() ? CASTLE_ARMAMENT : 0));
        int foodDays = CAP_DAYS * com.ale.warbandcraft.upgrade.Upgrades.foodCapFactor(level, v);
        return new Stock(best.emeralds() * CAP_DAYS, best.food() * foodDays, best.armament() * CAP_DAYS);
    }

    /**
     * Un día de una aldea: lo suma a lo que espera (hasta el tope) y, si la aldea está cargada, lo pasa al barril.
     * Devuelve lo producido.
     */
    public static Stock produce(ServerLevel level, VillageData.Village v) {
        VillageData data = VillageData.get(level);
        Stock day = daily(level, v);
        data.setStock(v.center(), data.stock(v.center()).plus(day).min(cap(level, v)));
        VillageStorehouse.flush(level, v);
        return day;
    }

    /** Todo el almacén: lo del barril (si está cargado) + lo que espera entrar. */
    public static Stock total(ServerLevel level, VillageData.Village v) {
        return VillageData.get(level).stock(v.center()).plus(VillageStorehouse.inBarrel(level, v));
    }

    /** Vacía el almacén (barril + lo que espera) y devuelve los ítems. */
    private static List<ItemStack> takeAll(ServerLevel level, VillageData.Village v) {
        VillageData data = VillageData.get(level);
        List<ItemStack> items = new ArrayList<>(data.stock(v.center()).items());
        data.setStock(v.center(), Stock.EMPTY);
        items.addAll(VillageStorehouse.takeAll(level, v));
        return items;
    }

    /** H12: saca hasta {@code n} esmeraldas del almacén (primero lo que espera, después el barril). Devuelve cuántas. */
    public static int takeEmeralds(ServerLevel level, VillageData.Village v, int n) {
        VillageData data = VillageData.get(level);
        Stock s = data.stock(v.center());
        int got = Math.min(n, s.emeralds());
        if (got > 0) data.setStock(v.center(), new Stock(s.emeralds() - got, s.food(), s.armament()));
        if (got < n) got += VillageStorehouse.takeEmeralds(level, v, n - got);   // H16: del Campamento de la aldea
        return got;
    }

    /** Solo para las pruebas: vacía el almacén. */
    public static void withdrawAllForTest(ServerLevel level, VillageData.Village v) {
        takeAll(level, v);
    }

    /** "en la casa grande (x, y, z)" o vacío si el barril todavía no se puso. */
    public static Component where(ServerLevel level, VillageData.Village v) {
        BlockPos pos = VillageData.get(level).storehouse(v.center());
        return pos == null ? Component.empty()
                : Component.translatable(v.castle() ? "msg.warbandcraft.stock.where_castle" : "msg.warbandcraft.stock.where",
                pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Un día nuevo: cada aldea/castillo tuyo junta lo suyo en su almacén. Avisa a cada dueño cuánto juntaron sus
     * aldeas. Devuelve lo juntado por dueño.
     */
    public static Map<UUID, Stock> produceAll(ServerLevel level) {
        VillageData data = VillageData.get(level);
        Map<UUID, Stock> made = new HashMap<>();
        for (VillageData.Village v : data.all()) {
            if (!v.isConquered()) continue;
            made.merge(v.owner(), produce(level, v), Stock::plus);
        }
        made.forEach((owner, s) -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) {
                p.sendSystemMessage(Component.translatable("msg.warbandcraft.stock.produced", s.describe()).withStyle(ChatFormatting.GREEN));
            }
        });
        return made;
    }

    // ------------------------------------------------------------------ retirar

    /** Tu aldea/castillo más cercano a ≤{@link #WITHDRAW_RADIUS} bloques, o null. */
    @Nullable
    public static VillageData.Village ownedNear(ServerLevel level, UUID owner, BlockPos pos) {
        for (VillageData.Village v : VillageData.get(level).byDistance(pos)) {
            if (VillageData.horizontalDistSq(v.center(), pos) > (double) WITHDRAW_RADIUS * WITHDRAW_RADIUS) return null;
            if (com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) return v;
        }
        return null;
    }

    /** Pasar a buscarlo: todo el almacén de la aldea al inventario del jugador (lo que no entra, al piso). */
    public static Component withdraw(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = ownedNear(level, player.getUUID(), player.blockPosition());
        if (v == null) return Component.translatable("msg.warbandcraft.stock.not_here", WITHDRAW_RADIUS).withStyle(ChatFormatting.RED);
        Stock s = total(level, v);
        List<ItemStack> items = takeAll(level, v);
        if (items.isEmpty()) return Component.translatable("msg.warbandcraft.stock.empty").withStyle(ChatFormatting.YELLOW);
        for (ItemStack stack : items) {
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
        return Component.translatable("msg.warbandcraft.stock.withdrawn", s.describe()).withStyle(ChatFormatting.GREEN);
    }

    /** El botón [Retirar] para el chat. */
    public static MutableComponent withdrawButton() {
        return Component.translatable("msg.warbandcraft.stock.withdraw_button").withStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/warband retirar"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("msg.warbandcraft.stock.withdraw_hover"))));
    }

    // ------------------------------------------------------------------ mandar por ruta

    /**
     * Manda todo el almacén de la aldea/castillo {@code from} al Campamento {@code to} como un envío de H13 (llega
     * pasado el tiempo del viaje). Devuelve null si salió, o el motivo.
     */
    @Nullable
    public static Component send(ServerLevel level, UUID owner, BlockPos from, BlockPos to) {
        VillageData data = VillageData.get(level);
        VillageData.Village v = data.at(from);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) return Component.translatable("msg.warbandcraft.stock.not_yours");
        if (!CampRegistry.get(level).isCampOf(owner, to)) return Component.translatable("msg.warbandcraft.stock.only_camps");
        Routes.Destination dest = Routes.destination(level, owner, from, to);
        if (dest == null) return Component.translatable("msg.warbandcraft.shipments.no_route");
        List<ItemStack> items = takeAll(level, v);
        if (items.isEmpty()) return Component.translatable("msg.warbandcraft.stock.empty");
        long now = level.getGameTime();
        RouteData.get(level).add(new RouteData.Shipment(UUID.randomUUID(), owner, from, to, true, now, now + dest.ticks(),
                List.of(), items));
        return null;
    }
}
