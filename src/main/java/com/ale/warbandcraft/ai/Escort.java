package com.ale.warbandcraft.ai;

import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Escolta (pedido de Ale): los soldados que te siguen, fuera de batallas, ejércitos, asedios y cuarteles.
 * 1. Te defienden: un bicho hostil a ≤ {@link #DEFEND_RANGE} tuyo (o cualquiera que te ataque a vos o a ellos) va
 *    primero; pelean en estado 0 (marca {@link #TAG}) y vuelven a seguirte. Creepers nunca. Lejos de vos, lo dejan.
 * 2. Se teletransportan al lado tuyo si quedan a más de {@link #TELEPORT_DISTANCE} (como los lobos).
 * 3. Nadan hacia vos (Recruits los deja flotando en la orilla).
 * 4. Si te subís a un bote, cada uno aparece en su propio bote (con su caballo adentro) y te sigue remando en dos
 *    columnas; al bajarte a tierra, se bajan al lado tuyo y los botes desaparecen.
 * Recruits pone "seguir" y "pelear" con la misma prioridad, y con el dueño a más de ~8 bloques gana seguir: por eso no
 * peleaban. Corre cada tick (botes y nado) y cada {@link #INTERVAL} ticks lo demás; no en GameTestServer.
 */
public final class Escort {
    /** Cada cuánto se revisa a los soldados (ticks). */
    public static int INTERVAL = 10;
    /** Bichos a esta distancia tuya (bloques) se atacan aunque todavía no te hayan pegado. */
    public static double DEFEND_RANGE = 16;
    /** Persiguiendo a uno, no se alejan más que esto de vos: lo dejan y vuelven. */
    public static double LEASH = 24;
    /** Más que esto de altura entre vos y el bicho: no se lo busca (otro piso, un acantilado). */
    public static double MAX_HEIGHT_DIFF = 8;
    /** Ticks persiguiendo sin acercarse 1 bloque: lo dejan por {@link #IGNORE_TICKS}. */
    public static int CHASE_GIVE_UP = 300, IGNORE_TICKS = 1200;
    /** Más lejos que esto de vos (bloques), aparecen a tu lado. */
    public static double TELEPORT_DISTANCE = 20;
    /** Fuerza de la brazada (por tick) y velocidad máxima nadando. */
    public static double SWIM_PUSH = 0.04, SWIM_SPEED = 0.2;
    /** Velocidad máxima remando (bloques/tick; un bote con jugador anda a ~0,4). */
    public static double BOAT_SPEED = 0.42;
    /** Botes más lejos que esto de su lugar en la fila aparecen al lado tuyo. */
    public static double BOAT_TELEPORT = 24;

    private static final int WANDER = 0, FOLLOW = 1, PASSIVE = 3;

    /** El soldado está peleando para defenderte (estado 0 prestado: después vuelve a seguirte). */
    public static final String TAG = "warbandcraft:escort";
    /** Marca de los botes de la escolta (se borran al bajarse o al cargarse vacíos). */
    public static final String TAG_BOAT = "warbandcraft:escort_boat";

    private record Chase(UUID target, double best, long since) {}

    private static final Map<UUID, Chase> CHASES = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> IGNORED = new HashMap<>();
    /** Soldados en el agua (se los empuja cada tick). */
    private static final Set<UUID> SWIMMERS = new HashSet<>();
    /** Bote de escolta → su lugar en la fila detrás del dueño (índice). */
    private static final Map<UUID, Integer> BOAT_SLOT = new HashMap<>();

    private Escort() {}

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % INTERVAL == 0) scan(level, now);
        steerBoats(level);
        swim(level);
    }

    // ---------------------------------------------------------------- revisión de cada soldado

    private static void scan(ServerLevel level, long now) {
        Map<UUID, List<AbstractRecruitEntity>> boating = new HashMap<>();
        for (AbstractRecruitEntity r : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getOwnerUUID() != null && Soldiers.isSoldier(r))) {
            update(level, r, now, boating);
        }
        // Lugares en la fila de botes: por dueño, en orden fijo (UUID) para que no se crucen
        BOAT_SLOT.clear();
        for (List<AbstractRecruitEntity> list : boating.values()) {
            list.sort(Comparator.comparing(Entity::getUUID));
            for (int i = 0; i < list.size(); i++) {
                Boat b = boatOf(list.get(i));
                if (b != null) BOAT_SLOT.put(b.getUUID(), i);
            }
        }
        for (Boat b : level.getEntities(EntityTypeTest.forClass(Boat.class), b -> b.getPersistentData().getBoolean(TAG_BOAT))) {
            if (b.getPassengers().isEmpty()) b.discard();   // quedó suelto
        }
        if (now % 1200 == 0) {
            CHASES.keySet().removeIf(id -> level.getEntity(id) == null);
            IGNORED.keySet().removeIf(id -> level.getEntity(id) == null);
        }
    }

    /** En batallas, ejércitos, asedios, cuarteles, etc. los maneja otro: la escolta no los toca. */
    public static boolean busy(AbstractRecruitEntity r) {
        if (com.ale.warbandcraft.nav.Mover.moving(r)) return true;
        CompoundTag pd = r.getPersistentData();
        return pd.contains(com.ale.warbandcraft.battle.BattleGeneral.TAG_BATTLE)
                || pd.contains(com.ale.warbandcraft.army.Armies.TAG_ARMY)
                || pd.contains(com.ale.warbandcraft.village.Quarters.TAG_QUARTERS)
                || pd.contains(com.ale.warbandcraft.invasion.AiWars.TAG_JOIN)
                || pd.contains(com.ale.warbandcraft.invasion.SiegeDefense.TAG_HELPER)
                || pd.contains(com.ale.warbandcraft.invasion.SiegeDefense.TAG_REINFORCEMENT)
                || pd.contains(com.ale.warbandcraft.battle.Surrender.TAG);
    }

    private static void update(ServerLevel level, AbstractRecruitEntity r, long now, Map<UUID, List<AbstractRecruitEntity>> boating) {
        CompoundTag pd = r.getPersistentData();
        boolean marked = pd.getBoolean(TAG);
        Boat myBoat = boatOf(r);
        if (busy(r)) {
            if (marked) pd.remove(TAG);   // ahora lo maneja otro: que no le devolvamos el "seguir"
            if (myBoat != null) leaveBoat(level, r, myBoat, null);
            return;
        }
        if (marked && r.getFollowState() != WANDER) {   // le diste otra orden mientras peleaba
            pd.remove(TAG);
            marked = false;
        }
        if (!marked && r.getFollowState() != FOLLOW) {
            if (myBoat != null) leaveBoat(level, r, myBoat, null);
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(r.getOwnerUUID());
        if (owner == null || owner.level() != level || owner.isSpectator()) {
            if (marked) backToFollow(r);
            if (myBoat != null) leaveBoat(level, r, myBoat, null);
            return;
        }

        // Botes: vos en un bote = ellos en el suyo; vos en tierra = se bajan
        if (owner.getVehicle() instanceof Boat ob && !isOurBoat(ob)) {
            if (marked) backToFollow(r);
            if (myBoat == null) myBoat = board(level, r, owner, ob);
            if (myBoat != null) boating.computeIfAbsent(owner.getUUID(), k -> new ArrayList<>()).add(r);
            return;
        }
        if (myBoat != null) {
            if (owner.onGround() && !owner.isInWater()) {
                Vec3 spot = spotNear(level, owner);
                if (spot != null) {
                    leaveBoat(level, r, myBoat, spot);
                    return;
                }
            }
            boating.computeIfAbsent(owner.getUUID(), k -> new ArrayList<>()).add(r);   // vos nadando: siguen remando
            return;
        }

        // 1. Defenderte
        if (defend(level, r, owner, now, marked)) return;
        if (pd.getBoolean(TAG)) backToFollow(r);

        // 2. Teletransporte
        Entity body = r.getVehicle() instanceof AbstractHorse h ? h : r;
        if (body.isPassenger()) return;
        LivingEntity t = r.getTarget();
        if (t != null && t.isAlive()) return;   // peleando por su cuenta (Recruits)
        double d = body.distanceTo(owner);
        if (d > TELEPORT_DISTANCE && !owner.isInWater() && (owner.onGround() || owner.isPassenger()) && !owner.isFallFlying()) {
            Vec3 spot = spotNear(level, owner);
            if (spot != null) {
                Unstuck.move(body, r, spot);
                return;
            }
        }
        // 3. Nadar
        if (body.isInWater() || d > 4 && body.onGround() && r.getNavigation().isDone() && waterAhead(level, body, owner)) {
            SWIMMERS.add(r.getUUID());
        }
    }

    // ---------------------------------------------------------------- defenderte

    /** Busca y pelea con lo que te amenaza. True = está peleando. */
    private static boolean defend(ServerLevel level, AbstractRecruitEntity r, ServerPlayer owner, long now, boolean marked) {
        if (r.getState() == PASSIVE) return false;   // en pasivo no ataca: lo elegiste vos
        LivingEntity t = r.getTarget();
        if (t instanceof Creeper) {
            r.setTarget(null);
            t = null;
        }
        if (t != null && !valid(level, r, owner, t, now)) {
            if (marked || t.distanceTo(owner) > LEASH) r.setTarget(null);
            t = null;
        }
        if (t == null) t = pick(level, r, owner, now);
        if (t == null) return false;
        if (!marked) {
            r.getPersistentData().putBoolean(TAG, true);
            r.setFollowState(WANDER);
        }
        if (r.getTarget() != t) r.setTarget(t);
        chaseCheck(r, t, now);
        return true;
    }

    private static boolean valid(ServerLevel level, AbstractRecruitEntity r, ServerPlayer owner, LivingEntity t, long now) {
        if (!t.isAlive() || t instanceof Creeper || t.distanceTo(owner) > LEASH) return false;
        if (ignored(r, t, now)) return false;
        return threat(owner, t) || t instanceof Mob m && m.getTarget() != null && isOurs(owner.getUUID(), m.getTarget());
    }

    @Nullable
    private static LivingEntity pick(ServerLevel level, AbstractRecruitEntity r, ServerPlayer owner, long now) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(DEFEND_RANGE, MAX_HEIGHT_DIFF, DEFEND_RANGE),
                e -> e.isAlive() && e != r && e != owner)) {
            if (e.distanceTo(owner) > DEFEND_RANGE || !threat(owner, e) || ignored(r, e, now)) continue;
            if (Math.abs(e.getY() - owner.getY()) > MAX_HEIGHT_DIFF) continue;
            boolean attacking = e instanceof Mob m && m.getTarget() != null && isOurs(owner.getUUID(), m.getTarget());
            if (!attacking && !owner.hasLineOfSight(e) && !r.hasLineOfSight(e)) continue;   // del otro lado de una pared, en una cueva
            double d = e.distanceToSqr(r);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Un bicho hostil (no creeper; neutrales y jefes solo si se enojaron con lo tuyo), o algo que te ataca. */
    private static boolean threat(ServerPlayer owner, LivingEntity e) {
        if (e instanceof Creeper) return false;
        boolean attacking = e instanceof Mob m && m.getTarget() != null && isOurs(owner.getUUID(), m.getTarget());
        if (attacking) return true;
        if (e instanceof Player || e instanceof AbstractRecruitEntity) return false;
        if (e instanceof NeutralMob || com.ale.warbandcraft.dungeon.Compat.isBoss(e)) return false;
        if (e instanceof Mob m && (m.isNoAi() || m.isPersistenceRequired() && m.hasCustomName())) return false;   // de adorno
        return e instanceof Enemy || e instanceof Raider;
    }

    private static boolean isOurs(UUID owner, LivingEntity t) {
        if (t instanceof Player p) return p.getUUID().equals(owner);
        return t instanceof AbstractRecruitEntity r && com.ale.warbandcraft.faction.Allies.same(r.getOwnerUUID(), owner);
    }

    private static boolean ignored(AbstractRecruitEntity r, LivingEntity e, long now) {
        Map<UUID, Long> m = IGNORED.get(r.getUUID());
        if (m == null) return false;
        m.values().removeIf(until -> until <= now);
        return m.containsKey(e.getUUID());
    }

    /** Si en {@link #CHASE_GIVE_UP} ticks no se acercó 1 bloque, lo deja un rato (no llega: agua, pared, arriba). */
    private static void chaseCheck(AbstractRecruitEntity r, LivingEntity t, long now) {
        double d = r.distanceTo(t);
        Chase c = CHASES.get(r.getUUID());
        if (c == null || !c.target().equals(t.getUUID()) || d < c.best() - 1) {
            CHASES.put(r.getUUID(), new Chase(t.getUUID(), d, now));
            return;
        }
        if (now - c.since() > CHASE_GIVE_UP && d > 3) {
            IGNORED.computeIfAbsent(r.getUUID(), k -> new HashMap<>()).put(t.getUUID(), now + IGNORE_TICKS);
            CHASES.remove(r.getUUID());
            r.setTarget(null);
        }
    }

    private static void backToFollow(AbstractRecruitEntity r) {
        r.getPersistentData().remove(TAG);
        CHASES.remove(r.getUUID());
        LivingEntity t = r.getTarget();
        if (t != null && !t.isAlive()) r.setTarget(null);
        r.setFollowState(FOLLOW);
    }

    // ---------------------------------------------------------------- teletransporte y nado

    /** Un lugar firme detrás tuyo (o al lado), o null. */
    @Nullable
    private static Vec3 spotNear(ServerLevel level, ServerPlayer owner) {
        Vec3 back = owner.getLookAngle().multiply(1, 0, 1);
        back = back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(-2);
        Vec3 s = Ground.nearestStandable(level, BlockPos.containing(owner.position().add(back)), 3);
        return s != null ? s : Ground.nearestStandable(level, owner.blockPosition(), 3);
    }

    /** Agua entre el soldado y vos, pegada a él (Recruits no lo mete: se queda en la orilla). */
    private static boolean waterAhead(ServerLevel level, Entity body, ServerPlayer owner) {
        Vec3 dir = owner.position().subtract(body.position()).multiply(1, 0, 1);
        if (dir.lengthSqr() < 1e-4) return false;
        dir = dir.normalize();
        for (int i = 1; i <= 2; i++) {
            BlockPos p = BlockPos.containing(body.position().add(dir.scale(i)));
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) return true;
        }
        return false;
    }

    private static void swim(ServerLevel level) {
        if (SWIMMERS.isEmpty()) return;
        SWIMMERS.removeIf(id -> {
            if (!(level.getEntity(id) instanceof AbstractRecruitEntity r) || !r.isAlive()) return !anyLevelHas(level, id);
            if (r.getFollowState() != FOLLOW || busy(r) || r.getOwnerUUID() == null) return true;
            Entity body = r.getVehicle() instanceof AbstractHorse h ? h : r;
            if (body.isPassenger()) return true;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(r.getOwnerUUID());
            if (owner == null || owner.level() != level) return true;
            Vec3 to = owner.position().subtract(body.position());
            Vec3 flat = new Vec3(to.x, 0, to.z);
            double d = flat.length();
            if (d < 2.5) return !body.isInWater();
            Vec3 dir = flat.scale(1 / d);
            Vec3 v = body.getDeltaMovement();
            if (!body.isInWater()) {
                if (!body.onGround()) return false;
                if (!waterAhead(level, body, owner)) return true;
                body.setDeltaMovement(dir.x * SWIM_SPEED, v.y, dir.z * SWIM_SPEED);   // a la orilla y al agua
                return false;
            }
            double vx = v.x + dir.x * SWIM_PUSH, vz = v.z + dir.z * SWIM_PUSH;
            double h = Math.sqrt(vx * vx + vz * vz);
            if (h > SWIM_SPEED) {
                vx *= SWIM_SPEED / h;
                vz *= SWIM_SPEED / h;
            }
            double vy = v.y;
            if (body.horizontalCollision) vy = 0.3;              // trepar a la orilla
            else if (body.isUnderWater()) vy = Math.max(vy, 0.04);   // no hundirse
            body.setDeltaMovement(vx, vy, vz);
            body.setYRot((float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90F);
            if (body == r) r.setYHeadRot(r.getYRot());
            return false;
        });
    }

    private static boolean anyLevelHas(ServerLevel any, UUID id) {
        for (ServerLevel l : any.getServer().getAllLevels()) if (l.getEntity(id) != null) return true;
        return false;
    }

    // ---------------------------------------------------------------- botes

    private static boolean isOurBoat(Boat b) {
        CompoundTag pd = b.getPersistentData();
        return pd.getBoolean(TAG_BOAT) || pd.getBoolean(com.ale.warbandcraft.nav.Mover.TAG_BOAT);
    }

    /** El bote de escolta en el que va (él o su caballo), o null. */
    @Nullable
    private static Boat boatOf(AbstractRecruitEntity r) {
        Entity v = r.getVehicle();
        if (v instanceof AbstractHorse h) v = h.getVehicle();
        return v instanceof Boat b && b.getPersistentData().getBoolean(TAG_BOAT) ? b : null;
    }

    /** Le da un bote en el agua cerca del tuyo y lo sube (a caballo: el caballo va adentro, con él encima). */
    @Nullable
    private static Boat board(ServerLevel level, AbstractRecruitEntity r, ServerPlayer owner, Boat ob) {
        if (r.distanceTo(owner) > 64) return null;
        Vec3 spot = waterNear(level, ob);
        if (spot == null) return null;
        Boat boat = new Boat(level, spot.x, spot.y, spot.z);
        boat.setVariant(ob.getVariant());
        boat.setYRot(ob.getYRot());
        boat.getPersistentData().putBoolean(TAG_BOAT, true);
        level.addFreshEntity(boat);
        r.getNavigation().stop();
        Entity rider = r.getVehicle() instanceof AbstractHorse h ? h : r;
        rider.teleportTo(spot.x, spot.y, spot.z);
        rider.startRiding(boat, true);
        SWIMMERS.remove(r.getUUID());
        return boat;
    }

    /** Agua libre a 3-7 bloques detrás del bote (o donde haya alrededor). */
    @Nullable
    private static Vec3 waterNear(ServerLevel level, Boat ob) {
        double yaw = Math.toRadians(ob.getYRot());
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        for (int dist = 4; dist <= 8; dist += 2) {
            for (int a = 0; a < 8; a++) {
                double ang = Math.PI + (a % 2 == 0 ? 1 : -1) * ((a + 1) / 2) * Math.PI / 6;
                Vec3 dir = new Vec3(fwd.x * Math.cos(ang) - fwd.z * Math.sin(ang), 0, fwd.x * Math.sin(ang) + fwd.z * Math.cos(ang));
                Vec3 p = ob.position().add(dir.scale(dist));
                Vec3 w = waterSurface(level, p);
                if (w != null) return w;
            }
        }
        return null;
    }

    @Nullable
    private static Vec3 waterSurface(ServerLevel level, Vec3 p) {
        BlockPos base = BlockPos.containing(p);
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos q = base.offset(0, dy, 0);
            if (!level.isLoaded(q)) return null;
            if (level.getFluidState(q).isSource() && level.getBlockState(q.above()).isAir() && level.getBlockState(q.above(2)).isAir()) {
                return new Vec3(q.getX() + 0.5, q.getY() + 1, q.getZ() + 0.5);
            }
        }
        return null;
    }

    /** Se baja del bote (a {@code spot}, si hay) y el bote se borra. */
    private static void leaveBoat(ServerLevel level, AbstractRecruitEntity r, Boat boat, @Nullable Vec3 spot) {
        Entity rider = r.getVehicle() instanceof AbstractHorse h ? h : r;
        rider.stopRiding();
        if (spot != null) {
            rider.teleportTo(spot.x, spot.y, spot.z);
            rider.setDeltaMovement(Vec3.ZERO);
            rider.fallDistance = 0;
        }
        if (boat.getPassengers().isEmpty()) boat.discard();
        BOAT_SLOT.remove(boat.getUUID());
    }

    /** Cada tick: cada bote de escolta rema a su lugar en la fila (dos columnas detrás del tuyo). */
    private static void steerBoats(ServerLevel level) {
        if (BOAT_SLOT.isEmpty()) return;
        for (Map.Entry<UUID, Integer> e : BOAT_SLOT.entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof Boat boat) || !boat.isAlive()) continue;
            AbstractRecruitEntity r = null;
            for (Entity p : boat.getPassengers()) {
                if (p instanceof AbstractRecruitEntity s) r = s;
                else if (p instanceof AbstractHorse h && h.getFirstPassenger() instanceof AbstractRecruitEntity s) r = s;
            }
            if (r == null || r.getOwnerUUID() == null) continue;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(r.getOwnerUUID());
            if (owner == null || owner.level() != level) continue;
            Entity lead = owner.getVehicle() instanceof Boat ob ? ob : owner;
            double yaw = Math.toRadians(lead.getYRot());
            Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
            int i = e.getValue();
            Vec3 goal = lead.position().subtract(fwd.scale(3.5 + 3.0 * (i / 2))).add(right.scale(i % 2 == 0 ? -1.6 : 1.6));
            Vec3 to = new Vec3(goal.x - boat.getX(), 0, goal.z - boat.getZ());
            double d = to.length();
            if (d > BOAT_TELEPORT && level.getGameTime() % INTERVAL == 0) {
                Vec3 w = owner.getVehicle() instanceof Boat ob ? waterNear(level, ob) : waterSurface(level, goal);
                if (w != null) {
                    boat.teleportTo(w.x, w.y, w.z);
                    boat.setDeltaMovement(Vec3.ZERO);
                    continue;
                }
            }
            Vec3 v = boat.getDeltaMovement();
            if (d < 0.6) {
                boat.setDeltaMovement(v.x * 0.5, v.y, v.z * 0.5);
                continue;
            }
            Vec3 dir = to.scale(1 / d);
            double speed = Math.min(BOAT_SPEED, 0.08 + d * 0.06);
            boat.setDeltaMovement(dir.x * speed, v.y, dir.z * speed);
            boat.setYRot((float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90F);
        }
    }

    /** Al cerrar el servidor. */
    public static void clear() {
        CHASES.clear();
        IGNORED.clear();
        SWIMMERS.clear();
        BOAT_SLOT.clear();
    }
}
