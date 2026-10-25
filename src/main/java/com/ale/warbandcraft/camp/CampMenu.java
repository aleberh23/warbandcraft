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
    /** Dueño o compañero de facción: ve la pestaña Barracón. */
    private final boolean ally;
    /** El Campamento ya es el de una aldea (no muestra "Fundar aldea"). */
    private final boolean village;
    /** H10: totales del almacén ({@link CampStats}), se actualizan solos. */
    private final ContainerData stats;
    /** Desguace: lo marcado (solo en el cliente; el servidor lo manda al abrir y después de cada cambio). */
    private java.util.Set<net.minecraft.resources.ResourceLocation> scrap = java.util.Set.of();

    public CampMenu(int id, Inventory inventory, Container container, BlockPos campPos, boolean owner, boolean ally, boolean village, ContainerData stats) {
        super(ModMenus.CAMP.get(), id, inventory, container, 6);
        this.campPos = campPos;
        this.owner = owner;
        this.ally = ally;
        this.village = village;
        this.stats = stats;
        addDataSlots(stats);
    }

    public static CampMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        CampMenu menu = new CampMenu(id, inventory, new SimpleContainer(CampBlockEntity.SIZE), buf.readBlockPos(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), new SimpleContainerData(CampStats.SIZE));
        menu.setScrap(readScrap(buf));
        return menu;
    }

    public static void writeScrap(FriendlyByteBuf buf, java.util.Collection<net.minecraft.resources.ResourceLocation> ids) {
        buf.writeVarInt(ids.size());
        for (net.minecraft.resources.ResourceLocation id : ids) buf.writeResourceLocation(id);
    }

    public static java.util.Set<net.minecraft.resources.ResourceLocation> readScrap(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        java.util.Set<net.minecraft.resources.ResourceLocation> out = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) out.add(buf.readResourceLocation());
        return out;
    }

    public java.util.Set<net.minecraft.resources.ResourceLocation> scrap() {
        return scrap;
    }

    public void setScrap(java.util.Set<net.minecraft.resources.ResourceLocation> scrap) {
        this.scrap = scrap;
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

    public boolean isAlly() {
        return ally;
    }

    public boolean isVillage() {
        return village;
    }
}
