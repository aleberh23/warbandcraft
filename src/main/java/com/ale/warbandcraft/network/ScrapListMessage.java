package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.CampMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.Set;
import java.util.function.Supplier;

/** Servidor → cliente: lo marcado para desguazar en un Campamento, después de cada cambio. */
public class ScrapListMessage {
    public final BlockPos camp;
    public final Set<ResourceLocation> items;

    public ScrapListMessage(BlockPos camp, Set<ResourceLocation> items) {
        this.camp = camp;
        this.items = items;
    }

    public static void encode(ScrapListMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        CampMenu.writeScrap(buf, msg.items);
    }

    public static ScrapListMessage decode(FriendlyByteBuf buf) {
        return new ScrapListMessage(buf.readBlockPos(), CampMenu.readScrap(buf));
    }

    public static void handle(ScrapListMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.camp.CampClientHandler.scrapList(msg)));
        ctx.get().setPacketHandled(true);
    }
}
