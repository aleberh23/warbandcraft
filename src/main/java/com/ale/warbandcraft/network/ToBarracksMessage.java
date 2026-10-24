package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampRegistry;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor (H8): los soldados seleccionados en la vista aérea caminan a un Campamento y se
 * guardan en su barracón al llegar. {@code camp} null = cada uno al suyo; si no, a ese (y pasan a
 * pertenecerle). Viene del botón Barracón (lista) o de un click derecho sobre un Campamento.
 */
public class ToBarracksMessage {
    private final List<UUID> recruitIds;
    @Nullable
    private final BlockPos camp;

    public ToBarracksMessage(List<UUID> recruitIds, @Nullable BlockPos camp) {
        this.recruitIds = recruitIds;
        this.camp = camp;
    }

    public static void encode(ToBarracksMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.recruitIds.size());
        for (UUID id : msg.recruitIds) buf.writeUUID(id);
        buf.writeBoolean(msg.camp != null);
        if (msg.camp != null) buf.writeBlockPos(msg.camp);
    }

    public static ToBarracksMessage decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUUID());
        return new ToBarracksMessage(ids, buf.readBoolean() ? buf.readBlockPos() : null);
    }

    public static void handle(ToBarracksMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            player.displayClientMessage(apply(player.serverLevel(), player.getUUID(), msg.recruitIds, msg.camp), true);
        });
        ctx.get().setPacketHandled(true);
    }

    /** Lógica pura (también la usan los GameTests). Devuelve el mensaje para el jugador. */
    public static Component apply(ServerLevel level, UUID player, List<UUID> ids, @Nullable BlockPos camp) {
        CampRegistry registry = CampRegistry.get(level);
        if (camp != null && !registry.isCampOf(player, camp)) {
            return Component.translatable("msg.warbandcraft.barracks.not_your_camp").withStyle(ChatFormatting.RED);
        }
        int sent = 0;
        for (UUID id : ids) {
            Entity found = level.getEntity(id);
            if (!(found instanceof AbstractRecruitEntity r) || !r.isAlive()) continue;
            if (!Objects.equals(r.getOwnerUUID(), player)) continue;
            if (Barracks.sendHome(level, r, camp)) sent++;
        }
        if (sent == 0) return Component.translatable("msg.warbandcraft.barracks.none_sent").withStyle(ChatFormatting.RED);
        return camp == null
                ? Component.translatable("msg.warbandcraft.barracks.going_own", sent).withStyle(ChatFormatting.GOLD)
                : Component.translatable("msg.warbandcraft.barracks.going", sent, registry.displayName(camp)).withStyle(ChatFormatting.GOLD);
    }
}
