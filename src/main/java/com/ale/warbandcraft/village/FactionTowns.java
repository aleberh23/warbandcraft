package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Las aldeas de las facciones tienen lo de su tamaño (pedido de Ale: "DALE VIDA"). Según sus camas son aldea, pueblo o
 * ciudad, y cada una tiene sus mejoras de H12/H19/H24 hechas: empalizada (o muralla), torres, plaza, mercado, taberna,
 * cuartel, herrería, capilla, granero y casas. Lo de su tamaño aparece de una al conocerla (de a una mejora por
 * segundo, para no trabar el juego); después crecen solas cada tanto, con obra y constructor como las tuyas. Si la
 * conquistás, todo eso queda (como siempre, los muros y edificios no cambian de dueño).
 */
public final class FactionTowns {
    /** Camas (dentro de 48 bloques de la campana) para ser pueblo o ciudad. */
    public static int TOWN_BEDS = 10, CITY_BEDS = 22;
    /** Chance de que una aldea de facción arranque un tamaño más grande que sus camas. */
    public static double BIGGER_CHANCE = 0.25;
    /** Chance por día de que una aldea/pueblo de facción pase al tamaño siguiente. */
    public static double GROW_CHANCE = 0.05;
    /** Una mejora que no encontró lugar se vuelve a probar a los tantos ticks. */
    public static int RETRY_TICKS = 20 * 600;
    /** Cuántas mejoras se ponen de una por segundo en todo el mundo (para no trabar). */
    public static int INSTANT_PER_SECOND = 1;

    private static final Map<String, Long> FAILED = new HashMap<>();

    private FactionTowns() {}

    /** Lo que tiene cada tamaño, en el orden en que se hace (las casas pueden repetirse). */
    public static List<Upgrade> wanted(int tier) {
        List<Upgrade> out = new ArrayList<>();
        switch (tier) {
            case 1 -> out.addAll(List.of(Upgrade.PALISADE, Upgrade.MARKET, Upgrade.TAVERN_1, Upgrade.HOUSE));
            case 2 -> out.addAll(List.of(Upgrade.PALISADE, Upgrade.TOWERS, Upgrade.TOWN, Upgrade.MARKET_2, Upgrade.TAVERN_2,
                    Upgrade.BARRACKS, Upgrade.GRANARY, Upgrade.HOUSE, Upgrade.HOUSE));
            default -> out.addAll(List.of(Upgrade.WALL, Upgrade.TOWERS, Upgrade.TOWN, Upgrade.CITY, Upgrade.MARKET_3, Upgrade.TAVERN_3,
                    Upgrade.BARRACKS, Upgrade.SMITHY, Upgrade.CHAPEL, Upgrade.GRANARY, Upgrade.HOUSE, Upgrade.HOUSE, Upgrade.HOUSE));
        }
        return out;
    }

    /** Cada segundo: las aldeas de facción cargadas que todavía no tienen lo de su tamaño ponen una mejora más. */
    public static void tick(ServerLevel level) {
        VillageData data = VillageData.get(level);
        TownData towns = TownData.get(level);
        long now = level.getGameTime();
        int instant = 0;
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            BlockPos c = v.center();
            if (v.castle() || v.isConquered() || data.isTest(c) || !level.isPositionEntityTicking(c)) continue;
            int r = 80;
            if (!level.hasChunksAt(c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r)) continue;   // todo cargado alrededor
            if (com.ale.warbandcraft.raid.RaidManager.get(level, c) != null
                    || com.ale.warbandcraft.invasion.InvasionData.get(level).battleAt(c) != null) continue;   // no en medio de una pelea
            TownData.Town t = towns.getOrCreate(c);
            if (t.job != null) continue;   // ya hay una obra
            if (t.npcTier == 0) {
                t.npcTier = tierFor(level, c);
                towns.changed();
                WarbandCraft.LOGGER.info("Aldea de facción en {}: {}", c.toShortString(), t.npcTier == 3 ? "ciudad" : t.npcTier == 2 ? "pueblo" : "aldea");
            }
            Upgrade next = next(t, now);
            if (next == null) {
                if (!t.npcReady) {
                    t.npcReady = true;
                    towns.changed();
                }
                continue;
            }
            if (!t.npcReady && instant >= INSTANT_PER_SECOND) continue;
            Blueprints.Plan plan = Upgrades.plan(level, v, t, next);
            if (plan == null || plan.size() == 0) {
                FAILED.put(key(c, next, t), now);
                continue;
            }
            if (!t.npcReady) {   // estaba de antes: aparece hecho
                Upgrades.buildNow(level, t, next, plan);
                instant++;
            } else {              // crece: obra con constructor, como las tuyas
                Upgrades.start(level, t, next, plan);
            }
            markRequired(t, next);
            towns.changed();
        }
    }

    /** Cada día: algunas aldeas/pueblos de facción ya armados pasan al tamaño siguiente (y empiezan a construirlo). */
    public static void onNewDay(ServerLevel level) {
        VillageData data = VillageData.get(level);
        TownData towns = TownData.get(level);
        for (VillageData.Village v : data.all()) {
            if (v.castle() || v.isConquered() || data.isTest(v.center())) continue;
            TownData.Town t = towns.at(v.center());
            if (t == null || !t.npcReady || t.npcTier <= 0 || t.npcTier >= 3) continue;
            if (level.getRandom().nextDouble() >= GROW_CHANCE) continue;
            t.npcTier++;
            towns.changed();
            WarbandCraft.LOGGER.info("La aldea de facción en {} crece a {}", v.center().toShortString(), t.npcTier == 3 ? "ciudad" : "pueblo");
        }
    }

    /** El tamaño según las camas (con un poco de azar para arriba). */
    public static int tierFor(ServerLevel level, BlockPos center) {
        long beds = level.getPoiManager().getCountInRange(type -> type.is(PoiTypes.HOME), center, 48, PoiManager.Occupancy.ANY);
        int tier = beds >= CITY_BEDS ? 3 : beds >= TOWN_BEDS ? 2 : 1;
        if (tier < 3 && level.getRandom().nextDouble() < BIGGER_CHANCE) tier++;
        return tier;
    }

    /** La próxima mejora que le falta (las casas se cuentan). Null si ya tiene todo. */
    @Nullable
    private static Upgrade next(TownData.Town t, long now) {
        int houses = 0;
        for (Upgrade u : wanted(t.npcTier)) {
            if (u == Upgrade.HOUSE) {
                houses++;
                if (t.houses >= houses) continue;
            } else if (t.has(u) || covered(t, u)) {
                continue;
            }
            Long failed = FAILED.get(key(t.center, u, t));
            if (failed != null && now - failed < RETRY_TICKS) continue;
            return u;
        }
        return null;
    }

    /** Una mejora más alta del mismo tipo ya está (la muralla cubre la empalizada, la ciudad al pueblo...). */
    private static boolean covered(TownData.Town t, Upgrade u) {
        for (Upgrade b : t.built) {
            if (b == Upgrade.WALL && u == Upgrade.PALISADE) return true;
            if (!b.siteKey().equals(u.siteKey())) continue;
            for (Upgrade r = b.requires; r != null; r = r.requires) if (r == u) return true;
        }
        return false;
    }

    /** Al hacer una mejora de nivel (Taberna 3, Muralla) de una, quedan hechas también las anteriores. */
    private static void markRequired(TownData.Town t, Upgrade u) {
        for (Upgrade r = u.requires; r != null; r = r.requires) if (r.listed() && r.siteKey().equals(u.siteKey())) t.built.add(r);
        if (u == Upgrade.WALL) t.built.add(Upgrade.PALISADE);
    }

    private static String key(BlockPos c, Upgrade u, TownData.Town t) {
        return c.asLong() + ":" + u.id + (u == Upgrade.HOUSE ? ":" + t.houses : "");
    }
}
