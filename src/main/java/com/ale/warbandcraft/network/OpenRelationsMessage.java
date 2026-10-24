package com.ale.warbandcraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Servidor → cliente: una línea ya formateada por facción, para mostrar en la pantalla de relaciones.
 *
 * {@code handle} no debe tocar {@code RelationsScreen}/{@code Minecraft} directamente: esta clase la
 * carga un servidor dedicado (al registrar el canal) y esas son clases de solo-cliente. Por eso el
 * cuerpo que las usa vive en {@link com.ale.warbandcraft.client.OpenRelationsClientHandler}.
 */
public class OpenRelationsMessage {
    public final List<Component> lines;

    public OpenRelationsMessage(List<Component> lines) {
        this.lines = lines;
    }

    public static void encode(OpenRelationsMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.lines.size());
        for (Component line : msg.lines) buf.writeComponent(line);
    }

    public static OpenRelationsMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Component> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) lines.add(buf.readComponent());
        return new OpenRelationsMessage(lines);
    }

    public static void handle(OpenRelationsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.OpenRelationsClientHandler.open(msg)));
        ctx.get().setPacketHandled(true);
    }
}
