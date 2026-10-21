package com.ale.warbandcraft.client;

import com.ale.warbandcraft.network.BattleKeyMessage;
import com.ale.warbandcraft.network.ChoiceAnswerMessage;
import com.ale.warbandcraft.network.ChoiceMessage;
import com.ale.warbandcraft.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * H23: la pantalla de elegir (meterse en una batalla entre facciones, asediar una aldea). La manda el servidor con
 * {@link ChoiceMessage}; cada botón contesta con su número, Esc = -1. No pausa el juego. También escucha la tecla B.
 */
@OnlyIn(Dist.CLIENT)
public class ChoiceScreen extends Screen {
    private static final int BUTTON_W = 200, BUTTON_H = 20, LINE = 11;
    private final ChoiceMessage msg;
    private boolean answered;

    public ChoiceScreen(ChoiceMessage msg) {
        super(msg.title);
        this.msg = msg;
    }

    /** Abre la pantalla. Si no la pidió el jugador y tiene otra abierta (inventario, panel), no lo interrumpe. */
    public static void open(ChoiceMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!msg.asked && mc.screen != null && !(mc.screen instanceof ChoiceScreen)) return;
        mc.setScreen(new ChoiceScreen(msg));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = top();
        int y = top + 22 + msg.lines.size() * LINE + 8;
        for (int i = 0; i < msg.options.size(); i++) {
            int option = i;
            this.addRenderableWidget(Button.builder(msg.options.get(i), b -> answer(option))
                    .bounds(cx - BUTTON_W / 2, y + i * (BUTTON_H + 4), BUTTON_W, BUTTON_H).build());
        }
    }

    private int top() {
        int h = 22 + msg.lines.size() * LINE + 8 + msg.options.size() * (BUTTON_H + 4);
        return Math.max(10, this.height / 2 - h / 2);
    }

    private void answer(int option) {
        if (answered) return;
        answered = true;
        ModNetworking.CHANNEL.sendToServer(new ChoiceAnswerMessage(msg.kind, msg.id, option));
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        if (!answered) {
            answered = true;
            ModNetworking.CHANNEL.sendToServer(new ChoiceAnswerMessage(msg.kind, msg.id, -1));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int top = top();
        g.drawCenteredString(this.font, this.title, this.width / 2, top, 0xFFD700);
        for (int i = 0; i < msg.lines.size(); i++) {
            g.drawCenteredString(this.font, msg.lines.get(i), this.width / 2, top + 22 + i * LINE, 0xFFFFFF);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** La tecla de batalla (B): le pregunta al servidor qué se puede hacer acá (meterse, asediar). */
    public static final class KeyHandler {
        @SubscribeEvent
        public void onKey(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            if (ModKeyMappings.BATTLE.consumeClick()) ModNetworking.CHANNEL.sendToServer(new BattleKeyMessage());
        }
    }

    /** Lo que dice la tecla de batalla (para el cartelito). */
    public static Component keyName() {
        return ModKeyMappings.BATTLE.getTranslatedKeyMessage();
    }
}
