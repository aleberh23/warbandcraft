package com.ale.warbandcraft.client.panel;

import com.ale.warbandcraft.client.ModKeyMappings;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenPanelMessage;
import com.ale.warbandcraft.network.PanelActionMessage;
import com.ale.warbandcraft.panel.Panel;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Lado cliente de H11: la tecla K pide el Panel de control, y el cuerpo de {@link OpenPanelMessage#handle}. */
@OnlyIn(Dist.CLIENT)
public final class PanelClientHandler {
    /** La última pestaña que se miró: K vuelve a abrir ahí. */
    static int lastTab = Panel.TAB_CAMPS;

    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (ModKeyMappings.PANEL.consumeClick()) {
            ModNetworking.CHANNEL.sendToServer(PanelActionMessage.open(lastTab, null));
        }
    }

    /** Abre el panel, o lo refresca si ya está abierto. */
    public static void open(OpenPanelMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PanelScreen open) {
            open.refresh(msg);
        } else if (mc.screen == null) {
            mc.setScreen(new PanelScreen(msg));
        }
    }
}
