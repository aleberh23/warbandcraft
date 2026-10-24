package com.ale.warbandcraft.kit;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModItems;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Armamento (H10): un ítem que reemplaza piezas de metal al armar. Cada pieza vale una cantidad de
 * armamento, según data/&lt;ns&gt;/warband_armament/*.json ({"minecraft:iron_sword": 2, ...}; se recarga
 * con /reload). Lo que no está en la tabla no se puede reemplazar.
 */
public class Armament extends SimpleJsonResourceReloadListener {
    // GSON antes que INSTANCE (igual que KitManager).
    private static final Gson GSON = new Gson();
    public static final Armament INSTANCE = new Armament();

    private Map<Item, Integer> values = Map.of();
    /** H19: lo que no es de metal, solo para el nivel básico: {valor, cada cuántos} (16 flechas = 2 → {2, 16}). */
    private Map<Item, int[]> basicValues = Map.of();

    private Armament() {
        super(GSON, "warband_armament");
    }

    /** Cuánto armamento vale una pieza (0 = no se puede reemplazar). */
    public int value(Item item) {
        return values.getOrDefault(item, 0);
    }

    /** ¿Se puede cubrir con armamento? ({@code basic} = nivel básico: también lo que no es de metal, H19). */
    public boolean coverable(Item item, boolean basic) {
        return value(item) > 0 || (basic && basicValues.containsKey(item));
    }

    /** Armamento que cuestan {@code n} de {@code item} que faltan (0 = no se puede cubrir). */
    public int cost(Item item, int n, boolean basic) {
        int v = value(item);
        if (v > 0) return n * v;
        int[] b = basic ? basicValues.get(item) : null;
        if (b == null) return 0;
        return (n * b[0] + b[1] - 1) / b[1];
    }

    public static Item item() {
        return ModItems.ARMAMENT.get();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<Item, Integer> loaded = new HashMap<>();
        Map<Item, int[]> basic = new HashMap<>();
        files.forEach((file, json) -> {
            try {
                json.getAsJsonObject().entrySet().forEach(e -> {
                    Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                    if (item == null || item == Items.AIR) return;
                    if (e.getValue().isJsonObject()) {   // H19: {"value": 2, "per": 16, "armament_only": true}
                        var o = e.getValue().getAsJsonObject();
                        int v = o.has("value") ? o.get("value").getAsInt() : 0;
                        int per = o.has("per") ? Math.max(1, o.get("per").getAsInt()) : 1;
                        boolean only = o.has("armament_only") && o.get("armament_only").getAsBoolean();
                        if (v <= 0) return;
                        if (only) basic.put(item, new int[]{v, per});
                        else loaded.put(item, v);
                        return;
                    }
                    int v = e.getValue().getAsInt();
                    if (v > 0) loaded.put(item, v);
                });
            } catch (Exception ex) {
                WarbandCraft.LOGGER.error("Tabla de armamento inválida {}: {}", file, ex.getMessage());
            }
        });
        values = Map.copyOf(loaded);
        basicValues = Map.copyOf(basic);
        WarbandCraft.LOGGER.info("Warband Craft: {} piezas con valor de armamento", values.size());
    }

    // ------------------------------------------------------------------ cobrar

    /**
     * Cómo se pagaría {@code cost} con {@code pool}: primero piezas de verdad, y lo que falte de la tabla con
     * armamento. {@code missing} = lo que falta (sin contar lo que cubre el armamento); {@code armament} = cuánto
     * armamento se usaría; {@code ok} = alcanza.
     */
    public record Plan(boolean ok, int armament, int armamentAvailable, Map<Item, Integer> shortfall) {}

    public Plan plan(Map<Item, Integer> pool, Map<Item, Integer> cost) {
        return plan(pool, cost, false);
    }

    /** {@code basic} = nivel básico de una clase: también se cubre lo que no es de metal (más caro, H19). */
    public Plan plan(Map<Item, Integer> pool, Map<Item, Integer> cost, boolean basic) {
        Item arm = item();
        int available = pool.getOrDefault(arm, 0) - cost.getOrDefault(arm, 0);
        boolean ok = available >= 0;
        int needed = 0;
        Map<Item, Integer> shortfall = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            if (e.getKey() == arm) continue;
            int missing = e.getValue() - pool.getOrDefault(e.getKey(), 0);
            if (missing <= 0) continue;
            shortfall.put(e.getKey(), missing);
            int c = cost(e.getKey(), missing, basic);
            if (c <= 0) ok = false;
            else needed += c;
        }
        if (needed > available) ok = false;
        return new Plan(ok, needed, Math.max(0, available), shortfall);
    }

    /** Lo que hay en el almacén del Campamento, por ítem. */
    public static Map<Item, Integer> poolOf(CampBlockEntity camp) {
        Map<Item, Integer> pool = new HashMap<>();
        camp.forEachStack(s -> pool.merge(s.getItem(), s.getCount(), Integer::sum));
        return pool;
    }

    /**
     * Cobra {@code cost} del almacén: piezas de verdad y, lo que falte, en armamento. Devuelve el armamento que
     * no se pudo cobrar (0 = todo bien; se llama después de {@link #plan} así que normalmente es 0).
     */
    public int pay(CampBlockEntity camp, Map<Item, Integer> cost) {
        return pay(camp, cost, false);
    }

    public int pay(CampBlockEntity camp, Map<Item, Integer> cost, boolean basic) {
        Item arm = item();
        int armament = cost.getOrDefault(arm, 0);
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            if (e.getKey() == arm) continue;
            int missing = e.getValue() - camp.take(e.getKey(), e.getValue());
            if (missing > 0) armament += cost(e.getKey(), missing, basic);
        }
        return armament - camp.take(arm, armament);
    }
}
