package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.Tags;
import org.jetbrains.annotations.Nullable;

/**
 * Estilo de los edificios de una aldea (pedido de Ale): los mismos planos de {@link Buildings} con otros materiales.
 * Las aldeas de facción usan el de su facción; las tuyas (conquistadas antes de tener estilo, fundadas) el del bioma.
 * Se elige con el primer edificio y queda guardado en {@link TownData.Town#style}: si la aldea cambia de dueño, sigue igual.
 */
public enum Style {
    /** Reino de Valdren (llanuras, bosques y lo que no tiene otro): entramado de roble oscuro, pizarra, piedra. */
    REINO("reino", "Reino", Blocks.STONE_BRICKS, Blocks.WHITE_TERRACOTTA, Blocks.DARK_OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_PRESSURE_PLATE, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES,
            Blocks.DARK_OAK_FENCE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.BLUE_CARPET, Blocks.BLUE_BED,
            Blocks.BLUE_WALL_BANNER, Blocks.BLUE_WOOL, Blocks.BLUE_STAINED_GLASS_PANE, false),
    /** Clanes del Norte (taiga, nieve, montaña): casas largas de abeto con techo oscuro y zócalo de piedra. */
    NORTE("norte", "Norte", Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_PRESSURE_PLATE, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_PLANKS,
            Blocks.SPRUCE_FENCE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_TRAPDOOR, Blocks.CYAN_CARPET, Blocks.CYAN_BED,
            Blocks.CYAN_WALL_BANNER, Blocks.CYAN_WOOL, Blocks.CYAN_STAINED_GLASS_PANE, false),
    /** Sultanato de Arena (desierto, badlands): arenisca, techo plano con pretil y toldos. */
    ARENA("arena", "Arena", Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_STAIRS,
            Blocks.JUNGLE_PRESSURE_PLATE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE,
            Blocks.JUNGLE_FENCE, Blocks.JUNGLE_DOOR, Blocks.JUNGLE_TRAPDOOR, Blocks.ORANGE_CARPET, Blocks.YELLOW_BED,
            Blocks.YELLOW_WALL_BANNER, Blocks.ORANGE_WOOL, Blocks.ORANGE_STAINED_GLASS_PANE, true),
    /** Liga de los Pantanos (pantano, selva, manglar): barro, troncos de mangle. */
    PANTANO("pantano", "Pantano", Blocks.MUD_BRICKS, Blocks.PACKED_MUD, Blocks.MANGROVE_LOG, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_STAIRS,
            Blocks.MANGROVE_PRESSURE_PLATE, Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_SLAB, Blocks.MANGROVE_PLANKS,
            Blocks.MANGROVE_FENCE, Blocks.MANGROVE_DOOR, Blocks.MANGROVE_TRAPDOOR, Blocks.GREEN_CARPET, Blocks.GREEN_BED,
            Blocks.GREEN_WALL_BANNER, Blocks.LIME_WOOL, Blocks.GREEN_STAINED_GLASS_PANE, false),
    /** Sabana (sin facción propia): acacia y terracota. */
    SABANA("sabana", "Sabana", Blocks.TERRACOTTA, Blocks.ACACIA_PLANKS, Blocks.ACACIA_LOG, Blocks.ACACIA_PLANKS, Blocks.ACACIA_STAIRS,
            Blocks.ACACIA_PRESSURE_PLATE, Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB, Blocks.ACACIA_PLANKS,
            Blocks.ACACIA_FENCE, Blocks.ACACIA_DOOR, Blocks.ACACIA_TRAPDOOR, Blocks.ORANGE_CARPET, Blocks.RED_BED,
            Blocks.ORANGE_WALL_BANNER, Blocks.RED_WOOL, Blocks.GLASS_PANE, false);

    public final String id, label;
    /** Zócalo y cimientos, relleno de las paredes, postes y vigas (un tronco o algo sin eje), pisos de adentro. */
    public final Block found, wall, frame, planks;
    /** Sillas, mesas (placa arriba de un cerco), techo (escalones, losas, bloque lleno). */
    public final Block seat, plate, roofStairs, roofSlab, roofBlock;
    public final Block fence, door, trapdoor, carpet, bed, banner, wool, glass;
    /** Techo plano con pretil en vez de a dos aguas. */
    public final boolean flat;

    Style(String id, String label, Block found, Block wall, Block frame, Block planks, Block seat, Block plate, Block roofStairs,
          Block roofSlab, Block roofBlock, Block fence, Block door, Block trapdoor, Block carpet, Block bed, Block banner, Block wool,
          Block glass, boolean flat) {
        this.id = id;
        this.label = label;
        this.found = found;
        this.wall = wall;
        this.frame = frame;
        this.planks = planks;
        this.seat = seat;
        this.plate = plate;
        this.roofStairs = roofStairs;
        this.roofSlab = roofSlab;
        this.roofBlock = roofBlock;
        this.fence = fence;
        this.door = door;
        this.trapdoor = trapdoor;
        this.carpet = carpet;
        this.bed = bed;
        this.banner = banner;
        this.wool = wool;
        this.glass = glass;
        this.flat = flat;
    }

    public static Style byId(@Nullable String id) {
        for (Style s : values()) if (s.id.equals(id)) return s;
        return REINO;
    }

    /** El estilo de una facción por su id (las que no están acá, por el bioma). */
    @Nullable
    public static Style ofFaction(@Nullable ResourceLocation faction) {
        if (faction == null) return null;
        return switch (faction.getPath()) {
            case "valdren" -> REINO;
            case "norte" -> NORTE;
            case "sultanato" -> ARENA;
            case "pantanos" -> PANTANO;
            default -> null;
        };
    }

    /** El estilo del bioma de {@code pos}. */
    public static Style ofBiome(ServerLevel level, BlockPos pos) {
        var b = level.getBiome(pos);
        if (b.is(Tags.Biomes.IS_DESERT) || b.is(BiomeTags.IS_BADLANDS)) return ARENA;
        if (b.is(BiomeTags.IS_SAVANNA)) return SABANA;
        if (b.is(Tags.Biomes.IS_SWAMP) || b.is(BiomeTags.IS_JUNGLE)) return PANTANO;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(BiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_MOUNTAIN)) return NORTE;
        return REINO;
    }

    /**
     * El estilo de la aldea: el guardado, o (la primera vez) el de su facción si no tiene dueño y si no el del bioma, y lo
     * guarda.
     */
    public static Style of(ServerLevel level, VillageData.Village v, TownData.Town town) {
        if (!town.style.isEmpty()) return byId(town.style);
        Style s = v.owner() == null ? ofFaction(v.faction()) : null;
        if (s == null) s = ofBiome(level, v.center());
        town.style = s.id;
        TownData.get(level).changed();
        return s;
    }
}
