package com.ale.warbandcraft.ai;

import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Destrabador de soldados: el buscador de caminos es de Recruits y no lo tocamos, pero desde afuera se
 * nota cuando un soldado que tiene a dónde ir no avanza. Entonces, de a poco:
 * 1. salta y vuelve a calcular el camino;
 * 2. da un saltito de 2-3 bloques hacia su destino, a un lugar libre;
 * 3. si sigue igual, avanza hasta {@link #BIG_STEP} bloques, o aparece al lado del dueño si lo seguía
 *    (como los lobos).
 * No toca a los que están peleando (de eso se encarga el combate de Recruits) ni a los que van en bote.
 */
public final class Unstuck {
    /** Cada cuánto se revisa (ticks). */
    public static int INTERVAL = 10;
    /** Menos que esto (en bloques, horizontal) entre revisiones = no avanzó. */
    private static double MIN_PROGRESS = 0.35;
    private static int JUMP_AFTER = 30, HOP_AFTER = 80, BIG_AFTER = 200;
    private static double HOP_STEP = 2.5;
    public static double BIG_STEP = 10.0;
    /** A esta distancia del destino ya llegó (guardia/mover) o ya está con el dueño (seguir). */
    // Seguir: Recruits deja que el soldado se quede a unos bloques del dueño; recién lejos cuenta como trabado.
    private static final double ARRIVED_HOLD = 3.0, ARRIVED_FOLLOW = 10.0;

    private static final int FOLLOW = 1, HOLD = 2, BACK_TO_POS = 3, HOLD_OWNER_POS = 4, PROTECT = 5;

    public enum Action { NONE, JUMP, HOP, BIG_STEP, TO_OWNER }

    private static final class Info {
        Vec3 lastPos;
        Vec3 lastDest;
        int stuck;
        boolean jumped;
    }

    private static final Map<UUID, Info> INFO = new HashMap<>();

    private Unstuck() {}

    /** Revisión periódica de todos los soldados con dueño de este mundo. */
    public static void tick(ServerLevel level) {
        for (AbstractRecruitEntity r : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getOwnerUUID() != null && Soldiers.isSoldier(r))) {
            update(level, r, INTERVAL);
        }
        if (level == level.getServer().overworld() && level.getServer().getTickCount() % 1200 == 0) {
            INFO.keySet().removeIf(id -> findAnywhere(level, id) == null);   // soldados que ya no existen
        }
    }

    @Nullable
    private static Entity findAnywhere(ServerLevel any, UUID id) {
        for (ServerLevel l : any.getServer().getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    /** A dónde quiere ir el soldado, o null si no tiene a dónde (o está peleando). */
    @Nullable
    public static Vec3 destination(ServerLevel level, AbstractRecruitEntity r) {
        LivingEntity target = r.getTarget();
        if (target != null && target.isAlive()) return null;
        int s = r.getFollowState();
        if (s == HOLD || s == BACK_TO_POS || s == HOLD_OWNER_POS) return r.getHoldPos();
        if (s == FOLLOW || s == PROTECT) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(r.getOwnerUUID());
            if (owner != null && owner.level() == level && !owner.isSpectator()) return owner.position();
        }
        return null;
    }

    /** Una revisión de un soldado ({@code elapsed} = ticks desde la anterior). Devuelve lo que hizo. */
    public static Action update(ServerLevel level, AbstractRecruitEntity r, int elapsed) {
        if (com.ale.warbandcraft.nav.Mover.moving(r)) return forget(r);   // lo lleva la malla de navegación (asedios)
        Entity body = r.getVehicle() instanceof AbstractHorse horse ? horse : r;
        if (r.isPassenger() && body == r || body.isPassenger()) return forget(r);   // en bote u otra cosa: no se toca
        Vec3 dest = destination(level, r);
        if (dest == null) return forget(r);
        boolean following = r.getFollowState() == FOLLOW || r.getFollowState() == PROTECT;
        double arrived = following ? ARRIVED_FOLLOW : ARRIVED_HOLD;
        Vec3 pos = body.position();
        if (horizontal(pos, dest) <= arrived) return forget(r);

        Info info = INFO.computeIfAbsent(r.getUUID(), k -> new Info());
        if (info.lastPos == null || info.lastDest == null || info.lastDest.distanceToSqr(dest) > 4.0 && !following) {
            info.lastPos = pos;
            info.lastDest = dest;
            info.stuck = 0;
            info.jumped = false;
            return Action.NONE;
        }
        info.lastDest = dest;
        if (horizontal(pos, info.lastPos) > MIN_PROGRESS) {
            info.lastPos = pos;
            info.stuck = 0;
            info.jumped = false;
            return Action.NONE;
        }
        info.lastPos = pos;
        info.stuck += elapsed;

        if (info.stuck >= BIG_AFTER) {
            info.stuck = 0;
            info.jumped = false;
            if (following && horizontal(pos, dest) > 12.0) {
                Vec3 near = Ground.nearestStandable(level, BlockPos.containing(dest), 3);
                if (near != null) {
                    move(body, r, near);
                    return Action.TO_OWNER;
                }
            }
            return stepToward(level, body, r, pos, dest, BIG_STEP) ? Action.BIG_STEP : Action.NONE;
        }
        if (info.stuck >= HOP_AFTER) {
            Vec3 toward = nextNode(r, pos);
            if (stepToward(level, body, r, pos, toward != null ? toward : dest, HOP_STEP)) {
                info.stuck = JUMP_AFTER + 1;   // si sigue trabado, otro saltito en ~2,5 s
                return Action.HOP;
            }
            return Action.NONE;
        }
        if (info.stuck >= JUMP_AFTER && !info.jumped) {
            info.jumped = true;
            if (body.onGround()) {
                if (body == r) r.getJumpControl().jump();
                else body.setDeltaMovement(body.getDeltaMovement().add(0, 0.5, 0));
            }
            r.getNavigation().stop();   // el goal de Recruits lo vuelve a calcular
            return Action.JUMP;
        }
        return Action.NONE;
    }

    /** El próximo punto del camino que Recruits calculó (si es distinto de donde ya está). */
    @Nullable
    public static Vec3 nextNode(AbstractRecruitEntity r, Vec3 pos) {
        Path path = r.getNavigation().getPath();
        if (path == null || path.isDone()) return null;
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node n = path.getNode(i);
            Vec3 v = Vec3.atBottomCenterOf(n.asBlockPos());
            if (horizontal(v, pos) >= 1.5) return v;
        }
        return null;
    }

    /** Lo mueve hasta {@code step} bloques hacia {@code toward}, a un lugar libre. */
    public static boolean stepToward(ServerLevel level, Entity body, AbstractRecruitEntity r, Vec3 from, Vec3 toward, double step) {
        Vec3 dir = new Vec3(toward.x - from.x, 0, toward.z - from.z);
        double len = dir.length();
        if (len < 0.5) return false;
        double dist = Math.min(step, len);
        Vec3 aim = from.add(dir.scale(dist / len));
        int yHint = (int) Math.floor(Math.max(from.y, Math.min(toward.y, from.y + dist)));
        Integer y = Ground.surfaceY(level, (int) Math.floor(aim.x), (int) Math.floor(aim.z), yHint, 3, 6);
        Vec3 spot = y != null ? new Vec3(Math.floor(aim.x) + 0.5, y, Math.floor(aim.z) + 0.5)
                : Ground.nearestStandable(level, BlockPos.containing(aim), 2);
        if (spot == null || !Ground.canStand(level, BlockPos.containing(spot))) {
            spot = Ground.nearestStandable(level, BlockPos.containing(aim), 2);
        }
        if (spot == null) return false;
        move(body, r, spot);
        return true;
    }

    public static void move(Entity body, AbstractRecruitEntity r, Vec3 spot) {
        r.getNavigation().stop();
        body.teleportTo(spot.x, spot.y, spot.z);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0;
    }

    private static Action forget(AbstractRecruitEntity r) {
        INFO.remove(r.getUUID());
        return Action.NONE;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
