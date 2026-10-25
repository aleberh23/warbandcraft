package com.ale.warbandcraft.village;

import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.camp.CampWorkers;
import com.ale.warbandcraft.dungeon.Rumors;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trabajadores para contratar en las tabernas (pedido de Ale): cada taberna (tuya, de facción o del mundo) junta
 * {@link #PER_DAY} por día hasta su tope ({@link #max}). Se contratan hablándole al Tabernero; el trabajador va solo a
 * un Campamento común tuyo que no tenga ({@link CampWorkers}).
 */
public final class TavernWorkers {
    public static final String KIND_HIRE = "tavern_worker";
    /** Cuántos llegan por día. */
    public static int PER_DAY = 1;
    /** Tope de los que esperan según el nivel de la taberna (las del mundo cuentan como nivel {@link #WORLD_LEVEL}). */
    public static int MAX_1 = 1, MAX_2 = 2, MAX_3 = 3;
    public static int WORLD_LEVEL = 2;
    /** Esmeraldas por contratar uno (inventario y después el almacén de la aldea del Tabernero). */
    public static int HIRE_COST = 10;
    /** Cuántos Campamentos se ofrecen (los más cercanos). */
    public static int CAMP_CHOICES = 4;

    private TavernWorkers() {}

    public static int max(int tavernLevel) {
        return tavernLevel >= 3 ? MAX_3 : tavernLevel == 2 ? MAX_2 : tavernLevel == 1 ? MAX_1 : 0;
    }

    /** La taberna de este Tabernero: clave y nivel. */
    private record Inn(long key, int level) {}

    @Nullable
    private static Inn innOf(ServerLevel level, InnkeeperEntity k) {
        CompoundTag tag = k.getPersistentData();
        if (tag.contains(WorldTaverns.TAG)) return new Inn(tag.getLong(WorldTaverns.TAG), WORLD_LEVEL);
        BlockPos center = tag.contains(FactionInn.TAG) ? BlockPos.of(tag.getLong(FactionInn.TAG)) : null;
        if (center == null && k.getCamp() != null) {
            VillageData.Village v = VillageCamps.villageOf(level, k.getCamp());
            if (v != null) center = v.center();
        }
        if (center == null) return null;
        TownData.Town t = TownData.get(level).at(center);
        return new Inn(center.asLong(), t == null ? 0 : t.tavernLevel());
    }

    // ------------------------------------------------------------------ guardado: {cuántos, último día}

    public static final class Data extends SavedData {
        private static final String FILE = "warbandcraft_tavern_workers";
        final Map<Long, long[]> inns = new HashMap<>();

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Data::load, Data::new, FILE);
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            CompoundTag all = tag.getCompound("Inns");
            for (String k : all.getAllKeys()) {
                try {
                    d.inns.put(Long.parseLong(k), all.getLongArray(k));
                } catch (NumberFormatException ignored) {
                    // clave rota: se saltea
                }
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag all = new CompoundTag();
            inns.forEach((k, v) -> all.putLongArray(Long.toString(k), v));
            tag.put("Inns", all);
            return tag;
        }
    }

    /** Cuántos esperan en esta taberna hoy (suma los días que pasaron, hasta el tope). */
    public static int available(ServerLevel level, long key, int tavernLevel) {
        Data d = Data.get(level);
        long today = Barracks.today(level);
        int cap = max(tavernLevel);
        long[] e = d.inns.get(key);
        if (e == null || e.length < 2) {
            e = new long[]{Math.min(1, cap), today};   // la primera vez ya hay uno
            d.inns.put(key, e);
            d.setDirty();
        } else if (today > e[1]) {
            e[0] = Math.min(cap, e[0] + (today - e[1]) * PER_DAY);
            e[1] = today;
            d.setDirty();
        }
        if (e[0] > cap) {   // bajó el nivel (o el tope en la configuración)
            e[0] = cap;
            d.setDirty();
        }
        return (int) e[0];
    }

    /** La opción del menú del Tabernero ("Contratar trabajador (2 esperan)"), o null si esta taberna no tiene. */
    @Nullable
    public static Component menuOption(ServerLevel level, InnkeeperEntity k) {
        Inn inn = innOf(level, k);
        if (inn == null || max(inn.level()) <= 0) return null;
        return Component.translatable("gui.warbandcraft.tavern.hire_worker", available(level, inn.key(), inn.level()), HIRE_COST);
    }

    /** Lo que se le ofreció a cada jugador (botón → Campamento). */
    private static final Map<UUID, List<BlockPos>> OFFERED = new HashMap<>();

    /** Elegir a qué Campamento va. */
    public static void offer(ServerPlayer player, InnkeeperEntity k) {
        ServerLevel level = player.serverLevel();
        Inn inn = innOf(level, k);
        if (inn == null || available(level, inn.key(), inn.level()) <= 0) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.none").withStyle(ChatFormatting.GRAY));
            return;
        }
        List<BlockPos> camps = new ArrayList<>(CampRegistry.get(level).campsOf(player.getUUID()).stream()
                .filter(c -> CampWorkers.canTake(level, player.getUUID(), c)).toList());
        if (camps.isEmpty()) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.no_camp").withStyle(ChatFormatting.RED));
            return;
        }
        camps.sort(Comparator.comparingDouble(c -> c.distSqr(k.blockPosition())));
        if (camps.size() > CAMP_CHOICES) camps = new ArrayList<>(camps.subList(0, CAMP_CHOICES));
        OFFERED.put(player.getUUID(), camps);
        List<Component> options = new ArrayList<>();
        for (BlockPos c : camps) options.add(CampWorkers.campLabel(level, c, k.blockPosition()));
        options.add(Component.translatable("gui.warbandcraft.camp_worker.close"));
        BattleChoices.send(player, KIND_HIRE, Integer.toString(k.getId()), Component.translatable("gui.warbandcraft.tavern_worker.title"),
                List.of(Component.translatable("gui.warbandcraft.tavern_worker.help", HIRE_COST)), options, true);
    }

    public static void answer(ServerPlayer player, String id, int option) {
        List<BlockPos> camps = OFFERED.remove(player.getUUID());
        if (camps == null || option < 0 || option >= camps.size()) return;
        ServerLevel level = player.serverLevel();
        InnkeeperEntity k;
        try {
            k = level.getEntity(Integer.parseInt(id)) instanceof InnkeeperEntity i ? i : null;
        } catch (NumberFormatException e) {
            return;
        }
        if (k == null || k.distanceTo(player) > 8) return;
        Inn inn = innOf(level, k);
        BlockPos camp = camps.get(option);
        if (inn == null || available(level, inn.key(), inn.level()) <= 0) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.none").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!CampWorkers.canTake(level, player.getUUID(), camp)) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.no_camp").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Rumors.pay(player, k, HIRE_COST)) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.no_money", HIRE_COST).withStyle(ChatFormatting.RED));
            return;
        }
        Data d = Data.get(level);
        d.inns.get(inn.key())[0]--;
        d.setDirty();
        CampWorkers.hire(player, camp, k.position());
        player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern_worker.hired", CampRegistry.get(level).displayName(camp))
                .withStyle(ChatFormatting.GREEN));
    }
}
