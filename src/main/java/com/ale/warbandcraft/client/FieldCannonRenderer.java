package com.ale.warbandcraft.client;

import com.ale.warbandcraft.sapper.FieldCannonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Cañón de campaña: por ahora un dispensador apuntando al objetivo (el modelo propio llega en H7). */
@OnlyIn(Dist.CLIENT)
public class FieldCannonRenderer extends EntityRenderer<FieldCannonEntity> {
    private static final BlockState LOOK = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.SOUTH);
    private final BlockRenderDispatcher blocks;

    public FieldCannonRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blocks = context.getBlockRenderDispatcher();
        this.shadowRadius = 0.5F;
    }

    @Override
    public void render(FieldCannonEntity cannon, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-cannon.getYRot()));
        pose.translate(-0.5, 0.0, -0.5);
        blocks.renderSingleBlock(LOOK, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(cannon, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(FieldCannonEntity cannon) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
