package com.ale.warbandcraft.network;

import com.ale.warbandcraft.army.ArmyData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Servidor → cliente: tus ejércitos en el campo (H20), para los botones de la vista aérea. */
public class ArmyStatusMessage {
    public record Field(UUID id, String name, int mode, List<UUID> members) {}

    public final List<Field> armies;

    public ArmyStatusMessage(List<Field> armies) {
        this.armies = armies;
    }

    public static void send(ServerPlayer player) {
        List<Field> out = new ArrayList<>();
        for (ArmyData.Army a : ArmyData.get(player.serverLevel()).of(player.getUUID())) {
            if (a.state == ArmyData.State.FIELD) out.add(new Field(a.id, a.name, a.mode.ordinal(), new ArrayList<>(a.members.keySet())));
        }
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ArmyStatusMessage(out));
    }

    public static void encode(ArmyStatusMessage msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.armies, (b, a) -> {
            b.writeUUID(a.id());
            b.writeUtf(a.name());
            b.writeVarInt(a.mode());
            b.writeCollection(a.members(), FriendlyByteBuf::writeUUID);
        });
    }

    public static ArmyStatusMessage decode(FriendlyByteBuf buf) {
        return new ArmyStatusMessage(buf.readList(b -> new Field(b.readUUID(), b.readUtf(), b.readVarInt(), b.readList(FriendlyByteBuf::readUUID))));
    }

    public static void handle(ArmyStatusMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.army.ArmyClient.receive(msg.armies)));
        ctx.get().setPacketHandled(true);
    }
}
