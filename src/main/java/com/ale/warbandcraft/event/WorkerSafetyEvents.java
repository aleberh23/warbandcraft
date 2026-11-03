package com.ale.warbandcraft.event;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.village.InsideWalls;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pedido de Ale: los trabajadores que andan afuera de la aldea (el leñador en el bosque, el puesto de afuera, la mina
 * lejos) no son objetivo de bichos ni de soldados de otras facciones: nadie que no sea un jugador los elige ni los
 * lastima. Adentro de la aldea, los defiende la guarnición como siempre.
 */
public class WorkerSafetyEvents {
    /** Cada cuánto se vuelve a mirar si un trabajador está afuera (ticks). */
    private static final int RECHECK = 100;
    /** Hasta dónde se busca su aldea. */
    private static final double VILLAGE_SEARCH = 256;

    private static final Map<UUID, long[]> CACHE = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewTarget();
        if (target == null || event.getEntity().level().isClientSide() || event.getEntity() instanceof Player) return;
        if (protectedWorker(target)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAttack(LivingAttackEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        Entity by = event.getSource().getEntity();
        if (!(by instanceof Mob) || by instanceof Player) return;   // un jugador sí puede (y lo que no tiene autor: caídas, fuego)
        if (protectedWorker(victim)) {
            event.setCanceled(true);
            if (by instanceof Mob m && m.getTarget() == victim) m.setTarget(null);
        }
    }

    /** Un trabajador con dueño que está afuera de su aldea (o lejos de toda aldea suya). */
    public static boolean protectedWorker(LivingEntity e) {
        if (!(e instanceof AbstractRecruitEntity w) || !Soldiers.isWorker(w) || w.getOwnerUUID() == null) return false;
        if (!(w.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        long[] c = CACHE.get(w.getUUID());
        if (c != null && now >= c[0] && now - c[0] < RECHECK) return c[1] != 0;
        boolean out = outside(level, w);
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(w.getUUID(), new long[]{now, out ? 1 : 0});
        return out;
    }

    private static boolean outside(ServerLevel level, AbstractRecruitEntity w) {
        VillageData.Village best = null;
        double bestD = VILLAGE_SEARCH * VILLAGE_SEARCH;
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (!com.ale.warbandcraft.faction.Allies.same(v.owner(), w.getOwnerUUID())) continue;
            double d = VillageData.horizontalDistSq(w.blockPosition(), v.center());
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best == null || !InsideWalls.inside(level, best, w.blockPosition());
    }
}
