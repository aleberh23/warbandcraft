package com.ale.warbandcraft.item;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampFinder;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.PlayerKits;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bastón de Mariscal.
 * - Click derecho al aire: elige la siguiente clase (agachado: la anterior).
 * - Click derecho a un soldado tuyo cerca del Campamento: le asigna la clase y lo arma.
 * - Agachado + click derecho a un cofre: lo vincula al almacén del Campamento (H6).
 */
public class MarshalBatonItem extends Item {
    private static final String TAG_CLASS = "WarbandClass";
    private static final String TAG_CLASS_NAME = "WarbandClassName";

    public MarshalBatonItem(Properties properties) {
        super(properties);
    }

    @Nullable
    public static ResourceLocation getSelected(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_CLASS)) return null;
        return ResourceLocation.tryParse(tag.getString(TAG_CLASS));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            // H6: con los nombres propios del jugador
            List<ClassKit> kits = PlayerKits.all(level.getServer(), player.getUUID());
            if (kits.isEmpty()) {
                player.displayClientMessage(Component.translatable("msg.warbandcraft.no_kits").withStyle(ChatFormatting.RED), true);
            } else {
                ResourceLocation current = getSelected(stack);
                int idx = -1;
                for (int i = 0; i < kits.size(); i++) {
                    if (kits.get(i).id().equals(current)) idx = i;
                }
                int step = player.isShiftKeyDown() ? -1 : 1;
                int next = Math.floorMod(idx + step, kits.size());
                if (idx == -1 && step == -1) next = kits.size() - 1;
                ClassKit kit = kits.get(next);

                CompoundTag tag = stack.getOrCreateTag();
                tag.putString(TAG_CLASS, kit.id().toString());
                tag.putString(TAG_CLASS_NAME, kit.name());
                player.displayClientMessage(Component.translatable("msg.warbandcraft.selected",
                        Component.literal(kit.name()).withStyle(ChatFormatting.GOLD)), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * Agachado + click derecho a un cofre: lo vincula (o desvincula) al Campamento tuyo más cercano.
     * Agachado + click derecho al Campamento: muestra qué cofres tiene vinculados.
     * Sin agacharse, el cofre se abre normal (Minecraft no llega a llamar a esto).
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        BlockEntity target = level.getBlockEntity(pos);
        if (target instanceof CampBlockEntity camp) {
            if (level instanceof ServerLevel server) showLinks(server, player, camp);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(target instanceof RandomizableContainerBlockEntity)) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;

        CampBlockEntity camp = CampFinder.findNearest(server, pos, player.getUUID(), CampBlockEntity.LINK_RADIUS);
        if (camp == null) {
            player.displayClientMessage(Component.translatable("msg.warbandcraft.link.no_camp",
                    CampBlockEntity.LINK_RADIUS).withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        CampBlockEntity.LinkResult result = camp.toggleLink(pos);
        Component msg = switch (result) {
            case LINKED -> Component.translatable("msg.warbandcraft.link.linked",
                    camp.getLinked().size(), camp.linkedSlots()).withStyle(ChatFormatting.GREEN);
            case UNLINKED -> Component.translatable("msg.warbandcraft.link.unlinked").withStyle(ChatFormatting.YELLOW);
            case TOO_FAR -> Component.translatable("msg.warbandcraft.link.too_far",
                    CampBlockEntity.LINK_RADIUS).withStyle(ChatFormatting.RED);
            case FULL -> Component.translatable("msg.warbandcraft.link.full",
                    CampBlockEntity.MAX_LINKED).withStyle(ChatFormatting.RED);
            case NOT_A_CHEST -> Component.translatable("msg.warbandcraft.link.not_chest").withStyle(ChatFormatting.RED);
        };
        player.displayClientMessage(msg, true);
        if (result == CampBlockEntity.LinkResult.LINKED || result == CampBlockEntity.LinkResult.UNLINKED) {
            server.playSound(null, pos, result == CampBlockEntity.LinkResult.LINKED
                    ? SoundEvents.CHAIN_PLACE : SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
            sparkle(server, pos);
            sparkle(server, camp.getBlockPos());
        }
        return InteractionResult.CONSUME;
    }

    private static void showLinks(ServerLevel level, Player player, CampBlockEntity camp) {
        List<BlockPos> linked = camp.getLinked();
        player.displayClientMessage(linked.isEmpty()
                ? Component.translatable("msg.warbandcraft.link.none").withStyle(ChatFormatting.GRAY)
                : Component.translatable("msg.warbandcraft.link.list", linked.size(), camp.linkedSlots())
                        .withStyle(ChatFormatting.GOLD), true);
        for (BlockPos p : linked) sparkle(level, p);
    }

    private static void sparkle(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                8, 0.3, 0.2, 0.3, 0.0);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_CLASS_NAME)) {
            tooltip.add(Component.translatable("tooltip.warbandcraft.baton.selected",
                    Component.literal(tag.getString(TAG_CLASS_NAME)).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.warbandcraft.baton.none").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.warbandcraft.baton.help1").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.warbandcraft.baton.help2").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.warbandcraft.baton.help3").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getSelected(stack) != null;
    }
}
