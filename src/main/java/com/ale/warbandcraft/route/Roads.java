package com.ale.warbandcraft.route;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.BuilderNpc;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construir caminos (H14): encargar el camino de una ruta, la obra que avanza por tramos y la ruta que queda "con
 * camino" (viaje ×0,1, {@link Routes#ROAD_FACTOR}). Los números están acá arriba.
 */
public final class Roads {
    /** Costo: 1 esmeralda cada tantos bloques de ruta, con un mínimo. */
    public static int BLOCKS_PER_EMERALD = 10, MIN_COST = 5;
    /** Ritmo de obra: bloques de ruta por segundo, y la duración entre 1 y 5 minutos. */
    public static int ROUTE_BLOCKS_PER_SECOND = 5;
    public static int MIN_TICKS = 20 * 60, MAX_TICKS = 20 * 300;
    /** Cada cuánto se ponen bloques (el constructor se mueve cada tick). */
    private static int PLACE_INTERVAL = 10;

    private Roads() {}

    public static int cost(RouteData.Route r) {
        return Math.max(MIN_COST, Mth.ceil(r.length() / (double) BLOCKS_PER_EMERALD));
    }

    // ------------------------------------------------------------------ encargar

    /** Encarga el camino de tu ruta {@code id} (las pruebas; sin lugar desde donde se paga primero). */
    public static Component order(ServerPlayer player, UUID id) {
        return order(player, id, null);
    }

    /**
     * Encarga el camino de tu ruta {@code id}: valida, cobra, mide el terreno y arranca la obra. Se encarga desde un
     * punto tuyo ({@code from}: un Campamento o el centro de una aldea/castillo) y la ruta tiene que salir de ahí; se
     * paga primero de ese almacén, después del inventario y después de los otros Campamentos cargados.
     */
    public static Component order(ServerPlayer player, UUID id, @Nullable BlockPos from) {
        ServerLevel level = player.serverLevel();
        RouteData data = RouteData.get(level);
        RouteData.Route r = data.route(id);
        if (r == null || !com.ale.warbandcraft.faction.Allies.same(r.owner(), player.getUUID())) return error("msg.warbandcraft.road.not_yours");
        if (from != null && !touches(level, r, from)) return error("msg.warbandcraft.road.not_here");
        if (r.road()) return error("msg.warbandcraft.road.done");
        if (data.roadJob(id) != null) return error("msg.warbandcraft.road.busy");
        if (!Routes.active(level, r)) return error("msg.warbandcraft.road.inactive");
        int cost = cost(r);
        int inHand = player.getInventory().countItem(Items.EMERALD);
        int inCamps = emeraldsInCamps(level, player.getUUID());
        if (inHand + inCamps < cost) {
            return Component.translatable("msg.warbandcraft.road.no_money", cost, inHand, inCamps).withStyle(ChatFormatting.RED);
        }
        RoadPlanner.Result plan = RoadPlanner.plan(level, r.points());
        if (plan.plan.size() == 0) return error("msg.warbandcraft.road.no_room");
        pay(level, player, cost, from == null ? null : storeOf(level, from));
        start(level, r, plan.plan);
        return Component.translatable("msg.warbandcraft.road.started", r.name(), cost, plan.bridges, plan.tunnels, plan.docks)
                .withStyle(ChatFormatting.GREEN);
    }

    /** Tus rutas que salen del punto tuyo {@code node} (Campamento o centro de aldea/castillo). */
    public static List<RouteData.Route> routesAt(ServerLevel level, UUID owner, BlockPos node) {
        List<RouteData.Route> out = new ArrayList<>();
        for (RouteData.Route r : RouteData.get(level).routesOf(owner)) if (touches(level, r, node)) out.add(r);
        return out;
    }

    /** Un extremo de la ruta es el punto {@code node}. */
    public static boolean touches(ServerLevel level, RouteData.Route r, BlockPos node) {
        BlockPos at = com.ale.warbandcraft.village.VillageCamps.canonical(level, node);
        Routes.Node a = Routes.nodeNear(level, r.owner(), r.a()), b = Routes.nodeNear(level, r.owner(), r.b());
        return a != null && a.pos().equals(at) || b != null && b.pos().equals(at);
    }

    /** El Campamento que guarda las cosas del punto: el mismo, o el de la aldea/castillo (H16). */
    @Nullable
    private static BlockPos storeOf(ServerLevel level, BlockPos node) {
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageData.get(level).at(node);
        return v != null ? com.ale.warbandcraft.village.VillageCamps.campOf(level, v) : node;
    }

    private static Component error(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    /** Esmeraldas en tus Campamentos cargados. */
    private static int emeraldsInCamps(ServerLevel level, UUID owner) {
        int n = 0;
        for (BlockPos p : CampRegistry.get(level).campsOf(owner)) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof CampBlockEntity camp) n += camp.count(Items.EMERALD);
        }
        return n;
    }

    /** Cobra: primero del Campamento {@code first} (si está cargado), después del inventario y el resto de los Campamentos cargados. */
    private static void pay(ServerLevel level, ServerPlayer player, int cost, @Nullable BlockPos first) {
        if (first != null && level.isLoaded(first) && level.getBlockEntity(first) instanceof CampBlockEntity camp) {
            cost -= camp.take(Items.EMERALD, cost);
        }
        for (int i = 0; i < player.getInventory().getContainerSize() && cost > 0; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(cost, s.getCount());
            s.shrink(take);
            cost -= take;
        }
        player.getInventory().setChanged();
        for (BlockPos p : CampRegistry.get(level).campsOf(player.getUUID())) {
            if (cost <= 0) break;
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof CampBlockEntity camp) cost -= camp.take(Items.EMERALD, cost);
        }
    }

    /** Arranca la obra con estos bloques (también lo usan las pruebas). */
    public static RouteData.RoadJob start(ServerLevel level, RouteData.Route r, Blueprints.Plan plan) {
        int n = plan.size();
        long[] positions = new long[n];
        int[] states = new int[n];
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> e : plan.blocks.entrySet()) {
            positions[i] = e.getKey().asLong();
            states[i] = index.computeIfAbsent(e.getValue(), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            i++;
        }
        int ticks = Mth.clamp(r.length() * 20 / ROUTE_BLOCKS_PER_SECOND, MIN_TICKS, MAX_TICKS);
        RouteData.RoadJob job = new RouteData.RoadJob(r.id(), level.getGameTime(), ticks, positions, states, palette, new BitSet(n));
        RouteData.get(level).putRoadJob(job);
        return job;
    }

    /** Corta la obra de la ruta (se borró o se volvió a trazar distinta). */
    public static void cancel(ServerLevel level, UUID route) {
        RouteData.RoadJob job = RouteData.get(level).removeRoadJob(route);
        if (job != null) BuilderNpc.remove(level, job.builder);
    }

    /** Porcentaje de la obra del camino de la ruta, o -1 si no hay obra. */
    public static int percent(ServerLevel level, UUID route) {
        RouteData.RoadJob job = RouteData.get(level).roadJob(route);
        return job == null || job.done ? -1 : job.percent(level.getGameTime());
    }

    // ------------------------------------------------------------------ obra

    /** Cada tick: el constructor acompaña el frente; cada {@link #PLACE_INTERVAL}, se ponen los bloques que tocan. */
    public static void tick(ServerLevel level) {
        RouteData data = RouteData.get(level);
        if (data.roadJobs().isEmpty()) return;
        long now = level.getGameTime();
        boolean place = now % PLACE_INTERVAL == 0;
        for (RouteData.RoadJob job : new ArrayList<>(data.roadJobs())) {
            int due = job.due(now);
            if (place) placeDue(level, data, job, due);
            if (!job.done && now - job.startTick >= job.durationTicks) {
                job.done = true;
                data.setRoad(job.route, true);
                data.changed();
                notifyDone(level, data.route(job.route));
            }
            if (job.done && job.first >= job.size()) {
                BuilderNpc.remove(level, job.builder);
                data.removeRoadJob(job.route);
                continue;
            }
            moveBuilder(level, data, job, due);
        }
    }

    /** Pone los bloques que ya tocan y tienen su chunk cargado (también lo usan las pruebas). */
    public static void placeDue(ServerLevel level, RouteData data, RouteData.RoadJob job, int due) {
        boolean changed = false;
        for (int i = job.first; i < Math.min(due, job.size()); i++) {
            if (job.placed.get(i)) continue;
            BlockPos p = BlockPos.of(job.positions[i]);
            if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;   // ese tramo, cuando se cargue
            place(level, p, job.palette.get(job.states[i]), i % 4 == 0);
            job.placed.set(i);
            changed = true;
        }
        int first = job.placed.nextClearBit(job.first);
        if (first != job.first || changed) {
            job.first = first;
            data.changed();
        }
    }

    private static void place(ServerLevel level, BlockPos p, BlockState s, boolean effects) {
        BlockState old = level.getBlockState(p);
        if (old == s) return;
        if (level.getBlockEntity(p) != null) return;   // nunca pisa cofres, camas, campanas...
        level.setBlock(p, s, 3);
        BlockState shown = s.isAir() ? old : s;
        if (effects && !shown.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, shown), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    4, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, p, shown.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5F, 0.9F);
        }
    }

    /** El constructor va con el frente de obra mientras ese lugar esté cargado; si no, se va. */
    private static void moveBuilder(ServerLevel level, RouteData data, RouteData.RoadJob job, int due) {
        if (job.size() == 0) return;
        int frontIndex = Math.max(0, Math.min(due, job.size()) - 1);
        BlockPos front = BlockPos.of(job.positions[frontIndex]);
        if (job.done || !level.isPositionEntityTicking(front)) {
            if (job.builder != null && level.getEntity(job.builder) != null) BuilderNpc.remove(level, job.builder);
            return;
        }
        BlockPos side = BlockPos.of(job.positions[Math.max(0, frontIndex - 40)]);   // se para del lado ya hecho
        UUID id = BuilderNpc.update(level, job.builder, front, side, "road:" + job.route);
        if (id != null && !id.equals(job.builder)) {
            job.builder = id;
            data.changed();
        }
    }

    private static void notifyDone(ServerLevel level, @Nullable RouteData.Route r) {
        if (r == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(r.owner());
        if (p != null) {
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.road.finished", r.name()).withStyle(ChatFormatting.GREEN));
        }
    }

    /** Un constructor de caminos sin obra (de un guardado viejo): sobra. */
    public static boolean isOrphanBuilder(ServerLevel level, Entity e, String route) {
        try {
            RouteData.RoadJob job = RouteData.get(level).roadJob(UUID.fromString(route));
            return job == null || !e.getUUID().equals(job.builder);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
}
