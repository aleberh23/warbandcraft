package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.village.Founding;
import com.ale.warbandcraft.village.VillageCamps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: botón "Fundar aldea" de la pestaña Cofre del Campamento. */
public class FoundVillageMessage {
    private final BlockPos camp;

    public FoundVillageMessage(BlockPos camp) {
        this.camp = camp;
    }

    public static void encode(FoundVillageMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
    }

    public static FoundVillageMessage decode(FriendlyByteBuf buf) {
        return new FoundVillageMessage(buf.readBlockPos());
    }

    public static void handle(FoundVillageMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            if (camp == null) return;
            player.sendSystemMessage(Founding.found(player, msg.camp));
            // Ya es una aldea: se vuelve a abrir el Cofre sin el botón
            if (VillageCamps.villageOf(player.serverLevel(), msg.camp) != null) CampTabs.open(player, camp, CampTabs.CHEST);
        });
        ctx.get().setPacketHandled(true);
    }
}
