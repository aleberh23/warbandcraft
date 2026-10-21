package com.ale.warbandcraft.client.army;

import com.ale.warbandcraft.army.ArmyData;
import com.ale.warbandcraft.army.ArmyPanel;
import com.ale.warbandcraft.client.route.RoutesScreen;
import com.ale.warbandcraft.network.ArmyActionMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.PanelActionMessage;
import com.ale.warbandcraft.panel.Panel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pantalla Ejércitos (H20): a la izquierda tus ejércitos, a la derecha cada barracón con sus soldados por clase y
 * botones para sumarlos; abajo Invocar aquí / Licenciar y, en el campo, las órdenes. Se abre desde el Panel (K) y
 * desde la pestaña Barracón del Campamento.
 */
@OnlyIn(Dist.CLIENT)
public class ArmyScreen extends Screen {
    private static final int W = 400, H = 240, LIST_X = 128, LIST_TOP = 74, ROW = 17, VISIBLE = 7;

    /** El ejército elegido (sobrevive a los refrescos y a cerrar la pantalla). */
    @Nullable
    private static UUID chosen;

    private ArmyPanel.Data data;
    private int left, top, scroll;
    @Nullable
    private EditBox nameBox, newBox;

    /** Una línea de la lista: el nombre de un Campamento o una fila de su barracón. */
    private record Entry(BlockPos camp, @Nullable OpenBarracksMessage.Row row, Component label, int available, int picked) {}

    public ArmyScreen(ArmyPanel.Data data) {
        super(Component.translatable("gui.warbandcraft.army.title"));
        this.data = data;
    }

    public static void open(ArmyPanel.Data data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ArmyScreen s) {
            s.data = data;
            s.rebuild();
        } else {
            mc.setScreen(new ArmyScreen(data));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuild() {
        String typed = nameBox != null && nameBox.isFocused() ? nameBox.getValue() : null;
        String typedNew = newBox != null ? newBox.getValue() : null;
        rebuildWidgets();
        if (typed != null && nameBox != null) {
            nameBox.setValue(typed);
            setFocused(nameBox);
        }
        if (typedNew != null && newBox != null) newBox.setValue(typedNew);
    }

    @Nullable
    private ArmyPanel.ArmyInfo army() {
        if (data.armies().isEmpty()) return null;
        for (ArmyPanel.ArmyInfo a : data.armies()) if (a.id().equals(chosen)) return a;
        chosen = data.armies().get(0).id();
        return data.armies().get(0);
    }

    private void send(ArmyActionMessage.Action action, @Nullable BlockPos camp, String text, int amount) {
        ArmyPanel.ArmyInfo a = army();
        ModNetworking.CHANNEL.sendToServer(new ArmyActionMessage(action, a == null ? null : a.id(), camp, text, amount, null, true));
    }

    private List<Entry> entries(@Nullable ArmyPanel.ArmyInfo a) {
        List<Entry> out = new ArrayList<>();
        List<ArmyPanel.PickInfo> left = new ArrayList<>(a == null ? List.of() : a.picks());
        for (ArmyPanel.CampRows c : data.camps()) {
            out.add(new Entry(c.pos(), null, c.name(), 0, 0));
            for (OpenBarracksMessage.Row r : c.rows()) {
                int picked = 0;
                for (ArmyPanel.PickInfo p : List.copyOf(left)) {
                    if (p.camp().equals(c.pos()) && p.row().equals(r.key())) {
                        picked = p.count();
                        left.remove(p);
                    }
                }
                out.add(new Entry(c.pos(), r, r.label(), r.count(), picked));
            }
        }
        for (ArmyPanel.PickInfo p : left) {   // pedidos de filas que hoy no tienen a nadie en el barracón
            out.add(new Entry(p.camp(), new OpenBarracksMessage.Row(p.row(), Component.literal(p.row().isEmpty() ? "-" : p.row()), 0, 0),
                    Component.literal(p.row()), 0, p.count()));
        }
        return out;
    }

    // ------------------------------------------------------------------ botones

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        nameBox = null;
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.army.to_panel"),
                        b -> ModNetworking.CHANNEL.sendToServer(PanelActionMessage.open(Panel.TAB_CAMPS, null)))
                .bounds(left + W - 66, top + 5, 58, 14).build());

        // Tus ejércitos
        ArmyPanel.ArmyInfo a = army();
        int y = top + 40;
        for (ArmyPanel.ArmyInfo x : data.armies()) {
            Button b = Button.builder(Component.literal(x.name()).withStyle(stateColor(x.state())), btn -> {
                chosen = x.id();
                scroll = 0;
                rebuild();
            }).bounds(left + 8, y, 112, 18).build();
            b.active = a == null || !x.id().equals(a.id());
            addRenderableWidget(b);
            y += 20;
        }
        if (data.armies().size() < data.maxArmies()) {
            newBox = addRenderableWidget(new EditBox(font, left + 8, y + 4, 112, 14, Component.translatable("gui.warbandcraft.army.new_name")));
            newBox.setMaxLength(32);
            newBox.setHint(Component.translatable("gui.warbandcraft.army.new_name").withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.army.create"),
                    b -> {
                        ModNetworking.CHANNEL.sendToServer(new ArmyActionMessage(ArmyActionMessage.Action.CREATE, null, null,
                                newBox == null ? "" : newBox.getValue(), 0, null, true));
                        if (newBox != null) newBox.setValue("");
                    }).bounds(left + 8, y + 20, 112, 16).build());
        } else {
            newBox = null;
        }
        if (a == null) return;

        // Nombre
        EditBox box = new EditBox(font, left + LIST_X, top + 40, 150, 14, Component.translatable("gui.warbandcraft.barracks.name"));
        box.setMaxLength(32);
        box.setValue(a.name());
        box.moveCursorToStart();
        nameBox = addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.rename"),
                b -> send(ArmyActionMessage.Action.RENAME, null, nameBox == null ? "" : nameBox.getValue(), 0))
                .bounds(left + LIST_X + 154, top + 40, 60, 14).build());

        // Filas de los barracones
        boolean home = a.state() == ArmyData.State.IDLE.ordinal();
        List<Entry> entries = entries(a);
        scroll = Math.max(0, Math.min(scroll, entries.size() - VISIBLE));
        for (int i = 0; i < VISIBLE && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            if (e.row() == null) continue;
            int ry = top + LIST_TOP + i * ROW;
            int bx = left + W - 8 - 104;
            int[][] steps = {{-10, 24}, {-1, 18}, {1, 18}, {10, 24}, {Integer.MAX_VALUE, 18}};
            String[] labels = {"-10", "-1", "+1", "+10", "T"};
            for (int k = 0; k < steps.length; k++) {
                int amount = steps[k][0];
                Button b = Button.builder(Component.literal(labels[k]), btn -> send(ArmyActionMessage.Action.ASSIGN, e.camp(), e.row().key(), amount))
                        .bounds(bx, ry, steps[k][1], 15).build();
                b.active = home && (amount < 0 ? e.picked() > 0 : e.picked() < e.available() && a.total() < data.max());
                addRenderableWidget(b);
                bx += steps[k][1] + 2;
            }
        }

        // Abajo: invocar / órdenes
        int by = top + H - 24;
        int bx = left + LIST_X;
        if (home) {
            Button summon = Button.builder(Component.translatable("gui.warbandcraft.army.summon"), b -> send(ArmyActionMessage.Action.SUMMON, null, "", 0))
                    .bounds(bx, by, 100, 18).build();
            summon.active = a.ready() > 0 && data.here().getString().isEmpty();
            addRenderableWidget(summon);
            addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.army.delete"), b -> send(ArmyActionMessage.Action.DELETE, null, "", 0))
                    .bounds(left + W - 68, by, 60, 18).build());
        } else if (a.state() == ArmyData.State.COMING.ordinal()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.army.cancel"), b -> send(ArmyActionMessage.Action.CANCEL, null, "", 0))
                    .bounds(bx, by, 100, 18).build());
        } else {
            ArmyActionMessage.Action[] orders = {ArmyActionMessage.Action.HOLD, ArmyActionMessage.Action.CHARGE,
                    ArmyActionMessage.Action.RETREAT, ArmyActionMessage.Action.DISMISS};
            String[] keys = {"hold", "charge", "retreat", "dismiss"};
            for (int k = 0; k < orders.length; k++) {
                ArmyActionMessage.Action order = orders[k];
                addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.army." + keys[k]), b -> send(order, null, "", 0))
                        .bounds(bx, by, 62, 18).build());
                bx += 66;
            }
        }
    }

    private static ChatFormatting stateColor(int state) {
        return state == ArmyData.State.FIELD.ordinal() ? ChatFormatting.GREEN
                : state == ArmyData.State.COMING.ordinal() ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int count = entries(army()).size();
        int next = Math.max(0, Math.min(Math.max(0, count - VISIBLE), scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            rebuild();
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
        gfx.drawString(font, Component.translatable("gui.warbandcraft.army.help", data.max()), left + 8, top + 22, 0xFFBBBBBB);
        gfx.fill(left + LIST_X - 5, top + 38, left + LIST_X - 4, top + H - 6, 0xFF6B5A3A);

        ArmyPanel.ArmyInfo a = army();
        if (a == null) {
            gfx.drawString(font, Component.translatable("gui.warbandcraft.army.none"), left + LIST_X, top + 44, 0xFF999999);
            super.render(gfx, mouseX, mouseY, partialTick);
            return;
        }
        Component state = switch (ArmyData.State.values()[a.state()]) {
            case IDLE -> Component.translatable("gui.warbandcraft.army.state.idle");
            case COMING -> Component.translatable("gui.warbandcraft.army.state.coming", RoutesScreen.time(a.ticksLeft()));
            case FIELD -> Component.translatable(a.mode() == ArmyData.Mode.CHARGE.ordinal() ? "gui.warbandcraft.army.state.charging"
                    : "gui.warbandcraft.army.state.field", a.inField());
        };
        Component summary = Component.translatable("gui.warbandcraft.army.summary", a.total(), data.max(), a.ready(), a.power(), a.food());
        gfx.drawString(font, font.plainSubstrByWidth(summary.getString(), W - LIST_X - 8), left + LIST_X, top + 58, 0xFFDDDDDD);
        gfx.drawString(font, state, left + LIST_X, top + H - 36, 0xFFD4A017);

        List<Entry> entries = entries(a);
        for (int i = 0; i < VISIBLE && scroll + i < entries.size(); i++) {
            Entry e = entries.get(scroll + i);
            int ry = top + LIST_TOP + i * ROW;
            if (e.row() == null) {
                gfx.drawString(font, e.label(), left + LIST_X, ry + 4, 0xFFD4A017);
                continue;
            }
            String text = "  " + e.label().getString();
            gfx.drawString(font, font.plainSubstrByWidth(text, 130), left + LIST_X, ry + 4, 0xFFEEEEEE);
            gfx.drawString(font, e.picked() + "/" + e.available(), left + LIST_X + 136, ry + 4, e.picked() > 0 ? 0xFF55FF55 : 0xFF888888);
        }
        if (entries.isEmpty()) gfx.drawString(font, Component.translatable("gui.warbandcraft.army.no_soldiers"), left + LIST_X, top + LIST_TOP + 4, 0xFF999999);

        if (a.state() == ArmyData.State.IDLE.ordinal()) {
            Component here = data.here().getString().isEmpty() ? Component.translatable("gui.warbandcraft.army.here_ok") : data.here();
            int color = data.here().getString().isEmpty() ? 0xFF55FF55 : 0xFFFF5555;
            gfx.drawString(font, font.plainSubstrByWidth(here.getString(), W - LIST_X - 136), left + LIST_X + 128, top + H - 36, color);
        }
        super.render(gfx, mouseX, mouseY, partialTick);
    }
}
