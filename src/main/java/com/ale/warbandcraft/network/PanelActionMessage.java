package com.ale.warbandcraft.network;

import com.ale.warbandcraft.panel.Panel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Cliente → servidor: abrir el Panel de control (H11, tecla K), cambiar de pestaña o de Campamento en detalle, y
 * sus botones. Después de cada uno se manda el panel actualizado.
 */
public class PanelActionMessage {
    public enum Action { OPEN, DEPLOY, STORE_NEAR, RECRUIT_PRISONER, RANSOM_PRISONER, SEND_SOLDIERS, SEND_STOCK, VILLAGE, BUILD, BUILD_ROAD,
        /** Renombrar un Campamento (pos = el Campamento) o una aldea/castillo (pos = su centro); el nombre va en row. */
        RENAME_CAMP, RENAME_VILLAGE,
        /** Fundar una aldea en el Campamento (pos). */
        FOUND_VILLAGE }

    private final Action action;
    private final int tab;
    /** El Campamento en detalle (o la aldea, para SEND_STOCK), o null. */
    @Nullable
    private final BlockPos pos;
    private final String row;
    private final int amount;
    @Nullable
    private final BlockPos dest;

    public PanelActionMessage(Action action, int tab, @Nullable BlockPos pos, String row, int amount, @Nullable BlockPos dest) {
        this.action = action;
        this.tab = tab;
        this.pos = pos;
        this.row = row;
        this.amount = amount;
        this.dest = dest;
    }

    public static PanelActionMessage open(int tab, @Nullable BlockPos camp) {
        return new PanelActionMessage(Action.OPEN, tab, camp, "", 0, null);
    }

    public static void encode(PanelActionMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeVarInt(msg.tab);
        buf.writeNullable(msg.pos, FriendlyByteBuf::writeBlockPos);
        buf.writeUtf(msg.row, 256);
        buf.writeVarInt(msg.amount);
        buf.writeNullable(msg.dest, FriendlyByteBuf::writeBlockPos);
    }

    public static PanelActionMessage decode(FriendlyByteBuf buf) {
        return new PanelActionMessage(buf.readEnum(Action.class), buf.readVarInt(), buf.readNullable(FriendlyByteBuf::readBlockPos),
                buf.readUtf(256), buf.readVarInt(), buf.readNullable(FriendlyByteBuf::readBlockPos));
    }

    public static void handle(PanelActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            BlockPos camp = msg.pos;
            Component result = null;
            if (msg.pos != null || msg.action == Action.BUILD_ROAD) {   // el camino va por id de ruta (row), sin lugar
                result = switch (msg.action) {
                    case OPEN -> null;
                    case DEPLOY -> Panel.deploy(player, msg.pos, msg.row, msg.amount);
                    case STORE_NEAR -> Panel.storeNear(player, msg.pos);
                    case RECRUIT_PRISONER -> Panel.recruit(player, msg.pos, msg.row);
                    case RANSOM_PRISONER -> Panel.ransom(player, msg.pos, msg.row);
                    case SEND_SOLDIERS -> msg.dest == null ? null : Panel.sendSoldiers(player, msg.pos, msg.row, msg.amount, msg.dest);
                    case SEND_STOCK -> Panel.sendStock(player, msg.pos);
                    case VILLAGE -> null;
                    case BUILD -> Panel.build(player, msg.pos, msg.row);
                    case BUILD_ROAD -> Panel.buildRoad(player, msg.row, null);
                    case RENAME_CAMP -> Panel.rename(player, msg.pos, msg.row, false);
                    case RENAME_VILLAGE -> Panel.rename(player, msg.pos, msg.row, true);
                    case FOUND_VILLAGE -> Panel.found(player, msg.pos);
                };
            }
            BlockPos village = null;
            if (msg.action == Action.SEND_STOCK) camp = null;   // era una aldea: el panel sigue en la lista
            if (msg.action == Action.VILLAGE || msg.action == Action.BUILD || msg.action == Action.RENAME_VILLAGE) {   // H12: Mejoras de una aldea
                village = msg.pos;
                camp = null;
            }
            if (result != null) player.displayClientMessage(result, msg.action != Action.BUILD && msg.action != Action.BUILD_ROAD);
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenPanelMessage.of(player, msg.tab, camp, village));
        });
        ctx.get().setPacketHandled(true);
    }
}
