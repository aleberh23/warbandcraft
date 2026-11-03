package com.ale.warbandcraft.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/**
 * Pedido de Ale: en una aldea con muralla (tuya o de facción), los aldeanos se quedan adentro. Cada 2 s, el que está
 * afuera camina de vuelta (se le da un destino adentro, como hace vanilla al pasear); si su cama está afuera, la suelta y
 * busca otra adentro. Los trabajadores de Villager Workers no: van a sus zonas (las de afuera las protege
 * {@link com.ale.warbandcraft.event.WorkerSafetyEvents}).
 */
public final class VillagersInside {
    /** Hasta cuánto afuera de la muralla se busca a los aldeanos de la aldea. */
    public static int SEARCH_MARGIN = 40;
    /** Cuánto antes de la muralla queda el destino adentro. */
    public static double BACK = 4;

    private VillagersInside() {}

    public static void tick(ServerLevel level) {
        VillageData data = VillageData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            BlockPos c = v.center();
            if (v.castle() || data.isTest(c) || !level.isPositionEntityTicking(c) || !InsideWalls.walled(level, v)) continue;
            int r = (int) Math.min(160, InsideWalls.radiusToward(level, v, Vec3.atBottomCenterOf(c).add(1, 0, 0)) + SEARCH_MARGIN + 16);
            for (Villager villager : level.getEntitiesOfClass(Villager.class, new AABB(c).inflate(r, 32, r), x -> x.isAlive())) {
                keepIn(level, v, villager);
            }
        }
    }

    /** Si está afuera, vuelve adentro caminando. Devuelve true si se lo mandó. */
    public static boolean keepIn(ServerLevel level, VillageData.Village v, Villager villager) {
        if (villager.isSleeping() || villager.isPassenger() || villager.getPersistentData().contains(com.ale.warbandcraft.camp.CampWorkers.TAG)) return false;
        if (InsideWalls.inside(level, v, villager.blockPosition())) return false;
        VillageData.Village nearest = VillageData.get(level).nearest(villager.blockPosition(), 256);
        if (nearest != null && !nearest.center().equals(v.center())) return false;   // es de otra aldea
        // Cama afuera de la muralla: la suelta (vanilla le busca otra)
        villager.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home -> {
            if (home.dimension() == level.dimension() && !InsideWalls.inside(level, v, home.pos())) {
                level.getPoiManager().release(home.pos());
                villager.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
        });
        Vec3 to = InsideWalls.pointInside(level, v, villager.position(), BACK);
        Integer y = com.ale.warbandcraft.util.Ground.surfaceY(level, (int) Math.floor(to.x), (int) Math.floor(to.z), v.center().getY(), 16, 16);
        BlockPos target = BlockPos.containing(to.x, y != null ? y : v.center().getY(), to.z);
        villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.6F, 1));
        return true;
    }
}
