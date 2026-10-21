package com.ale.warbandcraft.village;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.invasion.AiWars;
import java.util.UUID;

/**
 * Las guarniciones defienden su aldea de los illagers (pillager, vindicator, evoker, bruja, ravager...):
 * en cuanto un soldado ve uno, va a pegarle, y avisa a sus compañeros de la misma aldea.
 * También atacan a los jugadores enemigos y a sus tropas (H3): quien está atacando la aldea, o quien
 * está en guerra con su facción.
 */
public final class GarrisonDefense {
    /** Hasta dónde ve un soldado a un illager. */
    public static double SIGHT_RANGE = 40;
    /** A qué distancia del soldado que lo vio se avisa a los compañeros. */
    public static double ALERT_RANGE = 48;

    private static boolean alerting;

    private GarrisonDefense() {}

    /** El objetivo "illager más cercano", con prioridad máxima sobre los demás objetivos del soldado. */
    public static class RaiderTargetGoal extends NearestAttackableTargetGoal<Raider> {
        public RaiderTargetGoal(AbstractRecruitEntity soldier) {
            super(soldier, Raider.class, 5, true, false, LivingEntity::isAlive);
        }

        @Override
        protected double getFollowDistance() {
            return Math.max(SIGHT_RANGE, super.getFollowDistance());
        }
    }

    /** Jugadores enemigos (atacando la aldea o en guerra con la facción). */
    public static class EnemyPlayerGoal extends NearestAttackableTargetGoal<Player> {
        public EnemyPlayerGoal(AbstractRecruitEntity soldier) {
            super(soldier, Player.class, 10, true, false, p -> isHostile(soldier, p));
        }

        @Override
        protected double getFollowDistance() {
            return Math.max(SIGHT_RANGE, super.getFollowDistance());
        }
    }

    /** Soldados de jugadores enemigos. */
    public static class EnemyTroopsGoal extends NearestAttackableTargetGoal<AbstractRecruitEntity> {
        public EnemyTroopsGoal(AbstractRecruitEntity soldier) {
            super(soldier, AbstractRecruitEntity.class, 10, true, false, r -> r != soldier && isHostile(soldier, r));
        }

        @Override
        protected double getFollowDistance() {
            return Math.max(SIGHT_RANGE, super.getFollowDistance());
        }
    }

    /** Le agrega los objetivos al soldado (una sola vez). Se llama al aparecer y cada vez que se carga. */
    public static void install(AbstractRecruitEntity soldier) {
        for (WrappedGoal g : soldier.targetSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof RaiderTargetGoal) return;
        }
        soldier.targetSelector.addGoal(0, new RaiderTargetGoal(soldier));
        soldier.targetSelector.addGoal(1, new EnemyPlayerGoal(soldier));
        soldier.targetSelector.addGoal(2, new EnemyTroopsGoal(soldier));
        soldier.targetSelector.addGoal(2, new EnemyFactionGoal(soldier));
    }

    /** H15b: en una batalla entre facciones, los soldados del otro bando. */
    public static class EnemyFactionGoal extends NearestAttackableTargetGoal<AbstractRecruitEntity> {
        public EnemyFactionGoal(AbstractRecruitEntity soldier) {
            super(soldier, AbstractRecruitEntity.class, 10, true, false, r -> r != soldier && AiWars.enemies(soldier, r));
        }

        @Override
        protected double getFollowDistance() {
            return Math.max(SIGHT_RANGE, super.getFollowDistance());
        }
    }

    /**
     * ¿Este soldado de guarnición debe atacar a {@code target}? Sí si es un jugador (o un soldado de un jugador)
     * que está atacando su aldea, o cuya facción está en guerra con él.
     */
    public static boolean isHostile(AbstractRecruitEntity soldier, LivingEntity target) {
        if (!(soldier.level() instanceof ServerLevel level) || !VillageManager.isGarrison(soldier)) return false;
        UUID enemy = null;
        if (target instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            enemy = p.getUUID();
        } else if (target instanceof AbstractRecruitEntity r && r.getIsOwned()) {
            enemy = r.getOwnerUUID();
        }
        if (enemy == null) return false;

        if (AiWars.isFoe(level, enemy, soldier)) return true;   // H23: se metió en esta batalla contra nosotros
        BlockPos center = BlockPos.of(soldier.getPersistentData().getLong(VillageManager.TAG_VILLAGE));
        RaidManager.Raid raid = RaidManager.get(level, center);
        if (raid != null && raid.state == RaidManager.State.FIGHTING && raid.attacker.equals(enemy)) return true;

        ResourceLocation faction = ResourceLocation.tryParse(soldier.getPersistentData().getString(VillageManager.TAG_FACTION));
        return faction != null && Relations.get(level.getServer()).atWar(enemy, faction);
    }

    /** Un soldado de guarnición eligió como objetivo a un illager: sus compañeros cercanos sin pelea van también. */
    public static void alertComrades(AbstractRecruitEntity soldier, Raider raider) {
        if (alerting || !(soldier.level() instanceof ServerLevel level)) return;
        if (!soldier.getPersistentData().contains(VillageManager.TAG_VILLAGE)) return;
        BlockPos center = BlockPos.of(soldier.getPersistentData().getLong(VillageManager.TAG_VILLAGE));
        alerting = true;
        try {
            for (AbstractRecruitEntity mate : VillageManager.garrison(level, center)) {
                if (mate == soldier || !VillageManager.isGarrison(mate)) continue;
                if (mate.distanceToSqr(soldier) > ALERT_RANGE * ALERT_RANGE) continue;
                LivingEntity current = mate.getTarget();
                if (current != null && current.isAlive()) continue;
                mate.setTarget(raider);
            }
        } finally {
            alerting = false;
        }
    }
}
