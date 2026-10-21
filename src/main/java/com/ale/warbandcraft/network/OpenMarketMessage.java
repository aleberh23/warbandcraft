package com.ale.warbandcraft.network;

import com.ale.warbandcraft.market.MarketView;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Servidor → cliente: abre (o refresca) la pantalla Mercado (H24), con el resultado de la última orden. */
public class OpenMarketMessage {
    public final MarketView view;
    public final Component feedback;

    public OpenMarketMessage(MarketView view, Component feedback) {
        this.view = view;
        this.feedback = feedback;
    }

    public static void send(ServerPlayer player, MarketView view, Component feedback) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenMarketMessage(view, feedback));
    }

    public static void encode(OpenMarketMessage msg, FriendlyByteBuf buf) {
        MarketView.write(buf, msg.view);
        buf.writeComponent(msg.feedback);
    }

    public static OpenMarketMessage decode(FriendlyByteBuf buf) {
        return new OpenMarketMessage(MarketView.read(buf), buf.readComponent());
    }

    public static void handle(OpenMarketMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.market.MarketScreen.open(msg.view, msg.feedback)));
        ctx.get().setPacketHandled(true);
    }
}
