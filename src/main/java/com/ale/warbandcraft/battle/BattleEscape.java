package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.ai.Unstuck;
import com.ale.warbandcraft.sapper.SapperRules;
import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Destrabador de batalla (H9, sin teletransportes desde H17). En una aldea, un bosque o un castillo la formación importa
 * poco: lo que importa es llegar a pelear. Una vez por segundo, para cada soldado de la batalla, se mira si se acerca a
 * lo que busca (su enemigo o su puesto). Si en {@link #STUCK_TICKS} no se acercó:
 * <ol>
 *   <li>Si iba a su puesto en la formación: la deja y sale a buscar enemigos ({@link Action#FREE}).</li>
 *   <li>Si iba a su enemigo y hay camino (se traba con otros, un escalón): saltito hacia el camino.</li>
 *   <li>Si no hay camino de Minecraft (encerrado, una casa, un patio): el zapador pica hacia el enemigo; el resto busca
 *       el camino por la malla de navegación ({@code nav/}: puertas, escaleras, rodeos) y va por ahí
 *       ({@link Action#NAV}). Si tampoco hay, espera ({@link Action#HOLD}) y prueba de nuevo.</li>
 * </ol>
 * Los defensores de un castillo ({@code stayInside}) no salen: trabados yendo a su puesto dan saltitos hacia él, y si
 * no pueden llegar a un enemigo que está afuera lo sueltan y se quedan en su lugar ({@link Action#HOLD}).
 */
public final class BattleEscape {
    /** Sin acercarse al menos {@link #MIN_PROGRESS} bloques en este tiempo = trabado. */
    public static int STUCK_TICKS = 60;
    private static double MIN_PROGRESS = 1.0;
    /** Ya llegó: a esta distancia del enemigo (cuerpo a cuerpo) o del puesto. */
    private static double MELEE_REACH = 3.0;
    /** El zapador que picó tantas veces sin acercarse busca el camino por la malla. */
    private static int MAX_DIGS = 6;
    /** Bloques más duros que esto no se pican (obsidiana, bedrock...). */
    private static float MAX_HARDNESS = 10.0F;

    public enum Action { NONE, FREE, HOP, DIG, NAV, HOLD }

    /** Qué hacer con el soldado. */
    public record Decision(Action action, @Nullable Vec3 moveTo) {
        static final Decision NONE = new Decision(Action.NONE, null);
    }

    private static final class Info {
        double best = Double.MAX_VALUE;
        long since;
        @Nullable Vec3 goal;
        int digs;
    }

    private static final Map<UUID, Info> INFO = new HashMap<>();

    private BattleEscape() {}

    public static void forget(Entity e) {
        INFO.remove(e.getUUID());
    }

    public static void clear() {
        INFO.clear();
    }

    /**
     * Revisa al soldado. {@code commanded} = lo maneja el general (puede mandarlo a una puerta); si no, solo se
     * lo destraba con saltitos, picando o teletransportándolo.
     */
    public static Decision check(ServerLevel level, AbstractRecruitEntity s, boolean commanded) {
        return check(level, s, commanded, false);
    }

    /** Igual, pero con {@code stayInside} (defensor de castillo) nunca sale de donde está. */
    public static Decision check(ServerLevel level, AbstractRecruitEntity s, boolean commanded, boolean stayInside) {
        LivingEntity target = s.getTarget();
        boolean fighting = target != null && target.isAlive() && !Surrender.is(target);
        Vec3 goal = fighting ? target.position() : s.getFollowState() == 3 ? s.getHoldPos() : null;
        if (goal == null || s.isPassenger() && !(s.getVehicle() instanceof AbstractHorse)) {
            forget(s);
            return Decision.NONE;
        }
        Entity body = s.getVehicle() instanceof AbstractHorse h ? h : s;
        Vec3 pos = body.position();
        double dist = pos.distanceTo(goal);
        long now = level.getGameTime();
        Info info = INFO.computeIfAbsent(s.getUUID(), k -> new Info());

        // Yendo por la malla: lo lleva el Mover hasta que llegue o se trabe
        if (com.ale.warbandcraft.nav.Mover.moving(s)) {
            info.best = dist;
            info.since = now;
            return new Decision(Action.NAV, null);
        }

        boolean reached = dist <= MELEE_REACH
                || fighting && BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED
                && dist <= BattleGeneral.RANGED_REACH && s.hasLineOfSight(target);
        if (reached || info.goal == null || info.goal.distanceTo(goal) > 8 || dist < info.best - MIN_PROGRESS) {
            info.goal = goal;
            info.best = dist;
            info.since = now;
            info.digs = 0;
            return Decision.NONE;
        }
        info.goal = goal;
        if (now - info.since < STUCK_TICKS) return Decision.NONE;

        // Trabado yendo a su puesto: se olvida de la formación (en el castillo, saltito hacia el puesto)
        if (!fighting && stayInside) {
            info.since = now;
            info.best = dist;
            Vec3 toward = Unstuck.nextNode(s, pos);
            Unstuck.stepToward(level, body, s, pos, toward != null ? toward : goal, 2.5);
            return new Decision(Action.HOP, null);
        }
        if (!fighting) {
            info.since = now;
            info.best = dist;
            return new Decision(commanded ? Action.FREE : Action.NONE, null);
        }

        // Trabado yendo al enemigo: ¿hay camino?
        Path path = s.getNavigation().createPath(BlockPos.containing(goal), 1);
        if (path != null && path.canReach()) {
            info.since = now;
            Vec3 toward = Unstuck.nextNode(s, pos);
            Unstuck.stepToward(level, body, s, pos, toward != null ? toward : goal, 2.5);
            return new Decision(Action.HOP, null);
        }

        // Encerrado. En el castillo: el enemigo está afuera, se queda adentro defendiendo
        if (stayInside) {
            info.goal = null;
            return new Decision(Action.HOLD, null);
        }
        if (SapperRules.isSapper(s) && info.digs < MAX_DIGS) {
            info.digs++;
            if (dig(level, s, pos, goal)) {
                info.best = dist;       // le da otros 3 s para avanzar por el agujero
                info.since = now - STUCK_TICKS + 20;
                return new Decision(Action.DIG, null);
            }
        }
        // Por la malla de navegación (puertas, escaleras, rodeos); a caballo no: un saltito
        info.since = now;
        info.best = dist;
        if (body != s) {
            Unstuck.stepToward(level, body, s, pos, goal, 2.5);
            return new Decision(Action.HOP, null);
        }
        com.ale.warbandcraft.nav.NavPath.Bounds b = com.ale.warbandcraft.nav.NavPath.Bounds.around(BlockPos.containing(pos), NAV_REACH);
        if (com.ale.warbandcraft.nav.Mover.go(s, goal, 2.0, com.ale.warbandcraft.nav.NavCaps.SOLDIER, b, "escape")) {
            return new Decision(Action.NAV, null);
        }
        return new Decision(Action.HOLD, null);   // ni por la malla: espera y prueba de nuevo
    }

    /** Hasta dónde busca el camino por la malla. */
    public static int NAV_REACH = 48;

    // ------------------------------------------------------------------ picar

    /** Rompe los bloques de los pies y la cabeza en la dirección del enemigo. True si rompió algo. */
    private static boolean dig(ServerLevel level, AbstractRecruitEntity s, Vec3 pos, Vec3 goal) {
        if (!ForgeEventFactory.getMobGriefingEvent(level, s)) return false;
        Vec3 dir = new Vec3(goal.x - pos.x, 0, goal.z - pos.z);
        if (dir.lengthSqr() < 0.01) return false;
        dir = dir.normalize();
        BlockPos feet = BlockPos.containing(pos.x + dir.x, pos.y, pos.z + dir.z);
        boolean broke = false;
        int up = goal.y > pos.y + 1 ? 2 : 1;    // si el enemigo está más arriba, también el de encima de la cabeza
        for (int dy = 0; dy <= up; dy++) broke |= breakIfAllowed(level, s, feet.above(dy));
        if (goal.y < pos.y - 1) broke |= breakIfAllowed(level, s, feet.below());
        if (broke) s.swing(InteractionHand.MAIN_HAND);
        return broke;
    }

    private static boolean breakIfAllowed(ServerLevel level, AbstractRecruitEntity s, BlockPos p) {
        BlockState state = level.getBlockState(p);
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(level, p);
        if (hardness < 0 || hardness > MAX_HARDNESS) return false;
        if (level.getBlockEntity(p) instanceof Container) return false;   // cofres y barriles no
        return level.destroyBlock(p, true, s);
    }
}
