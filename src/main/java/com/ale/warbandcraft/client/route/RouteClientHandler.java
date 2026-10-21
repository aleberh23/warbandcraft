package com.ale.warbandcraft.client.route;

import com.ale.warbandcraft.client.ModKeyMappings;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import com.ale.warbandcraft.network.RouteActionMessage;
import com.ale.warbandcraft.network.TravelStateMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Lado cliente de H13: la tecla J abre la pantalla de Rutas, y el cuerpo de {@link OpenRoutesMessage#handle} y
 * {@link TravelStateMessage#handle} (aparte porque tocan clases de solo-cliente).
 */
@OnlyIn(Dist.CLIENT)
public final class RouteClientHandler {
    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (ModKeyMappings.ROUTES.consumeClick()) {
            ModNetworking.CHANNEL.sendToServer(RouteActionMessage.simple(RouteActionMessage.Action.OPEN, null));
        }
    }

    /** Abre la pantalla de Rutas, o la refresca si ya está abierta. */
    public static void openRoutes(OpenRoutesMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TravelScreen) return;   // viajando: no se tapa la barra
        if (mc.screen instanceof RoutesScreen open) {
            open.refresh(msg);
        } else {
            mc.setScreen(new RoutesScreen(msg));
        }
    }

    public static void travelState(TravelStateMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (msg.ticks > 0) {
            mc.setScreen(new TravelScreen(msg.dest, msg.ticks));
        } else if (mc.screen instanceof TravelScreen travel) {
            travel.finish();
        }
    }
}
