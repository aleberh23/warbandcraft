package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Map;

/**
 * H25: que los mods de mazmorras (YUNG's Better Dungeons, L_Ender's Cataclysm, Dungeon Now Loading) no choquen con
 * Warband Craft. No llama nada de su código: mira las estructuras del mundo y las etiquetas de entidades.
 * <ul>
 *   <li>{@link #foreignStructureAt}: una pieza de una estructura que no es aldea (campanas de mazmorras, castillos,
 *       campos de batalla, construir).</li>
 *   <li>{@link #surfaceStructureIn}: lo mismo en un rectángulo, mirando la superficie cada {@link #SAMPLE_STEP}.</li>
 *   <li>{@link #isBoss}: jefes (etiqueta {@code warbandcraft:no_auto_target}, que incluye {@code forge:bosses}): tus
 *       soldados no los eligen solos.</li>
 * </ul>
 */
public final class Compat {
    /** Cada cuántos bloques se mira la superficie al buscar estructuras en un rectángulo. */
    public static int SAMPLE_STEP = 8;

    /** Jefes que tus soldados no eligen solos (si te pegan a vos o a ellos, sí). */
    public static final TagKey<EntityType<?>> NO_AUTO_TARGET =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(WarbandCraft.MODID, "no_auto_target"));

    private Compat() {}

    /** ¿Hay en {@code pos} una pieza de una estructura que no es una aldea? Nunca carga chunks. */
    public static boolean foreignStructureAt(ServerLevel level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> all;
        try {
            all = level.structureManager().getAllStructuresAt(pos);
        } catch (Exception e) {
            return false;
        }
        if (all.isEmpty()) return false;
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure s : all.keySet()) {
            if (reg.wrapAsHolder(s).is(StructureTags.VILLAGE)) continue;
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, s);
            if (start.isValid()) return true;
        }
        return false;
    }

    /**
     * ¿Hay alguna estructura (que no sea aldea) en la superficie del rectángulo x0..x1, z0..z1? Mira el piso y 3 bloques
     * arriba cada {@link #SAMPLE_STEP}. Las mazmorras de abajo no cuentan: lo de arriba no las toca.
     */
    public static boolean surfaceStructureIn(ServerLevel level, int x0, int z0, int x1, int z1) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        for (int x = ax; x <= bx + SAMPLE_STEP - 1; x += SAMPLE_STEP) {
            for (int z = az; z <= bz + SAMPLE_STEP - 1; z += SAMPLE_STEP) {
                int cx = Math.min(x, bx), cz = Math.min(z, bz);
                if (!level.hasChunk(cx >> 4, cz >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
                if (foreignStructureAt(level, new BlockPos(cx, y - 1, cz)) || foreignStructureAt(level, new BlockPos(cx, y + 2, cz))) return true;
            }
        }
        return false;
    }

    /** ¿Es un jefe (de Cataclysm, Dungeon Now Loading, el Wither...)? */
    public static boolean isBoss(Entity e) {
        return e.getType().is(NO_AUTO_TARGET);
    }
}
