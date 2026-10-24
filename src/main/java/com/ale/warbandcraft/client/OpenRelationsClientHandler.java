package com.ale.warbandcraft.client;

import com.ale.warbandcraft.network.OpenRelationsMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * El cuerpo real de {@link OpenRelationsMessage#handle}, en una clase aparte: un servidor dedicado
 * carga ese mensaje al registrar el canal, y {@code Minecraft}/{@code RelationsScreen} son de solo-cliente.
 */
@OnlyIn(Dist.CLIENT)
public final class OpenRelationsClientHandler {
    private OpenRelationsClientHandler() {}

    public static void open(OpenRelationsMessage msg) {
        Minecraft.getInstance().setScreen(new RelationsScreen(msg.lines));
    }
}
