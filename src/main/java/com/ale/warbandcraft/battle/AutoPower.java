package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Cuánto vale un soldado en una autoresolución de asedio según su equipo y su clase (pedido de Ale). Un soldado
 * "normal" (cota de cuero + algo, espada de hierro) vale 1; mejor armadura o arma, más. En un asedio se pelea a pie:
 * el caballo no suma. Arqueros: defendiendo tiran desde los muros (valen más); atacando, contra muros (valen menos).
 */
public final class AutoPower {
    /** Armadura (defensa + la mitad de la dureza) de un soldado "normal": vale ×1. */
    public static double REF_ARMOR = 8;
    /** Cada tantos puntos de armadura, +1 al multiplicador (antes de normalizar). */
    public static double ARMOR_SCALE = 15;
    /** Daño del arma de un soldado "normal" (espada de hierro = 6): vale ×1. */
    public static double REF_DAMAGE = 6;
    /** Cuánto pesa el daño del arma: multiplicador = (1 + daño / esto) normalizado. */
    public static double DAMAGE_SCALE = 6;
    /** Arquero o ballestero defendiendo un asedio (tira desde arriba). */
    public static double RANGED_DEFENDING = 1.2;
    /** Arquero o ballestero atacando un asedio (tira contra muros). */
    public static double RANGED_ATTACKING = 0.7;
    /** Con escudo en la mano secundaria. */
    public static double SHIELD = 1.1;
    /** Tope de lo que puede valer un soldado (y piso), para que un número raro no decida solo. */
    public static double MAX = 4, MIN = 0.3;

    private AutoPower() {}

    /** Una ficha del barracón (lee el equipo puesto de su NBT; el nivel lo suma quien llama). */
    public static double sheet(BarracksData.Soldier s, boolean defending) {
        return clamp(gear(equipmentOf(s.entity), defending));
    }

    /** Lo que vale en promedio un soldado de esa facción (sus tropas por peso). 1 si no se conoce. */
    public static double faction(@Nullable ResourceLocation id, boolean defending) {
        Faction f = id == null ? null : FactionManager.INSTANCE.get(id);
        if (f == null) return 1;
        double sum = 0;
        int weight = 0;
        for (Troop t : f.troops()) {
            Troop.Resolved r = t.resolve();
            if (r == null || t.weight() <= 0) continue;
            Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
            for (Map.Entry<EquipmentSlot, Item> e : r.equipment().entrySet()) gear.put(e.getKey(), new ItemStack(e.getValue()));
            sum += clamp(gear(gear, defending)) * t.weight();
            weight += t.weight();
        }
        return weight == 0 ? 1 : sum / weight;
    }

    /** Equipo → multiplicador (sin nivel). */
    public static double gear(Map<EquipmentSlot, ItemStack> gear, boolean defending) {
        double armor = 0;
        for (ItemStack st : gear.values()) {
            if (st.getItem() instanceof ArmorItem a) armor += a.getDefense() + a.getToughness() / 2.0;
        }
        double p = (1 + armor / ARMOR_SCALE) / (1 + REF_ARMOR / ARMOR_SCALE);
        ItemStack main = gear.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        if (main.getItem() instanceof BowItem || main.getItem() instanceof CrossbowItem) {
            p *= defending ? RANGED_DEFENDING : RANGED_ATTACKING;
        } else {
            p *= (1 + damageOf(main) / DAMAGE_SCALE) / (1 + REF_DAMAGE / DAMAGE_SCALE);
        }
        if (gear.getOrDefault(EquipmentSlot.OFFHAND, ItemStack.EMPTY).getItem() instanceof ShieldItem) p *= SHIELD;
        return p;
    }

    /** Daño de un golpe con ese ítem en la mano (1 = puño). */
    private static double damageOf(ItemStack st) {
        double d = 1;
        if (st.isEmpty()) return d;
        for (AttributeModifier m : st.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
            if (m.getOperation() == AttributeModifier.Operation.ADDITION) d += m.getAmount();
        }
        return d;
    }

    /** Equipo puesto según el NBT de Recruits ("Items", espacios 0-5: cabeza, pecho, piernas, pies, secundaria, principal). */
    public static Map<EquipmentSlot, ItemStack> equipmentOf(CompoundTag entity) {
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND};
        for (Tag x : entity.getList("Items", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            int slot = t.getByte("Slot") & 255;
            if (slot < slots.length) out.put(slots[slot], ItemStack.of(t));
        }
        return out;
    }

    private static double clamp(double p) {
        return Math.max(MIN, Math.min(MAX, p));
    }
}
