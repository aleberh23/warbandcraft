package com.ale.warbandcraft.network;

import com.ale.warbandcraft.army.ArmyPanel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Servidor → cliente: abre (o refresca) la pantalla Ejércitos (H20). */
public class OpenArmiesMessage {
    public final ArmyPanel.Data data;

    public OpenArmiesMessage(ArmyPanel.Data data) {
        this.data = data;
    }

    public static void send(ServerPlayer player) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenArmiesMessage(ArmyPanel.of(player)));
    }

    public static void encode(OpenArmiesMessage msg, FriendlyByteBuf buf) {
        ArmyPanel.write(buf, msg.data);
    }

    public static OpenArmiesMessage decode(FriendlyByteBuf buf) {
        return new OpenArmiesMessage(ArmyPanel.read(buf));
    }

    public static void handle(OpenArmiesMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.army.ArmyScreen.open(msg.data)));
        ctx.get().setPacketHandled(true);
    }
}
