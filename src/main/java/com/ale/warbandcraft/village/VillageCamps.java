package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * H16: el almacén de una aldea o castillo tuyo es un Campamento (con cofre, barracón y todas sus pestañas). Este
 * Campamento "es" la aldea: en las rutas cuenta como el mismo punto, y su barracón es la guarnición de la aldea.
 */
public final class VillageCamps {
    /** Lugar en el barracón de una aldea / castillo con el Cuartel (H12); sin Cuartel, {@link #WITHOUT_BARRACKS} menos. */
    public static int VILLAGE_CAPACITY = 100, CASTLE_CAPACITY = 60, WITHOUT_BARRACKS = 20;
    /** A esta distancia del centro estás "en" la aldea (botón Gestión de la vista aérea). */
    public static int MANAGE_RADIUS = 64;

    private VillageCamps() {}

    /** La aldea/castillo de la que el Campamento en {@code pos} es el almacén, o null si es un Campamento común. */
    @Nullable
    public static VillageData.Village villageOf(ServerLevel level, BlockPos pos) {
        VillageData data = VillageData.get(level);
        CampRegistry camps = CampRegistry.get(level);
        for (VillageData.Village v : data.all()) {
            if (v.isConquered() && pos.equals(data.storehouse(v.center())) && camps.isCampOf(v.owner(), pos)) return v;
        }
        return null;
    }

    /** El Campamento de la aldea (ya puesto y del dueño de la aldea), o null. */
    @Nullable
    public static BlockPos campOf(ServerLevel level, VillageData.Village v) {
        if (!v.isConquered()) return null;
        BlockPos pos = VillageData.get(level).storehouse(v.center());
        return pos != null && CampRegistry.get(level).isCampOf(v.owner(), pos) ? pos : null;
    }

    /** Si {@code pos} es el Campamento de una aldea, el centro de la aldea; si no, {@code pos}. */
    public static BlockPos canonical(ServerLevel level, BlockPos pos) {
        VillageData.Village v = villageOf(level, pos);
        return v != null ? v.center() : pos;
    }

    /** Si {@code pos} es una aldea/castillo tuyo con Campamento, ese Campamento; si no, {@code pos}. */
    public static BlockPos campFor(ServerLevel level, BlockPos pos) {
        VillageData.Village v = VillageData.get(level).at(pos);
        BlockPos camp = v == null ? null : campOf(level, v);
        return camp != null ? camp : pos;
    }

    /** Lugar en el barracón de la aldea: 100 / 60 con Cuartel, 20 menos sin él. */
    public static int capacity(ServerLevel level, VillageData.Village v) {
        int max = v.castle() ? CASTLE_CAPACITY : VILLAGE_CAPACITY;
        TownData.Town t = TownData.get(level).at(v.center());
        int training = com.ale.warbandcraft.camp.Training.bonus(t == null ? 0 : t.trainingLevel());   // H19
        return (t != null && t.has(Upgrade.BARRACKS) ? max : max - WITHOUT_BARRACKS) + training;
    }

    /**
     * El nombre que le puso el dueño a la aldea (= el de su Campamento), o {@code fallback} si todavía tiene el de
     * siempre ({@link #nameFor}).
     */
    public static net.minecraft.network.chat.Component displayName(ServerLevel level, VillageData.Village v,
                                                                   net.minecraft.network.chat.Component fallback) {
        BlockPos camp = campOf(level, v);
        String name = camp == null ? null : CampRegistry.get(level).nameOf(camp);
        return name != null && !name.equals(nameFor(v)) ? net.minecraft.network.chat.Component.literal(name) : fallback;
    }

    /** Nombre del Campamento de la aldea: "Aldea de los Clanes del Norte" / "Castillo de ...". */
    public static String nameFor(VillageData.Village v) {
        com.ale.warbandcraft.faction.Faction f = com.ale.warbandcraft.faction.FactionManager.INSTANCE.get(v.faction());
        String of = f == null ? v.faction().getPath() : f.ofName();
        return (v.castle() ? "Castillo " : "Aldea ") + of;
    }
}
