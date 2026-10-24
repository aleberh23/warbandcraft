package com.ale.warbandcraft.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Qué opina cada facción NPC de cada jugador. Va de -100 (odio) a 100; arranca en 0.
 * Atacar, saquear y conquistar la bajan; con el tiempo vuelve sola hacia 0.
 * Por debajo de {@link #WAR_THRESHOLD} la facción está en guerra con el jugador: sus guarniciones lo atacan.
 * Se guarda una sola vez para todo el servidor (en el overworld).
 */
public class Relations extends SavedData {
    private static final String FILE = "warbandcraft_relations";

    public static int MIN = -100, MAX = 100;
    public static int WAR_THRESHOLD = -40;

    // Cuánto cambia la opinión por cada cosa (para ajustar con el juego)
    public static int RAID_STARTED = -10;
    public static int SACKED_TARGET = -15, SACKED_OTHERS = -5;
    public static int CONQUERED_TARGET = -25, CONQUERED_OTHERS = -3;
    /** Cuánto se recupera por día de juego, hacia 0. */
    public static int DAILY_RECOVERY = 2;

    private final Map<UUID, Map<ResourceLocation, Integer>> opinions = new HashMap<>();

    public static Relations get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Relations::load, Relations::new, FILE);
    }

    public int opinion(UUID player, ResourceLocation faction) {
        Map<ResourceLocation, Integer> m = opinions.get(player);
        return m == null ? 0 : m.getOrDefault(faction, 0);
    }

    public boolean atWar(UUID player, ResourceLocation faction) {
        Faction f = FactionManager.INSTANCE.get(faction);
        if (f != null && f.outlaw()) return true;   // H25: los Bandidos, con todos
        return opinion(player, faction) <= WAR_THRESHOLD;
    }

    /** Suma {@code delta} a la opinión y devuelve el valor nuevo. */
    public int add(UUID player, ResourceLocation faction, int delta) {
        int value = Math.max(MIN, Math.min(MAX, opinion(player, faction) + delta));
        opinions.computeIfAbsent(player, k -> new HashMap<>()).put(faction, value);
        setDirty();
        return value;
    }

    /** {@code target} cambia {@code targetDelta}; todas las demás facciones cargadas, {@code othersDelta}. */
    public void affectAll(UUID player, ResourceLocation target, int targetDelta, int othersDelta) {
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            add(player, f.id(), f.id().equals(target) ? targetDelta : othersDelta);
        }
        if (FactionManager.INSTANCE.get(target) == null) add(player, target, targetDelta);
    }

    /** Un día de juego: todas las opiniones se acercan {@link #DAILY_RECOVERY} a 0. */
    public void dailyRecovery() {
        boolean changed = false;
        for (Map<ResourceLocation, Integer> m : opinions.values()) {
            for (Map.Entry<ResourceLocation, Integer> e : m.entrySet()) {
                int v = e.getValue();
                if (v == 0) continue;
                int nv = v > 0 ? Math.max(0, v - DAILY_RECOVERY) : Math.min(0, v + DAILY_RECOVERY);
                e.setValue(nv);
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    public static Relations load(CompoundTag tag) {
        Relations r = new Relations();
        for (String player : tag.getAllKeys()) {
            CompoundTag p = tag.getCompound(player);
            Map<ResourceLocation, Integer> m = new HashMap<>();
            for (String f : p.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(f);
                if (id != null) m.put(id, p.getInt(f));
            }
            r.opinions.put(UUID.fromString(player), m);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        opinions.forEach((player, m) -> {
            CompoundTag p = new CompoundTag();
            m.forEach((f, v) -> p.putInt(f.toString(), v));
            tag.put(player.toString(), p);
        });
        return tag;
    }
}
