package com.ale.warbandcraft.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Las aldeas que ya tienen dueño, guardadas con el mundo (data/warbandcraft_villages.dat).
 * Una aldea se identifica por la posición de su campana (el centro que usan los aldeanos).
 */
public class VillageData extends SavedData {
    private static final String FILE = "warbandcraft_villages";

    /**
     * @param center      campana de la aldea (o el punto de reunión del castillo, H4)
     * @param faction     facción a la que pertenecía al descubrirla
     * @param foundedTick cuándo se la detectó
     * @param owner       jugador que la conquistó (null = sigue siendo de la facción)
     * @param lootedUntil hasta qué tick queda vacía por un saqueo (0 = nunca saqueada)
     * @param castle      true si es el castillo de la facción (H4) en vez de una aldea
     */
    public record Village(BlockPos center, ResourceLocation faction, long foundedTick,
                          @Nullable UUID owner, long lootedUntil, boolean castle) {
        public Village(BlockPos center, ResourceLocation faction, long foundedTick) {
            this(center, faction, foundedTick, null, 0L, false);
        }

        public boolean isConquered() {
            return owner != null;
        }

        public boolean isLooted(long gameTime) {
            return gameTime < lootedUntil;
        }

        public Village withOwner(@Nullable UUID newOwner) {
            return new Village(center, faction, foundedTick, newOwner, lootedUntil, castle);
        }

        public Village withLootedUntil(long tick) {
            return new Village(center, faction, foundedTick, owner, tick, castle);
        }

        public Village asCastle() {
            return new Village(center, faction, foundedTick, owner, lootedUntil, true);
        }
    }

    private final Map<Long, Village> villages = new LinkedHashMap<>();
    /** H10: lo que espera entrar al barril del almacén (centro → números; la aldea estaba descargada). */
    private final Map<Long, VillageStock.Stock> stocks = new LinkedHashMap<>();
    /** H10: dónde está el barril del almacén de cada aldea (centro → posición). */
    private final Map<Long, Long> storehouses = new LinkedHashMap<>();
    /** Ejércitos de prueba ({@code /warband prueba}): "aldeas" sin campana que solo existen para pelear. */
    private final java.util.Set<Long> tests = new java.util.HashSet<>();
    /** Aldeas que fundaste vos desde un Campamento ({@link Founding}): nunca fueron de la facción (no hay reconquista). */
    private final java.util.Set<Long> founded = new java.util.HashSet<>();
    /** Último día de juego en que se cobraron impuestos (para cobrar una sola vez por día). */
    private long lastTaxDay = -1;

    public static VillageData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(VillageData::load, VillageData::new, FILE);
    }

    public Collection<Village> all() {
        return villages.values();
    }

    /** Agrega o reemplaza (misma campana) una aldea. Si cambia de dueño, su almacén se vacía (H10). */
    public void add(Village village) {
        Village old = villages.put(village.center().asLong(), village);
        if (old == null || !java.util.Objects.equals(old.owner(), village.owner())) stocks.remove(village.center().asLong());
        if (old != null && !java.util.Objects.equals(old.owner(), village.owner())) founded.remove(village.center().asLong());
        setDirty();
    }

    /** H10: lo que tiene guardado la aldea de {@code center} (vacío si nada). */
    public VillageStock.Stock stock(BlockPos center) {
        return stocks.getOrDefault(center.asLong(), VillageStock.Stock.EMPTY);
    }

    /** H10: el barril del almacén de la aldea, o null si todavía no se puso. */
    @Nullable
    public BlockPos storehouse(BlockPos center) {
        Long pos = storehouses.get(center.asLong());
        return pos == null ? null : BlockPos.of(pos);
    }

    public void setStorehouse(BlockPos center, BlockPos pos) {
        storehouses.put(center.asLong(), pos.asLong());
        setDirty();
    }

    public void setStock(BlockPos center, VillageStock.Stock stock) {
        if (stock.isEmpty()) stocks.remove(center.asLong());
        else stocks.put(center.asLong(), stock);
        setDirty();
    }

    /** Saca una aldea (lo usan los ejércitos de prueba al terminar). */
    public void remove(BlockPos center) {
        villages.remove(center.asLong());
        stocks.remove(center.asLong());
        tests.remove(center.asLong());
        founded.remove(center.asLong());
        setDirty();
    }

    public void markFounded(BlockPos center) {
        founded.add(center.asLong());
        setDirty();
    }

    /** True si la fundaste vos (y sigue siendo tuya desde entonces). */
    public boolean isFounded(BlockPos center) {
        return founded.contains(center.asLong());
    }

    public void markTest(BlockPos center) {
        tests.add(center.asLong());
        setDirty();
    }

    /** True si es un ejército de prueba: no recibe refuerzos, no cambia opiniones, no se saquea ni se conquista. */
    public boolean isTest(BlockPos center) {
        return tests.contains(center.asLong());
    }

    public java.util.Set<Long> tests() {
        return java.util.Collections.unmodifiableSet(tests);
    }

    @Nullable
    public Village at(BlockPos center) {
        return villages.get(center.asLong());
    }

    public long lastTaxDay() {
        return lastTaxDay;
    }

    public void setLastTaxDay(long day) {
        this.lastTaxDay = day;
        setDirty();
    }

    /** La aldea más cercana a {@code pos} cuyo centro esté a menos de {@code maxDist} bloques (en horizontal). */
    @Nullable
    public Village nearest(BlockPos pos, double maxDist) {
        Village best = null;
        double bestSq = maxDist * maxDist;
        for (Village v : villages.values()) {
            double d = horizontalDistSq(v.center(), pos);
            if (d <= bestSq) {
                bestSq = d;
                best = v;
            }
        }
        return best;
    }

    /** Las aldeas ordenadas por distancia a {@code pos}. */
    public List<Village> byDistance(BlockPos pos) {
        List<Village> list = new ArrayList<>(villages.values());
        list.sort(Comparator.comparingDouble(v -> horizontalDistSq(v.center(), pos)));
        return list;
    }

    public static double horizontalDistSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ guardado

    public static VillageData load(CompoundTag tag) {
        VillageData data = new VillageData();
        for (Tag t : tag.getList("Villages", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            ResourceLocation faction = ResourceLocation.tryParse(c.getString("Faction"));
            if (faction == null) continue;
            BlockPos center = BlockPos.of(c.getLong("Center"));
            UUID owner = c.hasUUID("Owner") ? c.getUUID("Owner") : null;
            data.villages.put(center.asLong(), new Village(center, faction, c.getLong("Founded"), owner, c.getLong("LootedUntil"), c.getBoolean("Castle")));
        }
        data.lastTaxDay = tag.contains("LastTaxDay") ? tag.getLong("LastTaxDay") : -1;
        for (Tag t : tag.getList("Stocks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.stocks.put(c.getLong("Center"), new VillageStock.Stock(c.getInt("Emeralds"), c.getInt("Food"), c.getInt("Armament")));
        }
        for (Tag t : tag.getList("Storehouses", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.storehouses.put(c.getLong("Center"), c.getLong("Pos"));
        }
        for (long t : tag.getLongArray("Tests")) data.tests.add(t);
        for (long t : tag.getLongArray("PlayerFounded")) data.founded.add(t);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Village v : villages.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", v.center().asLong());
            c.putString("Faction", v.faction().toString());
            c.putLong("Founded", v.foundedTick());
            if (v.owner() != null) c.putUUID("Owner", v.owner());
            if (v.lootedUntil() > 0) c.putLong("LootedUntil", v.lootedUntil());
            if (v.castle()) c.putBoolean("Castle", true);
            list.add(c);
        }
        tag.put("Villages", list);
        tag.putLong("LastTaxDay", lastTaxDay);
        ListTag stockList = new ListTag();
        stocks.forEach((center, s) -> {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", center);
            c.putInt("Emeralds", s.emeralds());
            c.putInt("Food", s.food());
            c.putInt("Armament", s.armament());
            stockList.add(c);
        });
        tag.put("Stocks", stockList);
        ListTag houseList = new ListTag();
        storehouses.forEach((center, pos) -> {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", center);
            c.putLong("Pos", pos);
            houseList.add(c);
        });
        tag.put("Storehouses", houseList);
        tag.putLongArray("Tests", tests.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("PlayerFounded", founded.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
}
