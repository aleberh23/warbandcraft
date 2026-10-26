package com.ale.warbandcraft.client;

import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.RaidChoiceMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Se abre al ganar un ataque: hay que elegir Saquear, Conquistar o Quemar (solo aldeas). No se puede cerrar sin elegir
 * (Escape no hace nada) y, como con cualquier pantalla abierta, el jugador no puede moverse ni pegar
 * mientras está en pantalla.
 */
@OnlyIn(Dist.CLIENT)
public class RaidChoiceScreen extends Screen {
    private final Component who;
    private final boolean canBurn;

    public RaidChoiceScreen(Component who, boolean canBurn) {
        super(Component.translatable("gui.warbandcraft.raid_choice.title"));
        this.who = who;
        this.canBurn = canBurn;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int n = canBurn ? 3 : 2;
        int x = cx - (n * 100 + (n - 1) * 10) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.raid_choice.sack"), b -> choose(RaidChoiceMessage.SACK))
                .bounds(x, cy + 10, 100, 24)
                .tooltip(Tooltip.create(Component.translatable("msg.warbandcraft.hover_sack")))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.raid_choice.conquer"), b -> choose(RaidChoiceMessage.CONQUER))
                .bounds(x + 110, cy + 10, 100, 24)
                .tooltip(Tooltip.create(Component.translatable("msg.warbandcraft.hover_conquer")))
                .build());
        if (canBurn) {
            this.addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.raid_choice.burn"), b -> choose(RaidChoiceMessage.BURN))
                    .bounds(x + 220, cy + 10, 100, 24)
                    .tooltip(Tooltip.create(Component.translatable("msg.warbandcraft.hover_burn")))
                    .build());
        }
    }

    private void choose(int choice) {
        ModNetworking.CHANNEL.sendToServer(new RaidChoiceMessage(choice));
        this.minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
        g.drawCenteredString(this.font, this.who, this.width / 2, this.height / 2 - 20, 0xFFD700);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
