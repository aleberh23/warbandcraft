package com.ale.warbandcraft.battle.tactics;

import com.ale.warbandcraft.battle.BattleGeneral;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Las tácticas del general de un bando (como el "Team AI" de Bannerlord): cada una tiene un peso según la situación
 * (quién es más fuerte, qué tropas tiene cada uno, si hay una loma, si defiende un lugar) y gana la más pesada. La que
 * está usando tiene un plus para que no cambie de idea a cada rato. Los números de toda la IA de batalla están acá.
 */
public final class Tactics {
    /** Dificultad de la IA: 0 fácil (piensa lento, solo carga y forma líneas), 1 normal, 2 difícil (piensa rápido, más moral). */
    public static int DIFFICULTY = 1;
    /** Cada cuánto vuelve a pensar la táctica (ticks), por dificultad. */
    public static int THINK_EASY = 20 * 8, THINK_NORMAL = 20 * 5, THINK_HARD = 20 * 3;
    /** Plus de la táctica actual (para no cambiar a cada rato) y azar en los pesos por dificultad. */
    public static double HYSTERESIS = 1.3, NOISE_EASY = 0.6, NOISE_NORMAL = 0.25;
    /** Separación de los soldados: línea, arqueros, caballería, muro de escudos, suelta. */
    public static double LINE_SPACING = 1.6, RANGED_SPACING = 2.0, CAVALRY_SPACING = 2.6, SHIELD_WALL_SPACING = 1.15, LOOSE_SPACING = 3.0;
    /** Velocidad de la formación al avanzar (bloques por segundo): a pie y a caballo. */
    public static double FOOT_SPEED = 2.0, MOUNTED_SPEED = 4.5;
    /** No avanza si más de esta parte está a más de {@link #STRAGGLER} bloques de su puesto (espera a los rezagados). */
    public static double COHESION = 0.4, STRAGGLER = 5;
    /** El que tiene un enemigo a esto sale de la línea a pelear; peleando, sigue mientras lo tenga a {@link #KEEP_FIGHTING}. */
    public static double MELEE_ENGAGE = 5, KEEP_FIGHTING = 10;
    /** Una línea quieta (defendiendo) sale a pelear con lo que llega a esto. */
    public static double HOLD_ENGAGE = 7;
    /**
     * Bajo fuego: si el bando pierde vida durante tanto sin estar peleando cuerpo a cuerpo (le tiran de lejos), defenderse
     * deja de servir: la línea defensiva y la loma pesan × {@link #UNDER_FIRE_DEFENSE} y el ataque suma {@link #UNDER_FIRE_ATTACK}.
     */
    public static int UNDER_FIRE_TICKS = 20 * 8;
    public static double UNDER_FIRE_DEFENSE = 0.3, UNDER_FIRE_ATTACK = 1.5, MELEE_CONTACT = 8;
    /** Avanzando: carga cuando el enemigo está a esto. */
    public static double CHARGE_DISTANCE = 12;
    /** Arqueros: hasta dónde tiran, a qué distancia les gusta estar, y cuándo retroceden. */
    public static double RANGED_REACH = 40, SKIRMISH_RANGE = 28, SKIRMISH_TOO_CLOSE = 15;
    /** Arqueros detrás de su infantería: tantos bloques. */
    public static double SCREEN_BEHIND = 5;
    /** Rodeo de la caballería: qué tan afuera del costado enemigo pasa, qué tan atrás entra, y cuándo se rinde y carga. */
    public static double FLANK_WIDE = 18, FLANK_BEHIND = 14, FLANK_ARRIVED = 8;
    public static int FLANK_TIMEOUT = 20 * 25;
    /** Caballería protegiendo el flanco: carga contra lo que se le acerque a esto. */
    public static double PROTECT_REACT = 20;
    /** La reserva espera tantos bloques detrás de su infantería y entra si el enemigo llega a esto. */
    public static double RESERVE_BEHIND = 12, RESERVE_REACT = 15;
    /** Retirada: tantos bloques para atrás por vez. */
    public static double RETREAT_STEP = 30;
    /** Línea defensiva sobre un lugar a defender: tantos bloques hacia el enemigo. */
    public static double DEFEND_AHEAD = 6;
    /** Muro de escudos: con al menos esta parte con escudo y el enemigo con al menos esta parte de arqueros. */
    public static double SHIELD_SHARE = 0.5, ENEMY_RANGED_FOR_WALL = 0.25;
    /**
     * Avanzando bajo flechas con escudos (mismas condiciones que el muro): escudo en alto, y la formación va a esta parte
     * de su velocidad (con el escudo levantado se camina más lento; así no se desarma). Lo bajan al llegar a pelear.
     */
    public static double SHIELD_ADVANCE_SPEED = 0.6;
    /** Loma: cuánto más alta que el ejército para que cuente, y hasta qué distancia se busca. */
    public static int HILL_MIN_RISE = 3, HILL_SEARCH = 40;
    /** Retirada: con menos de esta relación de fuerzas, y no antes de tanto de pelea. */
    public static double RETREAT_RATIO = 0.35;
    public static int RETREAT_AFTER = 20 * 30;

    /** Prueba: táctica forzada para todos los generales de la IA (null = eligen solos). Ver /warband prueba tactica. */
    @Nullable
    public static Tactic forced;

    private Tactics() {}

    public static int thinkTicks() {
        return DIFFICULTY <= 0 ? THINK_EASY : DIFFICULTY >= 2 ? THINK_HARD : THINK_NORMAL;
    }

    public static boolean easy() {
        return DIFFICULTY <= 0;
    }

    public static boolean hard() {
        return DIFFICULTY >= 2;
    }

    // ------------------------------------------------------------------ lo que ve el general

    /** Lo que el general sabe de la batalla: cuántos de cada tipo, dónde están, quién es más fuerte. */
    public static final class View {
        final Map<Formation.Kind, Integer> own = new EnumMap<>(Formation.Kind.class), enemy = new EnumMap<>(Formation.Kind.class);
        final Map<Formation.Kind, List<LivingEntity>> enemyByKind = new EnumMap<>(Formation.Kind.class);
        int ownTotal, enemyTotal;
        /** Le están tirando de lejos y no puede responder (ver {@link #UNDER_FIRE_TICKS}). */
        boolean underFire;
        double ownPower, enemyPower;
        Vec3 ownCenter = Vec3.ZERO, enemyCenter = Vec3.ZERO;

        public static View of(List<AbstractRecruitEntity> own, List<LivingEntity> enemies) {
            View v = new View();
            for (Formation.Kind k : Formation.Kind.values()) {
                v.own.put(k, 0);
                v.enemy.put(k, 0);
                v.enemyByKind.put(k, new ArrayList<>());
            }
            for (AbstractRecruitEntity s : own) v.own.merge(Formation.kindOf(s), 1, Integer::sum);
            for (LivingEntity e : enemies) {
                Formation.Kind k = e instanceof AbstractRecruitEntity r ? Formation.kindOf(r) : e instanceof Player p && p.getVehicle() != null
                        ? Formation.Kind.CAVALRY : Formation.Kind.INFANTRY;
                v.enemy.merge(k, 1, Integer::sum);
                v.enemyByKind.get(k).add(e);
            }
            v.ownTotal = own.size();
            v.enemyTotal = enemies.size();
            v.ownPower = BattleGeneral.power(own);
            v.enemyPower = BattleGeneral.power(enemies);
            if (!own.isEmpty()) v.ownCenter = BattleGeneral.centroid(own);
            if (!enemies.isEmpty()) v.enemyCenter = BattleGeneral.centroid(enemies);
            return v;
        }

        public View underFire(boolean on) {
            underFire = on;
            return this;
        }

        /** Para las pruebas: una situación inventada. */
        public static View of(int ownInf, int ownRanged, int ownCav, int enemyInf, int enemyRanged, int enemyCav, double ratio) {
            View v = new View();
            v.own.put(Formation.Kind.INFANTRY, ownInf);
            v.own.put(Formation.Kind.RANGED, ownRanged);
            v.own.put(Formation.Kind.CAVALRY, ownCav);
            v.own.put(Formation.Kind.HORSE_ARCHER, 0);
            v.enemy.put(Formation.Kind.INFANTRY, enemyInf);
            v.enemy.put(Formation.Kind.RANGED, enemyRanged);
            v.enemy.put(Formation.Kind.CAVALRY, enemyCav);
            v.enemy.put(Formation.Kind.HORSE_ARCHER, 0);
            v.ownTotal = ownInf + ownRanged + ownCav;
            v.enemyTotal = enemyInf + enemyRanged + enemyCav;
            v.ownPower = ratio;
            v.enemyPower = 1;
            return v;
        }

        public double ratio() {
            return ownPower / Math.max(0.1, enemyPower);
        }

        double ownShare(Formation.Kind k) {
            return ownTotal == 0 ? 0 : own.getOrDefault(k, 0) / (double) ownTotal;
        }

        double enemyShare(Formation.Kind k) {
            return enemyTotal == 0 ? 0 : enemy.getOrDefault(k, 0) / (double) enemyTotal;
        }

        double ownRanged() {
            return ownShare(Formation.Kind.RANGED) + ownShare(Formation.Kind.HORSE_ARCHER);
        }

        double enemyRanged() {
            return enemyShare(Formation.Kind.RANGED) + enemyShare(Formation.Kind.HORSE_ARCHER);
        }

        double ownMounted() {
            return ownShare(Formation.Kind.CAVALRY) + ownShare(Formation.Kind.HORSE_ARCHER);
        }

        List<LivingEntity> enemies(Formation.Kind k) {
            return enemyByKind.getOrDefault(k, List.of());
        }
    }

    // ------------------------------------------------------------------ las tácticas

    public enum Tactic {
        /** Todos contra el más cercano (ejércitos chicos, o el enemigo ya está roto). */
        CHARGE,
        /** Ataque total: la infantería avanza en línea, los arqueros detrás tirando, la caballería rodea. */
        FULL_ATTACK,
        /** Línea defensiva: la infantería se planta (muro de escudos contra arqueros), arqueros detrás, caballería en el flanco. */
        DEFENSIVE_LINE,
        /** Hostigar: los arqueros desgastan a distancia, la infantería los cubre y espera. */
        RANGED_HARASS,
        /** Carga de caballería: la caballería va primero (contra los arqueros), la infantería detrás. */
        CAVALRY_CHARGE,
        /** Defender la loma: línea defensiva arriba de la altura más cercana. */
        HOLD_HILL,
        /** Retirada en orden. */
        RETREAT
    }

    /** Qué tan bien le viene cada táctica (sin el plus de la actual ni el azar). */
    public static double weight(Tactic t, View v, boolean defending, boolean hill, boolean canRetreat, long fightingTicks) {
        double r = v.ratio();
        return switch (t) {
            case CHARGE -> v.ownTotal < 6 || v.enemyTotal < 3 ? 3 : 0.3;
            case FULL_ATTACK -> (r > 1 ? 1 + (r - 1) * 1.5 : 0.5 * r) * (defending ? 0.4 : 1) + (v.underFire ? UNDER_FIRE_ATTACK : 0);
            case DEFENSIVE_LINE -> ((r < 1 ? 1 + (1 - r) * 2 : 0.6) + (v.ownRanged() > v.enemyRanged() ? 0.4 : 0) + (defending ? 1.5 : 0))
                    * (v.underFire && v.ownRanged() <= v.enemyRanged() ? UNDER_FIRE_DEFENSE : 1);
            case RANGED_HARASS -> v.ownRanged() >= 0.35 && v.ownRanged() > v.enemyRanged() ? 1 + v.ownRanged() * 2 : 0;
            case CAVALRY_CHARGE -> v.ownMounted() >= 0.4 ? 1 + v.ownMounted() * 2 + (r > 1 ? 0.5 : 0) : 0;
            case HOLD_HILL -> hill && !defending && r < 1.3
                    ? (weight(Tactic.DEFENSIVE_LINE, v, false, false, canRetreat, fightingTicks) + 0.5) * (v.underFire ? UNDER_FIRE_DEFENSE : 1) : 0;
            case RETREAT -> canRetreat && r < RETREAT_RATIO && fightingTicks >= RETREAT_AFTER ? 3 + (RETREAT_RATIO - r) * 5 : 0;
        };
    }

    /** Elige la táctica: la de más peso, con el plus de la actual y algo de azar según la dificultad. */
    public static Tactic choose(View v, @Nullable Tactic current, boolean defending, boolean hill, boolean canRetreat, long fightingTicks,
                                RandomSource random) {
        double noise = easy() ? NOISE_EASY : hard() ? 0 : NOISE_NORMAL;
        Tactic best = Tactic.CHARGE;
        double bestW = -1;
        for (Tactic t : Tactic.values()) {
            if (easy() && (t == Tactic.RANGED_HARASS || t == Tactic.CAVALRY_CHARGE || t == Tactic.HOLD_HILL)) continue;
            double w = weight(t, v, defending, hill, canRetreat, fightingTicks);
            if (w <= 0) continue;
            if (t == current) w *= HYSTERESIS;
            if (noise > 0) w += (random.nextDouble() * 2 - 1) * noise;
            if (w > bestW) {
                bestW = w;
                best = t;
            }
        }
        return best;
    }
}
