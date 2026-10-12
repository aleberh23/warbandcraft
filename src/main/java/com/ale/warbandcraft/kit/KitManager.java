package com.ale.warbandcraft.kit;

import com.ale.warbandcraft.WarbandCraft;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga las clases de soldado desde data/&lt;ns&gt;/warband_kits/*.json.
 * Se recarga con /reload, así se pueden ajustar recetas sin reiniciar el juego.
 */
public class KitManager extends SimpleJsonResourceReloadListener {
    public static final KitManager INSTANCE = new KitManager();
    private static final Gson GSON = new Gson();

    private Map<ResourceLocation, ClassKit> kits = Map.of();
    private List<ClassKit> ordered = List.of();

    private KitManager() {
        super(GSON, "warband_kits");
    }

    @Nullable
    public ClassKit get(ResourceLocation id) {
        return kits.get(id);
    }

    public List<ClassKit> ordered() {
        return ordered;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, ClassKit> loaded = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                loaded.put(id, parse(id, json.getAsJsonObject()));
            } catch (Exception e) {
                WarbandCraft.LOGGER.error("Clase inválida {}: {}", id, e.getMessage());
            }
        });
        this.kits = Map.copyOf(loaded);
        List<ClassKit> list = new ArrayList<>(loaded.values());
        list.sort(Comparator.comparingInt(ClassKit::order).thenComparing(k -> k.id().toString()));
        this.ordered = List.copyOf(list);
        WarbandCraft.LOGGER.info("Warband Craft: {} clases de soldado cargadas", kits.size());
    }

    private static ClassKit parse(ResourceLocation id, JsonObject json) {
        String name = GsonHelper.getAsString(json, "name", id.getPath());
        ResourceLocation entity = new ResourceLocation(GsonHelper.getAsString(json, "entity"));
        int order = GsonHelper.getAsInt(json, "order", 100);

        List<ClassKit.Tier> tiers = new ArrayList<>();
        JsonArray tierArray = GsonHelper.getAsJsonArray(json, "tiers");
        for (int i = 0; i < tierArray.size(); i++) {
            tiers.add(parseTier(id, i, tierArray.get(i).getAsJsonObject()));
        }
        if (tiers.isEmpty()) throw new IllegalArgumentException("sin niveles");
        return new ClassKit(id, name, entity, order, List.copyOf(tiers));
    }

    private static ClassKit.Tier parseTier(ResourceLocation kitId, int index, JsonObject json) {
        boolean[] valid = {true};
        String name = GsonHelper.getAsString(json, "name", "Nivel " + (index + 1));
        int minLevel = GsonHelper.getAsInt(json, "min_level", 0);

        Map<EquipmentSlot, Item> equipment = new EnumMap<>(EquipmentSlot.class);
        JsonObject eq = GsonHelper.getAsJsonObject(json, "equipment", new JsonObject());
        for (Map.Entry<String, JsonElement> e : eq.entrySet()) {
            EquipmentSlot slot = EquipmentSlot.byName(e.getKey());
            Item item = item(kitId, e.getValue().getAsString(), valid);
            equipment.put(slot, item);
        }

        List<ItemStack> inventory = stacks(kitId, GsonHelper.getAsJsonArray(json, "inventory", new JsonArray()), valid);
        List<ItemStack> extra = stacks(kitId, GsonHelper.getAsJsonArray(json, "extra_cost", new JsonArray()), valid);
        boolean horse = GsonHelper.getAsBoolean(json, "horse", false);

        return new ClassKit.Tier(name, minLevel, Map.copyOf(equipment), List.copyOf(inventory), List.copyOf(extra), horse, valid[0]);
    }

    private static List<ItemStack> stacks(ResourceLocation kitId, JsonArray array, boolean[] valid) {
        List<ItemStack> out = new ArrayList<>();
        for (JsonElement el : array) {
            JsonObject o = el.getAsJsonObject();
            Item item = item(kitId, GsonHelper.getAsString(o, "item"), valid);
            out.add(new ItemStack(item, GsonHelper.getAsInt(o, "count", 1)));
        }
        return out;
    }

    /** Un ítem que no existe invalida el nivel (no rompe la carga): así los kits pueden usar mods opcionales. */
    private static Item item(ResourceLocation kitId, String name, boolean[] valid) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(name));
        if (item == null || item == Items.AIR) {
            WarbandCraft.LOGGER.warn("Clase {}: el ítem {} no existe, ese nivel queda desactivado", kitId, name);
            valid[0] = false;
            return Items.AIR;
        }
        return item;
    }
}
