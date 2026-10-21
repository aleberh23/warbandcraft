package com.ale.warbandcraft.client.market;

import com.ale.warbandcraft.market.MarketView;
import com.ale.warbandcraft.network.MarketActionMessage;
import com.ale.warbandcraft.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pantalla Mercado (H24): pestañas Ventas (mínimo, precio y si se vende cada cosa del almacén, y las últimas ventas),
 * Envíos (envíos programados) y Lonja (nivel 3: lo que hay en tus otros almacenes y pedir). Se abre hablándole al Mercader,
 * desde Gestión (V) → Rutas o con /warband mercado. Al cerrarla vuelve a la pantalla de antes.
 */
@OnlyIn(Dist.CLIENT)
public class MarketScreen extends Screen {
    private static final int W = 420, H = 262, ROW = 20, SALE_ROWS = 7, LINE_ROWS = 6, STORE_ROWS = 8;
    private static final int TAB_SALES = 0, TAB_SHIP = 1, TAB_EXCHANGE = 2;
    private static final int GOLD = 0xFFFFD24A;

    /** Pestaña y elegidos (sobreviven a los refrescos y a cerrar la pantalla). */
    private static int tab = TAB_SALES;
    @Nullable
    private static UUID chosenSchedule;
    @Nullable
    private static BlockPos chosenStore;
    /** Lonja: lo que se va juntando para pedir (almacén → ítem → cantidad). */
    private static final Map<Long, Map<String, Integer>> cart = new LinkedHashMap<>();

    private MarketView view;
    private Component feedback;
    @Nullable
    private final Screen previous;
    private int left, top, scroll, pick;

    private MarketScreen(MarketView view, Component feedback, @Nullable Screen previous) {
        super(Component.translatable("gui.warbandcraft.market.title"));
        this.view = view;
        this.feedback = feedback;
        this.previous = previous;
    }

    public static void open(MarketView view, Component feedback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MarketScreen s) {
            s.view = view;
            if (!feedback.getString().isEmpty() || s.feedback.getString().isEmpty()) s.feedback = feedback;
            s.rebuildWidgets();
        } else {
            mc.setScreen(new MarketScreen(view, feedback, mc.screen));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previous);
    }

    private void send(MarketActionMessage.Action action, @Nullable UUID id, @Nullable BlockPos pos, String text, int a, int b) {
        ModNetworking.CHANNEL.sendToServer(new MarketActionMessage(action, view.center(), id, pos, text, a, b));
    }

    private static boolean shift() {
        return Screen.hasShiftDown();
    }

    @Nullable
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? null : item;
    }

    private static ItemStack stack(String id) {
        Item item = item(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String name(String id) {
        ItemStack s = stack(id);
        return s.isEmpty() ? id : s.getHoverName().getString();
    }

    // ------------------------------------------------------------------ botones

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String[] tabs = {"Ventas", "Envíos", "Lonja"};
        for (int i = 0; i < tabs.length; i++) {
            int t = i;
            Button b = Button.builder(Component.literal(tabs[i]), x -> {
                tab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(left + 6 + i * 70, top + 22, 68, 16).build();
            b.active = tab != i && (i != TAB_EXCHANGE || view.level() >= 3);
            if (i == TAB_EXCHANGE && view.level() < 3) b.setTooltip(Tooltip.create(Component.literal("Necesita la Lonja (mercado nivel 3)")));
            addRenderableWidget(b);
        }
        addRenderableWidget(Button.builder(Component.literal("Cerrar"), x -> onClose()).bounds(left + W - 56, top + 4, 50, 14).build());
        int y0 = top + 44;
        switch (tab) {
            case TAB_SALES -> initSales(y0);
            case TAB_SHIP -> initShip(y0);
            case TAB_EXCHANGE -> initExchange(y0);
            default -> {
            }
        }
    }

    private void pager(int x, int y, int total, int visible) {
        if (total <= visible) return;
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
            scroll = Math.max(0, scroll - visible);
            rebuildWidgets();
        }).bounds(x, y, 20, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
            scroll = Math.min(Math.max(0, total - visible), scroll + visible);
            rebuildWidgets();
        }).bounds(x + 22, y, 20, 14).build());
    }

    /** Ventas: por fila, mínimo ◀ ▶ (de a 16, con Shift de a 64), precio ◀ ▶ y Vender SÍ/NO. */
    private void initSales(int y0) {
        List<MarketView.SaleRow> rows = view.rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - SALE_ROWS)));
        for (int i = 0; i < SALE_ROWS && scroll + i < rows.size(); i++) {
            MarketView.SaleRow r = rows.get(scroll + i);
            int y = y0 + 14 + i * ROW;
            int x = left + 200;
            Button less = Button.builder(Component.literal("◀"), b -> send(MarketActionMessage.Action.MIN, null, null, r.item(),
                    Math.max(0, r.min() - (shift() ? 64 : 16)), 0)).bounds(x, y, 14, 14).build();
            less.active = r.min() > 0;
            less.setTooltip(Tooltip.create(Component.literal("Mínimo −16 (Shift: −64)")));
            Button more = Button.builder(Component.literal("▶"), b -> send(MarketActionMessage.Action.MIN, null, null, r.item(),
                    r.min() + (shift() ? 64 : 16), 0)).bounds(x + 52, y, 14, 14).build();
            more.setTooltip(Tooltip.create(Component.literal("Mínimo +16 (Shift: +64)")));
            addRenderableWidget(less);
            addRenderableWidget(more);
            x += 72;
            Button cheaper = Button.builder(Component.literal("◀"), b -> send(MarketActionMessage.Action.PRICE, null, null, r.item(),
                    r.price() - (shift() ? 8 : 1), 0)).bounds(x, y, 14, 14).build();
            cheaper.active = r.price() > 1;
            Button dearer = Button.builder(Component.literal("▶"), b -> send(MarketActionMessage.Action.PRICE, null, null, r.item(),
                    r.price() + (shift() ? 8 : 1), 0)).bounds(x + 62, y, 14, 14).build();
            addRenderableWidget(cheaper);
            addRenderableWidget(dearer);
            x += 80;
            Button sell = Button.builder(Component.literal(r.sell() ? "SÍ" : "NO"), b -> send(MarketActionMessage.Action.SELL, null, null,
                    r.item(), r.sell() ? 0 : 1, 0)).bounds(x, y, 34, 14).build();
            sell.setTooltip(Tooltip.create(Component.literal("¿Se le vende a otros jugadores?")));
            addRenderableWidget(sell);
        }
        pager(left + 6, y0 + 14 + SALE_ROWS * ROW, rows.size(), SALE_ROWS);
    }

    @Nullable
    private MarketView.ScheduleRow schedule() {
        for (MarketView.ScheduleRow s : view.schedules()) if (s.id().equals(chosenSchedule)) return s;
        if (!view.schedules().isEmpty()) {
            chosenSchedule = view.schedules().get(0).id();
            return view.schedules().get(0);
        }
        return null;
    }

    /** Envíos: a la izquierda la lista y "Nuevo"; a la derecha, el elegido. */
    private void initShip(int y0) {
        List<MarketView.ScheduleRow> list = view.schedules();
        for (int i = 0; i < list.size() && i < 10; i++) {
            MarketView.ScheduleRow s = list.get(i);
            boolean sel = s.id().equals(chosenSchedule);
            String label = (sel ? "▶ " : "") + (s.paused() ? "‖ " : "") + "→ " + s.toName().getString();
            Button b = Button.builder(Component.literal(font.plainSubstrByWidth(label, 120)), x -> {
                chosenSchedule = s.id();
                rebuildWidgets();
            }).bounds(left + 6, y0 + 14 + i * 18, 126, 16).build();
            addRenderableWidget(b);
        }
        Button add = Button.builder(Component.literal("+ Nuevo envío"), x -> send(MarketActionMessage.Action.NEW_SCHEDULE, null, null, "", 0, 0))
                .bounds(left + 6, top + H - 52, 126, 16).build();
        add.active = list.size() < view.maxSchedules();
        add.setTooltip(Tooltip.create(Component.literal("Hasta " + view.maxSchedules() + " con este nivel de mercado")));
        addRenderableWidget(add);

        MarketView.ScheduleRow s = schedule();
        if (s == null) return;
        int x = left + 140;
        // Destino ◀ ▶
        List<MarketView.Dest> dests = view.dests();
        int at = -1;
        for (int i = 0; i < dests.size(); i++) if (dests.get(i).pos().equals(s.to())) at = i;
        int cur = at;
        Button prev = Button.builder(Component.literal("◀"), b -> {
            int n = dests.size();
            send(MarketActionMessage.Action.DEST, s.id(), dests.get(Math.floorMod((cur < 0 ? 0 : cur) - 1, n)).pos(), "", 0, 0);
        }).bounds(x + 52, y0 + 12, 14, 14).build();
        Button next = Button.builder(Component.literal("▶"), b -> {
            int n = dests.size();
            send(MarketActionMessage.Action.DEST, s.id(), dests.get(Math.floorMod(cur + 1, n)).pos(), "", 0, 0);
        }).bounds(x + W - 140 - 20, y0 + 12, 14, 14).build();
        prev.active = next.active = !dests.isEmpty();
        addRenderableWidget(prev);
        addRenderableWidget(next);
        // Cada ◀ ▶
        Button fewer = Button.builder(Component.literal("◀"), b -> send(MarketActionMessage.Action.EVERY, s.id(), null, "", s.every() - 1, 0))
                .bounds(x + 52, y0 + 30, 14, 14).build();
        fewer.active = s.every() > 1;
        Button moreDays = Button.builder(Component.literal("▶"), b -> send(MarketActionMessage.Action.EVERY, s.id(), null, "", s.every() + 1, 0))
                .bounds(x + 110, y0 + 30, 14, 14).build();
        addRenderableWidget(fewer);
        addRenderableWidget(moreDays);
        // Líneas
        for (int i = 0; i < s.lines().size() && i < LINE_ROWS; i++) {
            MarketView.LineRow l = s.lines().get(i);
            int y = y0 + 62 + i * ROW;
            int bx = left + W - 6 - 4 * 32 - 22;
            Button minus = Button.builder(Component.literal("−16"), b -> send(MarketActionMessage.Action.LINE, s.id(), null, l.item(),
                    Math.max(shift() ? 0 : 1, l.amount() - (shift() ? 64 : 16)), l.refill() ? 1 : 0)).bounds(bx, y, 30, 14).build();
            Button plus = Button.builder(Component.literal("+16"), b -> send(MarketActionMessage.Action.LINE, s.id(), null, l.item(),
                    l.amount() + (shift() ? 64 : 16), l.refill() ? 1 : 0)).bounds(bx + 32, y, 30, 14).build();
            minus.setTooltip(Tooltip.create(Component.literal("Shift: de a 64")));
            plus.setTooltip(Tooltip.create(Component.literal("Shift: de a 64")));
            Button mode = Button.builder(Component.literal(l.refill() ? "Reponer" : "Fijo"), b -> send(MarketActionMessage.Action.LINE, s.id(),
                    null, l.item(), l.amount(), l.refill() ? 0 : 1)).bounds(bx + 64, y, 62, 14).build();
            mode.setTooltip(Tooltip.create(Component.literal(l.refill() ? "Reponer: manda lo que falta para que el destino tenga " + l.amount()
                    : "Fijo: manda " + l.amount() + " cada vez")));
            Button del = Button.builder(Component.literal("✕"), b -> send(MarketActionMessage.Action.LINE, s.id(), null, l.item(), 0, 0))
                    .bounds(bx + 128, y, 18, 14).build();
            addRenderableWidget(minus);
            addRenderableWidget(plus);
            addRenderableWidget(mode);
            addRenderableWidget(del);
        }
        // Agregar: ◀ ítem ▶ Agregar (lo que hay en el almacén)
        List<MarketView.SaleRow> items = view.rows();
        int addY = y0 + 62 + LINE_ROWS * ROW + 2;
        if (!items.isEmpty()) {
            pick = Math.floorMod(pick, items.size());
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                pick--;
                rebuildWidgets();
            }).bounds(x, addY, 14, 14).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                pick++;
                rebuildWidgets();
            }).bounds(x + 150, addY, 14, 14).build());
            String id = items.get(pick).item();
            Button addLine = Button.builder(Component.literal("Agregar"), b -> send(MarketActionMessage.Action.LINE, s.id(), null, id, 16, 0))
                    .bounds(x + 168, addY, 56, 14).build();
            addLine.active = s.lines().size() < LINE_ROWS && s.lines().stream().noneMatch(l -> l.item().equals(id));
            addRenderableWidget(addLine);
        }
        // Abajo: pausar, mandar ahora, borrar
        int by = top + H - 52;
        addRenderableWidget(Button.builder(Component.literal(s.paused() ? "Seguir" : "Pausar"), b -> send(MarketActionMessage.Action.PAUSE,
                s.id(), null, "", 0, 0)).bounds(x, by, 70, 16).build());
        Button now = Button.builder(Component.literal("Mandar ahora"), b -> send(MarketActionMessage.Action.SEND_NOW, s.id(), null, "", 0, 0))
                .bounds(x + 74, by, 90, 16).build();
        now.active = s.to() != null && !s.lines().isEmpty();
        addRenderableWidget(now);
        addRenderableWidget(Button.builder(Component.literal("Borrar"), b -> {
            send(MarketActionMessage.Action.DELETE, s.id(), null, "", 0, 0);
            chosenSchedule = null;
        }).bounds(x + 168, by, 56, 16).build());
    }

    @Nullable
    private MarketView.StoreRow store() {
        for (MarketView.StoreRow s : view.stores()) if (s.pos().equals(chosenStore)) return s;
        if (!view.stores().isEmpty()) {
            chosenStore = view.stores().get(0).pos();
            return view.stores().get(0);
        }
        return null;
    }

    /** Lonja: almacenes a la izquierda; lo que se puede pedir de cada uno a la derecha, con +16 / +64 / Todo, y Pedir. */
    private void initExchange(int y0) {
        List<MarketView.StoreRow> stores = view.stores();
        for (int i = 0; i < stores.size() && i < 10; i++) {
            MarketView.StoreRow s = stores.get(i);
            boolean sel = s.pos().equals(chosenStore);
            Button b = Button.builder(Component.literal(font.plainSubstrByWidth((sel ? "▶ " : "") + s.name().getString(), 120)), x -> {
                chosenStore = s.pos();
                scroll = 0;
                rebuildWidgets();
            }).bounds(left + 6, y0 + 14 + i * 18, 126, 16).build();
            addRenderableWidget(b);
        }
        MarketView.StoreRow s = store();
        if (s == null) return;
        Map<String, Integer> mine = cart.computeIfAbsent(s.pos().asLong(), k -> new LinkedHashMap<>());
        List<MarketView.ItemCount> items = s.items();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - STORE_ROWS)));
        for (int i = 0; i < STORE_ROWS && scroll + i < items.size(); i++) {
            MarketView.ItemCount it = items.get(scroll + i);
            int y = y0 + 14 + i * ROW;
            int bx = left + W - 6 - 3 * 34;
            int[] steps = {16, 64, Integer.MAX_VALUE};
            String[] labels = {"+16", "+64", "Todo"};
            for (int k = 0; k < 3; k++) {
                int step = steps[k];
                Button b = Button.builder(Component.literal(labels[k]), x -> {
                    int now = mine.getOrDefault(it.item(), 0);
                    mine.put(it.item(), step == Integer.MAX_VALUE ? it.available() : Math.min(it.available(), now + step));
                    rebuildWidgets();
                }).bounds(bx + k * 34, y, 32, 14).build();
                b.active = mine.getOrDefault(it.item(), 0) < it.available();
                addRenderableWidget(b);
            }
        }
        pager(left + 140, y0 + 14 + STORE_ROWS * ROW, items.size(), STORE_ROWS);
        int by = top + H - 52;
        Button order = Button.builder(Component.literal("Pedir"), b -> {
            StringBuilder spec = new StringBuilder();
            mine.forEach((k, n) -> {
                if (n > 0) spec.append(spec.length() == 0 ? "" : ",").append(k).append('=').append(n);
            });
            send(MarketActionMessage.Action.ORDER, null, s.pos(), spec.toString(), 0, 0);
            mine.clear();
        }).bounds(left + W - 6 - 130, by, 64, 16).build();
        order.active = mine.values().stream().anyMatch(n -> n > 0);
        addRenderableWidget(order);
        Button clear = Button.builder(Component.literal("Vaciar"), b -> {
            mine.clear();
            rebuildWidgets();
        }).bounds(left + W - 6 - 62, by, 62, 16).build();
        clear.active = !mine.isEmpty();
        addRenderableWidget(clear);
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        gfx.fill(left, top, left + W, top + H, 0xE0201810);
        gfx.renderOutline(left, top, W, H, 0xFF8B6B3A);
        gfx.drawString(font, view.name().getString() + " · " + levelName(view.level()), left + 6, top + 6, GOLD);
        int y0 = top + 44;
        switch (tab) {
            case TAB_SALES -> renderSales(gfx, y0);
            case TAB_SHIP -> renderShip(gfx, y0);
            case TAB_EXCHANGE -> renderExchange(gfx, y0);
            default -> {
            }
        }
        if (!feedback.getString().isEmpty()) {
            gfx.drawString(font, font.plainSubstrByWidth(feedback.getString(), W - 12), left + 6, top + H - 14,
                    feedback.getStyle().getColor() != null ? feedback.getStyle().getColor().getValue() | 0xFF000000 : 0xFFFFFFFF);
        }
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private static String levelName(int lvl) {
        return lvl >= 3 ? "Lonja" : lvl == 2 ? "Plaza de mercado" : "Mercado";
    }

    private void renderSales(GuiGraphics gfx, int y0) {
        gfx.drawString(font, "Mercaderes: " + view.merchants() + "/" + view.maxMerchants() + " · hasta " + view.maxOffers()
                + " ofertas a la vez · se vende lo que sobra arriba del mínimo", left + 6, y0, 0xFFCCCCCC);
        List<MarketView.SaleRow> rows = view.rows();
        if (rows.isEmpty()) gfx.drawString(font, "El almacén está vacío.", left + 6, y0 + 18, 0xFFAAAAAA);
        for (int i = 0; i < SALE_ROWS && scroll + i < rows.size(); i++) {
            MarketView.SaleRow r = rows.get(scroll + i);
            int y = y0 + 14 + i * ROW;
            gfx.renderItem(stack(r.item()), left + 6, y - 1);
            gfx.drawString(font, font.plainSubstrByWidth(name(r.item()), 110), left + 26, y + 3, 0xFFFFFFFF);
            gfx.drawString(font, String.valueOf(r.count()), left + 140, y + 3, r.count() > r.min() ? 0xFF8BD14A : 0xFFAAAAAA);
            gfx.drawCenteredString(font, "mín " + r.min(), left + 200 + 33, y + 3, 0xFFFFFFFF);
            gfx.drawCenteredString(font, r.price() + "◆/" + r.lot(), left + 272 + 38, y + 3, 0xFF55FF55);
        }
        int sy = y0 + 14 + SALE_ROWS * ROW + 18;
        gfx.drawString(font, "Últimas ventas:", left + 6, sy, GOLD);
        List<Component> sales = view.sales();
        if (sales.isEmpty()) gfx.drawString(font, "Todavía nadie compró nada.", left + 6, sy + 11, 0xFFAAAAAA);
        for (int i = 0; i < sales.size() && i < 2; i++) {
            gfx.drawString(font, font.plainSubstrByWidth(sales.get(i).getString(), W - 12), left + 6, sy + 11 + i * 10, 0xFFDDDDDD);
        }
    }

    private void renderShip(GuiGraphics gfx, int y0) {
        gfx.drawString(font, "Envíos programados (" + view.schedules().size() + "/" + view.maxSchedules() + ")", left + 6, y0, GOLD);
        if (view.schedules().isEmpty()) {
            gfx.drawString(font, "Ninguno todavía.", left + 6, y0 + 16, 0xFFAAAAAA);
        }
        MarketView.ScheduleRow s = schedule();
        if (s == null) return;
        int x = left + 140;
        gfx.drawString(font, "Destino:", x, y0 + 15, 0xFFCCCCCC);
        gfx.drawCenteredString(font, font.plainSubstrByWidth(s.to() == null ? "(elegí uno)" : s.toName().getString(), 190),
                (x + 68 + left + W - 22) / 2, y0 + 15, 0xFFFFFFFF);
        if (view.dests().isEmpty()) {
            gfx.drawString(font, "No hay rutas a otro almacén tuyo.", x, y0 + 46, 0xFFFF5555);
        }
        gfx.drawString(font, "Cada:", x, y0 + 33, 0xFFCCCCCC);
        gfx.drawCenteredString(font, s.every() + (s.every() == 1 ? " día" : " días"), x + 88, y0 + 33, 0xFFFFFFFF);
        String next = s.paused() ? "En pausa" : s.to() == null ? "" : s.inDays() <= 1 ? "Sale mañana al amanecer" : "Sale en " + s.inDays() + " días";
        gfx.drawString(font, next, x + 132, y0 + 33, s.paused() ? 0xFFFFAA00 : 0xFFAAAAAA);
        gfx.drawString(font, "Qué manda (nunca baja del mínimo de esta aldea):", x, y0 + 50, GOLD);
        for (int i = 0; i < s.lines().size() && i < LINE_ROWS; i++) {
            MarketView.LineRow l = s.lines().get(i);
            int y = y0 + 62 + i * ROW;
            gfx.renderItem(stack(l.item()), x, y - 1);
            String what = (l.refill() ? "hasta " : "") + l.amount() + " " + name(l.item());
            gfx.drawString(font, font.plainSubstrByWidth(what, 120), x + 20, y + 3, 0xFFFFFFFF);
        }
        if (s.lines().isEmpty()) gfx.drawString(font, "Agregá algo abajo.", x, y0 + 66, 0xFFAAAAAA);
        List<MarketView.SaleRow> items = view.rows();
        if (!items.isEmpty()) {
            int addY = y0 + 62 + LINE_ROWS * ROW + 2;
            MarketView.SaleRow r = items.get(Math.floorMod(pick, items.size()));
            gfx.renderItem(stack(r.item()), x + 18, addY - 1);
            gfx.drawString(font, font.plainSubstrByWidth(name(r.item()), 110), x + 36, addY + 3, 0xFFFFFFFF);
        }
    }

    private void renderExchange(GuiGraphics gfx, int y0) {
        gfx.drawString(font, "Tus almacenes conectados:", left + 6, y0, GOLD);
        if (view.stores().isEmpty()) gfx.drawString(font, "Ninguno (hacen falta rutas).", left + 6, y0 + 16, 0xFFAAAAAA);
        MarketView.StoreRow s = store();
        if (s == null) return;
        int x = left + 140;
        gfx.drawString(font, s.live() ? "Lo que se puede pedir:" : "Lo que se puede pedir (última cuenta):", x, y0, GOLD);
        Map<String, Integer> mine = cart.getOrDefault(s.pos().asLong(), Map.of());
        List<MarketView.ItemCount> items = s.items();
        if (items.isEmpty()) gfx.drawString(font, "Nada por encima de sus mínimos.", x, y0 + 18, 0xFFAAAAAA);
        for (int i = 0; i < STORE_ROWS && scroll + i < items.size(); i++) {
            MarketView.ItemCount it = items.get(scroll + i);
            int y = y0 + 14 + i * ROW;
            gfx.renderItem(stack(it.item()), x, y - 1);
            gfx.drawString(font, font.plainSubstrByWidth(name(it.item()), 100), x + 20, y + 3, 0xFFFFFFFF);
            int want = mine.getOrDefault(it.item(), 0);
            gfx.drawString(font, (want > 0 ? want + " / " : "") + it.available(), x + 124, y + 3, want > 0 ? 0xFF55FF55 : 0xFFAAAAAA);
        }
        List<String> parts = new ArrayList<>();
        mine.forEach((k, n) -> {
            if (n > 0) parts.add(n + " " + name(k));
        });
        gfx.drawString(font, font.plainSubstrByWidth(parts.isEmpty() ? "Pedido: nada" : "Pedido: " + String.join(", ", parts), W - 146),
                left + 6, top + H - 32, 0xFFFFFFFF);
    }
}
