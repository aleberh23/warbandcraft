package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** H24: de día el Mercader va a su puesto del mercado y se queda atendiendo. Si el mercado se hizo después, se muda solo. */
public class MerchantGoal extends Goal {
    private static double ARRIVE = 2.0;

    private final MerchantEntity merchant;
    private int repath;
    private int relocate;

    public MerchantGoal(MerchantEntity merchant) {
        this.merchant = merchant;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(merchant.level() instanceof ServerLevel level)) return false;
        if (++relocate >= 200) {
            relocate = 0;
            if (merchant.getCamp() != null) {
                VillageData.Village v = VillageCamps.villageOf(level, merchant.getCamp());
                BlockPos place = v == null ? null : com.ale.warbandcraft.market.Market.workplace(level, v);
                if (place != null) merchant.setWorkplace(place);
            }
        }
        return merchant.getWorkplace() != null && (merchant.shouldWork() || com.ale.warbandcraft.market.FactionMarket.is(merchant)) && !merchant.needsToSleep() && !merchant.isFleeing;
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
        merchant.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos place = merchant.getWorkplace();
        if (place == null) return;
        Vec3 spot = Vec3.atBottomCenterOf(place);
        if (merchant.position().distanceToSqr(spot) > ARRIVE * ARRIVE) {
            if (--repath <= 0) {
                repath = 20;
                merchant.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.6);
            }
            return;
        }
        merchant.getNavigation().stop();
    }
}
