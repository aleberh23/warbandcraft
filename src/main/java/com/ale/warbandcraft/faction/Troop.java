package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Un tipo de soldado de una facción. Parte de una clase del Campamento ({@code kit}) y le cambia lo que
 * haga falta: así "Hachero del Norte" es un Infante con hacha en vez de espada.
 *
 * Los campos opcionales vacíos significan "usar lo del kit".
 *
 * @param name      nombre de la tropa ("Hachero")
 * @param kit       clase base (warbandcraft:infante); puede faltar si se da entity + equipment
 * @param tier      nivel del kit (0 = Básico)
 * @param weight    qué tan seguido aparece
 * @param entity    entidad de Recruits a usar en lugar de la del kit (p. ej. recruits:nomad)
 * @param equipment piezas que reemplazan o se agregan a las del kit; AIR = sacar esa pieza
 * @param inventory si está, reemplaza la mochila del kit (flechas...)
 * @param horse     si está, reemplaza si va a caballo
 * @param valid     false si algún ítem o entidad del JSON no existe
 */
public record Troop(String name, @Nullable ResourceLocation kit, int tier, int weight,
                    @Nullable ResourceLocation entity, Map<EquipmentSlot, Item> equipment,
                    Optional<List<ItemStack>> inventory, Optional<Boolean> horse, boolean valid) {

    /** La tropa lista para aparecer: entidad, equipo, mochila y caballo. */
    public record Resolved(ResourceLocation entity, Map<EquipmentSlot, Item> equipment, List<ItemStack> inventory, boolean horse) {}

    /** Combina kit + cambios. Null si no se puede armar (falta el kit, un ítem o la entidad). */
    @Nullable
    public Resolved resolve() {
        if (!valid) return null;
        ResourceLocation type = entity;
        Map<EquipmentSlot, Item> gear = new EnumMap<>(EquipmentSlot.class);
        List<ItemStack> backpack = List.of();
        boolean mounted = false;

        if (kit != null) {
            ClassKit classKit = KitManager.INSTANCE.get(kit);
            if (classKit == null || classKit.tiers().isEmpty()) return null;
            ClassKit.Tier t = classKit.tiers().get(Math.min(Math.max(tier, 0), classKit.tiers().size() - 1));
            if (!t.valid()) return null;
            if (type == null) type = classKit.entity();
            gear.putAll(t.equipment());
            backpack = t.inventory();
            mounted = t.horse();
        }
        if (type == null) return null;

        equipment.forEach((slot, item) -> {
            if (item == Items.AIR) gear.remove(slot);
            else gear.put(slot, item);
        });
        if (inventory.isPresent()) backpack = inventory.get();
        if (horse.isPresent()) mounted = horse.get();
        return new Resolved(type, Map.copyOf(gear), backpack, mounted);
    }
}
