package com.ale.warbandcraft.event;

import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.RecruitsFactionBridge;
import com.ale.warbandcraft.item.MarshalBatonItem;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.kit.KitManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CommonEvents {

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(KitManager.INSTANCE);
        event.addListener(FactionManager.INSTANCE);
        event.addListener(Armament.INSTANCE);
        event.addListener(com.ale.warbandcraft.market.Prices.INSTANCE);   // H24
    }

    /** Ya arrancó Recruits (sus managers de facciones existen): registra las facciones NPC en su menú. */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        RecruitsFactionBridge.sync(event.getServer().overworld());
    }

    /**
     * Click derecho a un soldado con el Bastón de Mariscal. Se intercepta antes de que Recruits
     * abra su menú, así no hace falta tocar su código.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof MarshalBatonItem)) return;
        if (!(event.getTarget() instanceof AbstractRecruitEntity recruit) || Soldiers.isWorker(recruit)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (event.getEntity() instanceof ServerPlayer player) {
            ResourceLocation selected = MarshalBatonItem.getSelected(stack);
            if (selected == null) {
                player.displayClientMessage(Component.translatable("msg.warbandcraft.pick_first").withStyle(ChatFormatting.RED), true);
                return;
            }
            ClassAssigner.assign(player, recruit, selected);
        }
    }
}
