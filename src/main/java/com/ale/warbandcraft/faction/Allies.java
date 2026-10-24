package com.ale.warbandcraft.faction;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Compañeros de facción (pedido de Ale: "todos los campamentos, aldeas y soldados de una facción tienen que ser manejables por
 * todos los miembros por igual"). Dos jugadores en la misma facción de Recruits (mismo equipo del marcador, aunque uno esté
 * desconectado) son como uno solo para todo lo que pregunta "¿es tuyo?". Sin facción, cada uno es solo él.
 * El servidor se anota al arrancar ({@link #setServer}); la facción de cada uno se recuerda {@link #CACHE_TICKS}.
 */
public final class Allies {
    private Allies() {}

    /** Cada cuánto se vuelve a mirar la facción de un jugador (ticks). */
    private static final int CACHE_TICKS = 100;

    @Nullable
    private static MinecraftServer server;
    private static final Map<UUID, String> FACTION = new HashMap<>();
    private static final Map<UUID, Set<UUID>> MEMBERS = new HashMap<>();
    private static long cachedAt = Long.MIN_VALUE;

    public static void setServer(@Nullable MinecraftServer s) {
        server = s;
        FACTION.clear();
        MEMBERS.clear();
    }

    private static void expire() {
        if (server == null) return;
        long now = server.getTickCount();
        if (cachedAt != Long.MIN_VALUE && now >= cachedAt && now - cachedAt < CACHE_TICKS) return;
        cachedAt = now;
        FACTION.clear();
        MEMBERS.clear();
    }

    /** La facción de Recruits del jugador (nombre del equipo del marcador), o null. */
    @Nullable
    public static String factionOf(@Nullable UUID player) {
        if (player == null || server == null) return null;
        expire();
        if (FACTION.containsKey(player)) return FACTION.get(player);
        String name = nameOf(player);
        PlayerTeam team = name == null ? null : server.getScoreboard().getPlayersTeam(name);
        String id = team == null ? null : team.getName();
        FACTION.put(player, id);
        return id;
    }

    @Nullable
    private static String nameOf(UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) return p.getScoreboardName();
        return server.getProfileCache() == null ? null
                : server.getProfileCache().get(player).map(com.mojang.authlib.GameProfile::getName).orElse(null);
    }

    /** El mismo jugador, o los dos en la misma facción de Recruits. */
    public static boolean same(@Nullable UUID a, @Nullable UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String fa = factionOf(a);
        return fa != null && Objects.equals(fa, factionOf(b));
    }

    /** El jugador y todos los de su facción (por UUID; los de la facción que nunca entraron, no). */
    public static Set<UUID> members(UUID player) {
        expire();
        Set<UUID> cached = MEMBERS.get(player);
        if (cached != null) return cached;
        Set<UUID> out = new LinkedHashSet<>();
        out.add(player);
        String f = factionOf(player);
        PlayerTeam team = f == null || server == null ? null : server.getScoreboard().getPlayerTeam(f);
        if (team != null) {
            for (String name : team.getPlayers()) {
                UUID id = uuidOf(name);
                if (id != null) out.add(id);
            }
        }
        MEMBERS.put(player, out);
        return out;
    }

    @Nullable
    private static UUID uuidOf(String name) {
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        if (p != null) return p.getUUID();
        if (server.getProfileCache() != null) {
            UUID id = server.getProfileCache().get(name).map(com.mojang.authlib.GameProfile::getId).orElse(null);
            if (id != null) return id;
        }
        // Equipos de Recruits también tienen soldados (por UUID en texto): esos no son jugadores
        if (name.length() == 36 && name.indexOf('-') > 0) return null;
        return server.usesAuthentication() ? null : UUIDUtil.createOfflinePlayerUUID(name);
    }

    // ---------------------------------------------------------------- también en el cliente

    /**
     * Para el cliente (que no sabe de facciones guardadas): el soldado es de {@code player} o está en su mismo equipo del
     * marcador (Recruits pone a los soldados en el equipo de la facción del dueño).
     */
    public static boolean sameTeam(Entity player, @Nullable UUID entityOwner, Entity entity) {
        if (entityOwner != null && entityOwner.equals(player.getUUID())) return true;
        Team a = player.getTeam(), b = entity.getTeam();
        return a != null && b != null && a.getName().equals(b.getName()) && entityOwner != null;
    }
}
