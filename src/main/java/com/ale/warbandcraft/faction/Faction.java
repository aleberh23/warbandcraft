package com.ale.warbandcraft.faction;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Una facción NPC (Reino de Valdren, Clanes del Norte...) cargada desde data/&lt;ns&gt;/warband_factions/*.json.
 *
 * @param id            id del archivo, p. ej. warbandcraft:valdren
 * @param name          nombre completo ("Reino de Valdren")
 * @param ofName        con preposición, para frases ("del Reino de Valdren")
 * @param soldierSuffix lo que va después del nombre de la tropa ("de Valdren" → "Caballero de Valdren")
 * @param color         color del nombre y del equipo del marcador
 * @param armorColor    color con que se tiñe la armadura de cuero (RGB)
 * @param banner        estandarte de la facción (reemplaza cualquier estandarte de los kits)
 * @param biomeIds      biomas exactos donde la facción ocupa aldeas
 * @param biomeTags     etiquetas de bioma (#minecraft:is_taiga...)
 * @param garrisonMin   guarnición mínima por aldea
 * @param garrisonMax   guarnición máxima por aldea
 * @param troops        mezcla de tropas, con peso
 * @param order         prioridad cuando un bioma coincide con varias facciones (menor gana)
 * @param outlaw        H25: forajidos (los Bandidos): en guerra con todos, sin aldeas, fuera de {@link FactionManager#ordered()}
 */
public record Faction(ResourceLocation id, String name, String ofName, String soldierSuffix,
                      ChatFormatting color, int armorColor, ItemStack banner,
                      List<ResourceKey<Biome>> biomeIds, List<TagKey<Biome>> biomeTags,
                      int garrisonMin, int garrisonMax, List<Troop> troops, int order, boolean outlaw) {

    /** Nombre del equipo del marcador (scoreboard) que agrupa a sus soldados. */
    public String teamName() {
        return "wb_" + id.getPath();
    }

    public boolean matches(Holder<Biome> biome) {
        for (ResourceKey<Biome> key : biomeIds) if (biome.is(key)) return true;
        for (TagKey<Biome> tag : biomeTags) if (biome.is(tag)) return true;
        return false;
    }

    /** Elige una tropa al azar según los pesos (solo entre las que se pueden armar). */
    @Nullable
    public Troop pickTroop(RandomSource random) {
        int total = 0;
        for (Troop t : troops) if (t.resolve() != null) total += t.weight();
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (Troop t : troops) {
            if (t.resolve() == null) continue;
            roll -= t.weight();
            if (roll < 0) return t;
        }
        return null;
    }

    /** Estandarte y color de cuero de la facción. */
    public FactionStyle style() {
        return new FactionStyle(banner, armorColor);
    }

    /** Aplica los colores de la facción a una pieza: tiñe el cuero y cambia estandartes por el propio. */
    public ItemStack style(ItemStack stack) {
        return style().apply(stack);
    }
}
