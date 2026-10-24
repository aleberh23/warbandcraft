package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.village.VillageData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * H25: las guaridas de bandidos y los túmulos nórdicos (nuestras mazmorras), los lugares donde ya se tiró el dado,
 * los rumores de mazmorras de otros mods y los contratos de las facciones. Uno por dimensión (se usa el overworld).
 */
public class DungeonData extends SavedData {
    private static final String FILE = "warbandcraft_dungeons";

    public enum Kind { LAIR, BARROW }

    /** Una guarida o un túmulo. */
    public static class Dungeon {
        public final int id;
        public final Kind kind;
        /** La boca en la superficie (arriba de la escalera). */
        public final BlockPos entrance;
        /** El medio de adentro (la sala grande): de acá patrullan los bandidos y se miden las distancias. */
        public final BlockPos center;
        /** La caja de adentro: minX, minY, minZ, maxX, maxY, maxZ. */
        public final int[] box;
        public String name;
        /** Ya tiene a los de adentro (bandidos, draugr de pie). */
        public boolean populated;
        /** Cuántos quedan vivos de los que hay que matar (bandidos; en un túmulo, el jefe). */
        public int remaining;
        /** Cuándo se limpió (-1 = no) y cuándo vuelve a llenarse una guarida. */
        public long clearedTick = -1, refillTick = -1;
        /** Túmulo: ataúdes con un draugr dormido ({@code risen} = ya se levantó). */
        public final List<BlockPos> coffins = new ArrayList<>();
        public final Set<Integer> risen = new HashSet<>();
        /** Túmulo: el trono del jefe, si ya despertó y si ya cayó. */
        @Nullable
        public BlockPos throne;
        public boolean bossAwake, bossDead;
        /** Túmulo: la puerta de las garras (las 9 piedras), los 3 marcos y la combinación (índices de {@link Barrows#SYMBOLS}). */
        public final List<BlockPos> door = new ArrayList<>();
        public final List<BlockPos> pillars = new ArrayList<>();
        public int[] answer = new int[0];
        public boolean doorOpen;
        /** Guarida: dónde están los presos y cuántos quedan para liberar. */
        public final List<BlockPos> cells = new ArrayList<>();
        public int captives;
        /** El cofre del jefe (ahí va lo que los bandidos robaron de tus envíos). */
        @Nullable
        public BlockPos treasure;
        public final List<ItemStack> stolen = new ArrayList<>();
        /** Dónde se paran los de adentro (bandidos, draugr de pie): se usa al llenar y al volver a llenar. */
        public final List<BlockPos> spots = new ArrayList<>();
        /** Jugadores que saben dónde está (rumor o ya entraron): se dibuja en su mapa M. */
        public final Set<UUID> known = new HashSet<>();

        public Dungeon(int id, Kind kind, BlockPos entrance, BlockPos center, int[] box) {
            this.id = id;
            this.kind = kind;
            this.entrance = entrance.immutable();
            this.center = center.immutable();
            this.box = box;
        }

        public boolean cleared() {
            return clearedTick >= 0;
        }

        /** Guarida con bandidos adentro: asalta envíos. */
        public boolean activeLair() {
            return kind == Kind.LAIR && populated && !cleared();
        }

        public boolean inside(BlockPos p, int margin) {
            return p.getX() >= box[0] - margin && p.getX() <= box[3] + margin && p.getY() >= box[1] - margin && p.getY() <= box[4] + margin
                    && p.getZ() >= box[2] - margin && p.getZ() <= box[5] + margin;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putString("Kind", kind.name());
            t.putLong("Entrance", entrance.asLong());
            t.putLong("Center", center.asLong());
            t.putIntArray("Box", box);
            t.putString("Name", name == null ? "" : name);
            t.putBoolean("Populated", populated);
            t.putInt("Remaining", remaining);
            t.putLong("Cleared", clearedTick);
            t.putLong("Refill", refillTick);
            t.put("Coffins", longs(coffins));
            t.putIntArray("Risen", risen.stream().mapToInt(Integer::intValue).toArray());
            if (throne != null) t.putLong("Throne", throne.asLong());
            t.putBoolean("BossAwake", bossAwake);
            t.putBoolean("BossDead", bossDead);
            t.put("Door", longs(door));
            t.put("Pillars", longs(pillars));
            t.putIntArray("Answer", answer);
            t.putBoolean("DoorOpen", doorOpen);
            t.put("Cells", longs(cells));
            t.put("Spots", longs(spots));
            t.putInt("Captives", captives);
            if (treasure != null) t.putLong("Treasure", treasure.asLong());
            ListTag st = new ListTag();
            for (ItemStack s : stolen) st.add(s.save(new CompoundTag()));
            t.put("Stolen", st);
            ListTag k = new ListTag();
            for (UUID u : known) {
                CompoundTag c = new CompoundTag();
                c.putUUID("U", u);
                k.add(c);
            }
            t.put("Known", k);
            return t;
        }

        static Dungeon load(CompoundTag t) {
            Dungeon d = new Dungeon(t.getInt("Id"), Kind.valueOf(t.getString("Kind")), BlockPos.of(t.getLong("Entrance")),
                    BlockPos.of(t.getLong("Center")), t.getIntArray("Box"));
            d.name = t.getString("Name");
            d.populated = t.getBoolean("Populated");
            d.remaining = t.getInt("Remaining");
            d.clearedTick = t.getLong("Cleared");
            d.refillTick = t.getLong("Refill");
            readLongs(t.getList("Coffins", Tag.TAG_LONG), d.coffins);
            for (int i : t.getIntArray("Risen")) d.risen.add(i);
            if (t.contains("Throne")) d.throne = BlockPos.of(t.getLong("Throne"));
            d.bossAwake = t.getBoolean("BossAwake");
            d.bossDead = t.getBoolean("BossDead");
            readLongs(t.getList("Door", Tag.TAG_LONG), d.door);
            readLongs(t.getList("Pillars", Tag.TAG_LONG), d.pillars);
            d.answer = t.getIntArray("Answer");
            d.doorOpen = t.getBoolean("DoorOpen");
            readLongs(t.getList("Cells", Tag.TAG_LONG), d.cells);
            readLongs(t.getList("Spots", Tag.TAG_LONG), d.spots);
            d.captives = t.getInt("Captives");
            if (t.contains("Treasure")) d.treasure = BlockPos.of(t.getLong("Treasure"));
            for (Tag s : t.getList("Stolen", Tag.TAG_COMPOUND)) {
                ItemStack stack = ItemStack.of((CompoundTag) s);
                if (!stack.isEmpty()) d.stolen.add(stack);
            }
            for (Tag k : t.getList("Known", Tag.TAG_COMPOUND)) d.known.add(((CompoundTag) k).getUUID("U"));
            return d;
        }
    }

    /** Un rumor de una mazmorra de otro mod (no es nuestra: solo sabemos dónde está y cómo se llama). */
    public record Rumor(BlockPos pos, String name) {}

    /** Un trabajo de una facción: limpiar la mazmorra {@code dungeon} antes de {@code deadline}. */
    public record Contract(int dungeon, ResourceLocation faction, int reward, long deadline) {}

    private final List<Dungeon> dungeons = new ArrayList<>();
    private final List<BlockPos> attempts = new ArrayList<>();
    private final Map<UUID, List<Rumor>> rumors = new HashMap<>();
    private final Map<UUID, Contract> contracts = new HashMap<>();
    private int nextId = 1;

    public static DungeonData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(DungeonData::load, DungeonData::new, FILE);
    }

    public List<Dungeon> all() {
        return dungeons;
    }

    @Nullable
    public Dungeon byId(int id) {
        for (Dungeon d : dungeons) if (d.id == id) return d;
        return null;
    }

    public int newId() {
        setDirty();
        return nextId++;
    }

    public void add(Dungeon d) {
        dungeons.add(d);
        setDirty();
    }

    public void remove(Dungeon d) {
        dungeons.remove(d);
        contracts.values().removeIf(c -> c.dungeon() == d.id);
        setDirty();
    }

    /** La mazmorra (entrada o medio) más cercana a menos de {@code dist} bloques en horizontal, o null. */
    @Nullable
    public Dungeon near(BlockPos pos, int dist) {
        Dungeon best = null;
        double bestSq = (double) dist * dist;
        for (Dungeon d : dungeons) {
            double s = Math.min(VillageData.horizontalDistSq(d.entrance, pos), VillageData.horizontalDistSq(d.center, pos));
            if (s <= bestSq) {
                bestSq = s;
                best = d;
            }
        }
        return best;
    }

    /** La mazmorra que tiene a {@code pos} adentro (o a {@code margin} de su caja), o null. */
    @Nullable
    public Dungeon containing(BlockPos pos, int margin) {
        for (Dungeon d : dungeons) if (d.inside(pos, margin)) return d;
        return null;
    }

    @Nullable
    public BlockPos nearestAttempt(BlockPos pos, double maxDist) {
        for (BlockPos p : attempts) if (VillageData.horizontalDistSq(p, pos) <= maxDist * maxDist) return p;
        return null;
    }

    public void addAttempt(BlockPos pos) {
        attempts.add(pos.immutable());
        setDirty();
    }

    public List<Rumor> rumorsOf(UUID player) {
        return rumors.getOrDefault(player, List.of());
    }

    public void addRumor(UUID player, Rumor r) {
        rumors.computeIfAbsent(player, k -> new ArrayList<>()).add(r);
        setDirty();
    }

    public boolean hasRumor(UUID player, BlockPos pos) {
        for (Rumor r : rumorsOf(player)) if (r.pos().equals(pos)) return true;
        return false;
    }

    @Nullable
    public Contract contractOf(UUID player) {
        return contracts.get(player);
    }

    public void setContract(UUID player, @Nullable Contract c) {
        if (c == null) contracts.remove(player);
        else contracts.put(player, c);
        setDirty();
    }

    public Map<UUID, Contract> contracts() {
        return contracts;
    }

    public void changed() {
        setDirty();
    }

    public static DungeonData load(CompoundTag tag) {
        DungeonData data = new DungeonData();
        for (Tag t : tag.getList("Dungeons", Tag.TAG_COMPOUND)) {
            try {
                data.dungeons.add(Dungeon.load((CompoundTag) t));
            } catch (Exception ignored) {
                // una entrada rota no rompe el resto
            }
        }
        readLongs(tag.getList("Attempts", Tag.TAG_LONG), data.attempts);
        for (Tag t : tag.getList("Rumors", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.rumors.computeIfAbsent(c.getUUID("Player"), k -> new ArrayList<>())
                    .add(new Rumor(BlockPos.of(c.getLong("Pos")), c.getString("Name")));
        }
        for (Tag t : tag.getList("Contracts", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceLocation f = ResourceLocation.tryParse(c.getString("Faction"));
            if (f != null) data.contracts.put(c.getUUID("Player"), new Contract(c.getInt("Dungeon"), f, c.getInt("Reward"), c.getLong("Deadline")));
        }
        data.nextId = Math.max(1, tag.getInt("NextId"));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Dungeon d : dungeons) list.add(d.save());
        tag.put("Dungeons", list);
        tag.put("Attempts", longs(attempts));
        ListTag r = new ListTag();
        rumors.forEach((player, items) -> {
            for (Rumor rumor : items) {
                CompoundTag c = new CompoundTag();
                c.putUUID("Player", player);
                c.putLong("Pos", rumor.pos().asLong());
                c.putString("Name", rumor.name());
                r.add(c);
            }
        });
        tag.put("Rumors", r);
        ListTag cs = new ListTag();
        contracts.forEach((player, c) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("Player", player);
            t.putInt("Dungeon", c.dungeon());
            t.putString("Faction", c.faction().toString());
            t.putInt("Reward", c.reward());
            t.putLong("Deadline", c.deadline());
            cs.add(t);
        });
        tag.put("Contracts", cs);
        tag.putInt("NextId", nextId);
        return tag;
    }

    private static ListTag longs(List<BlockPos> list) {
        ListTag t = new ListTag();
        for (BlockPos p : list) t.add(LongTag.valueOf(p.asLong()));
        return t;
    }

    private static void readLongs(ListTag t, List<BlockPos> into) {
        for (Tag x : t) into.add(BlockPos.of(((LongTag) x).getAsLong()));
    }
}
