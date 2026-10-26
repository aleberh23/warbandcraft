package com.ale.warbandcraft.upgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mejoras de aldeas y castillos (H12), por dimensión (data/warbandcraft_towns.dat): qué está hecho, dónde está
 * cada edificio y la obra en curso. Se guarda aparte de {@code VillageData}: si la aldea cambia de dueño, sus
 * muros y edificios siguen ahí.
 */
public class TownData extends SavedData {
    private static final String FILE = "warbandcraft_towns";

    /** Una obra en curso: los bloques en el orden en que se ponen. */
    public static final class Job {
        public final Upgrade upgrade;
        public final long startTick;
        public final int durationTicks;
        public final long[] positions;
        public final int[] states;
        public final List<BlockState> palette;
        /** Cuántos ya se pusieron. */
        public int placed;
        /** H22: lo que se pagó (para devolver lo que falta poner si se cancela) y la clave de su terreno ("" = ninguna). */
        public BuildCost.Bill paid = BuildCost.Bill.none();
        public String siteKey = "";
        /** El aldeano constructor, o null si todavía no apareció. */
        @Nullable
        public UUID builder;

        public Job(Upgrade upgrade, long startTick, int durationTicks, long[] positions, int[] states, List<BlockState> palette) {
            this.upgrade = upgrade;
            this.startTick = startTick;
            this.durationTicks = durationTicks;
            this.positions = positions;
            this.states = states;
            this.palette = palette;
        }

        public int size() {
            return positions.length;
        }

        public BlockPos pos(int i) {
            return BlockPos.of(positions[i]);
        }

        public BlockState state(int i) {
            return palette.get(states[i]);
        }

        /** Cuántos bloques ya deberían estar puestos en {@code now}. */
        public int due(long now) {
            if (durationTicks <= 0) return size();
            double f = Math.min(1.0, Math.max(0.0, (now - startTick) / (double) durationTicks));
            return (int) Math.ceil(f * size());
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Upgrade", upgrade.id);
            t.putLong("Start", startTick);
            t.putInt("Duration", durationTicks);
            t.putLongArray("Positions", positions);
            t.putIntArray("States", states);
            ListTag pal = new ListTag();
            for (BlockState s : palette) pal.add(NbtUtils.writeBlockState(s));
            t.put("Palette", pal);
            t.putInt("Placed", placed);
            t.putIntArray("Paid", paid.amounts());
            t.putInt("PaidEmeralds", paid.emeralds());
            t.putString("SiteKey", siteKey);
            if (builder != null) t.putUUID("Builder", builder);
            return t;
        }

        @Nullable
        static Job load(ServerLevel level, CompoundTag t) {
            Upgrade u = Upgrade.byId(t.getString("Upgrade"));
            if (u == null) return null;
            List<BlockState> palette = new ArrayList<>();
            for (Tag x : t.getList("Palette", Tag.TAG_COMPOUND)) {
                palette.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), (CompoundTag) x));
            }
            Job j = new Job(u, t.getLong("Start"), t.getInt("Duration"), t.getLongArray("Positions"), t.getIntArray("States"), palette);
            j.placed = t.getInt("Placed");
            int[] paid = t.getIntArray("Paid");
            if (paid.length == BuildCost.Material.values().length) j.paid = new BuildCost.Bill(paid, t.getInt("PaidEmeralds"), 0);
            j.siteKey = t.getString("SiteKey");
            if (t.hasUUID("Builder")) j.builder = t.getUUID("Builder");
            return j;
        }
    }

    /** Las mejoras de una aldea o castillo. */
    public static final class Town {
        public final BlockPos center;
        public final EnumSet<Upgrade> built = EnumSet.noneOf(Upgrade.class);
        /** Terreno de cada edificio: esquina mínima, con y = el piso. */
        public final Map<String, BlockPos> sites = new HashMap<>();
        /** H22: giro de cada terreno (0..3, horario; sin entrada = 0). */
        public final Map<String, Integer> rotations = new HashMap<>();
        /** Lado de cada terreno (sin entrada = {@link Blueprints#SITE}: un terreno de antes de los estilos). */
        public final Map<String, Integer> siteSizes = new HashMap<>();
        /** Estilo de los edificios ({@link Style}; "" = todavía no se eligió). */
        public String style = "";
        /** H22c: los bloques de la muralla dibujada, portones y torres como quedaron (para repararlos). */
        public final LinkedHashMap<Long, BlockState> defense = new LinkedHashMap<>();
        /** H22c: torres sueltas (centro, y = piso) y portones (el bloque de abajo de cada puerta de cerco). */
        public final List<BlockPos> towers = new ArrayList<>();
        public final List<BlockPos> gates = new ArrayList<>();
        /** Pedido de Ale: columnas (x, 0, z) del anillo de H12 que un jugador de la aldea rompió enteras: ya no son muralla. */
        public final java.util.Set<Long> cutColumns = new java.util.HashSet<>();
        /** La empalizada/muralla es el anillo automático de H12 (no una dibujada). */
        public boolean ringWalls;
        /** Radio del anillo de la empalizada/muralla (0 = todavía no se calculó). */
        public int ringRadius;
        /** Versión del anillo con la que se armó (2 = cerrado, con portones): las aldeas de facción de antes se completan solas. */
        public int ringVersion;
        @Nullable
        public Job job;
        /** Casas: si se construyen solas cuando faltan camas, y cuántas se hicieron. */
        public boolean autoHouses = true;
        public int houses;
        /** H19: reclutas esperando en la taberna (nivel de cada uno y el día en que llegó). */
        public final List<int[]> tavernWaiting = new ArrayList<>();
        /** H19: los de la taberna pasan solos al cuartel / barracón cada mañana. */
        public boolean autoRecruit = true;
        /** H19: había un tabernero tuyo la última vez que la aldea estuvo cargada. */
        public boolean innkeeper;
        /** H21: popularidad (0..100), raciones e impuestos (0..4, 2 = normal), minutos hacia el próximo que llega / se va,
         *  y si ayer no alcanzó la comida. */
        public double popularity = com.ale.warbandcraft.village.Popularity.START;
        public int rations = 2, taxes = 2, growthMinutes, leaveMinutes;
        public boolean hungry;
        /** H21: aldeanos libres pasan solos a las zonas de trabajo que no tienen a nadie. */
        public boolean autoAssign = true;
        /** H21: hasta este tick la aldea sufre un ataque reciente (saqueo o invasión). */
        public long troubledUntil;
        /** H21, aldea descargada: lo último que se midió (cambio por minuto, habitantes, camas libres) y los que
         *  llegaron o se fueron "en papel" (aparecen o se van al cargarse). */
        public int lastDelta, paperPop, paperBeds, paperIn, paperOut;
        /** Aldea de facción (pedido de Ale, "darle vida"): su tamaño (0 = sin decidir, 1 aldea, 2 pueblo, 3 ciudad) y si
         *  ya tiene lo de su tamaño (lo de antes se pone de una; lo que crece después, con obra y constructor). */
        public int npcTier;
        public boolean npcReady;
        /** Aldea de facción: soldados de su reserva que ya se perdieron ({@link com.ale.warbandcraft.village.GarrisonReserve}). */
        public int npcReserveUsed;

        Town(BlockPos center) {
            this.center = center;
        }

        /** Nivel del constructor: el del taller más alto hecho (0 = no hay taller). */
        public int builderLevel() {
            int n = 0;
            for (Upgrade u : built) n = Math.max(n, u.givesBuilder());
            return n;
        }

        /** 1 = aldea, 2 = pueblo, 3 = ciudad. */
        public int level() {
            return built.contains(Upgrade.CITY) ? 3 : built.contains(Upgrade.TOWN) ? 2 : 1;
        }

        /** El lado del terreno {@code key}. */
        public int siteSize(String key) {
            return siteSizes.getOrDefault(key, Blueprints.SITE);
        }

        /** El terreno {@code key} es de antes de los estilos (9×9, edificio viejo). */
        public boolean legacySite(String key) {
            return sites.containsKey(key) && !siteSizes.containsKey(key);
        }

        public void putSite(String key, BlockPos corner, int size, int rot) {
            sites.put(key, corner);
            siteSizes.put(key, size);
            rotations.put(key, rot);
        }

        public void removeSite(String key) {
            sites.remove(key);
            siteSizes.remove(key);
            rotations.remove(key);
        }

        /** Los terrenos ocupados con su lado, menos {@code except}. */
        public List<Blueprints.Lot> lots(@Nullable String except) {
            List<Blueprints.Lot> out = new ArrayList<>();
            sites.forEach((k, p) -> {
                if (!k.equals(except)) out.add(new Blueprints.Lot(p, siteSize(k)));
            });
            return out;
        }

        public boolean has(Upgrade u) {
            return built.contains(u);
        }

        /** H19: nivel de la taberna (0 = no hay). */
        public int tavernLevel() {
            int n = 0;
            for (Upgrade u : built) n = Math.max(n, u.tavernLevel());
            return n;
        }

        /** H24: nivel del mercado (0 = no hay). */
        public int marketLevel() {
            int n = 0;
            for (Upgrade u : built) n = Math.max(n, u.marketLevel());
            return n;
        }

        /** H19: nivel del campo de entrenamiento (0 = no hay). */
        public int trainingLevel() {
            int n = 0;
            for (Upgrade u : built) n = Math.max(n, u.trainingLevel());
            return n;
        }
    }

    private final Map<Long, Town> towns = new LinkedHashMap<>();
    public static TownData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(t -> load(level, t), TownData::new, FILE);
    }

    @Nullable
    public Town at(BlockPos center) {
        return towns.get(center.asLong());
    }

    public Town getOrCreate(BlockPos center) {
        return towns.computeIfAbsent(center.asLong(), k -> {
            setDirty();
            return new Town(center);
        });
    }

    public Collection<Town> all() {
        return towns.values();
    }

    public void remove(BlockPos center) {
        if (towns.remove(center.asLong()) != null) setDirty();
    }

    public void changed() {
        setDirty();
    }

    // ------------------------------------------------------------------ guardado

    static TownData load(ServerLevel level, CompoundTag tag) {
        TownData data = new TownData();
        for (Tag x : tag.getList("Towns", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            Town t = new Town(BlockPos.of(c.getLong("Center")));
            for (Tag b : c.getList("Built", Tag.TAG_STRING)) {
                Upgrade u = Upgrade.byId(b.getAsString());
                if (u != null) t.built.add(u);
            }
            CompoundTag sites = c.getCompound("Sites");
            for (String k : sites.getAllKeys()) t.sites.put(k, BlockPos.of(sites.getLong(k)));
            CompoundTag rots = c.getCompound("Rotations");
            for (String k : rots.getAllKeys()) t.rotations.put(k, rots.getInt(k));
            CompoundTag sizes = c.getCompound("SiteSizes");
            for (String k : sizes.getAllKeys()) t.siteSizes.put(k, sizes.getInt(k));
            t.style = c.getString("Style");
            t.ringRadius = c.getInt("Ring");
            t.ringVersion = c.getInt("RingVersion");
            t.ringWalls = c.contains("RingWalls") ? c.getBoolean("RingWalls") : t.has(Upgrade.PALISADE) || t.has(Upgrade.WALL);
            CompoundTag def = c.getCompound("Defense");
            long[] dp = def.getLongArray("Positions");
            int[] ds = def.getIntArray("States");
            List<BlockState> dpal = new ArrayList<>();
            for (Tag dx : def.getList("Palette", Tag.TAG_COMPOUND)) {
                dpal.add(NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), (CompoundTag) dx));
            }
            for (int i = 0; i < dp.length && i < ds.length; i++) if (ds[i] < dpal.size()) t.defense.put(dp[i], dpal.get(ds[i]));
            for (long l : c.getLongArray("Towers")) t.towers.add(BlockPos.of(l));
            for (long l : c.getLongArray("Gates")) t.gates.add(BlockPos.of(l));
            for (long l : c.getLongArray("CutColumns")) t.cutColumns.add(l);
            t.autoHouses = !c.getBoolean("NoAutoHouses");
            t.houses = c.getInt("Houses");
            for (net.minecraft.nbt.Tag w : c.getList("Tavern", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) {
                int[] a = ((net.minecraft.nbt.IntArrayTag) w).getAsIntArray();
                if (a.length == 2) t.tavernWaiting.add(a);
            }
            t.autoRecruit = !c.getBoolean("NoAutoRecruit");
            t.innkeeper = c.getBoolean("Innkeeper");
            if (c.contains("Popularity")) t.popularity = c.getDouble("Popularity");
            if (c.contains("Rations")) t.rations = c.getInt("Rations");
            if (c.contains("Taxes")) t.taxes = c.getInt("Taxes");
            t.growthMinutes = c.getInt("Growth");
            t.leaveMinutes = c.getInt("Leave");
            t.hungry = c.getBoolean("Hungry");
            t.autoAssign = !c.getBoolean("NoAutoAssign");
            t.troubledUntil = c.getLong("Troubled");
            t.lastDelta = c.getInt("LastDelta");
            t.paperPop = c.getInt("PaperPop");
            t.paperBeds = c.getInt("PaperBeds");
            t.paperIn = c.getInt("PaperIn");
            t.paperOut = c.getInt("PaperOut");
            t.npcTier = c.getInt("NpcTier");
            t.npcReady = c.getBoolean("NpcReady");
            t.npcReserveUsed = c.getInt("NpcReserveUsed");
            if (c.contains("Job")) t.job = Job.load(level, c.getCompound("Job"));
            data.towns.put(t.center.asLong(), t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Town t : towns.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Center", t.center.asLong());
            ListTag built = new ListTag();
            for (Upgrade u : t.built) built.add(net.minecraft.nbt.StringTag.valueOf(u.id));
            c.put("Built", built);
            CompoundTag sites = new CompoundTag();
            t.sites.forEach((k, p) -> sites.putLong(k, p.asLong()));
            c.put("Sites", sites);
            CompoundTag rots = new CompoundTag();
            t.rotations.forEach(rots::putInt);
            c.put("Rotations", rots);
            CompoundTag sizes = new CompoundTag();
            t.siteSizes.forEach(sizes::putInt);
            c.put("SiteSizes", sizes);
            c.putString("Style", t.style);
            c.putInt("Ring", t.ringRadius);
            c.putInt("RingVersion", t.ringVersion);
            c.putBoolean("RingWalls", t.ringWalls);
            CompoundTag def = new CompoundTag();
            long[] dp = new long[t.defense.size()];
            int[] ds = new int[t.defense.size()];
            List<BlockState> dpal = new ArrayList<>();
            Map<BlockState, Integer> didx = new HashMap<>();
            int di = 0;
            for (Map.Entry<Long, BlockState> e : t.defense.entrySet()) {
                dp[di] = e.getKey();
                ds[di++] = didx.computeIfAbsent(e.getValue(), st -> {
                    dpal.add(st);
                    return dpal.size() - 1;
                });
            }
            def.putLongArray("Positions", dp);
            def.putIntArray("States", ds);
            ListTag dpalTag = new ListTag();
            for (BlockState st : dpal) dpalTag.add(NbtUtils.writeBlockState(st));
            def.put("Palette", dpalTag);
            c.put("Defense", def);
            c.putLongArray("Towers", t.towers.stream().mapToLong(BlockPos::asLong).toArray());
            c.putLongArray("Gates", t.gates.stream().mapToLong(BlockPos::asLong).toArray());
            if (!t.cutColumns.isEmpty()) c.putLongArray("CutColumns", t.cutColumns.stream().mapToLong(Long::longValue).toArray());
            if (!t.autoHouses) c.putBoolean("NoAutoHouses", true);
            c.putInt("Houses", t.houses);
            net.minecraft.nbt.ListTag tav = new net.minecraft.nbt.ListTag();
            for (int[] w : t.tavernWaiting) tav.add(new net.minecraft.nbt.IntArrayTag(w));
            c.put("Tavern", tav);
            if (!t.autoRecruit) c.putBoolean("NoAutoRecruit", true);
            if (t.innkeeper) c.putBoolean("Innkeeper", true);
            c.putDouble("Popularity", t.popularity);
            c.putInt("Rations", t.rations);
            c.putInt("Taxes", t.taxes);
            c.putInt("Growth", t.growthMinutes);
            c.putInt("Leave", t.leaveMinutes);
            if (t.hungry) c.putBoolean("Hungry", true);
            if (!t.autoAssign) c.putBoolean("NoAutoAssign", true);
            if (t.troubledUntil > 0) c.putLong("Troubled", t.troubledUntil);
            c.putInt("LastDelta", t.lastDelta);
            c.putInt("PaperPop", t.paperPop);
            c.putInt("PaperBeds", t.paperBeds);
            c.putInt("PaperIn", t.paperIn);
            c.putInt("PaperOut", t.paperOut);
            if (t.npcTier > 0) c.putInt("NpcTier", t.npcTier);
            if (t.npcReady) c.putBoolean("NpcReady", true);
            if (t.npcReserveUsed > 0) c.putInt("NpcReserveUsed", t.npcReserveUsed);
            if (t.job != null) c.put("Job", t.job.save());
            list.add(c);
        }
        tag.put("Towns", list);
        return tag;
    }
}
