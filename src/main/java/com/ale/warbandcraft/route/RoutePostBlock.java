package com.ale.warbandcraft.route;

import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;

/**
 * Poste de caminos: click derecho abre la pantalla de Rutas como si estuvieras en el punto tuyo (Campamento,
 * aldea o castillo) más cercano al poste, a ≤ {@link Routes#ENDPOINT_RADIUS} bloques. Así se viaja desde
 * el poste aunque la tecla J no te tome por estar un poco lejos.
 */
public class RoutePostBlock extends HorizontalDirectionalBlock {
    /** A cuánto del poste tenés que estar para que valga viajar desde él. */
    public static double USE_RADIUS = 8;

    private static final VoxelShape SHAPE_NS = Block.box(0, 0, 6, 16, 16, 10);
    private static final VoxelShape SHAPE_EW = Block.box(6, 0, 0, 10, 16, 16);

    public RoutePostBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @SuppressWarnings("deprecation")
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), OpenRoutesMessage.of(sp, null, pos));
        }
        return InteractionResult.CONSUME;
    }

    /** ¿Hay un poste en {@code pos} y el jugador está al lado? */
    public static boolean usable(ServerPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos).getBlock() instanceof RoutePostBlock
                && player.position().distanceToSqr(pos.getCenter()) <= USE_RADIUS * USE_RADIUS;
    }
}
