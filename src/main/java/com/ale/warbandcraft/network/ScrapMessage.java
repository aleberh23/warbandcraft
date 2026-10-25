package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.camp.Scrap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

/** Cliente → servidor: modo Desguace de la pestaña Cofre, click en una pieza = marcarla / desmarcarla. */
public class ScrapMessage {
    private final BlockPos camp;
    private final ResourceLocation item;

    public ScrapMessage(BlockPos camp, ResourceLocation item) {
        this.camp = camp;
        this.item = item;
    }

    public static void encode(ScrapMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeResourceLocation(msg.item);
    }

    public static ScrapMessage decode(FriendlyByteBuf buf) {
        return new ScrapMessage(buf.readBlockPos(), buf.readResourceLocation());
    }

    public static void handle(ScrapMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null) return;
            CampBlockEntity camp = CampTabs.access(player, msg.camp, true);
            Item item = ForgeRegistries.ITEMS.getValue(msg.item);
            if (camp == null || item == null || item == Items.AIR) return;
            Component name = new ItemStack(item).getHoverName();
            if (!camp.isScrap(item) && !Scrap.scrappable(item)) {
                player.displayClientMessage(Component.translatable("msg.warbandcraft.scrap.no", name).withStyle(ChatFormatting.RED), true);
            } else if (camp.toggleScrap(item)) {
                player.displayClientMessage(Component.translatable("msg.warbandcraft.scrap.on", name,
                        Scrap.valueOf(new ItemStack(item))).withStyle(ChatFormatting.GOLD), true);
            } else {
                player.displayClientMessage(Component.translatable("msg.warbandcraft.scrap.off", name).withStyle(ChatFormatting.GRAY), true);
            }
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ScrapListMessage(msg.camp, camp.scrapList()));
        });
        ctx.get().setPacketHandled(true);
    }
}
