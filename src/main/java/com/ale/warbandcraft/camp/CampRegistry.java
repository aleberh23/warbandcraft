package com.ale.warbandcraft.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dónde están los Campamentos de cada jugador (por mundo) y lo que todavía hay que llevarles.
 *
 * El botín y los impuestos se guardan en el Campamento del jugador. Si no hay ninguno cargado en ese momento
 * (el jugador está lejos), quedan "en camino" acá y llegan solos apenas se carga alguno.
 */
public class CampRegistry extends SavedData {
    private static final String FILE = "warbandcraft_camps";

    private final Map<UUID, Set<Long>> camps = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    /** H8: nombre de cada Campamento (posición → nombre), para conocerlo aunque no esté cargado. */
    private final Map<Long, String> names = new HashMap<>();
    /** H8: cuántos Campamentos puso cada jugador (para "Campamento 1", "Campamento 2"...). */
    private final Map<UUID, Integer> placed = new HashMap<>();
    /** H11: la última cuenta del almacén de cada Campamento ({@link CampStats}), para el Panel cuando no está cargado. */
    private final Map<Long, int[]> stats = new HashMap<>();
    /** H19: campo de entrenamiento de cada Campamento común: {nivel, día desde el que vale}. */
    private final Map<Long, int[]> training = new HashMap<>();

    public static CampRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CampRegistry::load, CampRegistry::new, FILE);
    }

    public void register(UUID owner, BlockPos pos) {
        if (camps.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(pos.asLong())) setDirty();
    }

    public void unregister(UUID owner, BlockPos pos) {
        Set<Long> set = camps.get(owner);
        if (set != null && set.remove(pos.asLong())) setDirty();
        if (names.remove(pos.asLong()) != null) setDirty();
        if (stats.remove(pos.asLong()) != null) setDirty();
        if (training.remove(pos.asLong()) != null) setDirty();
    }

    /** H19: {nivel, día desde el que vale} del campo de entrenamiento del Campamento, o null si no tiene. */
    @Nullable
    public int[] trainingOf(BlockPos pos) {
        return training.get(pos.asLong());
    }

    public void setTraining(BlockPos pos, int level, int fromDay) {
        training.put(pos.asLong(), new int[]{level, fromDay});
        setDirty();
    }

    /** H11: guarda la cuenta del almacén del Campamento en {@code pos}. */
    public void setStats(BlockPos pos, int[] values) {
        int[] old = stats.put(pos.asLong(), values.clone());
        if (old == null || !java.util.Arrays.equals(old, values)) setDirty();
    }

    /** H11: la última cuenta del almacén del Campamento en {@code pos} (índices de {@link CampStats}), o null. */
    @Nullable
    public int[] statsOf(BlockPos pos) {
        return stats.get(pos.asLong());
    }

    /** El número que le toca al próximo Campamento de {@code owner} ("Campamento 3"). */
    public int nextNumber(UUID owner) {
        int n = placed.merge(owner, 1, Integer::sum);
        setDirty();
        return n;
    }

    public void setName(BlockPos pos, String name) {
        if (!name.equals(names.put(pos.asLong(), name))) setDirty();
    }

    /** El nombre del Campamento en {@code pos}, o null si no se conoce. */
    @Nullable
    public String nameOf(BlockPos pos) {
        return names.get(pos.asLong());
    }

    /** El nombre para mostrar en mensajes: el propio o "Campamento". */
    public Component displayName(BlockPos pos) {
        String name = nameOf(pos);
        return name == null ? Component.translatable("container.warbandcraft.camp") : Component.literal(name);
    }

    /** El Campamento de {@code owner} más cercano a {@code near} en este mundo (cargado o no), o null. */
    @Nullable
    public BlockPos nearestCamp(UUID owner, BlockPos near) {
        BlockPos best = null;
        for (BlockPos p : campsOf(owner)) {
            if (best == null || p.distSqr(near) < best.distSqr(near)) best = p;
        }
        return best;
    }

    /** Es de {@code owner} o de alguien de su facción (pedido de Ale: lo de la facción es de todos). */
    public boolean isCampOf(UUID owner, BlockPos pos) {
        for (UUID m : com.ale.warbandcraft.faction.Allies.members(owner)) {
            Set<Long> set = camps.get(m);
            if (set != null && set.contains(pos.asLong())) return true;
        }
        return false;
    }

    /** H12: de quién es el Campamento en {@code pos}, o null. */
    @Nullable
    public UUID ownerOf(BlockPos pos) {
        long key = pos.asLong();
        for (Map.Entry<UUID, Set<Long>> e : camps.entrySet()) if (e.getValue().contains(key)) return e.getKey();
        return null;
    }

    /** Dónde están los Campamentos de {@code owner} y de los de su facción en este mundo (cargados o no). */
    public List<BlockPos> campsOf(UUID owner) {
        List<BlockPos> out = new ArrayList<>();
        for (UUID m : com.ale.warbandcraft.faction.Allies.members(owner)) {
            Set<Long> set = camps.get(m);
            if (set != null) for (long l : set) out.add(BlockPos.of(l));
        }
        return out;
    }

    /** Todos los Campamentos de este mundo, de todos los jugadores (H10: herreros). */
    public List<BlockPos> allCamps() {
        List<BlockPos> out = new ArrayList<>();
        for (Set<Long> set : camps.values()) for (long l : set) out.add(BlockPos.of(l));
        return out;
    }

    public boolean hasCamp(UUID owner) {
        return !campsOf(owner).isEmpty();
    }

    /** Lo que espera ser entregado a {@code owner}. */
    public List<ItemStack> pendingFor(UUID owner) {
        return pending.getOrDefault(owner, List.of());
    }

    /**
     * Guarda {@code stacks} en los Campamentos cargados de {@code owner}, empezando por el más cercano a {@code near}.
     * Lo que no entra (o si no hay ninguno cargado) queda pendiente.
     *
     * @return true si todo quedó guardado ya en algún Campamento
     */
    public boolean deliver(ServerLevel level, UUID owner, List<ItemStack> stacks, BlockPos near) {
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty()) rest.add(s.copy());
        rest = insertIntoLoadedCamps(level, owner, rest, near);
        if (!rest.isEmpty()) {
            pending.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(rest);
            setDirty();
            return false;
        }
        return true;
    }

    /** Intenta entregar lo pendiente de todos los jugadores (se llama cada tanto). */
    public void flushPending(ServerLevel level) {
        if (pending.isEmpty()) return;
        for (UUID owner : new ArrayList<>(pending.keySet())) {
            List<ItemStack> list = pending.get(owner);
            List<ItemStack> rest = insertIntoLoadedCamps(level, owner, list, BlockPos.ZERO);
            if (rest.size() != list.size() || rest.isEmpty()) {
                if (rest.isEmpty()) pending.remove(owner);
                else pending.put(owner, rest);
                setDirty();
            }
        }
    }

    private List<ItemStack> insertIntoLoadedCamps(ServerLevel level, UUID owner, List<ItemStack> stacks, BlockPos near) {
        Set<Long> set = camps.get(owner);
        if (set == null || set.isEmpty() || stacks.isEmpty()) return stacks;
        List<BlockPos> positions = new ArrayList<>();
        for (long l : set) positions.add(BlockPos.of(l));
        positions.sort(Comparator.comparingDouble(p -> p.distSqr(near)));

        List<ItemStack> rest = new ArrayList<>(stacks);
        for (BlockPos pos : positions) {
            if (rest.isEmpty()) break;
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof CampBlockEntity camp) || !owner.equals(camp.getOwner())) {
                set.remove(pos.asLong()); // el Campamento ya no está
                setDirty();
                continue;
            }
            List<ItemStack> next = new ArrayList<>();
            for (ItemStack s : rest) {
                ItemStack left = camp.insert(s);
                if (!left.isEmpty()) next.add(left);
            }
            rest = next;
        }
        return rest;
    }

    // ------------------------------------------------------------------ guardado

    public static CampRegistry load(CompoundTag tag) {
        CampRegistry r = new CampRegistry();
        CompoundTag campsTag = tag.getCompound("Camps");
        for (String key : campsTag.getAllKeys()) {
            Set<Long> set = new LinkedHashSet<>();
            for (long l : campsTag.getLongArray(key)) set.add(l);
            r.camps.put(UUID.fromString(key), set);
        }
        CompoundTag pendingTag = tag.getCompound("Pending");
        for (String key : pendingTag.getAllKeys()) {
            List<ItemStack> list = new ArrayList<>();
            for (Tag t : pendingTag.getList(key, Tag.TAG_COMPOUND)) {
                ItemStack s = ItemStack.of((CompoundTag) t);
                if (!s.isEmpty()) list.add(s);
            }
            if (!list.isEmpty()) r.pending.put(UUID.fromString(key), list);
        }
        CompoundTag namesTag = tag.getCompound("Names");
        for (String key : namesTag.getAllKeys()) {
            try {
                r.names.put(Long.parseLong(key), namesTag.getString(key));
            } catch (NumberFormatException ignored) {
            }
        }
        CompoundTag placedTag = tag.getCompound("Placed");
        for (String key : placedTag.getAllKeys()) r.placed.put(UUID.fromString(key), placedTag.getInt(key));
        CompoundTag statsTag = tag.getCompound("Stats");
        for (String key : statsTag.getAllKeys()) {
            int[] v = statsTag.getIntArray(key);
            if (v.length != CampStats.SIZE) continue;   // de otra versión: se vuelve a contar
            try {
                r.stats.put(Long.parseLong(key), v);
            } catch (NumberFormatException ignored) {
            }
        }
        CompoundTag trainingTag = tag.getCompound("Training");
        for (String key : trainingTag.getAllKeys()) {
            int[] v = trainingTag.getIntArray(key);
            if (v.length != 2) continue;
            try {
                r.training.put(Long.parseLong(key), v);
            } catch (NumberFormatException ignored) {
            }
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag campsTag = new CompoundTag();
        camps.forEach((owner, set) -> {
            if (!set.isEmpty()) campsTag.put(owner.toString(), new LongArrayTag(set.stream().mapToLong(Long::longValue).toArray()));
        });
        tag.put("Camps", campsTag);
        CompoundTag pendingTag = new CompoundTag();
        pending.forEach((owner, list) -> {
            ListTag l = new ListTag();
            for (ItemStack s : list) l.add(s.save(new CompoundTag()));
            pendingTag.put(owner.toString(), l);
        });
        tag.put("Pending", pendingTag);
        CompoundTag namesTag = new CompoundTag();
        names.forEach((pos, name) -> namesTag.putString(Long.toString(pos), name));
        tag.put("Names", namesTag);
        CompoundTag placedTag = new CompoundTag();
        placed.forEach((owner, n) -> placedTag.putInt(owner.toString(), n));
        tag.put("Placed", placedTag);
        CompoundTag statsTag = new CompoundTag();
        stats.forEach((pos, v) -> statsTag.putIntArray(Long.toString(pos), v));
        tag.put("Stats", statsTag);
        CompoundTag trainingTag = new CompoundTag();
        training.forEach((pos, v) -> trainingTag.putIntArray(Long.toString(pos), v));
        tag.put("Training", trainingTag);
        return tag;
    }
}
