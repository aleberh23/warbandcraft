package com.ale.warbandcraft.client.panel;

import com.ale.warbandcraft.client.ModKeyMappings;
import com.ale.warbandcraft.client.route.RoutesScreen;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.OpenPanelMessage;
import com.ale.warbandcraft.network.PanelActionMessage;
import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.village.VillageStock;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Panel de control (H11): pestañas Aldeas y castillos · Campamentos · Rutas · Envíos, con un resumen del reino
 * arriba. Un Campamento se abre en detalle (Ver): su barracón con Sacar / Mandar por fila y sus prisioneros.
 * Sin textura propia, como Rutas y el Barracón.
 */
@OnlyIn(Dist.CLIENT)
public class PanelScreen extends Screen {
    private static final int W = 400, H = 240, LIST_TOP = 66, ROW = 24, VISIBLE = 6, DETAIL_ROW = 20, DETAIL_VISIBLE = 6, VILLAGE_VISIBLE = 5;
    private static final String[] TABS = {"gui.warbandcraft.panel.tab.villages", "gui.warbandcraft.panel.tab.camps",
            "gui.warbandcraft.panel.tab.routes", "gui.warbandcraft.panel.tab.shipments"};

    private OpenPanelMessage state;
    /** Tick del mundo cuando llegaron los datos (para la cuenta regresiva de los envíos). */
    private long receivedAt;
    private int left, top, scroll;
    /** Destino elegido en el detalle (índice en {@code detail.destinations}). */
    private int destIndex;
    /** Nombre del Campamento o de la aldea en detalle (null en las listas); lo que se estaba escribiendo sobrevive a un refresco. */
    @Nullable
    private EditBox nameBox;
    @Nullable
    private String typedName;

    public PanelScreen(OpenPanelMessage state) {
        super(Component.translatable("gui.warbandcraft.panel.title"));
        setState(state);
    }

    public void refresh(OpenPanelMessage msg) {
        BlockPos oldDest = selectedDest() == null ? null : selectedDest().pos();
        boolean sameView = msg.tab == state.tab && sameDetail(msg);
        setState(msg);
        if (!sameView) scroll = 0;
        destIndex = 0;
        if (oldDest != null && msg.detail != null) {
            for (int i = 0; i < msg.detail.destinations().size(); i++) {
                if (msg.detail.destinations().get(i).pos().equals(oldDest)) destIndex = i;
            }
        }
        rebuild(sameView);
    }

    /** Rehace los botones sin perder lo que se estaba escribiendo en el nombre ({@code keepName} = misma vista). */
    private void rebuild(boolean keepName) {
        boolean typing = keepName && nameBox != null && nameBox.isFocused();
        typedName = typing ? nameBox.getValue() : null;
        rebuildWidgets();
        if (typing && nameBox != null) setFocused(nameBox);
    }

    private boolean sameDetail(OpenPanelMessage msg) {
        if ((msg.village == null) != (state.village == null)) return false;
        if (msg.village != null && !msg.village.pos().equals(state.village.pos())) return false;
        if (msg.detail == null || state.detail == null) return msg.detail == state.detail;
        return msg.detail.pos().equals(state.detail.pos());
    }

    private void setState(OpenPanelMessage msg) {
        state = msg;
        PanelClientHandler.lastTab = msg.tab;
        receivedAt = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime()
                : net.minecraft.client.Minecraft.getInstance().level == null ? 0 : net.minecraft.client.Minecraft.getInstance().level.getGameTime();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void send(PanelActionMessage msg) {
        ModNetworking.CHANNEL.sendToServer(msg);
    }

    private void open(int tab, @Nullable BlockPos camp) {
        send(PanelActionMessage.open(tab, camp));
    }

    @Nullable
    private Panel.Destination selectedDest() {
        if (state.detail == null || state.detail.destinations().isEmpty()) return null;
        return state.detail.destinations().get(Math.min(destIndex, state.detail.destinations().size() - 1));
    }

    // ------------------------------------------------------------------ botones

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        for (int i = 0; i < TABS.length; i++) {
            int tab = i;
            Button b = Button.builder(Component.translatable(TABS[i]), x -> open(tab, null))
                    .bounds(left + 8 + i * 77, top + 40, 74, 18).build();
            b.active = state.detail != null || state.village != null || state.tab != i;
            addRenderableWidget(b);
        }
        // H20: Ejércitos (pantalla aparte)
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.tab.armies"),
                        x -> ModNetworking.CHANNEL.sendToServer(com.ale.warbandcraft.network.ArmyActionMessage.open()))
                .bounds(left + 8 + TABS.length * 77, top + 40, 76, 18).build());
        nameBox = null;
        if (state.detail != null) {
            initName(state.detail.name(), PanelActionMessage.Action.RENAME_CAMP, state.detail.pos());
            initDetail(state.detail);
            return;
        }
        if (state.village != null) {
            initName(state.village.name(), PanelActionMessage.Action.RENAME_VILLAGE, state.village.pos());
            initVillage(state.village);
            return;
        }
        int count = count();
        scroll = Math.max(0, Math.min(scroll, count - VISIBLE));
        for (int i = 0; i < VISIBLE && scroll + i < count; i++) {
            int y = top + LIST_TOP + i * ROW + 3;
            int index = scroll + i;
            switch (state.tab) {
                case Panel.TAB_CAMPS -> {
                    Panel.CampInfo c = state.camps.get(index);
                    addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.view"), b -> open(Panel.TAB_CAMPS, c.pos()))
                            .bounds(left + W - 58, y, 50, 16).build());
                }
                case Panel.TAB_VILLAGES -> {
                    Panel.VillageInfo v = state.villages.get(index);
                    Button send = Button.builder(Component.translatable("gui.warbandcraft.panel.send_stock"),
                                    b -> send(new PanelActionMessage(PanelActionMessage.Action.SEND_STOCK, state.tab, v.pos(), "", 0, null)))
                            .bounds(left + W - 66, y, 58, 16).build();
                    send.active = v.sendTo() != null && !v.stock().isEmpty();
                    send.setTooltip(Tooltip.create(v.sendTo() == null ? Component.translatable("gui.warbandcraft.panel.send_stock_none")
                            : Component.translatable("gui.warbandcraft.panel.send_stock_to", v.sendToName(), RoutesScreen.time(v.sendTicks()))));
                    addRenderableWidget(send);
                    addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.upgrades"),
                                    b -> send(new PanelActionMessage(PanelActionMessage.Action.VILLAGE, state.tab, v.pos(), "", 0, null)))
                            .bounds(left + W - 130, y, 60, 16).build());
                }
                default -> {
                }
            }
        }
    }

    /** Cuadro de nombre + Renombrar, arriba a la derecha (Campamento en detalle o Mejoras de una aldea). */
    private void initName(Component current, PanelActionMessage.Action action, BlockPos pos) {
        EditBox box = new EditBox(font, left + W - 222, top + 5, 150, 14, Component.translatable("gui.warbandcraft.barracks.name"));
        box.setMaxLength(com.ale.warbandcraft.camp.CampBlockEntity.MAX_NAME_LENGTH);
        box.setValue(typedName != null ? typedName : current.getString());
        box.moveCursorToStart();
        nameBox = addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.rename"), b -> rename(action, pos))
                .bounds(left + W - 68, top + 5, 60, 14).build());
    }

    private void rename(PanelActionMessage.Action action, BlockPos pos) {
        if (nameBox == null) return;
        String value = nameBox.getValue().strip();
        if (value.isEmpty()) return;
        nameBox.setFocused(false);
        setFocused(null);
        typedName = null;
        send(new PanelActionMessage(action, state.tab, pos, value, 0, null));
    }

    private void initDetail(Panel.CampDetail d) {
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.back"), b -> open(Panel.TAB_CAMPS, null))
                .bounds(left + 8, top + LIST_TOP, 50, 16).build());
        Button store = Button.builder(Component.translatable("gui.warbandcraft.barracks.store_near", d.nearby()),
                        b -> send(new PanelActionMessage(PanelActionMessage.Action.STORE_NEAR, state.tab, d.pos(), "", 0, null)))
                .bounds(left + 62, top + LIST_TOP, 130, 16).build();
        store.active = d.near() && d.nearby() > 0;
        addRenderableWidget(store);

        // Destino de los envíos: ◀ nombre ▶
        boolean hasDest = !d.destinations().isEmpty();
        Button prev = Button.builder(Component.literal("◀"), b -> cycle(-1)).bounds(left + 200, top + LIST_TOP, 16, 16).build();
        Button next = Button.builder(Component.literal("▶"), b -> cycle(1)).bounds(left + W - 24, top + LIST_TOP, 16, 16).build();
        prev.active = next.active = d.destinations().size() > 1;
        addRenderableWidget(prev);
        addRenderableWidget(next);

        // Fundar una aldea en este Campamento (abajo a la derecha)
        if (d.found()) {
            Button found = Button.builder(Component.translatable("gui.warbandcraft.panel.found", com.ale.warbandcraft.village.Founding.COST),
                            b -> send(new PanelActionMessage(PanelActionMessage.Action.FOUND_VILLAGE, state.tab, d.pos(), "", 0, null)))
                    .bounds(left + W - FOUND_W - 8, top + H - 27, FOUND_W, 16).build();
            boolean blocked = !d.foundBlocked().getString().isEmpty();
            found.active = !blocked;
            found.setTooltip(Tooltip.create(blocked ? d.foundBlocked()
                    : Component.translatable("gui.warbandcraft.panel.found_help", com.ale.warbandcraft.village.Founding.COST,
                    com.ale.warbandcraft.village.Founding.SETTLERS)));
            addRenderableWidget(found);
        }

        int entries = d.rows().size() + d.prisoners().size();
        scroll = Math.max(0, Math.min(scroll, entries - DETAIL_VISIBLE));
        int listTop = top + LIST_TOP + 22;
        Panel.Destination dest = selectedDest();
        for (int i = 0; i < DETAIL_VISIBLE && scroll + i < entries; i++) {
            int y = listTop + i * DETAIL_ROW;
            int index = scroll + i;
            if (index >= d.rows().size()) {
                OpenBarracksMessage.PrisonerRow p = d.prisoners().get(index - d.rows().size());
                Button recruit = Button.builder(Component.translatable("gui.warbandcraft.barracks.prisoner_recruit"),
                                b -> send(new PanelActionMessage(PanelActionMessage.Action.RECRUIT_PRISONER, state.tab, d.pos(), p.key(), 1, null)))
                        .bounds(left + W - 150, y, 66, 16).build();
                recruit.active = d.soldiers() < d.capacity();
                addRenderableWidget(recruit);
                addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.prisoner_ransom", p.ransom()),
                                b -> send(new PanelActionMessage(PanelActionMessage.Action.RANSOM_PRISONER, state.tab, d.pos(), p.key(), 1, null)))
                        .bounds(left + W - 80, y, 72, 16).build());
                continue;
            }
            OpenBarracksMessage.Row r = d.rows().get(index);
            int x = left + W - 8 - 236;
            Button one = Button.builder(Component.translatable("gui.warbandcraft.panel.deploy_one"),
                            b -> send(new PanelActionMessage(PanelActionMessage.Action.DEPLOY, state.tab, d.pos(), r.key(), 1, null)))
                    .bounds(x, y, 44, 16).build();
            Button all = Button.builder(Component.translatable("gui.warbandcraft.panel.deploy_all"),
                            b -> send(new PanelActionMessage(PanelActionMessage.Action.DEPLOY, state.tab, d.pos(), r.key(), r.count(), null)))
                    .bounds(x + 46, y, 62, 16).build();
            one.active = all.active = d.near();
            if (!d.near()) {
                Tooltip far = Tooltip.create(Component.translatable("gui.warbandcraft.panel.deploy_far", Panel.NEAR_DISTANCE));
                one.setTooltip(far);
                all.setTooltip(far);
            }
            Button sendOne = Button.builder(Component.translatable("gui.warbandcraft.panel.send_one"),
                            b -> sendSoldiers(r.key(), 1))
                    .bounds(x + 112, y, 52, 16).build();
            Button sendAll = Button.builder(Component.translatable("gui.warbandcraft.panel.send_all"),
                            b -> sendSoldiers(r.key(), r.count()))
                    .bounds(x + 166, y, 70, 16).build();
            sendOne.active = sendAll.active = hasDest && dest != null && dest.free() > 0;
            addRenderableWidget(one);
            addRenderableWidget(all);
            addRenderableWidget(sendOne);
            addRenderableWidget(sendAll);
        }
    }

    // ------------------------------------------------------------------ H12: mejoras de una aldea

    private static final int VILLAGE_LIST_TOP = LIST_TOP + 22;

    private void initVillage(Panel.VillageDetail v) {
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.back"), b -> open(Panel.TAB_VILLAGES, null))
                .bounds(left + 8, top + LIST_TOP, 50, 16).build());
        int count = v.upgrades().size();
        scroll = Math.max(0, Math.min(scroll, count - VILLAGE_VISIBLE));
        for (int i = 0; i < VILLAGE_VISIBLE && scroll + i < count; i++) {
            Panel.UpgradeRow u = v.upgrades().get(scroll + i);
            if (u.state() != com.ale.warbandcraft.upgrade.Upgrades.State.AVAILABLE.ordinal()) continue;
            com.ale.warbandcraft.upgrade.Upgrade up = com.ale.warbandcraft.upgrade.Upgrade.byId(u.id());
            if (up != null && up.handPlaced() && !up.isLevelUp() && !v.castle()) continue;   // H22: se planta en Gestión (V)
            int y = top + VILLAGE_LIST_TOP + i * ROW + 3;
            addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.build", u.cost()),
                            b -> send(new PanelActionMessage(PanelActionMessage.Action.BUILD, state.tab, v.pos(), u.id(), 0, null)))
                    .bounds(left + W - 88, y, 80, 16).build());
        }
    }

    private void renderVillage(GuiGraphics gfx, Panel.VillageDetail v) {
        Component head = Component.empty().append(v.name()).append(" · ")
                .append(v.castle() ? Component.translatable("gui.warbandcraft.panel.castle") : levelName(v.level()))
                .append(Component.translatable("gui.warbandcraft.panel.builder_level", v.builder()))
                .append(Component.translatable("gui.warbandcraft.panel.pay_from", v.emeralds()));
        gfx.drawString(font, font.plainSubstrByWidth(head.getString(), W - 74), left + 64, top + LIST_TOP + 4, 0xFFFFD86B);
        int count = v.upgrades().size();
        for (int i = 0; i < VILLAGE_VISIBLE && scroll + i < count; i++) {
            Panel.UpgradeRow u = v.upgrades().get(scroll + i);
            int y = top + VILLAGE_LIST_TOP + i * ROW;
            if (i % 2 == 0) gfx.fill(left + 4, y, left + W - 4, y + ROW - 1, 0x20FFFFFF);
            com.ale.warbandcraft.upgrade.Upgrades.State st = com.ale.warbandcraft.upgrade.Upgrades.State.values()[u.state()];
            Component status = switch (st) {
                case DONE -> Component.translatable("gui.warbandcraft.panel.up_done");
                case BUILDING -> Component.translatable("gui.warbandcraft.panel.up_building", u.percent());
                case AVAILABLE -> Component.empty();
                case LOCKED -> Component.translatable("gui.warbandcraft.panel.up_locked", u.reason(), u.cost());
            };
            int color = switch (st) {
                case DONE -> 0xFF55FF55;
                case BUILDING -> 0xFFFFD86B;
                case AVAILABLE -> 0xFFFFFFFF;
                case LOCKED -> 0xFF888888;
            };
            Component first = Component.empty().append(u.name()).append(status.getString().isEmpty() ? Component.empty()
                    : Component.literal(" · ").append(status));
            int right = st == com.ale.warbandcraft.upgrade.Upgrades.State.AVAILABLE ? 92 : 0;
            gfx.drawString(font, font.plainSubstrByWidth(first.getString(), W - 16 - right), left + 8, y + 2, color);
            gfx.drawString(font, font.plainSubstrByWidth(u.effect().getString(), W - 16 - right), left + 8, y + 13, 0xFFAAAAAA);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.panel.upgrades_help"));
    }

    private static Component levelName(int level) {
        return Component.translatable("gui.warbandcraft.panel.level_" + Math.max(1, Math.min(3, level)));
    }

    private int ticks;

    @Override
    public void tick() {
        super.tick();
        // Con una obra en marcha, el porcentaje se pide de nuevo cada 2 s
        if (++ticks % 40 != 0) return;
        if (state.village == null) {
            // H14: caminos en obra en la pestaña Rutas
            if (state.detail == null && state.tab == Panel.TAB_ROUTES && state.routes.stream().anyMatch(r -> r.percent() >= 0)) {
                open(Panel.TAB_ROUTES, null);
            }
            return;
        }
        int building = com.ale.warbandcraft.upgrade.Upgrades.State.BUILDING.ordinal();
        if (state.village.upgrades().stream().anyMatch(u -> u.state() == building)) {
            send(new PanelActionMessage(PanelActionMessage.Action.VILLAGE, state.tab, state.village.pos(), "", 0, null));
        }
    }

    private void sendSoldiers(String row, int amount) {
        Panel.Destination dest = selectedDest();
        if (state.detail == null || dest == null) return;
        send(new PanelActionMessage(PanelActionMessage.Action.SEND_SOLDIERS, state.tab, state.detail.pos(), row, amount, dest.pos()));
    }

    private void cycle(int dir) {
        if (state.detail == null || state.detail.destinations().isEmpty()) return;
        int n = state.detail.destinations().size();
        destIndex = ((destIndex + dir) % n + n) % n;
        rebuild(true);
    }

    private int count() {
        return switch (state.tab) {
            case Panel.TAB_CAMPS -> state.camps.size();
            case Panel.TAB_VILLAGES -> state.villages.size();
            case Panel.TAB_ROUTES -> state.routes.size();
            default -> state.shipments.size();
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escribiendo el nombre: que la E o la K no cierren el panel; Enter = Renombrar
        if (nameBox != null && nameBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (state.detail != null) rename(PanelActionMessage.Action.RENAME_CAMP, state.detail.pos());
                else if (state.village != null) rename(PanelActionMessage.Action.RENAME_VILLAGE, state.village.pos());
                return true;
            }
            return nameBox.keyPressed(keyCode, scanCode, modifiers) || nameBox.canConsumeInput();
        }
        if (minecraft != null && (minecraft.options.keyInventory.matches(keyCode, scanCode)
                || ModKeyMappings.PANEL.matches(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int count = state.detail != null ? state.detail.rows().size() + state.detail.prisoners().size()
                : state.village != null ? state.village.upgrades().size() : count();
        int visible = state.detail != null ? DETAIL_VISIBLE : state.village != null ? VILLAGE_VISIBLE : VISIBLE;
        int next = Math.max(0, Math.min(Math.max(0, count - visible), scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            rebuild(true);
        }
        return true;
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        gfx.fill(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF6B5A3A);
        gfx.fill(left, top, left + W, top + H, 0xF0201810);

        gfx.drawString(font, title, left + 8, top + 8, 0xFFD4A017);
        int[] t = state.totals();
        Component summary = Component.translatable("gui.warbandcraft.panel.summary", state.camps.size(),
                countKind(false), countKind(true), t[0], t[1], t[2], t[3], t[4], t[5]);
        gfx.drawString(font, font.plainSubstrByWidth(summary.getString(), W - 16), left + 8, top + 22, 0xFFBBBBBB);

        if (state.detail != null) {
            renderDetail(gfx, state.detail);
        } else if (state.village != null) {
            renderVillage(gfx, state.village);
        } else {
            switch (state.tab) {
                case Panel.TAB_CAMPS -> renderCamps(gfx);
                case Panel.TAB_VILLAGES -> renderVillages(gfx);
                case Panel.TAB_ROUTES -> renderRoutes(gfx);
                default -> renderShipments(gfx);
            }
        }
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private int countKind(boolean castle) {
        int n = 0;
        for (Panel.VillageInfo v : state.villages) if (v.castle() == castle) n++;
        return n;
    }

    private void row(GuiGraphics gfx, int i, Component first, int firstColor, Component second, int secondColor, int rightMargin) {
        int y = top + LIST_TOP + i * ROW;
        if (i % 2 == 0) gfx.fill(left + 4, y, left + W - 4, y + ROW - 1, 0x20FFFFFF);
        int maxW = W - 16 - rightMargin;
        gfx.drawString(font, font.plainSubstrByWidth(first.getString(), maxW), left + 8, y + 2, firstColor);
        gfx.drawString(font, font.plainSubstrByWidth(second.getString(), maxW), left + 8, y + 13, secondColor);
    }

    private void empty(GuiGraphics gfx, String key) {
        gfx.drawWordWrap(font, Component.translatable(key), left + 8, top + LIST_TOP + 4, W - 16, 0xFFAAAAAA);
    }

    /** "120 m" desde donde estás. */
    private String distance(BlockPos pos) {
        if (minecraft == null || minecraft.player == null) return "";
        double dx = minecraft.player.getX() - (pos.getX() + 0.5), dz = minecraft.player.getZ() - (pos.getZ() + 0.5);
        return (int) Math.sqrt(dx * dx + dz * dz) + " m";
    }

    private void renderCamps(GuiGraphics gfx) {
        if (state.camps.isEmpty()) {
            empty(gfx, "gui.warbandcraft.panel.no_camps");
            return;
        }
        for (int i = 0; i < VISIBLE && scroll + i < state.camps.size(); i++) {
            Panel.CampInfo c = state.camps.get(scroll + i);
            Component first = Component.empty().append(c.name()).append(" · " + distance(c.pos()));
            Component food = switch (c.foodDays()) {
                case -1 -> Component.translatable("gui.warbandcraft.panel.food_empty");
                case -2 -> Component.translatable("gui.warbandcraft.panel.food_unknown");
                default -> Component.translatable("gui.warbandcraft.panel.food_days", c.foodDays());
            };
            Component store = c.emeralds() < 0 ? Component.translatable("gui.warbandcraft.panel.store_unknown")
                    : Component.translatable("gui.warbandcraft.panel.store", c.emeralds(), c.armament(), c.smiths());
            Component second = Component.translatable("gui.warbandcraft.panel.camp_line", c.soldiers(),
                    c.capacity(), c.guards(), c.prisoners(), food, store);
            int foodColor = c.foodDays() == 0 ? 0xFFFF7777 : 0xFFBBBBBB;
            row(gfx, i, first, c.near() ? 0xFFFFD86B : 0xFFFFFFFF, second, foodColor, 60);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.panel.camps_help", Panel.NEAR_DISTANCE));
    }

    private void renderVillages(GuiGraphics gfx) {
        if (state.villages.isEmpty()) {
            empty(gfx, "gui.warbandcraft.panel.no_villages");
            return;
        }
        for (int i = 0; i < VISIBLE && scroll + i < state.villages.size(); i++) {
            Panel.VillageInfo v = state.villages.get(scroll + i);
            Component first = Component.empty().append(v.name()).append(" · " + distance(v.pos()))
                    .append(Component.translatable("gui.warbandcraft.panel.guards", v.guards()))
                    .append(v.castle() ? Component.empty() : Component.literal(" · ").append(levelName(v.level())))
                    .append(v.building().getString().isEmpty() ? Component.empty()
                            : Component.translatable("gui.warbandcraft.panel.building", v.building(), v.percent()));
            VillageStock.Stock d = v.daily(), s = v.stock(), c = v.cap();
            Component second = v.castle()
                    ? Component.translatable("gui.warbandcraft.panel.castle_line", d.emeralds(), d.food(), d.armament(),
                    s.emeralds(), c.emeralds(), s.food(), c.food(), s.armament(), c.armament())
                    : Component.translatable("gui.warbandcraft.panel.village_line", d.emeralds(), d.food(),
                    s.emeralds(), c.emeralds(), s.food(), c.food());
            if (v.popularity() >= 0) {   // H21: popularidad
                second = second.copy().append(Component.translatable("gui.warbandcraft.panel.popularity", v.popularity())
                        .withStyle(v.popularity() >= com.ale.warbandcraft.village.Popularity.THRESHOLD ? net.minecraft.ChatFormatting.GREEN
                                : net.minecraft.ChatFormatting.RED));
            }
            if (v.stuckWorkers() > 0) {   // H18: trabajadores trabados
                second = second.copy().append(Component.translatable("gui.warbandcraft.panel.stuck_workers", v.stuckWorkers())
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            int color = v.castle() ? 0xFFFFB347 : 0xFFFFFFFF;
            if (v.invasionTicks() >= 0) {   // H15a: un ejército en camino (o peleando)
                long elapsed = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() - receivedAt : 0;
                int left = (int) Math.max(0, v.invasionTicks() - elapsed);
                first = Component.empty().append(v.name()).append(" · ").append(left > 0
                        ? Component.translatable("gui.warbandcraft.panel.invasion", v.invader(), RoutesScreen.time(left))
                        : Component.translatable("gui.warbandcraft.panel.invasion_now", v.invader()));
                color = 0xFFFF5555;
            }
            row(gfx, i, first, color, second, 0xFFFFE08A, 136);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.panel.villages_help"));
    }

    private void renderRoutes(GuiGraphics gfx) {
        if (state.routes.isEmpty()) {
            empty(gfx, "gui.warbandcraft.panel.no_routes");
            return;
        }
        for (int i = 0; i < VISIBLE && scroll + i < state.routes.size(); i++) {
            Panel.RouteInfo r = state.routes.get(scroll + i);
            Component status = r.percent() >= 0 ? Component.translatable("gui.warbandcraft.panel.route_building", r.percent())
                    : Component.translatable(!r.active() ? "gui.warbandcraft.panel.route_inactive"
                    : r.road() ? "gui.warbandcraft.panel.route_road" : "gui.warbandcraft.panel.route_no_road");
            Component first = Component.literal(r.name() + " · ").append(status);
            Component second = Component.translatable("gui.warbandcraft.panel.route_line", r.a(), r.b(), r.length(),
                    RoutesScreen.time(r.ticks()));
            row(gfx, i, first, r.active() ? 0xFF55FF55 : 0xFFFF5555, second, 0xFFBBBBBB, 0);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.panel.routes_help"));
    }

    private void renderShipments(GuiGraphics gfx) {
        if (state.shipments.isEmpty()) {
            empty(gfx, "gui.warbandcraft.panel.no_shipments");
            return;
        }
        long elapsed = minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() - receivedAt : 0;
        for (int i = 0; i < VISIBLE && scroll + i < state.shipments.size(); i++) {
            Panel.ShipmentInfo s = state.shipments.get(scroll + i);
            Component first = Component.translatable("gui.warbandcraft.routes.ends", s.from(), s.to());
            int remaining = (int) Math.max(0, s.ticksLeft() - elapsed);
            Component when = remaining > 0 ? Component.translatable("gui.warbandcraft.panel.arrives_in", RoutesScreen.time(remaining))
                    : Component.translatable("gui.warbandcraft.panel.arriving");
            Component second = Component.translatable("gui.warbandcraft.panel.shipment_line", s.soldiers(), s.stacks(), when);
            row(gfx, i, first, 0xFFFFFFFF, second, 0xFFBBBBBB, 0);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.panel.shipments_help"));
    }

    private void renderDetail(GuiGraphics gfx, Panel.CampDetail d) {
        int titleY = top + LIST_TOP - 2;
        Panel.Destination dest = selectedDest();
        Component destText = dest == null ? Component.translatable("gui.warbandcraft.panel.no_dest")
                : Component.empty().append(dest.name()).append(Component.translatable("gui.warbandcraft.panel.dest_info",
                RoutesScreen.time(dest.ticks()), dest.free()));
        String destStr = font.plainSubstrByWidth(destText.getString(), W - 24 - 220);
        gfx.drawString(font, destStr, left + 220, titleY + 6, dest == null ? 0xFF888888 : 0xFFFFFFFF);

        int listTop = top + LIST_TOP + 22;
        int entries = d.rows().size() + d.prisoners().size();
        if (entries == 0) {
            gfx.drawString(font, Component.translatable("gui.warbandcraft.barracks.empty"), left + 8, listTop + 4, 0xFFAAAAAA);
        }
        for (int i = 0; i < DETAIL_VISIBLE && scroll + i < entries; i++) {
            int y = listTop + i * DETAIL_ROW;
            int index = scroll + i;
            if (i % 2 == 0) gfx.fill(left + 4, y - 2, left + W - 4, y + DETAIL_ROW - 2, 0x20FFFFFF);
            if (index >= d.rows().size()) {
                OpenBarracksMessage.PrisonerRow p = d.prisoners().get(index - d.rows().size());
                Component label = Component.translatable("gui.warbandcraft.panel.prisoner").withStyle(ChatFormatting.GRAY).append(p.label()).append(" ×" + p.count());
                gfx.drawString(font, font.plainSubstrByWidth(label.getString(), W - 170), left + 8, y + 4, 0xFFCCCCCC);
                continue;
            }
            OpenBarracksMessage.Row r = d.rows().get(index);
            String label = r.label().getString() + " ×" + r.count() + (r.guards() > 0 ? " (" + r.guards() + "g)" : "");
            gfx.drawString(font, font.plainSubstrByWidth(label, W - 16 - 244), left + 8, y + 4, 0xFFFFFFFF);
        }
        Component name = Component.translatable("gui.warbandcraft.panel.detail_title", d.name(), d.soldiers(),
                d.capacity());
        footer(gfx, Component.empty().append(name).append(" · ").append(d.near()
                ? Component.translatable("gui.warbandcraft.panel.detail_near")
                : Component.translatable("gui.warbandcraft.panel.deploy_far", Panel.NEAR_DISTANCE)), d.found() ? FOUND_W + 6 : 0);
    }

    /** Ancho del botón Fundar aldea (en el pie del detalle de un Campamento). */
    private static final int FOUND_W = 110;

    private void footer(GuiGraphics gfx, Component text) {
        footer(gfx, text, 0);
    }

    /** {@code reserve}: ancho que se deja libre a la derecha (para un botón). */
    private void footer(GuiGraphics gfx, Component text, int reserve) {
        int fy = top + H - 24;
        gfx.fill(left + 4, fy - 5, left + W - 4, fy - 4, 0xFF6B5A3A);
        gfx.drawWordWrap(font, text, left + 8, fy, W - 16 - reserve, 0xFF888888);
    }
}
