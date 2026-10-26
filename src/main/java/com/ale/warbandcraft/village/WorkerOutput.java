package com.ale.warbandcraft.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pedido de Ale: lo que produce cada trabajador de tus aldeas, aunque no esté cargado. Mientras se lo atiende
 * ({@link WorkerSupply#serve}) se anota lo que deja en el almacén y cuánto tiempo estuvo trabajando: eso da su producción
 * por día. El tiempo que no se lo atendió (aldea o puesto sin cargar) se paga de golpe con esa producción cuando el
 * almacén de la aldea está cargado ("en papel"). Sin medir todavía, se usa lo que se espera de su oficio.
 */
public class WorkerOutput extends SavedData {
    private static final String FILE = "warbandcraft_worker_output";
    /** Un día de Minecraft. */
    public static final long DAY = 24000L;
    /** Con menos que esto medido se usa lo esperado del oficio. */
    public static int MIN_MEASURE_TICKS = 12000;
    /** Lo medido se va olvidando: pasado esto, se parte al medio (cuenta más lo último). */
    public static int MEASURE_WINDOW_TICKS = 72000;
    /** Más que esto sin atenderlo no se paga (días). */
    public static int MAX_OFFLINE_DAYS = 7;
    /** Un hueco más largo que esto entre dos atenciones = estuvo sin cargar. */
    public static int GAP_TICKS = 300;

    /** Un trabajador: de qué aldea es, dónde anda, si es un puesto de afuera, lo medido y lo esperado (ítems por día). */
    public static final class Rec {
        public final UUID worker;
        public BlockPos village;
        public BlockPos pos;
        public boolean outpost;
        public final Map<String, Double> made = new HashMap<>(), estimate = new HashMap<>(), carry = new HashMap<>();
        public long loadedTicks, lastTick;
        int missing;

        Rec(UUID worker, BlockPos village, BlockPos pos) {
            this.worker = worker;
            this.village = village;
            this.pos = pos;
        }

        /**
         * Ítems por día: lo medido si alcanza, si no lo esperado. Pedido de Ale ("al volver no veo lo que generan"): lo
         * esperado de su oficio es el piso (un trabajador con zona nunca da menos que eso), y las semillas no cuentan.
         */
        public Map<String, Double> rate() {
            Map<String, Double> out = new HashMap<>(estimate);
            if (loadedTicks >= MIN_MEASURE_TICKS) made.forEach((k, n) -> out.merge(k, n * DAY / loadedTicks, Math::max));
            out.keySet().removeIf(WorkerOutput::junk);
            return out;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("worker", worker);
            t.putLong("village", village.asLong());
            t.putLong("pos", pos.asLong());
            t.putBoolean("outpost", outpost);
            t.put("made", map(made));
            t.put("estimate", map(estimate));
            t.put("carry", map(carry));
            t.putLong("loaded", loadedTicks);
            t.putLong("last", lastTick);
            return t;
        }

        static Rec load(CompoundTag t) {
            Rec r = new Rec(t.getUUID("worker"), BlockPos.of(t.getLong("village")), BlockPos.of(t.getLong("pos")));
            r.outpost = t.getBoolean("outpost");
            read(t.getCompound("made"), r.made);
            read(t.getCompound("estimate"), r.estimate);
            read(t.getCompound("carry"), r.carry);
            r.loadedTicks = t.getLong("loaded");
            r.lastTick = t.getLong("last");
            return r;
        }

        private static CompoundTag map(Map<String, Double> m) {
            CompoundTag t = new CompoundTag();
            m.forEach(t::putDouble);
            return t;
        }

        private static void read(CompoundTag t, Map<String, Double> m) {
            for (String k : t.getAllKeys()) m.put(k, t.getDouble(k));
        }
    }

    private final Map<UUID, Rec> recs = new LinkedHashMap<>();

    public static WorkerOutput get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(WorkerOutput::load, WorkerOutput::new, FILE);
    }

    @Nullable
    public Rec of(UUID worker) {
        return recs.get(worker);
    }

    /** El de este trabajador (se crea la primera vez, sin hueco que pagar). */
    public Rec ensure(UUID worker, BlockPos village, BlockPos pos, long now) {
        Rec r = recs.computeIfAbsent(worker, k -> {
            Rec n = new Rec(worker, village, pos);
            n.lastTick = now;
            return n;
        });
        setDirty();
        return r;
    }

    public void remove(UUID worker) {
        if (recs.remove(worker) != null) setDirty();
    }

    /** Los de una aldea. */
    public List<Rec> of(BlockPos village) {
        List<Rec> out = new ArrayList<>();
        for (Rec r : recs.values()) if (r.village.equals(village)) out.add(r);
        return out;
    }

    /**
     * Pedido de Ale: "tiene que haber alguien de la facción conectado para que produzcan y consuman". El que es de alguien sin
     * nadie de su facción conectado (aldea: su dueño; Campamento común: el del Campamento) no junta tiempo sin atender.
     */
    public void freeze(ServerLevel level, long now) {
        long delta = lastFreeze < 0 || now < lastFreeze ? 0 : now - lastFreeze;   // lo que pasó desde la última vez
        lastFreeze = now;
        if (delta <= 0) return;
        for (Rec r : recs.values()) {
            VillageData.Village v = VillageData.get(level).at(r.village);
            UUID owner = v != null ? v.owner() : com.ale.warbandcraft.camp.CampRegistry.get(level).ownerOf(r.village);
            if (owner == null || com.ale.warbandcraft.faction.OnlineDays.active(level, owner)) continue;
            // se corre el reloj (no se pisa): lo que juntó antes de que se desconectaran, se le sigue debiendo
            r.lastTick = Math.min(now, r.lastTick + delta);
            setDirty();
        }
    }

    /** Última vez que se corrió {@link #freeze} (en memoria; -1 = todavía no). */
    private long lastFreeze = -1;

    /** Se lo atendió ahora: cuenta el tiempo trabajado (si fue seguido) y devuelve cuánto hubo sin atenderlo (0 = nada). */
    public long seen(Rec r, long now) {
        long gap = Math.max(0, now - r.lastTick);
        r.lastTick = now;
        setDirty();
        if (gap > GAP_TICKS) return gap;
        r.loadedTicks += gap;
        if (r.loadedTicks > MEASURE_WINDOW_TICKS) {
            r.loadedTicks /= 2;
            r.made.replaceAll((k, n) -> n / 2);
        }
        return 0;
    }

    /** Dejó {@code s} en el almacén. */
    public void made(Rec r, ItemStack s) {
        if (s.isEmpty()) return;
        r.made.merge(key(s.getItem()), (double) s.getCount(), Double::sum);
        setDirty();
    }

    /** Lo que produjo en {@code ticks} sin cargar (como mucho {@link #MAX_OFFLINE_DAYS}); lo que no llega a uno queda para la próxima. */
    public List<ItemStack> owed(Rec r, long ticks) {
        double days = Math.min(ticks, (long) MAX_OFFLINE_DAYS * DAY) / (double) DAY;
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : r.rate().entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(e.getKey()));
            if (item == null || item == Items.AIR) continue;
            double v = e.getValue() * days + r.carry.getOrDefault(e.getKey(), 0D);
            int n = (int) Math.floor(v);
            r.carry.put(e.getKey(), v - n);
            while (n > 0) {
                int c = Math.min(n, item.getMaxStackSize());
                out.add(new ItemStack(item, c));
                n -= c;
            }
        }
        setDirty();
        return out;
    }

    /** "≈ 32 Tronco de roble, 4 Retoño de roble por día" (las 3 cosas que más da), o vacío si no produce nada. */
    public static Component text(@Nullable Rec r) {
        if (r == null) return Component.empty();
        List<Map.Entry<String, Double>> list = new ArrayList<>(r.rate().entrySet());
        list.removeIf(e -> e.getValue() < 0.5);
        if (list.isEmpty()) return Component.translatable("status.warbandcraft.worker.produces_nothing");
        list.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        MutableComponent items = Component.empty();
        for (int i = 0; i < list.size() && i < 3; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(list.get(i).getKey()));
            if (item == null) continue;
            if (i > 0) items.append(", ");
            items.append(Math.round(list.get(i).getValue()) + " ").append(new ItemStack(item).getHoverName());
        }
        return Component.translatable(r.loadedTicks < MIN_MEASURE_TICKS ? "status.warbandcraft.worker.produces_guess"
                : "status.warbandcraft.worker.produces", items);
    }

    /** Lo de toda la aldea por día, sumado (ítem → cantidad). */
    public Map<Item, Double> total(BlockPos village) {
        Map<Item, Double> out = new HashMap<>();
        for (Rec r : of(village)) {
            r.rate().forEach((k, n) -> {
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(k));
                if (item != null && item != Items.AIR) out.merge(item, n, Double::sum);
            });
        }
        return out;
    }

    /** Lo que no sirve en el almacén: semillas (al granjero le aparecen solas). */
    static boolean junk(String key) {
        return key.equals("minecraft:wheat_seeds") || key.equals("minecraft:beetroot_seeds") || key.equals("minecraft:melon_seeds")
                || key.equals("minecraft:pumpkin_seeds") || key.equals("minecraft:torchflower_seeds") || key.equals("minecraft:pitcher_pod");
    }

    public static String key(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    /** Se lo ve muerto/borrado: con su lugar cargado y sin él, varias veces seguidas → se olvida. */
    boolean missing(Rec r, int limit) {
        r.missing++;
        if (r.missing < limit) return false;
        remove(r.worker);
        return true;
    }

    void found(Rec r) {
        r.missing = 0;
    }

    public static WorkerOutput load(CompoundTag tag) {
        WorkerOutput d = new WorkerOutput();
        for (Tag t : tag.getList("recs", Tag.TAG_COMPOUND)) {
            Rec r = Rec.load((CompoundTag) t);
            d.recs.put(r.worker, r);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Rec r : recs.values()) list.add(r.save());
        tag.put("recs", list);
        return tag;
    }
}
