package com.ale.warbandcraft.nav;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * El objetivo de máxima prioridad que se le suma a cada soldado de Recruits: mientras tiene un camino de la malla
 * ({@link Mover}), se mueve por él y ninguna otra IA de movimiento lo toca.
 */
public class RouteGoal extends Goal {
    private final AbstractRecruitEntity soldier;

    public RouteGoal(AbstractRecruitEntity soldier) {
        this.soldier = soldier;
        // Sin JUMP: Minecraft apaga los objetivos con JUMP en un bote, y en el bote también hay que remar
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Mover.Run r = Mover.run(soldier);
        return r != null && r.active() && soldier.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        Mover.Run r = Mover.run(soldier);
        if (r != null) Mover.tick(soldier, r);
    }
}
