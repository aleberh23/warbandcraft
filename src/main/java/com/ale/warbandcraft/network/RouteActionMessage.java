package com.ale.warbandcraft.network;

import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.RoutePostBlock;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.route.Travel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cliente → servidor: botones de la pantalla de Rutas (H13). Abrir (tecla J), registrar una ruta del mapa de
 * Recruits, borrarla, viajar o cancelar el viaje. Después se refresca la pantalla.
 */
public class RouteActionMessage {
    /** SEND_STOCK (H10): mandar el almacén de la aldea/castillo donde estás al Campamento {@code dest}. */
    /** BUILD_ROAD (H14): encargar el camino de la ruta {@code id}, desde la pestaña Rutas del Campamento. */
    public enum Action { OPEN, REGISTER, DELETE, TRAVEL, CANCEL, SEND_STOCK, BUILD_ROAD }

    /** Tope de puntos que se aceptan de una ruta (Recruits ya limita los suyos). */
    private static final int MAX_POINTS = 256;

    private final Action action;
    @Nullable
    private final BlockPos camp;
    private final UUID id;
    private final String name;
    private final List<BlockPos> points;
    private final BlockPos dest;
    /** El Poste de caminos desde el que se abrió la pantalla, o null. */
    @Nullable
    private final BlockPos post;

    public RouteActionMessage(Action action, @Nullable BlockPos camp, UUID id, String name, List<BlockPos> points, BlockPos dest) {
        this(action, camp, id, name, points, dest, null);
    }

    public RouteActionMessage(Action action, @Nullable BlockPos camp, UUID id, String name, List<BlockPos> points, BlockPos dest,
                              @Nullable BlockPos post) {
        this.action = action;
        this.camp = camp;
        this.id = id;
        this.name = name;
        this.points = points;
        this.dest = dest;
        this.post = post;
    }

    public static RouteActionMessage simple(Action action, @Nullable BlockPos camp) {
        return new RouteActionMessage(action, camp, new UUID(0, 0), "", List.of(), BlockPos.ZERO);
    }

    public static void encode(RouteActionMessage msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeNullable(msg.camp, FriendlyByteBuf::writeBlockPos);
        buf.writeUUID(msg.id);
        buf.writeUtf(msg.name, 64);
        buf.writeCollection(msg.points, FriendlyByteBuf::writeBlockPos);
        buf.writeBlockPos(msg.dest);
        buf.writeNullable(msg.post, FriendlyByteBuf::writeBlockPos);
    }

    public static RouteActionMessage decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        BlockPos camp = buf.readNullable(FriendlyByteBuf::readBlockPos);
        UUID id = buf.readUUID();
        String name = buf.readUtf(64);
        int n = buf.readVarInt();
        if (n > MAX_POINTS) throw new IllegalArgumentException("ruta con demasiados puntos: " + n);
        List<BlockPos> points = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) points.add(buf.readBlockPos());
        BlockPos dest = buf.readBlockPos();
        return new RouteActionMessage(action, camp, id, name, points, dest, buf.readNullable(FriendlyByteBuf::readBlockPos));
    }

    public static void handle(RouteActionMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            // La pestaña solo vale si seguís al lado de tu Campamento
            BlockPos camp = msg.camp != null && CampTabs.access(player, msg.camp, true) != null ? msg.camp : null;
            // El poste solo vale si seguís al lado
            BlockPos post = msg.post != null && RoutePostBlock.usable(player, msg.post) ? msg.post : null;
            switch (msg.action) {
                case OPEN -> {
                }
                case REGISTER -> {
                    Component error = Routes.register(player.serverLevel(), player.getUUID(), msg.id, msg.name, msg.points);
                    player.displayClientMessage(error != null ? error.copy().withStyle(ChatFormatting.RED)
                            : Component.translatable("msg.warbandcraft.routes.registered", msg.name).withStyle(ChatFormatting.GREEN), false);
                }
                case DELETE -> {
                    RouteData.Route r = RouteData.get(player.serverLevel()).route(msg.id);
                    if (r != null && RouteData.get(player.serverLevel()).remove(msg.id, player.getUUID())) {
                        com.ale.warbandcraft.route.Roads.cancel(player.serverLevel(), msg.id);   // H14
                        player.displayClientMessage(Component.translatable("msg.warbandcraft.routes.deleted", r.name()), true);
                    }
                }
                case TRAVEL -> {
                    Component error = Travel.start(player, msg.dest, camp != null ? camp : post);
                    if (error != null) player.displayClientMessage(error.copy().withStyle(ChatFormatting.RED), true);
                    return;   // abre "Viajando..." (o deja la pantalla como estaba)
                }
                case CANCEL -> {
                    Travel.cancel(player, Component.translatable("msg.warbandcraft.travel.cancelled"));
                    return;
                }
                case BUILD_ROAD -> {
                    Component result = camp == null ? Component.translatable("msg.warbandcraft.road.from_camp").withStyle(ChatFormatting.RED)
                            : com.ale.warbandcraft.route.Roads.order(player, msg.id, camp);
                    player.displayClientMessage(result, false);
                }
                case SEND_STOCK -> {
                    // Desde el punto tuyo donde estás (o el del poste), como el resto de la pantalla
                    Routes.Node here = Routes.nodeNear(player.serverLevel(), player.getUUID(), post != null ? post : player.blockPosition());
                    Component error = here == null ? Component.translatable("msg.warbandcraft.stock.not_yours")
                            : VillageStock.send(player.serverLevel(), player.getUUID(), here.pos(), msg.dest);
                    player.displayClientMessage(error != null ? error.copy().withStyle(ChatFormatting.RED)
                            : Component.translatable("msg.warbandcraft.stock.sent").withStyle(ChatFormatting.GREEN), false);
                }
            }
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenRoutesMessage.of(player, camp, post));
        });
        ctx.get().setPacketHandled(true);
    }
}
