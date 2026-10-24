package com.ale.warbandcraft.castle;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Qué castillo es (pedido de Ale: castillos distintos por facción). El clásico es el plano de siempre
 * ({@code castle_default.nbt}) con los materiales de la facción; los otros los dibuja {@link CastleDesigns}.
 */
public enum CastleKind {
    /** El plano de siempre, con los materiales de la facción. */
    CLASSIC("clasico"),
    /** Reino de Valdren: muralla de ladrillo de piedra, torres redondas, torre del homenaje, a veces foso. */
    FORTRESS("fortaleza"),
    /** Clanes del Norte: empalizada de troncos sobre piedra, torres de vigía, gran salón. */
    LONGFORT("troncos"),
    /** Sultanato: muros altos de arenisca, torres cuadradas de techo plano, patio con fuente y palmeras. */
    KASBAH("kasbah"),
    /** Liga de los Pantanos: barro y mangle, foso de agua, casa fuerte sobre pilotes. */
    SWAMPFORT("pantano");

    public final String id;

    CastleKind(String id) {
        this.id = id;
    }

    @Nullable
    public static CastleKind byId(@Nullable String id) {
        for (CastleKind k : values()) if (k.id.equals(id)) return k;
        return null;
    }

    /** El castillo dibujado de una facción (una facción nueva sin diseño propio: Fortaleza, con su estilo). */
    public static CastleKind drawnFor(@Nullable ResourceLocation faction) {
        if (faction == null) return FORTRESS;
        return switch (faction.getPath()) {
            case "norte" -> LONGFORT;
            case "sultanato" -> KASBAH;
            case "pantanos" -> SWAMPFORT;
            default -> FORTRESS;
        };
    }
}
