package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.init.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

/**
 * Pestaña "Cofre" del Campamento: la misma grilla de 54 de un cofre doble, pero con su propio tipo de
 * menú para que el cliente sepa que es un Campamento (y dibuje las pestañas Doctrinas / Kits).
 */
public class CampMenu extends ChestMenu {
    private final BlockPos campPos;
    private final boolean owner;
    /** El Campamento ya es el de una aldea (no muestra "Fundar aldea"). */
    private final boolean village;
    /** H10: totales del almacén ({@link CampStats}), se actualizan solos. */
    private final ContainerData stats;

    public CampMenu(int id, Inventory inventory, Container container, BlockPos campPos, boolean owner, boolean village, ContainerData stats) {
        super(ModMenus.CAMP.get(), id, inventory, container, 6);
        this.campPos = campPos;
        this.owner = owner;
        this.village = village;
        this.stats = stats;
        addDataSlots(stats);
    }

    public static CampMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        return new CampMenu(id, inventory, new SimpleContainer(CampBlockEntity.SIZE), buf.readBlockPos(), buf.readBoolean(),
                buf.readBoolean(), new SimpleContainerData(CampStats.SIZE));
    }

    /** Un total del almacén (índices de {@link CampStats}). */
    public int stat(int index) {
        return stats.get(index);
    }

    public BlockPos campPos() {
        return campPos;
    }

    /** Solo el dueño ve las pestañas Doctrinas y Kits. */
    public boolean isOwner() {
        return owner;
    }

    public boolean isVillage() {
        return village;
    }
}
