package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.village.WorkZones;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El Organizador (pedido de Ale): ordena los cofres vinculados de un Campamento (común o de aldea). El Campamento es la
 * bandeja de entrada (botín, impuestos, envíos, lo que juntan los trabajadores) y cada cofre vinculado guarda una
 * {@link Category}. Cuántos cofres por categoría sale de lo que hay ({@link #plan}): los espacios que ocupa cada una
 * juntando las pilas, y un cofre más cuando los suyos quedan llenos a más de {@link #FILL}. Un cofre con un marco con un
 * ítem guarda ese ítem primero y su categoría, y no cambia nunca. Las categorías chicas sin lugar van a Varios; lo que no
 * entra en ningún lado queda en el Campamento ("Faltan cofres"). Una vez asignado, un cofre no cambia de categoría hasta
 * quedar vacío y sobrarle lugar a los demás. No toca: cofres de zonas de trabajo, ítems con nombre puesto a mano, lo marcado
 * para desguace (queda en el Campamento). Esta clase solo calcula y mueve; el caminar es {@link com.ale.warbandcraft.worker.OrganizerGoal}.
 */
public final class Organizer {
    /** Pilas que lleva por viaje. */
    public static int STACKS_PER_TRIP = 4;
    /** Un cofre de una categoría lleno a más de esto (0-1) pide otro. */
    public static double FILL = 0.8;

    private Organizer() {}

    public enum Category {
        FOOD("food"), GEAR("gear"), BUILDING("building"), ORES("ores"), PLANTS("plants"), MISC("misc");

        public final String key;

        Category(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable("gui.warbandcraft.organizer.cat." + key);
        }

        @Nullable
        public static Category byKey(String key) {
            for (Category c : values()) if (c.key.equals(key)) return c;
            return null;
        }
    }

    /** La categoría de un ítem. */
    public static Category of(ItemStack s) {
        Item i = s.getItem();
        if (s.isEdible() || i == Items.WHEAT || i == Items.SUGAR || i == Items.EGG || i == Items.COCOA_BEANS || i == Items.MILK_BUCKET
                || i == Items.CAKE) return Category.FOOD;
        if (i instanceof ArmorItem || i instanceof ShieldItem || i instanceof ProjectileWeaponItem || i instanceof ArrowItem
                || i instanceof HorseArmorItem || s.isDamageableItem() || i == Armament.item() || s.is(ItemTags.ARROWS)) return Category.GEAR;
        if (s.is(Tags.Items.ORES) || s.is(Tags.Items.INGOTS) || s.is(Tags.Items.NUGGETS) || s.is(Tags.Items.GEMS)
                || s.is(Tags.Items.RAW_MATERIALS) || s.is(Tags.Items.DUSTS) || s.is(Tags.Items.STORAGE_BLOCKS) || s.is(ItemTags.COALS))
            return Category.ORES;
        if (s.is(Tags.Items.SEEDS) || s.is(ItemTags.SAPLINGS) || s.is(ItemTags.FLOWERS) || s.is(ItemTags.LEAVES) || s.is(Tags.Items.CROPS)
                || i == Items.BONE_MEAL || i == Items.SUGAR_CANE || i == Items.BAMBOO || i == Items.CACTUS) return Category.PLANTS;
        if (i instanceof BlockItem) return Category.BUILDING;
        return Category.MISC;
    }

    /** No se mueve: con nombre puesto a mano, o marcado para desguace (los herreros lo buscan en el Campamento). */
    public static boolean keep(CampBlockEntity camp, ItemStack s) {
        return s.hasCustomHoverName() || camp.isScrap(s.getItem());
    }

    // ------------------------------------------------------------------ cofres

    /** Un cofre vinculado (las dos mitades de uno doble): {@code key} = la mitad más chica. */
    public record Unit(BlockPos key, List<BlockPos> parts) {}

    /** Los cofres vinculados cargados que ordena (sin los de las zonas de trabajo), del más cerca al más lejos del Campamento. */
    public static List<Unit> units(ServerLevel level, CampBlockEntity camp) {
        Set<BlockPos> zone = WorkZones.zoneChests(level, camp.getBlockPos());
        Set<BlockPos> done = new HashSet<>();
        List<BlockPos> linked = camp.getLinked();
        List<Unit> out = new ArrayList<>();
        for (BlockPos p : linked) {
            if (done.contains(p) || zone.contains(p) || !level.isLoaded(p)) continue;
            if (!(level.getBlockEntity(p) instanceof RandomizableContainerBlockEntity)) continue;
            List<BlockPos> parts = new ArrayList<>();
            parts.add(p);
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = p.relative(ChestBlock.getConnectedDirection(st));
                if (linked.contains(other) && !zone.contains(other) && level.getBlockEntity(other) instanceof RandomizableContainerBlockEntity) parts.add(other);
            }
            parts.sort(Comparator.comparingLong(BlockPos::asLong));
            done.addAll(parts);
            out.add(new Unit(parts.get(0), List.copyOf(parts)));
        }
        BlockPos c = camp.getBlockPos();
        out.sort(Comparator.comparingDouble(u -> u.key().distSqr(c)));
        return out;
    }

    public static List<Container> containers(ServerLevel level, Unit u) {
        List<Container> out = new ArrayList<>();
        for (BlockPos p : u.parts()) if (level.getBlockEntity(p) instanceof RandomizableContainerBlockEntity c) out.add(c);
        return out;
    }

    private static int capacity(ServerLevel level, Unit u) {
        int n = 0;
        for (Container c : containers(level, u)) n += c.getContainerSize();
        return n;
    }

    private static boolean empty(ServerLevel level, Unit u) {
        for (Container c : containers(level, u)) if (!c.isEmpty()) return false;
        return true;
    }

    /** El ítem del marco puesto en el cofre (cualquiera de sus mitades), o vacío. */
    public static ItemStack frameOf(ServerLevel level, Unit u) {
        AABB box = null;
        for (BlockPos p : u.parts()) box = box == null ? new AABB(p) : box.minmax(new AABB(p));
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, box.inflate(1.0))) {
            if (f.getItem().isEmpty()) continue;
            BlockPos on = f.getPos().relative(f.getDirection().getOpposite());
            if (u.parts().contains(on)) return f.getItem();
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ el plan

    /**
     * Qué guarda cada cofre ({@code assign}, por {@link Unit#key}), el ítem del marco de los que tienen ({@code frames}) y las
     * categorías que no entran en ningún lado ({@code missing}).
     */
    public record Plan(Map<BlockPos, Category> assign, Map<BlockPos, Item> frames, Set<Category> missing, List<Unit> units) {
        @Nullable
        public Unit unit(BlockPos key) {
            for (Unit u : units) if (u.key().equals(key)) return u;
            return null;
        }
    }

    private static String kindKey(ItemStack s) {
        CompoundTag t = s.getTag();
        return ForgeRegistries.ITEMS.getKey(s.getItem()) + (t == null ? "" : t.toString());
    }

    /** Espacios que ocupa cada categoría con las pilas juntas (Campamento + cofres). */
    public static Map<Category, Integer> slots(ServerLevel level, CampBlockEntity camp, List<Unit> units) {
        Map<String, int[]> kinds = new HashMap<>();   // {cantidad, tope de la pila, categoría}
        List<Container> all = new ArrayList<>();
        all.add(camp);
        for (Unit u : units) all.addAll(containers(level, u));
        for (Container c : all) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || keep(camp, s)) continue;
                int[] k = kinds.computeIfAbsent(kindKey(s), x -> new int[]{0, Math.max(1, s.getMaxStackSize()), of(s).ordinal()});
                k[0] += s.getCount();
            }
        }
        Map<Category, Integer> out = new EnumMap<>(Category.class);
        for (int[] k : kinds.values()) out.merge(Category.values()[k[2]], (k[0] + k[1] - 1) / k[1], Integer::sum);
        return out;
    }

    /**
     * Arma el plan a partir del anterior ({@code previous}: lo que ya tenía asignado se respeta). Marcos primero; después se
     * sueltan los cofres vacíos que sobran; después cada categoría con cosas recibe un cofre (de la más grande a la más chica;
     * si no alcanzan, el último libre es Varios y las chicas van ahí); por último más cofres a las que quedan llenas a más de
     * {@link #FILL}, pegados a los suyos.
     */
    public static Plan plan(ServerLevel level, CampBlockEntity camp, Map<BlockPos, Category> previous) {
        List<Unit> units = units(level, camp);
        Map<Category, Integer> need = slots(level, camp, units);
        Map<BlockPos, Category> assign = new LinkedHashMap<>();
        Map<BlockPos, Item> frames = new HashMap<>();
        Map<BlockPos, Integer> cap = new HashMap<>();
        for (Unit u : units) {
            cap.put(u.key(), capacity(level, u));
            ItemStack f = frameOf(level, u);
            if (!f.isEmpty()) {
                assign.put(u.key(), of(f));
                frames.put(u.key(), f.getItem());
            }
        }
        for (Unit u : units) {   // lo de antes
            Category c = previous.get(u.key());
            if (c != null && !assign.containsKey(u.key())) assign.put(u.key(), c);
        }
        for (Unit u : units) {   // cofres vacíos que sobran: se sueltan
            Category c = assign.get(u.key());
            if (c == null || frames.containsKey(u.key()) || !empty(level, u)) continue;
            int rest = capacityOf(assign, cap, c) - cap.get(u.key());
            if (rest * FILL >= need.getOrDefault(c, 0)) assign.remove(u.key());
        }
        List<Category> order = new ArrayList<>();
        for (Category c : Category.values()) if (need.getOrDefault(c, 0) > 0) order.add(c);
        order.sort(Comparator.comparingInt((Category c) -> need.getOrDefault(c, 0)).reversed());
        // Un cofre para cada una; si no alcanzan, el último libre es Varios
        List<Category> uncovered = new ArrayList<>();
        for (Category c : order) if (capacityOf(assign, cap, c) == 0) uncovered.add(c);
        int free = (int) units.stream().filter(u -> !assign.containsKey(u.key())).count();
        boolean needMisc = uncovered.size() > free && !assign.containsValue(Category.MISC);
        for (Category c : uncovered) {
            if (c == Category.MISC && needMisc) continue;   // se le da el último
            if (free <= (needMisc ? 1 : 0)) break;
            Unit u = pick(units, assign, c, camp.getBlockPos());
            if (u == null) break;
            assign.put(u.key(), c);
            free--;
        }
        if (needMisc) {
            Unit u = pick(units, assign, Category.MISC, camp.getBlockPos());
            if (u != null) assign.put(u.key(), Category.MISC);
        }
        // Más cofres para las que quedan llenas (lo que va a Varios lo suma Varios)
        Map<Category, Integer> want = new EnumMap<>(need);
        for (Category c : order) {
            if (c != Category.MISC && capacityOf(assign, cap, c) == 0) want.merge(Category.MISC, need.get(c), Integer::sum);
        }
        for (Category c : order) {
            if (capacityOf(assign, cap, c) == 0 && c != Category.MISC) continue;
            while (capacityOf(assign, cap, c) * FILL < want.getOrDefault(c, 0)) {
                Unit u = pick(units, assign, c, camp.getBlockPos());
                if (u == null) break;
                assign.put(u.key(), c);
            }
        }
        if (want.getOrDefault(Category.MISC, 0) > 0 && capacityOf(assign, cap, Category.MISC) == 0) {
            Unit u = pick(units, assign, Category.MISC, camp.getBlockPos());
            if (u != null) assign.put(u.key(), Category.MISC);
        }
        // Lo que no entra en ningún lado
        Set<Category> missing = new HashSet<>();
        int miscRoom = capacityOf(assign, cap, Category.MISC) - need.getOrDefault(Category.MISC, 0);
        for (Category c : order) {
            if (c == Category.MISC) continue;
            int over = need.get(c) - capacityOf(assign, cap, c);
            if (over <= 0) continue;
            if (miscRoom >= over) miscRoom -= over;
            else missing.add(c);
        }
        if (miscRoom < 0) missing.add(Category.MISC);
        return new Plan(assign, frames, missing, units);
    }

    private static int capacityOf(Map<BlockPos, Category> assign, Map<BlockPos, Integer> cap, Category c) {
        int n = 0;
        for (Map.Entry<BlockPos, Category> e : assign.entrySet()) if (e.getValue() == c) n += cap.getOrDefault(e.getKey(), 0);
        return n;
    }

    /** Un cofre libre para {@code c}: el más pegado a los que ya tiene, o el más cerca del Campamento. */
    @Nullable
    private static Unit pick(List<Unit> units, Map<BlockPos, Category> assign, Category c, BlockPos camp) {
        List<BlockPos> mine = new ArrayList<>();
        assign.forEach((k, v) -> {
            if (v == c) mine.add(k);
        });
        Unit best = null;
        double bestD = Double.MAX_VALUE;
        for (Unit u : units) {
            if (assign.containsKey(u.key())) continue;
            double d = u.key().distSqr(camp) * 0.01;   // a igualdad, el más cerca del Campamento
            if (!mine.isEmpty()) d = mine.stream().mapToDouble(m -> m.distSqr(u.key())).min().orElse(0) + d;
            if (d < bestD) {
                bestD = d;
                best = u;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ qué mover

    /** Un viaje: de {@code from} (un cofre o el Campamento) llevar {@code sample} (y los iguales) a {@code to}. */
    public record Task(BlockPos from, ItemStack sample, BlockPos to) {}

    /** ¿Dónde va esta pila? Un cofre con lugar (el del marco, los de su categoría, Varios), o null si en ningún lado. */
    @Nullable
    private static BlockPos target(ServerLevel level, Plan plan, ItemStack s) {
        Category c = of(s);
        for (Map.Entry<BlockPos, Item> e : plan.frames().entrySet()) {
            Unit u = plan.unit(e.getKey());
            if (e.getValue() == s.getItem() && u != null && room(containers(level, u), s) > 0) return u.key();
        }
        for (int pass = 0; pass < 2; pass++) {
            Category want = pass == 0 ? c : Category.MISC;
            for (Unit u : plan.units()) {
                if (plan.assign().get(u.key()) == want && room(containers(level, u), s) > 0) return u.key();
            }
        }
        return null;
    }

    /** ¿Esta pila está bien en este cofre? */
    private static boolean belongs(Plan plan, BlockPos unit, ItemStack s) {
        Item frame = plan.frames().get(unit);
        if (frame != null && frame == s.getItem()) return true;
        Category c = plan.assign().get(unit);
        if (c == null) return false;
        if (c == of(s)) return true;
        // En Varios está bien lo que no tiene cofre propio
        return c == Category.MISC && !plan.assign().containsValue(of(s));
    }

    /** El próximo viaje, o null si está todo en su lugar. Primero vacía el Campamento, después corrige los cofres. */
    @Nullable
    public static Task next(ServerLevel level, CampBlockEntity camp, Plan plan) {
        for (int i = 0; i < camp.getContainerSize(); i++) {
            ItemStack s = camp.getItem(i);
            if (s.isEmpty() || keep(camp, s)) continue;
            BlockPos to = target(level, plan, s);
            if (to != null) return new Task(camp.getBlockPos(), s.copy(), to);
        }
        for (Unit u : plan.units()) {
            for (Container c : containers(level, u)) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty() || belongs(plan, u.key(), s)) continue;
                    if (keep(camp, s)) {   // lo marcado para desguace vuelve al Campamento
                        if (!s.hasCustomHoverName() && room(List.of(camp), s) > 0) return new Task(u.key(), s.copy(), camp.getBlockPos());
                        continue;
                    }
                    BlockPos to = target(level, plan, s);
                    if (to == null && room(List.of(camp), s) > 0 && plan.assign().get(u.key()) != null) to = camp.getBlockPos();
                    if (to != null && !to.equals(u.key())) return new Task(u.key(), s.copy(), to);
                }
            }
        }
        return null;
    }

    /** Cuántos de {@code s} entran. */
    public static int room(List<Container> cs, ItemStack s) {
        int n = 0;
        for (Container c : cs) {
            int max = Math.min(s.getMaxStackSize(), c.getMaxStackSize());
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack x = c.getItem(i);
                if (x.isEmpty()) n += max;
                else if (ItemStack.isSameItemSameTags(x, s)) n += Math.max(0, max - x.getCount());
            }
        }
        return n;
    }

    /** Lo de un lugar ({@code pos}: el Campamento o un cofre del plan). */
    public static List<Container> at(ServerLevel level, CampBlockEntity camp, Plan plan, BlockPos pos) {
        if (pos.equals(camp.getBlockPos())) return List.of(camp);
        Unit u = plan.unit(pos);
        return u == null ? List.of() : containers(level, u);
    }

    /** Saca de {@code from} hasta {@link #STACKS_PER_TRIP} pilas iguales a {@code sample}, sin pasar de {@code max} ítems. */
    public static List<ItemStack> pick(List<Container> from, ItemStack sample, int max) {
        List<ItemStack> out = new ArrayList<>();
        for (Container c : from) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && out.size() < STACKS_PER_TRIP && max > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !ItemStack.isSameItemSameTags(s, sample)) continue;
                ItemStack got = s.split(Math.min(max, s.getCount()));
                max -= got.getCount();
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                out.add(got);
                changed = true;
            }
            if (changed) c.setChanged();
        }
        return out;
    }

    /** Mete {@code stack} en {@code to} (completa pilas, después espacios vacíos). Devuelve lo que no entró. */
    public static ItemStack put(List<Container> to, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
            for (Container c : to) {
                boolean changed = false;
                for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                    ItemStack x = c.getItem(i);
                    int max = Math.min(rest.getMaxStackSize(), c.getMaxStackSize());
                    if (pass == 0 && !x.isEmpty() && ItemStack.isSameItemSameTags(x, rest) && x.getCount() < max) {
                        int n = Math.min(rest.getCount(), max - x.getCount());
                        x.grow(n);
                        rest.shrink(n);
                        changed = true;
                    } else if (pass == 1 && x.isEmpty() && c.canPlaceItem(i, rest)) {
                        c.setItem(i, rest.split(Math.min(rest.getCount(), max)));
                        changed = true;
                    }
                }
                if (changed) c.setChanged();
            }
        }
        return rest;
    }

    /** Ordena adentro: junta las pilas y las acomoda (por categoría y después por ítem), repartidas en las mitades. */
    public static void sort(List<Container> cs) {
        List<ItemStack> all = new ArrayList<>();
        for (Container c : cs) for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) all.add(c.getItem(i).copy());
        if (all.isEmpty()) return;
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack s : all) {
            for (ItemStack m : merged) {
                if (s.isEmpty()) break;
                if (ItemStack.isSameItemSameTags(m, s) && m.getCount() < m.getMaxStackSize()) {
                    int n = Math.min(s.getCount(), m.getMaxStackSize() - m.getCount());
                    m.grow(n);
                    s.shrink(n);
                }
            }
            if (!s.isEmpty()) merged.add(s);
        }
        merged.sort(Comparator.comparingInt((ItemStack s) -> of(s).ordinal())
                .thenComparing(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())))
                .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));
        int k = 0;
        for (Container c : cs) {
            for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, k < merged.size() ? merged.get(k++) : ItemStack.EMPTY);
            c.setChanged();
        }
    }

    /** La tapa del cofre (o el barril) se abre o se cierra, con su sonido. */
    public static void lid(ServerLevel level, List<BlockPos> parts, boolean open) {
        for (BlockPos p : parts) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof ChestBlock) {
                level.blockEvent(p, st.getBlock(), 1, open ? 1 : 0);
            } else if (st.hasProperty(BarrelBlock.OPEN)) {
                level.setBlock(p, st.setValue(BarrelBlock.OPEN, open), 3);
            }
        }
        BlockPos p = parts.get(0);
        boolean barrel = level.getBlockState(p).hasProperty(BarrelBlock.OPEN);
        level.playSound(null, p, barrel ? (open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE)
                : (open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE), SoundSource.BLOCKS, 0.4F, 1.0F);
    }

    /** "Faltan cofres para: comida, minerales" (vacío si no falta nada). */
    public static Component missingText(Set<Category> missing) {
        if (missing.isEmpty()) return Component.empty();
        net.minecraft.network.chat.MutableComponent list = Component.empty();
        int i = 0;
        for (Category c : Category.values()) {
            if (!missing.contains(c)) continue;
            if (i++ > 0) list.append(", ");
            list.append(c.label());
        }
        return Component.translatable("status.warbandcraft.organizer.missing", list);
    }
}
