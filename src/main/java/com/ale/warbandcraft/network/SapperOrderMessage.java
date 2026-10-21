package com.ale.warbandcraft.network;

import com.ale.warbandcraft.sapper.FieldCannonEntity;
import com.ale.warbandcraft.sapper.SapperRules;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: orden de Zapador desde la vista aérea (H6).
 * - Volar: los zapadores seleccionados van a poner TNT en el bloque.
 * - Cañón: si el Zapador no tiene cañón, lo arma en ese lugar; si ya tiene, le apunta ahí.
 */
public class SapperOrderMessage {
    private final List<UUID> recruitIds;
    private final boolean cannon;
    private final BlockPos pos;

    public SapperOrderMessage(List<UUID> recruitIds, boolean cannon, BlockPos pos) {
        this.recruitIds = recruitIds;
        this.cannon = cannon;
        this.pos = pos;
    }

    public static void encode(SapperOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.recruitIds.size());
        for (UUID id : msg.recruitIds) buf.writeUUID(id);
        buf.writeBoolean(msg.cannon);
        buf.writeBlockPos(msg.pos);
    }

    public static SapperOrderMessage decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUUID());
        return new SapperOrderMessage(ids, buf.readBoolean(), buf.readBlockPos());
    }

    public static void handle(SapperOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null) player.displayClientMessage(apply(player.serverLevel(), player.getUUID(), msg.recruitIds, msg.cannon, msg.pos), true);
        });
        ctx.get().setPacketHandled(true);
    }

    /** Lógica pura (también la usan los GameTests). Devuelve el mensaje para el jugador. */
    public static Component apply(ServerLevel level, UUID player, List<UUID> ids, boolean cannon, BlockPos pos) {
        boolean enemy = SapperRules.enemyTarget(level, pos, player) != null;
        int sappers = 0, deploying = 0, aimed = 0;
        for (UUID id : ids) {
            Entity found = level.getEntity(id);
            if (!(found instanceof AbstractRecruitEntity r) || !r.isAlive()) continue;
            if (!Objects.equals(r.getOwnerUUID(), player) || !SapperRules.isSapper(r)) continue;
            sappers++;
            CompoundTag data = r.getPersistentData();
            if (!cannon) {
                if (!enemy) continue;
                data.remove(SapperRules.TAG_DEPLOY);
                data.putLong(SapperRules.TAG_BLAST, pos.asLong());
                continue;
            }
            FieldCannonEntity existing = FieldCannonEntity.of(level, r);
            if (existing != null) {
                if (!enemy) continue;
                existing.setTarget(pos);
                aimed++;
            } else {
                data.remove(SapperRules.TAG_BLAST);
                data.putLong(SapperRules.TAG_DEPLOY, pos.asLong());
                deploying++;
            }
        }
        if (sappers == 0) return Component.translatable("msg.warbandcraft.sapper.none_selected").withStyle(ChatFormatting.RED);
        if (!cannon) {
            return enemy ? Component.translatable("msg.warbandcraft.sapper.going", sappers).withStyle(ChatFormatting.GOLD)
                    : Component.translatable("msg.warbandcraft.sapper.not_enemy").withStyle(ChatFormatting.RED);
        }
        if (aimed == 0 && deploying == 0) {
            return Component.translatable("msg.warbandcraft.sapper.not_enemy").withStyle(ChatFormatting.RED);
        }
        return aimed > 0 ? Component.translatable("msg.warbandcraft.cannon.aimed", aimed).withStyle(ChatFormatting.GOLD)
                : Component.translatable("msg.warbandcraft.sapper.deploying", deploying).withStyle(ChatFormatting.GOLD);
    }
}
