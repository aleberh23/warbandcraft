package com.ale.warbandcraft.network;

import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Servidor → cliente (H22): la vista previa de plantar un edificio en el modo Gestión. {@code key} = lo que pidió el
 * cliente ("id,x,z" + giro) para saber si sigue siendo la del lugar donde está el mouse.
 */
public class BuildPreviewMessage {
    public final String key;
    public final boolean ok;
    public final String text;
    public final int floorY;

    public BuildPreviewMessage(String key, boolean ok, String text, int floorY) {
        this.key = key;
        this.ok = ok;
        this.text = text;
        this.floorY = floorY;
    }

    /** "id,x,z": qué edificio y la esquina de su terreno. */
    public record Target(Upgrade upgrade, BlockPos corner) {
        @Nullable
        public static Target parse(String text) {
            String[] p = text.split(",");
            if (p.length != 3) return null;
            Upgrade u = Upgrade.byId(p[0]);
            if (u == null || !u.handPlaced()) return null;
            try {
                return new Target(u, new BlockPos(Integer.parseInt(p[1]), 0, Integer.parseInt(p[2])));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static void answer(ServerPlayer player, VillageData.Village v, String text, int rot) {
        Upgrades.Preview p;
        if (text.contains(":")) {
            p = Upgrades.previewWork(player, v.center(), text);
        } else {
            Target t = Target.parse(text);
            if (t == null) return;
            p = Upgrades.preview(player, v.center(), t.upgrade(), t.corner(), rot);
        }
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BuildPreviewMessage(text + "," + rot, p.ok(),
                p.text().length() > 400 ? p.text().substring(0, 400) : p.text(), p.floorY()));
    }

    public static void encode(BuildPreviewMessage msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.key, 4200);
        buf.writeBoolean(msg.ok);
        buf.writeUtf(msg.text, 400);
        buf.writeInt(msg.floorY);
    }

    public static BuildPreviewMessage decode(FriendlyByteBuf buf) {
        return new BuildPreviewMessage(buf.readUtf(4200), buf.readBoolean(), buf.readUtf(400), buf.readInt());
    }

    public static void handle(BuildPreviewMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.aerial.AerialScreen.receivePreview(msg.key, msg.ok, msg.text, msg.floorY)));
        ctx.get().setPacketHandled(true);
    }
}
