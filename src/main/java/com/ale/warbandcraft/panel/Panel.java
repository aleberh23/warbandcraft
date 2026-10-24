package com.ale.warbandcraft.panel;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.camp.CampStats;
import com.ale.warbandcraft.camp.Prisoners;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Roads;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.upgrade.WallRepair;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStock;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Panel de control (H11): todo tu reino en una pantalla (tecla K). Acá se arman los datos que viajan al cliente
 * y se validan las órdenes que se dan desde lejos. Solo mira la dimensión donde está el jugador.
 */
public final class Panel {
    /** Sacar soldados o guardar a los de cerca: el Campamento cargado y vos a esta distancia (la del guardado automático). */
    public static final int NEAR_DISTANCE = Barracks.AUTO_STORE_DISTANCE;
    /** Un guardia cuenta para una aldea si su puesto está a esta distancia del centro. */
    public static final int GUARD_RADIUS = 48;

    public static final int TAB_VILLAGES = 0, TAB_CAMPS = 1, TAB_ROUTES = 2, TAB_SHIPMENTS = 3;

    private Panel() {}

    // ------------------------------------------------------------------ datos

    /**
     * Un Campamento. {@code emeralds}/{@code armament}/{@code food} = -1 si nunca se contó (no se cargó desde que
     * existe el panel). {@code foodDays}: -1 = barracón vacío, -2 = no se sabe.
     */
    public record CampInfo(BlockPos pos, Component name, int soldiers, int capacity, int guards, int prisoners, int foodDays,
                           int emeralds, int armament, int smiths, boolean near) {}

    /** Una aldea o castillo tuyo. {@code sendTo} = el Campamento conectado más cercano en tiempo (null = ninguno). */
    public record VillageInfo(BlockPos pos, Component name, boolean castle, int guards, VillageStock.Stock daily,
                              VillageStock.Stock stock, VillageStock.Stock cap, boolean loaded,
                              @Nullable BlockPos sendTo, Component sendToName, int sendTicks,
                              int level, Component building, int percent, int invasionTicks, Component invader,
                              int popularity, int stuckWorkers) {}

    /** H12: una mejora en la pantalla de Mejoras. {@code state} = ordinal de {@link Upgrades.State}. */
    public record UpgradeRow(String id, Component name, Component effect, int cost, int state, Component reason, int percent) {}

    /** H12: las mejoras de una aldea o castillo tuyo. {@code emeralds} = las del almacén (lo que falte sale de tu inventario). */
    public record VillageDetail(BlockPos pos, Component name, boolean castle, int level, int builder, int emeralds,
                                List<UpgradeRow> upgrades) {}

    /** {@code percent}: avance de la obra del camino (H14), -1 si no hay obra. */
    public record RouteInfo(UUID id, String name, Component a, Component b, int length, int ticks, boolean active, boolean road,
                            int cost, int percent) {}

    /** Un envío en camino; {@code stacks} = pilas de ítems. */
    public record ShipmentInfo(Component from, Component to, int soldiers, int stacks, int ticksLeft) {}

    /** Un destino para mandar soldados desde un Campamento: {@code free} = lugar en el barracón que los recibe. */
    public record Destination(BlockPos pos, Component name, int ticks, int free) {}

    /**
     * El detalle de un Campamento: su barracón, sus presos y a dónde se pueden mandar soldados. {@code found}: botón
     * Fundar aldea (false = ya es el Campamento de una aldea); {@code foundBlocked} = por qué no se puede (vacío = se puede).
     */
    public record CampDetail(BlockPos pos, Component name, int soldiers, int capacity, int nearby, boolean near,
                             List<OpenBarracksMessage.Row> rows, List<OpenBarracksMessage.PrisonerRow> prisoners,
                             List<Destination> destinations, boolean found, Component foundBlocked) {}

    public static List<CampInfo> camps(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        CampRegistry registry = CampRegistry.get(level);
        BarracksData data = BarracksData.get(level);
        List<CampInfo> out = new ArrayList<>();
        for (BlockPos pos : registry.campsOf(owner)) {
            BarracksData.Barracks b = data.at(pos);
            int soldiers = b == null ? 0 : b.soldiers.size();
            int[] st = stats(level, pos);
            int foodDays;
            if (soldiers == 0) foodDays = -1;
            else if (st == null) foodDays = -2;
            else foodDays = (st[CampStats.FOOD] + b.foodCredit) / (soldiers * BarracksData.FOOD_PER_SOLDIER);
            out.add(new CampInfo(pos, registry.displayName(pos), soldiers, Barracks.capacity(level, pos), b == null ? 0 : b.guards(),
                    b == null ? 0 : b.prisoners.size(), foodDays,
                    st == null ? -1 : st[CampStats.EMERALDS], st == null ? -1 : st[CampStats.ARMAMENT],
                    st == null ? -1 : st[CampStats.SMITHS], near(player, pos)));
        }
        out.sort(Comparator.comparingDouble(c -> c.pos().distSqr(player.blockPosition())));
        return out;
    }

    public static List<VillageInfo> villages(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        List<VillageInfo> out = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).byDistance(player.blockPosition())) {
            if (!com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) continue;
            Routes.Node node = Routes.node(level, owner, v.center());
            Routes.Destination to = nearestCamp(level, owner, v.center());
            TownData.Town town = Upgrades.town(level, v.center());
            TownData.Job job = town == null ? null : town.job;
            com.ale.warbandcraft.invasion.InvasionData.Invasion inv = com.ale.warbandcraft.invasion.InvasionData.get(level).at(v.center());
            com.ale.warbandcraft.invasion.InvasionData.Host host = inv != null ? null : com.ale.warbandcraft.invasion.Hosts.hostAt(level, v.center());
            out.add(new VillageInfo(v.center(), node == null ? Component.literal("?") : node.name(), v.castle(),
                    guardsAt(level, owner, v.center()), VillageStock.daily(level, v), VillageStock.total(level, v),
                    VillageStock.cap(level, v), level.isPositionEntityTicking(v.center()),
                    to == null ? null : to.node().pos(), to == null ? Component.empty() : to.node().name(),
                    to == null ? 0 : to.ticks(), town == null ? 1 : town.level(),
                    job == null ? Component.empty() : job.upgrade.name(v.castle()), job == null ? 0 : Upgrades.percent(job),
                    inv != null ? (int) Math.max(0, inv.arriveTick - level.getGameTime())
                            : host != null ? (int) Math.max(0, host.deadline - level.getGameTime()) : -1,   // H23: la hueste, como una invasión
                    inv != null ? com.ale.warbandcraft.invasion.Invasions.factionName(inv.faction)
                            : host != null ? com.ale.warbandcraft.invasion.Invasions.factionName(host.faction) : Component.empty(),
                    v.castle() || town == null ? -1 : (int) town.popularity, stuckWorkers(level, owner, v)));
        }
        return out;
    }

    /** H18: tus trabajadores de la aldea (cargada) que están trabados: les falta algo, almacén lleno, con hambre, sin zona. */
    public static int stuckWorkers(ServerLevel level, UUID owner, VillageData.Village v) {
        if (!level.isPositionEntityTicking(v.center())) return 0;
        int n = 0;
        for (com.talhanation.workers.entities.AbstractWorkerEntity w : com.ale.warbandcraft.village.VillageManagement.workers(level, v.center(), owner)) {
            if (com.ale.warbandcraft.village.WorkerSupply.status(level, w).color() == com.ale.warbandcraft.village.WorkerSupply.RED) n++;
        }
        return n;
    }

    public static List<RouteInfo> routes(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        List<RouteInfo> out = new ArrayList<>();
        for (RouteData.Route r : RouteData.get(level).routesOf(owner)) {
            out.add(new RouteInfo(r.id(), r.name(), nodeName(level, owner, r.a()), nodeName(level, owner, r.b()), r.length(),
                    Routes.ticks(r), Routes.active(level, r), r.road(), Roads.cost(r), Roads.percent(level, r.id())));
        }
        return out;
    }

    public static List<ShipmentInfo> shipments(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        long now = level.getGameTime();
        List<RouteData.Shipment> list = new ArrayList<>(RouteData.get(level).shipmentsOf(owner));
        list.sort(Comparator.comparingLong(RouteData.Shipment::arriveTick));
        List<ShipmentInfo> out = new ArrayList<>();
        for (RouteData.Shipment s : list) {
            out.add(new ShipmentInfo(nodeName(level, owner, s.from()), nodeName(level, owner, s.to()), s.soldiers().size(),
                    s.items().size(), (int) Math.max(0, Math.min(Integer.MAX_VALUE, s.arriveTick() - now))));
        }
        return out;
    }

    /** El detalle de tu Campamento en {@code camp}, o null si no es tuyo. */
    @Nullable
    public static CampDetail detail(ServerPlayer player, BlockPos camp) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        CampRegistry registry = CampRegistry.get(level);
        if (!registry.isCampOf(owner, camp)) return null;
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        boolean near = near(player, camp);
        int nearby = near ? Barracks.storableNear(level, camp, owner).size() : 0;
        List<Destination> dests = new ArrayList<>();
        for (Routes.Destination d : Routes.reachable(level, owner, camp)) {
            dests.add(new Destination(d.node().pos(), d.node().name(), d.ticks(), Routes.freeAt(level, owner, d.node().pos())));
        }
        boolean found = com.ale.warbandcraft.village.VillageCamps.villageOf(level, camp) == null;
        Component blocked = found ? com.ale.warbandcraft.village.Founding.blocked(level, owner, camp) : null;
        return new CampDetail(camp, registry.displayName(camp), b == null ? 0 : b.soldiers.size(), Barracks.capacity(level, camp), nearby, near,
                OpenBarracksMessage.rows(player.getServer(), owner, b), OpenBarracksMessage.prisonerRows(b), dests,
                found, blocked == null ? Component.empty() : blocked);
    }

    /** H12: las mejoras de tu aldea/castillo en {@code center}, o null si no es tuyo. */
    @Nullable
    public static VillageDetail villageDetail(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return null;
        TownData.Town t = Upgrades.town(level, center);
        List<UpgradeRow> rows = new ArrayList<>();
        UpgradeRow repair = repairRow(level, v, t);
        if (repair != null) rows.add(repair);
        for (Upgrades.Status s : Upgrades.status(level, v)) {
            Upgrade u = s.upgrade();
            rows.add(new UpgradeRow(u.id, u.name(v.castle()), u.effect(v.castle()), u.cost, s.state().ordinal(), s.reason(), s.percent()));
        }
        rows.addAll(roadRows(level, player.getUUID(), center));   // H14: los caminos de las rutas que salen de acá
        Routes.Node node = Routes.node(level, player.getUUID(), center);
        return new VillageDetail(center, node == null ? Component.literal("?") : node.name(), v.castle(),
                t == null ? 1 : t.level(), t == null ? 0 : t.builderLevel(), VillageStock.total(level, v).emeralds(), rows);
    }

    /** Reparar murallas: una fila arriba de las mejoras si la empalizada/muralla está rota (o se está reparando). */
    @Nullable
    private static UpgradeRow repairRow(ServerLevel level, VillageData.Village v, @Nullable TownData.Town t) {
        if (v.castle() || !WallRepair.hasWalls(t)) return null;
        Upgrade u = Upgrade.REPAIR;
        if (t.job != null && t.job.upgrade == u) {
            return new UpgradeRow(u.id, u.name(false), Component.translatable("upgrade.warbandcraft.repair.effect", t.job.size()), 0,
                    Upgrades.State.BUILDING.ordinal(), Component.empty(), Upgrades.percent(t.job));
        }
        WallRepair.Damage d = WallRepair.damage(level, v);
        if (d.missing() == 0) return null;
        Component busy = t.job == null ? null : Component.translatable("upgrade.warbandcraft.busy", t.job.upgrade.name(false));
        return new UpgradeRow(u.id, u.name(false), Component.translatable("upgrade.warbandcraft.repair.effect", d.missing()), d.cost(),
                (busy == null ? Upgrades.State.AVAILABLE : Upgrades.State.LOCKED).ordinal(), busy == null ? Component.empty() : busy, 0);
    }

    /** H14: id de fila de Mejoras que es el camino de una ruta ("road:" + id de la ruta). */
    public static final String ROAD_ROW = "road:";

    /** H14: una fila de Mejoras por cada ruta tuya que sale de {@code node} (Campamento o aldea/castillo). */
    public static List<UpgradeRow> roadRows(ServerLevel level, UUID owner, BlockPos node) {
        List<UpgradeRow> out = new ArrayList<>();
        BlockPos here = com.ale.warbandcraft.village.VillageCamps.canonical(level, node);
        for (RouteData.Route r : Roads.routesAt(level, owner, node)) {
            Routes.Node a = Routes.nodeNear(level, owner, r.a());
            BlockPos other = a != null && a.pos().equals(here) ? r.b() : r.a();
            RouteData.Route paved = new RouteData.Route(r.id(), r.owner(), r.name(), r.points(), r.a(), r.b(), r.length(), true);
            Component effect = Component.translatable("gui.warbandcraft.panel.road_effect", nodeName(level, owner, other), r.length(),
                    clock(Routes.ticks(r)), clock(Routes.ticks(paved)));
            int percent = Roads.percent(level, r.id());
            Upgrades.State state = r.road() ? Upgrades.State.DONE : percent >= 0 ? Upgrades.State.BUILDING
                    : Routes.active(level, r) ? Upgrades.State.AVAILABLE : Upgrades.State.LOCKED;
            Component reason = state == Upgrades.State.LOCKED ? Component.translatable("gui.warbandcraft.panel.route_inactive") : Component.empty();
            out.add(new UpgradeRow(ROAD_ROW + r.id(), Component.translatable("gui.warbandcraft.panel.road_row", r.name()), effect,
                    Roads.cost(r), state.ordinal(), reason, Math.max(0, percent)));
        }
        return out;
    }

    /** "1:05" a partir de ticks (del lado del servidor). */
    private static String clock(int ticks) {
        int s = Math.max(0, ticks / 20);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    /** H14: encargar el camino de una ruta que sale de {@code from} (Campamento o aldea/castillo). */
    public static Component buildRoad(ServerPlayer player, String routeId, @Nullable BlockPos from) {
        try {
            return Roads.order(player, UUID.fromString(routeId), from);
        } catch (IllegalArgumentException e) {
            return Component.translatable("msg.warbandcraft.road.not_yours").withStyle(ChatFormatting.RED);
        }
    }

    /** H12: encargar una mejora desde el panel. */
    public static Component build(ServerPlayer player, BlockPos center, String upgradeId) {
        if (upgradeId.startsWith(ROAD_ROW)) return buildRoad(player, upgradeId.substring(ROAD_ROW.length()), center);
        Upgrade u = Upgrade.byId(upgradeId);
        if (u == null) return Component.translatable("upgrade.warbandcraft.not_here").withStyle(ChatFormatting.RED);
        if (u == Upgrade.REPAIR) return WallRepair.order(player, center);
        return Upgrades.order(player, center, u);
    }

    /** Totales para el resumen de arriba: {soldados guardados, prisioneros, esmeraldas/día, comida/día, armamento/día, envíos}. */
    public static int[] totals(List<CampInfo> camps, List<VillageInfo> villages, List<ShipmentInfo> shipments) {
        int soldiers = 0, prisoners = 0, emeralds = 0, food = 0, armament = 0;
        for (CampInfo c : camps) {
            soldiers += c.soldiers();
            prisoners += c.prisoners();
        }
        for (VillageInfo v : villages) {
            emeralds += v.daily().emeralds();
            food += v.daily().food();
            armament += v.daily().armament();
        }
        return new int[]{soldiers, prisoners, emeralds, food, armament, shipments.size()};
    }

    // ------------------------------------------------------------------ cuentas

    /** La cuenta del almacén: si está cargado se cuenta de nuevo (y se guarda), si no la última que se guardó. */
    @Nullable
    public static int[] stats(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CampBlockEntity camp) {
            int[] v = CampStats.compute(level, camp);
            CampRegistry.get(level).setStats(pos, v);
            return v;
        }
        return CampRegistry.get(level).statsOf(pos);
    }

    /** Recuenta los Campamentos cargados (cada 10 s, para que el panel tenga números frescos). */
    public static void refreshStats(ServerLevel level) {
        for (BlockPos pos : CampRegistry.get(level).allCamps()) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CampBlockEntity) stats(level, pos);
        }
    }

    /** El Campamento está cargado y el jugador lo bastante cerca para sacar o guardar soldados. */
    public static boolean near(ServerPlayer player, BlockPos camp) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(camp) || !(level.getBlockEntity(camp) instanceof CampBlockEntity)) return false;
        return VillageData.horizontalDistSq(player.blockPosition(), camp) <= (double) NEAR_DISTANCE * NEAR_DISTANCE;
    }

    /** Tus guardias en una aldea: fichas de guardia con puesto cerca del centro + los que están ahí parados. */
    public static int guardsAt(ServerLevel level, UUID owner, BlockPos center) {
        double r2 = (double) GUARD_RADIUS * GUARD_RADIUS;
        int n = 0;
        BarracksData data = BarracksData.get(level);
        for (BlockPos camp : CampRegistry.get(level).campsOf(owner)) {
            BarracksData.Barracks b = data.at(camp);
            if (b == null) continue;
            for (BarracksData.Soldier s : b.soldiers) {
                if (s.guard && s.post != null && horizontalDistSq(s.post, center) <= r2) n++;
            }
        }
        if (level.isPositionEntityTicking(center)) {
            AABB box = new AABB(center).inflate(GUARD_RADIUS, 32, GUARD_RADIUS);
            for (AbstractRecruitEntity e : level.getEntitiesOfClass(AbstractRecruitEntity.class, box,
                    e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), owner) && Soldiers.isSoldier(e))) {
                if (Barracks.isGuard(e) && horizontalDistSq(e.getHoldPos(), center) <= r2) n++;
            }
        }
        return n;
    }

    private static double horizontalDistSq(Vec3 a, BlockPos b) {
        double dx = a.x - (b.getX() + 0.5), dz = a.z - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    /** El Campamento tuyo conectado por rutas más cercano (en tiempo) a {@code from}, o null. */
    @Nullable
    public static Routes.Destination nearestCamp(ServerLevel level, UUID owner, BlockPos from) {
        Routes.Destination best = null;
        for (Routes.Destination d : Routes.reachable(level, owner, from)) {
            if (d.node().kind() == Routes.Kind.CAMP && (best == null || d.ticks() < best.ticks())) best = d;
        }
        return best;
    }

    private static Component nodeName(ServerLevel level, UUID owner, BlockPos pos) {
        Routes.Node n = Routes.node(level, owner, pos);
        return n == null ? Component.literal("?").withStyle(ChatFormatting.RED) : n.name();
    }

    // ------------------------------------------------------------------ órdenes

    /** Saca soldados del barracón: solo con el Campamento cargado y vos cerca (si no, se guardarían solos otra vez). */
    public static Component deploy(ServerPlayer player, BlockPos camp, String row, int amount) {
        Component error = checkNear(player, camp);
        if (error != null) return error;
        int n = Barracks.deploy(player.serverLevel(), camp, row, Math.max(1, amount), player.getUUID());
        return Component.translatable("msg.warbandcraft.barracks.deployed", n).withStyle(ChatFormatting.GREEN);
    }

    public static Component storeNear(ServerPlayer player, BlockPos camp) {
        Component error = checkNear(player, camp);
        if (error != null) return error;
        int n = Barracks.storeNear(player.serverLevel(), camp, player.getUUID());
        return Component.translatable("msg.warbandcraft.barracks.stored", n).withStyle(n > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    @Nullable
    private static Component checkNear(ServerPlayer player, BlockPos camp) {
        if (!CampRegistry.get(player.serverLevel()).isCampOf(player.getUUID(), camp)) {
            return Component.translatable("msg.warbandcraft.panel.not_yours").withStyle(ChatFormatting.RED);
        }
        if (!near(player, camp)) return Component.translatable("msg.warbandcraft.panel.too_far", NEAR_DISTANCE).withStyle(ChatFormatting.RED);
        return null;
    }

    /** Reclutar un prisionero: desde cualquier lado (pasa de una lista a la otra del barracón). */
    public static Component recruit(ServerPlayer player, BlockPos camp, String row) {
        if (!CampRegistry.get(player.serverLevel()).isCampOf(player.getUUID(), camp)) {
            return Component.translatable("msg.warbandcraft.panel.not_yours").withStyle(ChatFormatting.RED);
        }
        return switch (Prisoners.recruit(player.serverLevel(), camp, row, player.getUUID())) {
            case OK -> Component.translatable("msg.warbandcraft.prisoners.recruited").withStyle(ChatFormatting.GREEN);
            case FULL -> Component.translatable("msg.warbandcraft.prisoners.full").withStyle(ChatFormatting.RED);
            case NONE -> Component.translatable("msg.warbandcraft.prisoners.none").withStyle(ChatFormatting.YELLOW);
        };
    }

    public static Component ransom(ServerPlayer player, BlockPos camp, String row) {
        if (!CampRegistry.get(player.serverLevel()).isCampOf(player.getUUID(), camp)) {
            return Component.translatable("msg.warbandcraft.panel.not_yours").withStyle(ChatFormatting.RED);
        }
        int emeralds = Prisoners.ransom(player.serverLevel(), camp, row);
        return emeralds > 0
                ? Component.translatable("msg.warbandcraft.prisoners.ransomed", emeralds).withStyle(ChatFormatting.GREEN)
                : Component.translatable("msg.warbandcraft.prisoners.none").withStyle(ChatFormatting.YELLOW);
    }

    /**
     * Renombrar desde el Panel, desde cualquier lado. {@code village} = {@code pos} es el centro de una aldea/castillo
     * tuyo: se renombra su Campamento (que es la aldea). Si no, {@code pos} es un Campamento tuyo.
     */
    public static Component rename(ServerPlayer player, BlockPos pos, String name, boolean village) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        BlockPos camp = pos;
        if (village) {
            VillageData.Village v = VillageData.get(level).at(pos);
            if (v == null || !com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) {
                return Component.translatable("msg.warbandcraft.panel.village_not_yours").withStyle(ChatFormatting.RED);
            }
            camp = com.ale.warbandcraft.village.VillageCamps.campOf(level, v);
            if (camp == null) return Component.translatable("msg.warbandcraft.panel.no_village_camp").withStyle(ChatFormatting.RED);
        } else if (!CampRegistry.get(level).isCampOf(owner, camp)) {
            return Component.translatable("msg.warbandcraft.panel.not_yours").withStyle(ChatFormatting.RED);
        }
        if (!CampBlockEntity.renameAt(level, camp, name)) return null;
        return Component.translatable("msg.warbandcraft.camp.renamed", CampBlockEntity.cleanName(name)).withStyle(ChatFormatting.GREEN);
    }

    /** Manda soldados de una fila del barracón por ruta (envío de H13). Solo soldados: los ítems se cargan en el Campamento. */
    public static Component sendSoldiers(ServerPlayer player, BlockPos camp, String row, int amount, BlockPos to) {
        Component error = Routes.send(player.serverLevel(), player.getUUID(), camp, to, Map.of(row, Math.max(1, amount)), List.of());
        return error != null ? error.copy().withStyle(ChatFormatting.RED)
                : Component.translatable("msg.warbandcraft.shipments.sent", nodeName(player.serverLevel(), player.getUUID(), to))
                .withStyle(ChatFormatting.GREEN);
    }

    /** Fundar una aldea en tu Campamento ({@link com.ale.warbandcraft.village.Founding}). */
    public static Component found(ServerPlayer player, BlockPos camp) {
        return com.ale.warbandcraft.village.Founding.found(player, camp);
    }

    /** Manda todo el almacén de tu aldea/castillo al Campamento conectado más cercano. */
    public static Component sendStock(ServerPlayer player, BlockPos village) {
        ServerLevel level = player.serverLevel();
        Routes.Destination to = nearestCamp(level, player.getUUID(), village);
        if (to == null) return Component.translatable("msg.warbandcraft.panel.no_camp_route").withStyle(ChatFormatting.RED);
        Component error = VillageStock.send(level, player.getUUID(), village, to.node().pos());
        return error != null ? error.copy().withStyle(ChatFormatting.RED)
                : Component.translatable("msg.warbandcraft.stock.sent").withStyle(ChatFormatting.GREEN);
    }
}
