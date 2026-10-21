package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.nav.NavGrid;
import com.ale.warbandcraft.nav.NavPath;
import com.ale.warbandcraft.nav.Step;
import com.ale.warbandcraft.sapper.FieldCannonEntity;
import com.ale.warbandcraft.sapper.SapperRules;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * El bando atacante de un asedio, al estilo de Bannerlord (lo usan las invasiones contra tus aldeas, tus asedios a
 * castillos y las batallas entre facciones):
 * <ul>
 *   <li><b>Carriles</b>: cada carril ataca por su lado. Cada {@link #REPLAN_TICKS} se busca el camino más barato desde su
 *       frente hasta adentro, sobre la malla ({@code nav/}) con las conexiones "apagadas" prendidas. Sin ellas = hay un
 *       hueco abierto y se entra por ahí (siempre se prefiere); si cruza el muro, con un zapador: un cañón (uno por
 *       asedio), si no un túnel por debajo del muro (los demás entran por ahí cuando está listo), si no TNT; sin
 *       zapador, escaleras.</li>
 *   <li><b>Grupos</b>: afuera, de 3 a 5 detrás de un guía que espera a los de atrás; caminan por la malla
 *       ({@link Mover}). Adentro (o con un enemigo encima) se separan y pelean.</li>
 *   <li>Los arqueros, con orden, tiran desde afuera mientras tengan a quién.</li>
 * </ul>
 * Lo que es de cada clase de pelea (avisos, dónde se guardan las escaleras, si hay cañones) lo da el {@link Host}.
 */
public final class SiegeLanes {
    /** Formar a tanto afuera del muro; los arqueros, a tanto afuera del cruce. */
    public static int STAGE_MARGIN = 14, ARCHER_MARGIN = 7;
    /** Cada cuánto se vuelve a planear cada carril (buscando siempre el hueco abierto). */
    public static int REPLAN_TICKS = 20 * 8;
    /** Escaleras por carril, y cuánto tardan en volver a ponerla si se la tumban. */
    public static int LADDERS_PER_LANE = 2, LADDER_RETRY = 20 * 12;
    /** Zapador: mecha, cuánto le da para llegar al muro, cuántas veces prueba. Cañón: a qué distancia del muro. */
    public static int FUSE = 60, SAPPER_TIMEOUT = 20 * 40, MAX_BLASTS = 4;
    public static double CANNON_DISTANCE = 26;
    /** Grupos de tantos (3 a 5): el guía espera si alguno queda a más de tanto. */
    public static int SQUAD_SIZE = 4;
    public static double SQUAD_WAIT = 6;
    /** Un enemigo a tanto: sale a pelear; no se lo vuelve a formar mientras lo tenga a tanto; alcance de los arqueros. */
    public static double ENGAGE = 8, KEEP_FIGHTING = 16, RANGED_REACH = 48;
    /** Marca de un cañón de asedio de la IA (se borra si se carga del disco: el carril arma otro si hace falta). */
    public static final String TAG_CANNON = "warbandcraft:ai_cannon";
    /** Cuánto lejos del centro se busca (bloques más allá del muro). */
    public static int AREA_MARGIN = 62;

    private static final int WANDER = 0, BACK_TO_POS = 3;
    private static final String BY = "siege";

    /** Cómo entra un carril. */
    public enum Entry { NONE, OPEN, LADDER, BLAST, CANNON, TUNNEL, STUCK }

    /** Lo que se le avisa a quien mira (el defensor o el atacante). */
    public enum Event { LADDERS, BLAST, BOOM, CANNON, TUNNEL, TUNNEL_DONE }

    /** Lo que la defensa necesita saber de un carril. */
    public record LaneInfo(int index, Entry entry, Vec3 approach, @Nullable BlockPos outside, @Nullable BlockPos inside,
                           List<BlockPos> ladders, int attackers) {}

    /** Lo que es de cada clase de pelea. */
    public interface Host {
        BlockPos center();

        /** Dónde se guardan las escaleras puestas (para sacarlas al terminar). */
        List<Long> ladders();

        void changed();

        /** Puede armar cañones (ejércitos de la IA). */
        boolean cannons();

        void announce(Event e);

        /** Para el log. */
        String name();

        /** Cobra el cañón (la IA no paga; tus soldados, con el hierro de siempre). False = no hay con qué. */
        default boolean payCannon(ServerLevel level, AbstractRecruitEntity sapper) {
            return true;
        }

        /** Adónde van los que ya entraron si no hay a quién pelear (el centro; en una invasión, el almacén). */
        default BlockPos objective() {
            return center();
        }

        /** Los que entran atacan también a los aldeanos que tengan cerca (la IA sí; tus soldados, no). */
        default boolean villagers() {
            return false;
        }
    }

    /** Túnel: bloques que cava el zapador por segundo. */
    public static int TUNNEL_PER_SECOND = 1;

    public static final class Lane {
        public final int index;
        public final double angle;
        public final BlockPos stage;
        Entry entry = Entry.NONE;
        @Nullable BlockPos outside, inside, wall;
        /** El paso que abre el TNT de este carril (1 de ancho, 2 de alto, atravesando el muro). */
        List<BlockPos> hole = List.of();
        final List<Breach.Ladder> ladders = new ArrayList<>();
        long ladderLost = Long.MIN_VALUE / 2;
        @Nullable UUID sapper, cannon;
        long sapperSince, blastAt = -1;
        int blasts;
        boolean noBreaker, noTunnel;
        @Nullable List<Breach.TunnelCell> tunnel;
        int dug;
        long planned = Long.MIN_VALUE / 2;
        /** Un guía que no encontró el camino que el carril cree abierto: el próximo plan sale de ahí. */
        @Nullable BlockPos planFrom;
        int attackers;

        Lane(int index, double angle, BlockPos stage) {
            this.index = index;
            this.angle = angle;
            this.stage = stage;
        }

        public Entry entry() {
            return entry;
        }
    }

    /** Todo el bando atacante de una pelea. */
    public static final class State {
        public final List<Lane> lanes = new ArrayList<>();
        final Set<Event> told = new HashSet<>();
        /** Ya se armó el cañón del asedio (uno: el otro zapador cava). */
        boolean cannonBuilt;
        /** Los que ya pasaron el muro: pelean adentro, no se los vuelve a llevar. */
        final Set<UUID> inside = new HashSet<>();

        public Lane add(double angle, BlockPos stage) {
            Lane l = new Lane(lanes.size(), angle, stage);
            lanes.add(l);
            return l;
        }

        public Lane lane(int index) {
            return lanes.get(Math.max(0, Math.min(index, lanes.size() - 1)));
        }
    }

    private SiegeLanes() {}

    // ------------------------------------------------------------------ lugares

    /**
     * Dónde forma un carril: afuera del muro, cargado y seco, y del lado de la aldea: se prueban lados (empezando por
     * el pedido) hasta uno desde el que se llega sin cruzar agua. Si ninguno, el que menos agua cruce.
     */
    public static BlockPos stagePos(ServerLevel level, BlockPos center, double angle, Breach.Walls w, @Nullable BlockPos avoid) {
        BlockPos fallback = null;
        int fallbackWater = Integer.MAX_VALUE;
        int[] offsets = {0, 20, -20, 40, -40, 60, -60, 90, -90, 135, -135, 180};
        for (int off : offsets) {
            double a = angle + Math.toRadians(off);
            for (int extra : new int[]{STAGE_MARGIN, STAGE_MARGIN + 8, STAGE_MARGIN / 2}) {
                Vec3 p = Breach.ground(level, Breach.at(center, a, w.radius() + extra), center.getY());
                BlockPos b = BlockPos.containing(p);
                if (!level.isPositionEntityTicking(b) || !Breach.dry(level, b) || NavGrid.flagsAt(level, b.getX(), b.getY(), b.getZ()) != 0) continue;
                if (avoid != null && b.distSqr(avoid) < 16 * 16) continue;   // no todos del mismo lado
                List<Step> path = NavPath.find(level, b, groundCenter(level, center), goalReach(w), NavCaps.siege(true), bounds(center, w), false);
                if (path == null) continue;
                int water = NavPath.water(path);
                if (water == 0) return b;
                if (water < fallbackWater) {
                    fallbackWater = water;
                    fallback = b;
                }
                break;
            }
        }
        if (fallback != null) return fallback;
        return BlockPos.containing(Breach.dryNear(level, Breach.ground(level, Breach.at(center, angle, w.radius() + STAGE_MARGIN), center.getY())));
    }

    /** El ángulo (desde el centro) de un lugar. */
    public static double angleOf(BlockPos center, Vec3 p) {
        return Math.atan2(p.z - center.getZ() - 0.5, p.x - center.getX() - 0.5);
    }

    public static Vec3 groundCenter(ServerLevel level, BlockPos center) {
        return Breach.ground(level, Vec3.atBottomCenterOf(center), center.getY());
    }

    /** Hasta dónde hay que llegar para estar "adentro". */
    public static double goalReach(Breach.Walls w) {
        return w.walled() ? Math.max(2, w.bandMin() - 2) : Math.max(4, w.radius() * 0.5);
    }

    public static NavPath.Bounds bounds(BlockPos center, Breach.Walls w) {
        return NavPath.Bounds.around(center, w.radius() + AREA_MARGIN);
    }

    /**
     * En un asedio todos pelean de a pie (como en Bannerlord): se baja del caballo. {@code keepHorse}: el caballo queda
     * ahí (tus soldados); si no, se va.
     */
    public static void dismount(AbstractRecruitEntity s, boolean keepHorse) {
        if (!(s.getVehicle() instanceof AbstractHorse h)) return;
        s.stopRiding();
        if (!keepHorse) h.discard();
    }

    // ------------------------------------------------------------------ un carril

    /** Una vuelta (1 s) de un carril con sus soldados. {@code enemies}: los defensores. */
    public static void tick(ServerLevel level, Host host, State st, Lane lane, List<AbstractRecruitEntity> members,
                            List<LivingEntity> enemies, Breach.Walls w, boolean ordered, long now) {
        lane.attackers = members.size();
        if (members.isEmpty()) return;
        BlockPos center = host.center();
        // Escaleras que tumbaron los defensores: se olvidan (y se pueden volver a poner en un rato)
        int before = lane.ladders.size();
        lane.ladders.removeIf(l -> ladderAt(level, l.base()) == null);
        if (lane.ladders.size() < before) {
            lane.ladderLost = now;
            host.ladders().removeIf(k -> ladderAt(level, BlockPos.of(k)) == null);
            host.changed();
        }
        if (now - lane.planned >= REPLAN_TICKS || lane.entry == Entry.NONE) plan(level, host, st, lane, members, w, now);

        List<AbstractRecruitEntity> foot = new ArrayList<>(), ranged = new ArrayList<>();
        AbstractRecruitEntity sapper = null;
        for (AbstractRecruitEntity s : members) {
            if (breaking(lane) && s.getUUID().equals(lane.sapper)) {
                sapper = s;
                continue;
            }
            (BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED ? ranged : foot).add(s);
        }
        // Arqueros: con orden, tiran desde afuera mientras haya a quién; si no, entran con los demás
        if (ordered && !ranged.isEmpty() && anyInRange(ranged, enemies)) {
            Vec3 stage = Vec3.atBottomCenterOf(lane.stage);
            Vec3 dir = toward(stage, center);
            Vec3 line = lane.outside != null ? Vec3.atBottomCenterOf(lane.outside).subtract(dir.scale(ARCHER_MARGIN)) : stage.add(dir.scale(4));
            archers(level, ranged, Breach.dryNear(level, line), dir, enemies);
        } else {
            foot.addAll(ranged);
        }

        if (lane.entry == Entry.BLAST && sapper != null) blast(level, host, lane, sapper, w, now);
        else if (lane.entry == Entry.CANNON && sapper != null) cannon(level, lane, sapper);
        else if (lane.entry == Entry.TUNNEL && sapper != null) dig(level, host, st, lane, sapper, w, now);
        else if (breaking(lane) && sapper == null) {
            lane.noBreaker = true;
            lane.entry = Entry.NONE;
        }

        // Infantería: adentro (o con un enemigo encima) pelea; afuera, en grupos por la malla
        List<AbstractRecruitEntity> outside = new ArrayList<>();
        BlockPos objective = enemies.isEmpty() ? host.objective() : center;
        for (AbstractRecruitEntity s : foot) {
            boolean in = st.inside.contains(s.getUUID()) || dist(s, center) < w.inside();
            if (in && w.walled()) st.inside.add(s.getUUID());
            boolean busy = Mover.moving(s) && (onLadder(s) || s.isPassenger());
            if (!busy && in) {
                // Adentro (pedido de Ale): enemigos cerca, después aldeanos cerca, después el centro; ahí, a buscar al enemigo
                if (!SiegePush.hunting(s)) Mover.stop(s);
                SiegePush.inside(level, s, enemies, host.objective(), host.villagers());
            } else if (!busy && (nearestDistance(s, enemies) <= ENGAGE || isFighting(s))) {
                Mover.stop(s);
                engageOrCenter(s, enemies, objective);
            } else {
                outside.add(s);
            }
        }
        Vec3 goal = groundCenter(level, center);
        for (List<AbstractRecruitEntity> squad : squads(outside)) squad(level, lane, squad, goal, w, center, now);
    }

    /** Antes del asalto: el carril forma frente a su lado, infantería adelante y arqueros atrás. */
    public static void form(ServerLevel level, Lane lane, BlockPos center, List<AbstractRecruitEntity> members, List<LivingEntity> enemies) {
        Vec3 stage = Vec3.atBottomCenterOf(lane.stage);
        Vec3 dir = toward(stage, center);
        List<AbstractRecruitEntity> foot = new ArrayList<>(), ranged = new ArrayList<>();
        for (AbstractRecruitEntity s : members) (BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED ? ranged : foot).add(s);
        List<Vec3> slots = BattleGeneral.linePositions(level, stage, dir, foot.size(), 2);
        for (int i = 0; i < foot.size(); i++) {
            AbstractRecruitEntity s = foot.get(i);
            Mover.stop(s);
            if (nearestDistance(s, enemies) <= ENGAGE) engage(s, enemies);
            else {
                s.setShouldBlock(true);
                moveTo(s, Breach.dryNear(level, slots.get(i)));
            }
        }
        archers(level, ranged, stage.subtract(dir.scale(4)), dir, enemies);
    }

    private static boolean breaking(Lane lane) {
        return lane.entry == Entry.BLAST || lane.entry == Entry.CANNON || lane.entry == Entry.TUNNEL;
    }

    private static Vec3 toward(Vec3 from, BlockPos center) {
        Vec3 dir = new Vec3(center.getX() + 0.5 - from.x, 0, center.getZ() + 0.5 - from.z);
        return dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
    }

    /** Arqueros en línea, tirándole al más cercano a tiro. */
    private static void archers(ServerLevel level, List<AbstractRecruitEntity> ranged, Vec3 line, Vec3 dir, List<LivingEntity> enemies) {
        if (ranged.isEmpty()) return;
        List<Vec3> slots = BattleGeneral.linePositions(level, line, dir, ranged.size(), 1);
        for (int i = 0; i < ranged.size(); i++) {
            Mover.stop(ranged.get(i));
            moveTo(ranged.get(i), Breach.dryNear(level, slots.get(i)));
            aim(ranged.get(i), enemies);
        }
    }

    /**
     * Planea el carril: el camino más barato desde su frente hasta adentro, con las conexiones apagadas prendidas
     * (trepar, y romper si tiene zapador). Si no las usa, entran por un hueco; si las usa, ahí va la escalera, el TNT o
     * el cañón.
     */
    private static void plan(ServerLevel level, Host host, State st, Lane lane, List<AbstractRecruitEntity> members, Breach.Walls w, long now) {
        lane.planned = now;
        BlockPos center = host.center();
        AbstractRecruitEntity breaker = null;
        if (!lane.noBreaker && ForgeEventFactory.getMobGriefingEvent(level, members.get(0))) {
            for (AbstractRecruitEntity s : members) if (SapperRules.isSapper(s)) breaker = s;
        }
        // Desde el más adelantado de los que van a entrar (no el zapador: puede estar en su túnel o en el pozo del TNT, y
        // desde ahí "hay hueco" aunque el resto no llegue), o desde el guía que no encontró el camino.
        BlockPos from = lane.stage;
        double bestD = Double.MAX_VALUE;
        for (AbstractRecruitEntity s : members) {
            double d = dist(s, center);
            if (d > w.inside() + 1 && d < bestD && !s.isPassenger() && !SapperRules.isSapper(s)) {
                bestD = d;
                from = s.blockPosition();
            }
        }
        if (lane.planFrom != null) {
            from = lane.planFrom;
            lane.planFrom = null;
        }
        Entry before = lane.entry;
        List<Step> path = NavPath.find(level, from, groundCenter(level, center), goalReach(w), NavCaps.siege(breaker != null), bounds(center, w), false);
        if (path == null) {
            lane.entry = Entry.STUCK;
            if (before != Entry.STUCK) log(host, lane, "no hay camino ni trepando (vuelve a probar en " + REPLAN_TICKS / 20 + " s)");
            return;
        }
        int climb = NavPath.first(path, Step.Kind.CLIMB), brk = NavPath.first(path, Step.Kind.BREAK);
        int k = climb < 0 ? brk : brk < 0 ? climb : Math.min(climb, brk);
        crossing(lane, path, center, w, k);
        if (k < 0) {
            if (before != Entry.OPEN) {
                log(host, lane, lane.ladders.isEmpty() ? "entra por un hueco abierto" : "entra por un hueco o sus escaleras");
                discardCannon(level, lane);
            }
            lane.entry = Entry.OPEN;
            return;
        }
        Step s = path.get(k);
        BlockPos foot = path.get(k - 1).blockPos();
        BlockPos wall = BlockPos.of(s.wall());
        if (breaking(lane) && committed(level, lane)) return;   // ya están en eso: no se cambia de lugar hasta abrirlo
        // Con zapador: cañón (uno por asedio), túnel, o TNT
        if (breaker != null && breakWith(level, host, st, lane, breaker, path, k, foot, wall, w, now)) return;
        if (s.kind() != Step.Kind.CLIMB) {   // planeó romper pero no hay con qué: a planear sin romper (escaleras)
            lane.noBreaker = true;
            lane.planned = Long.MIN_VALUE / 2;
            lane.entry = Entry.STUCK;
            return;
        }
        if (now - lane.ladderLost < LADDER_RETRY) {   // se la acaban de tumbar: esperan un poco antes de volver a ponerla
            lane.entry = Entry.STUCK;
            return;
        }
        Breach.Ladder l = Breach.placeLadder(level, new Breach.Probe(true, foot, wall, foot.getY(), 0));
        if (l == null) {
            lane.entry = Entry.STUCK;
            log(host, lane, "no se pudo poner la escalera en " + foot.toShortString());
            return;
        }
        addLadder(host, lane, l);
        Direction perp = l.intoWall().getClockWise();
        for (int side : new int[]{1, -1, 2, -2}) {   // otra al lado, sobre el mismo muro
            if (lane.ladders.size() >= LADDERS_PER_LANE) break;
            BlockPos f2 = foot.relative(perp, side), w2 = wall.relative(perp, side);
            BlockPos nf = NavGrid.nearestNode(level, f2, 0);
            if (nf == null || nf.getX() != f2.getX() || nf.getZ() != f2.getZ()) continue;
            Breach.Ladder second = Breach.placeLadder(level, new Breach.Probe(true, nf, w2.atY(nf.getY()), nf.getY(), 0));
            if (second != null) addLadder(host, lane, second);
        }
        lane.entry = Entry.LADDER;
        log(host, lane, lane.ladders.size() + " escalera(s), la primera en " + l.base().toShortString());
        once(host, st, Event.LADDERS);
    }

    /** El carril sigue con lo que empezó: el túnel sin terminar, o su paso con algo todavía por romper. */
    private static boolean committed(ServerLevel level, Lane lane) {
        if (lane.wall == null) return false;
        if (lane.entry == Entry.TUNNEL) return lane.tunnel != null;
        for (BlockPos p : lane.hole) {
            var s = level.getBlockState(p);
            float hard = s.getDestroySpeed(level, p);
            if (!s.getCollisionShape(level, p).isEmpty() && hard >= 0 && hard <= SapperRules.HOLE_HARDNESS && level.getBlockEntity(p) == null) return true;
        }
        return false;
    }

    /** El paso de un {@link Step.Kind#BREAK}: de {@code foot} a {@code landing}, 1 de ancho y 2 de alto (3 si sube al salir). */
    public static List<BlockPos> holeCells(BlockPos foot, BlockPos landing) {
        Direction d = Direction.getNearest(landing.getX() - foot.getX(), 0, landing.getZ() - foot.getZ());
        int cols = Math.max(1, Math.max(Math.abs(landing.getX() - foot.getX()), Math.abs(landing.getZ() - foot.getZ())) - 1);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
            BlockPos c = foot.relative(d, i);
            out.add(c);
            out.add(c.above());
            if (i == cols && landing.getY() > foot.getY()) out.add(c.above(2));
        }
        return out;
    }

    /**
     * El zapador del carril abre paso: un cañón si se puede (uno por asedio, pagado, con TNT y lugar a tiro), si no un
     * túnel por debajo del muro, si no TNT al pie. False si no puede nada (entonces, escaleras).
     */
    private static boolean breakWith(ServerLevel level, Host host, State st, Lane lane, AbstractRecruitEntity breaker, List<Step> path, int k,
                                     BlockPos foot, BlockPos wall, Breach.Walls w, long now) {
        boolean tnt = breaker.getInventory().countItem(Items.TNT) > 0;
        List<BlockPos> hole = path.get(k).kind() == Step.Kind.BREAK ? holeCells(foot, path.get(k).blockPos()) : List.of(wall, wall.above());
        BlockPos spot = host.cannons() && tnt && (!st.cannonBuilt || cannonOf(level, lane) != null) ? cannonSpot(path, k, wall) : null;
        if (spot != null) {
            FieldCannonEntity c = cannonOf(level, lane);
            if (c == null && host.payCannon(level, breaker)) {
                c = ModEntities.FIELD_CANNON.get().create(level);
                if (c != null) {
                    c.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
                    c.getPersistentData().putLong(TAG_CANNON, host.center().asLong());
                    level.addFreshEntity(c);
                    lane.cannon = c.getUUID();
                    st.cannonBuilt = true;
                }
            }
            if (c != null) {
                start(lane, breaker, foot, wall, now, Entry.CANNON);
                lane.hole = hole;
                c.setAi(breaker.getUUID(), wall, hole);
                log(host, lane, "cañón en " + spot.toShortString() + " contra el muro en " + wall.toShortString());
                once(host, st, Event.CANNON);
                return true;
            }
        }
        if (!lane.noTunnel) {
            List<Breach.TunnelCell> t = Breach.tunnel(level, foot, wall, host.center(), w);
            if (t != null) {
                start(lane, breaker, foot, wall, now, Entry.TUNNEL);
                lane.tunnel = t;
                lane.dug = 1;   // la entrada no se cava
                log(host, lane, "el zapador cava un túnel desde " + t.get(0).feet().toShortString() + " (" + (t.size() - 1) + " tramos)");
                once(host, st, Event.TUNNEL);
                return true;
            }
            lane.noTunnel = true;
        }
        if (tnt && lane.blasts < MAX_BLASTS) {
            start(lane, breaker, foot, wall, now, Entry.BLAST);
            lane.hole = hole;
            log(host, lane, "el zapador va a volar el muro en " + wall.toShortString());
            once(host, st, Event.BLAST);
            return true;
        }
        return false;
    }

    private static void start(Lane lane, AbstractRecruitEntity sapper, BlockPos foot, BlockPos wall, long now, Entry entry) {
        lane.wall = wall;
        lane.outside = foot;
        lane.sapper = sapper.getUUID();
        lane.sapperSince = now;
        lane.blastAt = -1;
        lane.entry = entry;
    }

    /**
     * El zapador cava el túnel: va a la entrada y cava {@link #TUNNEL_PER_SECOND} tramo por segundo, metiéndose. Al
     * terminar, el carril se replanea: el camino pasa por el túnel y los demás entran por ahí.
     */
    private static void dig(ServerLevel level, Host host, State st, Lane lane, AbstractRecruitEntity sapper, Breach.Walls w, long now) {
        List<Breach.TunnelCell> t = lane.tunnel;
        if (t == null) {
            lane.entry = Entry.NONE;
            return;
        }
        sapper.setTarget(null);
        if (lane.dug >= t.size()) {   // listo: por ahí entran todos
            lane.tunnel = null;
            lane.wall = null;
            lane.entry = Entry.NONE;
            lane.planned = Long.MIN_VALUE / 2;
            Mover.stop(sapper);
            log(host, lane, "túnel terminado: entran por ahí");
            once(host, st, Event.TUNNEL_DONE);
            return;
        }
        Vec3 entrance = Vec3.atBottomCenterOf(t.get(0).feet());
        if (lane.dug == 1 && sapper.position().distanceTo(entrance) > 1.6) {
            if (now - lane.sapperSince > SAPPER_TIMEOUT) {
                lane.noTunnel = true;
                lane.entry = Entry.NONE;
                return;
            }
            Mover.Run r = Mover.run(sapper);
            if (r == null || !r.active() || r.goal.distanceToSqr(entrance) > 1) Mover.go(sapper, entrance, 0.6, NavCaps.INVADER, bounds(host.center(), w), BY);
            return;
        }
        Mover.stop(sapper);
        for (int n = 0; n < TUNNEL_PER_SECOND && lane.dug < t.size(); n++) {
            Breach.TunnelCell c = t.get(lane.dug);
            for (int h = 0; h < c.height(); h++) {
                BlockPos p = c.feet().above(h);
                if (level.getBlockState(p).isAir()) continue;
                if (!Breach.diggable(level, p)) {   // apareció algo que no se cava: se deja
                    lane.noTunnel = true;
                    lane.tunnel = null;
                    lane.entry = Entry.NONE;
                    return;
                }
                level.destroyBlock(p, false, sapper);
            }
            sapper.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            Vec3 at = Vec3.atBottomCenterOf(c.feet());
            sapper.setPos(at.x, at.y, at.z);
            moveTo(sapper, at);
            lane.dug++;
        }
    }

    private static void once(Host host, State st, Event e) {
        if (st.told.add(e)) host.announce(e);
    }

    /** Anota dónde cruza el camino del carril (afuera / adentro), para la defensa. */
    private static void crossing(Lane lane, List<Step> path, BlockPos center, Breach.Walls w, int k) {
        if (k > 0) {
            lane.outside = path.get(k - 1).blockPos();
            lane.inside = path.get(Math.min(path.size() - 1, k + 1)).blockPos();
            return;
        }
        lane.outside = lane.inside = null;
        for (int i = 1; i < path.size(); i++) {
            double d = Math.sqrt(horizontalSq(path.get(i).blockPos(), center));
            if (lane.outside == null && d <= (w.walled() ? w.bandMax() + 2 : w.radius())) lane.outside = path.get(i).blockPos();
            if (d <= w.inside()) {
                lane.inside = path.get(i).blockPos();
                return;
            }
        }
    }

    /** Lugar para el cañón: en el camino, afuera, a 12-{@link #CANNON_DISTANCE} del muro y a tiro. */
    @Nullable
    private static BlockPos cannonSpot(List<Step> path, int k, BlockPos wall) {
        for (int i = 0; i < k; i++) {
            Step s = path.get(i);
            if (s.kind() == Step.Kind.WATER || s.kind() == Step.Kind.LADDER) continue;
            double d = Math.sqrt(s.blockPos().distSqr(wall));
            if (d <= CANNON_DISTANCE && d >= 12 && d <= SapperRules.CANNON_RANGE - 4) return s.blockPos();
        }
        return null;
    }

    @Nullable
    private static FieldCannonEntity cannonOf(ServerLevel level, Lane lane) {
        return lane.cannon != null && level.getEntity(lane.cannon) instanceof FieldCannonEntity c && c.isAlive() ? c : null;
    }

    private static void discardCannon(ServerLevel level, Lane lane) {
        FieldCannonEntity c = cannonOf(level, lane);
        if (c != null) c.discard();
        lane.cannon = null;
    }

    private static void addLadder(Host host, Lane lane, Breach.Ladder l) {
        lane.ladders.add(l);
        if (!host.ladders().contains(l.key())) host.ladders().add(l.key());
        host.changed();
    }

    /** La escalera que hay en {@code base} (medida de nuevo), o null si ya no está. */
    @Nullable
    public static Breach.Ladder ladderAt(ServerLevel level, BlockPos base) {
        var s = level.getBlockState(base);
        if (!s.is(Blocks.LADDER)) return null;
        Direction facing = s.getValue(LadderBlock.FACING);
        int top = base.getY();
        while (level.getBlockState(base.atY(top)).is(Blocks.LADDER)) top++;
        return new Breach.Ladder(base, facing.getOpposite(), top);
    }

    /** El zapador va al pie del muro, deja el TNT encendido y sale corriendo. Cuando explota, el carril se replanea. */
    private static void blast(ServerLevel level, Host host, Lane lane, AbstractRecruitEntity sapper, Breach.Walls w, long now) {
        if (lane.outside == null || lane.wall == null) {
            lane.entry = Entry.NONE;
            return;
        }
        if (lane.blastAt >= 0) {
            if (now >= lane.blastAt) {
                lane.blasts++;
                lane.blastAt = -1;
                lane.wall = null;
                lane.planned = Long.MIN_VALUE / 2;   // ¿abrió? a planear de nuevo
            }
            return;
        }
        if (now - lane.sapperSince > SAPPER_TIMEOUT) {
            lane.noBreaker = true;
            lane.entry = Entry.NONE;
            return;
        }
        Vec3 spot = Vec3.atBottomCenterOf(lane.outside);
        sapper.setTarget(null);
        Mover.Run r = Mover.run(sapper);
        if (r == null || !r.active() || r.goal.distanceToSqr(spot) > 1) Mover.go(sapper, spot, 0.8, NavCaps.INVADER, bounds(host.center(), w), BY);
        if (horizontal(sapper.position(), spot) <= 2.2 && Math.abs(sapper.getY() - spot.y) <= 2) {
            if (!SapperRules.takeTnt(sapper)) {
                lane.noBreaker = true;
                lane.entry = Entry.NONE;
                return;
            }
            SapperRules.markHole(SapperRules.spawnTnt(level, spot, Vec3.ZERO, FUSE, sapper, null), lane.hole);
            lane.blastAt = now + FUSE + 20;
            Mover.go(sapper, Vec3.atBottomCenterOf(lane.stage), 3, NavCaps.INVADER, bounds(host.center(), w), BY);   // a correr
            host.announce(Event.BOOM);
        }
    }

    /** El zapador atiende el cañón (al lado, cargándolo); cada tanto el carril se replanea para ver si abrió. */
    private static void cannon(ServerLevel level, Lane lane, AbstractRecruitEntity sapper) {
        FieldCannonEntity c = cannonOf(level, lane);
        if (c == null || sapper.getInventory().countItem(Items.TNT) == 0) {
            discardCannon(level, lane);
            lane.noBreaker = sapper.getInventory().countItem(Items.TNT) == 0;
            lane.entry = Entry.NONE;
            return;
        }
        sapper.setTarget(null);
        if (sapper.distanceTo(c) > 3) {
            Mover.Run r = Mover.run(sapper);
            if (r == null || !r.active()) Mover.go(sapper, c.position(), 1.5, NavCaps.INVADER, NavPath.Bounds.around(c.blockPosition(), 48), BY);
        } else {
            Mover.stop(sapper);
            moveTo(sapper, sapper.position());
            sapper.getLookControl().setLookAt(c);
        }
    }

    // ------------------------------------------------------------------ grupos

    /** Los soldados en grupos de 3 a 5 (siempre los mismos mientras estén vivos); el primero de cada uno guía. */
    public static List<List<AbstractRecruitEntity>> squads(List<AbstractRecruitEntity> soldiers) {
        List<AbstractRecruitEntity> sorted = new ArrayList<>(soldiers);
        sorted.sort(Comparator.comparing(e -> e.getUUID().toString()));
        List<List<AbstractRecruitEntity>> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i += SQUAD_SIZE) out.add(new ArrayList<>(sorted.subList(i, Math.min(sorted.size(), i + SQUAD_SIZE))));
        if (out.size() > 1 && out.get(out.size() - 1).size() < 3) out.get(out.size() - 2).addAll(out.remove(out.size() - 1));
        return out;
    }

    /**
     * Un grupo hacia adentro: el guía busca el camino por la malla (sin trepar ni romper: usa los huecos y las escaleras
     * que ya están), los demás siguen el mismo camino y el guía espera si alguno quedó lejos. Sin camino todavía (se está
     * poniendo la escalera o rompiendo el muro): esperan cerca del cruce.
     */
    private static void squad(ServerLevel level, Lane lane, List<AbstractRecruitEntity> squad, Vec3 goal, Breach.Walls w, BlockPos center,
                              long now) {
        AbstractRecruitEntity lead = squad.get(0);
        Mover.Run r = Mover.run(lead);
        boolean fresh = r != null && r.active() && now - r.started < REPLAN_TICKS * 2;
        if (!fresh || now - lane.planned < 20 && lane.entry != Entry.STUCK) {
            boolean ok = Mover.go(lead, goal, goalReach(w), NavCaps.INVADER, bounds(center, w), BY) && reaches(Mover.run(lead), goal, goalReach(w));
            if (!ok && lane.entry == Entry.OPEN && lane.outside != null) {
                // El hueco puede quedar lejos (del otro lado): primero hasta la entrada, por afuera; de ahí, adentro
                Vec3 gate = Vec3.atBottomCenterOf(lane.outside);
                ok = Mover.go(lead, gate, 1.5, NavCaps.INVADER, bounds(center, w), BY) && reaches(Mover.run(lead), gate, 1.5);
            }
            if (!ok) {
                Mover.stop(lead);
                if (lane.entry == Entry.OPEN && now - lane.planned >= 40) {   // el carril cree que hay hueco, pero desde acá no: replanear desde el guía
                    lane.planFrom = lead.blockPosition();
                    lane.planned = Long.MIN_VALUE / 2;
                }
                Vec3 stage = Vec3.atBottomCenterOf(lane.stage);
                Vec3 wait = lane.outside != null ? Vec3.atBottomCenterOf(lane.outside).add(stage.subtract(Vec3.atBottomCenterOf(lane.outside)).normalize().scale(6))
                        : stage;
                for (AbstractRecruitEntity s : squad) {
                    Mover.stop(s);
                    s.setShouldBlock(true);
                    int hsh = s.getUUID().hashCode();
                    moveTo(s, Breach.dryNear(level, wait.add(((hsh & 3) - 1.5) * 1.3, 0, (((hsh >> 2) & 3) - 1.5) * 1.3)));
                }
                return;
            }
            r = Mover.run(lead);
        }
        if (r == null) return;
        double straggler = 0;
        for (int i = 1; i < squad.size(); i++) {
            AbstractRecruitEntity s = squad.get(i);
            Mover.Run fr = Mover.run(s);
            if (fr == null || !fr.active() || fr.path != r.path) Mover.start(s, r.path, goal, NavCaps.INVADER, BY);
            fr = Mover.run(s);
            if (fr != null) fr.paused = fr.i > r.i;   // no se adelanta al guía
            straggler = Math.max(straggler, horizontal(s.position(), lead.position()));
        }
        r.paused = straggler > SQUAD_WAIT && !onLadder(lead) && !lead.isPassenger();
        for (AbstractRecruitEntity s : squad) s.setShouldBlock(false);
    }

    /** ¿El camino llega a {@code reach} de {@code goal} (no se quedó corto contra el muro)? */
    public static boolean reaches(@Nullable Mover.Run r, Vec3 goal, double reach) {
        if (r == null) return false;
        Vec3 end = r.path.get(r.path.size() - 1).center();
        return horizontal(end, goal) <= reach + 1.5;
    }

    public static boolean onLadder(AbstractRecruitEntity s) {
        return s.onClimbable() || s.level().getBlockState(s.blockPosition()).is(Blocks.LADDER);
    }

    // ------------------------------------------------------------------ para la defensa y el fin

    public static List<LaneInfo> info(@Nullable State st) {
        List<LaneInfo> out = new ArrayList<>();
        if (st == null) return out;
        for (Lane l : st.lanes) {
            List<BlockPos> ladders = new ArrayList<>();
            for (Breach.Ladder x : l.ladders) ladders.add(x.base());
            out.add(new LaneInfo(l.index, l.entry, Vec3.atBottomCenterOf(l.stage), l.outside, l.inside, ladders, l.attackers));
        }
        return out;
    }

    /** Terminó: se sacan las escaleras y los cañones. */
    public static void end(ServerLevel level, Host host, @Nullable State st) {
        if (st != null) for (Lane l : st.lanes) discardCannon(level, l);
        for (long l : host.ladders()) Breach.removeLadder(level, BlockPos.of(l));
        host.ladders().clear();
        host.changed();
        if (!level.isPositionEntityTicking(host.center())) return;
        long key = host.center().asLong();
        for (FieldCannonEntity c : level.getEntitiesOfClass(FieldCannonEntity.class, new AABB(host.center()).inflate(128),
                c -> c.getPersistentData().getLong(TAG_CANNON) == key)) c.discard();
    }

    /** Un cañón de asedio que se carga del disco: sobra (el carril arma otro si todavía hace falta). */
    public static boolean isSiegeCannon(Entity e) {
        return e instanceof FieldCannonEntity c && c.getPersistentData().contains(TAG_CANNON);
    }

    // ------------------------------------------------------------------ órdenes a un soldado

    public static void moveTo(AbstractRecruitEntity s, Vec3 pos) {
        Vec3 hold = s.getHoldPos();
        if (hold == null || hold.distanceToSqr(pos) > 1.0 || s.getFollowState() != BACK_TO_POS) {
            if (BattleGeneral.roleOf(s) != BattleGeneral.Role.RANGED) s.setTarget(null);
            s.setHoldPos(pos);
            s.setFollowState(BACK_TO_POS);
        }
    }

    /** A pelear con el más cercano; sin nadie a quien pelear, al centro. */
    public static void engageOrCenter(AbstractRecruitEntity s, List<LivingEntity> enemies, BlockPos center) {
        s.setShouldBlock(false);
        if (enemies.isEmpty()) {
            moveTo(s, Vec3.atBottomCenterOf(center));
            return;
        }
        engage(s, enemies);
    }

    public static void engage(AbstractRecruitEntity s, List<LivingEntity> enemies) {
        LivingEntity current = s.getTarget();
        boolean keep = current != null && current.isAlive() && !Surrender.is(current) && s.distanceTo(current) <= KEEP_FIGHTING;
        if (!keep) {
            LivingEntity t = nearest(s, enemies);
            if (t != null && s.canAttack(t)) s.setTarget(t);
        }
        if (s.getFollowState() != WANDER) s.setFollowState(WANDER);
    }

    public static void aim(AbstractRecruitEntity s, List<LivingEntity> enemies) {
        LivingEntity current = s.getTarget();
        if (current != null && current.isAlive() && !Surrender.is(current) && s.distanceTo(current) <= RANGED_REACH) return;
        LivingEntity t = nearest(s, enemies);
        if (t != null && s.distanceTo(t) <= RANGED_REACH && s.canAttack(t)) s.setTarget(t);
    }

    private static boolean anyInRange(List<AbstractRecruitEntity> ranged, List<LivingEntity> enemies) {
        for (AbstractRecruitEntity s : ranged) if (nearestDistance(s, enemies) <= RANGED_REACH) return true;
        return false;
    }

    private static boolean isFighting(AbstractRecruitEntity s) {
        LivingEntity t = s.getTarget();
        return t != null && t.isAlive() && !Surrender.is(t) && s.distanceTo(t) <= KEEP_FIGHTING && s.getFollowState() == WANDER;
    }

    // ------------------------------------------------------------------ geometría

    public static double dist(Entity e, BlockPos c) {
        return horizontal(e.position(), Vec3.atBottomCenterOf(c));
    }

    public static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    @Nullable
    public static LivingEntity nearest(LivingEntity from, List<LivingEntity> candidates) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            if (!e.isAlive() || Surrender.is(e)) continue;
            double d = from.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    public static double nearestDistance(LivingEntity from, List<LivingEntity> candidates) {
        LivingEntity n = nearest(from, candidates);
        return n == null ? Double.MAX_VALUE : from.distanceTo(n);
    }

    private static void log(Host host, Lane lane, String what) {
        com.ale.warbandcraft.WarbandCraft.LOGGER.info("Asedio a {}: carril {} ({}°): {}", host.name(), lane.index,
                Math.round(Math.toDegrees(lane.angle)), what);
    }
}
