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

    /**
     * Del mismo bando que {@code soldier} (con dueño): un soldado o un jugador de su dueño o de su facción de Recruits
     * (pedido de Ale: después de una pelea se mataban entre ellos). Sin dueño (guarniciones de la IA), nunca.
     */
    public static boolean friendly(com.talhanation.recruits.entities.AbstractRecruitEntity soldier, @org.jetbrains.annotations.Nullable Entity other) {
        java.util.UUID owner = soldier.getOwnerUUID();
        if (owner == null || other == null || other == soldier) return false;
        if (other instanceof net.minecraft.world.entity.player.Player p) return com.ale.warbandcraft.faction.Allies.same(owner, p.getUUID());
        if (other instanceof com.talhanation.recruits.entities.AbstractRecruitEntity r) {
            return r.getOwnerUUID() != null && com.ale.warbandcraft.faction.Allies.same(owner, r.getOwnerUUID());
        }
        return false;
    }

    /** Lo contrario: un soldado de verdad (cualquier entidad que no sea un trabajador). */
    public static boolean isSoldier(Entity entity) {
        return !isWorker(entity);
    }
}
