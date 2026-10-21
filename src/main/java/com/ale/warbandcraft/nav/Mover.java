package com.ale.warbandcraft.nav;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lleva a un soldado por un camino de la malla, tick a tick, sin la navegación de Minecraft (la anula un objetivo de
 * máxima prioridad, {@link RouteGoal}): camina hacia el próximo lugar, salta los escalones, abre las puertas, sube y
 * baja las escaleras (guionado: Minecraft no sabe treparlas a propósito) y cruza el agua en bote (lo remamos
 * nosotros). Recruits sigue a cargo de pelear: quien da las órdenes saca al soldado del camino ({@link #stop}) para
 * que pelee.
 */
public final class Mover {
    /** Velocidad al caminar (como la de Recruits yendo a un lugar). */
    public static double SPEED = 1.05;
    /** Subiendo/bajando escaleras y remando (bloques por tick). */
    public static double CLIMB_SPEED = 0.16, ROW_SPEED = 0.32;
    /** Sin acercarse al próximo lugar: salto, saltito y, último recurso, aparece ahí. */
    private static int JUMP_AFTER = 30, NUDGE_AFTER = 80, TELEPORT_AFTER = 160;
    /** Teletransportes antes de darse por vencido (el que dio la orden busca otro camino). */
    private static int MAX_TELEPORTS = 3;

    /** Un camino en curso. */
    public static final class Run {
        public final List<Step> path;
        public final Vec3 goal;
        public final NavCaps caps;
        /** Quién lo mandó (para no pisarse: asalto, defensa...). */
        public final String by;
        /** Cuándo empezó (para saber si el camino es viejo). */
        public final long started;
        /** El próximo paso. */
        public int i = 1;
        /** Quieto esperando (el guía espera a su grupo). */
        public boolean paused;
        public boolean done, failed;
        double best = Double.MAX_VALUE;
        long bestTick;
        int teleports;
        @Nullable UUID boat;
        /** Saliendo de la escalera arriba: deslizándose hacia el lugar (ticks que van). */
        int glide = -1;

        Run(List<Step> path, Vec3 goal, NavCaps caps, String by, long now) {
            this.path = path;
            this.goal = goal;
            this.caps = caps;
            this.by = by;
            this.started = now;
            this.bestTick = now;
        }

        public Step next() {
            return path.get(Math.min(i, path.size() - 1));
        }

        public boolean active() {
            return !done && !failed;
        }
    }

    private static final Map<UUID, Run> RUNS = new HashMap<>();

    private Mover() {}

    public static void clear() {
        RUNS.clear();
    }

    /**
     * Busca el camino y lo empieza. False si no hay (ni siquiera uno que acerque): sigue con lo que tenía.
     */
    public static boolean go(AbstractRecruitEntity s, Vec3 goal, double reach, NavCaps caps, NavPath.Bounds bounds, String by) {
        if (!(s.level() instanceof ServerLevel level)) return false;
        List<Step> path = NavPath.find(level, s.blockPosition(), goal, reach, caps, bounds.include(s.blockPosition(), 8), true);
        if (path == null || path.size() < 2) {
            stop(s);
            return false;
        }
        start(s, path, goal, caps, by);
        return true;
    }

    /** Empieza un camino ya calculado (el del guía, para los que lo siguen): desde el lugar más cercano del camino. */
    public static void start(AbstractRecruitEntity s, List<Step> path, Vec3 goal, NavCaps caps, String by) {
        Run old = RUNS.get(s.getUUID());
        Run r = new Run(path, goal, caps, by, s.level().getGameTime());
        // Arranca desde el lugar del camino que tiene más cerca (y si está en un bote, sigue en él)
        int nearest = 0;
        double bd = Double.MAX_VALUE;
        for (int k = 0; k < path.size(); k++) {
            double d = path.get(k).center().distanceToSqr(s.position());
            if (d < bd) {
                bd = d;
                nearest = k;
            }
        }
        r.i = Math.min(path.size() - 1, nearest + 1);
        if (old != null && old.boat != null && s.getVehicle() instanceof Boat) r.boat = old.boat;
        RUNS.put(s.getUUID(), r);
    }

    @Nullable
    public static Run run(Entity s) {
        return RUNS.get(s.getUUID());
    }

    /** Está yendo (no terminó ni se trabó). */
    public static boolean moving(Entity s) {
        Run r = RUNS.get(s.getUUID());
        return r != null && r.active();
    }

    /** Lo saca del camino (para que pelee o porque cambió la orden). Si estaba en un bote, se baja en la orilla. */
    public static void stop(Entity s) {
        Run r = RUNS.remove(s.getUUID());
        if (r != null && s.getVehicle() instanceof Boat b && s.level() instanceof ServerLevel level) leaveBoat(level, s, b, r);
    }

    // ------------------------------------------------------------------ cada tick

    /** Un tick del soldado en su camino ({@link RouteGoal}). */
    static void tick(AbstractRecruitEntity s, Run r) {
        if (!(s.level() instanceof ServerLevel level) || !r.active()) return;
        if (s.isPassenger() && !(s.getVehicle() instanceof Boat)) {   // a caballo: esto no lo maneja
            r.failed = true;
            return;
        }
        if (r.i >= r.path.size()) {
            finish(level, s, r);
            return;
        }
        if (r.paused) {
            if (s.getVehicle() instanceof Boat b) b.setDeltaMovement(b.getDeltaMovement().multiply(0.5, 1, 0.5));
            return;
        }
        Step prev = r.path.get(r.i - 1), next = r.path.get(r.i);
        Vec3 np = next.center();
        long now = level.getGameTime();

        // En bote (o subiendo a uno): el agua se rema
        if (next.kind() == Step.Kind.WATER || s.getVehicle() instanceof Boat) {
            row(level, s, r, next, np);
            stuckCheck(level, s, r, np, now);
            return;
        }
        // Escalera: subir/bajar por la misma columna, guionado
        boolean sameColumn = BlockPos.getX(prev.pos()) == BlockPos.getX(next.pos()) && BlockPos.getZ(prev.pos()) == BlockPos.getZ(next.pos());
        if (sameColumn && (next.kind() == Step.Kind.LADDER || prev.kind() == Step.Kind.LADDER)) {
            climb(s, r, np);
            r.bestTick = now;
            return;
        }
        // Saliendo de arriba de una escalera al muro (o entrando a ella desde arriba): se desliza
        if (prev.kind() == Step.Kind.LADDER && np.y > s.getY() + 0.3 || r.glide >= 0) {
            glide(s, r, prev.center(), np);
            r.bestTick = now;
            return;
        }
        if (next.kind() == Step.Kind.CLIMB || next.kind() == Step.Kind.BREAK) {   // eso lo resuelve quien planea el asalto
            r.failed = true;
            return;
        }
        if (next.kind() == Step.Kind.DOOR) openDoor(level, next.blockPos());
        walk(s, np);
        double horiz = horizontal(s.position(), np);
        boolean corner = r.i + 1 < r.path.size() && r.path.get(r.i + 1).kind() == Step.Kind.WALK;
        if (horiz < (corner ? 0.75 : 0.45) && Math.abs(s.getY() - np.y) < 1.1) {
            r.i++;
            r.best = Double.MAX_VALUE;
            r.bestTick = now;
            return;
        }
        stuckCheck(level, s, r, np, now);
    }

    private static void walk(AbstractRecruitEntity s, Vec3 np) {
        s.getNavigation().stop();
        s.getMoveControl().setWantedPosition(np.x, np.y, np.z, SPEED);
        if (np.y > s.getY() + 0.5 && horizontal(s.position(), np) < 1.8 && s.onGround()) s.getJumpControl().jump();
        s.getLookControl().setLookAt(np.x, np.y + 1.5, np.z);
    }

    /** Sube o baja un peldaño (pegado a la escalera, sin gravedad). */
    private static void climb(AbstractRecruitEntity s, Run r, Vec3 np) {
        s.getNavigation().stop();
        // ¿Sigue la escalera? (si la tumbaron, se cae)
        BlockPos at = BlockPos.containing(s.getX(), Math.min(s.getY(), np.y) + 0.2, s.getZ());
        if (!(s.level().getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.LadderBlock)
                && !(s.level().getBlockState(BlockPos.containing(np)).getBlock() instanceof net.minecraft.world.level.block.LadderBlock)) {
            r.failed = true;
            return;
        }
        double y = s.getY();
        double dy = np.y - y;
        double step = Math.max(-CLIMB_SPEED, Math.min(CLIMB_SPEED, dy));
        double x = s.getX() + (np.x - s.getX()) * 0.5, z = s.getZ() + (np.z - s.getZ()) * 0.5;
        s.setDeltaMovement(Vec3.ZERO);
        s.fallDistance = 0;
        s.setPos(x, y + step, z);
        if (Math.abs(dy) <= CLIMB_SPEED + 1e-3) {
            s.setPos(np.x, np.y, np.z);
            r.i++;
            r.best = Double.MAX_VALUE;
        }
    }

    /** Del último peldaño al lugar de al lado (arriba del muro): en 8 ticks. */
    private static void glide(AbstractRecruitEntity s, Run r, Vec3 from, Vec3 np) {
        s.getNavigation().stop();
        r.glide++;
        double t = Math.min(1.0, r.glide / 8.0);
        double y = Math.max(from.y, np.y);
        s.setDeltaMovement(Vec3.ZERO);
        s.fallDistance = 0;
        s.setPos(from.x + (np.x - from.x) * t, y + 0.05, from.z + (np.z - from.z) * t);
        if (t >= 1.0) {
            s.setPos(np.x, np.y, np.z);
            r.glide = -1;
            r.i++;
            r.best = Double.MAX_VALUE;
        }
    }

    /** Al agua: sube a un bote (lo saca de la mochila: aparece en la orilla) y rema hasta el próximo lugar. */
    private static void row(ServerLevel level, AbstractRecruitEntity s, Run r, Step next, Vec3 np) {
        Boat boat = s.getVehicle() instanceof Boat b ? b : null;
        if (boat == null) {
            if (next.kind() != Step.Kind.WATER) return;
            if (horizontal(s.position(), np) > 2.5) {   // primero hasta la orilla
                walk(s, np);
                return;
            }
            boat = new Boat(level, np.x, np.y, np.z);
            boat.setYRot(s.getYRot());
            boat.getPersistentData().putBoolean(TAG_BOAT, true);
            level.addFreshEntity(boat);
            s.startRiding(boat, true);
            r.boat = boat.getUUID();
            return;
        }
        if (next.kind() != Step.Kind.WATER) {
            // Llegando a tierra: cuando está cerca, se baja ahí
            if (horizontal(boat.position(), np) <= 1.8) {
                leaveBoat(level, s, boat, r);
                s.setPos(np.x, np.y, np.z);
                r.i++;
                r.best = Double.MAX_VALUE;
                return;
            }
        } else if (horizontal(boat.position(), np) <= 0.9) {
            r.i++;
            r.best = Double.MAX_VALUE;
            return;
        }
        Vec3 dir = new Vec3(np.x - boat.getX(), 0, np.z - boat.getZ());
        if (dir.lengthSqr() > 1e-4) dir = dir.normalize();
        boat.setDeltaMovement(dir.x * ROW_SPEED, boat.getDeltaMovement().y, dir.z * ROW_SPEED);
        boat.setYRot((float) (Math.atan2(dir.z, dir.x) * 180 / Math.PI) - 90F);
    }

    /** Marca de los botes que sacan los soldados (se borran al bajarse). */
    public static final String TAG_BOAT = "warbandcraft:nav_boat";

    private static void leaveBoat(ServerLevel level, Entity s, Boat boat, Run r) {
        s.stopRiding();
        if (boat.getPersistentData().getBoolean(TAG_BOAT) && boat.getPassengers().isEmpty()) boat.discard();
        r.boat = null;
    }

    private static void openDoor(ServerLevel level, BlockPos p) {
        for (BlockPos q : new BlockPos[]{p, p.above()}) {
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof DoorBlock d && !s.getValue(DoorBlock.OPEN) && d.type().canOpenByHand()) {
                d.setOpen(null, level, s, q, true);
                return;
            }
            if (s.getBlock() instanceof FenceGateBlock && !s.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(q, s.setValue(FenceGateBlock.OPEN, true), 10);
                return;
            }
        }
    }

    /** Si no se acerca: salto, saltito, y al final aparece en el próximo lugar. */
    private static void stuckCheck(ServerLevel level, AbstractRecruitEntity s, Run r, Vec3 np, long now) {
        double d = s.position().distanceTo(np);
        if (d < r.best - 0.3) {
            r.best = d;
            r.bestTick = now;
            return;
        }
        long stuck = now - r.bestTick;
        if (stuck == JUMP_AFTER && s.onGround()) s.getJumpControl().jump();
        else if (stuck == NUDGE_AFTER && !s.isPassenger()) {
            Vec3 dir = np.subtract(s.position());
            if (dir.lengthSqr() > 1e-4) s.setDeltaMovement(dir.normalize().scale(0.35).add(0, 0.42, 0));
        } else if (stuck >= TELEPORT_AFTER) {
            if (++r.teleports > MAX_TELEPORTS) {
                r.failed = true;
                return;
            }
            Entity body = s.getVehicle() != null ? s.getVehicle() : s;
            body.teleportTo(np.x, np.y, np.z);
            r.i++;
            r.best = Double.MAX_VALUE;
            r.bestTick = now;
        }
    }

    private static void finish(ServerLevel level, AbstractRecruitEntity s, Run r) {
        if (s.getVehicle() instanceof Boat b) leaveBoat(level, s, b, r);
        r.done = true;
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Se olvida de los que ya no existen (cada tanto). */
    public static void cleanup(ServerLevel level) {
        RUNS.keySet().removeIf(id -> {
            Entity e = level.getEntity(id);
            return e != null && !e.isAlive();
        });
    }
}
