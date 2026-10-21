package com.ale.warbandcraft.network;

import com.ale.warbandcraft.battle.BattleChoices;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor (H23): qué botón tocó en la pantalla de {@link ChoiceMessage} (-1 = la cerró). */
public class ChoiceAnswerMessage {
    private final String kind, id;
    private final int option;

    public ChoiceAnswerMessage(String kind, String id, int option) {
        this.kind = kind;
        this.id = id;
        this.option = option;
    }

    public static void encode(ChoiceAnswerMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.kind);
        buf.writeUtf(msg.id);
        buf.writeVarInt(msg.option + 1);
    }

    public static ChoiceAnswerMessage decode(FriendlyByteBuf buf) {
        return new ChoiceAnswerMessage(buf.readUtf(), buf.readUtf(), buf.readVarInt() - 1);
    }

    public static void handle(ChoiceAnswerMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null) BattleChoices.answer(player, msg.kind, msg.id, msg.option);
        });
        ctx.get().setPacketHandled(true);
    }
}
