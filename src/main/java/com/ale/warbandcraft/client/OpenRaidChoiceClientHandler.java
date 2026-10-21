package com.ale.warbandcraft.client;

import com.ale.warbandcraft.network.OpenRaidChoiceMessage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * El cuerpo real de {@link OpenRaidChoiceMessage#handle}, en una clase aparte: un servidor dedicado
 * carga ese mensaje al registrar el canal, y {@code Minecraft}/{@code RaidChoiceScreen} son de solo-cliente.
 */
@OnlyIn(Dist.CLIENT)
public final class OpenRaidChoiceClientHandler {
    private OpenRaidChoiceClientHandler() {}

    public static void open(OpenRaidChoiceMessage msg) {
        Minecraft.getInstance().setScreen(new RaidChoiceScreen(msg.who));
    }
}
