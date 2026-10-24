package com.ale.warbandcraft.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Servidor → cliente: arrancó un viaje de {@code ticks} hacia {@code dest} (abre "Viajando..."), o terminó (ticks 0). */
public class TravelStateMessage {
    public final int ticks;
    public final Component dest;

    public TravelStateMessage(int ticks, Component dest) {
        this.ticks = ticks;
        this.dest = dest;
    }

    public static void encode(TravelStateMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.ticks);
        buf.writeComponent(msg.dest);
    }

    public static TravelStateMessage decode(FriendlyByteBuf buf) {
        return new TravelStateMessage(buf.readVarInt(), buf.readComponent());
    }

    public static void handle(TravelStateMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.route.RouteClientHandler.travelState(msg)));
        ctx.get().setPacketHandled(true);
    }
}
