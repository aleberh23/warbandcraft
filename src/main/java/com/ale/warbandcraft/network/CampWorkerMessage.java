package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.CampWorkers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: botón "Trabajador" de la pestaña Cofre de un Campamento común. */
public class CampWorkerMessage {
    private final BlockPos camp;

    public CampWorkerMessage(BlockPos camp) {
        this.camp = camp;
    }

    public static void encode(CampWorkerMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
    }

    public static CampWorkerMessage decode(FriendlyByteBuf buf) {
        return new CampWorkerMessage(buf.readBlockPos());
    }

    public static void handle(CampWorkerMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            if (camp != null) CampWorkers.open(player, camp);
        });
        ctx.get().setPacketHandled(true);
    }
}
