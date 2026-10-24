package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.ale.warbandcraft.kit.KitManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Cliente → servidor: ponerle nombre propio a una clase (pestaña Kits). Vacío = nombre original. */
public class RenameClassMessage {
    private final BlockPos camp;
    private final ResourceLocation kit;
    private final String name;

    public RenameClassMessage(BlockPos camp, ResourceLocation kit, String name) {
        this.camp = camp;
        this.kit = kit;
        this.name = name;
    }

    public static void encode(RenameClassMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeResourceLocation(msg.kit);
        buf.writeUtf(msg.name, PlayerFactionData.MAX_NAME_LENGTH * 4);
    }

    public static RenameClassMessage decode(FriendlyByteBuf buf) {
        return new RenameClassMessage(buf.readBlockPos(), buf.readResourceLocation(), buf.readUtf(PlayerFactionData.MAX_NAME_LENGTH * 4));
    }

    public static void handle(RenameClassMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null || CampTabs.access(player, msg.camp, true) == null) return;
            if (KitManager.INSTANCE.get(msg.kit) == null) return;
            PlayerFactionData.get(player.getServer()).setClassName(player.getUUID(), msg.kit, msg.name);
            String shown = PlayerFactionData.get(player.getServer()).className(player.getUUID(), msg.kit);
            player.displayClientMessage(shown == null
                    ? Component.translatable("msg.warbandcraft.kit.name_reset").withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("msg.warbandcraft.kit.renamed", shown).withStyle(ChatFormatting.GREEN), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
