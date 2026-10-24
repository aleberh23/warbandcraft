package com.ale.warbandcraft.network;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: botón Batalla de la vista aérea (H9). Prende la orden Batalla en los soldados elegidos
 * (sin elegidos: todos los tuyos a {@link #RADIUS} bloques); si ya la tenían todos, la apaga.
 */
public class BattleOrderMessage {
    /** Sin soldados elegidos, a quiénes alcanza la orden. */
    public static final int RADIUS = 64;
    /** Si no hay un ataque tuyo a esta distancia, se avisa que arrancan solos cuando empiece uno. */
    private static final int RAID_NEAR = 128;

    private final List<UUID> recruitIds;

    public BattleOrderMessage(List<UUID> recruitIds) {
        this.recruitIds = recruitIds;
    }

    public static void encode(BattleOrderMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.recruitIds.size());
        for (UUID id : msg.recruitIds) buf.writeUUID(id);
    }

    public static BattleOrderMessage decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) ids.add(buf.readUUID());
        return new BattleOrderMessage(ids);
    }

    public static void handle(BattleOrderMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player != null) player.displayClientMessage(toggle(player, msg.recruitIds, null), true);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Prende o apaga la orden Batalla. {@code ids} vacío = todos los tuyos cerca. {@code on} null = si todos la
     * tenían, se apaga; si no, se prende. Devuelve el mensaje para el jugador.
     */
    public static Component toggle(ServerPlayer player, List<UUID> ids, Boolean on) {
        ServerLevel level = player.serverLevel();
        List<AbstractRecruitEntity> soldiers = new ArrayList<>();
        if (ids.isEmpty()) {
            soldiers.addAll(level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(player.blockPosition()).inflate(RADIUS),
                    r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), player.getUUID()) && r.getListen() && Soldiers.isSoldier(r)));
        } else {
            for (UUID id : ids) {
                Entity e = level.getEntity(id);
                if (e instanceof AbstractRecruitEntity r && r.isAlive() && r.getListen() && Soldiers.isSoldier(r)
                        && Objects.equals(r.getOwnerUUID(), player.getUUID())) soldiers.add(r);
            }
        }
        if (soldiers.isEmpty()) return Component.translatable("msg.warbandcraft.battle.none").withStyle(ChatFormatting.YELLOW);

        boolean turnOn = on != null ? on : !soldiers.stream().allMatch(BattleGeneral::inBattle);
        for (AbstractRecruitEntity s : soldiers) BattleGeneral.setBattle(s, turnOn);
        if (!turnOn) {
            return Component.translatable("msg.warbandcraft.battle.off", soldiers.size()).withStyle(ChatFormatting.GRAY);
        }
        boolean raidNear = false;
        for (RaidManager.Raid raid : RaidManager.all()) {
            if (raid.attacker.equals(player.getUUID()) && raid.state == RaidManager.State.FIGHTING && raid.dimension == level.dimension()
                    && VillageData.horizontalDistSq(raid.center, player.blockPosition()) <= (double) RAID_NEAR * RAID_NEAR) {
                raidNear = true;
            }
        }
        return Component.translatable(raidNear ? "msg.warbandcraft.battle.on" : "msg.warbandcraft.battle.on_waiting", soldiers.size())
                .withStyle(ChatFormatting.AQUA);
    }
}
