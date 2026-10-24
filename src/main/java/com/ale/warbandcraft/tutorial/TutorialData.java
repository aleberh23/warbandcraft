package com.ale.warbandcraft.tutorial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Salón del tutorial (guardado en el overworld): dónde está y qué libro va en cada atril. */
public class TutorialData extends SavedData {
    private static final String FILE = "warbandcraft_tutorial";

    @Nullable private BlockPos center;
    /** Atril → id del libro (nombre del archivo de {@code warband_tutorial/} sin ".txt"). */
    final Map<BlockPos, String> lecterns = new LinkedHashMap<>();

    public static TutorialData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TutorialData::load, TutorialData::new, FILE);
    }

    @Nullable
    public BlockPos center() {
        return center;
    }

    void set(BlockPos center, Map<BlockPos, String> lecterns) {
        this.center = center;
        this.lecterns.clear();
        this.lecterns.putAll(lecterns);
        setDirty();
    }

    private static TutorialData load(CompoundTag tag) {
        TutorialData d = new TutorialData();
        if (tag.contains("Center")) d.center = BlockPos.of(tag.getLong("Center"));
        for (Tag t : tag.getList("Lecterns", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.lecterns.put(BlockPos.of(c.getLong("Pos")), c.getString("Book"));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (center != null) tag.putLong("Center", center.asLong());
        ListTag list = new ListTag();
        lecterns.forEach((pos, book) -> {
            CompoundTag c = new CompoundTag();
            c.putLong("Pos", pos.asLong());
            c.putString("Book", book);
            list.add(c);
        });
        tag.put("Lecterns", list);
        return tag;
    }
}
