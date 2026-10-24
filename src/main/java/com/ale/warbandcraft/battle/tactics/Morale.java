package com.ale.warbandcraft.battle.tactics;

import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.invasion.Assault;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Moral (como en Bannerlord): cada soldado en una batalla de campo tiene su moral. Baja si caen compañeros cerca, si cae
 * su general, si le pegan por el costado o por detrás, si está muy herido o si su bando va perdiendo; sube si va
 * ganando o tiene al general cerca. Con poca moral huye: los de la IA se van del campo, los tuyos corren un poco y
 * vuelven a juntarse. Además, pegar por el costado o por detrás hace más daño (vale en todas las peleas entre soldados).
 */
public final class Morale {
    /** Moral al empezar, más por nivel; los de la IA en difícil, un poco más. */
    public static float START = 60, PER_LEVEL = 4, HARD_BONUS = 10;
    /** Lo que baja: compañero caído cerca, el general, golpe de costado / por detrás, por segundo muy herido. */
    public static float DEATH_NEAR = 4, GENERAL_DEATH = 20, FLANK_HIT = 1.5F, REAR_HIT = 3, WOUNDED = 1;
    /** Por segundo: ganando, perdiendo por mucho, al lado del general. */
    public static float WINNING = 0.3F, LOSING = 0.5F, GENERAL_AURA = 0.5F;
    /** Huye por debajo de esto; los tuyos vuelven con esta moral. */
    public static float BREAK = 10, RALLY = 40;
    /** Distancias: compañeros caídos, aura del general. */
    public static double DEATH_RADIUS = 12, AURA_RADIUS = 16;
    /** Golpe de costado / por detrás: el ángulo (desde donde mira) y cuánto más daño. */
    public static double FLANK_ANGLE = 60, REAR_ANGLE = 120, FLANK_DAMAGE = 1.25, REAR_DAMAGE = 1.5;
    /** Huyendo: hasta qué distancia del enemigo más cercano y cuánto como mucho. */
    public static double FLEE_SAFE = 36;
    public static int FLEE_TICKS = 20 * 20;

    private static final Map<UUID, Float> VALUES = new HashMap<>();
    private static final Map<UUID, Long> FLEEING = new HashMap<>();

    private Morale() {}

    public static void clear() {
        VALUES.clear();
        FLEEING.clear();
    }

    public static float get(AbstractRecruitEntity s) {
        return VALUES.computeIfAbsent(s.getUUID(), k -> Math.min(100, START + PER_LEVEL * s.getXpLevel()
                + (Tactics.hard() && !s.getIsOwned() ? HARD_BONUS : 0)));
    }

    public static void add(AbstractRecruitEntity s, float delta) {
        VALUES.put(s.getUUID(), Mth.clamp(get(s) + delta, 0, 100));
    }

    public static boolean fleeing(AbstractRecruitEntity s) {
        return FLEEING.containsKey(s.getUUID());
    }

    // ------------------------------------------------------------------ golpes

    /** Golpe de un soldado o jugador a un soldado: por el costado o por detrás pega más y baja la moral. */
    public static float onHurt(LivingEntity victim, @Nullable Entity attacker, float amount) {
        if (!(victim instanceof AbstractRecruitEntity s) || attacker == null || attacker == victim) return amount;
        double angle = angle(victim, attacker.position());
        if (angle >= REAR_ANGLE) {
            add(s, -REAR_HIT);
            return (float) (amount * REAR_DAMAGE);
        }
        if (angle >= FLANK_ANGLE) {
            add(s, -FLANK_HIT);
            return (float) (amount * FLANK_DAMAGE);
        }
        return amount;
    }

    /** Ángulo (grados, 0-180) entre hacia dónde mira {@code victim} y de dónde viene el golpe. */
    public static double angle(LivingEntity victim, Vec3 from) {
        Entity body = victim.getVehicle() instanceof AbstractHorse h ? h : victim;
        float yaw = body instanceof LivingEntity l ? l.yBodyRot : body.getYRot();
        return angle(yaw, victim.position(), from);
    }

    /** Lo mismo con el giro del cuerpo de vanilla (para las pruebas). */
    public static double angle(float yawDeg, Vec3 at, Vec3 from) {
        double fx = -Math.sin(Math.toRadians(yawDeg)), fz = Math.cos(Math.toRadians(yawDeg));
        double dx = from.x - at.x, dz = from.z - at.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) return 0;
        double cos = (fx * dx + fz * dz) / len;
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1)));
    }

    /** Cayó un soldado: a sus compañeros cerca les baja la moral (mucho si era el general o el capitán). */
    public static void onDeath(ServerLevel level, AbstractRecruitEntity dead) {
        VALUES.remove(dead.getUUID());
        FLEEING.remove(dead.getUUID());
        boolean general = dead.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN) || dead.getPersistentData().getBoolean(Assault.TAG_GENERAL);
        double r = general ? 48 : DEATH_RADIUS;
        for (AbstractRecruitEntity a : level.getEntitiesOfClass(AbstractRecruitEntity.class, dead.getBoundingBox().inflate(r),
                e -> e != dead && e.isAlive() && sameSide(e, dead))) {
            if (!VALUES.containsKey(a.getUUID()) && !general) continue;   // solo los que están en una batalla de campo
            add(a, -(general ? GENERAL_DEATH : DEATH_NEAR));
        }
    }

    /** ¿Del mismo bando? Tuyos con tuyos (mismo dueño), de la IA con su facción. */
    public static boolean sameSide(AbstractRecruitEntity a, AbstractRecruitEntity b) {
        if (a.getIsOwned() || b.getIsOwned()) return a.getIsOwned() && b.getIsOwned() && com.ale.warbandcraft.faction.Allies.same(a.getOwnerUUID(), b.getOwnerUUID());
        return a.getPersistentData().getString(VillageManager.TAG_FACTION).equals(b.getPersistentData().getString(VillageManager.TAG_FACTION));
    }

    // ------------------------------------------------------------------ cada segundo, por bando

    /**
     * La moral de un bando en una batalla de campo: sube o baja según cómo va, y el que se quiebra huye. Devuelve cuántos
     * de la IA se fueron del campo (para las cuentas del que maneja la batalla).
     */
    static int tickTeam(ServerLevel level, List<AbstractRecruitEntity> own, List<LivingEntity> enemies, double ratio) {
        AbstractRecruitEntity general = null;
        for (AbstractRecruitEntity s : own) {
            if (s.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN) || s.getPersistentData().getBoolean(Assault.TAG_GENERAL)) general = s;
        }
        long now = level.getGameTime();
        int gone = 0;
        for (AbstractRecruitEntity s : own) {
            if (Surrender.is(s)) continue;
            Long since = FLEEING.get(s.getUUID());
            if (since != null) {
                if (flee(level, s, enemies, now - since)) gone++;
                continue;
            }
            float d = 0;
            if (ratio > 1.3) d += WINNING;
            else if (ratio < 0.6) d -= LOSING;
            if (s.getHealth() < s.getMaxHealth() * 0.3F) d -= WOUNDED;
            if (general != null && general != s && s.distanceToSqr(general) <= AURA_RADIUS * AURA_RADIUS) d += GENERAL_AURA;
            add(s, d);
            if (get(s) <= BREAK && general != s) {
                FLEEING.put(s.getUUID(), now);
                s.setTarget(null);
                s.setShouldBlock(false);
            }
        }
        return gone;
    }

    /** Huye: corre lejos del enemigo. Ya a salvo (o pasado el tiempo): el de la IA se va, el tuyo se junta de nuevo. */
    private static boolean flee(ServerLevel level, AbstractRecruitEntity s, List<LivingEntity> enemies, long fleeingFor) {
        LivingEntity near = Formation.nearest(s.position(), enemies, Double.MAX_VALUE);
        double dist = near == null ? Double.MAX_VALUE : s.distanceTo(near);
        if (dist >= FLEE_SAFE || fleeingFor >= FLEE_TICKS) {
            FLEEING.remove(s.getUUID());
            if (!s.getIsOwned() && !Armies.inArmy(s)) {
                if (s.getVehicle() instanceof AbstractHorse h) h.discard();
                s.discard();
                return true;
            }
            VALUES.put(s.getUUID(), RALLY);
            return false;
        }
        Vec3 away = near == null ? s.position() : s.position().add(Formation.flat(s.position().subtract(near.position())).scale(12));
        s.setTarget(null);
        s.setFollowState(3);
        s.setHoldPos(Armies.ground(level, away, s.getY()));
        return false;
    }

    /** ¿Hay soldados de esta batalla huyendo? (los que maneja el general no los toca). */
    static boolean isFleeing(UUID id) {
        return FLEEING.containsKey(id);
    }

    /** Para las pruebas: soldados de un bando cerca de {@code at} con la moral anotada. */
    public static int tracked(ServerLevel level, Vec3 at, double r) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(at, at).inflate(r), e -> VALUES.containsKey(e.getUUID())).size();
    }
}
