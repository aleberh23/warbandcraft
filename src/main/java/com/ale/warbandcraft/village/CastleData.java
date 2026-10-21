package com.ale.warbandcraft.village;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Dónde ya se tiró el dado por un castillo nuevo (con o sin suerte), para no repetir el intento cada
 * 5 segundos mientras un jugador se queda en la misma zona. Los castillos en sí se guardan como
 * {@link VillageData.Village} (con {@code castle() == true}); esto solo recuerda las zonas ya exploradas.
 */
public class CastleData extends SavedData {
    private static final String FILE = "warbandcraft_castle_scan";

    private final List<BlockPos> attempts = new ArrayList<>();

    public static CastleData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CastleData::load, CastleData::new, FILE);
    }

    /** La zona explorada más cercana a {@code pos} a menos de {@code maxDist} bloques, o null. */
    @Nullable
    public BlockPos nearest(BlockPos pos, double maxDist) {
        BlockPos best = null;
        double bestSq = maxDist * maxDist;
        for (BlockPos p : attempts) {
            double d = VillageData.horizontalDistSq(p, pos);
            if (d <= bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    public void add(BlockPos pos) {
        attempts.add(pos.immutable());
        setDirty();
    }

    public static CastleData load(CompoundTag tag) {
        CastleData data = new CastleData();
        for (Tag t : tag.getList("Attempts", Tag.TAG_LONG)) {
            data.attempts.add(BlockPos.of(((LongTag) t).getAsLong()));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BlockPos p : attempts) list.add(LongTag.valueOf(p.asLong()));
        tag.put("Attempts", list);
        return tag;
    }
}
