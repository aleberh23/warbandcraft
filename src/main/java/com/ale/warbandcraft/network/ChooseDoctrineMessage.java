package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.event.MyFactionEvents;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.PlayerFactionData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Cliente → servidor: adoptar una doctrina desde la pestaña Doctrinas del Campamento.
 * La primera es gratis; después cuesta esmeraldas del almacén de ese Campamento y hay que esperar un día.
 */
public class ChooseDoctrineMessage {
    private final BlockPos camp;
    private final int doctrine;

    public ChooseDoctrineMessage(BlockPos camp, int doctrine) {
        this.camp = camp;
        this.doctrine = doctrine;
    }

    public static void encode(ChooseDoctrineMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeVarInt(msg.doctrine);
    }

    public static ChooseDoctrineMessage decode(FriendlyByteBuf buf) {
        return new ChooseDoctrineMessage(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(ChooseDoctrineMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            Doctrine d = Doctrine.byOrdinal(msg.doctrine);
            if (camp == null || d == null) return;
            Component result = choose(player, camp, d);
            player.displayClientMessage(result, true);
            CampTabs.open(player, camp, CampTabs.DOCTRINES); // refrescar la pantalla
        });
        ctx.get().setPacketHandled(true);
    }

    /** Lógica pura (también la usan los GameTests). Devuelve el mensaje para el jugador. */
    public static Component choose(ServerPlayer player, CampBlockEntity camp, Doctrine d) {
        PlayerFactionData data = PlayerFactionData.get(player.getServer());
        if (data.doctrine(player.getUUID()) == d) return Component.translatable("msg.warbandcraft.doctrine.same");
        long now = player.level().getGameTime();
        if (!data.nextChangeFree(player.getUUID())) {
            long wait = data.waitLeft(player.getUUID(), now);
            if (wait > 0) {
                return Component.translatable("msg.warbandcraft.doctrine.wait", wait / 1200 + 1).withStyle(ChatFormatting.RED);
            }
            if (camp.count(Items.EMERALD) < Doctrine.CHANGE_COST_EMERALDS) {
                return Component.translatable("msg.warbandcraft.doctrine.no_emeralds", Doctrine.CHANGE_COST_EMERALDS)
                        .withStyle(ChatFormatting.RED);
            }
            camp.take(Items.EMERALD, Doctrine.CHANGE_COST_EMERALDS);
        }
        data.setDoctrine(player.getUUID(), d, now);
        MyFactionEvents.reapplyDoctrine(player.getServer(), player.getUUID());
        return Component.translatable("msg.warbandcraft.doctrine.adopted", d.displayName()).withStyle(ChatFormatting.GREEN);
    }
}
