package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Rendirse (H9): durante un ataque, un soldado que recibe un golpe mortal puede quedar rendido en vez de
 * morir. Rendido = tirado en el piso sangrando, quieto, con poca vida, invulnerable y sin pelear. Al terminar el ataque el
 * ganador se lo lleva como prisionero (ver {@link RaidManager}) o se levanta.
 */
public final class Surrender {
    /** En el soldado: está rendido en el ataque a la aldea con este centro (long). */
    public static final String TAG = "warbandcraft:surrendered";
    /** En el soldado: estado de agresividad de Recruits que tenía antes de rendirse. */
    private static final String TAG_AGGRO = "warbandcraft:surrender_aggro";
    /** Solo en las pruebas: la chance de rendirse de este soldado (fuera de las pruebas no se usa). */
    public static final String TAG_TEST_CHANCE = "warbandcraft:surrender_test";

    /** Chance de rendirse de un soldado de nivel 0... */
    public static double BASE_CHANCE = 0.45;
    /** ...menos esto por cada nivel... */
    public static double CHANCE_PER_LEVEL = 0.07;
    /** ...pero nunca menos que esto. */
    public static double MIN_CHANCE = 0.10;
    /** Con qué parte de la vida queda (y mínimo 2 puntos). */
    private static float HEALTH_LEFT = 0.2F;

    /** Color de la sangre (rojo oscuro) y tamaño de cada gota. */
    private static final DustParticleOptions BLOOD = new DustParticleOptions(new Vector3f(0.45F, 0.0F, 0.0F), 1.3F);

    private static final UUID FROZEN_ID = UUID.fromString("3c8c5f0e-7f5d-4a8e-9a43-6d0b1f2a9c11");
    private static final int PASSIVE = 3, WANDER = 0, FOLLOW_OWNER = 1, BACK_TO_POS = 3;

    private Surrender() {}

    public static boolean is(Entity entity) {
        return entity.getPersistentData().contains(TAG);
    }

    /** Centro del ataque en el que se rindió (o null). */
    @Nullable
    public static BlockPos raidCenter(Entity entity) {
        return is(entity) ? BlockPos.of(entity.getPersistentData().getLong(TAG)) : null;
    }

    public static double chance(AbstractRecruitEntity soldier) {
        return Math.max(MIN_CHANCE, BASE_CHANCE - CHANCE_PER_LEVEL * soldier.getXpLevel());
    }

    /** El ataque en curso en el que pelea este soldado: el de su aldea (guarnición) o uno de su dueño cerca. */
    @Nullable
    public static RaidManager.Raid raidOf(ServerLevel level, AbstractRecruitEntity soldier) {
        CompoundTag data = soldier.getPersistentData();
        if (VillageManager.isGarrison(soldier) && data.contains(VillageManager.TAG_VILLAGE)) {
            RaidManager.Raid raid = RaidManager.get(level, BlockPos.of(data.getLong(VillageManager.TAG_VILLAGE)));
            return raid != null && raid.state == RaidManager.State.FIGHTING ? raid : null;
        }
        UUID owner = soldier.getOwnerUUID();
        if (owner == null || !soldier.getIsOwned()) return null;
        for (RaidManager.Raid raid : RaidManager.all()) {
            if (raid.state != RaidManager.State.FIGHTING || raid.dimension != level.dimension() || !raid.attacker.equals(owner)) continue;
            if (VillageData.horizontalDistSq(soldier.blockPosition(), raid.center) <= RaidManager.REINFORCE_RANGE * RaidManager.REINFORCE_RANGE) {
                return raid;
            }
        }
        return null;
    }

    /**
     * Un golpe de {@code damage} le llega al soldado. Si lo mataría y está en un ataque, tira el dado: si sale,
     * se rinde y devuelve true (el golpe se cancela).
     */
    public static boolean tryOnLethalHit(ServerLevel level, AbstractRecruitEntity soldier, float damage) {
        if (is(soldier) || damage < soldier.getHealth()) return false;
        if (soldier.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)) return false;
        double chance;
        if (level.getServer() instanceof GameTestServer) {
            // En las pruebas solo se rinde quien la prueba elige (las de H3 necesitan que mueran todos)
            if (!soldier.getPersistentData().contains(TAG_TEST_CHANCE)) return false;
            chance = soldier.getPersistentData().getDouble(TAG_TEST_CHANCE);
        } else {
            chance = chance(soldier);
        }
        RaidManager.Raid raid = raidOf(level, soldier);
        if (raid == null || level.getRandom().nextDouble() >= chance) return false;
        surrender(soldier, raid);
        return true;
    }

    /** Lo rinde en el ataque {@code raid}. */
    public static void surrender(AbstractRecruitEntity soldier, RaidManager.Raid raid) {
        CompoundTag data = soldier.getPersistentData();
        data.putLong(TAG, raid.center.asLong());
        data.putInt(TAG_AGGRO, soldier.getState());
        soldier.setHealth(Math.max(2.0F, soldier.getMaxHealth() * HEALTH_LEFT));
        if (soldier.isPassenger()) soldier.stopRiding();
        raid.surrendered.add(soldier.getUUID());
        RaidManager.changed();
        freeze(soldier);
        if (soldier.level() instanceof ServerLevel level) {
            // Salpicón al caer
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
                    soldier.getX(), soldier.getY() + 0.3, soldier.getZ(), 30, 0.4, 0.2, 0.4, 0.1);
            bleed(level, soldier);
        }
    }

    /** Charco de sangre alrededor del rendido (se llama cada segundo). */
    public static void bleed(ServerLevel level, Entity soldier) {
        for (int i = 0; i < 6; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2;
            double dist = level.getRandom().nextDouble() * 1.1;
            level.sendParticles(BLOOD, soldier.getX() + Math.cos(angle) * dist, soldier.getY() + 0.05,
                    soldier.getZ() + Math.sin(angle) * dist, 1, 0, 0, 0, 0);
        }
    }

    /** Lo deja quieto y fuera de la pelea (al rendirse y cada vez que se carga). */
    public static void freeze(AbstractRecruitEntity soldier) {
        soldier.setTarget(null);
        soldier.setLastHurtByMob(null);
        soldier.setAggroState(PASSIVE);
        soldier.setShouldBlock(false);
        soldier.setHoldPos(soldier.position());
        soldier.setFollowState(BACK_TO_POS);
        soldier.setInvulnerable(true);
        soldier.getNavigation().stop();
        soldier.setPose(Pose.SLEEPING);        // tirado en el piso (sin cama: se acuesta mirando a donde miraba)
        AttributeInstance speed = soldier.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(FROZEN_ID) == null) {
            speed.addTransientModifier(new AttributeModifier(FROZEN_ID, "warbandcraft surrendered", -1.0,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    /** Se levanta: vuelve a pelear para su bando (el suyo de antes, o el que lo capturó). */
    public static void standUp(AbstractRecruitEntity soldier) {
        CompoundTag data = soldier.getPersistentData();
        int aggro = data.contains(TAG_AGGRO) ? data.getInt(TAG_AGGRO) : 0;
        data.remove(TAG);
        data.remove(TAG_AGGRO);
        AttributeInstance speed = soldier.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(FROZEN_ID);
        soldier.setInvulnerable(false);
        soldier.setPose(Pose.STANDING);
        soldier.setAggroState(aggro == PASSIVE ? 0 : aggro);
        soldier.setTarget(null);
        soldier.setFollowState(soldier.getIsOwned() ? FOLLOW_OWNER : WANDER);
    }
}
