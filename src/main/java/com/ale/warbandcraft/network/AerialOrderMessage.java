package com.ale.warbandcraft.network;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.ale.warbandcraft.ai.Formation;
import com.ale.warbandcraft.battle.BattleGeneral;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: orden de mover/atacar de la vista aérea (H5) para los soldados seleccionados.
 *
 * Las órdenes de Steve de Recruits ({@code MessageMovement}/{@code MessageAttack}) calculan el destino
 * con {@code player.pick(...)}, es decir, apuntan a donde mira el jugador real en ese instante. En vista
 * aérea la cámara está desacoplada de la mirada de Steve, así que el punto/objetivo ya viene calculado
 * por la cámara del cliente (uno por soldado, para poder repartirlos en formación).
 *
 * Importante: usamos el estado 3 de Recruits ({@code setFollowState(3)}, el mismo que usa
 * {@code CommandEvents.onAttackCommand} para "enganchar" un objetivo) para TODO — mover y atacar —
 * en vez del estado 2 ("mantener tu posición"). Leyendo el código de Recruits:
 * {@code AbstractRecruitEntity.setFollowState(2)} pisa el {@code holdPos} con la posición ACTUAL del
 * soldado ({@code clearHoldPos(); setHoldPos(position());}), así que cualquier punto que le hayamos
 * puesto antes queda tirado y el soldado se queda quieto donde ya estaba. El estado 3 no tiene ese
 * problema (no toca {@code holdPos}), así que el punto que mandamos desde la cámara sobrevive.
 */
public class AerialOrderMessage {
    private static final int FOLLOW_HOLD = 3;

    private final List<UUID> recruitIds;
    private final boolean attack;
    private final List<Vec3> targets; // mismo largo que recruitIds
    private final UUID targetEntityId;

    public AerialOrderMessage(List<UUID> recruitIds, boolean attack, List<Vec3> targets, UUID targetEntityId) {
        this.recruitIds = recruitIds;
        this.attack = attack;
        this.targets = targets;
        this.targetEntityId = targetEntityId;
    }

    public static void encode(AerialOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.recruitIds.size());
        for (UUID id : msg.recruitIds) buf.writeUUID(id);
        buf.writeBoolean(msg.attack);
        for (Vec3 pos : msg.targets) {
            buf.writeDouble(pos.x);
            buf.writeDouble(pos.y);
            buf.writeDouble(pos.z);
        }
        buf.writeBoolean(msg.targetEntityId != null);
        if (msg.targetEntityId != null) buf.writeUUID(msg.targetEntityId);
    }

    public static AerialOrderMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        boolean attack = buf.readBoolean();
        List<Vec3> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) targets.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        UUID targetEntityId = buf.readBoolean() ? buf.readUUID() : null;
        return new AerialOrderMessage(ids, attack, targets, targetEntityId);
    }

    public static void handle(AerialOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> apply(player, msg));
        ctx.get().setPacketHandled(true);
    }

    private static void apply(ServerPlayer player, AerialOrderMessage msg) {
        if (player == null || msg.recruitIds.isEmpty()) return;
        ServerLevel level = player.serverLevel();

        // Resolver por UUID (en vez de escanear a cierta distancia): un soldado que se alejó
        // mucho de Steve (o la cámara) con una orden anterior no debe dejar de recibir la próxima.
        LivingEntity target = null;
        if (msg.attack && msg.targetEntityId != null) {
            Entity found = level.getEntity(msg.targetEntityId);
            if (found instanceof LivingEntity living) target = living;
        }

        // Formación Libre: todos al mismo punto los amontona y se traban; se reparten en grilla.
        List<Vec3> targets = msg.targets;
        if (target == null && targets.size() > 1 && Formation.allSame(targets)) {
            targets = Formation.spread(level, targets.get(0), targets.size());
        }

        for (int i = 0; i < msg.recruitIds.size(); i++) {
            Entity found = level.getEntity(msg.recruitIds.get(i));
            if (!(found instanceof AbstractRecruitEntity recruit)) continue;
            if (!recruit.isAlive() || !recruit.getListen() || !com.ale.warbandcraft.faction.Allies.same(recruit.getOwnerUUID(), player.getUUID())) continue;

            Vec3 pos = targets.get(i);
            BattleGeneral.setBattle(recruit, false);   // H9: una orden a mano manda más que el general
            if (target != null && recruit.canAttack(target)) {
                recruit.setTarget(target);
            } else {
                recruit.setTarget(null);
            }
            recruit.setHoldPos(pos);
            recruit.setFollowState(FOLLOW_HOLD);
        }
    }
}
