package com.ale.warbandcraft.invasion;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IA viva (H15a), por dimensión (data/warbandcraft_invasions.dat): las invasiones en curso (una por aldea) y la leva
 * de cada facción (soldados que puede mandar).
 */
public class InvasionData extends SavedData {
    private static final String FILE = "warbandcraft_invasions";

    public enum State { MARCHING, FIGHTING }

    /**
     * Qué clase de ataque es: una incursión (pocos, todos juntos, sin orden) o un ejército con general (varias
     * columnas, zapadores, escaleras, retirada ordenada). Ver {@link Assault}.
     */
    public enum Kind { RAID, ARMY }

    /** Un ejército de una facción contra tu aldea o castillo. */
    public static final class Invasion {
        public final BlockPos target;
        public final ResourceLocation faction;
        public final UUID owner;
        /** Soldados del ejército (en la pelea real: los que quedan, según la última cuenta). */
        public int size;
        public final long arriveTick;
        public State state = State.MARCHING;
        /** Pelea real: desde cuándo no queda ningún defensor (Long.MIN_VALUE = hay). */
        public long noDefendersSince = Long.MIN_VALUE;
        /** Pelea real: de qué lado vinieron (para el aviso y para aparecer). */
        public BlockPos from;
        /** Cuándo salió (para dibujarlo en el mapa, H15b). */
        public long startTick;
        /** Incursión o ejército con general. */
        public Kind kind = Kind.RAID;
        /** Pelea real: cuántos aparecieron (para saber cuándo se retiran). */
        public int startSize;
        /** Pelea real: escaleras que pusieron en tus muros (se sacan al terminar). */
        public final java.util.List<Long> ladders = new java.util.ArrayList<>();
        /** H23: los que esperan para entrar (tamaño de batalla): aparecen a medida que caen los de adelante. */
        public int reserve;
        /** Parte de guerra: cuántos vinieron en total (con los que se sumaron) y la mayor cantidad de defensores tuyos a la vez. */
        public int attackers, peakDefenders;

        public Invasion(BlockPos target, ResourceLocation faction, UUID owner, int size, long arriveTick, BlockPos from) {
            this.target = target;
            this.faction = faction;
            this.owner = owner;
            this.size = size;
            this.attackers = size;
            this.arriveTick = arriveTick;
            this.from = from;
            this.startTick = arriveTick - Invasions.COUNTDOWN_TICKS;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Target", target.asLong());
            t.putString("Faction", faction.toString());
            t.putUUID("Owner", owner);
            t.putInt("Size", size);
            t.putLong("Arrive", arriveTick);
            t.putString("State", state.name());
            t.putLong("NoDefenders", noDefendersSince);
            t.putLong("From", from.asLong());
            t.putLong("Start", startTick);
            t.putString("Kind", kind.name());
            t.putInt("StartSize", startSize);
            t.putLongArray("Ladders", ladders.stream().mapToLong(Long::longValue).toArray());
            t.putInt("Reserve", reserve);
            t.putInt("Attackers", attackers);
            t.putInt("PeakDefenders", peakDefenders);
            return t;
        }

        @Nullable
        static Invasion load(CompoundTag t) {
            ResourceLocation f = ResourceLocation.tryParse(t.getString("Faction"));
            if (f == null || !t.hasUUID("Owner")) return null;
            Invasion i = new Invasion(BlockPos.of(t.getLong("Target")), f, t.getUUID("Owner"), t.getInt("Size"), t.getLong("Arrive"),
                    BlockPos.of(t.getLong("From")));
            try {
                i.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException ignored) {
            }
            i.noDefendersSince = t.getLong("NoDefenders");
            if (t.contains("Start")) i.startTick = t.getLong("Start");
            try {
                if (t.contains("Kind")) i.kind = Kind.valueOf(t.getString("Kind"));
            } catch (IllegalArgumentException ignored) {
            }
            i.startSize = t.getInt("StartSize");
            for (long l : t.getLongArray("Ladders")) i.ladders.add(l);
            i.reserve = t.getInt("Reserve");
            if (t.contains("Attackers")) i.attackers = t.getInt("Attackers");
            i.peakDefenders = t.getInt("PeakDefenders");
            return i;
        }
    }

    /**
     * H15b: un ejército de una facción contra una aldea/castillo de otra facción. Al llegar se autoresuelve, salvo que
     * haya un jugador cerca: ahí se pelea de verdad ({@code fighting}) y el jugador se puede meter.
     */
    public static final class March {
        public final UUID id;
        public final ResourceLocation attacker;
        public final BlockPos target;
        public final BlockPos from;
        /** Soldados del ejército (en la pelea: los que quedan, según la última cuenta). */
        public int size;
        public final long startTick, arriveTick;
        /** Pelea real: está peleando, cuántos defensores quedaban en la última cuenta. */
        public boolean fighting;
        public int defenders;
        /** Jugadores que se metieron: le pegaron a los defensores (ayudaron al atacante) o a los atacantes. */
        public final java.util.Set<UUID> helpedAttack = new java.util.HashSet<>(), helpedDefense = new java.util.HashSet<>();
        /** Asedio (H17): escaleras que pusieron (se sacan al terminar). */
        public final java.util.List<Long> ladders = new java.util.ArrayList<>();
        /** H23: cuántos había de cada bando al empezar la pelea (para el botín de los que ayudaron). */
        public int startAttackers, startDefenders;
        /** H23: ya se avisó que llega pronto. */
        public boolean warned;

        public March(UUID id, ResourceLocation attacker, BlockPos target, BlockPos from, int size, long startTick, long arriveTick) {
            this.id = id;
            this.attacker = attacker;
            this.target = target;
            this.from = from;
            this.size = size;
            this.startTick = startTick;
            this.arriveTick = arriveTick;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("Attacker", attacker.toString());
            t.putLong("Target", target.asLong());
            t.putLong("From", from.asLong());
            t.putInt("Size", size);
            t.putLong("Start", startTick);
            t.putLong("Arrive", arriveTick);
            t.putBoolean("Fighting", fighting);
            t.putInt("Defenders", defenders);
            t.put("HelpedAttack", uuids(helpedAttack));
            t.put("HelpedDefense", uuids(helpedDefense));
            t.putLongArray("Ladders", ladders.stream().mapToLong(Long::longValue).toArray());
            t.putInt("StartAttackers", startAttackers);
            t.putInt("StartDefenders", startDefenders);
            t.putBoolean("Warned", warned);
            return t;
        }

        @Nullable
        static March load(CompoundTag t) {
            ResourceLocation a = ResourceLocation.tryParse(t.getString("Attacker"));
            if (a == null || !t.hasUUID("Id")) return null;
            March m = new March(t.getUUID("Id"), a, BlockPos.of(t.getLong("Target")), BlockPos.of(t.getLong("From")), t.getInt("Size"),
                    t.getLong("Start"), t.getLong("Arrive"));
            m.fighting = t.getBoolean("Fighting");
            m.defenders = t.getInt("Defenders");
            readUuids(t.getList("HelpedAttack", Tag.TAG_INT_ARRAY), m.helpedAttack);
            readUuids(t.getList("HelpedDefense", Tag.TAG_INT_ARRAY), m.helpedDefense);
            for (long l : t.getLongArray("Ladders")) m.ladders.add(l);
            m.startAttackers = t.getInt("StartAttackers");
            m.startDefenders = t.getInt("StartDefenders");
            m.warned = t.getBoolean("Warned");
            return m;
        }
    }

    /**
     * H23: la hueste de una facción acampada cerca de una aldea tuya, esperando que le presentes batalla. Si no vas antes
     * de {@code deadline}, marcha contra la aldea con todo. Ver {@link Hosts}.
     */
    public static final class Host {
        public enum State { CAMPED, FIGHTING }

        public final UUID id;
        public final ResourceLocation faction;
        public final UUID owner;
        /** La aldea/castillo tuyo que amenaza. */
        public final BlockPos target;
        /** Dónde acampa (un campo de batalla válido). */
        public final BlockPos camp;
        /** Hombres que le quedan (en la pelea: en el campo + los que esperan). */
        public int size;
        /** Con cuántos empezó la pelea (para saber cuándo se desbanda). */
        public int startSize;
        /** En la pelea: los que todavía no entraron (tamaño de batalla). */
        public int reserve;
        public final long createdTick;
        public long deadline;
        public State state = State.CAMPED;
        /** En la pelea: hacia dónde mira la hueste y dónde formó (las oleadas entran por detrás). */
        public double facingX, facingZ = 1;
        public BlockPos front;
        /** Último día en que se le recordó al dueño. */
        public long lastWarnDay = -1;
        /** Hacia dónde queda el campo desde el punto de reunión (las carpas de la hueste); 0,0 = alejándose de la aldea. */
        public double axisX, axisZ;

        public Host(UUID id, ResourceLocation faction, UUID owner, BlockPos target, BlockPos camp, int size, long createdTick, long deadline) {
            this.id = id;
            this.faction = faction;
            this.owner = owner;
            this.target = target;
            this.camp = camp;
            this.size = size;
            this.createdTick = createdTick;
            this.deadline = deadline;
            this.front = camp;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("Faction", faction.toString());
            t.putUUID("Owner", owner);
            t.putLong("Target", target.asLong());
            t.putLong("Camp", camp.asLong());
            t.putInt("Size", size);
            t.putInt("StartSize", startSize);
            t.putInt("Reserve", reserve);
            t.putLong("Created", createdTick);
            t.putLong("Deadline", deadline);
            t.putString("State", state.name());
            t.putDouble("FX", facingX);
            t.putDouble("FZ", facingZ);
            t.putLong("Front", front.asLong());
            t.putLong("WarnDay", lastWarnDay);
            t.putDouble("AxisX", axisX);
            t.putDouble("AxisZ", axisZ);
            return t;
        }

        @Nullable
        static Host load(CompoundTag t) {
            ResourceLocation f = ResourceLocation.tryParse(t.getString("Faction"));
            if (f == null || !t.hasUUID("Id") || !t.hasUUID("Owner")) return null;
            Host h = new Host(t.getUUID("Id"), f, t.getUUID("Owner"), BlockPos.of(t.getLong("Target")), BlockPos.of(t.getLong("Camp")),
                    t.getInt("Size"), t.getLong("Created"), t.getLong("Deadline"));
            h.startSize = t.getInt("StartSize");
            h.reserve = t.getInt("Reserve");
            try {
                h.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException ignored) {
            }
            h.facingX = t.getDouble("FX");
            h.facingZ = t.getDouble("FZ");
            if (t.contains("Front")) h.front = BlockPos.of(t.getLong("Front"));
            h.lastWarnDay = t.getLong("WarnDay");
            h.axisX = t.getDouble("AxisX");
            h.axisZ = t.getDouble("AxisZ");
            return h;
        }
    }

    private final Map<UUID, Host> hosts = new LinkedHashMap<>();
    /** H23: hombres que cada facción juntó para una hueste. */
    private final Map<ResourceLocation, Integer> hostPool = new HashMap<>();
    /** H23: hasta qué día una facción no manda otra hueste contra un jugador ("facción|jugador" → día). */
    private final Map<String, Long> hostCooldown = new HashMap<>();

    public Collection<Host> hosts() {
        return hosts.values();
    }

    @Nullable
    public Host host(UUID id) {
        return hosts.get(id);
    }

    public void putHost(Host h) {
        hosts.put(h.id, h);
        setDirty();
    }

    public void removeHost(UUID id) {
        if (hosts.remove(id) != null) setDirty();
    }

    public int hostPool(ResourceLocation faction) {
        return hostPool.getOrDefault(faction, 0);
    }

    public void setHostPool(ResourceLocation faction, int n) {
        hostPool.put(faction, Math.max(0, n));
        setDirty();
    }

    public long hostCooldown(ResourceLocation faction, UUID player) {
        return hostCooldown.getOrDefault(faction + "|" + player, -1L);
    }

    public void setHostCooldown(ResourceLocation faction, UUID player, long untilDay) {
        hostCooldown.put(faction + "|" + player, untilDay);
        setDirty();
    }

    /** {@link Threat}: hasta qué día un grupo de jugadores no recibe ningún ataque (se guarda con los descansos de las huestes). */
    public long restUntil(String group) {
        return hostCooldown.getOrDefault("rest|" + group, -1L);
    }

    public void setRest(String group, long untilDay) {
        hostCooldown.put("rest|" + group, untilDay);
        setDirty();
    }

    private static ListTag uuids(java.util.Set<UUID> set) {
        ListTag l = new ListTag();
        for (UUID u : set) l.add(net.minecraft.nbt.NbtUtils.createUUID(u));
        return l;
    }

    private static void readUuids(ListTag l, java.util.Set<UUID> into) {
        for (Tag x : l) into.add(net.minecraft.nbt.NbtUtils.loadUUID(x));
    }

    /** H15b: la marcha que está peleando contra {@code target}, o null. */
    @Nullable
    public March battleAt(BlockPos target) {
        for (March m : marches.values()) if (m.fighting && m.target.equals(target)) return m;
        return null;
    }

    private final Map<UUID, March> marches = new LinkedHashMap<>();
    /** H15b: guerras entre facciones de la IA ("a|b" ordenado → hasta qué día). */
    private final Map<String, Long> wars = new HashMap<>();

    public Collection<March> marches() {
        return marches.values();
    }

    public void putMarch(March m) {
        marches.put(m.id, m);
        setDirty();
    }

    public void removeMarch(UUID id) {
        if (marches.remove(id) != null) setDirty();
    }

    public static String warKey(ResourceLocation a, ResourceLocation b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    /** Hasta qué día están en guerra (-1 = en paz). */
    public long warUntil(ResourceLocation a, ResourceLocation b) {
        return wars.getOrDefault(warKey(a, b), -1L);
    }

    public void setWar(ResourceLocation a, ResourceLocation b, long untilDay) {
        if (untilDay < 0) wars.remove(warKey(a, b));
        else wars.put(warKey(a, b), untilDay);
        setDirty();
    }

    /** H15b: guarnición que espera aparecer en una aldea recién tomada por una facción (centro → soldados). */
    private final Map<Long, Integer> pending = new HashMap<>();

    public int pendingGarrison(BlockPos center) {
        return pending.getOrDefault(center.asLong(), 0);
    }

    public void setPendingGarrison(BlockPos center, int n) {
        if (n <= 0) {
            if (pending.remove(center.asLong()) != null) setDirty();
        } else {
            pending.put(center.asLong(), n);
            setDirty();
        }
    }

    public java.util.Set<Long> pendingCenters() {
        return java.util.Set.copyOf(pending.keySet());
    }

    private final Map<Long, Invasion> invasions = new LinkedHashMap<>();
    private final Map<ResourceLocation, Integer> levy = new HashMap<>();

    public static InvasionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(InvasionData::load, InvasionData::new, FILE);
    }

    @Nullable
    public Invasion at(BlockPos target) {
        return invasions.get(target.asLong());
    }

    public Collection<Invasion> all() {
        return invasions.values();
    }

    public void put(Invasion inv) {
        invasions.put(inv.target.asLong(), inv);
        setDirty();
    }

    public void remove(BlockPos target) {
        if (invasions.remove(target.asLong()) != null) setDirty();
    }

    public int levy(ResourceLocation faction) {
        return levy.getOrDefault(faction, 0);
    }

    public void setLevy(ResourceLocation faction, int n) {
        levy.put(faction, Math.max(0, n));
        setDirty();
    }

    public void changed() {
        setDirty();
    }

    public static InvasionData load(CompoundTag tag) {
        InvasionData d = new InvasionData();
        for (Tag x : tag.getList("Invasions", Tag.TAG_COMPOUND)) {
            Invasion i = Invasion.load((CompoundTag) x);
            if (i != null) d.invasions.put(i.target.asLong(), i);
        }
        CompoundTag lv = tag.getCompound("Levy");
        for (String k : lv.getAllKeys()) {
            ResourceLocation f = ResourceLocation.tryParse(k);
            if (f != null) d.levy.put(f, lv.getInt(k));
        }
        for (Tag x : tag.getList("Marches", Tag.TAG_COMPOUND)) {
            March m = March.load((CompoundTag) x);
            if (m != null) d.marches.put(m.id, m);
        }
        CompoundTag wars = tag.getCompound("Wars");
        for (String k : wars.getAllKeys()) d.wars.put(k, wars.getLong(k));
        CompoundTag pend = tag.getCompound("Pending");
        for (String k : pend.getAllKeys()) {
            try {
                d.pending.put(Long.parseLong(k), pend.getInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        for (Tag x : tag.getList("Hosts", Tag.TAG_COMPOUND)) {
            Host h = Host.load((CompoundTag) x);
            if (h != null) d.hosts.put(h.id, h);
        }
        CompoundTag pool = tag.getCompound("HostPool");
        for (String k : pool.getAllKeys()) {
            ResourceLocation f = ResourceLocation.tryParse(k);
            if (f != null) d.hostPool.put(f, pool.getInt(k));
        }
        CompoundTag cool = tag.getCompound("HostCooldown");
        for (String k : cool.getAllKeys()) d.hostCooldown.put(k, cool.getLong(k));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Invasion i : invasions.values()) list.add(i.save());
        tag.put("Invasions", list);
        CompoundTag lv = new CompoundTag();
        levy.forEach((f, n) -> lv.putInt(f.toString(), n));
        tag.put("Levy", lv);
        ListTag ml = new ListTag();
        for (March m : marches.values()) ml.add(m.save());
        tag.put("Marches", ml);
        CompoundTag wars = new CompoundTag();
        this.wars.forEach(wars::putLong);
        tag.put("Wars", wars);
        CompoundTag pend = new CompoundTag();
        pending.forEach((c, n) -> pend.putInt(Long.toString(c), n));
        tag.put("Pending", pend);
        ListTag hl = new ListTag();
        for (Host h : hosts.values()) hl.add(h.save());
        tag.put("Hosts", hl);
        CompoundTag pool = new CompoundTag();
        hostPool.forEach((f, n) -> pool.putInt(f.toString(), n));
        tag.put("HostPool", pool);
        CompoundTag cool = new CompoundTag();
        hostCooldown.forEach(cool::putLong);
        tag.put("HostCooldown", cool);
        return tag;
    }
}
