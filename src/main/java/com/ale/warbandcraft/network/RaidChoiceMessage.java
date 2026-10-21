package com.ale.warbandcraft.network;

import com.ale.warbandcraft.raid.RaidManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: el jugador eligió saquear o conquistar la aldea que ganó. */
public class RaidChoiceMessage {
    private final boolean sack;

    public RaidChoiceMessage(boolean sack) {
        this.sack = sack;
    }

    public static void encode(RaidChoiceMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.sack);
    }

    public static RaidChoiceMessage decode(FriendlyByteBuf buf) {
        return new RaidChoiceMessage(buf.readBoolean());
    }

    public static void handle(RaidChoiceMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            RaidManager.Outcome out = msg.sack
                    ? RaidManager.sack(player.serverLevel(), player.getUUID(), player.blockPosition())
                    : RaidManager.conquer(player.serverLevel(), player.getUUID(), player.blockPosition());
            player.sendSystemMessage(out.message());
        });
        ctx.get().setPacketHandled(true);
    }
}
