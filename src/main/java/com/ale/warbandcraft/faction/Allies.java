package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.village.Territory;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Compañeros de facción (pedido de Ale): dos jugadores en la misma facción de Recruits (mismo equipo del marcador,
 * aunque uno esté desconectado). Pueden sacar y guardar soldados de los barracones del otro.
 */
public final class Allies {
    private Allies() {}

    /** El mismo jugador, o los dos en la misma facción de Recruits. */
    public static boolean same(ServerLevel level, @Nullable UUID a, @Nullable UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String fa = Territory.factionOf(level, a);
        return fa != null && Objects.equals(fa, Territory.factionOf(level, b));
    }
}
