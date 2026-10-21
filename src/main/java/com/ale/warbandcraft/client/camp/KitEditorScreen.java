package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.KitEditorMenu;
import com.ale.warbandcraft.kit.KitOverride;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.RenameClassMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * Pestaña Kits del Campamento: lista de clases a la izquierda; a la derecha el nombre, el nivel, el
 * equipo (6 espacios) y la mochila (9) como plantilla, el costo y si el Campamento alcanza.
 * Sin textura propia: rectángulos lisos hasta que H7 le dé arte.
 */
@OnlyIn(Dist.CLIENT)
public class KitEditorScreen extends AbstractContainerScreen<KitEditorMenu> {
    private static final String[] SLOT_KEYS = {"head", "chest", "legs", "feet", "offhand", "mainhand"};

    private EditBox nameBox;
    private int shownClass = -1, shownTier = -1;

    public KitEditorScreen(KitEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = KitEditorMenu.WIDTH;
        imageHeight = KitEditorMenu.HEIGHT;
        titleLabelX = 8;
        titleLabelY = 5;
        inventoryLabelX = KitEditorMenu.INV_X;
        inventoryLabelY = KitEditorMenu.INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        shownClass = menu.selectedClass();
        shownTier = menu.selectedTier();
        for (Button b : CampTabButtons.create(menu.campPos(), CampTabs.KITS, leftPos, topPos)) addRenderableWidget(b);

        var classes = menu.classes();
        for (int i = 0; i < classes.size(); i++) {
            int id = i;
            Button b = Button.builder(Component.literal(classes.get(i).name), x -> press(id))
                    .bounds(leftPos + 6, topPos + 16 + i * 16, 80, 14).build();
            b.active = i != shownClass;
            addRenderableWidget(b);
        }

        KitEditorMenu.ClassInfo current = menu.current();
        if (current == null) return;
        nameBox = new EditBox(font, leftPos + 92, topPos + 16, 120, 14, Component.translatable("gui.warbandcraft.kits.name"));
        nameBox.setMaxLength(24);
        nameBox.setValue(current.name);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.kits.rename"), x -> rename())
                .bounds(leftPos + 216, topPos + 16, 78, 14).build());

        for (int t = 0; t < current.tierNames.size(); t++) {
            int tier = t;
            Button b = Button.builder(Component.literal(current.tierNames.get(t)), x -> press(KitEditorMenu.BTN_TIER + tier))
                    .bounds(leftPos + 92 + t * 68, topPos + 33, 64, 14).build();
            b.active = t != shownTier;
            addRenderableWidget(b);
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.kits.save"), x -> press(KitEditorMenu.BTN_SAVE))
                .bounds(leftPos + 92, topPos + 142, 70, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.kits.reset"), x -> press(KitEditorMenu.BTN_RESET))
                .bounds(leftPos + 166, topPos + 142, 110, 14).build());
    }

    private void press(int id) {
        if (minecraft != null && minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void rename() {
        KitEditorMenu.ClassInfo current = menu.current();
        if (current == null) return;
        String value = nameBox.getValue().strip();
        ModNetworking.CHANNEL.sendToServer(new RenameClassMessage(menu.campPos(), current.id, value));
        if (!value.isEmpty()) current.name = value;
        rebuildWidgets();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Cambió la clase o el nivel (el servidor lo confirma por los DataSlot): rehacer botones.
        if (menu.selectedClass() != shownClass || menu.selectedTier() != shownTier) rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escribiendo el nombre: que la E (inventario) y los números no cierren ni muevan nada.
        if (nameBox != null && nameBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                rename();
                return true;
            }
            return nameBox.keyPressed(keyCode, scanCode, modifiers) || nameBox.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderTooltip(GuiGraphics gfx, int mouseX, int mouseY) {
        Slot slot = hoveredSlot;
        if (slot != null && slot.index < KitOverride.SLOTS.length && !slot.hasItem() && menu.getCarried().isEmpty()) {
            gfx.renderTooltip(font, Component.translatable("gui.warbandcraft.kits.slot." + SLOT_KEYS[slot.index]), mouseX, mouseY);
            return;
        }
        super.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        gfx.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, 0xFF6B5A3A);
        gfx.fill(x, y, x + imageWidth, y + imageHeight, 0xF0201810);
        gfx.fill(x + 88, y + 14, x + 89, y + imageHeight - 4, 0xFF6B5A3A);
        for (Slot slot : menu.slots) {
            int sx = x + slot.x, sy = y + slot.y;
            boolean ghost = slot.index < KitEditorMenu.GHOST && slot.container != playerInventoryContainer();
            gfx.fill(sx - 1, sy - 1, sx + 17, sy + 17, ghost ? 0xFFB08A3A : 0xFF373737);
            gfx.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
        }
    }

    private Object playerInventoryContainer() {
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getInventory();
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(font, title, titleLabelX, titleLabelY, 0xFFD4A017, false);
        gfx.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFBBBBBB, false);
        gfx.drawString(font, Component.translatable("gui.warbandcraft.kits.equipment"), KitEditorMenu.EQUIP_X, KitEditorMenu.EQUIP_Y - 10, 0xFFBBBBBB, false);
        gfx.drawString(font, Component.translatable("gui.warbandcraft.kits.backpack"), KitEditorMenu.PACK_X, KitEditorMenu.PACK_Y - 10, 0xFFBBBBBB, false);

        // Costo (equipo + mochila + costo extra del nivel), como íconos con cantidad
        gfx.drawString(font, Component.translatable("gui.warbandcraft.kits.cost"), 92, 115, 0xFFBBBBBB, false);
        int ix = 92 + font.width(Component.translatable("gui.warbandcraft.kits.cost")) + 4;
        int shown = 0;
        for (Map.Entry<Item, Integer> e : menu.cost().entrySet()) {
            if (shown++ >= 9) break;
            ItemStack icon = new ItemStack(e.getKey(), e.getValue());
            gfx.renderItem(icon, ix, 111);
            gfx.renderItemDecorations(font, icon, ix, 111);
            ix += 18;
        }

        // Estado: problema del kit (rojo) o si el Campamento alcanza
        KitEditorMenu.ClassInfo current = menu.current();
        Component status;
        int color;
        Component error = current == null ? null : PlayerKits.validate(current.entity, menu.equipment(), menu.backpack());
        if (error != null) {
            status = error;
            color = 0xFFFF5555;
        } else if (menu.affordable()) {
            status = menu.armamentUsed() > 0
                    ? Component.translatable("gui.warbandcraft.kits.affordable_armament", menu.armamentUsed())
                    : Component.translatable("gui.warbandcraft.kits.affordable");
            color = 0xFF55FF55;
        } else {
            status = Component.translatable("gui.warbandcraft.kits.not_affordable");
            color = 0xFFFFAA00;
        }
        gfx.drawString(font, status.copy().withStyle(ChatFormatting.RESET), 92, 131, color, false);
    }
}
