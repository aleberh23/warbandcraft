package com.ale.warbandcraft.army;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ejércitos de campaña (H20), por dimensión. Un ejército es una lista de pedidos (Campamento + fila del barracón +
 * cantidad): mientras no se invoca, los soldados siguen en su barracón. Invocado, guarda quiénes salieron y de qué
 * Campamento (para volver a guardarlos y para la comida).
 */
public class ArmyData extends SavedData {
    private static final String FILE = "warbandcraft_armies";

    public enum State { IDLE, COMING, FIELD }

    /** Qué hace el ejército en el campo. */
    public enum Mode { HOLD, CHARGE, DELEGATE }

    /** Un pedido: {@code count} soldados de la fila {@code row} (clase#nivel, "" = reclutas) del barracón de {@code camp}. */
    public static final class Pick {
        public final BlockPos camp;
        public final String row;
        public int count;

        public Pick(BlockPos camp, String row, int count) {
            this.camp = camp;
            this.row = row;
            this.count = count;
        }
    }

    public static final class Army {
        public final UUID id;
        public final UUID owner;
        public String name;
        public final List<Pick> picks = new ArrayList<>();
        public State state = State.IDLE;
        public Mode mode = Mode.HOLD;
        /** COMING: cuándo llega; FIELD: cuándo llegó. */
        public long arriveTick;
        /** Cuándo se invocó (para la barra de la cuenta regresiva). */
        public long summonTick;
        /** Centro de la formación y hacia dónde mira (horizontal, normalizado). */
        public Vec3 anchor = Vec3.ZERO;
        public Vec3 facing = new Vec3(0, 0, 1);
        /** Los que están en el campo: UUID → Campamento de origen (long). */
        public final Map<UUID, Long> members = new LinkedHashMap<>();
        /** La primera carga desde un lugar escondido pega más fuerte hasta este tick. */
        public long ambushUntil;
        public boolean charged;
        /** Enemigos de la batalla de campo en curso (los de una marcha que se le tiró encima). */
        public final List<UUID> foes = new ArrayList<>();
        /** H23: los que todavía esperan en sus barracones para entrar (tamaño de batalla). */
        public final List<Pick> waiting = new ArrayList<>();

        // En memoria: puesto de cada uno y la última orden que se le dio (para saber si alguien se la cambió)
        transient final Map<UUID, Vec3> slots = new HashMap<>();
        transient final Map<UUID, int[]> lastOrder = new HashMap<>();
        /** Soldados a los que el jugador les dio otra orden: el ejército no los toca hasta la próxima orden del ejército. */
        transient final java.util.Set<UUID> free = new java.util.HashSet<>();
        transient long calmSince = -1;

        public Army(UUID id, UUID owner, String name) {
            this.id = id;
            this.owner = owner;
            this.name = name;
        }

        public int total() {
            int n = 0;
            for (Pick p : picks) n += p.count;
            return n;
        }

        @Nullable
        public Pick pick(BlockPos camp, String row) {
            for (Pick p : picks) if (p.camp.equals(camp) && p.row.equals(row)) return p;
            return null;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putUUID("Owner", owner);
            t.putString("Name", name);
            ListTag picks = new ListTag();
            for (Pick p : this.picks) {
                CompoundTag c = new CompoundTag();
                c.putLong("Camp", p.camp.asLong());
                c.putString("Row", p.row);
                c.putInt("Count", p.count);
                picks.add(c);
            }
            t.put("Picks", picks);
            t.putString("State", state.name());
            t.putString("Mode", mode.name());
            t.putLong("Arrive", arriveTick);
            t.putLong("Summon", summonTick);
            t.putDouble("AX", anchor.x);
            t.putDouble("AY", anchor.y);
            t.putDouble("AZ", anchor.z);
            t.putDouble("FX", facing.x);
            t.putDouble("FZ", facing.z);
            ListTag members = new ListTag();
            this.members.forEach((uuid, camp) -> {
                CompoundTag c = new CompoundTag();
                c.putUUID("Id", uuid);
                c.putLong("Camp", camp);
                members.add(c);
            });
            t.put("Members", members);
            t.putBoolean("Charged", charged);
            ListTag waiting = new ListTag();
            for (Pick p : this.waiting) {
                CompoundTag c = new CompoundTag();
                c.putLong("Camp", p.camp.asLong());
                c.putString("Row", p.row);
                c.putInt("Count", p.count);
                waiting.add(c);
            }
            t.put("Waiting", waiting);
            return t;
        }

        static Army load(CompoundTag t) {
            Army a = new Army(t.getUUID("Id"), t.getUUID("Owner"), t.getString("Name"));
            for (Tag x : t.getList("Picks", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                a.picks.add(new Pick(BlockPos.of(c.getLong("Camp")), c.getString("Row"), c.getInt("Count")));
            }
            try {
                a.state = State.valueOf(t.getString("State"));
                a.mode = Mode.valueOf(t.getString("Mode"));
            } catch (IllegalArgumentException ignored) {
            }
            a.arriveTick = t.getLong("Arrive");
            a.summonTick = t.getLong("Summon");
            a.anchor = new Vec3(t.getDouble("AX"), t.getDouble("AY"), t.getDouble("AZ"));
            Vec3 f = new Vec3(t.getDouble("FX"), 0, t.getDouble("FZ"));
            a.facing = f.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : f.normalize();
            for (Tag x : t.getList("Members", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                a.members.put(c.getUUID("Id"), c.getLong("Camp"));
            }
            a.charged = t.getBoolean("Charged");
            for (Tag x : t.getList("Waiting", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) x;
                a.waiting.add(new Pick(BlockPos.of(c.getLong("Camp")), c.getString("Row"), c.getInt("Count")));
            }
            return a;
        }
    }

    private final Map<UUID, Army> armies = new LinkedHashMap<>();

    public static ArmyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ArmyData::load, ArmyData::new, FILE);
    }

    @Nullable
    public Army get(UUID id) {
        return armies.get(id);
    }

    public List<Army> of(UUID owner) {
        List<Army> out = new ArrayList<>();
        for (Army a : armies.values()) if (com.ale.warbandcraft.faction.Allies.same(a.owner, owner)) out.add(a);
        return out;
    }

    public java.util.Collection<Army> all() {
        return armies.values();
    }

    public void put(Army a) {
        armies.put(a.id, a);
        setDirty();
    }

    public void remove(UUID id) {
        if (armies.remove(id) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }

    /** El ejército del soldado (por su UUID), o null. */
    @Nullable
    public Army armyOf(UUID soldier) {
        for (Army a : armies.values()) if (a.members.containsKey(soldier)) return a;
        return null;
    }

    public static ArmyData load(CompoundTag tag) {
        ArmyData d = new ArmyData();
        for (Tag t : tag.getList("Armies", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("Id") || !c.hasUUID("Owner")) continue;
            Army a = Army.load(c);
            d.armies.put(a.id, a);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Army a : armies.values()) list.add(a.save());
        tag.put("Armies", list);
        return tag;
    }
}
