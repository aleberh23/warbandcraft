package com.ale.warbandcraft.market;

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
import java.util.Map;

/**
 * H24: precios de partida del mercado, de data/&lt;ns&gt;/warband_prices/*.json
 * ({"minecraft:bread": {"lot": 16, "price": 3}, ...}; se recarga con /reload). Precio = esmeraldas por lote.
 * Lo que no está en la tabla arranca con {@link Market#DEFAULT_LOT} y {@link Market#DEFAULT_PRICE}; el dueño cambia el precio
 * en la pantalla Mercado (el lote queda fijo).
 */
public class Prices extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    public static final Prices INSTANCE = new Prices();

    /** {lote, precio} por ítem. */
    private Map<Item, int[]> table = Map.of();

    private Prices() {
        super(GSON, "warband_prices");
    }

    /** Cuántos van en cada venta (las herramientas, armas y armaduras de a una). */
    public int lot(Item item) {
        int[] p = table.get(item);
        if (p != null) return p[0];
        return new net.minecraft.world.item.ItemStack(item).getMaxStackSize() == 1 ? 1 : Math.min(Market.DEFAULT_LOT, 64);
    }

    /** Esmeraldas por lote de partida. */
    public int price(Item item) {
        int[] p = table.get(item);
        return p != null ? p[1] : Market.DEFAULT_PRICE;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<Item, int[]> loaded = new HashMap<>();
        files.forEach((file, json) -> {
            try {
                json.getAsJsonObject().entrySet().forEach(e -> {
                    Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
                    if (item == null || item == Items.AIR || !e.getValue().isJsonObject()) return;
                    var o = e.getValue().getAsJsonObject();
                    int lot = o.has("lot") ? o.get("lot").getAsInt() : Market.DEFAULT_LOT;
                    int price = o.has("price") ? o.get("price").getAsInt() : Market.DEFAULT_PRICE;
                    loaded.put(item, new int[]{Math.max(1, Math.min(64, lot)), Math.max(1, Math.min(Market.MAX_PRICE, price))});
                });
            } catch (RuntimeException ex) {
                com.ale.warbandcraft.WarbandCraft.LOGGER.warn("Precios del mercado mal escritos en {}: {}", file, ex.getMessage());
            }
        });
        table = loaded;
    }
}
