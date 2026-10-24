package com.ale.warbandcraft.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Barracones (H8): los soldados guardados de cada Campamento, como fichas (por dimensión, igual que
 * {@link CampRegistry}). Un soldado guardado no existe como entidad: es su NBT completo más unos datos
 * para mostrarlo en la pestaña sin tener que leer ese NBT.
 */
public class BarracksData extends SavedData {
    private static final String FILE = "warbandcraft_barracks";

    /** Cuántos soldados entran en el barracón de un Campamento (H12: el cuartel lo sube). */
    public static int CAPACITY = 40;
    /** Puntos de comida (los de la barra de hambre) que come cada soldado guardado por día. */
    public static int FOOD_PER_SOLDIER = 4;
    /** Días seguidos sin comer a partir de los cuales empiezan a irse. */
    public static int HUNGRY_DAYS_BEFORE_DESERTING = 2;
    /** Qué parte del barracón se va cada día de hambre (mínimo 1). */
    public static double DESERT_FRACTION = 0.10;
    /** Cuántos días atrasados se cobran como mucho al volver a cargarse el Campamento. */
    public static int MAX_CATCH_UP_DAYS = 7;
    /** Cuántos prisioneros entran en un Campamento (H9). */
    public static int PRISON_CAPACITY = 40;

    /** Un soldado guardado. */
    public static final class Soldier {
        /** NBT completo de la entidad (con su UUID, para que los grupos de la vista aérea lo sigan reconociendo). */
        public final CompoundTag entity;
        /** NBT del caballo en el que iba montado (sin pasajeros), o null. */
        @Nullable
        public final CompoundTag horse;
        /** Tenía un puesto y estaba parado en él: al salir vuelve ahí, con la misma orden. */
        public final boolean guard;
        /** El puesto (solo si {@link #guard}). */
        @Nullable
        public final Vec3 post;
        public final float yRot;
        public final int followState;
        // Para mostrar sin leer el NBT
        public final String name;
        @Nullable
        public final ResourceLocation kit;
        public final int tier;
        public final int xpLevel;

        public Soldier(CompoundTag entity, @Nullable CompoundTag horse, boolean guard, @Nullable Vec3 post, float yRot,
                       int followState, String name, @Nullable ResourceLocation kit, int tier, int xpLevel) {
            this.entity = entity;
            this.horse = horse;
            this.guard = guard;
            this.post = post;
            this.yRot = yRot;
            this.followState = followState;
            this.name = name;
            this.kit = kit;
            this.tier = tier;
            this.xpLevel = xpLevel;
        }

        /** Fila de la pestaña a la que pertenece: misma clase y nivel ("" = recluta sin clase). */
        public String rowKey() {
            return kit == null ? "" : kit + "#" + tier;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("Entity", entity);
            if (horse != null) t.put("Horse", horse);
            t.putBoolean("Guard", guard);
            if (post != null) {
                t.putDouble("PostX", post.x);
                t.putDouble("PostY", post.y);
                t.putDouble("PostZ", post.z);
            }
            t.putFloat("YRot", yRot);
            t.putInt("Follow", followState);
            t.putString("Name", name);
            if (kit != null) t.putString("Kit", kit.toString());
            t.putInt("Tier", tier);
            t.putInt("Xp", xpLevel);
            return t;
        }

        public static Soldier load(CompoundTag t) {
            Vec3 post = t.contains("PostX") ? new Vec3(t.getDouble("PostX"), t.getDouble("PostY"), t.getDouble("PostZ")) : null;
            return new Soldier(t.getCompound("Entity"), t.contains("Horse") ? t.getCompound("Horse") : null,
                    t.getBoolean("Guard"), post, t.getFloat("YRot"), t.getInt("Follow"), t.getString("Name"),
                    t.contains("Kit") ? ResourceLocation.tryParse(t.getString("Kit")) : null, t.getInt("Tier"), t.getInt("Xp"));
        }
    }

    /**
     * Un prisionero (H9): soldado enemigo capturado en un ataque. No come ni ocupa lugar de soldado; se
     * recluta (pasa a ser un {@link Soldier} tuyo) o se devuelve por rescate.
     */
    public static final class Prisoner {
        /** NBT completo de la entidad, tal cual era (sin dueño, con las marcas de su guarnición). */
        public final CompoundTag entity;
        @Nullable
        public final CompoundTag horse;
        public final String name;
        /** Facción de la que viene (null si no tenía, p. ej. un soldado tuyo recapturado de otro jugador). */
        @Nullable
        public final ResourceLocation faction;
        public final int xpLevel;
        public final boolean captain;

        public Prisoner(CompoundTag entity, @Nullable CompoundTag horse, String name, @Nullable ResourceLocation faction,
                        int xpLevel, boolean captain) {
            this.entity = entity;
            this.horse = horse;
            this.name = name;
            this.faction = faction;
            this.xpLevel = xpLevel;
            this.captain = captain;
        }

        /** Fila de la pestaña: mismo nombre y nivel. */
        public String rowKey() {
            return name + "#" + xpLevel;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("Entity", entity);
            if (horse != null) t.put("Horse", horse);
            t.putString("Name", name);
            if (faction != null) t.putString("Faction", faction.toString());
            t.putInt("Xp", xpLevel);
            t.putBoolean("Captain", captain);
            return t;
        }

        public static Prisoner load(CompoundTag t) {
            return new Prisoner(t.getCompound("Entity"), t.contains("Horse") ? t.getCompound("Horse") : null, t.getString("Name"),
                    t.contains("Faction") ? ResourceLocation.tryParse(t.getString("Faction")) : null, t.getInt("Xp"), t.getBoolean("Captain"));
        }
    }

    /** El barracón de un Campamento. */
    public static final class Barracks {
        public final UUID owner;
        public final List<Soldier> soldiers = new ArrayList<>();
        /** Prisioneros (H9): aparte de los soldados, no comen. */
        public final List<Prisoner> prisoners = new ArrayList<>();
        /**
         * Cuartel (aldeas y castillos tuyos): los que patrullan adentro de la aldea, guardados mientras el dueño está
         * lejos (con él cerca andan sueltos, ver {@link com.ale.warbandcraft.village.Quarters}). Comen como el resto.
         */
        public final List<Soldier> quarters = new ArrayList<>();
        /** Último día ya pagado (-1 = nunca). */
        public long lastFedDay = -1;
        public int hungryDays;
        /** Puntos de comida que sobraron del último ítem (un pan son 5 y quizás se necesitaban 4). */
        public int foodCredit;

        Barracks(UUID owner) {
            this.owner = owner;
        }

        /** Los que comen del almacén: barracón + cuartel guardado. */
        public int eaters() {
            return soldiers.size() + quarters.size();
        }

        public int guards() {
            int n = 0;
            for (Soldier s : soldiers) if (s.guard) n++;
            return n;
        }
    }

    private final Map<Long, Barracks> barracks = new HashMap<>();

    public static BarracksData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(BarracksData::load, BarracksData::new, FILE);
    }

    @Nullable
    public Barracks at(BlockPos camp) {
        return barracks.get(camp.asLong());
    }

    public Barracks getOrCreate(BlockPos camp, UUID owner) {
        return barracks.computeIfAbsent(camp.asLong(), k -> new Barracks(owner));
    }

    public int count(BlockPos camp) {
        Barracks b = at(camp);
        return b == null ? 0 : b.soldiers.size();
    }

    @Nullable
    public Barracks remove(BlockPos camp) {
        Barracks b = barracks.remove(camp.asLong());
        if (b != null) setDirty();
        return b;
    }

    /** Todos los barracones (posición del Campamento como long → barracón). */
    public Map<Long, Barracks> all() {
        return barracks;
    }

    public void changed() {
        setDirty();
    }

    // ------------------------------------------------------------------ guardado

    public static BarracksData load(CompoundTag tag) {
        BarracksData d = new BarracksData();
        for (Tag t : tag.getList("Barracks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("Owner")) continue;
            Barracks b = new Barracks(c.getUUID("Owner"));
            b.lastFedDay = c.getLong("LastFed");
            b.hungryDays = c.getInt("Hungry");
            b.foodCredit = c.getInt("Credit");
            for (Tag s : c.getList("Soldiers", Tag.TAG_COMPOUND)) b.soldiers.add(Soldier.load((CompoundTag) s));
            for (Tag s : c.getList("Prisoners", Tag.TAG_COMPOUND)) b.prisoners.add(Prisoner.load((CompoundTag) s));
            for (Tag s : c.getList("Quarters", Tag.TAG_COMPOUND)) b.quarters.add(Soldier.load((CompoundTag) s));
            d.barracks.put(c.getLong("Camp"), b);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        barracks.forEach((pos, b) -> {
            if (b.soldiers.isEmpty() && b.prisoners.isEmpty() && b.quarters.isEmpty()) return;
            CompoundTag c = new CompoundTag();
            c.putLong("Camp", pos);
            c.putUUID("Owner", b.owner);
            c.putLong("LastFed", b.lastFedDay);
            c.putInt("Hungry", b.hungryDays);
            c.putInt("Credit", b.foodCredit);
            ListTag soldiers = new ListTag();
            for (Soldier s : b.soldiers) soldiers.add(s.save());
            c.put("Soldiers", soldiers);
            ListTag prisoners = new ListTag();
            for (Prisoner p : b.prisoners) prisoners.add(p.save());
            c.put("Prisoners", prisoners);
            ListTag quarters = new ListTag();
            for (Soldier s : b.quarters) quarters.add(s.save());
            c.put("Quarters", quarters);
            list.add(c);
        });
        tag.put("Barracks", list);
        return tag;
    }
}
