package com.ale.warbandcraft.village;

import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Viviendas de una aldea (modo Gestión, pestaña Casas): cuántos aldeanos no tienen cama o trabajo, cuántas camas y
 * puestos de trabajo libres hay, y un terreno plano sugerido para la próxima casa. Aldeanos de vanilla y (H18) los
 * trabajadores de Villager Workers, que duermen en una cama de la aldea con su zona Hogar.
 * <p>
 * Casas automáticas (prendido de entrada, se apaga en Gestión): cada {@link #AUTO_EVERY_TICKS}, si faltan camas, la
 * aldea está cargada y sin obra y el almacén tiene las esmeraldas, el constructor levanta una casa de 2 camas
 * ({@link Upgrade#HOUSE}, obra de H12) en un terreno plano cerca del centro.
 */
public final class Housing {
    /** Camas libres que se marcan en el mundo, como mucho. */
    public static int MAX_MARKED_BEDS = 24;
    /** Dónde se busca el terreno sugerido (del centro) y cada cuánto se vuelve a buscar (es caro). */
    public static int SITE_MIN = 8, SITE_MAX = 48, SITE_REFRESH_TICKS = 20 * 60;
    /** Cada cuánto se fija si hay que encargar una casa sola. */
    public static int AUTO_EVERY_TICKS = 20 * 30;

    /**
     * {@code homeless} = sin cama, {@code jobless} = sin oficio (o tontos), {@code both} = ni una cosa ni la otra;
     * {@code homelessIds} para resaltarlos; {@code site} = esquina mínima de un terreno de 9×9 (y = piso), o null.
     */
    public record Data(int adults, int babies, int beds, int freeBeds, int homeless, int jobless, int both, int freeJobSites,
                       List<UUID> homelessIds, List<BlockPos> freeBedPos, @Nullable BlockPos site,
                       boolean auto, int houses, int cost, int stockEmeralds, Component job, int jobPercent) {
        // job = la obra de la aldea (vacío si no hay), jobPercent = cuánto va (-1 sin obra)
        /** Camas que faltan para que todos (más los chicos) tengan una. */
        public int missingBeds() {
            return Math.max(0, homeless + babies - freeBeds);
        }

        public void write(FriendlyByteBuf b) {
            b.writeVarInt(adults);
            b.writeVarInt(babies);
            b.writeVarInt(beds);
            b.writeVarInt(freeBeds);
            b.writeVarInt(homeless);
            b.writeVarInt(jobless);
            b.writeVarInt(both);
            b.writeVarInt(freeJobSites);
            b.writeCollection(homelessIds, FriendlyByteBuf::writeUUID);
            b.writeCollection(freeBedPos, FriendlyByteBuf::writeBlockPos);
            b.writeNullable(site, FriendlyByteBuf::writeBlockPos);
            b.writeBoolean(auto);
            b.writeVarInt(houses);
            b.writeVarInt(cost);
            b.writeVarInt(stockEmeralds);
            b.writeComponent(job);
            b.writeVarInt(jobPercent + 1);
        }

        public static Data read(FriendlyByteBuf b) {
            return new Data(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), b.readList(FriendlyByteBuf::readUUID), b.readList(FriendlyByteBuf::readBlockPos),
                    b.readNullable(FriendlyByteBuf::readBlockPos), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readComponent(), b.readVarInt() - 1);
        }
    }

    private record CachedSite(@Nullable BlockPos site, long until) {}

    private static final Map<Long, CachedSite> SITES = new HashMap<>();

    private Housing() {}

    /** Lo de la pestaña Casas (con terreno sugerido y estado de la obra). */
    public static Data of(ServerLevel level, BlockPos center) {
        Data d = count(level, center, true);
        VillageData.Village v = VillageData.get(level).at(center);
        TownData.Town t = TownData.get(level).at(center);
        Component job = Component.empty();
        int percent = -1;
        if (t != null && t.job != null) {
            job = t.job.upgrade.name(v != null && v.castle());
            percent = Upgrades.percent(t.job);
        }
        int emeralds = v == null ? 0 : VillageStock.total(level, v).emeralds();
        return new Data(d.adults, d.babies, d.beds, d.freeBeds, d.homeless, d.jobless, d.both, d.freeJobSites, d.homelessIds,
                d.freeBedPos, d.site, false, t == null ? 0 : t.houses, Upgrade.HOUSE.cost, emeralds, job, percent);
    }

    /** Las cuentas de aldeanos, camas y puestos ({@code withSite}: también busca el terreno, que es caro). */
    private static Data count(ServerLevel level, BlockPos center, boolean withSite) {
        int r = VillageManagement.RADIUS;
        int adults = 0, homeless = 0, jobless = 0, both = 0;
        List<UUID> homelessIds = new ArrayList<>();
        for (Villager v : VillageManagement.villagers(level, center)) {
            adults++;
            boolean noBed = v.getBrain().getMemory(MemoryModuleType.HOME).isEmpty();
            boolean noJob = VillageManagement.isFree(v);
            if (noBed) {
                homeless++;
                homelessIds.add(v.getUUID());
            }
            if (noJob) jobless++;
            if (noBed && noJob) both++;
        }
        // H18: los trabajadores de Villager Workers también duermen en una cama de la aldea (zona Hogar, WorkerSupply)
        for (com.talhanation.workers.entities.AbstractWorkerEntity w : level.getEntitiesOfClass(com.talhanation.workers.entities.AbstractWorkerEntity.class,
                new AABB(center).inflate(r, 32, r), w -> w.isAlive() && w.isOwned())) {
            adults++;
            if (!WorkerSupply.hasHome(w)) {
                homeless++;
                homelessIds.add(w.getUUID());
            }
        }
        int babies = level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(r, 32, r), v -> v.isAlive() && v.isBaby()).size();

        PoiManager poi = level.getPoiManager();
        int beds = (int) poi.getCountInRange(h -> h.is(PoiTypes.HOME), center, r, PoiManager.Occupancy.ANY);
        List<BlockPos> free = poi.getInRange(h -> h.is(PoiTypes.HOME), center, r, PoiManager.Occupancy.HAS_SPACE)
                .map(p -> p.getPos()).toList();
        int jobSites = (int) poi.getCountInRange(h -> h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE), center, r, PoiManager.Occupancy.HAS_SPACE);

        return new Data(adults, babies, beds, free.size(), homeless, jobless, both, jobSites, homelessIds,
                free.size() > MAX_MARKED_BEDS ? free.subList(0, MAX_MARKED_BEDS) : free, withSite ? site(level, center) : null,
                true, 0, Upgrade.HOUSE.cost, 0, Component.empty(), -1);
    }

    /** Un terreno para una casa: cerca del centro y sin pisar los de las mejoras ni otras casas. */
    @Nullable
    public static BlockPos findSite(ServerLevel level, BlockPos center, Collection<BlockPos> taken) {
        SITES.remove(center.asLong());   // el sugerido que se mostraba deja de servir
        return Blueprints.findSite(level, center, SITE_MIN, SITE_MAX, new ArrayList<>(taken));
    }

    // ------------------------------------------------------------------ casas automáticas

    /** Prende o apaga las casas automáticas de tu aldea. */
    public static Component setAuto(ServerPlayer player, VillageData.Village v, boolean on) {
        TownData data = TownData.get(player.serverLevel());
        data.getOrCreate(v.center()).autoHouses = on;
        data.changed();
        return Component.literal(on ? "Casas automáticas: prendidas." : "Casas automáticas: apagadas.")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    /** Recorre tus aldeas cargadas y encarga una casa donde falten camas (ver arriba). H22: ya no se llama (pedido de Ale: las casas se plantan a mano). */
    public static void autoTick(ServerLevel level) {
        TownData towns = TownData.get(level);
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (v.owner() == null || v.castle() || !level.isPositionEntityTicking(v.center())) continue;
            TownData.Town t = towns.at(v.center());
            if (t != null && (!t.autoHouses || t.job != null)) continue;
            if (count(level, v.center(), false).missingBeds() <= 0) continue;
            if (VillageStock.total(level, v).emeralds() < Upgrade.HOUSE.cost) continue;
            TownData.Town town = towns.getOrCreate(v.center());
            Blueprints.Plan plan = Upgrades.plan(level, v, town, Upgrade.HOUSE);
            if (plan == null || plan.size() == 0) continue;   // no hay lugar: se vuelve a probar más tarde
            VillageStock.takeEmeralds(level, v, Upgrade.HOUSE.cost);
            Upgrades.start(level, town, Upgrade.HOUSE, plan);
            towns.changed();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(v.owner());
            if (p != null) {
                p.sendSystemMessage(Component.literal("Faltan camas en " + VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v))).getString()
                        + ": el constructor empezó una casa (" + Upgrade.HOUSE.cost + " esmeraldas del almacén).").withStyle(ChatFormatting.GREEN));
            }
        }
    }

    /** Un terreno de 9×9 casi plano y libre cerca del centro, que no pise las obras de las mejoras (se guarda 1 min). */
    @Nullable
    private static BlockPos site(ServerLevel level, BlockPos center) {
        long now = level.getGameTime();
        CachedSite c = SITES.get(center.asLong());
        if (c != null && c.until() > now) return c.site();
        TownData.Town town = TownData.get(level).at(center);
        List<BlockPos> taken = town == null ? List.of() : new ArrayList<>(town.sites.values());
        BlockPos site = Blueprints.findSite(level, center, SITE_MIN, SITE_MAX, taken);
        SITES.put(center.asLong(), new CachedSite(site, now + SITE_REFRESH_TICKS));
        return site;
    }
}
