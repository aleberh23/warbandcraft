package com.ale.warbandcraft.client.route;

import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.RouteActionMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * "Viajando a X..." con una barra (H13). El servidor manda cuándo arranca y cuándo termina; la barra se llena
 * con el reloj local. Esc cancela el viaje.
 */
@OnlyIn(Dist.CLIENT)
public class TravelScreen extends Screen {
    private static final int BAR_W = 220, BAR_H = 10;

    private final Component dest;
    private final int duration;
    private int elapsed;
    private boolean finished;

    public TravelScreen(Component dest, int duration) {
        super(Component.translatable("gui.warbandcraft.travel.title", dest));
        this.dest = dest;
        this.duration = Math.max(1, duration);
    }

    /** El servidor avisó que terminó (llegaste o se cortó): se cierra sin mandar "cancelar". */
    public void finish() {
        finished = true;
        onClose();
    }

    @Override
    public void tick() {
        elapsed++;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (!finished) ModNetworking.CHANNEL.sendToServer(RouteActionMessage.simple(RouteActionMessage.Action.CANCEL, null));
        finished = true;
        super.onClose();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        int cx = width / 2, cy = height / 2;
        gfx.drawCenteredString(font, Component.translatable("gui.warbandcraft.travel.title", dest), cx, cy - 24, 0xFFD4A017);

        float progress = Math.min(1.0F, (elapsed + partialTick) / duration);
        int x = cx - BAR_W / 2, y = cy - BAR_H / 2;
        gfx.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0xFF6B5A3A);
        gfx.fill(x, y, x + BAR_W, y + BAR_H, 0xFF201810);
        gfx.fill(x, y, x + (int) (BAR_W * progress), y + BAR_H, 0xFFB08A3A);

        int left = Math.max(0, (duration - elapsed) / 20);
        gfx.drawCenteredString(font, Component.translatable("gui.warbandcraft.travel.left", RoutesScreen.time(left * 20)),
                cx, cy + 12, 0xFFBBBBBB);
        gfx.drawCenteredString(font, Component.translatable("gui.warbandcraft.travel.cancel"), cx, cy + 26, 0xFF888888);
        super.render(gfx, mouseX, mouseY, partialTick);
    }
}
