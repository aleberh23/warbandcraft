package com.ale.warbandcraft.network;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Servidor → cliente: tus Campamentos en este mundo, para la vista aérea (H8): nombre sobre cada uno,
 * su jurisdicción al seleccionarlo y la lista del botón Barracón. El cliente lo pide con
 * {@link Request} al entrar a la vista y al abrir esa lista.
 */
public class AerialCampsMessage {
    public record CampInfo(BlockPos pos, Component name, int count, int capacity) {}

    public final List<CampInfo> camps;
    /** H16: centros de tus aldeas/castillos (botón Gestión cuando estás en una). */
    public final List<BlockPos> villages;

    public AerialCampsMessage(List<CampInfo> camps, List<BlockPos> villages) {
        this.camps = camps;
        this.villages = villages;
    }

    public static AerialCampsMessage of(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CampRegistry registry = CampRegistry.get(level);
        BarracksData data = BarracksData.get(level);
        List<CampInfo> out = new ArrayList<>();
        for (BlockPos pos : registry.campsOf(player.getUUID())) {
            out.add(new CampInfo(pos, registry.displayName(pos), data.count(pos), com.ale.warbandcraft.camp.Barracks.capacity(level, pos)));
        }
        List<BlockPos> villages = new ArrayList<>();
        for (com.ale.warbandcraft.village.VillageData.Village v : com.ale.warbandcraft.village.VillageData.get(level).all()) {
            if (player.getUUID().equals(v.owner())) villages.add(v.center());
        }
        return new AerialCampsMessage(out, villages);
    }

    public static void send(ServerPlayer player) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), of(player));
    }

    public static void encode(AerialCampsMessage msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.camps.size());
        for (CampInfo c : msg.camps) {
            buf.writeBlockPos(c.pos());
            buf.writeComponent(c.name());
            buf.writeVarInt(c.count());
            buf.writeVarInt(c.capacity());
        }
        buf.writeCollection(msg.villages, FriendlyByteBuf::writeBlockPos);
    }

    public static AerialCampsMessage decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<CampInfo> camps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) camps.add(new CampInfo(buf.readBlockPos(), buf.readComponent(), buf.readVarInt(), buf.readVarInt()));
        return new AerialCampsMessage(camps, buf.readList(FriendlyByteBuf::readBlockPos));
    }

    public static void handle(AerialCampsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.aerial.AerialScreen.receiveCamps(msg.camps, msg.villages)));
        ctx.get().setPacketHandled(true);
    }

    /** Cliente → servidor: "mandame mis Campamentos". */
    public static class Request {
        public static void encode(Request msg, FriendlyByteBuf buf) {}

        public static Request decode(FriendlyByteBuf buf) {
            return new Request();
        }

        public static void handle(Request msg, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (player != null) send(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
