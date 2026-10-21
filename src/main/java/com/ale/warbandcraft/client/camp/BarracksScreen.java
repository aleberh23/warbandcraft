package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.network.BarracksActionMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.RenameCampMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Pestaña Barracón del Campamento (H8): el nombre del Campamento, cuántos hay guardados, una fila por
 * clase y nivel con botones para sacarlos, guardar a los de cerca, mandar a los guardias a sus puestos y
 * para cuántos días alcanza la comida. Sin textura propia, como Doctrinas. Debajo de los soldados, los
 * prisioneros (H9) con Reclutar y Rescate.
 */
@OnlyIn(Dist.CLIENT)
public class BarracksScreen extends Screen {
    private static final int W = 300, H = 214, ROW = 18, ROWS_TOP = 70, VISIBLE_ROWS = 7;

    private OpenBarracksMessage state;
    private int left, top, scroll;
    private EditBox nameBox;
    private String typedName;

    public BarracksScreen(OpenBarracksMessage state) {
        super(Component.translatable("gui.warbandcraft.tab.barracks"));
        this.state = state;
    }

    public BlockPos camp() {
        return state.camp;
    }

    /** Llegaron datos nuevos (después de un botón): se rehace todo menos lo que se estaba escribiendo. */
    public void refresh(OpenBarracksMessage msg) {
        boolean typing = nameBox != null && nameBox.isFocused();
        if (typing) typedName = nameBox.getValue();
        else typedName = null;
        state = msg;
        scroll = Math.max(0, Math.min(scroll, entries() - VISIBLE_ROWS));
        rebuildWidgets();
        if (typing) setFocused(nameBox);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        for (Button b : CampTabButtons.create(state.camp, CampTabs.BARRACKS, left, top)) addRenderableWidget(b);

        nameBox = new EditBox(font, left + 8, top + 6, 190, 14, Component.translatable("gui.warbandcraft.barracks.name"));
        nameBox.setMaxLength(CampBlockEntity.MAX_NAME_LENGTH);
        nameBox.setValue(typedName != null ? typedName : state.name);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.rename"), b -> rename())
                .bounds(left + 202, top + 6, 90, 14).build());

        Button store = Button.builder(Component.translatable("gui.warbandcraft.barracks.store_near", state.nearby),
                        b -> send(BarracksActionMessage.Action.STORE_NEAR, "", 0))
                .bounds(left + 8, top + 40, 140, 16).build();
        store.active = state.nearby > 0 && state.count < state.capacity;
        addRenderableWidget(store);
        Button guards = Button.builder(Component.translatable("gui.warbandcraft.barracks.guards_out", state.guards),
                        b -> send(BarracksActionMessage.Action.DEPLOY_GUARDS, "", 0))
                .bounds(left + 152, top + 40, 140, 16).build();
        guards.active = state.guards > 0;
        addRenderableWidget(guards);

        // H20: ejércitos de campaña (arriba, entre la cuenta y los presos)
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.panel.tab.armies"),
                        b -> com.ale.warbandcraft.network.ModNetworking.CHANNEL.sendToServer(com.ale.warbandcraft.network.ArmyActionMessage.open()))
                .bounds(left + 115, top + 23, 70, 13).build());

        // H19: campo de entrenamiento de un Campamento común (abajo a la derecha)
        if (state.trainingCost >= 0 || state.trainingOrdered > 0) {
            boolean waiting = state.trainingOrdered > state.training;
            Component label = state.trainingCost < 0
                    ? Component.translatable("gui.warbandcraft.barracks.training_max", state.training)
                    : waiting ? Component.translatable("gui.warbandcraft.barracks.training_wait", state.trainingOrdered)
                    : Component.translatable("gui.warbandcraft.barracks.training", state.training, state.trainingCost);
            Button train = Button.builder(label, b -> send(BarracksActionMessage.Action.TRAINING, "", 0))
                    .bounds(left + W - 158, top + H - 20, 150, 14).build();
            train.active = state.trainingCost >= 0 && !waiting;
            train.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.warbandcraft.barracks.training_tip",
                    com.ale.warbandcraft.camp.Training.bonus(1), com.ale.warbandcraft.camp.Training.cap(1),
                    com.ale.warbandcraft.camp.Training.cap(2), com.ale.warbandcraft.camp.Training.cap(3))));
            addRenderableWidget(train);
        }

        for (int i = 0; i < VISIBLE_ROWS && scroll + i < entries(); i++) {
            int y = top + ROWS_TOP + i * ROW;
            OpenBarracksMessage.PrisonerRow prisoner = prisonerAt(scroll + i);
            if (prisoner != null) {
                Button recruit = Button.builder(Component.translatable("gui.warbandcraft.barracks.prisoner_recruit"),
                                b -> send(BarracksActionMessage.Action.RECRUIT_PRISONER, prisoner.key(), 1))
                        .bounds(left + W - 140, y, 60, 14).build();
                recruit.active = state.count < state.capacity;
                addRenderableWidget(recruit);
                addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.prisoner_ransom", prisoner.ransom()),
                                b -> send(BarracksActionMessage.Action.RANSOM_PRISONER, prisoner.key(), 1))
                        .bounds(left + W - 76, y, 68, 14).build());
                continue;
            }
            OpenBarracksMessage.Row row = state.rows.get(scroll + i);
            addRenderableWidget(Button.builder(Component.literal("1"), b -> send(BarracksActionMessage.Action.DEPLOY, row.key(), 1))
                    .bounds(left + W - 112, y, 28, 14).build());
            Button five = Button.builder(Component.literal("5"), b -> send(BarracksActionMessage.Action.DEPLOY, row.key(), 5))
                    .bounds(left + W - 80, y, 28, 14).build();
            five.active = row.count() > 1;
            addRenderableWidget(five);
            addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.barracks.all"),
                            b -> send(BarracksActionMessage.Action.DEPLOY, row.key(), row.count()))
                    .bounds(left + W - 48, y, 40, 14).build());
        }
    }

    /** Filas de la lista: primero los soldados, después los prisioneros. */
    private int entries() {
        return state.rows.size() + state.prisoners.size();
    }

    @org.jetbrains.annotations.Nullable
    private OpenBarracksMessage.PrisonerRow prisonerAt(int index) {
        int p = index - state.rows.size();
        return p >= 0 && p < state.prisoners.size() ? state.prisoners.get(p) : null;
    }

    private void send(BarracksActionMessage.Action action, String row, int amount) {
        ModNetworking.CHANNEL.sendToServer(new BarracksActionMessage(state.camp, action, row, amount));
    }

    private void rename() {
        String value = nameBox.getValue().strip();
        if (value.isEmpty()) return;
        ModNetworking.CHANNEL.sendToServer(new RenameCampMessage(state.camp, value));
        typedName = null;
        nameBox.setFocused(false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escribiendo el nombre: que la E (inventario) no cierre la pantalla
        if (nameBox != null && nameBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                rename();
                return true;
            }
            return nameBox.keyPressed(keyCode, scanCode, modifiers) || nameBox.canConsumeInput();
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, entries() - VISIBLE_ROWS);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            typedName = nameBox != null && nameBox.isFocused() ? nameBox.getValue() : null;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        gfx.fill(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF6B5A3A);
        gfx.fill(left, top, left + W, top + H, 0xF0201810);

        gfx.drawString(font, Component.translatable("gui.warbandcraft.barracks.count", state.count, state.capacity)
                .withStyle(ChatFormatting.GOLD), left + 8, top + 26, 0xFFFFFF);

        if (state.prisonerCount > 0) {
            Component prisoners = Component.translatable("gui.warbandcraft.barracks.prisoners", state.prisonerCount);
            gfx.drawString(font, prisoners, left + W - 8 - font.width(prisoners), top + 26, 0xBBBBBB);
        }
        if (entries() == 0) {
            gfx.drawString(font, Component.translatable("gui.warbandcraft.barracks.empty"), left + 8, top + ROWS_TOP + 3, 0xAAAAAA);
        }
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < entries(); i++) {
            int y = top + ROWS_TOP + i * ROW;
            if (i % 2 == 0) gfx.fill(left + 4, y - 2, left + W - 4, y + ROW - 2, 0x20FFFFFF);
            OpenBarracksMessage.PrisonerRow prisoner = prisonerAt(scroll + i);
            if (prisoner != null) {
                gfx.drawString(font, prisoner.label(), left + 8, y + 3, 0xFFFFFF);
                Component amount = Component.translatable("gui.warbandcraft.barracks.row", prisoner.count());
                gfx.drawString(font, amount, left + W - 146 - font.width(amount), y + 3, 0xBBBBBB);
                continue;
            }
            OpenBarracksMessage.Row row = state.rows.get(scroll + i);
            gfx.drawString(font, row.label(), left + 8, y + 3, 0xFFFFFF);
            Component amount = row.guards() > 0
                    ? Component.translatable("gui.warbandcraft.barracks.row_guards", row.count(), row.guards())
                    : Component.translatable("gui.warbandcraft.barracks.row", row.count());
            gfx.drawString(font, amount, left + W - 118 - font.width(amount), y + 3, 0xBBBBBB);
        }
        if (entries() > VISIBLE_ROWS) {
            gfx.drawString(font, Component.translatable("gui.warbandcraft.barracks.scroll", scroll + 1,
                    Math.min(entries(), scroll + VISIBLE_ROWS), entries()), left + 8, top + ROWS_TOP + VISIBLE_ROWS * ROW, 0x888888);
        }

        int fy = top + H - 16;
        gfx.fill(left + 4, fy - 5, left + W - 4, fy - 4, 0xFF6B5A3A);
        Component food;
        int color;
        if (state.foodDays < 0) {
            food = Component.translatable("gui.warbandcraft.barracks.food_none");
            color = 0xAAAAAA;
        } else if (state.hungryDays > 0) {
            food = Component.translatable("gui.warbandcraft.barracks.food_hungry", state.hungryDays);
            color = 0xFF5555;
        } else if (state.foodDays == 0) {
            food = Component.translatable("gui.warbandcraft.barracks.food_out");
            color = 0xFFAA00;
        } else {
            food = Component.translatable("gui.warbandcraft.barracks.food_days", state.foodDays);
            color = state.foodDays < 3 ? 0xFFAA00 : 0x55FF55;
        }
        gfx.drawString(font, food, left + 8, fy, color);
        super.render(gfx, mouseX, mouseY, partialTick);
    }
}
