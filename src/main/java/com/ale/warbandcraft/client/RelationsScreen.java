package com.ale.warbandcraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** /warband relaciones: qué opina cada facción de vos, en una pantalla en vez de en el chat. */
@OnlyIn(Dist.CLIENT)
public class RelationsScreen extends Screen {
    private final List<Component> lines;

    public RelationsScreen(List<Component> lines) {
        super(Component.translatable("cmd.warbandcraft.relations_header"));
        this.lines = lines;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.close"), b -> this.onClose())
                .bounds(this.width / 2 - 50, this.height - 30, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int y = 30;
        g.drawCenteredString(this.font, this.title, this.width / 2, y, 0xFFFFFF);
        y += 20;
        for (Component line : lines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += 14;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
