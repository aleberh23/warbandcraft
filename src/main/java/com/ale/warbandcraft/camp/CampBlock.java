package com.ale.warbandcraft.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** El bloque del Campamento. Click derecho abre su cofre (54 espacios), con las pestañas Doctrinas y Kits (H6). */
public class CampBlock extends BaseEntityBlock {

    public CampBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CampBlockEntity(pos, state);
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof CampBlockEntity camp && player instanceof ServerPlayer sp) {
            CampTabs.open(sp, camp, CampTabs.CHEST);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player && level.getBlockEntity(pos) instanceof CampBlockEntity camp) {
            camp.setOwner(player.getUUID());
            // H8: el nombre del yunque (como un cofre) o "Campamento N"
            if (level instanceof ServerLevel server) {
                camp.rename(stack.hasCustomHoverName() ? stack.getHoverName().getString()
                        : defaultName(CampRegistry.get(server).nextNumber(player.getUUID())));
            }
        }
    }

    /** "Campamento 3". El nombre queda guardado como texto, así que se traduce acá (en el idioma del servidor). */
    private static String defaultName(int number) {
        String key = "container.warbandcraft.camp.numbered";
        String name = Component.translatable(key, number).getString();
        return name.startsWith(key) ? "Campamento " + number : name;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof CampBlockEntity camp && camp.getOwner() != null) {
                CampRegistry.get(server).unregister(camp.getOwner(), pos);
                Barracks.campBroken(server, pos, camp.getOwner()); // H8: los del barracón salen, nada se pierde
            }
            if (level.getBlockEntity(pos) instanceof Container container) {
                Containers.dropContents(level, pos, container);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}
