package com.ale.warbandcraft.network;

import com.ale.warbandcraft.battle.BattleChoices;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor (H23): apretó la tecla de batalla (B). El servidor decide qué ofrecer ({@link BattleChoices#onKey}). */
public class BattleKeyMessage {
    public BattleKeyMessage() {}

    public static void encode(BattleKeyMessage msg, FriendlyByteBuf buf) {}

    public static BattleKeyMessage decode(FriendlyByteBuf buf) {
        return new BattleKeyMessage();
    }

    public static void handle(BattleKeyMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null) BattleChoices.onKey(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
