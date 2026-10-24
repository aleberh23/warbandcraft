package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.ModList;

import java.nio.file.Path;

/**
 * H25: datos que pisan los de otro mod (no alcanza con ponerlos en el jar: el orden entre mods no está asegurado). Van
 * en un paquete de datos de adentro del mod, obligatorio y arriba de todo, y solo si ese mod está (si no, sus
 * estructuras no existen y el mundo no cargaría). Hoy: Cataclysm, con sus estructuras de superficie (desierto y nieve,
 * justo donde caen las batallas) más espaciadas ({@code compat/cataclysm}).
 */
public final class CompatPacks {
    private CompatPacks() {}

    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        add(event, "cataclysm", "Warband Craft + Cataclysm");
    }

    private static void add(AddPackFindersEvent event, String modId, String title) {
        if (!ModList.get().isLoaded(modId)) return;
        Path path = ModList.get().getModFileById(WarbandCraft.MODID).getFile().findResource("compat", modId);
        Pack pack = Pack.readMetaAndCreate(WarbandCraft.MODID + ":compat_" + modId, Component.literal(title), true,
                id -> new PathPackResources(id, path, true), PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
        if (pack != null) event.addRepositorySource(consumer -> consumer.accept(pack));
    }
}
