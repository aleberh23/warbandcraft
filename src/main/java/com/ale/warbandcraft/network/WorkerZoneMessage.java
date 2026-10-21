package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.WorkerZone;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: botón "Zona de trabajadores" de la pestaña Cofre (H10). */
public class WorkerZoneMessage {
    private final BlockPos camp;

    public WorkerZoneMessage(BlockPos camp) {
        this.camp = camp;
    }

    public static void encode(WorkerZoneMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
    }

    public static WorkerZoneMessage decode(FriendlyByteBuf buf) {
        return new WorkerZoneMessage(buf.readBlockPos());
    }

    public static void handle(WorkerZoneMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            if (camp == null) return;
            WorkerZone.Result r = WorkerZone.create(player.serverLevel(), camp, player.getUUID(), player.getName().getString());
            player.sendSystemMessage(switch (r) {
                case CREATED -> Component.translatable("msg.warbandcraft.worker_zone.created").withStyle(ChatFormatting.GREEN);
                case OVERLAPPING -> Component.translatable("msg.warbandcraft.worker_zone.overlapping").withStyle(ChatFormatting.YELLOW);
                case FAILED -> Component.translatable("msg.warbandcraft.worker_zone.failed").withStyle(ChatFormatting.RED);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
