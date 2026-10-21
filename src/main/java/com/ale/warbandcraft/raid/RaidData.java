package com.ale.warbandcraft.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Guardado de los ataques en curso (H9): si se cierra el juego en medio de una batalla, al volver sigue donde
 * estaba. Vive en el overworld (como {@link com.ale.warbandcraft.faction.Relations}) y guarda todos los
 * ataques de todos los mundos; los datos en sí siguen en {@link RaidManager}.
 */
public class RaidData extends SavedData {
    private static final String FILE = "warbandcraft_raids";

    /** Lo cargado del disco, para que {@link RaidManager} lo tome. */
    final List<RaidManager.Raid> loaded = new ArrayList<>();

    /** Los ataques leídos del disco que todavía no tomó {@link RaidManager} (lo usan las pruebas). */
    public List<RaidManager.Raid> loadedRaids() {
        return loaded;
    }

    public static RaidData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RaidData::load, RaidData::new, FILE);
    }

    public static RaidData load(CompoundTag tag) {
        RaidData d = new RaidData();
        for (Tag t : tag.getList("Raids", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceLocation dim = ResourceLocation.tryParse(c.getString("Dim"));
            ResourceLocation faction = ResourceLocation.tryParse(c.getString("Faction"));
            if (dim == null || faction == null || !c.hasUUID("Attacker")) continue;
            RaidManager.Raid raid = new RaidManager.Raid(ResourceKey.create(Registries.DIMENSION, dim),
                    BlockPos.of(c.getLong("Center")), faction, c.getUUID("Attacker"), c.getLong("Start"));
            raid.lastNearTick = c.getLong("LastNear");
            raid.initialGarrison = Math.max(1, c.getInt("Initial"));
            raid.killed = c.getInt("Killed");
            raid.state = c.getBoolean("Won") ? RaidManager.State.WON : RaidManager.State.FIGHTING;
            for (Tag g : c.getList("Gear", Tag.TAG_COMPOUND)) {
                ItemStack s = ItemStack.of((CompoundTag) g);
                if (!s.isEmpty()) raid.fallenGear.add(s);
            }
            for (Tag u : c.getList("Surrendered", Tag.TAG_INT_ARRAY)) raid.surrendered.add(NbtUtils.loadUUID(u));
            for (long l : c.getLongArray("Ladders")) raid.ladders.add(l);
            d.loaded.add(raid);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (RaidManager.Raid raid : RaidManager.all()) {
            CompoundTag c = new CompoundTag();
            c.putString("Dim", raid.dimension.location().toString());
            c.putLong("Center", raid.center.asLong());
            c.putString("Faction", raid.faction.toString());
            c.putUUID("Attacker", raid.attacker);
            c.putLong("Start", raid.startTick);
            c.putLong("LastNear", raid.lastNearTick);
            c.putInt("Initial", raid.initialGarrison);
            c.putInt("Killed", raid.killed);
            c.putBoolean("Won", raid.state == RaidManager.State.WON);
            ListTag gear = new ListTag();
            for (ItemStack s : raid.fallenGear) gear.add(s.save(new CompoundTag()));
            c.put("Gear", gear);
            ListTag surrendered = new ListTag();
            for (java.util.UUID id : raid.surrendered) surrendered.add(NbtUtils.createUUID(id));
            c.put("Surrendered", surrendered);
            c.putLongArray("Ladders", raid.ladders.stream().mapToLong(Long::longValue).toArray());
            list.add(c);
        }
        tag.put("Raids", list);
        return tag;
    }
}
