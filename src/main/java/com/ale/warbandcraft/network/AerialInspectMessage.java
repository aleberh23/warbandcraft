package com.ale.warbandcraft.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: abrir el inventario/menú propio de Recruits para un soldado (shift+click sobre
 * una sola unidad en la vista aérea) — el mismo {@code recruit.openGUI(player)} que abre Recruits
 * cuando agachado le hacés click en primera persona. No reinventamos esa pantalla.
 */
public class AerialInspectMessage {
    private final UUID recruitId;

    public AerialInspectMessage(UUID recruitId) {
        this.recruitId = recruitId;
    }

    public static void encode(AerialInspectMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.recruitId);
    }

    public static AerialInspectMessage decode(FriendlyByteBuf buf) {
        return new AerialInspectMessage(buf.readUUID());
    }

    public static void handle(AerialInspectMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            Entity found = player.serverLevel().getEntity(msg.recruitId);
            if (found instanceof AbstractRecruitEntity r && r.isAlive() && Objects.equals(r.getOwnerUUID(), player.getUUID())) {
                r.openGUI(player);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
