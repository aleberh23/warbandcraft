package com.ale.warbandcraft.battle.tactics;

import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.battle.tactics.Formation.Behavior;
import com.ale.warbandcraft.battle.tactics.Formation.Kind;
import com.ale.warbandcraft.battle.tactics.Tactics.Tactic;
import com.ale.warbandcraft.invasion.Assault;
import com.ale.warbandcraft.village.CastleManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * El general de un bando en una batalla de campo (H24, como el "Team AI" de Bannerlord). Cada segundo:
 * <ol>
 *   <li>La moral de cada soldado ({@link Morale}); el que se quiebra huye.</li>
 *   <li>Arma las formaciones por tipo (infantería, arqueros, caballería, arqueros a caballo).</li>
 *   <li>Cada tanto (según la dificultad) piensa la táctica ({@link Tactics}) y le da un comportamiento a cada formación.</li>
 *   <li>Cada formación hace lo suyo ({@link Formation}).</li>
 * </ol>
 * Lo usan la Hueste, las batallas de campo entre facciones, las emboscadas y tus ejércitos cuando delegás el mando. No
 * avisa nada: lo que hace el enemigo se descubre mirando. Lo que piensa no se guarda (al volver, piensa de nuevo).
 */
public final class TeamAI {
    /**
     * Lo que el que maneja la batalla le dice al general: hacia dónde queda su casa (para retirarse), un lugar que tiene
     * que defender (aldea) o null, y si se puede retirar.
     */
    public record Context(Vec3 home, @Nullable Vec3 defendPoint, boolean canRetreat) {}

    private static final Map<String, TeamAI> ALL = new HashMap<>();

    final String key;
    final long startTick;
    @Nullable Tactic tactic;
    long lastThink = Long.MIN_VALUE;
    final Map<Kind, Formation> formations = new EnumMap<>(Kind.class);
    /** La loma buscada al empezar (null = no hay); se busca una sola vez. */
    @Nullable Vec3 hill;
    boolean hillSearched;
    @Nullable AbstractRecruitEntity general;
    /** Bajo fuego: vida total la última vez, y desde hace cuánto pierde vida sin pelear cuerpo a cuerpo. */
    double lastHealth = -1;
    int underFireTicks;

    private TeamAI(String key, long now) {
        this.key = key;
        this.startTick = now;
    }

    @Nullable
    public static TeamAI get(String key) {
        return ALL.get(key);
    }

    /** Terminó la batalla de este bando: suelta a todos (bajan los escudos) y olvida. */
    public static void forget(String key) {
        TeamAI ai = ALL.remove(key);
        if (ai != null) for (Formation f : ai.formations.values()) f.release();
    }

    public static void clear() {
        ALL.clear();
        Morale.clear();
    }

    @Nullable
    public Tactic tactic() {
        return tactic;
    }

    @Nullable
    public Formation formation(Kind k) {
        return formations.get(k);
    }

    /**
     * Una vuelta del general de {@code key}: {@code own} son sus soldados en pie, {@code enemies} los del otro bando
     * (soldados y jugadores). Devuelve cuántos de la IA huyeron del campo y se fueron.
     */
    public static int tick(ServerLevel level, String key, List<AbstractRecruitEntity> own, List<LivingEntity> enemies, Context ctx) {
        TeamAI ai = ALL.computeIfAbsent(key, k -> new TeamAI(k, level.getGameTime()));
        return ai.update(level, own, enemies, ctx, key.startsWith("army:") ? null : Tactics.forced);
    }

    /** Igual, pero con la táctica forzada (pruebas y el comando de prueba). */
    public static int tick(ServerLevel level, String key, List<AbstractRecruitEntity> own, List<LivingEntity> enemies, Context ctx, Tactic forced) {
        TeamAI ai = ALL.computeIfAbsent(key, k -> new TeamAI(k, level.getGameTime()));
        return ai.update(level, own, enemies, ctx, forced);
    }

    private int update(ServerLevel level, List<AbstractRecruitEntity> all, List<LivingEntity> enemies, Context ctx, @Nullable Tactic forced) {
        long now = level.getGameTime();
        List<AbstractRecruitEntity> own = new ArrayList<>();
        for (AbstractRecruitEntity s : all) if (s.isAlive() && !Surrender.is(s)) own.add(s);
        if (own.isEmpty()) return 0;
        Tactics.View view = Tactics.View.of(own, enemies);
        int gone = Morale.tickTeam(level, own, enemies, view.ratio());
        if (enemies.isEmpty()) return gone;

        // Formaciones (los que huyen y el general, aparte)
        general = null;
        for (Formation f : formations.values()) f.members.clear();
        for (AbstractRecruitEntity s : own) {
            if (!s.isAlive() || Morale.isFleeing(s.getUUID())) continue;
            if (s.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN) || s.getPersistentData().getBoolean(Assault.TAG_GENERAL)) {
                general = s;
                continue;
            }
            Kind k = Formation.kindOf(s);
            formations.computeIfAbsent(k, x -> new Formation(x, s.position())).members.add(s);
        }
        formations.values().removeIf(f -> {
            if (!f.members.isEmpty()) return false;
            f.release();
            return true;
        });
        if (formations.isEmpty()) return gone;
        for (Formation f : formations.values()) {
            if (f.behaviorSince == 0) {   // recién armada: arranca donde están sus soldados, mirando al enemigo
                f.position = f.center();
                f.facing = Formation.flat(view.enemyCenter.subtract(f.position));
            }
        }

        if (!hillSearched) {
            hillSearched = true;
            hill = findHill(level, view.ownCenter, view.enemyCenter);
        }

        // ¿Le tiran de lejos sin que pueda responder? (Bannerlord: una línea bajo flechas no se queda quieta)
        boolean wasUnderFire = underFireTicks >= Tactics.UNDER_FIRE_TICKS;
        double health = 0;
        boolean melee = false;
        for (AbstractRecruitEntity s : own) {
            health += s.getHealth();
            if (!melee && !Formation.isRanged(Formation.kindOf(s)) && Formation.nearestDistance(s, enemies) <= Tactics.MELEE_CONTACT) melee = true;
        }
        if (melee) underFireTicks = 0;
        else if (lastHealth >= 0 && health < lastHealth - 0.5) underFireTicks += 20;
        lastHealth = health;
        view.underFire = underFireTicks >= Tactics.UNDER_FIRE_TICKS;
        boolean urgent = view.underFire && !wasUnderFire;

        // Pensar la táctica
        if (forced != null || tactic == null || urgent || now - lastThink >= Tactics.thinkTicks()) {
            Tactic next = forced != null ? forced : Tactics.choose(view, tactic, ctx.defendPoint() != null, hill != null, ctx.canRetreat(),
                    now - startTick, level.getRandom());
            if (next != tactic) apply(next, view, ctx, now);
            else assignNew(view, ctx, now);
            tactic = next;
            lastThink = now;
        } else {
            assignNew(view, ctx, now);
        }

        for (Formation f : formations.values()) run(level, f, view, enemies, ctx, now);
        if (general != null) commandGeneral(level, enemies);
        return gone;
    }

    // ------------------------------------------------------------------ la táctica → comportamientos

    /** Cambió la táctica: cada formación recibe lo suyo. */
    private void apply(Tactic t, Tactics.View v, Context ctx, long now) {
        tactic = t;
        Formation inf = formations.get(Kind.INFANTRY);
        Vec3 toEnemy = Formation.flat(v.enemyCenter.subtract(v.ownCenter));
        for (Formation f : formations.values()) {
            f.holdPoint = null;
            f.targetKind = null;
            f.setBehavior(behaviorFor(t, f.kind, inf != null), now);
            f.side = sideFor(f, v);
        }
        if (inf != null && (t == Tactic.DEFENSIVE_LINE || t == Tactic.HOLD_HILL || t == Tactic.RANGED_HARASS)) {
            Vec3 anchor = t == Tactic.HOLD_HILL && hill != null ? hill
                    : ctx.defendPoint() != null ? ctx.defendPoint().add(toEnemy.scale(Tactics.DEFEND_AHEAD)) : inf.center();
            inf.holdPoint = anchor;
        }
        Formation ranged = formations.get(Kind.RANGED);
        if (ranged != null && inf == null && (t == Tactic.DEFENSIVE_LINE || t == Tactic.HOLD_HILL)) {
            ranged.holdPoint = t == Tactic.HOLD_HILL && hill != null ? hill : ctx.defendPoint() != null ? ctx.defendPoint() : ranged.center();
            ranged.setBehavior(Behavior.HOLD, now);
        }
        Formation cav = formations.get(Kind.CAVALRY);
        if (cav != null && t == Tactic.CAVALRY_CHARGE) cav.targetKind = v.enemyRanged() > 0 ? Kind.RANGED : null;
    }

    /** Las formaciones que aparecieron después (oleadas, alguien se bajó del caballo) toman lo de la táctica actual. */
    private void assignNew(Tactics.View v, Context ctx, long now) {
        if (tactic == null) return;
        Formation inf = formations.get(Kind.INFANTRY);
        for (Formation f : formations.values()) {
            if (f.behaviorSince == 0) {
                f.setBehavior(behaviorFor(tactic, f.kind, inf != null), now);
                f.behaviorSince = Math.max(1, now);
                f.side = sideFor(f, v);
            }
        }
    }

    private static Behavior behaviorFor(Tactic t, Kind k, boolean hasInfantry) {
        boolean flank = !Tactics.easy();
        return switch (t) {
            case CHARGE -> Behavior.CHARGE;
            case RETREAT -> Behavior.RETREAT;
            case FULL_ATTACK -> switch (k) {
                case INFANTRY -> Behavior.ADVANCE;
                case RANGED -> hasInfantry ? Behavior.SCREEN : Behavior.SKIRMISH;
                case CAVALRY -> flank ? Behavior.FLANK : Behavior.CHARGE;
                case HORSE_ARCHER -> Behavior.SKIRMISH;
            };
            case DEFENSIVE_LINE, HOLD_HILL -> switch (k) {
                case INFANTRY -> Behavior.HOLD;
                case RANGED -> hasInfantry ? Behavior.SCREEN : Behavior.HOLD;
                case CAVALRY -> flank ? Behavior.PROTECT_FLANK : Behavior.RESERVE;
                case HORSE_ARCHER -> Behavior.SKIRMISH;
            };
            case RANGED_HARASS -> switch (k) {
                case INFANTRY -> Behavior.HOLD;
                case RANGED, HORSE_ARCHER -> Tactics.easy() ? Behavior.SCREEN : Behavior.SKIRMISH;
                case CAVALRY -> Behavior.PROTECT_FLANK;
            };
            case CAVALRY_CHARGE -> switch (k) {
                case INFANTRY -> Behavior.ADVANCE;
                case RANGED -> hasInfantry ? Behavior.SCREEN : Behavior.SKIRMISH;
                case CAVALRY -> Behavior.HUNT_RANGED;
                case HORSE_ARCHER -> Behavior.SKIRMISH;
            };
        };
    }

    /** El costado: para rodear, el que tenga menos caballería enemiga cerca (o el de su lado); para proteger, el más amenazado. */
    private int sideFor(Formation f, Tactics.View v) {
        Vec3 toEnemy = Formation.flat(v.enemyCenter.subtract(v.ownCenter));
        Vec3 perp = new Vec3(-toEnemy.z, 0, toEnemy.x);
        List<LivingEntity> cav = v.enemies(Kind.CAVALRY);
        double cavSide = 0;
        for (LivingEntity e : cav) cavSide += Math.signum(e.position().subtract(v.enemyCenter).dot(perp));
        if (f.behavior == Behavior.PROTECT_FLANK) return cavSide >= 0 ? 1 : -1;
        if (cavSide != 0) return cavSide > 0 ? -1 : 1;
        return f.center().subtract(v.ownCenter).dot(perp) >= 0 ? 1 : -1;
    }

    // ------------------------------------------------------------------ cada formación

    private void run(ServerLevel level, Formation f, Tactics.View v, List<LivingEntity> enemies, Context ctx, long now) {
        Formation inf = formations.get(Kind.INFANTRY);
        Vec3 center = f.center();
        Vec3 enemyCenter = v.enemyCenter;
        LivingEntity nearestEnemy = Formation.nearest(center, enemies, Double.MAX_VALUE);
        double enemyDist = nearestEnemy == null ? Double.MAX_VALUE : Formation.horizontal(center, nearestEnemy.position());
        f.shieldsUp = false;
        switch (f.behavior) {
            case CHARGE -> f.charge(enemies, f.targetKind == null ? null : v.enemies(f.targetKind));
            case ADVANCE -> {
                // Bajo flechas: con escudos, avanzan con el escudo en alto (más lento); sin escudos, abiertos
                boolean shielded = !Tactics.easy() && !Formation.isMounted(f.kind) && v.enemyRanged() >= Tactics.ENEMY_RANGED_FOR_WALL
                        && f.shieldShare() >= Tactics.SHIELD_SHARE;
                f.shieldsUp = shielded;
                f.arrangement = !shielded && v.enemyRanged() >= 0.3 && !Formation.isMounted(f.kind)
                        ? Formation.Arrangement.LOOSE : Formation.Arrangement.LINE;
                if (enemyDist <= Tactics.CHARGE_DISTANCE) {
                    f.setBehavior(Behavior.CHARGE, now);
                    f.charge(enemies, null);
                    return;
                }
                Vec3 dir = Formation.flat(enemyCenter.subtract(f.position));
                double speed = (Formation.isMounted(f.kind) ? Tactics.MOUNTED_SPEED : Tactics.FOOT_SPEED) * (shielded ? Tactics.SHIELD_ADVANCE_SPEED : 1);
                if (cohesive(f)) f.position = f.position.add(dir.scale(Math.min(speed, Math.max(0, Formation.horizontal(f.position, enemyCenter) - Tactics.CHARGE_DISTANCE))));
                f.facing = dir;
                f.form(level, enemies, Tactics.MELEE_ENGAGE, false);
            }
            case HOLD -> {
                Vec3 at = f.holdPoint != null ? f.holdPoint : f.position;
                f.position = at;
                f.facing = Formation.flat(enemyCenter.subtract(at));
                boolean wall = !Tactics.easy() && !Formation.isMounted(f.kind) && v.enemyRanged() >= Tactics.ENEMY_RANGED_FOR_WALL
                        && f.shieldShare() >= Tactics.SHIELD_SHARE;
                f.arrangement = wall ? Formation.Arrangement.SHIELD_WALL : Formation.Arrangement.LINE;
                f.form(level, enemies, Tactics.HOLD_ENGAGE, true);
            }
            case SCREEN -> {
                if (inf == null || inf.members.isEmpty() || inf.behavior == Behavior.CHARGE) {
                    skirmish(level, f, enemies, v);
                    return;
                }
                f.facing = inf.facing;
                f.position = inf.position.subtract(inf.facing.scale(inf.depth(inf.members.size()) + Tactics.SCREEN_BEHIND));
                f.arrangement = Formation.Arrangement.LINE;
                f.form(level, enemies, Tactics.MELEE_ENGAGE, true);
            }
            case SKIRMISH -> skirmish(level, f, enemies, v);
            case FLANK -> flank(level, f, enemies, v, now);
            case HUNT_RANGED -> {
                List<LivingEntity> ranged = new ArrayList<>(v.enemies(Kind.RANGED));
                ranged.addAll(v.enemies(Kind.HORSE_ARCHER));
                if (ranged.isEmpty()) {
                    f.setBehavior(Tactics.easy() ? Behavior.CHARGE : Behavior.FLANK, now);
                    return;
                }
                f.charge(enemies, ranged);
            }
            case PROTECT_FLANK -> {
                if (inf == null || inf.members.isEmpty()) {
                    f.setBehavior(Behavior.CHARGE, now);
                    return;
                }
                Vec3 perp = new Vec3(-inf.facing.z, 0, inf.facing.x);
                f.facing = inf.facing;
                f.position = inf.position.add(perp.scale(f.side * (inf.width(inf.members.size()) / 2 + 6)));
                f.arrangement = Formation.Arrangement.LINE;
                if (Formation.nearest(f.center(), enemies, Tactics.PROTECT_REACT) != null) {
                    f.charge(enemies, v.enemies(Kind.CAVALRY));   // contra lo que viene (primero la caballería)
                    return;
                }
                f.form(level, enemies, Tactics.MELEE_ENGAGE, false);
            }
            case RESERVE -> {
                Vec3 base = inf != null && !inf.members.isEmpty() ? inf.position.subtract(inf.facing.scale(Tactics.RESERVE_BEHIND)) : f.position;
                f.position = base;
                f.facing = Formation.flat(enemyCenter.subtract(base));
                boolean needed = Formation.nearest(f.center(), enemies, Tactics.RESERVE_REACT) != null
                        || inf != null && engagedShare(inf) > 0.5 && v.ratio() < 1;
                if (needed) {
                    f.setBehavior(Behavior.CHARGE, now);
                    return;
                }
                f.form(level, enemies, Tactics.MELEE_ENGAGE, false);
            }
            case RETREAT -> {
                Vec3 away = Formation.flat(ctx.home().subtract(center));
                if (Formation.horizontal(ctx.home(), center) < 4) away = Formation.flat(center.subtract(enemyCenter));
                f.facing = away.scale(-1);
                f.position = center.add(away.scale(Tactics.RETREAT_STEP));
                f.arrangement = Formation.Arrangement.LOOSE;
                f.form(level, enemies, 3, false);
            }
        }
    }

    /** ¿La formación está junta? (no avanza si muchos se quedaron atrás). */
    private static boolean cohesive(Formation f) {
        if (f.members.isEmpty()) return false;
        int far = 0;
        for (AbstractRecruitEntity s : f.members) {
            Vec3 h = s.getHoldPos();
            if (h != null && s.getFollowState() == 3 && Formation.horizontal(h, s.position()) > Tactics.STRAGGLER) far++;
        }
        return far <= f.members.size() * Tactics.COHESION;
    }

    private static double engagedShare(Formation f) {
        if (f.members.isEmpty()) return 0;
        int n = 0;
        for (AbstractRecruitEntity s : f.members) if (Formation.fighting(s)) n++;
        return n / (double) f.members.size();
    }

    /** Arqueros sueltos: se ponen a {@link Tactics#SKIRMISH_RANGE} del enemigo, tiran, y retroceden si se les vienen encima. */
    private void skirmish(ServerLevel level, Formation f, List<LivingEntity> enemies, Tactics.View v) {
        List<LivingEntity> melee = new ArrayList<>(v.enemies(Kind.INFANTRY));
        melee.addAll(v.enemies(Kind.CAVALRY));
        Vec3 center = f.center();
        LivingEntity threat = Formation.nearest(center, melee.isEmpty() ? enemies : melee, Double.MAX_VALUE);
        if (threat == null) return;
        Vec3 dir = Formation.flat(threat.position().subtract(center));
        double d = Formation.horizontal(center, threat.position());
        double speed = Formation.isMounted(f.kind) ? Tactics.MOUNTED_SPEED : Tactics.FOOT_SPEED;
        if (d < Tactics.SKIRMISH_TOO_CLOSE && !Tactics.easy()) f.position = center.subtract(dir.scale(speed * 2));   // que no los alcancen
        else if (d > Tactics.SKIRMISH_RANGE + 6) f.position = center.add(dir.scale(Math.min(speed, d - Tactics.SKIRMISH_RANGE)));
        else f.position = center;
        f.facing = dir;
        f.arrangement = Formation.Arrangement.LOOSE;
        f.form(level, enemies, 2.5, true);
    }

    /**
     * Rodeo de la caballería (Bannerlord: "flank"): primero a un punto afuera del costado del enemigo, después detrás de
     * él, y de ahí a la carga contra sus arqueros (o lo que haya). Si la caballería enemiga se le viene, la enfrenta.
     */
    private void flank(ServerLevel level, Formation f, List<LivingEntity> enemies, Tactics.View v, long now) {
        List<LivingEntity> enemyCav = new ArrayList<>(v.enemies(Kind.CAVALRY));
        if (Formation.nearest(f.center(), enemyCav, 15) != null || now - f.behaviorSince > Tactics.FLANK_TIMEOUT || f.flankStage >= 2) {
            List<LivingEntity> priority = new ArrayList<>(v.enemies(Kind.RANGED));
            if (priority.isEmpty()) priority = null;
            if (Formation.nearest(f.center(), enemyCav, 15) != null) priority = enemyCav;
            f.flankStage = 2;
            f.charge(enemies, priority);
            return;
        }
        Vec3 ownCenter = v.ownCenter;
        Vec3 enemyFacing = Formation.flat(ownCenter.subtract(v.enemyCenter));   // el enemigo mira hacia nosotros
        Vec3 perp = new Vec3(-enemyFacing.z, 0, enemyFacing.x);
        double halfWidth = 0;
        for (LivingEntity e : enemies) halfWidth = Math.max(halfWidth, Math.abs(e.position().subtract(v.enemyCenter).dot(perp)));
        Vec3 wide = v.enemyCenter.add(perp.scale(f.side * (halfWidth + Tactics.FLANK_WIDE)));
        Vec3 behind = v.enemyCenter.subtract(enemyFacing.scale(Tactics.FLANK_BEHIND)).add(perp.scale(f.side * 4));
        if (!dry(level, wide)) {   // del otro lado, si hay agua
            f.side = -f.side;
            wide = v.enemyCenter.add(perp.scale(f.side * (halfWidth + Tactics.FLANK_WIDE)));
        }
        Vec3 goal = f.flankStage == 0 ? wide : behind;
        if (Formation.horizontal(f.center(), goal) <= Tactics.FLANK_ARRIVED) {
            f.flankStage++;
            goal = behind;
        }
        f.arrangement = Formation.Arrangement.COLUMN;
        f.facing = Formation.flat(goal.subtract(f.center()));
        f.position = goal;
        f.form(level, enemies, 3, false);
    }

    private static boolean dry(ServerLevel level, Vec3 p) {
        BlockPos col = BlockPos.containing(p);
        if (!level.isLoaded(col)) return true;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
        return level.getFluidState(new BlockPos(col.getX(), y - 1, col.getZ())).isEmpty();
    }

    /** El general (capitán) se queda detrás de su infantería; pelea solo si alguien llega hasta él. */
    private void commandGeneral(ServerLevel level, List<LivingEntity> enemies) {
        AbstractRecruitEntity g = general;
        if (g == null) return;
        if (Formation.nearestDistance(g, enemies) <= 6) {
            Formation.fight(g, enemies, null);
            return;
        }
        Formation inf = formations.get(Kind.INFANTRY);
        Vec3 at = inf == null || inf.members.isEmpty() ? g.position() : inf.position.subtract(inf.facing.scale(inf.depth(inf.members.size()) + 8));
        Formation.toSlot(g, Armies.ground(level, at, g.getY()));
    }

    /**
     * Terreno: la loma más alta cerca del ejército (a {@link Tactics#HILL_SEARCH} bloques, del lado propio), si es al menos
     * {@link Tactics#HILL_MIN_RISE} más alta que donde está y no tiene agua. Null = no hay.
     */
    @Nullable
    static Vec3 findHill(ServerLevel level, Vec3 own, Vec3 enemy) {
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(own.x), (int) Math.floor(own.z));
        Vec3 toEnemy = Formation.flat(enemy.subtract(own));
        Vec3 best = null;
        int bestY = baseY + Tactics.HILL_MIN_RISE - 1;
        for (int dx = -Tactics.HILL_SEARCH; dx <= Tactics.HILL_SEARCH; dx += 4) {
            for (int dz = -Tactics.HILL_SEARCH; dz <= Tactics.HILL_SEARCH; dz += 4) {
                Vec3 p = own.add(dx, 0, dz);
                if (p.subtract(own).dot(toEnemy) > Formation.horizontal(own, enemy) * 0.5) continue;   // no ir hacia el enemigo
                BlockPos col = BlockPos.containing(p);
                if (!level.isLoaded(col)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
                if (y > bestY && level.getFluidState(new BlockPos(col.getX(), y - 1, col.getZ())).isEmpty()) {
                    bestY = y;
                    best = new Vec3(col.getX() + 0.5, y, col.getZ() + 0.5);
                }
            }
        }
        return best;
    }

    /** Para las pruebas: el general de esta batalla, si hay. */
    public static boolean exists(String key) {
        return ALL.containsKey(key);
    }

    /** El centro de la formación de ese tipo (para las pruebas y el que maneja la batalla). */
    @Nullable
    public Vec3 position(Kind k) {
        Formation f = formations.get(k);
        return f == null ? null : f.position;
    }

    static RandomSource random(ServerLevel level) {
        return level.getRandom();
    }

    /** ¿Hay alguien de este bando peleando? (para saber si la batalla sigue viva). */
    public boolean engaged() {
        for (Formation f : formations.values()) if (engagedShare(f) > 0) return true;
        return false;
    }

    static List<AbstractRecruitEntity> alive(List<AbstractRecruitEntity> list) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (AbstractRecruitEntity s : list) if (s.isAlive()) out.add(s);
        return out;
    }

    static double power(List<? extends LivingEntity> side) {
        return BattleGeneral.power(side);
    }
}
