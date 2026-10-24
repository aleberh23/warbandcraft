package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.ShipmentMenu;
import com.ale.warbandcraft.client.route.RoutesScreen;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.SendShipmentMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pestaña Envíos del Campamento (H13): destino con ◀ ▶, cuántos soldados de cada fila del barracón van (− / +),
 * la caja de carga (solo si el destino es un Campamento), el botón Mandar y lo que ya va en camino.
 */
@OnlyIn(Dist.CLIENT)
public class ShipmentScreen extends AbstractContainerScreen<ShipmentMenu> {
    private static final int ROW = 16, ROWS_TOP = 40, VISIBLE_ROWS = 4;

    private int dest, scroll;
    private final Map<String, Integer> chosen = new LinkedHashMap<>();

    public ShipmentScreen(ShipmentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = ShipmentMenu.WIDTH;
        imageHeight = ShipmentMenu.HEIGHT;
        titleLabelX = 8;
        titleLabelY = 6;
        inventoryLabelX = ShipmentMenu.INV_X;
        inventoryLabelY = ShipmentMenu.INV_Y - 10;
    }

    private ShipmentMenu.Info info() {
        return menu.info();
    }

    private ShipmentMenu.Dest current() {
        List<ShipmentMenu.Dest> d = info().dests();
        return d.isEmpty() ? null : d.get(Math.floorMod(dest, d.size()));
    }

    private int chosenTotal() {
        int n = 0;
        for (int v : chosen.values()) n += v;
        return n;
    }

    @Override
    protected void init() {
        try {
            build();
        } catch (RuntimeException e) {
            // Forge se traga los errores al abrir pantallas: sin esto la pestaña "no abre" sin dejar rastro
            com.ale.warbandcraft.WarbandCraft.LOGGER.error("No se pudo armar la pestaña Envíos", e);
            throw e;
        }
    }

    private void build() {
        super.init();
        for (Button b : CampTabButtons.create(info().camp(), CampTabs.SHIPMENTS, leftPos, topPos)) addRenderableWidget(b);
        if (info().dests().isEmpty()) return;

        addRenderableWidget(Button.builder(Component.literal("◀"), b -> { dest--; rebuildWidgets(); })
                .bounds(leftPos + 70, topPos + 18, 16, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> { dest++; rebuildWidgets(); })
                .bounds(leftPos + imageWidth - 24, topPos + 18, 16, 14).build());

        List<OpenBarracksMessage.Row> rows = info().rows();
        scroll = Math.max(0, Math.min(scroll, rows.size() - VISIBLE_ROWS));
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
            OpenBarracksMessage.Row row = rows.get(scroll + i);
            int y = topPos + ROWS_TOP + i * ROW;
            int have = chosen.getOrDefault(row.key(), 0);
            Button minus = Button.builder(Component.literal("−"), b -> change(row, -1))
                    .bounds(leftPos + imageWidth - 64, y, 16, 14).build();
            minus.active = have > 0;
            addRenderableWidget(minus);
            Button plus = Button.builder(Component.literal("+"), b -> change(row, hasShiftDown() ? row.count() : 1))
                    .bounds(leftPos + imageWidth - 24, y, 16, 14).build();
            plus.active = have < row.count();
            addRenderableWidget(plus);
        }

        Button send = Button.builder(Component.translatable("gui.warbandcraft.shipments.send"), b -> send())
                .bounds(leftPos + 8, topPos + 166, 60, 16).build();
        ShipmentMenu.Dest d = current();
        send.active = d != null && chosenTotal() <= d.free();
        addRenderableWidget(send);
    }

    private void change(OpenBarracksMessage.Row row, int delta) {
        int next = Math.max(0, Math.min(row.count(), chosen.getOrDefault(row.key(), 0) + delta));
        chosen.put(row.key(), next);
        rebuildWidgets();
    }

    private void send() {
        ShipmentMenu.Dest d = current();
        if (d == null) return;
        Map<String, Integer> rows = new LinkedHashMap<>();
        chosen.forEach((k, v) -> { if (v > 0) rows.put(k, v); });
        ModNetworking.CHANNEL.sendToServer(new SendShipmentMessage(info().camp(), d.pos(), rows));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, info().rows().size() - VISIBLE_ROWS);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        gfx.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, 0xFF6B5A3A);
        gfx.fill(x, y, x + imageWidth, y + imageHeight, 0xF0201810);
        ShipmentMenu.Dest d = current();
        boolean cargoAllowed = d != null && d.camp();
        for (Slot slot : menu.slots) {
            int sx = x + slot.x, sy = y + slot.y;
            boolean cargo = slot.index < ShipmentMenu.CARGO;
            gfx.fill(sx - 1, sy - 1, sx + 17, sy + 17, cargo ? 0xFFB08A3A : 0xFF373737);
            gfx.fill(sx, sy, sx + 16, sy + 16, cargo && !cargoAllowed ? 0xFF5A4A4A : 0xFF8B8B8B);
        }
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < info().rows().size(); i++) {
            if (i % 2 == 0) gfx.fill(x + 4, y + ROWS_TOP + i * ROW - 1, x + imageWidth - 4, y + ROWS_TOP + (i + 1) * ROW - 1, 0x20FFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(font, Component.translatable("gui.warbandcraft.shipments.title", info().name()), titleLabelX, titleLabelY, 0xFFD4A017, false);
        gfx.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFBBBBBB, false);

        ShipmentMenu.Dest d = current();
        gfx.drawString(font, Component.translatable("gui.warbandcraft.shipments.dest"), 8, 21, 0xFFBBBBBB, false);
        if (d == null) {
            gfx.drawWordWrap(font, Component.translatable("gui.warbandcraft.shipments.no_dests"), 8, ROWS_TOP, imageWidth - 16, 0xFFAAAAAA);
        } else {
            String name = font.plainSubstrByWidth(d.name().getString(), imageWidth - 24 - 90 - 50);
            gfx.drawString(font, name, 90, 21, 0xFFFFFFFF, false);
            String t = RoutesScreen.time(d.ticks());
            gfx.drawString(font, t, imageWidth - 28 - font.width(t), 21, 0xFFBBBBBB, false);

            List<OpenBarracksMessage.Row> rows = info().rows();
            if (rows.isEmpty()) gfx.drawString(font, Component.translatable("gui.warbandcraft.shipments.no_soldiers"), 8, ROWS_TOP + 3, 0xFFAAAAAA, false);
            for (int i = 0; i < VISIBLE_ROWS && scroll + i < rows.size(); i++) {
                OpenBarracksMessage.Row row = rows.get(scroll + i);
                int y = ROWS_TOP + i * ROW + 3;
                gfx.drawString(font, font.plainSubstrByWidth(row.label().getString(), imageWidth - 120), 8, y, 0xFFFFFFFF, false);
                String amount = chosen.getOrDefault(row.key(), 0) + " / " + row.count();
                gfx.drawString(font, amount, imageWidth - 44 - font.width(amount) / 2 - 2, y, 0xFFFFFFFF, false);
            }
            int total = chosenTotal();
            Component cap = Component.translatable("gui.warbandcraft.shipments.capacity", total, d.free());
            gfx.drawString(font, cap, 8, ROWS_TOP + VISIBLE_ROWS * ROW + 2, total > d.free() ? 0xFFFF5555 : 0xFFBBBBBB, false);

            Component cargo = d.camp() ? Component.translatable("gui.warbandcraft.shipments.cargo")
                    : Component.translatable("gui.warbandcraft.shipments.cargo_village");
            gfx.drawString(font, cargo, ShipmentMenu.CARGO_X, ShipmentMenu.CARGO_Y - 10, d.camp() ? 0xFFBBBBBB : 0xFFFFAA00, false);
        }

        // Lo que va en camino (hasta 2 líneas)
        List<ShipmentMenu.Transit> transit = info().transit();
        int ty = 166;
        for (int i = 0; i < Math.min(2, transit.size()); i++) {
            ShipmentMenu.Transit t = transit.get(i);
            Component line = Component.translatable("gui.warbandcraft.shipments.transit", t.dest(), t.soldiers(), t.stacks(),
                    RoutesScreen.time(t.ticksLeft()));
            gfx.drawString(font, font.plainSubstrByWidth(line.getString(), imageWidth - 80), 74, ty + i * 10, 0xFFBBBBBB, false);
        }
        if (transit.size() > 2) {
            gfx.drawString(font, "+" + (transit.size() - 2), imageWidth - 20, ty + 10, 0xFF888888, false);
        }
    }
}
