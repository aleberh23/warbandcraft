package com.ale.warbandcraft.castle;

import com.ale.warbandcraft.upgrade.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * El castillo clásico ({@code castle_default.nbt}, piedra rústica y abeto) con los materiales de la facción: cambia
 * cada bloque por el de la tabla copiando sus propiedades (escalones, losas, puertas, troncos quedan igual de puestos).
 * Solo se usa en el acto al pegar el plano (no se guarda en ningún lado: por eso {@link #getType} es el de "nada").
 */
public final class CastleRestyle extends StructureProcessor {
    private final Map<Block, Block> swap;

    private CastleRestyle(Map<Block, Block> swap) {
        this.swap = swap;
    }

    /** Null = el estilo usa los materiales del plano tal cual (Norte). */
    @Nullable
    public static CastleRestyle of(Style st) {
        Map<String, String> t = new HashMap<>();
        switch (st) {
            case REINO -> {
                stone(t, "stone_bricks", "stone_brick");
                t.put("mossy_cobblestone", "mossy_stone_bricks");
                t.put("mossy_cobblestone_wall", "mossy_stone_brick_wall");
            }
            case ARENA -> {
                stone(t, "sandstone", "sandstone");
                t.put("mossy_cobblestone", "smooth_sandstone");
                t.put("mossy_cobblestone_wall", "sandstone_wall");
                t.put("mossy_stone_bricks", "chiseled_sandstone");
                t.put("cracked_stone_bricks", "cut_sandstone");
                t.put("andesite", "smooth_sandstone");
                t.put("stone", "cut_sandstone");
                t.put("stone_slab", "smooth_sandstone_slab");
                t.put("stone_stairs", "smooth_sandstone_stairs");
                t.put("stone_brick_wall", "sandstone_wall");
                t.put("grass_block", "sand");
                t.put("dirt", "sandstone");
                t.put("coarse_dirt", "smooth_sandstone");
                t.put("grass", "air");
                t.put("tall_grass", "air");
                wood(t, "jungle");
            }
            case PANTANO -> {
                stone(t, "mud_bricks", "mud_brick");
                t.put("mossy_cobblestone", "packed_mud");
                t.put("mossy_cobblestone_wall", "mud_brick_wall");
                t.put("andesite", "packed_mud");
                t.put("stone", "mud_bricks");
                t.put("stone_slab", "mud_brick_slab");
                t.put("stone_stairs", "mud_brick_stairs");
                t.put("stone_brick_wall", "mud_brick_wall");
                t.put("coarse_dirt", "mud");
                wood(t, "mangrove");
            }
            case SABANA -> {
                stone(t, "red_sandstone", "red_sandstone");
                t.put("mossy_cobblestone", "smooth_red_sandstone");
                t.put("mossy_cobblestone_wall", "red_sandstone_wall");
                wood(t, "acacia");
            }
            default -> {
                return null;
            }
        }
        Map<Block, Block> swap = new HashMap<>();
        t.forEach((from, to) -> {
            ResourceLocation f = new ResourceLocation(from), k = new ResourceLocation(to);
            if (BuiltInRegistries.BLOCK.containsKey(f) && BuiltInRegistries.BLOCK.containsKey(k)) {
                swap.put(BuiltInRegistries.BLOCK.get(f), BuiltInRegistries.BLOCK.get(k));
            }
        });
        return new CastleRestyle(swap);
    }

    /** Piedra del plano (adoquín y sus escalones, losas y muros) → la familia {@code base} / {@code stem}_stairs... */
    private static void stone(Map<String, String> t, String base, String stem) {
        t.put("cobblestone", base);
        t.put("cobblestone_stairs", stem + "_stairs");
        t.put("cobblestone_slab", stem + "_slab");
        t.put("cobblestone_wall", stem + "_wall");
    }

    /** Lo de abeto del plano → la misma pieza de {@code wood}. */
    private static void wood(Map<String, String> t, String wood) {
        for (String piece : new String[]{"slab", "trapdoor", "fence", "log", "door", "stairs", "planks", "fence_gate", "wall_sign"}) {
            t.put("spruce_" + piece, wood + "_" + piece);
        }
    }

    public BlockState restyle(BlockState s) {
        Block to = swap.get(s.getBlock());
        return to == null ? s : copy(s, to);
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                             StructureTemplate.StructureBlockInfo original,
                                                             StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        BlockState s = restyle(current.state());
        return s == current.state() ? current : new StructureTemplate.StructureBlockInfo(current.pos(), s, current.nbt());
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return StructureProcessorType.NOP;
    }

    /** {@code to} con las propiedades de {@code from} que tenga con el mismo nombre. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState copy(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property<?> p : from.getProperties()) {
            Property q = to.getStateDefinition().getProperty(p.getName());
            if (q == null) continue;
            String value = ((Property) p).getName(from.getValue(p));
            java.util.Optional<? extends Comparable> v = q.getValue(value);
            if (v.isPresent()) s = s.setValue(q, v.get());
        }
        return s;
    }
}
