package com.ale.warbandcraft.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Un paso de un camino: a qué lugar (los pies) y cómo se llega. {@code wall}: el bloque del muro para {@link Kind#CLIMB}
 * (donde va la escalera) y {@link Kind#BREAK} (lo que hay que romper).
 */
public record Step(long pos, Kind kind, long wall) {
    public enum Kind {
        /** El primer lugar (donde está parado). */
        START,
        WALK,
        /** Abrir una puerta o tranquera al pasar. */
        DOOR,
        /** Un peldaño de una escalera que ya está puesta. */
        LADDER,
        /** Agua: se cruza en bote. */
        WATER,
        /** Trepar el muro con una escalera de mano que hay que poner. */
        CLIMB,
        /** Romper el muro (TNT, cañón). */
        BREAK
    }

    public BlockPos blockPos() {
        return BlockPos.of(pos);
    }

    public Vec3 center() {
        return Vec3.atBottomCenterOf(BlockPos.of(pos));
    }
}
