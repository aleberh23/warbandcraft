package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.OpenDoctrineMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** El cuerpo de {@link OpenDoctrineMessage#handle} y {@link OpenBarracksMessage#handle}, aparte porque toca clases de solo-cliente. */
@OnlyIn(Dist.CLIENT)
public final class CampClientHandler {
    private CampClientHandler() {}

    public static void openDoctrines(OpenDoctrineMessage msg) {
        Minecraft.getInstance().setScreen(new DoctrineScreen(msg));
    }

    /** H8: abre la pestaña Barracón, o la refresca sin perder lo que se estaba escribiendo en el nombre. */
    public static void openBarracks(OpenBarracksMessage msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BarracksScreen open && open.camp().equals(msg.camp)) {
            open.refresh(msg);
        } else {
            mc.setScreen(new BarracksScreen(msg));
        }
    }
}
