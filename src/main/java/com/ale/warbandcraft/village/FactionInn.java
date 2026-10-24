package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * El Tabernero de las aldeas de facción (pedido de Ale: "vi 3 tabernas y no está"). Una aldea de facción con taberna
 * tiene uno, sin dueño e invulnerable, detrás de la barra de día y de noche (rumores y trabajos, como el tuyo). Si la
 * aldea pasa a ser tuya o se queda sin taberna, se va; en medio de una pelea no aparece uno nuevo.
 */
public final class FactionInn {
    /** Marca del Tabernero de una aldea de facción (centro de la aldea). */
    public static final String TAG = "warbandcraft:faction_inn";

    private FactionInn() {}

    public static boolean is(InnkeeperEntity k) {
        return k.getPersistentData().contains(TAG);
    }

    /** Cada 5 s: cada aldea de facción cargada con taberna tiene su Tabernero; los que sobran se van. */
    public static void tick(ServerLevel level) {
        VillageData data = VillageData.get(level);
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            if (data.isTest(v.center()) || !level.isPositionEntityTicking(v.center())) continue;
            serve(level, v, towns.at(v.center()));
        }
    }

    /** Una aldea: su Tabernero si tiene taberna (lo usan también las pruebas, con aldeas de prueba). */
    public static void serve(ServerLevel level, VillageData.Village v, TownData.Town t) {
        BlockPos c = v.center();
        List<InnkeeperEntity> mine = keepers(level, c);
        BlockPos place = v.isConquered() || t == null || t.tavernLevel() <= 0 ? null : Tavern.keeperSpot(level, v);
        int want = place == null ? 0 : 1;
        for (int i = want; i < mine.size(); i++) mine.get(i).discard();
        if (want == 0) return;
        if (!mine.isEmpty()) {
            mine.get(0).setWorkplace(place);   // se cuida solo (InnkeeperEntity.tick): si está lejos, aparece ahí
            return;
        }
        boolean peace = RaidManager.get(level, c) == null && InvasionData.get(level).battleAt(c) == null;
        if (peace) spawn(level, v, place);
    }

    private static List<InnkeeperEntity> keepers(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        return level.getEntitiesOfClass(InnkeeperEntity.class, new AABB(center).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS),
                k -> k.isAlive() && k.getPersistentData().contains(TAG) && k.getPersistentData().getLong(TAG) == key);
    }

    private static void spawn(ServerLevel level, VillageData.Village v, BlockPos place) {
        InnkeeperEntity k = ModEntities.INNKEEPER.get().create(level);
        if (k == null) return;
        k.moveTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5, level.getRandom().nextFloat() * 360F, 0F);
        try {
            k.finalizeSpawn(level, level.getCurrentDifficultyAt(place), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException e) {
            WarbandCraft.LOGGER.debug("finalizeSpawn del tabernero de facción falló, se sigue igual", e);
        }
        k.initSpawn();
        k.setIsOwned(false);
        k.setWorkplace(place);
        k.setInvulnerable(true);
        k.setPersistenceRequired();
        k.getPersistentData().putLong(TAG, v.center().asLong());
        if (!level.addFreshEntity(k)) WarbandCraft.LOGGER.warn("No apareció el tabernero de la aldea de facción en {}", v.center().toShortString());
    }
}
