package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.ShipmentMenu;
import com.ale.warbandcraft.route.Routes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cliente → servidor: botón "Mandar" de la pestaña Envíos (H13). Los soldados van por fila del barracón; los
 * ítems son los de la caja de carga del menú abierto.
 */
public class SendShipmentMessage {
    private final BlockPos camp;
    private final BlockPos dest;
    private final Map<String, Integer> rows;

    public SendShipmentMessage(BlockPos camp, BlockPos dest, Map<String, Integer> rows) {
        this.camp = camp;
        this.dest = dest;
        this.rows = rows;
    }

    public static void encode(SendShipmentMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeBlockPos(msg.dest);
        buf.writeMap(msg.rows, (b, k) -> b.writeUtf(k), FriendlyByteBuf::writeVarInt);
    }

    public static SendShipmentMessage decode(FriendlyByteBuf buf) {
        BlockPos camp = buf.readBlockPos(), dest = buf.readBlockPos();
        Map<String, Integer> rows = buf.readMap(LinkedHashMap::new, b -> b.readUtf(), FriendlyByteBuf::readVarInt);
        return new SendShipmentMessage(camp, dest, rows);
    }

    public static void handle(SendShipmentMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            if (camp == null || !(player.containerMenu instanceof ShipmentMenu menu) || !menu.info().camp().equals(msg.camp)) return;
            Component error = Routes.send(player.serverLevel(), player.getUUID(), msg.camp, msg.dest, msg.rows, menu.cargoItems());
            if (error != null) {
                player.displayClientMessage(error.copy().withStyle(ChatFormatting.RED), true);
                return;
            }
            menu.clearCargo();
            Routes.Node to = Routes.node(player.serverLevel(), player.getUUID(), msg.dest);
            player.displayClientMessage(Component.translatable("msg.warbandcraft.shipments.sent",
                    to != null ? to.name() : Component.literal(msg.dest.toShortString())).withStyle(ChatFormatting.GREEN), true);
            CampTabs.open(player, camp, CampTabs.SHIPMENTS);   // refresca la pestaña
        });
        ctx.get().setPacketHandled(true);
    }
}
