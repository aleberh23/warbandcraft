package com.ale.warbandcraft.battle.tactics;

import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Una formación (como en Bannerlord): los soldados de un tipo de un bando, con su lugar (el frente, al medio), hacia dónde
 * mira, cómo se ordena (línea, muro de escudos, suelta, columna) y qué está haciendo (su comportamiento, que le da la
 * táctica del general). Cada soldado va a su puesto y solo se larga a pelear cuando tiene un enemigo encima: así la
 * línea se mantiene hasta el choque.
 */
public final class Formation {
    public enum Kind { INFANTRY, RANGED, CAVALRY, HORSE_ARCHER }

    public enum Arrangement { LINE, SHIELD_WALL, LOOSE, COLUMN }

    public enum Behavior {
        /** Avanza en formación hacia el enemigo y carga al llegar. */
        ADVANCE,
        /** Cada uno contra el enemigo más cercano (de su objetivo, si tiene). */
        CHARGE,
        /** Quieta en su lugar, mirando al enemigo; pelea con el que llega. */
        HOLD,
        /** Arqueros detrás de su infantería, tirando. */
        SCREEN,
        /** Arqueros a distancia: tiran y retroceden si se les acercan. */
        SKIRMISH,
        /** Caballería: da la vuelta por un costado y entra por detrás. */
        FLANK,
        /** Caballería al costado de su infantería; carga contra lo que se acerque. */
        PROTECT_FLANK,
        /** Caballería contra los arqueros enemigos. */
        HUNT_RANGED,
        /** Espera detrás; entra cuando hace falta. */
        RESERVE,
        /** Se va en orden, sin pelear salvo que la alcancen. */
        RETREAT
    }

    public final Kind kind;
    final List<AbstractRecruitEntity> members = new ArrayList<>();
    /** El frente de la formación, al medio. */
    Vec3 position;
    Vec3 facing = new Vec3(0, 0, 1);
    Arrangement arrangement = Arrangement.LINE;
    Behavior behavior = Behavior.HOLD;
    long behaviorSince;
    /** Para HOLD: dónde se planta (lo pone la táctica). Null = donde está. */
    @Nullable Vec3 holdPoint;
    /** Flanqueo y protección del flanco: de qué lado (+1 / -1) y por qué etapa va el rodeo. */
    int side = 1, flankStage;
    /** Contra qué tipo de formación enemiga va (null = la más cercana). */
    @Nullable Kind targetKind;
    /** Escudo en alto (muro de escudos, o avanzando bajo flechas): lo levantan los que tienen escudo y no están peleando. */
    boolean shieldsUp;

    Formation(Kind kind, Vec3 position) {
        this.kind = kind;
        this.position = position;
    }

    public Behavior behavior() {
        return behavior;
    }

    public Arrangement arrangement() {
        return arrangement;
    }

    public List<AbstractRecruitEntity> members() {
        return members;
    }

    void setBehavior(Behavior b, long now) {
        if (behavior == b) return;
        behavior = b;
        behaviorSince = now;
        flankStage = 0;
    }

    /** De qué tipo es un soldado (caballo + arco = arquero a caballo). */
    public static Kind kindOf(AbstractRecruitEntity s) {
        boolean mounted = s.getVehicle() instanceof AbstractHorse;
        boolean ranged = BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED
                || mounted && (s.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem);
        if (mounted) return ranged ? Kind.HORSE_ARCHER : Kind.CAVALRY;
        return ranged ? Kind.RANGED : Kind.INFANTRY;
    }

    public static boolean isMounted(Kind k) {
        return k == Kind.CAVALRY || k == Kind.HORSE_ARCHER;
    }

    public static boolean isRanged(Kind k) {
        return k == Kind.RANGED || k == Kind.HORSE_ARCHER;
    }

    public Vec3 center() {
        return members.isEmpty() ? position : BattleGeneral.centroid(members);
    }

    /** Qué parte tiene escudo en la otra mano. */
    double shieldShare() {
        if (members.isEmpty()) return 0;
        int n = 0;
        for (AbstractRecruitEntity s : members) if (s.getOffhandItem().getItem() instanceof ShieldItem) n++;
        return n / (double) members.size();
    }

    // ------------------------------------------------------------------ geometría

    static double spacing(Kind kind, Arrangement a) {
        return switch (a) {
            case SHIELD_WALL -> Tactics.SHIELD_WALL_SPACING;
            case LOOSE -> Tactics.LOOSE_SPACING;
            case COLUMN -> Tactics.CAVALRY_SPACING;
            case LINE -> isMounted(kind) ? Tactics.CAVALRY_SPACING : isRanged(kind) ? Tactics.RANGED_SPACING : Tactics.LINE_SPACING;
        };
    }

    static int ranks(Kind kind, Arrangement a, int n) {
        if (n <= 0) return 1;
        return switch (a) {
            case SHIELD_WALL -> Math.max(3, (int) Math.ceil(n / 10.0));
            case COLUMN -> Math.max(1, (int) Math.ceil(n / 3.0));
            default -> isMounted(kind) ? (n <= 12 ? 1 : 2) : isRanged(kind) ? (n <= 15 ? 1 : 2) : n <= 8 ? 1 : n <= 24 ? 2 : (int) Math.ceil(n / 16.0);
        };
    }

    static double rankGap(Kind kind, Arrangement a) {
        return a == Arrangement.SHIELD_WALL ? 1.2 : isMounted(kind) || a == Arrangement.COLUMN ? 3.0 : 1.8;
    }

    /** Profundidad (de la primera fila a la última) para {@code n} soldados. */
    double depth(int n) {
        return (ranks(kind, arrangement, n) - 1) * rankGap(kind, arrangement);
    }

    double width(int n) {
        int ranks = ranks(kind, arrangement, n);
        int perRank = (int) Math.ceil(n / (double) ranks);
        return Math.max(0, perRank - 1) * spacing(kind, arrangement);
    }

    /**
     * Los puestos: fila por fila desde el frente ({@code front} es el medio de la primera fila), cada fila de izquierda a
     * derecha. Sin altura.
     */
    public static List<Vec3> slots(Vec3 front, Vec3 facing, int n, Kind kind, Arrangement a) {
        List<Vec3> out = new ArrayList<>(n);
        if (n <= 0) return out;
        Vec3 f = flat(facing);
        Vec3 side = new Vec3(-f.z, 0, f.x);
        int ranks = ranks(kind, a, n);
        int perRank = (int) Math.ceil(n / (double) ranks);
        double spacing = spacing(kind, a), gap = rankGap(kind, a);
        for (int i = 0; i < n; i++) {
            int r = i / perRank, c = i % perRank;
            int inRow = Math.min(perRank, n - r * perRank);
            double offset = (c - (inRow - 1) / 2.0) * spacing;
            out.add(front.add(side.scale(offset)).subtract(f.scale(r * gap)));
        }
        return out;
    }

    /**
     * Reparte los puestos sin que se crucen: los de más adelante a la primera fila y, en cada fila, de izquierda a derecha
     * según dónde está cada uno.
     */
    Map<AbstractRecruitEntity, Vec3> assign(List<AbstractRecruitEntity> soldiers, Vec3 front, Vec3 facing) {
        Map<AbstractRecruitEntity, Vec3> out = new LinkedHashMap<>();
        int n = soldiers.size();
        if (n == 0) return out;
        Vec3 f = flat(facing);
        Vec3 sideV = new Vec3(-f.z, 0, f.x);
        List<Vec3> slots = slots(front, f, n, kind, arrangement);
        int ranks = ranks(kind, arrangement, n);
        int perRank = (int) Math.ceil(n / (double) ranks);
        List<AbstractRecruitEntity> byDepth = new ArrayList<>(soldiers);
        // H26: las picas y armas de asta van a la primera fila (frenan a la caballería)
        byDepth.sort(Comparator.<AbstractRecruitEntity>comparingInt(s -> com.ale.warbandcraft.kit.WeaponRules.isPolearm(s) ? 0 : 1)
                .thenComparingDouble(s -> -s.position().subtract(front).dot(f)));
        int i = 0;
        for (int r = 0; r < ranks && i < n; r++) {
            List<AbstractRecruitEntity> row = new ArrayList<>(byDepth.subList(i, Math.min(n, i + perRank)));
            row.sort(Comparator.comparingDouble(s -> s.position().subtract(front).dot(sideV)));
            for (AbstractRecruitEntity s : row) out.put(s, slots.get(i++));
        }
        return out;
    }

    // ------------------------------------------------------------------ órdenes a los soldados

    /** Cada uno a su puesto; el que tiene un enemigo encima (o ya está peleando cerca) pelea. */
    void form(ServerLevel level, List<LivingEntity> enemies, double engage, boolean shoot) {
        Map<AbstractRecruitEntity, Vec3> slots = assign(members, position, facing);
        boolean block = arrangement == Arrangement.SHIELD_WALL || shieldsUp;
        for (Map.Entry<AbstractRecruitEntity, Vec3> e : slots.entrySet()) {
            AbstractRecruitEntity s = e.getKey();
            if (!isRanged(kind) && (fighting(s) || nearestDistance(s, enemies) <= engage)) {
                fight(s, enemies, null);
                continue;
            }
            toSlot(s, Armies.ground(level, e.getValue(), position.y));
            s.setShouldBlock(block && s.getOffhandItem().getItem() instanceof ShieldItem);
            if (isRanged(kindOf(s)) && (shoot || isRanged(kind))) aim(s, enemies);   // los de cuerpo a cuerpo no: saldrían de la línea
        }
    }

    /** ¡A la carga!: contra el más cercano, prefiriendo los de {@code preferred} si están más o menos igual de cerca. */
    void charge(List<LivingEntity> enemies, @Nullable List<LivingEntity> preferred) {
        for (AbstractRecruitEntity s : members) fight(s, enemies, preferred);
    }

    static void fight(AbstractRecruitEntity s, List<LivingEntity> enemies, @Nullable List<LivingEntity> preferred) {
        s.setShouldBlock(false);
        LivingEntity current = s.getTarget();
        if (current == null || !current.isAlive() || Surrender.is(current) || s.distanceTo(current) > Tactics.KEEP_FIGHTING
                || !enemies.contains(current)) {
            LivingEntity t = nearest(s.position(), enemies, Double.MAX_VALUE);
            if (preferred != null && !preferred.isEmpty()) {
                LivingEntity p = nearest(s.position(), preferred, Double.MAX_VALUE);
                if (p != null && (t == null || s.distanceTo(p) <= s.distanceTo(t) * 1.6 + 6)) t = p;
            }
            if (t != null && s.canAttack(t)) s.setTarget(t);
        }
        if (s.getFollowState() != 0) s.setFollowState(0);
    }

    /** A su puesto (estado 3 de Recruits); solo se toca si cambió de verdad. Los de a pie y a caballo sueltan al enemigo. */
    static void toSlot(AbstractRecruitEntity s, Vec3 pos) {
        Vec3 hold = s.getHoldPos();
        if (hold == null || hold.distanceToSqr(pos) > 1.0 || s.getFollowState() != 3) {
            if (!isRanged(kindOf(s))) s.setTarget(null);
            s.setFollowState(3);
            s.setHoldPos(pos);
        }
    }

    /** Arquero: si no tiene a quién tirarle, al enemigo más cercano a tiro. */
    static void aim(AbstractRecruitEntity s, List<LivingEntity> enemies) {
        LivingEntity current = s.getTarget();
        if (current != null && current.isAlive() && !Surrender.is(current) && s.distanceTo(current) <= Tactics.RANGED_REACH) return;
        LivingEntity t = nearest(s.position(), enemies, Tactics.RANGED_REACH);
        if (t != null && s.canAttack(t)) s.setTarget(t);
    }

    static boolean fighting(AbstractRecruitEntity s) {
        LivingEntity t = s.getTarget();
        return t != null && t.isAlive() && !Surrender.is(t) && s.distanceTo(t) <= Tactics.KEEP_FIGHTING && s.getFollowState() == 0;
    }

    /** Suelta a todos (terminó la batalla o se olvidó la formación). */
    void release() {
        for (AbstractRecruitEntity s : members) s.setShouldBlock(false);
    }

    // ------------------------------------------------------------------ ayudas

    static double nearestDistance(LivingEntity s, List<? extends LivingEntity> list) {
        double best = Double.MAX_VALUE;
        for (LivingEntity e : list) best = Math.min(best, e.distanceToSqr(s));
        return Math.sqrt(best);
    }

    @Nullable
    static LivingEntity nearest(Vec3 from, List<? extends LivingEntity> list, double reach) {
        LivingEntity best = null;
        double bestD = reach == Double.MAX_VALUE ? Double.MAX_VALUE : reach * reach;
        for (LivingEntity e : list) {
            double d = e.position().distanceToSqr(from);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        return f.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : f.normalize();
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
