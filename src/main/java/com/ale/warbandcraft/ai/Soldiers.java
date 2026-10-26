package com.ale.warbandcraft.ai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Quién cuenta como soldado (H10). Los trabajadores de Villager Workers son, por dentro, soldados de
 * Recruits (heredan de su arquero), así que todo lo nuestro que recorre "tus soldados" los tiene que
 * saltear: barracón, Batalla, vista aérea, destrabador, Bastón, kits y doctrinas.
 */
public final class Soldiers {
    /** Namespace de las entidades de Villager Workers. */
    public static final String WORKERS_MOD = "workers";

    private Soldiers() {}

    /** Un trabajador de Villager Workers (se reconoce por el mod de su tipo, sin tocar clases de Workers). */
    public static boolean isWorker(Entity entity) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (entity instanceof com.talhanation.workers.entities.AbstractWorkerEntity) return true;   // H16: los nuestros (Herrero, Mantenimiento...)
        return id != null && WORKERS_MOD.equals(id.getNamespace());
    }

    /** Lo contrario: un soldado de verdad (cualquier entidad que no sea un trabajador). */
    public static boolean isSoldier(Entity entity) {
        return !isWorker(entity);
    }
}
