package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.camp.CampMenu;
import com.ale.warbandcraft.camp.CampStats;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.network.FoundVillageMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.village.Founding;
import com.ale.warbandcraft.network.ScrapMessage;
import com.ale.warbandcraft.network.WorkerZoneMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Pestaña Cofre: el cofre de 54 de siempre, con las pestañas a la izquierda (solo para el dueño). H10: arriba,
 * los totales del almacén; a la derecha, el botón "Zona de trabajadores" y el modo Desguace (qué piezas convierten
 * los herreros en armamento).
 */
@OnlyIn(Dist.CLIENT)
public class CampScreen extends ContainerScreen {
    /** Desguace: prendido = click en una pieza la marca/desmarca en vez de moverla. */
    private boolean scrapMode;
    private Button scrapButton;

    public CampScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        if (menu instanceof CampMenu camp && !camp.isOwner() && camp.isAlly()) {
            // Compañero de facción del dueño: solo el Barracón
            for (Button b : CampTabButtons.create(camp.campPos(), CampTabs.CHEST, leftPos, topPos, false)) addRenderableWidget(b);
        }
        if (menu instanceof CampMenu camp && camp.isOwner()) {
            for (Button b : CampTabButtons.create(camp.campPos(), CampTabs.CHEST, leftPos, topPos)) addRenderableWidget(b);
            Button zone = Button.builder(Component.translatable("gui.warbandcraft.camp.worker_zone"),
                            x -> ModNetworking.CHANNEL.sendToServer(new WorkerZoneMessage(camp.campPos())))
                    .bounds(leftPos + imageWidth + 4, topPos + 4, 96, 20).build();
            zone.setTooltip(Tooltip.create(Component.translatable("gui.warbandcraft.camp.worker_zone.tip")));
            addRenderableWidget(zone);
            if (!camp.isVillage()) {
                Button found = Button.builder(Component.translatable("gui.warbandcraft.panel.found", Founding.COST),
                                x -> ModNetworking.CHANNEL.sendToServer(new FoundVillageMessage(camp.campPos())))
                        .bounds(leftPos + imageWidth + 4, topPos + 28, 96, 20).build();
                found.setTooltip(Tooltip.create(Component.translatable("gui.warbandcraft.panel.found_help", Founding.COST, Founding.SETTLERS)));
                addRenderableWidget(found);
            }
            scrapButton = Button.builder(scrapLabel(), x -> {
                        scrapMode = !scrapMode;
                        x.setMessage(scrapLabel());
                    })
                    .bounds(leftPos + imageWidth + 4, topPos + (camp.isVillage() ? 28 : 52), 96, 20).build();
            scrapButton.setTooltip(Tooltip.create(Component.translatable("gui.warbandcraft.camp.scrap.tip")));
            addRenderableWidget(scrapButton);
            if (!camp.isVillage()) {   // en una aldea los trabajadores se manejan desde Gestión
                Button worker = Button.builder(Component.translatable("gui.warbandcraft.camp.worker"),
                                x -> ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.CampWorkerMessage(camp.campPos())))
                        .bounds(leftPos + imageWidth + 4, topPos + 76, 96, 20).build();
                worker.setTooltip(Tooltip.create(Component.translatable("gui.warbandcraft.camp.worker.tip")));
                addRenderableWidget(worker);
            }
        }
    }

    private Component scrapLabel() {
        return Component.translatable(scrapMode ? "gui.warbandcraft.camp.scrap.on" : "gui.warbandcraft.camp.scrap.off");
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int button, ClickType type) {
        if (scrapMode && slot != null && menu instanceof CampMenu camp) {
            // En modo Desguace no se mueve nada: click en una pieza = marcarla / desmarcarla
            if (slot.hasItem()) {
                ModNetworking.CHANNEL.sendToServer(new ScrapMessage(camp.campPos(),
                        ForgeRegistries.ITEMS.getKey(slot.getItem().getItem())));
            }
            return;
        }
        super.slotClicked(slot, slotId, button, type);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        // Lo marcado para desguazar: rojo encima del ítem (fuerte con el modo prendido)
        if (menu instanceof CampMenu camp && !camp.scrap().isEmpty()) {
            int color = scrapMode ? 0x90FF2020 : 0x50FF2020;
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 300);
            for (Slot slot : menu.slots) {
                if (slot.hasItem() && camp.scrap().contains(ForgeRegistries.ITEMS.getKey(slot.getItem().getItem()))) {
                    gfx.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
                }
            }
            gfx.pose().popPose();
        }
        super.renderLabels(gfx, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        if (!(menu instanceof CampMenu camp)) return;
        // H10: "Esmeraldas 34 · Comida 120 (6 días) · Armamento 20 · Herreros 2 · Trabajadores 3"
        int days = camp.stat(CampStats.FOOD_DAYS);
        Component food = days < 0
                ? Component.translatable("gui.warbandcraft.camp.stats.food", camp.stat(CampStats.FOOD))
                : Component.translatable("gui.warbandcraft.camp.stats.food_days", camp.stat(CampStats.FOOD), days);
        Component line = Component.translatable("gui.warbandcraft.camp.stats",
                camp.stat(CampStats.EMERALDS), food, camp.stat(CampStats.ARMAMENT),
                camp.stat(CampStats.SMITHS), camp.stat(CampStats.WORKERS));
        int w = font.width(line);
        int x = Math.max(2, leftPos + (imageWidth - w) / 2);
        gfx.drawString(font, line, x, topPos - 11, 0xFFFFE08A, true);
    }
}
