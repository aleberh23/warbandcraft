package com.ale.warbandcraft.castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Para dibujar un castillo en un terreno cuadrado de {@code size} girado {@code rot} (como {@code StructureTemplate}:
 * horario = (x, z) → (lado−1−z, x)), igual que {@code upgrade/Buildings.Canvas} pero poniendo los bloques en el acto.
 * y = 0 es la fila del piso (la que el plano viejo traía de pasto); se camina en y = 1. Lo que se cuelga o se apoya
 * (puertas, escaleras de mano, faroles, estandartes, camas) va con {@link #late}.
 */
final class CastleCanvas {
    final BlockPos origin;
    final Rotation rot;
    final int size;
    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> late = new LinkedHashMap<>();
    private final List<Map.Entry<BlockPos, ItemStack>> banners = new ArrayList<>();

    CastleCanvas(BlockPos origin, Rotation rot, int size) {
        this.origin = origin;
        this.rot = rot;
        this.size = size;
    }

    BlockPos at(int x, int y, int z) {
        int m = size - 1;
        return switch (rot) {
            case CLOCKWISE_90 -> origin.offset(m - z, y, x);
            case CLOCKWISE_180 -> origin.offset(m - x, y, m - z);
            case COUNTERCLOCKWISE_90 -> origin.offset(z, y, m - x);
            default -> origin.offset(x, y, z);
        };
    }

    boolean inside(int x, int z) {
        return x >= 0 && z >= 0 && x < size && z < size;
    }

    void set(int x, int y, int z, BlockState s) {
        if (!inside(x, z)) return;
        BlockPos p = at(x, y, z);
        late.remove(p);
        blocks.remove(p);
        blocks.put(p, s.rotate(rot));
    }

    void set(int x, int y, int z, Block b) {
        set(x, y, z, b.defaultBlockState());
    }

    void late(int x, int y, int z, BlockState s) {
        if (!inside(x, z)) return;
        BlockPos p = at(x, y, z);
        blocks.remove(p);
        late.remove(p);
        late.put(p, s.rotate(rot));
    }

    void air(int x, int y, int z) {
        set(x, y, z, Blocks.AIR.defaultBlockState());
    }

    void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y, z, s);
    }

    void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        fill(x0, y0, z0, x1, y1, z1, b.defaultBlockState());
    }

    /** Lo que ya se dibujó en (x, y, z), o null. */
    BlockState get(int x, int y, int z) {
        BlockPos p = at(x, y, z);
        BlockState s = late.get(p);
        return s != null ? s : blocks.get(p);
    }

    /** Estandarte de la facción colgado de la pared de atrás de (x, y, z), mirando a {@code facing}. */
    void banner(int x, int y, int z, Direction facing, ItemStack banner) {
        DyeColor color = banner.getItem() instanceof BannerItem b ? b.getColor() : DyeColor.WHITE;
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(color.getName() + "_wall_banner"));
        if (!(block instanceof WallBannerBlock)) block = Blocks.WHITE_WALL_BANNER;
        late(x, y, z, block.defaultBlockState().setValue(WallBannerBlock.FACING, facing));
        if (inside(x, z)) banners.add(Map.entry(at(x, y, z), banner.copy()));
    }

    /** Pone todo (sin avisar a los vecinos, como el plano viejo) y después une cercos, muros, rejas y escalones. */
    void place(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
        for (Map.Entry<BlockPos, BlockState> e : late.entrySet()) level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
        for (Map<BlockPos, BlockState> m : List.of(blocks, late)) {
            for (BlockPos p : m.keySet()) {
                BlockState s = level.getBlockState(p);
                Block b = s.getBlock();
                if (b instanceof FenceBlock || b instanceof IronBarsBlock || b instanceof WallBlock || b instanceof StairBlock) {
                    BlockState joined = Block.updateFromNeighbourShapes(s, level, p);
                    if (joined != s) level.setBlock(p, joined, Block.UPDATE_CLIENTS);
                }
            }
        }
        for (Map.Entry<BlockPos, ItemStack> e : banners) {
            if (level.getBlockEntity(e.getKey()) instanceof BannerBlockEntity be) {
                DyeColor color = e.getValue().getItem() instanceof BannerItem b ? b.getColor() : DyeColor.WHITE;
                be.fromItem(e.getValue(), color);
                be.setChanged();
            }
        }
    }
}
