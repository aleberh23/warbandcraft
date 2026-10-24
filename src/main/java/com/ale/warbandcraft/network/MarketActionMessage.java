package com.ale.warbandcraft.network;

import com.ale.warbandcraft.market.Exchange;
import com.ale.warbandcraft.market.Market;
import com.ale.warbandcraft.market.Schedules;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: botones de la pantalla Mercado (H24). Siempre se responde con la pantalla actualizada.
 * {@code text} = id del ítem (o el pedido de la Lonja), {@code a}/{@code b} = números de cada orden.
 */
public class MarketActionMessage {
    public enum Action { OPEN, MIN, PRICE, SELL, NEW_SCHEDULE, DEST, EVERY, LINE, PAUSE, DELETE, SEND_NOW, ORDER }

    private final Action action;
    private final BlockPos center;
    @Nullable
    private final UUID id;
    @Nullable
    private final BlockPos pos;
    private final String text;
    private final int a, b;

    public MarketActionMessage(Action action, BlockPos center, @Nullable UUID id, @Nullable BlockPos pos, String text, int a, int b) {
        this.action = action;
        this.center = center;
        this.id = id;
        this.pos = pos;
        this.text = text;
        this.a = a;
        this.b = b;
    }

    public static void encode(MarketActionMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeBlockPos(msg.center);
        buf.writeNullable(msg.id, FriendlyByteBuf::writeUUID);
        buf.writeNullable(msg.pos, FriendlyByteBuf::writeBlockPos);
        buf.writeUtf(msg.text, 2048);
        buf.writeInt(msg.a);
        buf.writeInt(msg.b);
    }

    public static MarketActionMessage decode(FriendlyByteBuf buf) {
        return new MarketActionMessage(buf.readEnum(Action.class), buf.readBlockPos(), buf.readNullable(FriendlyByteBuf::readUUID),
                buf.readNullable(FriendlyByteBuf::readBlockPos), buf.readUtf(2048), buf.readInt(), buf.readInt());
    }

    public static void handle(MarketActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null) apply(player, msg);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, MarketActionMessage msg) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(msg.center);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.stock.not_yours").withStyle(ChatFormatting.RED));
            return;
        }
        if (Market.level(level, v) == 0) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.market.no_market").withStyle(ChatFormatting.RED));
            return;
        }
        Component feedback = Component.empty();
        switch (msg.action) {
            case OPEN -> {
            }
            case MIN -> feedback = Market.setRule(level, v, msg.text, msg.a, -1, -1);
            case PRICE -> feedback = Market.setRule(level, v, msg.text, -1, msg.a, -1);
            case SELL -> feedback = Market.setRule(level, v, msg.text, -1, -1, msg.a);
            case NEW_SCHEDULE -> feedback = Schedules.create(level, v);
            case DEST -> {
                if (msg.id != null && msg.pos != null) feedback = Schedules.setDest(level, v, msg.id, msg.pos);
            }
            case EVERY -> {
                if (msg.id != null) feedback = Schedules.setEvery(level, v, msg.id, msg.a);
            }
            case LINE -> {
                if (msg.id != null) feedback = Schedules.setLine(level, v, msg.id, msg.text, msg.a, msg.b == 1);
            }
            case PAUSE -> {
                if (msg.id != null) feedback = Schedules.togglePause(level, v, msg.id);
            }
            case DELETE -> {
                if (msg.id != null) feedback = Schedules.delete(level, v, msg.id);
            }
            case SEND_NOW -> {
                if (msg.id != null) feedback = Schedules.sendNow(level, v, msg.id);
            }
            case ORDER -> {
                if (msg.pos != null) feedback = Exchange.order(level, v, msg.pos, msg.text);
            }
        }
        OpenMarketMessage.send(player, Market.view(level, v), feedback);
    }
}
