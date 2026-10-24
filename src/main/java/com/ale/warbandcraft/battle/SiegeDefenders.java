package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.nav.NavGrid;
import com.ale.warbandcraft.nav.NavPath;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * El bando defensor de un asedio (lo usan tu cuartel contra las invasiones, la guarnición de un castillo o una aldea
 * contra tus soldados y las batallas entre facciones):
 * <ul>
 *   <li>Se reparten por los carriles atacados ({@link SiegeLanes}), según cuántos vienen por cada uno.</li>
 *   <li>Los arqueros buscan el mejor lugar para tirar: alto (muralla, torres, techos), con vista a por donde vienen y a
 *       buena distancia. Llegan caminando por la malla, escaleras de las torres incluidas.</li>
 *   <li>Los de a pie van a tumbar las escaleras (desde arriba del muro o desde el pie de adentro, con la horquilla); si
 *       no pueden llegar, esperan adentro, al lado de donde van a entrar, para darles cuando entren.</li>
 * </ul>
 * Los refuerzos y lo que es de cada clase de pelea los pone quien llama ({@link Host}).
 */
public final class SiegeDefenders {
    /** Lo que es de cada clase de pelea. */
    public interface Host {
        BlockPos center();

        /** A quién se le avisa que tumbaron una escalera, o null. */
        @Nullable
        UUID listener();

        /** Tumbaron la escalera de {@code base}: sacarla de la lista del atacante. */
        void ladderPushed(BlockPos base);
    }

    /** Tumbar: a tanto (horizontal) de la escalera. Cada escalera, cuántos la van a tumbar. */
    public static double PUSH_REACH = 2.6;
    public static int PUSHERS_PER_LADDER = 2;
    /** Enemigo adentro a tanto: se le va al humo. */
    public static double ENGAGE_INSIDE = 12;
    /** Arqueros: dónde buscan lugar alrededor del cruce, a qué distancia les gusta, separación entre ellos. */
    public static int SPOT_SEARCH = 18;
    public static double SPOT_BEST_DISTANCE = 14, SPOT_SPACING = 2.0, RANGED_REACH = 48;
    /** Cada cuánto se vuelven a buscar los lugares de los arqueros (ticks). */
    public static int SPOT_REFRESH = 20 * 10;

    private static final int WANDER = 0, BACK_TO_POS = 3, NEUTRAL = 0;
    private static final String BY = "defense";

    private enum Job { ARCHER, PUSHER, HOLD }

    private record Assignment(Job job, int lane, @Nullable BlockPos spot, @Nullable BlockPos ladder) {}

    private static final class Memory {
        final Map<UUID, Assignment> jobs = new HashMap<>();
        final Map<Integer, List<BlockPos>> spots = new HashMap<>();
        final Map<Integer, Long> spotsAt = new HashMap<>();
        final Set<Long> badSpots = new HashSet<>();
        final Set<UUID> cantPush = new HashSet<>();
    }

    private static final Map<String, Memory> MEMORY = new HashMap<>();

    private SiegeDefenders() {}

    public static void clear() {
        MEMORY.clear();
    }

    // ------------------------------------------------------------------ cada segundo

    /**
     * Una vuelta (1 s) de la defensa. {@code key}: la pelea (para recordar quién hace qué); {@code defenders}: los que
     * defienden (ya de a pie, sin el capitán); {@code enemies}: los atacantes; {@code caps}: cómo caminan.
     */
    public static void tick(ServerLevel level, String key, Host host, List<AbstractRecruitEntity> defenders, List<LivingEntity> enemies,
                            List<SiegeLanes.LaneInfo> allLanes, Breach.Walls w, NavCaps caps) {
        BlockPos center = host.center();
        Memory m = MEMORY.computeIfAbsent(key, k -> new Memory());
        long now = level.getGameTime();
        List<SiegeLanes.LaneInfo> lanes = new ArrayList<>(allLanes);
        lanes.removeIf(l -> l.attackers() == 0);
        if (lanes.isEmpty()) return;
        m.jobs.keySet().removeIf(id -> defenders.stream().noneMatch(d -> d.getUUID().equals(id)));
        assign(level, m, defenders, lanes, now);

        for (AbstractRecruitEntity s : defenders) {
            if (s.getState() != NEUTRAL) s.setAggroState(NEUTRAL);
            Assignment a = m.jobs.get(s.getUUID());
            if (a == null) continue;
            SiegeLanes.LaneInfo lane = laneOf(lanes, a.lane());
            // Enemigo adentro y cerca: a pelear (los arqueros tiran desde donde están)
            LivingEntity inside = nearestInside(s, enemies, center, w);
            if (a.job() != Job.ARCHER && inside != null && s.distanceTo(inside) <= ENGAGE_INSIDE) {
                Mover.stop(s);
                engage(s, inside);
                continue;
            }
            switch (a.job()) {
                case ARCHER -> archer(s, a, m, enemies, center, caps);
                case PUSHER -> pusher(level, host, s, a, m, enemies, center, w, caps);
                case HOLD -> hold(level, s, lane, center, w, enemies, caps);
            }
        }
    }

    // ------------------------------------------------------------------ repartir

    /**
     * Arqueros a los mejores lugares de cada carril; a pie: a cada escalera {@link #PUSHERS_PER_LADDER} para tumbarla,
     * el resto a esperar adentro, repartidos según cuántos atacan por cada carril.
     */
    private static void assign(ServerLevel level, Memory m, List<AbstractRecruitEntity> defenders, List<SiegeLanes.LaneInfo> lanes, long now) {
        int total = 0;
        for (SiegeLanes.LaneInfo l : lanes) total += l.attackers();
        Map<Integer, Integer> want = new HashMap<>(), have = new HashMap<>();
        List<AbstractRecruitEntity> foot = new ArrayList<>(), ranged = new ArrayList<>();
        for (AbstractRecruitEntity s : defenders) (BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED ? ranged : foot).add(s);
        for (SiegeLanes.LaneInfo l : lanes) want.put(l.index(), Math.max(1, (int) Math.round(foot.size() * (double) l.attackers() / Math.max(1, total))));

        // Los que ya tienen un trabajo válido lo siguen haciendo
        Map<BlockPos, Integer> pushers = new HashMap<>();
        for (AbstractRecruitEntity s : defenders) {
            Assignment a = m.jobs.get(s.getUUID());
            if (a == null) continue;
            SiegeLanes.LaneInfo lane = laneOf(lanes, a.lane());
            boolean ok = lane != null && switch (a.job()) {
                case ARCHER -> BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED && a.spot() != null && !m.badSpots.contains(a.spot().asLong());
                case PUSHER -> a.ladder() != null && lane.ladders().contains(a.ladder()) && !m.cantPush.contains(s.getUUID());
                case HOLD -> BattleGeneral.roleOf(s) != BattleGeneral.Role.RANGED;
            };
            if (!ok) {
                m.jobs.remove(s.getUUID());
                continue;
            }
            if (a.job() != Job.ARCHER) have.merge(a.lane(), 1, Integer::sum);
            if (a.job() == Job.PUSHER) pushers.merge(a.ladder(), 1, Integer::sum);
        }

        List<SiegeLanes.LaneInfo> byThreat = new ArrayList<>(lanes);
        byThreat.sort(Comparator.comparingInt(l -> -l.attackers()));
        int li = 0;
        for (AbstractRecruitEntity s : ranged) {
            if (m.jobs.containsKey(s.getUUID())) continue;
            SiegeLanes.LaneInfo lane = byThreat.get(li++ % byThreat.size());
            BlockPos spot = freeSpot(level, m, lane, now);
            if (spot != null) m.jobs.put(s.getUUID(), new Assignment(Job.ARCHER, lane.index(), spot, null));
        }
        for (AbstractRecruitEntity s : foot) {
            if (m.jobs.containsKey(s.getUUID())) continue;
            SiegeLanes.LaneInfo lane = byThreat.get(0);
            int need = Integer.MIN_VALUE;
            for (SiegeLanes.LaneInfo l : lanes) {
                int n = want.getOrDefault(l.index(), 1) - have.getOrDefault(l.index(), 0);
                if (n > need) {
                    need = n;
                    lane = l;
                }
            }
            have.merge(lane.index(), 1, Integer::sum);
            BlockPos ladder = null;
            if (!m.cantPush.contains(s.getUUID())) {
                for (BlockPos l : lane.ladders()) {
                    if (pushers.getOrDefault(l, 0) < PUSHERS_PER_LADDER) {
                        ladder = l;
                        break;
                    }
                }
            }
            if (ladder != null) {
                pushers.merge(ladder, 1, Integer::sum);
                m.jobs.put(s.getUUID(), new Assignment(Job.PUSHER, lane.index(), null, ladder));
            } else {
                m.jobs.put(s.getUUID(), new Assignment(Job.HOLD, lane.index(), null, null));
            }
        }
    }

    @Nullable
    private static SiegeLanes.LaneInfo laneOf(List<SiegeLanes.LaneInfo> lanes, int index) {
        for (SiegeLanes.LaneInfo l : lanes) if (l.index() == index) return l;
        return null;
    }

    // ------------------------------------------------------------------ arqueros

    /** Va a su lugar y, al llegar, tira al más cercano a tiro. */
    private static void archer(AbstractRecruitEntity s, Assignment a, Memory m, List<LivingEntity> enemies, BlockPos center, NavCaps caps) {
        BlockPos spot = a.spot();
        if (spot == null) return;
        Vec3 at = Vec3.atBottomCenterOf(spot);
        if (s.position().distanceTo(at) > 1.6) {
            Mover.Run r = Mover.run(s);
            if (r == null || !r.active() || r.goal.distanceToSqr(at) > 1) {
                if (r != null && r.failed || !Mover.go(s, at, 0.6, caps, NavPath.Bounds.around(center, 96), BY)) {
                    m.badSpots.add(spot.asLong());   // no llega: otro lugar
                    m.jobs.remove(s.getUUID());
                }
            }
            aim(s, enemies);
            return;
        }
        Mover.stop(s);
        if (s.getFollowState() != BACK_TO_POS || s.getHoldPos() == null || s.getHoldPos().distanceToSqr(at) > 1) {
            s.setHoldPos(at);
            s.setFollowState(BACK_TO_POS);
        }
        aim(s, enemies);
    }

    @Nullable
    private static BlockPos freeSpot(ServerLevel level, Memory m, SiegeLanes.LaneInfo lane, long now) {
        Long at = m.spotsAt.get(lane.index());
        if (at == null || now - at > SPOT_REFRESH) {
            m.spots.put(lane.index(), spots(level, lane));
            m.spotsAt.put(lane.index(), now);
        }
        Set<Long> taken = new HashSet<>();
        for (Assignment a : m.jobs.values()) if (a.spot() != null) taken.add(a.spot().asLong());
        for (BlockPos p : m.spots.getOrDefault(lane.index(), List.of())) {
            if (m.badSpots.contains(p.asLong())) continue;
            boolean near = false;
            for (long t : taken) if (BlockPos.of(t).distSqr(p) < SPOT_SPACING * SPOT_SPACING) near = true;
            if (!near) return p;
        }
        return null;
    }

    /**
     * Lugares para tirar cerca del cruce del carril, del mejor al peor: alto, con vista a por donde vienen (el pie del
     * muro, su lado de afuera y el medio) y a buena distancia. Nada de agua.
     */
    public static List<BlockPos> spots(ServerLevel level, SiegeLanes.LaneInfo lane) {
        BlockPos cross = lane.inside() != null ? lane.inside() : lane.outside();
        if (cross == null) return List.of();
        Vec3 out = lane.outside() != null ? Vec3.atBottomCenterOf(lane.outside()) : lane.approach();
        Vec3[] look = {out.add(0, 1.2, 0), lane.approach().add(0, 1.2, 0), out.add(lane.approach()).scale(0.5).add(0, 1.2, 0)};
        int baseY = cross.getY();
        record Scored(BlockPos p, double score) {}
        List<Scored> all = new ArrayList<>();
        for (int dx = -SPOT_SEARCH; dx <= SPOT_SEARCH; dx += 2) {
            for (int dz = -SPOT_SEARCH; dz <= SPOT_SEARCH; dz += 2) {
                int x = cross.getX() + dx, z = cross.getZ() + dz;
                for (int y : NavGrid.standable(level, x, z, baseY - 3, baseY + 24)) {
                    if (NavGrid.flagsAt(level, x, y, z) != 0) continue;   // ni agua, ni escalera, ni puerta
                    BlockPos p = new BlockPos(x, y, z);
                    if (lane.outside() != null && p.distSqr(lane.outside()) < 9) continue;
                    Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.6, 0);
                    int seen = 0;
                    for (Vec3 l : look) {
                        if (level.clip(new ClipContext(eye, l, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() == HitResult.Type.MISS) seen++;
                    }
                    if (seen == 0) continue;
                    double d = eye.distanceTo(out);
                    if (d > RANGED_REACH - 6) continue;
                    all.add(new Scored(p, 2.0 * (y - baseY) + 6 * seen - 0.4 * Math.abs(d - SPOT_BEST_DISTANCE)));
                }
            }
        }
        all.sort(Comparator.comparingDouble(sc -> -sc.score()));
        List<BlockPos> outList = new ArrayList<>();
        for (int i = 0; i < Math.min(40, all.size()); i++) outList.add(all.get(i).p());
        return outList;
    }

    // ------------------------------------------------------------------ tumbar escaleras

    /**
     * Va a la escalera: arriba del muro (al lado de donde sale) o al pie de adentro. Si llega, la tumba (el que estaba
     * subiendo se cae). Si no puede llegar, pasa a esperar adentro.
     */
    private static void pusher(ServerLevel level, Host host, AbstractRecruitEntity s, Assignment a, Memory m, List<LivingEntity> enemies,
                               BlockPos center, Breach.Walls w, NavCaps caps) {
        BlockPos base = a.ladder();
        Breach.Ladder l = base == null ? null : SiegeLanes.ladderAt(level, base);
        if (l == null) {
            m.jobs.remove(s.getUUID());
            return;
        }
        Vec3 top = Vec3.atBottomCenterOf(l.wallTop()), in = Vec3.atBottomCenterOf(l.inside());
        double h = Math.min(horizontal(s.position(), Vec3.atBottomCenterOf(l.base())), horizontal(s.position(), top));
        if (h <= PUSH_REACH && s.getY() >= l.base().getY() - 1 && s.getY() <= l.topFeet() + 2) {
            push(level, host, s, l, enemies);
            m.jobs.remove(s.getUUID());
            return;
        }
        Mover.Run r = Mover.run(s);
        if (r != null && r.active()) return;
        NavPath.Bounds b = NavPath.Bounds.around(center, w.radius() + 24);
        // Primero arriba del muro; si no se llega, al pie de adentro
        if (r != null && r.failed || !Mover.go(s, top, 1.5, caps, b, BY) || !reaches(Mover.run(s), top, 2.5)) {
            BlockPos foot = NavGrid.nearestNode(level, l.inside().atY(l.base().getY()), 2);
            if (foot == null || !Mover.go(s, Vec3.atBottomCenterOf(foot), 1.0, caps, b, BY) || !reaches(Mover.run(s), in, PUSH_REACH + 1)) {
                Mover.stop(s);
                m.cantPush.add(s.getUUID());
                m.jobs.remove(s.getUUID());
            }
        }
    }

    private static boolean reaches(@Nullable Mover.Run r, Vec3 goal, double reach) {
        if (r == null) return false;
        Vec3 end = r.path.get(r.path.size() - 1).center();
        return horizontal(end, goal) <= reach + 0.5;
    }

    /** Tumba la escalera; los que iban subiendo se caen para atrás. */
    private static void push(ServerLevel level, Host host, AbstractRecruitEntity s, Breach.Ladder l, List<LivingEntity> enemies) {
        Mover.stop(s);
        s.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        Breach.removeLadder(level, l.base());
        host.ladderPushed(l.base());
        level.playSound(null, l.base(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2F, 0.7F);
        for (LivingEntity e : enemies) {
            if (horizontal(e.position(), Vec3.atBottomCenterOf(l.base())) > 1.2 || e.getY() < l.base().getY() + 0.5) continue;
            Mover.stop(e);
            e.setDeltaMovement(-l.intoWall().getStepX() * 0.5, 0.2, -l.intoWall().getStepZ() * 0.5);
        }
        ServerPlayer p = host.listener() == null ? null : level.getServer().getPlayerList().getPlayer(host.listener());
        if (p != null) p.displayClientMessage(Component.translatable("msg.warbandcraft.defense.ladder_down").withStyle(ChatFormatting.GOLD), true);
    }

    // ------------------------------------------------------------------ esperar adentro

    /** Espera adentro del cruce del carril (formando, tapando el hueco) y le pega al que entra. */
    private static void hold(ServerLevel level, AbstractRecruitEntity s, @Nullable SiegeLanes.LaneInfo lane, BlockPos center, Breach.Walls w,
                             List<LivingEntity> enemies, NavCaps caps) {
        if (lane == null) return;
        LivingEntity in = nearestInside(s, enemies, center, w);
        if (in != null && s.distanceTo(in) <= ENGAGE_INSIDE + 6) {
            Mover.stop(s);
            engage(s, in);
            return;
        }
        BlockPos cross = lane.inside();
        if (cross == null) return;
        int h = s.getUUID().hashCode();
        Vec3 c = Vec3.atBottomCenterOf(cross);
        Vec3 toCenter = new Vec3(center.getX() + 0.5 - c.x, 0, center.getZ() + 0.5 - c.z);
        toCenter = toCenter.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : toCenter.normalize();
        Vec3 side = new Vec3(-toCenter.z, 0, toCenter.x);
        Vec3 spot = c.add(toCenter.scale(2 + (h & 1) * 1.8)).add(side.scale(((h >> 1) % 5 - 2) * 1.5));
        BlockPos node = NavGrid.nearestNode(level, BlockPos.containing(spot), 3);
        Vec3 at = node != null ? Vec3.atBottomCenterOf(node) : spot;
        if (s.position().distanceTo(at) > 2) {
            Mover.Run r = Mover.run(s);
            if (r == null || !r.active() || r.goal.distanceToSqr(at) > 4) Mover.go(s, at, 1.0, caps, NavPath.Bounds.around(center, 96), BY);
            return;
        }
        Mover.stop(s);
        s.setShouldBlock(true);
        if (s.getFollowState() != BACK_TO_POS || s.getHoldPos() == null || s.getHoldPos().distanceToSqr(at) > 1) {
            s.setHoldPos(at);
            s.setFollowState(BACK_TO_POS);
        }
    }

    // ------------------------------------------------------------------ fin

    /** Terminó: se olvida quién hacía qué y los defensores quedan sueltos. */
    public static void end(String key, List<AbstractRecruitEntity> defenders) {
        MEMORY.remove(key);
        for (AbstractRecruitEntity s : defenders) {
            Mover.stop(s);
            s.setShouldBlock(false);
        }
    }

    // ------------------------------------------------------------------ ayudas

    @Nullable
    private static LivingEntity nearestInside(AbstractRecruitEntity s, List<LivingEntity> enemies, BlockPos center, Breach.Walls w) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : enemies) {
            if (!e.isAlive() || Surrender.is(e)) continue;
            if (horizontal(e.position(), Vec3.atBottomCenterOf(center)) > w.inside() + 1) continue;
            double d = s.distanceToSqr(e);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static void engage(AbstractRecruitEntity s, LivingEntity t) {
        s.setShouldBlock(false);
        if (s.getTarget() != t && s.canAttack(t)) s.setTarget(t);
        if (s.getFollowState() != WANDER) s.setFollowState(WANDER);
    }

    private static void aim(AbstractRecruitEntity s, List<LivingEntity> enemies) {
        LivingEntity current = s.getTarget();
        if (current != null && current.isAlive() && !Surrender.is(current) && s.distanceTo(current) <= RANGED_REACH) return;
        LivingEntity best = null;
        double bd = RANGED_REACH * RANGED_REACH;
        for (LivingEntity e : enemies) {
            double d = s.distanceToSqr(e);
            if (e.isAlive() && !Surrender.is(e) && d < bd && s.hasLineOfSight(e)) {
                bd = d;
                best = e;
            }
        }
        if (best != null && s.canAttack(best)) s.setTarget(best);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
