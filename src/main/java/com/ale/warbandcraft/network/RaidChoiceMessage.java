package com.ale.warbandcraft.network;

import com.ale.warbandcraft.raid.RaidManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: el jugador eligió saquear, conquistar o quemar la aldea que ganó. */
public class RaidChoiceMessage {
    public static final int SACK = 0, CONQUER = 1, BURN = 2;
    private final int choice;

    public RaidChoiceMessage(int choice) {
        this.choice = choice;
    }

    public static void encode(RaidChoiceMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.choice);
    }

    public static RaidChoiceMessage decode(FriendlyByteBuf buf) {
        return new RaidChoiceMessage(buf.readVarInt());
    }

    public static void handle(RaidChoiceMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            RaidManager.Outcome out = switch (msg.choice) {
                case SACK -> RaidManager.sack(player.serverLevel(), player.getUUID(), player.blockPosition());
                case BURN -> RaidManager.burn(player.serverLevel(), player.getUUID(), player.blockPosition());
                default -> RaidManager.conquer(player.serverLevel(), player.getUUID(), player.blockPosition());
            };
            player.sendSystemMessage(out.message());
        });
        ctx.get().setPacketHandled(true);
    }
}
