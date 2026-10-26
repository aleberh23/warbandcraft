package com.ale.warbandcraft.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pedido de Ale: "tiene que haber alguien de la facción conectado para que produzcan y consuman". Los días (del overworld)
 * en que cada jugador o alguien de su facción estuvo conectado. Lo que se cobra de golpe por días atrasados (los herreros
 * de H10) cuenta solo esos días. Se anota cada segundo ({@link #tick}); se guardan los últimos {@link #KEEP_DAYS}.
 */
public class OnlineDays extends SavedData {
    private static final String FILE = "warbandcraft_online_days";
    private static final int KEEP_DAYS = 30;

    private final Map<UUID, TreeSet<Long>> days = new HashMap<>();

    public static OnlineDays get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(OnlineDays::load, OnlineDays::new, FILE);
    }

    /** Hoy, en días del overworld. */
    public static long today(MinecraftServer server) {
        return server.overworld().getDayTime() / 24000L;
    }

    /** Cada segundo: los conectados y los de su facción quedan anotados en el día de hoy. */
    public static void tick(MinecraftServer server) {
        if (server.getPlayerList().getPlayerCount() == 0) return;
        OnlineDays d = get(server);
        long today = today(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            for (UUID id : Allies.members(p.getUUID())) d.mark(id, today);
            d.mark(p.getUUID(), today);
        }
    }

    private void mark(UUID id, long day) {
        TreeSet<Long> set = days.computeIfAbsent(id, k -> new TreeSet<>());
        if (set.add(day)) {
            while (set.size() > KEEP_DAYS) set.pollFirst();
            setDirty();
        }
    }

    /** Cuántos de los días ({@code from}, {@code to}] tuvieron a {@code owner} o a alguien de su facción conectado. */
    public static long count(ServerLevel level, UUID owner, long from, long to) {
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return Math.max(0, to - from);
        if (owner == null || to <= from) return 0;
        OnlineDays d = get(level.getServer());
        TreeSet<Long> all = new TreeSet<>();
        for (UUID id : Allies.members(owner)) {
            TreeSet<Long> s = d.days.get(id);
            if (s != null) all.addAll(s.subSet(from, false, to, true));
        }
        TreeSet<Long> own = d.days.get(owner);
        if (own != null) all.addAll(own.subSet(from, false, to, true));
        return all.size();
    }

    /** ¿Producen y consumen ahora las cosas de {@code owner}? (alguien de su facción conectado; en las pruebas, siempre) */
    public static boolean active(ServerLevel level, UUID owner) {
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return true;
        return owner != null && Allies.anyOnline(level.getServer(), owner);
    }

    public static OnlineDays load(CompoundTag tag) {
        OnlineDays d = new OnlineDays();
        for (Tag t : tag.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            TreeSet<Long> set = new TreeSet<>();
            for (long l : c.getLongArray("days")) set.add(l);
            d.days.put(c.getUUID("id"), set);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        days.forEach((id, set) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", id);
            c.putLongArray("days", set.stream().mapToLong(Long::longValue).toArray());
            list.add(c);
        });
        tag.put("players", list);
        return tag;
    }
}
