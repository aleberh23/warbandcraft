package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Cliente → servidor: ponerle nombre al Campamento (H8, desde la pestaña Barracón). Vacío = no cambia. */
public class RenameCampMessage {
    private final BlockPos camp;
    private final String name;

    public RenameCampMessage(BlockPos camp, String name) {
        this.camp = camp;
        this.name = name;
    }

    public static void encode(RenameCampMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeUtf(msg.name, CampBlockEntity.MAX_NAME_LENGTH * 4);
    }

    public static RenameCampMessage decode(FriendlyByteBuf buf) {
        return new RenameCampMessage(buf.readBlockPos(), buf.readUtf(CampBlockEntity.MAX_NAME_LENGTH * 4));
    }

    public static void handle(RenameCampMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.accessBarracks(player, msg.camp);
            if (camp == null || !camp.rename(msg.name)) return;
            player.displayClientMessage(Component.translatable("msg.warbandcraft.camp.renamed", camp.nameString())
                    .withStyle(ChatFormatting.GREEN), true);
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenBarracksMessage.of(player, camp));
        });
        ctx.get().setPacketHandled(true);
    }
}
