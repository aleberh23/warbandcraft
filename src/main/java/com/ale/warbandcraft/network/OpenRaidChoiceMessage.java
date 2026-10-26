package com.ale.warbandcraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Servidor → cliente: ganaste el ataque, abrí la pantalla para elegir saquear, conquistar o quemar.
 *
 * {@code handle} no debe tocar {@code RaidChoiceScreen}/{@code Minecraft} directamente: esta clase la
 * carga un servidor dedicado (al registrar el canal) y esas son clases de solo-cliente. Por eso el
 * cuerpo que las usa vive en {@link com.ale.warbandcraft.client.OpenRaidChoiceClientHandler}.
 */
public class OpenRaidChoiceMessage {
    public final Component who;
    /** Se puede quemar (aldea, no castillo). */
    public final boolean canBurn;

    public OpenRaidChoiceMessage(Component who, boolean canBurn) {
        this.who = who;
        this.canBurn = canBurn;
    }

    public static void encode(OpenRaidChoiceMessage msg, FriendlyByteBuf buf) {
        buf.writeComponent(msg.who);
        buf.writeBoolean(msg.canBurn);
    }

    public static OpenRaidChoiceMessage decode(FriendlyByteBuf buf) {
        return new OpenRaidChoiceMessage(buf.readComponent(), buf.readBoolean());
    }

    public static void handle(OpenRaidChoiceMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.OpenRaidChoiceClientHandler.open(msg)));
        ctx.get().setPacketHandled(true);
    }
}
