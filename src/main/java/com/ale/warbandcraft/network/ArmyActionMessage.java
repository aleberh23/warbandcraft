package com.ale.warbandcraft.network;

import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.army.ArmyData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: botones de la pantalla Ejércitos y de la vista aérea (H20). Con {@code screen} se responde con la
 * pantalla actualizada; siempre, con el estado de los ejércitos en el campo.
 */
public class ArmyActionMessage {
    public enum Action { OPEN, STATUS, CREATE, DELETE, RENAME, ASSIGN, SUMMON, CANCEL, HOLD, ADVANCE, CHARGE, RETREAT, DISMISS, DELEGATE }

    /** Hasta dónde de vos se puede mandar a avanzar al ejército. */
    private static final double MAX_ADVANCE = 512;

    private final Action action;
    @Nullable
    private final UUID army;
    @Nullable
    private final BlockPos camp;
    private final String text;
    private final int amount;
    @Nullable
    private final Vec3 pos;
    private final boolean screen;

    public ArmyActionMessage(Action action, @Nullable UUID army, @Nullable BlockPos camp, String text, int amount, @Nullable Vec3 pos,
                             boolean screen) {
        this.action = action;
        this.army = army;
        this.camp = camp;
        this.text = text;
        this.amount = amount;
        this.pos = pos;
        this.screen = screen;
    }

    public static ArmyActionMessage open() {
        return new ArmyActionMessage(Action.OPEN, null, null, "", 0, null, true);
    }

    public static ArmyActionMessage order(Action action, UUID army, @Nullable Vec3 pos) {
        return new ArmyActionMessage(action, army, null, "", 0, pos, false);
    }

    public static void encode(ArmyActionMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeNullable(msg.army, FriendlyByteBuf::writeUUID);
        buf.writeNullable(msg.camp, FriendlyByteBuf::writeBlockPos);
        buf.writeUtf(msg.text, 256);
        buf.writeInt(msg.amount);
        buf.writeNullable(msg.pos, (b, v) -> {
            b.writeDouble(v.x);
            b.writeDouble(v.y);
            b.writeDouble(v.z);
        });
        buf.writeBoolean(msg.screen);
    }

    public static ArmyActionMessage decode(FriendlyByteBuf buf) {
        return new ArmyActionMessage(buf.readEnum(Action.class), buf.readNullable(FriendlyByteBuf::readUUID),
                buf.readNullable(FriendlyByteBuf::readBlockPos), buf.readUtf(256), buf.readInt(),
                buf.readNullable(b -> new Vec3(b.readDouble(), b.readDouble(), b.readDouble())), buf.readBoolean());
    }

    public static void handle(ArmyActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            ArmyData.Army a = Armies.mine(level, player.getUUID(), msg.army);
            Component result = null;
            if (msg.action == Action.CREATE) {
                result = Armies.create(level, player.getUUID(), msg.text);
            } else if (a != null) {
                result = switch (msg.action) {
                    case DELETE -> Armies.delete(level, a);
                    case RENAME -> Armies.rename(level, a, msg.text);
                    case ASSIGN -> msg.camp == null ? null : Armies.assign(level, a, msg.camp, msg.text, msg.amount);
                    case SUMMON -> Armies.summon(player, a);
                    case CANCEL -> Armies.cancel(level, a);
                    case HOLD -> Armies.holdOrder(level, a);
                    case ADVANCE -> msg.pos == null || msg.pos.distanceToSqr(player.position()) > MAX_ADVANCE * MAX_ADVANCE
                            ? null : Armies.advance(level, a, msg.pos);
                    case CHARGE -> Armies.charge(level, a, player);
                    case RETREAT -> Armies.retreat(level, a);
                    case DISMISS -> Armies.dismiss(level, a);
                    case DELEGATE -> Armies.delegate(level, a);
                    default -> null;
                };
            }
            if (result != null) player.displayClientMessage(result, !msg.screen);
            if (msg.screen) OpenArmiesMessage.send(player);
            ArmyStatusMessage.send(player);
        });
        ctx.get().setPacketHandled(true);
    }
}
