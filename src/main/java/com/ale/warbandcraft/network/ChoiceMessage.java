package com.ale.warbandcraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Servidor → cliente (H23): una pantalla chica con un título, unas líneas y botones ("Ayudar a ...", "Asediar"...).
 * La respuesta vuelve con {@link ChoiceAnswerMessage} ({@code kind} + {@code id} + el botón; -1 = la cerró).
 * El cuerpo de solo-cliente vive en {@code client.ChoiceScreen}.
 */
public class ChoiceMessage {
    public final String kind, id;
    public final Component title;
    public final List<Component> lines, options;
    /** true = se abre aunque haya otra pantalla abierta (la pidió el jugador con la tecla). */
    public final boolean asked;

    public ChoiceMessage(String kind, String id, Component title, List<Component> lines, List<Component> options, boolean asked) {
        this.kind = kind;
        this.id = id;
        this.title = title;
        this.lines = lines;
        this.options = options;
        this.asked = asked;
    }

    public static void encode(ChoiceMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.kind);
        buf.writeUtf(msg.id);
        buf.writeComponent(msg.title);
        buf.writeCollection(msg.lines, FriendlyByteBuf::writeComponent);
        buf.writeCollection(msg.options, FriendlyByteBuf::writeComponent);
        buf.writeBoolean(msg.asked);
    }

    public static ChoiceMessage decode(FriendlyByteBuf buf) {
        return new ChoiceMessage(buf.readUtf(), buf.readUtf(), buf.readComponent(), buf.readList(FriendlyByteBuf::readComponent),
                buf.readList(FriendlyByteBuf::readComponent), buf.readBoolean());
    }

    public static void handle(ChoiceMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.ChoiceScreen.open(msg)));
        ctx.get().setPacketHandled(true);
    }
}
