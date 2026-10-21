package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.init.ModMenus;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pestaña "Envíos" del Campamento (H13): una caja de carga de 18 espacios donde se ponen los ítems a mandar,
 * más los datos para elegir destino y soldados del barracón (eso lo maneja la pantalla y viaja en
 * {@link com.ale.warbandcraft.network.SendShipmentMessage}). Lo que queda en la caja al cerrar vuelve al jugador.
 */
public class ShipmentMenu extends AbstractContainerMenu {
    public static final int CARGO = 18;

    // Posiciones dentro de la pantalla (las usa también ShipmentScreen)
    public static final int WIDTH = 300, HEIGHT = 268;
    public static final int CARGO_X = 69, CARGO_Y = 124;
    public static final int INV_X = 69, INV_Y = 188;

    /** Un destino posible: si es un Campamento (acepta ítems) y cuánto lugar queda en su barracón. */
    public record Dest(BlockPos pos, Component name, int ticks, boolean camp, int free) {}

    /** Algo que va en camino desde cualquiera de tus puntos. */
    public record Transit(Component dest, int ticksLeft, int soldiers, int stacks) {}

    /** Todo lo que la pantalla necesita saber. */
    public record Info(BlockPos camp, Component name, List<Dest> dests, List<OpenBarracksMessage.Row> rows, List<Transit> transit) {
        public static Info of(ServerPlayer player, CampBlockEntity camp) {
            ServerLevel level = player.serverLevel();
            UUID owner = player.getUUID();
            BlockPos pos = camp.getBlockPos();
            List<Dest> dests = new ArrayList<>();
            for (Routes.Destination d : Routes.reachable(level, owner, pos)) {
                dests.add(new Dest(d.node().pos(), d.node().name(), d.ticks(), d.node().kind() == Routes.Kind.CAMP,
                        Routes.freeAt(level, owner, d.node().pos())));
            }
            List<Transit> transit = new ArrayList<>();
            long now = level.getGameTime();
            for (RouteData.Shipment s : RouteData.get(level).shipmentsOf(owner)) {
                Routes.Node to = Routes.node(level, owner, s.to());
                Component name = to != null ? to.name() : Component.literal(s.to().toShortString());
                transit.add(new Transit(name, (int) Math.max(0, s.arriveTick() - now), s.soldiers().size(), s.items().size()));
            }
            List<OpenBarracksMessage.Row> rows = OpenBarracksMessage.of(player, camp).rows;
            return new Info(pos, Component.literal(camp.nameString()), dests, rows, transit);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBlockPos(camp);
            buf.writeComponent(name);
            buf.writeCollection(dests, (b, d) -> {
                b.writeBlockPos(d.pos());
                b.writeComponent(d.name());
                b.writeVarInt(d.ticks());
                b.writeBoolean(d.camp());
                b.writeVarInt(d.free());
            });
            buf.writeCollection(rows, (b, r) -> {
                b.writeUtf(r.key());
                b.writeComponent(r.label());
                b.writeVarInt(r.count());
                b.writeVarInt(r.guards());
            });
            buf.writeCollection(transit, (b, t) -> {
                b.writeComponent(t.dest());
                b.writeVarInt(t.ticksLeft());
                b.writeVarInt(t.soldiers());
                b.writeVarInt(t.stacks());
            });
        }

        public static Info read(FriendlyByteBuf buf) {
            BlockPos camp = buf.readBlockPos();
            Component name = buf.readComponent();
            List<Dest> dests = buf.readList(b -> new Dest(b.readBlockPos(), b.readComponent(), b.readVarInt(), b.readBoolean(), b.readVarInt()));
            List<OpenBarracksMessage.Row> rows = buf.readList(b -> new OpenBarracksMessage.Row(b.readUtf(), b.readComponent(), b.readVarInt(), b.readVarInt()));
            List<Transit> transit = buf.readList(b -> new Transit(b.readComponent(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
            return new Info(camp, name, dests, rows, transit);
        }
    }

    private final Info info;
    private final SimpleContainer cargo = new SimpleContainer(CARGO);

    public ShipmentMenu(int id, Inventory inventory, Info info) {
        super(ModMenus.SHIPMENTS.get(), id);
        this.info = info;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(cargo, col + row * 9, CARGO_X + col * 18, CARGO_Y + row * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + col + row * 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INV_X + col * 18, INV_Y + 58));
    }

    public static ShipmentMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        try {
            return new ShipmentMenu(id, inventory, Info.read(buf));
        } catch (RuntimeException e) {
            // Forge se traga los errores al abrir pantallas: sin esto la pestaña "no abre" sin dejar rastro
            com.ale.warbandcraft.WarbandCraft.LOGGER.error("No se pudo leer la pestaña Envíos", e);
            throw e;
        }
    }

    public Info info() {
        return info;
    }

    /** Lo que hay en la caja de carga. */
    public List<ItemStack> cargoItems() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < CARGO; i++) if (!cargo.getItem(i).isEmpty()) out.add(cargo.getItem(i));
        return out;
    }

    /** El envío salió: la caja queda vacía. */
    public void clearCargo() {
        cargo.clearContent();
        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved = index < CARGO ? moveItemStackTo(stack, CARGO, slots.size(), true) : moveItemStackTo(stack, 0, CARGO, false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        BlockPos c = info.camp();
        return player.distanceToSqr(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5) <= CampTabs.REACH * CampTabs.REACH;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, cargo);   // lo que no se mandó vuelve al jugador
    }
}
