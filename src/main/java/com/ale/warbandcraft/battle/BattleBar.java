package com.ale.warbandcraft.battle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * La barra de arriba de una pelea, al estilo Total War: una sola barra que se llena con la parte de los soldados que le
 * quedan a un bando (izquierda) contra el otro (derecha), y dice quién va ganando. Para el jugador, su bando va a la
 * izquierda: verde si va ganando, rojo si va perdiendo, amarillo parejo. En las peleas de otros, blanca.
 */
public final class BattleBar {
    /** Va ganando el que tiene al menos esta parte de los soldados que quedan. */
    public static double WINNING = 0.6;

    private static final Map<String, ServerBossEvent> BARS = new HashMap<>();

    private BattleBar() {}

    /** Un bando: su nombre y cuántos le quedan. */
    public record Side(Component name, int count) {}

    /**
     * Muestra/actualiza la barra {@code key} a {@code viewers} (a los demás se la saca). {@code yours}: el bando izquierdo
     * es el del que mira (colores de ganar/perder); si no, neutral.
     */
    public static void update(String key, Side left, Side right, boolean yours, Collection<ServerPlayer> viewers) {
        ServerBossEvent bar = BARS.computeIfAbsent(key, k -> new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.WHITE,
                BossEvent.BossBarOverlay.PROGRESS));
        int total = left.count() + right.count();
        float share = total == 0 ? 0.5F : left.count() / (float) total;
        bar.setProgress(share);
        Component state = share >= WINNING ? Component.translatable("msg.warbandcraft.bar.winning", left.name())
                : share <= 1 - WINNING ? Component.translatable("msg.warbandcraft.bar.winning", right.name())
                : Component.translatable("msg.warbandcraft.bar.even");
        bar.setName(Component.translatable("msg.warbandcraft.bar.title", left.name(), left.count(), right.count(), right.name(), state));
        bar.setColor(!yours ? BossEvent.BossBarColor.WHITE
                : share >= WINNING ? BossEvent.BossBarColor.GREEN
                : share <= 1 - WINNING ? BossEvent.BossBarColor.RED
                : BossEvent.BossBarColor.YELLOW);
        Set<ServerPlayer> keep = new HashSet<>(viewers);
        for (ServerPlayer p : new HashSet<>(bar.getPlayers())) if (!keep.contains(p)) bar.removePlayer(p);
        for (ServerPlayer p : keep) bar.addPlayer(p);
    }

    /** Terminó la pelea: se saca la barra. */
    public static void close(String key) {
        ServerBossEvent bar = BARS.remove(key);
        if (bar != null) bar.removeAllPlayers();
    }

    public static void clear() {
        BARS.values().forEach(ServerBossEvent::removeAllPlayers);
        BARS.clear();
    }

    @Nullable
    public static ServerBossEvent get(String key) {
        return BARS.get(key);
    }
}
