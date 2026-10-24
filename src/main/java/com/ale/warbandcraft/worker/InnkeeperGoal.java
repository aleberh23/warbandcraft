package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.village.Tavern;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** H19: de día el Tabernero va a la taberna y se queda atendiendo. Si la taberna se hizo después, se muda solo. */
public class InnkeeperGoal extends Goal {
    private static double ARRIVE = 2.0;

    private final InnkeeperEntity keeper;
    private int repath;
    private int relocate;

    public InnkeeperGoal(InnkeeperEntity keeper) {
        this.keeper = keeper;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(keeper.level() instanceof ServerLevel level)) return false;
        if (++relocate >= 200) {
            relocate = 0;
            if (keeper.getCamp() != null) {
                VillageData.Village v = VillageCamps.villageOf(level, keeper.getCamp());
                BlockPos place = v == null ? null : Tavern.keeperSpot(level, v);
                if (place != null) keeper.setWorkplace(place);
            }
        }
        return keeper.getWorkplace() != null && level.isDay() && !keeper.isFleeing;
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
    public void stop() {
        keeper.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos place = keeper.getWorkplace();
        if (place == null) return;
        Vec3 spot = Vec3.atBottomCenterOf(place);
        if (keeper.position().distanceToSqr(spot) > ARRIVE * ARRIVE) {
            if (--repath <= 0) {
                repath = 20;
                keeper.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.6);
            }
            return;
        }
        keeper.getNavigation().stop();
    }
}
