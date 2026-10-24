package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.WarbandCraft;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Carga las facciones NPC desde data/&lt;ns&gt;/warband_factions/*.json. Se recarga con /reload.
 * Formato: ver CLAUDE.md.
 */
public class FactionManager extends SimpleJsonResourceReloadListener {
    // GSON antes que INSTANCE (si no, el constructor recibe null).
    private static final Gson GSON = new Gson();
    public static final FactionManager INSTANCE = new FactionManager();

    private Map<ResourceLocation, Faction> factions = Map.of();
    private List<Faction> ordered = List.of();

    private FactionManager() {
        super(GSON, "warband_factions");
    }

    @Nullable
    public Faction get(@Nullable ResourceLocation id) {
        return id == null ? null : factions.get(id);
    }

    public List<Faction> ordered() {
        return ordered;
    }

    /** La facción que ocupa las aldeas de ese bioma, o null si ninguna lo reclama. */
    @Nullable
    public Faction forBiome(Holder<Biome> biome) {
        for (Faction f : ordered) {
            if (f.matches(biome)) return f;
        }
        return null;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Faction> loaded = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                loaded.put(id, parse(id, json.getAsJsonObject()));
            } catch (Exception e) {
                WarbandCraft.LOGGER.error("Facción inválida {}: {}", id, e.getMessage());
            }
        });
        this.factions = Map.copyOf(loaded);
        List<Faction> list = new ArrayList<>();
        for (Faction f : loaded.values()) if (!f.outlaw()) list.add(f);   // H25: los Bandidos no tienen aldeas ni guerras
        list.sort(Comparator.comparingInt(Faction::order).thenComparing(f -> f.id().toString()));
        this.ordered = List.copyOf(list);
        WarbandCraft.LOGGER.info("Warband Craft: {} facciones NPC cargadas", factions.size());

        // En el primer load (arranque) todavía no existe el servidor: lo registra ServerStartedEvent.
        // En un /reload con el servidor ya corriendo, hay que volver a sincronizar acá.
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) RecruitsFactionBridge.sync(server.overworld());
    }

    // ------------------------------------------------------------------ lectura del JSON

    private static Faction parse(ResourceLocation id, JsonObject json) {
        String name = GsonHelper.getAsString(json, "name", id.getPath());
        String ofName = GsonHelper.getAsString(json, "of_name", "de " + name);
        String suffix = GsonHelper.getAsString(json, "soldier_suffix", ofName);
        ChatFormatting color = ChatFormatting.getByName(GsonHelper.getAsString(json, "color", "white"));
        if (color == null || !color.isColor()) color = ChatFormatting.WHITE;
        int armorColor = parseRgb(GsonHelper.getAsString(json, "armor_color", "#A06540"));
        ItemStack banner = parseBanner(id, GsonHelper.getAsJsonObject(json, "banner", new JsonObject()));

        List<ResourceKey<Biome>> biomeIds = new ArrayList<>();
        List<TagKey<Biome>> biomeTags = new ArrayList<>();
        for (JsonElement el : GsonHelper.getAsJsonArray(json, "biomes", new JsonArray())) {
            String s = el.getAsString();
            if (s.startsWith("#")) biomeTags.add(TagKey.create(Registries.BIOME, new ResourceLocation(s.substring(1))));
            else biomeIds.add(ResourceKey.create(Registries.BIOME, new ResourceLocation(s)));
        }

        JsonObject garrison = GsonHelper.getAsJsonObject(json, "garrison", new JsonObject());
        int min = Math.max(1, GsonHelper.getAsInt(garrison, "min", 4));
        int max = Math.max(min, GsonHelper.getAsInt(garrison, "max", 8));

        List<Troop> troops = new ArrayList<>();
        for (JsonElement el : GsonHelper.getAsJsonArray(json, "troops")) {
            troops.add(parseTroop(id, el.getAsJsonObject()));
        }
        if (troops.isEmpty()) throw new IllegalArgumentException("sin tropas");

        int order = GsonHelper.getAsInt(json, "order", 100);
        boolean outlaw = GsonHelper.getAsBoolean(json, "outlaw", false);
        return new Faction(id, name, ofName, suffix, color, armorColor, banner,
                outlaw ? List.of() : List.copyOf(biomeIds), outlaw ? List.of() : List.copyOf(biomeTags), min, max, List.copyOf(troops), order, outlaw);
    }

    private static Troop parseTroop(ResourceLocation factionId, JsonObject json) {
        boolean[] valid = {true};
        String name = GsonHelper.getAsString(json, "name", "Soldado");
        ResourceLocation kit = json.has("kit") ? new ResourceLocation(GsonHelper.getAsString(json, "kit")) : null;
        int tier = GsonHelper.getAsInt(json, "tier", 0);
        int weight = Math.max(0, GsonHelper.getAsInt(json, "weight", 1));

        ResourceLocation entity = null;
        if (json.has("entity")) {
            entity = new ResourceLocation(GsonHelper.getAsString(json, "entity"));
            if (!ForgeRegistries.ENTITY_TYPES.containsKey(entity)) {
                WarbandCraft.LOGGER.warn("Facción {}: la entidad {} no existe, la tropa {} queda desactivada", factionId, entity, name);
                valid[0] = false;
            }
        }
        if (kit == null && entity == null) {
            WarbandCraft.LOGGER.warn("Facción {}: la tropa {} no tiene ni kit ni entity", factionId, name);
            valid[0] = false;
        }

        Map<EquipmentSlot, Item> equipment = new EnumMap<>(EquipmentSlot.class);
        JsonObject eq = GsonHelper.getAsJsonObject(json, "equipment", new JsonObject());
        for (Map.Entry<String, JsonElement> e : eq.entrySet()) {
            EquipmentSlot slot = EquipmentSlot.byName(e.getKey());
            String itemName = com.ale.warbandcraft.kit.ItemChoice.pick(e.getValue());
            // "none" saca esa pieza del kit
            equipment.put(slot, itemName.equals("none") ? Items.AIR : item(factionId, itemName, valid));
        }

        Optional<List<ItemStack>> inventory = Optional.empty();
        if (json.has("inventory")) {
            List<ItemStack> stacks = new ArrayList<>();
            for (JsonElement el : GsonHelper.getAsJsonArray(json, "inventory")) {
                JsonObject o = el.getAsJsonObject();
                stacks.add(new ItemStack(item(factionId, GsonHelper.getAsString(o, "item"), valid), GsonHelper.getAsInt(o, "count", 1)));
            }
            inventory = Optional.of(List.copyOf(stacks));
        }
        Optional<Boolean> horse = json.has("horse") ? Optional.of(GsonHelper.getAsBoolean(json, "horse")) : Optional.empty();

        return new Troop(name, kit, tier, weight, entity, Map.copyOf(equipment), inventory, horse, valid[0]);
    }

    private static Item item(ResourceLocation factionId, String name, boolean[] valid) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(name));
        if (item == null || item == Items.AIR) {
            WarbandCraft.LOGGER.warn("Facción {}: el ítem {} no existe, esa tropa queda desactivada", factionId, name);
            valid[0] = false;
            return Items.AIR;
        }
        return item;
    }

    private static int parseRgb(String s) {
        try {
            return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xA06540;
        }
    }

    /**
     * {"base": "blue", "patterns": [{"pattern": "minecraft:cross", "color": "yellow"}]}
     * Los patrones son los ids del registro de Minecraft (minecraft:stripe_center, minecraft:border...).
     */
    private static ItemStack parseBanner(ResourceLocation factionId, JsonObject json) {
        DyeColor base = DyeColor.byName(GsonHelper.getAsString(json, "base", "white"), DyeColor.WHITE);
        ItemStack stack = new ItemStack(BannerBlock.byColor(base).asItem());
        BannerPattern.Builder builder = new BannerPattern.Builder();
        int count = 0;
        for (JsonElement el : GsonHelper.getAsJsonArray(json, "patterns", new JsonArray())) {
            JsonObject o = el.getAsJsonObject();
            ResourceLocation patternId = new ResourceLocation(GsonHelper.getAsString(o, "pattern"));
            DyeColor color = DyeColor.byName(GsonHelper.getAsString(o, "color", "white"), DyeColor.WHITE);
            Optional<Holder.Reference<BannerPattern>> pattern =
                    BuiltInRegistries.BANNER_PATTERN.getHolder(ResourceKey.create(Registries.BANNER_PATTERN, patternId));
            if (pattern.isEmpty()) {
                WarbandCraft.LOGGER.warn("Facción {}: el patrón de estandarte {} no existe", factionId, patternId);
                continue;
            }
            builder.addPattern(pattern.get(), color);
            count++;
        }
        if (count > 0) {
            CompoundTag blockEntity = stack.getOrCreateTagElement("BlockEntityTag");
            blockEntity.put("Patterns", builder.toListTag());
        }
        return stack;
    }
}
