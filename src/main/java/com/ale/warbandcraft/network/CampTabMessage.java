package com.ale.warbandcraft.network;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: cambiar de pestaña en el Campamento (Cofre · Doctrinas · Kits, H6). */
public class CampTabMessage {
    private final BlockPos camp;
    private final int tab;

    public CampTabMessage(BlockPos camp, int tab) {
        this.camp = camp;
        this.tab = tab;
    }

    public static void encode(CampTabMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeVarInt(msg.tab);
    }

    public static CampTabMessage decode(FriendlyByteBuf buf) {
        return new CampTabMessage(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(CampTabMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, false);
            if (camp == null) return;
            try {
                CampTabs.open(player, camp, msg.tab);
            } catch (RuntimeException e) {
                // enqueueWork se traga los errores: sin esto la pestaña "no abre" sin dejar rastro
                WarbandCraft.LOGGER.error("No se pudo abrir la pestaña {} del Campamento en {}", msg.tab, msg.camp, e);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
