package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.Prisoners;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Cliente → servidor: botones de la pestaña Barracón (H8). Después se refresca la pestaña. */
public class BarracksActionMessage {
    public enum Action { STORE_NEAR, DEPLOY, DEPLOY_GUARDS, RECRUIT_PRISONER, RANSOM_PRISONER, /** H19 */ TRAINING }

    private final BlockPos camp;
    private final Action action;
    private final String row;
    private final int amount;

    public BarracksActionMessage(BlockPos camp, Action action, String row, int amount) {
        this.camp = camp;
        this.action = action;
        this.row = row;
        this.amount = amount;
    }

    public static void encode(BarracksActionMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeEnum(msg.action);
        buf.writeUtf(msg.row);
        buf.writeVarInt(msg.amount);
    }

    public static BarracksActionMessage decode(FriendlyByteBuf buf) {
        return new BarracksActionMessage(buf.readBlockPos(), buf.readEnum(Action.class), buf.readUtf(), buf.readVarInt());
    }

    public static void handle(BarracksActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            // Sacar y guardar: también un compañero de facción del dueño. Lo demás, solo el dueño.
            CampBlockEntity camp = CampTabs.accessBarracks(player, msg.camp);
            if (camp == null) return;
            boolean shared = msg.action == Action.STORE_NEAR || msg.action == Action.DEPLOY || msg.action == Action.DEPLOY_GUARDS;
            if (!shared && !camp.canBeUsedBy(player.getUUID())) return;
            Component result = switch (msg.action) {
                case STORE_NEAR -> {
                    int n = Barracks.storeNear(player.serverLevel(), msg.camp, player.getUUID());
                    yield Component.translatable("msg.warbandcraft.barracks.stored", n)
                            .withStyle(n > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
                }
                case DEPLOY -> {
                    int n = Barracks.deploy(player.serverLevel(), msg.camp, msg.row, Math.max(1, msg.amount), player.getUUID());
                    yield Component.translatable("msg.warbandcraft.barracks.deployed", n).withStyle(ChatFormatting.GREEN);
                }
                case DEPLOY_GUARDS -> {
                    int n = Barracks.deployGuards(player.serverLevel(), msg.camp, player.getUUID());
                    yield Component.translatable("msg.warbandcraft.barracks.guards_out", n).withStyle(ChatFormatting.GREEN);
                }
                case RECRUIT_PRISONER -> switch (Prisoners.recruit(player.serverLevel(), msg.camp, msg.row, player.getUUID())) {
                    case OK -> Component.translatable("msg.warbandcraft.prisoners.recruited").withStyle(ChatFormatting.GREEN);
                    case FULL -> Component.translatable("msg.warbandcraft.prisoners.full").withStyle(ChatFormatting.RED);
                    case NONE -> Component.translatable("msg.warbandcraft.prisoners.none").withStyle(ChatFormatting.YELLOW);
                };
                case TRAINING -> com.ale.warbandcraft.camp.Training.upgradeCamp(player.serverLevel(), camp);
                case RANSOM_PRISONER -> {
                    int emeralds = Prisoners.ransom(player.serverLevel(), msg.camp, msg.row);
                    yield emeralds > 0
                            ? Component.translatable("msg.warbandcraft.prisoners.ransomed", emeralds).withStyle(ChatFormatting.GREEN)
                            : Component.translatable("msg.warbandcraft.prisoners.none").withStyle(ChatFormatting.YELLOW);
                }
            };
            player.displayClientMessage(result, true);
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenBarracksMessage.of(player, camp));
        });
        ctx.get().setPacketHandled(true);
    }
}
