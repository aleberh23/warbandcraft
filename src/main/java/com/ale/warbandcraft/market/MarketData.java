package com.ale.warbandcraft.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H24: el mercado de cada aldea/castillo tuyo, por dimensión (data/warbandcraft_markets.dat): qué se vende y hasta
 * dónde (mínimo), los envíos programados y las últimas ventas. Se borra si la aldea cambia de dueño.
 */
public class MarketData extends SavedData {
    private static final String FILE = "warbandcraft_markets";

    /** Qué hacer con un ítem del almacén: no bajar de {@code min}; vender (sí/no) a {@code price} esmeraldas por lote. */
    public static final class Rule {
        public int min, price;
        public boolean sell;

        public Rule(int min, int price, boolean sell) {
            this.min = min;
            this.price = price;
            this.sell = sell;
        }
    }

    /** Una línea de un envío programado: {@code amount} de {@code item} (o, con {@code refill}, que el destino tenga al menos eso). */
    public static final class Line {
        public final String item;
        public int amount;
        public boolean refill;

        public Line(String item, int amount, boolean refill) {
            this.item = item;
            this.amount = amount;
            this.refill = refill;
        }
    }

    /** Un envío programado desde la aldea hacia {@code to} (aldea/castillo o Campamento), cada {@code every} días. */
    public static final class Schedule {
        public final UUID id;
        @Nullable
        public BlockPos to;
        public int every = 1;
        /** El día de juego en que sale el próximo. */
        public long nextDay;
        public boolean paused;
        public final List<Line> lines = new ArrayList<>();

        public Schedule(UUID id) {
            this.id = id;
        }
    }

    /** Una venta: quién compró, qué, cuánto pagó y el día. */
    public record Sale(String buyer, String item, int count, int emeralds, long day) {}

    public static final class Market {
        public final BlockPos center;
        /** El dueño de la aldea cuando se armó: si cambia, el mercado arranca de cero. */
        @Nullable
        public UUID owner;
        public final Map<String, Rule> rules = new LinkedHashMap<>();
        public final List<Schedule> schedules = new ArrayList<>();
        public final List<Sale> sales = new ArrayList<>();

        Market(BlockPos center) {
            this.center = center;
        }

        @Nullable
        public Schedule schedule(UUID id) {
            for (Schedule s : schedules) if (s.id.equals(id)) return s;
            return null;
        }
    }

    private final Map<Long, Market> markets = new LinkedHashMap<>();
    /** Última cuenta de cada almacén (pos del Campamento → ítem → cuántos), para la Lonja. No se guarda. */
    final Map<Long, Map<String, Integer>> snapshots = new HashMap<>();

    public static MarketData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(MarketData::load, MarketData::new, FILE);
    }

    @Nullable
    public Market at(BlockPos center) {
        return markets.get(center.asLong());
    }

    /** El mercado de la aldea de {@code owner} (si era de otro dueño, se borra y arranca de cero). */
    public Market getOrCreate(BlockPos center, UUID owner) {
        Market m = markets.get(center.asLong());
        if (m == null || !owner.equals(m.owner)) {
            m = new Market(center);
            m.owner = owner;
            markets.put(center.asLong(), m);
            setDirty();
        }
        return m;
    }

    public Collection<Market> all() {
        return markets.values();
    }

    public void remove(BlockPos center) {
        if (markets.remove(center.asLong()) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }

    // ------------------------------------------------------------------ guardado

    static MarketData load(CompoundTag tag) {
        MarketData data = new MarketData();
        for (Tag x : tag.getList("Markets", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            Market m = new Market(BlockPos.of(c.getLong("Center")));
            if (c.hasUUID("Owner")) m.owner = c.getUUID("Owner");
            CompoundTag rules = c.getCompound("Rules");
            for (String k : rules.getAllKeys()) {
                CompoundTag r = rules.getCompound(k);
                m.rules.put(k, new Rule(r.getInt("Min"), r.getInt("Price"), r.getBoolean("Sell")));
            }
            for (Tag sx : c.getList("Schedules", Tag.TAG_COMPOUND)) {
                CompoundTag st = (CompoundTag) sx;
                Schedule s = new Schedule(st.getUUID("Id"));
                s.to = st.contains("To") ? BlockPos.of(st.getLong("To")) : null;
                s.every = Math.max(1, st.getInt("Every"));
                s.nextDay = st.getLong("Next");
                s.paused = st.getBoolean("Paused");
                for (Tag lx : st.getList("Lines", Tag.TAG_COMPOUND)) {
                    CompoundTag lt = (CompoundTag) lx;
                    s.lines.add(new Line(lt.getString("Item"), lt.getInt("Amount"), lt.getBoolean("Refill")));
                }
                m.schedules.add(s);
            }
            for (Tag sx : c.getList("Sales", Tag.TAG_COMPOUND)) {
                CompoundTag st = (CompoundTag) sx;
                m.sales.add(new Sale(st.getString("Buyer"), st.getString("Item"), st.getInt("Count"), st.getInt("Emeralds"), st.getLong("Day")));
            }
            data.markets.put(m.center.asLong(), m);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Market m : markets.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", m.center.asLong());
            if (m.owner != null) c.putUUID("Owner", m.owner);
            CompoundTag rules = new CompoundTag();
            m.rules.forEach((k, r) -> {
                CompoundTag rt = new CompoundTag();
                rt.putInt("Min", r.min);
                rt.putInt("Price", r.price);
                rt.putBoolean("Sell", r.sell);
                rules.put(k, rt);
            });
            c.put("Rules", rules);
            ListTag schedules = new ListTag();
            for (Schedule s : m.schedules) {
                CompoundTag st = new CompoundTag();
                st.putUUID("Id", s.id);
                if (s.to != null) st.putLong("To", s.to.asLong());
                st.putInt("Every", s.every);
                st.putLong("Next", s.nextDay);
                st.putBoolean("Paused", s.paused);
                ListTag lines = new ListTag();
                for (Line l : s.lines) {
                    CompoundTag lt = new CompoundTag();
                    lt.putString("Item", l.item);
                    lt.putInt("Amount", l.amount);
                    lt.putBoolean("Refill", l.refill);
                    lines.add(lt);
                }
                st.put("Lines", lines);
                schedules.add(st);
            }
            c.put("Schedules", schedules);
            ListTag sales = new ListTag();
            for (Sale s : m.sales) {
                CompoundTag st = new CompoundTag();
                st.putString("Buyer", s.buyer());
                st.putString("Item", s.item());
                st.putInt("Count", s.count());
                st.putInt("Emeralds", s.emeralds());
                st.putLong("Day", s.day());
                sales.add(st);
            }
            c.put("Sales", sales);
            list.add(c);
        }
        tag.put("Markets", list);
        return tag;
    }
}
