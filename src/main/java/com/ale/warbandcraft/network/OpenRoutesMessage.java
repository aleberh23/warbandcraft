package com.ale.warbandcraft.network;

import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Roads;
import com.ale.warbandcraft.route.Routes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Servidor → cliente: abre (o refresca) la pantalla de Rutas (H13): dónde estás, tus puntos, tus rutas
 * registradas y a dónde podés viajar. Las rutas del mapa de Recruits las lee el cliente solo.
 */
public class OpenRoutesMessage {
    public record NodeInfo(BlockPos pos, Component name, Routes.Kind kind) {}

    /** H14: {@code cost} del camino, {@code percent} de la obra (-1 = sin obra), {@code here} = sale del Campamento donde estás. */
    public record RouteInfo(UUID id, String name, BlockPos a, BlockPos b, int length, boolean road, boolean active, int ticks,
                            int cost, int percent, boolean here) {}

    /** {@code camp}: el destino es un Campamento (H10: ahí se puede mandar el almacén de una aldea). */
    public record DestInfo(BlockPos pos, Component name, int ticks, int legs, boolean camp) {}

    /** El Campamento desde cuya pestaña se abrió (para dibujar las pestañas), o null. */
    @Nullable
    public final BlockPos camp;
    /** El Poste de caminos desde el que se abrió, o null. */
    @Nullable
    public final BlockPos post;
    /** El punto tuyo donde estás parado, o null. */
    @Nullable
    public final NodeInfo here;
    public final List<NodeInfo> nodes;
    public final List<RouteInfo> routes;
    public final List<DestInfo> destinations;
    public final int endpointRadius;
    /** H10: almacén del punto donde estás si es tu aldea/castillo ({esmeraldas, comida, armamento}), o null. */
    @Nullable
    public final int[] stock;

    public OpenRoutesMessage(@Nullable BlockPos camp, @Nullable BlockPos post, @Nullable NodeInfo here, List<NodeInfo> nodes, List<RouteInfo> routes,
                             List<DestInfo> destinations, int endpointRadius, @Nullable int[] stock) {
        this.camp = camp;
        this.post = post;
        this.here = here;
        this.nodes = nodes;
        this.routes = routes;
        this.destinations = destinations;
        this.endpointRadius = endpointRadius;
        this.stock = stock;
    }

    public static OpenRoutesMessage of(ServerPlayer player, @Nullable BlockPos camp) {
        return of(player, camp, null);
    }

    /** {@code post}: el Poste de caminos desde el que se abrió (se viaja desde el punto tuyo más cercano a él). */
    public static OpenRoutesMessage of(ServerPlayer player, @Nullable BlockPos camp, @Nullable BlockPos post) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        Routes.Node here = camp != null ? Routes.node(level, owner, camp) : null;
        if (here == null) here = Routes.nodeNear(level, owner, post != null ? post : player.blockPosition());

        List<NodeInfo> nodes = new ArrayList<>();
        for (Routes.Node n : Routes.nodes(level, owner)) nodes.add(new NodeInfo(n.pos(), n.name(), n.kind()));
        List<RouteInfo> routes = new ArrayList<>();
        // H14: los caminos se encargan desde la pestaña Rutas de un Campamento (las aldeas/castillos, desde Mejoras)
        BlockPos roadsFrom = camp != null && here != null && here.kind() == Routes.Kind.CAMP ? here.pos() : null;
        for (RouteData.Route r : RouteData.get(level).routesOf(owner)) {
            routes.add(new RouteInfo(r.id(), r.name(), r.a(), r.b(), r.length(), r.road(), Routes.active(level, r), Routes.ticks(r),
                    Roads.cost(r), Roads.percent(level, r.id()), roadsFrom != null && Roads.touches(level, r, roadsFrom)));
        }
        List<DestInfo> dests = new ArrayList<>();
        if (here != null) {
            for (Routes.Destination d : Routes.reachable(level, owner, here.pos())) {
                dests.add(new DestInfo(d.node().pos(), d.node().name(), d.ticks(), d.legs(), d.node().kind() == Routes.Kind.CAMP));
            }
        }
        NodeInfo hereInfo = here == null ? null : new NodeInfo(here.pos(), here.name(), here.kind());
        int[] stock = null;
        if (here != null && here.kind() != Routes.Kind.CAMP) {
            VillageData.Village v = VillageData.get(level).at(here.pos());
            VillageStock.Stock s = v == null ? VillageStock.Stock.EMPTY : VillageStock.total(level, v);
            stock = new int[]{s.emeralds(), s.food(), s.armament()};
        }
        return new OpenRoutesMessage(camp, post, hereInfo, nodes, routes, dests, Routes.ENDPOINT_RADIUS, stock);
    }

    public static void encode(OpenRoutesMessage msg, FriendlyByteBuf buf) {
        buf.writeNullable(msg.camp, FriendlyByteBuf::writeBlockPos);
        buf.writeNullable(msg.post, FriendlyByteBuf::writeBlockPos);
        buf.writeNullable(msg.here, OpenRoutesMessage::writeNode);
        buf.writeCollection(msg.nodes, OpenRoutesMessage::writeNode);
        buf.writeCollection(msg.routes, (b, r) -> {
            b.writeUUID(r.id());
            b.writeUtf(r.name());
            b.writeBlockPos(r.a());
            b.writeBlockPos(r.b());
            b.writeVarInt(r.length());
            b.writeBoolean(r.road());
            b.writeBoolean(r.active());
            b.writeVarInt(r.ticks());
            b.writeVarInt(r.cost());
            b.writeInt(r.percent());
            b.writeBoolean(r.here());
        });
        buf.writeCollection(msg.destinations, (b, d) -> {
            b.writeBlockPos(d.pos());
            b.writeComponent(d.name());
            b.writeVarInt(d.ticks());
            b.writeVarInt(d.legs());
            b.writeBoolean(d.camp());
        });
        buf.writeVarInt(msg.endpointRadius);
        buf.writeNullable(msg.stock, FriendlyByteBuf::writeVarIntArray);
    }

    private static void writeNode(FriendlyByteBuf b, NodeInfo n) {
        b.writeBlockPos(n.pos());
        b.writeComponent(n.name());
        b.writeEnum(n.kind());
    }

    private static NodeInfo readNode(FriendlyByteBuf b) {
        return new NodeInfo(b.readBlockPos(), b.readComponent(), b.readEnum(Routes.Kind.class));
    }

    public static OpenRoutesMessage decode(FriendlyByteBuf buf) {
        BlockPos camp = buf.readNullable(FriendlyByteBuf::readBlockPos);
        BlockPos post = buf.readNullable(FriendlyByteBuf::readBlockPos);
        NodeInfo here = buf.readNullable(OpenRoutesMessage::readNode);
        List<NodeInfo> nodes = buf.readList(OpenRoutesMessage::readNode);
        List<RouteInfo> routes = buf.readList(b -> new RouteInfo(b.readUUID(), b.readUtf(), b.readBlockPos(), b.readBlockPos(),
                b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readInt(), b.readBoolean()));
        List<DestInfo> dests = buf.readList(b -> new DestInfo(b.readBlockPos(), b.readComponent(), b.readVarInt(), b.readVarInt(),
                b.readBoolean()));
        int radius = buf.readVarInt();
        return new OpenRoutesMessage(camp, post, here, nodes, routes, dests, radius, buf.readNullable(FriendlyByteBuf::readVarIntArray));
    }

    public static void handle(OpenRoutesMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.route.RouteClientHandler.openRoutes(msg)));
        ctx.get().setPacketHandled(true);
    }
}
