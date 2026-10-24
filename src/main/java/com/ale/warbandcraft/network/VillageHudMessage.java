package com.ale.warbandcraft.network;

import com.ale.warbandcraft.village.VillageHud;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Servidor → cliente: el cartelito de la aldea/castillo en que estás (null = saliste: se esconde). */
public class VillageHudMessage {
    @Nullable
    public final VillageHud.Info info;

    public VillageHudMessage(@Nullable VillageHud.Info info) {
        this.info = info;
    }

    public static void encode(VillageHudMessage msg, FriendlyByteBuf buf) {
        buf.writeNullable(msg.info, (b, i) -> {
            b.writeComponent(i.name());
            b.writeBoolean(i.castle());
            b.writeVarInt(i.relation());
            b.writeComponent(i.owner());
            OpenPanelMessage.writeStock(b, i.daily());
            b.writeVarInt(i.population());
            b.writeVarInt(i.barracks() + 1);
            b.writeVarInt(i.capacity() + 1);
            b.writeVarInt(i.guards() + 1);
            b.writeVarInt(i.garrison());
            b.writeVarInt(i.invasionTicks() + 1);
        });
    }

    public static VillageHudMessage decode(FriendlyByteBuf buf) {
        return new VillageHudMessage(buf.readNullable(b -> new VillageHud.Info(b.readComponent(), b.readBoolean(), b.readVarInt(),
                b.readComponent(), OpenPanelMessage.readStock(b), b.readVarInt(), b.readVarInt() - 1, b.readVarInt() - 1,
                b.readVarInt() - 1, b.readVarInt(), b.readVarInt() - 1)));
    }

    public static void handle(VillageHudMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.VillageHudOverlay.receive(msg.info)));
        ctx.get().setPacketHandled(true);
    }
}
