package com.ale.warbandcraft.route;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.TravelStateMessage;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * El viaje del jugador por sus rutas (H13). Mientras dura queda quieto en el origen y no recibe daño
 * ({@link com.ale.warbandcraft.event.RouteEvents}); al terminar aparece en el destino con su caballo y los
 * soldados que lo seguían. En memoria: si se cierra el servidor en medio, el viaje se pierde (no pasa nada).
 */
public final class Travel {
    /** Si el jugador se mueve más que esto del lugar donde arrancó, el viaje se corta. */
    public static double MAX_DRIFT = 2.0;
    /** Soldados que te siguen a esta distancia viajan con vos. */
    public static int ESCORT_RADIUS = 32;

    private record Trip(ServerLevel level, Vec3 start, BlockPos to, Component destName, long startTick, int duration, Path path) {}

    /**
     * H15b: el trazado del viaje (los puntos de las rutas, en orden) y en qué tick se pasa por cada uno. Sirve para
     * saber dónde "va" el jugador y si se cruza con un ejército.
     */
    public record Path(List<Vec3> points, double[] at) {
        /** Dónde va a los {@code tick} ticks de salir. */
        public Vec3 position(double tick) {
            if (points.size() == 1 || tick <= at[0]) return points.get(0);
            for (int i = 1; i < points.size(); i++) {
                if (tick <= at[i]) {
                    double span = at[i] - at[i - 1];
                    return span <= 0 ? points.get(i) : points.get(i - 1).lerp(points.get(i), (tick - at[i - 1]) / span);
                }
            }
            return points.get(points.size() - 1);
        }

        /** En línea recta (sin rutas: las pruebas). */
        public static Path straight(Vec3 from, Vec3 to, int duration) {
            return new Path(List.of(from, to), new double[]{0, duration});
        }

        /**
         * Saliendo de {@code origin} por {@code legs}: cada ruta se recorre en el sentido que toca y su tiempo
         * ({@link Routes#ticks}) se reparte según el largo de cada tramo.
         */
        public static Path of(BlockPos origin, List<RouteData.Route> legs) {
            List<Vec3> pts = new ArrayList<>();
            List<Double> at = new ArrayList<>();
            BlockPos cur = origin;
            double t = 0;
            for (RouteData.Route r : legs) {
                List<BlockPos> raw = new ArrayList<>(r.points());
                if (!r.a().equals(cur)) java.util.Collections.reverse(raw);
                double total = 0;
                for (int i = 1; i < raw.size(); i++) total += horizontal(raw.get(i - 1), raw.get(i));
                int ticks = Routes.ticks(r);
                double d = 0;
                for (int i = 0; i < raw.size(); i++) {
                    if (i > 0) d += horizontal(raw.get(i - 1), raw.get(i));
                    pts.add(Vec3.atBottomCenterOf(raw.get(i)));
                    at.add(t + (total <= 0 ? (i == 0 ? 0 : ticks) : ticks * d / total));
                }
                t += ticks;
                cur = r.other(cur);
            }
            if (pts.isEmpty()) return new Path(List.of(Vec3.atBottomCenterOf(origin)), new double[]{0});
            double[] a = new double[at.size()];
            for (int i = 0; i < a.length; i++) a[i] = at.get(i);
            return new Path(pts, a);
        }

        private static double horizontal(BlockPos a, BlockPos b) {
            double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private static final Map<UUID, Trip> TRIPS = new HashMap<>();

    private Travel() {}

    public static boolean isTraveling(UUID player) {
        return TRIPS.containsKey(player);
    }

    /**
     * Arranca el viaje a {@code to}, saliendo del punto tuyo más cercano a {@code from} (el Campamento o Poste de
     * caminos desde el que se abrió la pantalla; null = donde estás parado). Devuelve null si arrancó, o el motivo.
     */
    @Nullable
    public static Component start(ServerPlayer player, BlockPos to, @Nullable BlockPos from) {
        if (isTraveling(player.getUUID())) return Component.translatable("msg.warbandcraft.travel.already");
        ServerLevel level = player.serverLevel();
        Routes.Node here = Routes.nodeNear(level, player.getUUID(), from != null ? from : player.blockPosition());
        if (here == null) return Component.translatable("msg.warbandcraft.travel.not_at_node", Routes.ENDPOINT_RADIUS);
        Routes.Destination dest = Routes.destination(level, player.getUUID(), here.pos(), to);
        if (dest == null) return Component.translatable("msg.warbandcraft.shipments.no_route");
        if (player.getLastHurtByMob() != null) return Component.translatable("msg.warbandcraft.travel.fighting");
        start(player, to, dest.node().name(), dest.ticks(), Path.of(here.pos(), dest.path()));
        return null;
    }

    /** Arranca sin validar (también lo usan las pruebas). */
    public static void start(ServerPlayer player, BlockPos to, Component destName, int duration) {
        start(player, to, destName, duration, Path.straight(player.position(), Vec3.atCenterOf(to), duration));
    }

    public static void start(ServerPlayer player, BlockPos to, Component destName, int duration, Path path) {
        TRIPS.put(player.getUUID(), new Trip(player.serverLevel(), player.position(), to, destName,
                player.server.getTickCount(), duration, path));
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TravelStateMessage(duration, destName));
    }

    /** Corta el viaje (Esc, se movió, se desconectó). */
    public static void cancel(ServerPlayer player, @Nullable Component reason) {
        if (TRIPS.remove(player.getUUID()) == null) return;
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TravelStateMessage(0, Component.empty()));
        if (reason != null) player.displayClientMessage(reason.copy().withStyle(ChatFormatting.YELLOW), true);
    }

    public static void forget(UUID player) {
        TRIPS.remove(player);
    }

    /** Cada tick: corta los viajes de quien se movió y hace llegar a los que terminaron. */
    public static void tick(MinecraftServer server) {
        if (TRIPS.isEmpty()) return;
        for (Map.Entry<UUID, Trip> e : new ArrayList<>(TRIPS.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            Trip trip = e.getValue();
            if (player == null || player.isRemoved() || player.serverLevel() != trip.level()) {
                TRIPS.remove(e.getKey());
                continue;
            }
            if (player.position().distanceToSqr(trip.start()) > MAX_DRIFT * MAX_DRIFT) {
                cancel(player, Component.translatable("msg.warbandcraft.travel.moved"));
                continue;
            }
            long elapsed = server.getTickCount() - trip.startTick();
            if (elapsed % 20 == 0 && elapsed < trip.duration() && ambush(player, trip, elapsed)) continue;   // H15b
            if (elapsed >= trip.duration()) {
                TRIPS.remove(e.getKey());
                arrive(player, trip.to());
                ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TravelStateMessage(0, Component.empty()));
                player.displayClientMessage(Component.translatable("msg.warbandcraft.travel.arrived", trip.destName())
                        .withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    /**
     * H15b: dónde "va" el jugador (por el trazado de sus rutas, según el tiempo); si ahí hay un ejército
     * enemigo en marcha, el viaje se corta y aparece en ese lugar con su escolta, frente al ejército.
     */
    private static boolean ambush(ServerPlayer player, Trip trip, long elapsed) {
        ServerLevel level = trip.level();
        Vec3 at = trip.path().position(elapsed);
        com.ale.warbandcraft.invasion.Ambush.Target t = com.ale.warbandcraft.invasion.Ambush.find(level, player.getUUID(), at);
        if (t == null) return false;
        TRIPS.remove(player.getUUID());
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new TravelStateMessage(0, Component.empty()));
        int x = (int) Math.floor(at.x), z = (int) Math.floor(at.z);
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos spot = new BlockPos(x, y, z);
        arrive(player, spot);
        com.ale.warbandcraft.invasion.Ambush.spring(level, player, spot, t);
        return true;
    }

    /**
     * Lleva al jugador (con su caballo) y a los soldados suyos que lo siguen a ≤ {@link #ESCORT_RADIUS} bloques
     * hasta al lado de {@code to}. Devuelve cuántos soldados viajaron.
     */
    public static int arrive(ServerPlayer player, BlockPos to) {
        ServerLevel level = player.serverLevel();
        List<AbstractRecruitEntity> escort = level.getEntitiesOfClass(AbstractRecruitEntity.class,
                new AABB(player.blockPosition()).inflate(ESCORT_RADIUS),
                r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), player.getUUID()) && Barracks.isWithOwner(r));

        level.getChunk(to);   // fuerza que el destino exista y esté cargado
        Vec3 spot = Barracks.standable(level, to, null, to);
        Entity mount = player.getVehicle();
        player.stopRiding();
        player.teleportTo(level, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        if (mount instanceof AbstractHorse horse) {
            horse.teleportTo(spot.x, spot.y, spot.z);
            player.startRiding(horse, true);
        }

        int i = 0;
        for (AbstractRecruitEntity s : escort) {
            Vec3 p = Barracks.standable(level, BlockPos.containing(Routes.post(BlockPos.containing(spot), i++, escort.size())), null, to);
            Entity moved = s.getVehicle() instanceof AbstractHorse h ? h : s;
            moved.teleportTo(p.x, p.y, p.z);   // el caballo se lleva a su jinete
            moved.resetFallDistance();
            s.getNavigation().stop();
            s.setTarget(null);
        }
        return escort.size();
    }
}
