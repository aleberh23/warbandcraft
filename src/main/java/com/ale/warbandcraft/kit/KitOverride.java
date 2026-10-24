package com.ale.warbandcraft.kit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * El equipo propio que un jugador le puso a un nivel de una clase (H6, pestaña Kits del Campamento).
 * Solo tipo de ítem y cantidad: sin encantamientos ni nombres, así la plantilla no puede "fabricar"
 * equipo mejor que el que se cobra.
 */
public record KitOverride(Map<EquipmentSlot, Item> equipment, List<ItemStack> inventory) {

    /** Orden de los espacios de equipo en el editor (el mismo que el inventario de Recruits). */
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND
    };
    public static final int BACKPACK_SIZE = 9;

    public KitOverride {
        equipment = Map.copyOf(equipment);
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack s : inventory) if (!s.isEmpty()) copy.add(new ItemStack(s.getItem(), s.getCount()));
        inventory = List.copyOf(copy);
    }

    public static KitOverride of(ClassKit.Tier tier) {
        return new KitOverride(tier.equipment(), tier.inventory());
    }

    /** Mismo equipo y misma mochila (en el mismo orden) que el nivel. */
    public boolean sameAs(ClassKit.Tier tier) {
        if (!equipment.equals(tier.equipment())) return false;
        if (inventory.size() != tier.inventory().size()) return false;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack a = inventory.get(i), b = tier.inventory().get(i);
            if (!a.is(b.getItem()) || a.getCount() != b.getCount()) return false;
        }
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag eq = new CompoundTag();
        equipment.forEach((slot, item) -> eq.putString(slot.getName(), ForgeRegistries.ITEMS.getKey(item).toString()));
        tag.put("equipment", eq);
        ListTag inv = new ListTag();
        for (ItemStack s : inventory) {
            CompoundTag t = new CompoundTag();
            t.putString("item", ForgeRegistries.ITEMS.getKey(s.getItem()).toString());
            t.putInt("count", s.getCount());
            inv.add(t);
        }
        tag.put("inventory", inv);
        return tag;
    }

    /** Un ítem que ya no existe (se sacó un mod) se descarta en silencio. */
    public static KitOverride load(CompoundTag tag) {
        Map<EquipmentSlot, Item> equipment = new EnumMap<>(EquipmentSlot.class);
        CompoundTag eq = tag.getCompound("equipment");
        for (String key : eq.getAllKeys()) {
            Item item = item(eq.getString(key));
            if (item != Items.AIR) equipment.put(EquipmentSlot.byName(key), item);
        }
        List<ItemStack> inventory = new ArrayList<>();
        for (Tag t : tag.getList("inventory", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Item item = item(c.getString("item"));
            if (item != Items.AIR) inventory.add(new ItemStack(item, Math.max(1, c.getInt("count"))));
        }
        return new KitOverride(equipment, inventory);
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return item == null ? Items.AIR : item;
    }
}
