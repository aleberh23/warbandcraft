package com.ale.warbandcraft.route;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageCamps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Rutas y viaje (H13), lado servidor: tus puntos (Campamentos, aldeas y castillos conquistados), registrar una
 * ruta dibujada en el mapa de Recruits, qué destinos se alcanzan encadenando rutas y los envíos.
 * El viaje del jugador está en {@link Travel}. Los datos viven en {@link RouteData}.
 */
public final class Routes {
    /** A cuánto de un punto tuyo tiene que empezar/terminar una ruta, y a cuánto tenés que estar para viajar. */
    public static int ENDPOINT_RADIUS = 32;
    /** Velocidad caminando de un jugador (bloques por segundo). */
    public static double WALK_SPEED = 4.3;
    /** Sin camino construido el viaje tarda esta parte de lo que tardarías a pie. */
    public static double NO_ROAD_FACTOR = 0.2;
    /** Con camino construido (H14). */
    public static double ROAD_FACTOR = 0.1;
    /** Ningún tramo tarda menos que esto (ticks). */
    public static int MIN_LEG_TICKS = 20 * 2;
    /** Rutas más largas que esto no se aceptan (bloques). */
    public static int MAX_LENGTH = 6000;
    /** Cada cuántos bloques del trazado se revisa que el terreno esté explorado. */
    private static int EXPLORE_STEP = 8;

    private Routes() {}

    // ------------------------------------------------------------------ puntos

    public enum Kind { CAMP, VILLAGE, CASTLE }

    /** Un punto tuyo: un Campamento o el centro de una aldea/castillo conquistado. */
    public record Node(BlockPos pos, Kind kind, Component name) {}

    /** Tus puntos en este mundo. */
    public static List<Node> nodes(ServerLevel level, UUID owner) {
        List<Node> out = new ArrayList<>();
        CampRegistry camps = CampRegistry.get(level);
        for (BlockPos p : camps.campsOf(owner)) {
            if (VillageCamps.villageOf(level, p) == null) out.add(new Node(p, Kind.CAMP, camps.displayName(p)));   // H16: el de una aldea es la aldea
        }
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) out.add(villageNode(level, v));
        }
        return out;
    }

    private static Node villageNode(ServerLevel level, VillageData.Village v) {
        Faction f = FactionManager.INSTANCE.get(v.faction());
        String of = f == null ? v.faction().getPath() : f.ofName();
        String key = v.castle() ? "gui.warbandcraft.routes.castle" : "gui.warbandcraft.routes.village";
        return new Node(v.center(), v.castle() ? Kind.CASTLE : Kind.VILLAGE,
                VillageCamps.displayName(level, v, Component.translatable(key, of, v.center().getX(), v.center().getZ())));
    }

    /** El punto tuyo en {@code pos} exacto (o null si ya no es tuyo). */
    @Nullable
    public static Node node(ServerLevel level, UUID owner, BlockPos pos) {
        VillageData.Village vc = VillageCamps.villageOf(level, pos);   // H16
        if (vc != null) return com.ale.warbandcraft.faction.Allies.same(owner, vc.owner()) ? villageNode(level, vc) : null;
        CampRegistry camps = CampRegistry.get(level);
        if (camps.isCampOf(owner, pos)) return new Node(pos, Kind.CAMP, camps.displayName(pos));
        VillageData.Village v = VillageData.get(level).at(pos);
        return v != null && com.ale.warbandcraft.faction.Allies.same(owner, v.owner()) ? villageNode(level, v) : null;
    }

    /** El punto tuyo más cercano a {@code pos} a ≤ {@link #ENDPOINT_RADIUS} bloques (en horizontal), o null. */
    @Nullable
    public static Node nodeNear(ServerLevel level, UUID owner, BlockPos pos) {
        VillageData.Village vc = VillageCamps.villageOf(level, pos);   // H16: desde el Campamento de una aldea, la aldea
        if (vc != null && com.ale.warbandcraft.faction.Allies.same(owner, vc.owner())) return villageNode(level, vc);
        Node best = null;
        double bestSq = (double) ENDPOINT_RADIUS * ENDPOINT_RADIUS;
        for (Node n : nodes(level, owner)) {
            double d = VillageData.horizontalDistSq(n.pos(), pos);
            if (d <= bestSq) {
                bestSq = d;
                best = n;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ registrar

    /**
     * Valida y guarda una ruta dibujada en el mapa de Recruits. Devuelve null si quedó registrada, o el motivo
     * por el que no.
     */
    @Nullable
    public static Component register(ServerLevel level, UUID owner, UUID id, String name, List<BlockPos> points) {
        if (points.size() < 2) return Component.translatable("msg.warbandcraft.routes.too_short");
        Node a = nodeNear(level, owner, points.get(0));
        Node b = nodeNear(level, owner, points.get(points.size() - 1));
        if (a == null || b == null) return Component.translatable("msg.warbandcraft.routes.no_endpoints", ENDPOINT_RADIUS);
        if (a.pos().equals(b.pos())) return Component.translatable("msg.warbandcraft.routes.same_endpoint");
        int length = length(points);
        if (length > MAX_LENGTH) return Component.translatable("msg.warbandcraft.routes.too_long", length, MAX_LENGTH);
        BlockPos unexplored = firstUnexplored(level, points);
        if (unexplored != null) {
            return Component.translatable("msg.warbandcraft.routes.unexplored", unexplored.getX(), unexplored.getZ());
        }
        RouteData data = RouteData.get(level);
        RouteData.Route old = data.route(id);
        if (old != null && !old.owner().equals(owner)) id = UUID.randomUUID();
        String clean = name == null || name.isBlank() ? "Ruta" : name.strip();
        if (clean.length() > 32) clean = clean.substring(0, 32);
        // H14: el camino solo sigue si es el mismo trazado; si no, se corta la obra
        boolean same = old != null && old.points().equals(points);
        if (old != null && !same) Roads.cancel(level, id);
        data.put(new RouteData.Route(id, owner, clean, List.copyOf(points), a.pos(), b.pos(), length, same && old.road()));
        return null;
    }

    /** Largo del trazado en bloques (en horizontal). */
    public static int length(List<BlockPos> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += Math.sqrt(VillageData.horizontalDistSq(points.get(i - 1), points.get(i)));
        return (int) Math.round(total);
    }

    /** El primer lugar del trazado que pasa por terreno sin explorar, o null si todo está explorado. */
    @Nullable
    public static BlockPos firstUnexplored(ServerLevel level, List<BlockPos> points) {
        Set<Long> seen = new HashSet<>();
        for (BlockPos p : sample(points)) {
            ChunkPos c = new ChunkPos(p);
            if (seen.add(c.toLong()) && !explored(level, c)) return p;
        }
        return null;
    }

    /** Puntos cada {@link #EXPLORE_STEP} bloques a lo largo del trazado. */
    private static List<BlockPos> sample(List<BlockPos> points) {
        List<BlockPos> out = new ArrayList<>();
        out.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            BlockPos p = points.get(i - 1), q = points.get(i);
            double d = Math.sqrt(VillageData.horizontalDistSq(p, q));
            int steps = Math.max(1, (int) Math.ceil(d / EXPLORE_STEP));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                out.add(BlockPos.containing(p.getX() + (q.getX() - p.getX()) * t, p.getY(), p.getZ() + (q.getZ() - p.getZ()) * t));
            }
        }
        return out;
    }

    /**
     * Explorado = el chunk ya fue generado del todo (Minecraft solo genera chunks cerca de algún jugador).
     * Cargado ahora, o guardado en disco con estado "full".
     */
    public static boolean explored(ServerLevel level, ChunkPos c) {
        if (level.hasChunk(c.x, c.z)) return true;
        try {
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(c).join();
            return tag.isPresent() && ChunkSerializer.getChunkTypeFromTag(tag.get()) == ChunkStatus.ChunkType.LEVELCHUNK;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ destinos

    /** Cuánto tarda (ticks) recorrer una ruta. */
    public static int ticks(RouteData.Route r) {
        double factor = r.road() ? ROAD_FACTOR : NO_ROAD_FACTOR;
        return Math.max(MIN_LEG_TICKS, (int) Math.round(r.length() / WALK_SPEED * factor * 20));
    }

    /** Un destino alcanzable: el punto, cuánto tarda (sumando tramos) y por cuántas rutas pasa. */
    public record Destination(Node node, int ticks, int legs, List<RouteData.Route> path) {}

    /** Ruta activa: sus dos extremos siguen siendo tuyos. */
    public static boolean active(ServerLevel level, RouteData.Route r) {
        return node(level, r.owner(), r.a()) != null && node(level, r.owner(), r.b()) != null;
    }

    /**
     * Los puntos a los que se llega desde {@code origin} encadenando tus rutas activas, por el camino más rápido
     * (sin el origen), ordenados por tiempo.
     */
    public static List<Destination> reachable(ServerLevel level, UUID owner, BlockPos origin) {
        origin = VillageCamps.canonical(level, origin);   // H16
        Map<Long, Node> nodes = new HashMap<>();
        for (Node n : nodes(level, owner)) nodes.put(n.pos().asLong(), n);
        if (!nodes.containsKey(origin.asLong())) return List.of();

        Map<Long, List<RouteData.Route>> edges = new HashMap<>();
        for (RouteData.Route r : RouteData.get(level).routesOf(owner)) {
            if (!nodes.containsKey(r.a().asLong()) || !nodes.containsKey(r.b().asLong())) continue;
            edges.computeIfAbsent(r.a().asLong(), k -> new ArrayList<>()).add(r);
            edges.computeIfAbsent(r.b().asLong(), k -> new ArrayList<>()).add(r);
        }

        Map<Long, int[]> best = new HashMap<>(); // pos → {ticks, tramos}
        Map<Long, RouteData.Route> via = new HashMap<>();   // H15b: por qué ruta se llegó (para el trazado del viaje)
        best.put(origin.asLong(), new int[]{0, 0});
        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) -> Long.compare(x[1], y[1]));
        queue.add(new long[]{origin.asLong(), 0});
        while (!queue.isEmpty()) {
            long[] cur = queue.poll();
            int[] here = best.get(cur[0]);
            if (cur[1] > here[0]) continue;
            for (RouteData.Route r : edges.getOrDefault(cur[0], List.of())) {
                long next = r.other(BlockPos.of(cur[0])).asLong();
                int t = here[0] + ticks(r);
                int[] known = best.get(next);
                if (known == null || t < known[0]) {
                    best.put(next, new int[]{t, here[1] + 1});
                    via.put(next, r);
                    queue.add(new long[]{next, t});
                }
            }
        }
        List<Destination> out = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : best.entrySet()) {
            if (e.getKey() == origin.asLong()) continue;
            out.add(new Destination(nodes.get(e.getKey()), e.getValue()[0], e.getValue()[1], pathTo(via, origin, e.getKey())));
        }
        out.sort((x, y) -> Integer.compare(x.ticks(), y.ticks()));
        return out;
    }

    /** Las rutas, en orden, para ir de {@code origin} a {@code to} (según lo que anotó Dijkstra). */
    private static List<RouteData.Route> pathTo(Map<Long, RouteData.Route> via, BlockPos origin, long to) {
        List<RouteData.Route> out = new ArrayList<>();
        long cur = to;
        while (cur != origin.asLong() && via.containsKey(cur) && out.size() < 1000) {
            RouteData.Route r = via.get(cur);
            out.add(0, r);
            cur = r.other(BlockPos.of(cur)).asLong();
        }
        return out;
    }

    @Nullable
    public static Destination destination(ServerLevel level, UUID owner, BlockPos origin, BlockPos to) {
        for (Destination d : reachable(level, owner, origin)) if (d.node().pos().equals(to)) return d;
        return null;
    }

    // ------------------------------------------------------------------ envíos

    /** Dónde comen y se guardan los soldados que llegan a {@code to}: ese Campamento, o el tuyo más cercano a la aldea. */
    @Nullable
    public static BlockPos homeCamp(ServerLevel level, UUID owner, BlockPos to) {
        BlockPos own = VillageCamps.campFor(level, to);   // H16: la aldea tiene su propio barracón
        if (!own.equals(to)) return own;
        CampRegistry camps = CampRegistry.get(level);
        return camps.isCampOf(owner, to) ? to : camps.nearestCamp(owner, to);
    }

    /** Soldados en camino que van a terminar en el barracón de {@code home}. */
    public static int inTransitTo(ServerLevel level, UUID owner, BlockPos home) {
        int n = 0;
        for (RouteData.Shipment s : RouteData.get(level).shipmentsOf(owner)) {
            if (!s.soldiers().isEmpty() && home.equals(homeCamp(level, owner, s.to()))) n += s.soldiers().size();
        }
        return n;
    }

    /** Lugar libre en el barracón que recibe a los que van a {@code to} (contando lo que ya va en camino). */
    public static int freeAt(ServerLevel level, UUID owner, BlockPos to) {
        BlockPos home = homeCamp(level, owner, to);
        if (home == null) return 0;
        return Math.max(0, Barracks.capacity(level, home) - BarracksData.get(level).count(home) - inTransitTo(level, owner, home));
    }

    /**
     * Manda un envío desde el Campamento {@code from}: {@code rows} = cuántos soldados de cada fila del barracón
     * (clave de fila → cantidad), {@code items} = lo de la caja de carga. Devuelve null si salió, o el motivo.
     * Si salió, las fichas ya no están en el barracón (los ítems los tiene que sacar quien llama).
     */
    @Nullable
    public static Component send(ServerLevel level, UUID owner, BlockPos from, BlockPos to, Map<String, Integer> rows,
                                 List<ItemStack> items) {
        if (!CampRegistry.get(level).isCampOf(owner, from)) return Component.translatable("msg.warbandcraft.shipments.no_origin");
        Destination dest = destination(level, owner, from, to);
        if (dest == null) return Component.translatable("msg.warbandcraft.shipments.no_route");
        boolean toCamp = dest.node().kind() == Kind.CAMP || !VillageCamps.campFor(level, to).equals(to);   // H16: aldea con cofre
        List<ItemStack> cargo = new ArrayList<>();
        for (ItemStack s : items) if (!s.isEmpty()) cargo.add(s.copy());
        if (!cargo.isEmpty() && !toCamp) return Component.translatable("msg.warbandcraft.shipments.items_to_village");

        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(from);
        List<BarracksData.Soldier> picked = new ArrayList<>();
        for (Map.Entry<String, Integer> e : rows.entrySet()) {
            int want = e.getValue();
            if (want <= 0 || b == null) continue;
            for (boolean guards : new boolean[]{false, true}) {   // primero los que no tienen puesto
                for (BarracksData.Soldier s : b.soldiers) {
                    if (want <= 0) break;
                    if (s.guard == guards && s.rowKey().equals(e.getKey()) && !picked.contains(s)) {
                        picked.add(s);
                        want--;
                    }
                }
            }
        }
        if (picked.isEmpty() && cargo.isEmpty()) return Component.translatable("msg.warbandcraft.shipments.empty");
        if (!picked.isEmpty()) {
            if (homeCamp(level, owner, to) == null) return Component.translatable("msg.warbandcraft.shipments.no_home");
            int free = freeAt(level, owner, to);
            if (picked.size() > free) return Component.translatable("msg.warbandcraft.shipments.full", free);
        }

        List<BarracksData.Soldier> soldiers = new ArrayList<>();
        for (BarracksData.Soldier s : picked) {
            b.soldiers.remove(s);
            soldiers.add(s);
        }
        if (!picked.isEmpty()) data.changed();
        long now = level.getGameTime();
        RouteData.get(level).add(new RouteData.Shipment(UUID.randomUUID(), owner, from, to, toCamp, now, now + dest.ticks(),
                soldiers, cargo));
        return null;
    }

    /** Entrega los envíos que ya llegaron (se llama cada segundo). */
    public static void tickShipments(ServerLevel level) {
        RouteData data = RouteData.get(level);
        long now = level.getGameTime();
        for (RouteData.Shipment s : new ArrayList<>(data.shipments())) {
            if (now < s.arriveTick()) continue;
            s = com.ale.warbandcraft.dungeon.Bandits.ambush(level, s);   // H25: bandidos cerca del camino
            RouteData.Shipment rest = s;
            if (!s.soldiers().isEmpty()) {
                deliverSoldiers(level, s);
                rest = new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(), s.arriveTick(),
                        List.of(), s.items());
            }
            if (!rest.items().isEmpty() && !deliverItems(level, rest)) {
                if (rest != s) data.replace(rest);   // los ítems esperan a que se cargue el Campamento
                continue;
            }
            data.removeShipment(s.id());
        }
    }

    /** Los soldados entran al barracón del destino; si es una aldea/castillo, como guardias alrededor del centro. */
    private static void deliverSoldiers(ServerLevel level, RouteData.Shipment s) {
        UUID owner = s.owner();
        Node dest = node(level, owner, s.to());
        BlockPos home = dest != null ? homeCamp(level, owner, s.to()) : CampRegistry.get(level).nearestCamp(owner, s.to());
        boolean guards = dest != null && dest.kind() != Kind.CAMP;
        if (home == null) {
            // No te queda ningún Campamento: aparecen en el destino (o donde iban), quietos.
            BlockPos at = s.to();
            level.getChunk(at);
            for (int i = 0; i < s.soldiers().size(); i++) {
                BarracksData.Soldier sheet = s.soldiers().get(i);
                Vec3 post = post(at, i, s.soldiers().size());
                Barracks.create(level, withPost(sheet, post), Barracks.standable(level, BlockPos.containing(post), null, at), null, -1, 0);
            }
            return;
        }
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.getOrCreate(home, owner);
        if (b.soldiers.isEmpty()) {
            b.lastFedDay = Barracks.today(level);
            b.hungryDays = 0;
        }
        for (int i = 0; i < s.soldiers().size(); i++) {
            BarracksData.Soldier sheet = s.soldiers().get(i);
            b.soldiers.add(guards ? withPost(sheet, post(s.to(), i, s.soldiers().size())) : withoutPost(sheet));
        }
        data.changed();

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            Component where = dest != null ? dest.name() : CampRegistry.get(level).displayName(home);
            player.sendSystemMessage(Component.translatable(guards ? "msg.warbandcraft.shipments.arrived_guards"
                    : "msg.warbandcraft.shipments.arrived_soldiers", s.soldiers().size(), where).withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * Los ítems entran al almacén del Campamento de destino cuando está cargado (lo que no entra, al más cercano
     * como el botín). Si el Campamento ya no está, van directo a los demás. Devuelve false si todavía espera.
     */
    private static boolean deliverItems(ServerLevel level, RouteData.Shipment s) {
        UUID owner = s.owner();
        CampRegistry camps = CampRegistry.get(level);
        List<ItemStack> rest = new ArrayList<>();
        Component where;
        BlockPos target = VillageCamps.campFor(level, s.to());   // H16: a una aldea, a su Campamento
        if (camps.isCampOf(owner, target)) {
            if (!level.isLoaded(target)) return false;
            if (!(level.getBlockEntity(target) instanceof CampBlockEntity camp)) return false;
            for (ItemStack stack : s.items()) {
                ItemStack left = camp.insert(stack.copy());
                if (!left.isEmpty()) rest.add(left);
            }
            where = camps.displayName(target);
        } else {
            rest.addAll(s.items());
            where = Component.translatable("container.warbandcraft.camp");
        }
        if (!rest.isEmpty()) camps.deliver(level, owner, rest, s.to());
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.shipments.arrived_items", s.items().size(), where)
                    .withStyle(ChatFormatting.GREEN));
        }
        return true;
    }

    /** Puesto de guardia número {@code i} de {@code n}: anillos alrededor del centro de la aldea. */
    public static Vec3 post(BlockPos center, int i, int n) {
        int ring = i / 8;
        int inRing = Math.min(8, n - ring * 8);
        double angle = 2 * Math.PI * (i % 8) / Math.max(1, inRing);
        double r = 4 + ring * 2.5;
        return new Vec3(center.getX() + 0.5 + Math.cos(angle) * r, center.getY(), center.getZ() + 0.5 + Math.sin(angle) * r);
    }

    private static final int BACK_TO_POS = 3;

    private static BarracksData.Soldier withPost(BarracksData.Soldier s, Vec3 post) {
        return new BarracksData.Soldier(s.entity, s.horse, true, post, s.yRot, BACK_TO_POS, s.name, s.kit, s.tier, s.xpLevel);
    }

    private static BarracksData.Soldier withoutPost(BarracksData.Soldier s) {
        return new BarracksData.Soldier(s.entity, s.horse, false, null, s.yRot, s.followState, s.name, s.kit, s.tier, s.xpLevel);
    }

    /** Para la pantalla: cuánto de cada fila (clave de fila → cantidad) hay en el barracón. */
    public static Map<String, Integer> rowCounts(@Nullable BarracksData.Barracks b) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (b != null) for (BarracksData.Soldier s : b.soldiers) out.merge(s.rowKey(), 1, Integer::sum);
        return out;
    }
}
