package com.ale.warbandcraft.sapper;

import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * IA extra del Zapador (H6), sin tocar a Recruits: se le suma a cada Zapador al aparecer.
 *
 * - Volar ({@link SapperRules#TAG_BLAST}): camina hasta el bloque, deja un TNT encendido del lado de
 *   donde vino y sale corriendo.
 * - Armar cañón ({@link SapperRules#TAG_DEPLOY}): camina hasta el lugar, cobra los materiales y arma
 *   un {@link FieldCannonEntity}; después se queda al lado para cargarlo.
 *
 * Para caminar usa la misma "posición a mantener" de Recruits (setHoldPos + estado 3, como las órdenes
 * de la vista aérea): así su propia IA de movimiento lo lleva y no se pelean.
 */
public class SapperGoal extends Goal {
    private static final int FOLLOW_HOLD = 3;
    private static int GIVE_UP_TICKS = 20 * 30;
    private static int FLEE_TICKS = 20 * 3;
    private static double REACH = 3.0;

    private enum Phase { IDLE, GOING, FLEEING }

    private final AbstractRecruitEntity sapper;
    private Phase phase = Phase.IDLE;
    private boolean deploy;
    private BlockPos target;
    private int ticks;

    public SapperGoal(AbstractRecruitEntity sapper) {
        this.sapper = sapper;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        CompoundTag data = sapper.getPersistentData();
        return sapper.isAlive() && (data.contains(SapperRules.TAG_BLAST) || data.contains(SapperRules.TAG_DEPLOY));
    }

    @Override
    public boolean canContinueToUse() {
        return sapper.isAlive() && phase != Phase.IDLE;
    }

    @Override
    public void start() {
        CompoundTag data = sapper.getPersistentData();
        deploy = data.contains(SapperRules.TAG_DEPLOY);
        target = BlockPos.of(data.getLong(deploy ? SapperRules.TAG_DEPLOY : SapperRules.TAG_BLAST));
        phase = Phase.GOING;
        ticks = 0;
        walkTo(Vec3.atBottomCenterOf(target));
    }

    @Override
    public void stop() {
        phase = Phase.IDLE;
        sapper.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(sapper.level() instanceof ServerLevel level)) return;
        ticks++;
        Vec3 center = Vec3.atCenterOf(target);
        if (phase == Phase.GOING) {
            sapper.getLookControl().setLookAt(center.x, center.y, center.z);
            if (ticks % 20 == 0) sapper.getNavigation().moveTo(center.x, target.getY() + 1, center.z, 1.1);
            if (sapper.position().distanceTo(center) <= REACH) {
                if (deploy) deployCannon(level);
                else plantTnt(level);
            } else if (ticks > GIVE_UP_TICKS) {
                SapperRules.tell(level, sapper.getOwnerUUID(), Component.translatable("msg.warbandcraft.sapper.cant_reach"));
                finish();
            }
        } else if (phase == Phase.FLEEING && ticks > FLEE_TICKS) {
            finish();
        }
    }

    private void plantTnt(ServerLevel level) {
        UUID owner = sapper.getOwnerUUID();
        VillageData.Village village = owner == null ? null : SapperRules.enemyTarget(level, target, owner);
        if (village == null) {
            SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.not_enemy"));
            finish();
            return;
        }
        if (!SapperRules.takeTnt(sapper)) {
            SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.no_tnt"));
            finish();
            return;
        }
        // El TNT va pegado al bloque, del lado del Zapador (si ese lado está libre).
        Direction side = Direction.getNearest(sapper.getX() - (target.getX() + 0.5), 0, sapper.getZ() - (target.getZ() + 0.5));
        BlockPos spot = target.relative(side);
        if (!level.getBlockState(spot).canBeReplaced()) spot = sapper.blockPosition();
        Doctrine doctrine = SapperRules.doctrineOf(level, owner);
        SapperRules.spawnTnt(level, Vec3.atBottomCenterOf(spot), Vec3.ZERO, Doctrine.fuseTicks(doctrine), sapper, doctrine);
        SapperRules.startRaid(level, village, owner);
        SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.fire_in_the_hole"));

        // A correr: unos 10 bloques en la dirección contraria al TNT.
        Vec3 away = sapper.position().subtract(Vec3.atCenterOf(target)).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        walkTo(sapper.position().add(away.normalize().scale(10)));
        phase = Phase.FLEEING;
        ticks = 0;
    }

    private void deployCannon(ServerLevel level) {
        UUID owner = sapper.getOwnerUUID();
        if (owner == null || !SapperRules.payCannon(level, sapper, owner)) {
            SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.cannon_cost"));
            finish();
            return;
        }
        FieldCannonEntity cannon = ModEntities.FIELD_CANNON.get().create(level);
        if (cannon == null) {
            finish();
            return;
        }
        cannon.moveTo(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, sapper.getYRot(), 0);
        cannon.setOwnerAndSapper(owner, sapper.getUUID());
        level.addFreshEntity(cannon);
        SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.cannon_ready"));
        // Se queda al lado del cañón para cargarlo.
        Vec3 offset = sapper.position().subtract(cannon.position()).multiply(1, 0, 1);
        if (offset.lengthSqr() < 0.01) offset = new Vec3(1, 0, 0);
        walkTo(cannon.position().add(offset.normalize().scale(1.5)));
        finish();
    }

    private void walkTo(Vec3 pos) {
        sapper.setHoldPos(pos);
        sapper.setFollowState(FOLLOW_HOLD);
        sapper.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.1);
    }

    private void finish() {
        CompoundTag data = sapper.getPersistentData();
        data.remove(SapperRules.TAG_BLAST);
        data.remove(SapperRules.TAG_DEPLOY);
        phase = Phase.IDLE;
    }
}
