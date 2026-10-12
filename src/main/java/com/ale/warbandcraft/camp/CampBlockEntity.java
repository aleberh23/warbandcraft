package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Cofre del Campamento: acá se guardan los recursos con los que se arman las tropas. */
public class CampBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 54;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    @Nullable
    private UUID owner;

    public CampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAMP.get(), pos, state);
    }

    // ---------- Dueño ----------

    public void setOwner(UUID owner) {
        this.owner = owner;
        this.setChanged();
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    /** Un Campamento sin dueño (p. ej. puesto con comandos) lo puede usar cualquiera. */
    public boolean canBeUsedBy(UUID player) {
        return owner == null || owner.equals(player);
    }

    // ---------- Recursos ----------

    public int count(Item item) {
        int total = 0;
        for (ItemStack stack : items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Saca hasta {@code amount} unidades de {@code item}. Devuelve cuántas sacó. */
    public int take(Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.is(item)) {
                int n = Math.min(remaining, stack.getCount());
                stack.shrink(n);
                remaining -= n;
                if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
            }
        }
        if (remaining != amount) setChanged();
        return amount - remaining;
    }

    /** Guarda un stack en el cofre. Devuelve lo que no entró. */
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, rest)) {
                int max = Math.min(slot.getMaxStackSize(), getMaxStackSize());
                int n = Math.min(rest.getCount(), max - slot.getCount());
                if (n > 0) {
                    slot.grow(n);
                    rest.shrink(n);
                }
            }
        }
        for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, rest.copy());
                rest = ItemStack.EMPTY;
            }
        }
        setChanged();
        return rest;
    }

    // ---------- Container ----------

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.warbandcraft.camp");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return ChestMenu.sixRows(id, inventory, this);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // ---------- Guardado ----------

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        if (owner != null) tag.putUUID("Owner", owner);
    }
}
