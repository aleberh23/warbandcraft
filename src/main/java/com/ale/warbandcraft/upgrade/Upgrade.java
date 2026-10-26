package com.ale.warbandcraft.upgrade;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Las mejoras de una aldea o castillo tuyo (H12). Cada una cuesta esmeraldas (desde H22, además de los materiales de
 * {@link BuildCost}), pide un nivel mínimo del constructor
 * (regla de MineColonies) y a veces otra mejora antes. Los números de los efectos están en {@link Upgrades}.
 */
public enum Upgrade {
    // id, costo, constructor mínimo, requisito, aldea, castillo
    BUILDER_1("builder_1", 8, 0, null, true, true),
    BUILDER_2("builder_2", 16, 1, BUILDER_1, true, true),
    BUILDER_3("builder_3", 32, 2, BUILDER_2, true, true),
    PALISADE("palisade", 12, 1, null, true, false),
    WALL("wall", 32, 2, PALISADE, true, false),
    TOWERS("towers", 24, 2, PALISADE, true, false),
    TOWN("town", 24, 2, PALISADE, true, false),
    CITY("city", 48, 3, TOWN, true, false),
    BARRACKS("barracks", 16, 1, null, true, true),
    SMITHY("smithy", 16, 2, null, true, true),
    MARKET("market", 16, 2, null, true, true),
    /** H24: Plaza de mercado y Lonja (el Mercado de H12 es el nivel 1), mismo terreno. */
    MARKET_2("market_2", 32, 2, MARKET, true, true),
    MARKET_3("market_3", 64, 3, MARKET_2, true, true),
    GRANARY("granary", 12, 1, null, true, true),
    /** H22: capilla (popularidad). Solo aldeas. */
    CHAPEL("chapel", 16, 1, null, true, false),
    /** Pedido de Ale: caseta de mantenimiento (el trabajador de Mantenimiento embellece la aldea). Solo aldeas. */
    MAINTENANCE("maintenance", 10, 1, null, true, false),
    /** Pedido de Ale: cocina (el cocinero cocina lo del almacén con poca madera, {@link com.ale.warbandcraft.village.Kitchen}). Solo aldeas. */
    KITCHEN("kitchen", 12, 1, null, true, false),
    /** H19: taberna (llegan reclutas) en 3 niveles, mismo terreno. Solo aldeas. */
    TAVERN_1("tavern_1", 12, 1, null, true, false),
    TAVERN_2("tavern_2", 24, 2, TAVERN_1, true, false),
    TAVERN_3("tavern_3", 48, 3, TAVERN_2, true, false),
    /** H19: campo de entrenamiento (más lugar y suben de nivel en el barracón) en 3 niveles, mismo terreno. */
    TRAINING_1("training_1", 8, 1, BARRACKS, true, true),
    TRAINING_2("training_2", 16, 2, TRAINING_1, true, true),
    TRAINING_3("training_3", 32, 3, TRAINING_2, true, true),
    /** Una casa con 2 camas: no va en la lista de mejoras, se repite (pestaña Casas del modo Gestión). */
    HOUSE("house", 5, 0, null, true, false),
    /** Reparar la empalizada/muralla rota: no va en la lista (sale como fila aparte cuando hay algo roto), costo según lo roto. */
    REPAIR("repair", 0, 0, null, true, false),
    /**
     * H22c: lo que se dibuja o planta en Defensa, por niveles (1 = madera, 2 = piedra): muralla (tramos dibujados),
     * portón (puesto de control sobre la muralla) y torre suelta. Se repiten; el costo en esmeraldas sale de {@link Works}.
     */
    WALL_1("wall_1", 0, 1, null, true, false),
    WALL_2("wall_2", 0, 2, null, true, false),
    GATE_1("gate_1", 4, 1, null, true, false),
    GATE_2("gate_2", 8, 2, null, true, false),
    TOWER_1("tower_1", 6, 1, null, true, false),
    TOWER_2("tower_2", 12, 2, null, true, false),
    /** Pedido de Ale: de un click, toda la empalizada pasa a muralla de piedra y las torres de madera a piedra. */
    STONE_WALLS("stone_walls", 0, 2, null, true, false),
    STONE_TOWERS("stone_towers", 0, 2, null, true, false),
    /** H22b: terreno (allanar, rellenar) y demoler un edificio o una torre. */
    EARTH_LEVEL("earth_level", 0, 0, null, true, false),
    EARTH_FILL("earth_fill", 0, 0, null, true, false),
    DEMOLISH("demolish", 0, 0, null, true, false);

    public final String id;
    public final int cost;
    public final int builderLevel;
    @Nullable
    public final Upgrade requires;
    public final boolean village, castle;

    Upgrade(String id, int cost, int builderLevel, @Nullable Upgrade requires, boolean village, boolean castle) {
        this.id = id;
        this.cost = cost;
        this.builderLevel = builderLevel;
        this.requires = requires;
        this.village = village;
        this.castle = castle;
    }

    /** False = no se muestra con las mejoras ni queda "hecha": la casa y la reparación se pueden hacer muchas veces. */
    public boolean listed() {
        return this != HOUSE && this != REPAIR && !isWork();
    }

    /** H22: obra de Defensa o Terreno de {@link Works} (no queda "hecha", se repite). */
    public boolean isWork() {
        return switch (this) {
            case WALL_1, WALL_2, GATE_1, GATE_2, TOWER_1, TOWER_2, STONE_WALLS, STONE_TOWERS, EARTH_LEVEL, EARTH_FILL, DEMOLISH -> true;
            default -> false;
        };
    }

    /** H22c: 1 = madera, 2 = piedra (0 si no es de Defensa). */
    public int defenseLevel() {
        return switch (this) {
            case WALL_1, GATE_1, TOWER_1 -> 1;
            case WALL_2, GATE_2, TOWER_2, STONE_WALLS, STONE_TOWERS -> 2;
            default -> 0;
        };
    }

    public boolean appliesTo(boolean isCastle) {
        return isCastle ? castle : village;
    }

    /** Nombre para mostrar (el cuartel de un castillo es "Cuartel grande"). */
    public Component name(boolean isCastle) {
        return Component.translatable("upgrade.warbandcraft." + id + (isCastle && this == BARRACKS ? "_castle" : ""));
    }

    public Component effect(boolean isCastle) {
        return Component.translatable("upgrade.warbandcraft." + id + (isCastle && this == BARRACKS ? "_castle" : "") + ".effect");
    }

    /** El nivel de constructor que deja hecha (0 si no es un taller). */
    public int givesBuilder() {
        return switch (this) {
            case BUILDER_1 -> 1;
            case BUILDER_2 -> 2;
            case BUILDER_3 -> 3;
            default -> 0;
        };
    }

    /** True si es un edificio con su propio terreno (el taller usa siempre el mismo). */
    public boolean isBuilding() {
        return switch (this) {
            case BUILDER_1, BUILDER_2, BUILDER_3, BARRACKS, SMITHY, MARKET, MARKET_2, MARKET_3, GRANARY, CHAPEL, MAINTENANCE, KITCHEN,
                 TAVERN_1, TAVERN_2, TAVERN_3, TRAINING_1, TRAINING_2, TRAINING_3 -> true;
            default -> false;
        };
    }

    /**
     * Lado del terreno: el del nivel más grande del edificio, así el primero ya reserva el lugar (pedido de Ale: la
     * Taberna 1 ocupa lo mismo que la 3).
     */
    public int siteSize() {
        return switch (this) {
            case TAVERN_1, TAVERN_2, TAVERN_3, MARKET, MARKET_2, MARKET_3, TRAINING_1, TRAINING_2, TRAINING_3 -> 13;
            case BUILDER_1, BUILDER_2, BUILDER_3, BARRACKS, SMITHY, CHAPEL -> 11;
            case MAINTENANCE -> 7;
            default -> 9;
        };
    }

    /** H22: se planta a mano desde el modo Gestión (elegís el lugar y el giro): los edificios y las casas. */
    public boolean handPlaced() {
        return isBuilding() || this == HOUSE;
    }

    /** H22: el nivel anterior del mismo edificio (Taberna 2 → Taberna 1): ese ya tiene terreno, no se vuelve a plantar. */
    public boolean isLevelUp() {
        return requires != null && requires.siteKey().equals(siteKey());
    }

    /** Clave del terreno (los tres talleres comparten uno; los niveles de taberna y de entrenamiento, también). */
    public String siteKey() {
        if (givesBuilder() > 0) return "builder";
        if (tavernLevel() > 0) return "tavern";
        if (trainingLevel() > 0) return "training";
        if (marketLevel() > 0) return MARKET.id;
        return id;
    }

    /** H19: el nivel de taberna que deja hecha (0 si no es una taberna). */
    public int tavernLevel() {
        return switch (this) {
            case TAVERN_1 -> 1;
            case TAVERN_2 -> 2;
            case TAVERN_3 -> 3;
            default -> 0;
        };
    }

    /** H19: el nivel de campo de entrenamiento que deja hecho (0 si no es uno). */
    public int trainingLevel() {
        return switch (this) {
            case TRAINING_1 -> 1;
            case TRAINING_2 -> 2;
            case TRAINING_3 -> 3;
            default -> 0;
        };
    }

    /** H24: el nivel de mercado que deja hecho (0 si no es un mercado). */
    public int marketLevel() {
        return switch (this) {
            case MARKET -> 1;
            case MARKET_2 -> 2;
            case MARKET_3 -> 3;
            default -> 0;
        };
    }

    @Nullable
    public static Upgrade byId(String id) {
        for (Upgrade u : values()) if (u.id.equals(id)) return u;
        return null;
    }
}
