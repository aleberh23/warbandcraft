package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.kit.KitOverride;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lo que cada jugador personalizó de su facción (H6): doctrina, nombres de clases y kits propios.
 * Va por jugador (no por facción de Recruits). Se guarda una vez para todo el servidor, en el overworld.
 */
public class PlayerFactionData extends SavedData {
    private static final String FILE = "warbandcraft_player_factions";

    public static final int MAX_NAME_LENGTH = 24;

    private static final class Entry {
        @Nullable Doctrine doctrine;
        boolean chosenOnce;
        long lastChange = -1;
        final Map<ResourceLocation, String> names = new HashMap<>();
        final Map<ResourceLocation, Map<Integer, KitOverride>> kits = new HashMap<>();
        final Map<ResourceLocation, Integer> versions = new HashMap<>();
    }

    private final Map<UUID, Entry> players = new HashMap<>();

    public static PlayerFactionData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlayerFactionData::load, PlayerFactionData::new, FILE);
    }

    @Nullable
    private Entry peek(UUID player) {
        return players.get(player);
    }

    private Entry entry(UUID player) {
        return players.computeIfAbsent(player, k -> new Entry());
    }

    // ---------- Doctrina ----------

    @Nullable
    public Doctrine doctrine(UUID player) {
        Entry e = peek(player);
        return e == null ? null : e.doctrine;
    }

    /** true si el próximo cambio es gratis (nunca eligió una). */
    public boolean nextChangeFree(UUID player) {
        Entry e = peek(player);
        return e == null || !e.chosenOnce;
    }

    /** Cuántos ticks faltan para poder cambiar de nuevo (0 = ya puede). */
    public long waitLeft(UUID player, long gameTime) {
        Entry e = peek(player);
        if (e == null || !e.chosenOnce || e.lastChange < 0) return 0;
        return Math.max(0, e.lastChange + Doctrine.CHANGE_WAIT_TICKS - gameTime);
    }

    public void setDoctrine(UUID player, Doctrine doctrine, long gameTime) {
        Entry e = entry(player);
        e.doctrine = doctrine;
        e.chosenOnce = true;
        e.lastChange = gameTime;
        setDirty();
    }

    // ---------- Nombres ----------

    @Nullable
    public String className(UUID player, ResourceLocation kit) {
        Entry e = peek(player);
        return e == null ? null : e.names.get(kit);
    }

    /** Vacío = vuelve al nombre original. */
    public void setClassName(UUID player, ResourceLocation kit, String name) {
        String clean = name.strip();
        if (clean.length() > MAX_NAME_LENGTH) clean = clean.substring(0, MAX_NAME_LENGTH);
        if (clean.isEmpty()) entry(player).names.remove(kit);
        else entry(player).names.put(kit, clean);
        setDirty();
    }

    // ---------- Kits ----------

    @Nullable
    public KitOverride override(UUID player, ResourceLocation kit, int tier) {
        Entry e = peek(player);
        if (e == null) return null;
        Map<Integer, KitOverride> tiers = e.kits.get(kit);
        return tiers == null ? null : tiers.get(tier);
    }

    /** {@code override} null = vuelve al kit original. Sube la versión de la clase (re-armado automático). */
    public void setOverride(UUID player, ResourceLocation kit, int tier, @Nullable KitOverride override) {
        Entry e = entry(player);
        if (override == null) {
            Map<Integer, KitOverride> tiers = e.kits.get(kit);
            if (tiers != null) {
                tiers.remove(tier);
                if (tiers.isEmpty()) e.kits.remove(kit);
            }
        } else {
            e.kits.computeIfAbsent(kit, k -> new HashMap<>()).put(tier, override);
        }
        e.versions.merge(kit, 1, Integer::sum);
        setDirty();
    }

    /** Versión del kit de esa clase: sube cada vez que se guarda un cambio. 0 = nunca se tocó. */
    public int kitVersion(UUID player, ResourceLocation kit) {
        Entry e = peek(player);
        return e == null ? 0 : e.versions.getOrDefault(kit, 0);
    }

    // ---------- Guardado ----------

    public static PlayerFactionData load(CompoundTag tag) {
        PlayerFactionData data = new PlayerFactionData();
        CompoundTag all = tag.getCompound("players");
        for (String key : all.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            CompoundTag p = all.getCompound(key);
            Entry e = new Entry();
            e.doctrine = Doctrine.byId(p.getString("doctrine"));
            e.chosenOnce = p.getBoolean("chosen_once");
            e.lastChange = p.contains("last_change") ? p.getLong("last_change") : -1;
            CompoundTag names = p.getCompound("names");
            for (String k : names.getAllKeys()) {
                ResourceLocation rl = ResourceLocation.tryParse(k);
                if (rl != null) e.names.put(rl, names.getString(k));
            }
            CompoundTag kits = p.getCompound("kits");
            for (String k : kits.getAllKeys()) {
                ResourceLocation rl = ResourceLocation.tryParse(k);
                if (rl == null) continue;
                CompoundTag tiers = kits.getCompound(k);
                Map<Integer, KitOverride> map = new HashMap<>();
                for (String t : tiers.getAllKeys()) {
                    try {
                        map.put(Integer.parseInt(t), KitOverride.load(tiers.getCompound(t)));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!map.isEmpty()) e.kits.put(rl, map);
            }
            CompoundTag versions = p.getCompound("versions");
            for (String k : versions.getAllKeys()) {
                ResourceLocation rl = ResourceLocation.tryParse(k);
                if (rl != null) e.versions.put(rl, versions.getInt(k));
            }
            data.players.put(id, e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, e) -> {
            CompoundTag p = new CompoundTag();
            if (e.doctrine != null) p.putString("doctrine", e.doctrine.id());
            p.putBoolean("chosen_once", e.chosenOnce);
            p.putLong("last_change", e.lastChange);
            CompoundTag names = new CompoundTag();
            e.names.forEach((k, v) -> names.putString(k.toString(), v));
            p.put("names", names);
            CompoundTag kits = new CompoundTag();
            e.kits.forEach((k, tiers) -> {
                CompoundTag t = new CompoundTag();
                tiers.forEach((i, o) -> t.put(String.valueOf(i), o.save()));
                kits.put(k.toString(), t);
            });
            p.put("kits", kits);
            CompoundTag versions = new CompoundTag();
            e.versions.forEach((k, v) -> versions.putInt(k.toString(), v));
            p.put("versions", versions);
            all.put(id.toString(), p);
        });
        tag.put("players", all);
        return tag;
    }
}
