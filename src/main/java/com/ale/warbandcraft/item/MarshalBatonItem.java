package com.ale.warbandcraft.item;

import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bastón de Mariscal.
 * - Click derecho al aire: elige la siguiente clase (agachado: la anterior).
 * - Click derecho a un soldado tuyo cerca del Campamento: le asigna la clase y lo arma.
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
            List<ClassKit> kits = KitManager.INSTANCE.ordered();
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
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getSelected(stack) != null;
    }
}
