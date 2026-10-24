package com.ale.warbandcraft.kit;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Una clase de soldado (Infante, Arquero...) cargada desde data/<ns>/warband_kits/*.json.
 *
 * @param id     id del archivo, p. ej. warbandcraft:arquero
 * @param name   nombre que ve el jugador
 * @param entity tipo de entidad de Recruits en que se convierte el soldado (recruits:bowman, ...)
 * @param order  orden en el Bastón de Mariscal
 * @param tiers  niveles de equipo, de básico a élite
 */
public record ClassKit(ResourceLocation id, String name, ResourceLocation entity, int order, List<Tier> tiers) {

    /**
     * Un nivel de equipo. Lo que se le pone al soldado es lo que se consume del Campamento,
     * más {@code extraCost} (p. ej. heno para el caballo).
     *
     * @param valid false si algún ítem no existe (p. ej. un mod de armas que no está instalado)
     */
    public record Tier(String name, int minLevel, Map<EquipmentSlot, Item> equipment, List<ItemStack> inventory,
                       List<ItemStack> extraCost, boolean horse, boolean valid) {

        /** Todo lo que este nivel saca del Campamento, agrupado por ítem. */
        public Map<Item, Integer> cost() {
            Map<Item, Integer> cost = new LinkedHashMap<>();
            equipment.values().forEach(item -> cost.merge(item, 1, Integer::sum));
            inventory.forEach(stack -> cost.merge(stack.getItem(), stack.getCount(), Integer::sum));
            extraCost.forEach(stack -> cost.merge(stack.getItem(), stack.getCount(), Integer::sum));
            return cost;
        }
    }

    /** El primer nivel que se puede usar (para mostrar qué falta). */
    public Tier firstValidTier() {
        for (Tier t : tiers) {
            if (t.valid()) return t;
        }
        return null;
    }

    public List<Tier> validTiers() {
        List<Tier> out = new ArrayList<>();
        for (Tier t : tiers) if (t.valid()) out.add(t);
        return out;
    }
}
