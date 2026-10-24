package com.ale.warbandcraft.kit;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * H26: una pieza de un kit o de una tropa puede ser una lista ["magistuarmory:iron_pike", "minecraft:iron_axe"]:
 * se usa la primera que exista. Así, sin Epic Knights, cae a lo de vanilla en vez de apagar el nivel.
 */
public final class ItemChoice {
    public static String pick(JsonElement value) {
        if (!value.isJsonArray()) return value.getAsString();
        String last = "minecraft:air";
        for (JsonElement el : value.getAsJsonArray()) {
            last = el.getAsString();
            if (last.equals("none")) return last;
            ResourceLocation id = ResourceLocation.tryParse(last);
            if (id != null && ForgeRegistries.ITEMS.containsKey(id)) return last;
        }
        return last;
    }

    private ItemChoice() {}
}
