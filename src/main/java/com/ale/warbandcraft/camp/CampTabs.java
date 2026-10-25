package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.network.OpenDoctrineMessage;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pestañas del Campamento (H6/H8/H13), lado servidor: Cofre · Doctrinas · Kits · Barracón · Rutas · Envíos. El cliente pide cambiar de
 * pestaña con {@link com.ale.warbandcraft.network.CampTabMessage} y acá se valida y se abre.
 */
public final class CampTabs {
    public static final int CHEST = 0, DOCTRINES = 1, KITS = 2, BARRACKS = 3, ROUTES = 4, SHIPMENTS = 5;
    /** Hasta dónde se puede estar del Campamento con sus pantallas abiertas. */
    public static final double REACH = 8.0;

    private CampTabs() {}

    /** El Campamento en {@code pos} si el jugador está cerca; con {@code ownerOnly}, además tiene que ser suyo. */
    @Nullable
    public static CampBlockEntity access(ServerPlayer player, BlockPos pos, boolean ownerOnly) {
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > REACH * REACH) return null;
        if (!(player.level().getBlockEntity(pos) instanceof CampBlockEntity camp)) return null;
        if (ownerOnly && !camp.canBeUsedBy(player.getUUID())) return null;
        return camp;
    }

    /** Como {@link #access} pero también para un compañero de la facción del dueño (barracón). */
    @Nullable
    public static CampBlockEntity accessBarracks(ServerPlayer player, BlockPos pos) {
        CampBlockEntity camp = access(player, pos, false);
        return camp != null && camp.barracksUsableBy(player.getUUID()) ? camp : null;
    }

    public static void open(ServerPlayer player, CampBlockEntity camp, int tab) {
        BlockPos pos = camp.getBlockPos();
        boolean owner = camp.canBeUsedBy(player.getUUID());
        boolean ally = camp.barracksUsableBy(player.getUUID());
        if (!owner && !(ally && tab == BARRACKS)) tab = CHEST;
        switch (tab) {
            case DOCTRINES -> {
                if (player.containerMenu != player.inventoryMenu) player.closeContainer();
                ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenDoctrineMessage.of(player, pos));
            }
            case BARRACKS -> {
                if (player.containerMenu != player.inventoryMenu) player.closeContainer();
                ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenBarracksMessage.of(player, camp));
            }
            case ROUTES -> {
                if (player.containerMenu != player.inventoryMenu) player.closeContainer();
                ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenRoutesMessage.of(player, pos));
            }
            case SHIPMENTS -> {
                ShipmentMenu.Info info = ShipmentMenu.Info.of(player, camp);
                NetworkHooks.openScreen(player, new SimpleMenuProvider(
                                (id, inv, p) -> new ShipmentMenu(id, inv, info),
                                Component.translatable("gui.warbandcraft.tab.shipments")),
                        info::write);
            }
            case KITS -> {
                List<ClassKit> kits = PlayerKits.all(player.getServer(), player.getUUID());
                List<KitEditorMenu.ClassInfo> classes = KitEditorMenu.ClassInfo.of(kits);
                NetworkHooks.openScreen(player, new SimpleMenuProvider(
                                (id, inv, p) -> new KitEditorMenu(id, inv, pos, classes, true),
                                Component.translatable("gui.warbandcraft.tab.kits")),
                        buf -> KitEditorMenu.write(buf, pos, classes));
            }
            default -> NetworkHooks.openScreen(player, camp, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(owner);
                buf.writeBoolean(ally);
                buf.writeBoolean(camp.getVillage() != null);
                CampMenu.writeScrap(buf, camp.scrapList());
            });
        }
    }
}
