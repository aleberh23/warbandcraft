package com.ale.warbandcraft.network;

import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.FactionStyle;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.talhanation.recruits.world.RecruitsFaction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Servidor → cliente: abre (o refresca) la pestaña Doctrinas del Campamento con el estado del jugador.
 * Lleva también la identidad de su facción de Recruits (nombre, estandarte, color) para mostrarla.
 */
public class OpenDoctrineMessage {
    public final BlockPos camp;
    public final int current;          // ordinal de la doctrina, -1 = ninguna
    public final boolean free;
    public final long waitTicks;
    public final String factionName;   // vacío = no tiene facción de Recruits
    public final ItemStack banner;
    public final int armorColor;

    public OpenDoctrineMessage(BlockPos camp, int current, boolean free, long waitTicks,
                               String factionName, ItemStack banner, int armorColor) {
        this.camp = camp;
        this.current = current;
        this.free = free;
        this.waitTicks = waitTicks;
        this.factionName = factionName;
        this.banner = banner;
        this.armorColor = armorColor;
    }

    public static OpenDoctrineMessage of(ServerPlayer player, BlockPos camp) {
        PlayerFactionData data = PlayerFactionData.get(player.getServer());
        Doctrine d = data.doctrine(player.getUUID());
        RecruitsFaction faction = FactionStyle.recruitsFactionOf(player);
        FactionStyle style = faction == null ? null : FactionStyle.of(faction);
        return new OpenDoctrineMessage(camp, d == null ? -1 : d.ordinal(), data.nextChangeFree(player.getUUID()),
                data.waitLeft(player.getUUID(), player.level().getGameTime()),
                faction == null ? "" : faction.getTeamDisplayName(),
                style == null ? ItemStack.EMPTY : style.banner(), style == null ? 0xFFFFFF : style.armorColor());
    }

    public static void encode(OpenDoctrineMessage msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.camp);
        buf.writeVarInt(msg.current + 1);
        buf.writeBoolean(msg.free);
        buf.writeVarLong(msg.waitTicks);
        buf.writeUtf(msg.factionName);
        buf.writeItem(msg.banner);
        buf.writeInt(msg.armorColor);
    }

    public static OpenDoctrineMessage decode(FriendlyByteBuf buf) {
        return new OpenDoctrineMessage(buf.readBlockPos(), buf.readVarInt() - 1, buf.readBoolean(), buf.readVarLong(),
                buf.readUtf(), buf.readItem(), buf.readInt());
    }

    public static void handle(OpenDoctrineMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ale.warbandcraft.client.camp.CampClientHandler.openDoctrines(msg)));
        ctx.get().setPacketHandled(true);
    }
}
