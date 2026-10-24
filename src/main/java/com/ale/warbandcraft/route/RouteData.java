package com.ale.warbandcraft.route;

import com.ale.warbandcraft.camp.BarracksData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rutas registradas y envíos en camino (H13), por dimensión como {@link com.ale.warbandcraft.camp.CampRegistry}.
 * La ruta se dibuja en el mapa de Recruits (que la guarda en la compu del jugador); acá queda la copia que
 * validó el servidor.
 */
public class RouteData extends SavedData {
    private static final String FILE = "warbandcraft_routes";

    /**
     * Una ruta registrada.
     *
     * @param id     el mismo id de la ruta de Recruits (registrarla de nuevo la reemplaza)
     * @param points los puntos tal como se dibujaron (el primero toca {@code a}, el último {@code b})
     * @param a      punto tuyo de un extremo (Campamento o centro de aldea/castillo)
     * @param b      punto tuyo del otro extremo
     * @param length largo del trazado en bloques
     * @param road   tiene camino construido (H14)
     */
    public record Route(UUID id, UUID owner, String name, List<BlockPos> points, BlockPos a, BlockPos b,
                        int length, boolean road) {
        public boolean touches(BlockPos node) {
            return a.equals(node) || b.equals(node);
        }

        public BlockPos other(BlockPos node) {
            return a.equals(node) ? b : a;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putUUID("Owner", owner);
            t.putString("Name", name);
            t.put("Points", new LongArrayTag(points.stream().mapToLong(BlockPos::asLong).toArray()));
            t.putLong("A", a.asLong());
            t.putLong("B", b.asLong());
            t.putInt("Length", length);
            t.putBoolean("Road", road);
            return t;
        }

        static Route load(CompoundTag t) {
            List<BlockPos> points = new ArrayList<>();
            for (long l : t.getLongArray("Points")) points.add(BlockPos.of(l));
            return new Route(t.getUUID("Id"), t.getUUID("Owner"), t.getString("Name"), points,
                    BlockPos.of(t.getLong("A")), BlockPos.of(t.getLong("B")), t.getInt("Length"), t.getBoolean("Road"));
        }
    }

    /**
     * Un envío en camino: soldados (como fichas del barracón) e ítems que llegan a {@code to} en {@code arriveTick}.
     *
     * @param toCamp el destino era un Campamento al mandarlo (si no, una aldea o castillo)
     */
    public record Shipment(UUID id, UUID owner, BlockPos from, BlockPos to, boolean toCamp, long departTick, long arriveTick,
                           List<BarracksData.Soldier> soldiers, List<ItemStack> items) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putUUID("Owner", owner);
            t.putLong("From", from.asLong());
            t.putLong("To", to.asLong());
            t.putBoolean("ToCamp", toCamp);
            t.putLong("Depart", departTick);
            t.putLong("Arrive", arriveTick);
            ListTag s = new ListTag();
            for (BarracksData.Soldier soldier : soldiers) s.add(soldier.save());
            t.put("Soldiers", s);
            ListTag i = new ListTag();
            for (ItemStack stack : items) i.add(stack.save(new CompoundTag()));
            t.put("Items", i);
            return t;
        }

        static Shipment load(CompoundTag t) {
            List<BarracksData.Soldier> soldiers = new ArrayList<>();
            for (Tag x : t.getList("Soldiers", Tag.TAG_COMPOUND)) soldiers.add(BarracksData.Soldier.load((CompoundTag) x));
            List<ItemStack> items = new ArrayList<>();
            for (Tag x : t.getList("Items", Tag.TAG_COMPOUND)) {
                ItemStack s = ItemStack.of((CompoundTag) x);
                if (!s.isEmpty()) items.add(s);
            }
            return new Shipment(t.getUUID("Id"), t.getUUID("Owner"), BlockPos.of(t.getLong("From")), BlockPos.of(t.getLong("To")),
                    t.getBoolean("ToCamp"), t.getLong("Depart"), t.getLong("Arrive"), soldiers, items);
        }
    }

    private final Map<UUID, Route> routes = new LinkedHashMap<>();
    private final Map<UUID, Shipment> shipments = new LinkedHashMap<>();

    /**
     * H14: la obra de un camino. Los bloques van en orden a lo largo de la ruta; cada uno se pone cuando ya le tocó
     * (por tiempo) y su chunk está cargado, así un tramo lejano aparece al pasar por ahí.
     */
    public static final class RoadJob {
        public final UUID route;
        public final long startTick;
        public final int durationTicks;
        public final long[] positions;
        public final int[] states;
        public final List<net.minecraft.world.level.block.state.BlockState> palette;
        public final java.util.BitSet placed;
        /** El primero que falta poner. */
        public int first;
        /** Ya se avisó que la ruta tiene camino (pasó el tiempo de obra). */
        public boolean done;
        @Nullable
        public UUID builder;

        public RoadJob(UUID route, long startTick, int durationTicks, long[] positions, int[] states,
                       List<net.minecraft.world.level.block.state.BlockState> palette, java.util.BitSet placed) {
            this.route = route;
            this.startTick = startTick;
            this.durationTicks = durationTicks;
            this.positions = positions;
            this.states = states;
            this.palette = palette;
            this.placed = placed;
        }

        public int size() {
            return positions.length;
        }

        /** Cuántos ya deberían estar puestos en {@code now} (por tiempo). */
        public int due(long now) {
            if (durationTicks <= 0) return size();
            double f = Math.min(1.0, Math.max(0.0, (now - startTick) / (double) durationTicks));
            return (int) Math.ceil(f * size());
        }

        public int percent(long now) {
            return durationTicks <= 0 ? 100 : (int) Math.min(100, Math.max(0, (now - startTick) * 100 / durationTicks));
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Route", route);
            t.putLong("Start", startTick);
            t.putInt("Duration", durationTicks);
            t.putLongArray("Positions", positions);
            t.putIntArray("States", states);
            ListTag pal = new ListTag();
            for (net.minecraft.world.level.block.state.BlockState s : palette) pal.add(net.minecraft.nbt.NbtUtils.writeBlockState(s));
            t.put("Palette", pal);
            t.putLongArray("Placed", placed.toLongArray());
            t.putInt("First", first);
            t.putBoolean("Done", done);
            if (builder != null) t.putUUID("Builder", builder);
            return t;
        }

        static RoadJob load(CompoundTag t) {
            List<net.minecraft.world.level.block.state.BlockState> palette = new ArrayList<>();
            for (Tag x : t.getList("Palette", Tag.TAG_COMPOUND)) {
                palette.add(net.minecraft.nbt.NbtUtils.readBlockState(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), (CompoundTag) x));
            }
            RoadJob j = new RoadJob(t.getUUID("Route"), t.getLong("Start"), t.getInt("Duration"), t.getLongArray("Positions"),
                    t.getIntArray("States"), palette, java.util.BitSet.valueOf(t.getLongArray("Placed")));
            j.first = t.getInt("First");
            j.done = t.getBoolean("Done");
            if (t.hasUUID("Builder")) j.builder = t.getUUID("Builder");
            return j;
        }
    }

    private final Map<UUID, RoadJob> roads = new LinkedHashMap<>();

    @Nullable
    public RoadJob roadJob(UUID route) {
        return roads.get(route);
    }

    public Collection<RoadJob> roadJobs() {
        return roads.values();
    }

    public void putRoadJob(RoadJob job) {
        roads.put(job.route, job);
        setDirty();
    }

    @Nullable
    public RoadJob removeRoadJob(UUID route) {
        RoadJob j = roads.remove(route);
        if (j != null) setDirty();
        return j;
    }

    /** Marca (o desmarca) que la ruta tiene camino construido. */
    public void setRoad(UUID id, boolean road) {
        Route r = routes.get(id);
        if (r == null || r.road() == road) return;
        routes.put(id, new Route(r.id(), r.owner(), r.name(), r.points(), r.a(), r.b(), r.length(), road));
        setDirty();
    }

    public void changed() {
        setDirty();
    }

    public static RouteData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RouteData::load, RouteData::new, FILE);
    }

    public void put(Route route) {
        routes.put(route.id(), route);
        setDirty();
    }

    @Nullable
    public Route route(UUID id) {
        return routes.get(id);
    }

    public boolean remove(UUID id, UUID owner) {
        Route r = routes.get(id);
        if (r == null || !com.ale.warbandcraft.faction.Allies.same(r.owner(), owner)) return false;
        routes.remove(id);
        setDirty();
        return true;
    }

    public List<Route> routesOf(UUID owner) {
        List<Route> out = new ArrayList<>();
        for (Route r : routes.values()) if (com.ale.warbandcraft.faction.Allies.same(r.owner(), owner)) out.add(r);
        return out;
    }

    public void add(Shipment shipment) {
        shipments.put(shipment.id(), shipment);
        setDirty();
    }

    public void removeShipment(UUID id) {
        if (shipments.remove(id) != null) setDirty();
    }

    /** Reemplaza un envío (p. ej. ya entregó los soldados y solo esperan los ítems). */
    public void replace(Shipment shipment) {
        shipments.put(shipment.id(), shipment);
        setDirty();
    }

    public Collection<Shipment> shipments() {
        return shipments.values();
    }

    public List<Shipment> shipmentsOf(UUID owner) {
        List<Shipment> out = new ArrayList<>();
        for (Shipment s : shipments.values()) if (com.ale.warbandcraft.faction.Allies.same(s.owner(), owner)) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------ guardado

    public static RouteData load(CompoundTag tag) {
        RouteData d = new RouteData();
        for (Tag t : tag.getList("Routes", Tag.TAG_COMPOUND)) {
            Route r = Route.load((CompoundTag) t);
            d.routes.put(r.id(), r);
        }
        for (Tag t : tag.getList("Shipments", Tag.TAG_COMPOUND)) {
            Shipment s = Shipment.load((CompoundTag) t);
            d.shipments.put(s.id(), s);
        }
        for (Tag t : tag.getList("Roads", Tag.TAG_COMPOUND)) {
            RoadJob j = RoadJob.load((CompoundTag) t);
            d.roads.put(j.route, j);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag r = new ListTag();
        for (Route route : routes.values()) r.add(route.save());
        tag.put("Routes", r);
        ListTag s = new ListTag();
        for (Shipment shipment : shipments.values()) s.add(shipment.save());
        tag.put("Shipments", s);
        ListTag roadList = new ListTag();
        for (RoadJob j : roads.values()) roadList.add(j.save());
        tag.put("Roads", roadList);
        return tag;
    }
}
