package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.village.Territory;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.WorkZones;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * H22: si un edificio se puede plantar donde lo elegiste en el modo Gestión (lo mismo que se ve en rojo en la vista
 * previa) y a qué altura queda su piso. El terreno desparejo se allana solo (el plano del edificio ya trae la tierra
 * que falta y saca lo que sobra), hasta {@link #MAX_LEVEL} de desnivel.
 */
public final class Placement {
    /** Desnivel máximo del terreno que se allana solo. */
    public static int MAX_LEVEL = 8;
    /** Cuánto arriba del piso tiene que estar libre (de casas y cosas con datos). */
    public static int CLEAR_ABOVE = 12;

    private Placement() {}

    /** El terreno (esquina mínima, y = piso) o el motivo por el que no sirve. */
    public record Spot(@Nullable BlockPos site, @Nullable String problem) {
        static Spot no(String why) {
            return new Spot(null, why);
        }
    }

    /** {@link #check} para un terreno de {@link Blueprints#SITE} (una casa). */
    public static Spot check(ServerLevel level, VillageData.Village v, @Nullable TownData.Town town, BlockPos corner, String key) {
        return check(level, v, town, corner, key, Blueprints.SITE);
    }

    /**
     * Revisa el terreno de {@code size}×{@code size} con esquina {@code corner} para la aldea {@code v}; {@code key} = la
     * clave de su terreno (para no chocar consigo mismo).
     */
    public static Spot check(ServerLevel level, VillageData.Village v, @Nullable TownData.Town town, BlockPos corner, String key, int size) {
        int x0 = corner.getX(), z0 = corner.getZ(), x1 = x0 + size - 1, z1 = z0 + size - 1;
        if (!inside(level, v, x0, z0) || !inside(level, v, x1, z0) || !inside(level, v, x0, z1) || !inside(level, v, x1, z1)) {
            return Spot.no("Afuera del territorio");
        }
        if (town != null) {
            if (Blueprints.overlaps(new BlockPos(x0, 0, z0), size, town.lots(key))) return Spot.no("Se pisa con otro edificio");
            for (long l : town.defense.keySet()) {   // H22c: la muralla, portones y torres dibujados
                BlockPos d = BlockPos.of(l);
                if (d.getX() >= x0 && d.getX() <= x1 && d.getZ() >= z0 && d.getZ() <= z1) return Spot.no("Pisa la muralla o una torre");
            }
        }
        if (v.owner() != null) {   // las zonas de trabajo (huerta, mina, corral...) no se pisan
            net.minecraft.world.phys.AABB foot = new net.minecraft.world.phys.AABB(x0, level.getMinBuildHeight(), z0, x1 + 1, level.getMaxBuildHeight(), z1 + 1);
            boolean zone = !level.getEntitiesOfClass(com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity.class, foot.inflate(WorkZones.MAX_SIDE),
                    a -> a.isAlive() && v.owner().equals(a.getPlayerUUID()) && a.getArea() != null
                            && !(a instanceof com.talhanation.workers.entities.workarea.HomeArea) && a.getArea().intersects(foot)).isEmpty();
            if (zone) return Spot.no("Pisa una zona de trabajo");
        }
        if (com.ale.warbandcraft.dungeon.Compat.surfaceStructureIn(level, x0, z0, x1, z1)
                || com.ale.warbandcraft.dungeon.DungeonData.get(level).near(new BlockPos(x0 + size / 2, 0, z0 + size / 2),
                size / 2 + com.ale.warbandcraft.dungeon.Dungeons.FOOTPRINT) != null) {
            return Spot.no("Pisa una mazmorra");   // H25
        }
        int hint = v.center().getY();
        int[] heights = new int[size * size];
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                Integer g = Blueprints.groundY(level, x, z, hint);
                if (g == null) return Spot.no("No se ve el piso (¿no está cargado?)");
                BlockPos ground = new BlockPos(x, g, z);
                BlockState gs = level.getBlockState(ground);
                if (!gs.getFluidState().isEmpty()) return Spot.no("Hay agua");
                if (!Blueprints.natural(gs) || level.getBlockEntity(ground) != null) return Spot.no("Pisa una casa o una construcción");
                for (int dy = 1; dy <= CLEAR_ABOVE; dy++) {
                    BlockPos p = ground.above(dy);
                    if (level.getBlockEntity(p) != null) return Spot.no("Pisa un cofre, una cama o algo así");
                    if (!Blueprints.clearable(level.getBlockState(p))) return Spot.no("Pisa una casa o una construcción");
                }
                heights[n++] = g;
            }
        }
        Arrays.sort(heights);
        int slope = heights[heights.length - 1] - heights[0];
        if (slope > MAX_LEVEL) return Spot.no("Muy empinado (desnivel " + slope + ", máximo " + MAX_LEVEL + ")");
        int floor = heights[heights.length / 2];   // la mediana: se rellena lo bajo y se corta lo alto
        return new Spot(new BlockPos(x0, floor, z0), null);
    }

    /** Adentro del territorio de la aldea (sin territorio: a {@link WorkZones#REACH} del centro). */
    public static boolean inside(ServerLevel level, VillageData.Village v, int x, int z) {
        List<ChunkPos> chunks = Territory.get(level).of(v.center());
        if (chunks.isEmpty()) {
            double dx = x - v.center().getX(), dz = z - v.center().getZ();
            return dx * dx + dz * dz <= (double) WorkZones.REACH * WorkZones.REACH;
        }
        return chunks.contains(new ChunkPos(x >> 4, z >> 4));
    }
}
