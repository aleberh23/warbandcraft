package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.Maintenance;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * El trabajo del de Mantenimiento: de día pide la próxima tarea ({@link Maintenance#next}), camina hasta ahí de verdad,
 * trabaja {@link #WORK_TICKS} y la hace (cobra y pone los bloques). Trabado {@link #STUCK_TICKS} sin acercarse, aparece al
 * lado. Sin materiales o sin nada que hacer, espera {@link #IDLE_TICKS} y vuelve a mirar.
 */
public class MaintainerGoal extends Goal {
    /** A esta distancia ya llega; cuánto trabaja en el lugar; cada cuánto vuelve a mirar si no había nada (o faltaba algo). */
    public static double REACH = 3.0;
    public static int WORK_TICKS = 40, IDLE_TICKS = 20 * 20;
    /** Sin acercarse 1 bloque en tanto, aparece al lado. */
    public static int STUCK_TICKS = 20 * 15;

    private final MaintainerEntity w;
    @Nullable
    private Maintenance.Task task;
    private long nextLook;
    private int working, repath;
    private double best;
    private int bestSince;

    public MaintainerGoal(MaintainerEntity w) {
        this.w = w;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean awake() {
        return w.shouldWork() && !w.needsToSleep() && !w.isFleeing;
    }

    @Nullable
    private static VillageData.Village village(ServerLevel level, MaintainerEntity w) {
        BlockPos c = w.getTown();
        VillageData.Village v = c == null ? null : VillageData.get(level).at(c);
        return v != null && com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), v.owner()) ? v : null;
    }

    @Override
    public boolean canUse() {
        if (!(w.level() instanceof ServerLevel level) || !awake() || level.getGameTime() < nextLook) return false;
        nextLook = level.getGameTime() + IDLE_TICKS;
        VillageData.Village v = village(level, w);
        TownData.Town t = v == null ? null : TownData.get(level).at(v.center());
        if (!Maintenance.hasShed(t)) {
            w.doing = "";
            w.missing = "sin caseta de mantenimiento";
            return false;
        }
        task = Maintenance.next(level, v, t);
        if (task == null) {
            w.doing = "nada que embellecer";
            return false;
        }
        w.doing = task.what();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return task != null && awake();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        working = 0;
        best = Double.MAX_VALUE;
        bestSince = 0;
        repath = 0;
    }

    @Override
    public void stop() {
        w.getNavigation().stop();
        task = null;
    }

    @Override
    public void tick() {
        if (!(w.level() instanceof ServerLevel level) || task == null) return;
        BlockPos at = task.stand();
        if (!arrived(level, at)) return;
        w.getLookControl().setLookAt(Vec3.atCenterOf(at));
        if (working++ % 10 == 0) w.swing(InteractionHand.MAIN_HAND);
        if (working < WORK_TICKS) return;
        VillageData.Village v = village(level, w);
        TownData.Town t = v == null ? null : TownData.get(level).at(v.center());
        String lack = v == null || t == null ? "sin aldea" : Maintenance.apply(level, v, t, task);
        w.missing = lack == null ? "" : lack;
        // hecha: la próxima enseguida; si faltó algo, espera
        if (lack == null) nextLook = level.getGameTime() + 20;
        task = null;
    }

    /** Camina hasta el lugar. True = ya llegó. */
    private boolean arrived(ServerLevel level, BlockPos pos) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        double d = w.position().distanceTo(c);
        if (d <= REACH) {
            w.getNavigation().stop();
            return true;
        }
        if (d < best - 1) {
            best = d;
            bestSince = 0;
        } else if (++bestSince > STUCK_TICKS) {   // trabado: aparece al lado
            w.teleportTo(c.x, c.y, c.z);
            w.getNavigation().stop();
            return true;
        }
        if (--repath <= 0) {
            repath = 20;
            Path path = w.getNavigation().createPath(pos, 1);
            if (path != null) w.getNavigation().moveTo(path, 0.6);
            else w.getNavigation().moveTo(c.x, c.y, c.z, 0.6);
        }
        return false;
    }

    /** Para las pruebas: hace hasta {@code max} tareas en el acto (sin caminar). Devuelve cuántas hizo. */
    public static int runNow(ServerLevel level, MaintainerEntity w, int max) {
        VillageData.Village v = village(level, w);
        TownData.Town t = v == null ? null : TownData.get(level).at(v.center());
        if (!Maintenance.hasShed(t)) return 0;
        int n = 0;
        for (; n < max; n++) {
            Maintenance.Task task = Maintenance.next(level, v, t);
            if (task == null) break;
            String lack = Maintenance.apply(level, v, t, task);
            w.missing = lack == null ? "" : lack;
            if (lack != null) break;
        }
        return n;
    }
}
