package com.ale.warbandcraft.network;

import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManagement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor (H16): órdenes del modo Gestión. OPEN pide los datos de la aldea tuya donde estás (pos
 * ignorado); el resto actúa sobre la aldea {@code pos}. Después de cada una el servidor manda los datos de nuevo.
 */
public class ManageActionMessage {
    public enum Action { OPEN, REFRESH, ASSIGN, BUILD, SEND_STOCK, ZONE, ZONE_AUTO, ZONE_DELETE, EXPAND, HOUSES_AUTO, TO_QUARTERS, TO_BARRACKS,
        /** H19: Auto-reclutar de la taberna (value = 1 prendido). */
        TAVERN_AUTO,
        /** H21: raciones / impuestos de la aldea (value = 0..4). */
        RATIONS, TAXES,
        /** H21: Auto-asignar aldeanos libres a zonas sin trabajador (value = 1 prendido). */
        AUTO_ASSIGN,
        /**
         * H22: plantar / pedir la vista previa (text = "id,x,z" de la esquina de un edificio, value = giro 0..3; o una obra
         * de {@code Works}: "wall:1:x,z;...", "gate:2:x,z", "tower:1:x,z", "earth:0:x0,z0,x1,z1,y", "demolish:clave") y cancelar la obra.
         */
        PLACE, PREVIEW, CANCEL_JOB }

    public final Action action;
    public final BlockPos pos;
    @Nullable
    public final UUID target;
    /** ASSIGN: el oficio (ordinal de {@link VillageManagement.Job}); BUILD: no se usa. */
    public final int value;
    /** BUILD: id de la mejora; ZONE: el rectángulo "x0,z0,x1,z1". */
    public final String text;

    public ManageActionMessage(Action action, BlockPos pos, @Nullable UUID target, int value, String text) {
        this.action = action;
        this.pos = pos;
        this.target = target;
        this.value = value;
        this.text = text;
    }

    public static ManageActionMessage open() {
        return new ManageActionMessage(Action.OPEN, BlockPos.ZERO, null, 0, "");
    }

    public static void encode(ManageActionMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeBlockPos(msg.pos);
        buf.writeNullable(msg.target, FriendlyByteBuf::writeUUID);
        buf.writeVarInt(msg.value);
        buf.writeUtf(msg.text, 4096);
    }

    public static ManageActionMessage decode(FriendlyByteBuf buf) {
        return new ManageActionMessage(buf.readEnum(Action.class), buf.readBlockPos(), buf.readNullable(FriendlyByteBuf::readUUID),
                buf.readVarInt(), buf.readUtf(4096));
    }

    public static void handle(ManageActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            VillageData.Village v = msg.action == Action.OPEN ? VillageManagement.near(player) : VillageManagement.access(player, msg.pos);
            if (msg.action == Action.PREVIEW) {   // H22: solo la vista previa, sin mandar todo de nuevo
                if (v != null) BuildPreviewMessage.answer(player, v, msg.text, msg.value);
                return;
            }
            Component feedback = Component.empty();
            if (v == null) {
                if (msg.action == Action.OPEN) {
                    feedback = Component.translatable("msg.warbandcraft.manage.not_here", com.ale.warbandcraft.village.VillageCamps.MANAGE_RADIUS)
                            .withStyle(ChatFormatting.RED);
                }
                send(player, null, feedback);
                return;
            }
            switch (msg.action) {
                case ASSIGN -> {
                    VillageManagement.Job[] jobs = VillageManagement.Job.values();
                    if (msg.target != null && msg.value >= 0 && msg.value < jobs.length) {
                        feedback = VillageManagement.assign(player, v, msg.target, jobs[msg.value]);
                    }
                }
                case BUILD -> feedback = Panel.build(player, v.center(), msg.text);
                case PLACE -> {
                    if (msg.text.contains(":")) {   // H22b/c: muralla, portón, torre, terreno, demoler
                        feedback = com.ale.warbandcraft.upgrade.Upgrades.orderWork(player, v.center(), msg.text);
                    } else {
                        BuildPreviewMessage.Target t = BuildPreviewMessage.Target.parse(msg.text);
                        if (t != null) feedback = com.ale.warbandcraft.upgrade.Upgrades.order(player, v.center(), t.upgrade(), t.corner(), msg.value);
                    }
                }
                case CANCEL_JOB -> feedback = com.ale.warbandcraft.upgrade.Upgrades.cancel(player, v.center());
                case SEND_STOCK -> feedback = Panel.sendStock(player, v.center());
                case ZONE -> {
                    int[] r = rect(msg.text);
                    if (msg.target != null && r != null) {
                        feedback = com.ale.warbandcraft.village.WorkZones.mark(player, v, msg.target, r[0], r[1], r[2], r[3]);
                    }
                }
                case ZONE_AUTO -> {
                    if (msg.target != null) feedback = com.ale.warbandcraft.village.WorkZones.suggest(player, v, msg.target);
                }
                case EXPAND -> {   // agrandar el territorio: text = "cx,cz"
                    String[] p = msg.text.split(",");
                    try {
                        feedback = com.ale.warbandcraft.village.Territory.expand(player, v,
                                new net.minecraft.world.level.ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
                    } catch (RuntimeException ignored) {
                    }
                }
                case HOUSES_AUTO -> feedback = com.ale.warbandcraft.village.Housing.setAuto(player, v, msg.value != 0);
                case RATIONS -> feedback = com.ale.warbandcraft.village.Popularity.setRations(player.serverLevel(), v, msg.value);
                case TAXES -> feedback = com.ale.warbandcraft.village.Popularity.setTaxes(player.serverLevel(), v, msg.value);
                case AUTO_ASSIGN -> feedback = com.ale.warbandcraft.village.Popularity.setAutoAssign(player.serverLevel(), v, msg.value != 0);
                case TAVERN_AUTO -> feedback = com.ale.warbandcraft.village.Tavern.setAuto(player.serverLevel(), v, msg.value != 0);
                case TO_QUARTERS, TO_BARRACKS -> {   // pasar tropas entre el barracón y el cuartel (text = fila, "*" = todas; value = cuántos)
                    if (com.ale.warbandcraft.village.Tavern.ROW.equals(msg.text)) {   // H19: los de la taberna
                        feedback = com.ale.warbandcraft.village.Tavern.send(player.serverLevel(), v, msg.action == Action.TO_QUARTERS, Math.max(1, msg.value));
                        break;
                    }
                    String row = "*".equals(msg.text) ? null : msg.text;
                    int amount = Math.max(1, msg.value);
                    feedback = msg.action == Action.TO_QUARTERS
                            ? com.ale.warbandcraft.village.Quarters.toQuarters(player.serverLevel(), v, row, amount)
                            : com.ale.warbandcraft.village.Quarters.toBarracks(player.serverLevel(), v, row, amount);
                }
                case ZONE_DELETE -> {
                    if (msg.target != null) feedback = com.ale.warbandcraft.village.WorkZones.delete(player, v, msg.target);
                }
                default -> {
                }
            }
            send(player, VillageManagement.data(player, v), feedback);
        });
        ctx.get().setPacketHandled(true);
    }

    @Nullable
    private static int[] rect(String text) {
        String[] p = text.split(",");
        if (p.length != 4) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void send(ServerPlayer player, @Nullable VillageManagement.Data data, Component feedback) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ManageMessage(data, feedback));
    }
}
