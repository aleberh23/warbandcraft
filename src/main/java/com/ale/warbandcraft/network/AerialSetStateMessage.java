package com.ale.warbandcraft.network;

import com.ale.warbandcraft.battle.BattleGeneral;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.network.FriendlyByteBuf;
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
 * Cliente → servidor: botones de modo de la vista aérea (H5) para los soldados seleccionados.
 *
 * {@code HOLD_HERE} ("mantener posición") usa el estado 3 de Recruits con el {@code holdPos} puesto en
 * la posición ACTUAL de cada soldado (se calcula acá, del lado servidor, porque cada soldado está en un
 * punto distinto) — no el estado 2: ese internamente pisa el {@code holdPos} de cualquier forma, así que
 * da exactamente el mismo resultado, pero mejor usar el que no tiene esa trampa (ver
 * {@link AerialOrderMessage}). {@code FOLLOW} son los estados simples de Recruits que no tocan
 * {@code holdPos} (0 = vagar libre, 1 = seguir al dueño). {@code AGGRO_STATE} es
 * {@code setAggroState} (0 neutral, 1 agresivo, 2 raid, 3 pasivo, los mismos números que usa la
 * pantalla de comando de Recruits).
 */
public class AerialSetStateMessage {
    private static final int FOLLOW_HOLD = 3;

    public enum Kind { FOLLOW, HOLD_HERE, AGGRO_STATE }

    private final List<UUID> recruitIds;
    private final Kind kind;
    private final int value;

    public AerialSetStateMessage(List<UUID> recruitIds, Kind kind, int value) {
        this.recruitIds = recruitIds;
        this.kind = kind;
        this.value = value;
    }

    public static void encode(AerialSetStateMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.recruitIds.size());
        for (UUID id : msg.recruitIds) buf.writeUUID(id);
        buf.writeEnum(msg.kind);
        buf.writeVarInt(msg.value);
    }

    public static AerialSetStateMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        Kind kind = buf.readEnum(Kind.class);
        int value = buf.readVarInt();
        return new AerialSetStateMessage(ids, kind, value);
    }

    public static void handle(AerialSetStateMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> apply(player, msg));
        ctx.get().setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, AerialSetStateMessage msg) {
        if (player == null || msg.recruitIds.isEmpty()) return;
        ServerLevel level = player.serverLevel();

        for (UUID id : msg.recruitIds) {
            Entity found = level.getEntity(id);
            if (!(found instanceof AbstractRecruitEntity r)) continue;
            if (!r.isAlive() || !r.getListen() || !Objects.equals(r.getOwnerUUID(), player.getUUID())) continue;

            switch (msg.kind) {
                case FOLLOW -> {
                    BattleGeneral.setBattle(r, false);
                    r.setTarget(null);
                    r.setFollowState(msg.value);
                }
                case HOLD_HERE -> {
                    BattleGeneral.setBattle(r, false);
                    r.setTarget(null);
                    r.setHoldPos(r.position());
                    r.setFollowState(FOLLOW_HOLD);
                }
                case AGGRO_STATE -> r.setAggroState(msg.value);
            }
        }
    }
}
