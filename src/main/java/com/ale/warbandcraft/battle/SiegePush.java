package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.nav.NavPath;
import com.ale.warbandcraft.nav.Step;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reglas comunes a todos los asedios (pedido de Ale: que nunca se queden parados y el asedio termine siempre):
 * <ul>
 *   <li><b>Lado más débil</b> ({@link #weakestAngles}): los atacantes aparecen siempre afuera del muro, del lado más fácil
 *       de entrar (hueco abierto antes que escaleras, escaleras antes que romper, pocos defensores cerca), no siempre del
 *       mismo lado.</li>
 *   <li><b>Prioridades adentro</b> ({@link #insideTarget}): enemigos cerca, después aldeanos cerca, después al centro; en
 *       el centro sin nadie cerca, a buscar al enemigo más cercano.</li>
 *   <li><b>Destrabe</b> ({@link #watch}): si en {@link #STALL_SECONDS} nadie perdió vida, todos salen a buscar al enemigo
 *       más cercano por la malla; si en {@link #GIVE_UP_SECONDS} sigue igual, la pelea se decide con lo que queda.</li>
 * </ul>
 */
public final class SiegePush {
    /** Sin que nadie pierda vida tanto tiempo (s): todos a buscar al enemigo. */
    public static int STALL_SECONDS = 30;
    /** Sin que nadie pierda vida tanto tiempo (s): se decide con lo que queda de cada lado. */
    public static int GIVE_UP_SECONDS = 300;
    /** Adentro: un enemigo a tanto, se lo ataca primero; un aldeano a tanto, después. */
    public static double NEAR_ENEMY = 16, NEAR_VILLAGER = 10;
    /** Llegó al centro si está a tanto. */
    public static double AT_CENTER = 6;
    /** Buscar a uno lejos: cada cuánto se vuelve a calcular el camino (ticks). */
    public static int HUNT_REPATH = 60;
    /** Lado más débil: cuántos lados se prueban; cuánto pesa cada cosa (en bloques caminados). */
    public static int SIDES = 8;
    public static double CLIMB_PENALTY = 30, BREAK_PENALTY = 50, DEFENDER_PENALTY = 6, TURN_PENALTY = 10, RANDOM_PENALTY = 15;

    public enum Stage { NORMAL, PUSH, GIVE_UP }

    private static final class Watch {
        double hp = -1;
        int count = -1;
        long last;
        Stage stage = Stage.NORMAL;
    }

    private static final Map<String, Watch> WATCHES = new HashMap<>();
    private static final Map<UUID, Long> HUNTS = new HashMap<>();

    private SiegePush() {}

    // ------------------------------------------------------------------ destrabe

    /**
     * Una vuelta (1 s) de la pelea {@code key}: mira si alguien perdió vida (o cayó). Devuelve en qué está: normal, a
     * buscarse (PUSH) o decidir con lo que queda (GIVE_UP).
     */
    public static Stage watch(String key, long now, List<? extends LivingEntity> a, List<? extends LivingEntity> b) {
        Watch w = WATCHES.computeIfAbsent(key, k -> new Watch());
        double hp = 0;
        for (LivingEntity e : a) hp += e.getHealth();
        for (LivingEntity e : b) hp += e.getHealth();
        int count = a.size() + b.size();
        if (w.hp < 0 || hp < w.hp - 0.5 || count < w.count) w.last = now;
        w.hp = hp;
        w.count = count;
        long idle = now - w.last;
        w.stage = idle >= GIVE_UP_SECONDS * 20L ? Stage.GIVE_UP : idle >= STALL_SECONDS * 20L ? Stage.PUSH : Stage.NORMAL;
        return w.stage;
    }

    /** En qué quedó la pelea {@code key} la última vez que se miró (sin mirarla de nuevo). */
    public static Stage peek(String key) {
        Watch w = WATCHES.get(key);
        return w == null ? Stage.NORMAL : w.stage;
    }

    public static void forget(String key) {
        WATCHES.remove(key);
    }

    public static void clear() {
        WATCHES.clear();
        HUNTS.clear();
    }

    /** ¿Quién gana si se decide con lo que queda? True = el bando {@code a}. */
    public static boolean aWins(List<? extends LivingEntity> a, List<? extends LivingEntity> b) {
        return BattleGeneral.power(a) >= BattleGeneral.power(b);
    }

    /**
     * Destrabe: cada uno de {@code side} sale a buscar al enemigo más cercano. Cerca, pelea; lejos, va por la malla
     * (con {@code caps}); sin camino, pelea igual (lo lleva Recruits).
     */
    public static void huntAll(ServerLevel level, List<? extends AbstractRecruitEntity> side, List<? extends LivingEntity> foes, BlockPos center,
                               NavCaps caps) {
        if (foes.isEmpty()) return;
        List<LivingEntity> list = new ArrayList<>(foes);
        for (AbstractRecruitEntity s : side) hunt(level, s, list, center, caps);
    }

    public static void hunt(ServerLevel level, AbstractRecruitEntity s, List<LivingEntity> foes, BlockPos center, NavCaps caps) {
        LivingEntity t = SiegeLanes.nearest(s, foes);
        if (t == null) return;
        s.setShouldBlock(false);
        double d = s.distanceTo(t);
        if (d <= 6 || BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED && d <= SiegeLanes.RANGED_REACH * 0.6 && s.hasLineOfSight(t)) {
            Mover.stop(s);
            if (s.getTarget() != t) s.setTarget(t);
            if (s.getFollowState() != 0) s.setFollowState(0);
            return;
        }
        long now = level.getGameTime();
        Long last = HUNTS.get(s.getUUID());
        if (Mover.moving(s) && last != null && now - last < HUNT_REPATH) return;
        HUNTS.put(s.getUUID(), now);
        if (Mover.go(s, t.position(), 2.5, caps, NavPath.Bounds.around(center, 128), "push")) {
            s.setTarget(null);
            return;
        }
        if (s.getTarget() != t) s.setTarget(t);   // sin camino por la malla: que lo intente Recruits
        if (s.getFollowState() != 0) s.setFollowState(0);
    }

    /** ¿Va por la malla buscando a alguien (destrabe o desde el centro)? */
    public static boolean hunting(net.minecraft.world.entity.Entity s) {
        Mover.Run r = Mover.run(s);
        return r != null && r.active() && "push".equals(r.by);
    }

    // ------------------------------------------------------------------ adentro

    /**
     * El objetivo de un atacante que ya entró: un enemigo cerca; si no, un aldeano cerca ({@code villagers}); si no, null
     * (va al centro, o si ya está ahí, a buscar al enemigo más cercano: ver {@link #inside}).
     */
    @Nullable
    public static LivingEntity insideTarget(ServerLevel level, AbstractRecruitEntity s, List<LivingEntity> enemies, boolean villagers) {
        LivingEntity e = SiegeLanes.nearest(s, enemies);
        if (e != null && s.distanceTo(e) <= NEAR_ENEMY) return e;
        if (!villagers) return null;
        LivingEntity current = s.getTarget();
        if (current instanceof Villager v && v.isAlive() && s.distanceTo(v) <= NEAR_VILLAGER * 1.5) return v;
        List<LivingEntity> near = new ArrayList<>(level.getEntitiesOfClass(Villager.class, s.getBoundingBox().inflate(NEAR_VILLAGER, 4, NEAR_VILLAGER),
                v -> v.isAlive() && !v.isBaby()));
        return SiegeLanes.nearest(s, near);
    }

    /** Un atacante adentro: enemigos cerca → aldeanos cerca → al centro → en el centro, a buscar al enemigo. */
    public static void inside(ServerLevel level, AbstractRecruitEntity s, List<LivingEntity> enemies, BlockPos objective, boolean villagers) {
        s.setShouldBlock(false);
        LivingEntity t = insideTarget(level, s, enemies, villagers);
        if (t != null) {
            if (s.getTarget() != t && s.canAttack(t)) s.setTarget(t);
            if (s.getFollowState() != 0) s.setFollowState(0);
            return;
        }
        if (SiegeLanes.dist(s, objective) > AT_CENTER) {
            SiegeLanes.moveTo(s, Vec3.atBottomCenterOf(objective));
            return;
        }
        if (!enemies.isEmpty()) hunt(level, s, enemies, objective, NavCaps.INVADER);
    }

    // ------------------------------------------------------------------ el lado más débil

    /**
     * Los lados desde donde atacar, del más débil al más fuerte (ángulos desde el centro): se prueban {@link #SIDES} lados
     * y en cada uno el camino hasta adentro (hueco &lt; escalera &lt; romper), los defensores cerca de ese tramo de muro, cuánto
     * hay que girar desde {@code preferred} (de donde vienen) y un poco de azar. Solo lados cargados y secos.
     */
    public static List<Double> weakestAngles(ServerLevel level, BlockPos center, Breach.Walls w, double preferred,
                                             List<? extends LivingEntity> defenders, RandomSource random) {
        record Side(double angle, double score) {}
        List<Side> sides = new ArrayList<>();
        Vec3 goal = SiegeLanes.groundCenter(level, center);
        double offset = random.nextDouble() * Math.PI * 2 / SIDES;
        for (int i = 0; i < SIDES; i++) {
            double a = offset + i * Math.PI * 2 / SIDES;
            BlockPos p = BlockPos.containing(Breach.ground(level, Breach.at(center, a, w.radius() + SiegeLanes.STAGE_MARGIN), center.getY()));
            if (!level.isPositionEntityTicking(p) || !Breach.dry(level, p)) continue;
            List<Step> path = NavPath.find(level, p, goal, SiegeLanes.goalReach(w), NavCaps.siege(true), SiegeLanes.bounds(center, w), false);
            if (path == null) continue;
            double score = path.size() + NavPath.water(path) * 3.0;
            if (NavPath.first(path, Step.Kind.BREAK) >= 0) score += BREAK_PENALTY;
            else if (NavPath.first(path, Step.Kind.CLIMB) >= 0) score += CLIMB_PENALTY;
            Vec3 wall = Breach.at(center, a, w.radius());
            for (LivingEntity d : defenders) {
                double dx = d.getX() - wall.x, dz = d.getZ() - wall.z;
                if (dx * dx + dz * dz <= 18 * 18) score += DEFENDER_PENALTY;
            }
            double turn = Math.abs(Math.atan2(Math.sin(a - preferred), Math.cos(a - preferred)));
            score += turn / Math.PI * TURN_PENALTY + random.nextDouble() * RANDOM_PENALTY;
            sides.add(new Side(a, score));
        }
        sides.sort(java.util.Comparator.comparingDouble(Side::score));
        List<Double> out = new ArrayList<>();
        for (Side s : sides) out.add(s.angle());
        if (out.isEmpty()) out.add(preferred);
        return out;
    }

    /**
     * Lugares de partida de {@code lanes} carriles: el primero del lado más débil, los demás de los siguientes lados que
     * queden separados al menos 60°.
     */
    public static List<BlockPos> stages(ServerLevel level, BlockPos center, Breach.Walls w, double preferred, int lanes,
                                        List<? extends LivingEntity> defenders, RandomSource random) {
        List<Double> angles = weakestAngles(level, center, w, preferred, defenders, random);
        List<Double> picked = new ArrayList<>();
        for (double a : angles) {
            if (picked.size() >= lanes) break;
            boolean far = true;
            for (double b : picked) if (Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b))) < Math.toRadians(60)) far = false;
            if (far) picked.add(a);
        }
        while (picked.size() < lanes) picked.add(picked.get(0) + Math.toRadians(60) * picked.size());
        List<BlockPos> out = new ArrayList<>();
        for (double a : picked) out.add(SiegeLanes.stagePos(level, center, a, w, out.isEmpty() ? null : out.get(0)));
        return out;
    }
}
