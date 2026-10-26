package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.WorkerZone;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.worker.SmithEntity;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.AnimalFarmerEntity;
import com.talhanation.workers.entities.CookEntity;
import com.talhanation.workers.entities.FarmerEntity;
import com.talhanation.workers.entities.FishermanEntity;
import com.talhanation.workers.entities.LumberjackEntity;
import com.talhanation.workers.entities.MinerEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import com.talhanation.workers.entities.workarea.AnimalPenArea;
import com.talhanation.workers.entities.workarea.CropArea;
import com.talhanation.workers.entities.workarea.FishingArea;
import com.talhanation.workers.entities.workarea.HomeArea;
import com.talhanation.workers.entities.workarea.KitchenArea;
import com.talhanation.workers.entities.workarea.LumberArea;
import com.talhanation.workers.entities.workarea.MiningArea;
import com.talhanation.workers.init.ModEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * H16: las zonas de trabajo desde el modo Gestión. Marcar = crear la zona de Villager Workers del oficio del trabajador
 * sobre un rectángulo del piso (y engancharlo a esa zona); Auto = la zona sugerida (huerta, árboles, agua, la casa del
 * almacén); Borrar = sacar la zona. El Herrero (nuestro) no usa zonas de Workers: su "zona" es su lugar al lado de un
 * yunque.
 */
public final class WorkZones {
    /** Lado mínimo y máximo de una zona (la mina, más chica) y alturas por oficio. */
    public static int MIN_SIDE = 3, MAX_SIDE = 32, MAX_MINE_SIDE = 32;
    /** La mina en escalera: largo mínimo (por donde baja) y ancho máximo de la escalera. */
    public static int MIN_MINE_LENGTH = 8, MAX_MINE_WIDTH = 5;
    /** Pedido de Ale: un leñador puede tener su zona afuera del territorio (un puesto), hasta esta distancia del centro. */
    public static int OUTPOST_RANGE = 200;
    /** Un puesto no puede estar a menos de esto del centro de otra aldea/castillo. */
    public static int OUTPOST_OTHER_VILLAGE = 64;
    public static int MINE_DEPTH = 8, LUMBER_HEIGHT = 24, FLAT_HEIGHT = 4, KITCHEN_HEIGHT = 6;
    /** Hasta dónde del centro se puede marcar (la aldea + un poco). */
    public static int REACH = VillageCamps.MANAGE_RADIUS + 16;
    /** Sugerencias: árboles y agua a esta distancia del centro, y el tamaño de la zona sugerida. */
    public static int SUGGEST_SEARCH = 48, SUGGEST_LUMBER = 16, SUGGEST_WATER = 9;
    /** Animales con los que arranca un corral nuevo (si adentro no había). */
    public static int PEN_START_ANIMALS = 2;

    private WorkZones() {}

    /** Una zona en el modo Gestión: qué es, dónde está (bloques, inclusive) y su color. */
    public record AreaRow(UUID id, Component kind, BlockPos min, BlockPos max, int color, Component status, int statusColor,
                          Component production) {}

    /** El oficio de un trabajador (null = no es un trabajador que sepamos manejar). */
    @Nullable
    public static VillageManagement.Job jobOf(Entity e) {
        if (e instanceof SmithEntity) return VillageManagement.Job.SMITH;
        if (e instanceof com.ale.warbandcraft.worker.InnkeeperEntity) return VillageManagement.Job.INNKEEPER;
        if (e instanceof com.ale.warbandcraft.worker.MerchantEntity) return VillageManagement.Job.MERCHANT;
        if (e instanceof com.ale.warbandcraft.worker.OrganizerEntity) return VillageManagement.Job.ORGANIZER;
        if (e instanceof FarmerEntity) return VillageManagement.Job.FARMER;
        if (e instanceof LumberjackEntity) return VillageManagement.Job.LUMBERJACK;
        if (e instanceof MinerEntity) return VillageManagement.Job.MINER;
        if (e instanceof FishermanEntity) return VillageManagement.Job.FISHERMAN;
        if (e instanceof AnimalFarmerEntity) return VillageManagement.Job.ANIMAL_FARMER;
        if (e instanceof CookEntity) return VillageManagement.Job.COOK;
        return null;
    }

    /** H18: la zona del trabajador (la actual, o la más cercana tuya de su oficio: de noche Workers la suelta). */
    @Nullable
    public static AbstractWorkAreaEntity areaFor(ServerLevel level, AbstractWorkerEntity w) {
        AbstractWorkAreaEntity a = w.getCurrentWorkArea();
        if (a != null && a.isAlive()) return a;
        VillageManagement.Job job = jobOf(w);
        if (job == null || job == VillageManagement.Job.SMITH || job == VillageManagement.Job.INNKEEPER
                || job == VillageManagement.Job.MERCHANT || job == VillageManagement.Job.ORGANIZER) return null;
        List<AbstractWorkAreaEntity> near = level.getEntitiesOfClass(AbstractWorkAreaEntity.class, w.getBoundingBox().inflate(REACH, 48, REACH),
                x -> x.isAlive() && jobOfArea(x) == job && com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), x.getPlayerUUID()));
        near.sort(Comparator.comparingDouble(x -> x.distanceToSqr(w)));
        return near.isEmpty() ? null : near.get(0);
    }

    /** ¿El trabajador tiene dónde trabajar? */
    public static boolean hasPlace(AbstractWorkerEntity w) {
        if (w instanceof com.ale.warbandcraft.worker.InnkeeperEntity k) return k.getWorkplace() != null;
        if (w instanceof com.ale.warbandcraft.worker.MerchantEntity m) return m.getWorkplace() != null;
        if (w instanceof com.ale.warbandcraft.worker.OrganizerEntity o) return o.getCamp() != null;
        return w instanceof SmithEntity s ? s.getWorkplace() != null : w.getCurrentWorkArea() != null;
    }

    /** Color de cada tipo de zona en la vista aérea. */
    public static int color(@Nullable VillageManagement.Job job) {
        if (job == null) return 0xFFAAAAAA;
        return switch (job) {
            case FARMER -> 0xFF8BD14A;
            case LUMBERJACK -> 0xFF2E8B57;
            case MINER -> 0xFF9E9E9E;
            case FISHERMAN -> 0xFF3FA9F5;
            case ANIMAL_FARMER -> 0xFFE0A060;
            case COOK -> 0xFFFF7043;
            case SMITH -> 0xFFE53935;
            case INNKEEPER -> 0xFFB8860B;
            case MERCHANT -> 0xFF2ECC71;
            case ORGANIZER -> 0xFF8E7CC3;
        };
    }

    @Nullable
    private static VillageManagement.Job jobOfArea(AbstractWorkAreaEntity a) {
        if (a instanceof CropArea) return VillageManagement.Job.FARMER;
        if (a instanceof LumberArea) return VillageManagement.Job.LUMBERJACK;
        if (a instanceof MiningArea) return VillageManagement.Job.MINER;
        if (a instanceof FishingArea) return VillageManagement.Job.FISHERMAN;
        if (a instanceof AnimalPenArea) return VillageManagement.Job.ANIMAL_FARMER;
        if (a instanceof KitchenArea) return VillageManagement.Job.COOK;
        return null;
    }

    /** Tus zonas de Workers cerca de la aldea (y las de sus puestos de afuera) y los lugares de tus herreros. */
    public static List<AreaRow> areas(ServerLevel level, BlockPos center, UUID owner) {
        List<AreaRow> out = new ArrayList<>();
        AABB around = new AABB(center).inflate(REACH, 48, REACH);
        // H18: el estado de cada trabajador va sobre su zona (cartelito del modo Gestión), con lo que produce por día
        Map<UUID, WorkerSupply.Status> byArea = new HashMap<>();
        Map<UUID, Component> made = new HashMap<>();
        WorkerOutput output = WorkerOutput.get(level);
        for (AbstractWorkerEntity w : VillageManagement.workers(level, center, owner)) {
            if (w instanceof SmithEntity) continue;
            AbstractWorkAreaEntity a = areaFor(level, w);
            if (a == null) continue;
            byArea.putIfAbsent(a.getUUID(), WorkerSupply.status(level, w));
            made.putIfAbsent(a.getUUID(), WorkerOutput.text(output.of(w.getUUID())));
        }
        List<AbstractWorkAreaEntity> found = new ArrayList<>(level.getEntitiesOfClass(AbstractWorkAreaEntity.class, around,
                a -> a.isAlive() && a.getArea() != null && owner.equals(a.getPlayerUUID()) && !(a instanceof HomeArea)));
        for (WorkerOutput.Rec r : output.of(center)) {   // los puestos de afuera
            if (!r.outpost) continue;
            for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, new AABB(r.pos).inflate(OUTPOST_SEARCH),
                    a -> a.isAlive() && a.getArea() != null && a.getPersistentData().getLong(TAG_OUTPOST) == center.asLong())) {
                if (found.stream().noneMatch(x -> x.getUUID().equals(a.getUUID()))) found.add(a);
            }
        }
        for (AbstractWorkAreaEntity a : found) {
            AABB b = a.getArea();
            VillageManagement.Job job = jobOfArea(a);
            WorkerSupply.Status st = byArea.get(a.getUUID());
            if (st == null) st = job == null ? new WorkerSupply.Status(Component.empty(), WorkerSupply.GREY)
                    : new WorkerSupply.Status(Component.translatable("status.warbandcraft.worker.no_worker"), WorkerSupply.GREY);
            Component kind = a.getPersistentData().contains(TAG_OUTPOST)
                    ? Component.translatable("gui.warbandcraft.manage.outpost", a.getType().getDescription()) : a.getType().getDescription();
            out.add(new AreaRow(a.getUUID(), kind,
                    BlockPos.containing(b.minX, b.minY, b.minZ), BlockPos.containing(b.maxX - 1, b.maxY - 1, b.maxZ - 1), color(job),
                    st.text(), st.color(), made.getOrDefault(a.getUUID(), Component.empty())));
        }
        for (SmithEntity s : level.getEntitiesOfClass(SmithEntity.class, around,
                s -> s.isAlive() && s.getWorkplace() != null && com.ale.warbandcraft.faction.Allies.same(s.getOwnerUUID(), owner))) {
            BlockPos p = s.getWorkplace();
            WorkerSupply.Status st = WorkerSupply.status(level, s);
            out.add(new AreaRow(s.getUUID(), Component.translatable("gui.warbandcraft.manage.smithy"), p, p.above(), color(VillageManagement.Job.SMITH),
                    st.text(), st.color(), Component.empty()));
        }
        out.sort(Comparator.comparingDouble(r -> r.min().distSqr(center)));
        return out;
    }

    /**
     * H21, Auto-asignar: una zona tuya de la aldea que no tiene trabajador y un aldeano libre (primero los recién llegados)
     * → pasa a ser trabajador de ese oficio, enganchado a esa zona. Devuelve el trabajador nuevo, o null si no hubo.
     */
    @Nullable
    public static AbstractWorkerEntity autoAssign(ServerLevel level, VillageData.Village v) {
        UUID owner = v.owner();
        if (owner == null) return null;
        AABB around = new AABB(v.center()).inflate(REACH, 48, REACH);
        java.util.Set<UUID> used = new java.util.HashSet<>();
        for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, around,
                w -> w.isAlive() && com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), owner))) {
            AbstractWorkAreaEntity a = areaFor(level, w);
            if (a != null) used.add(a.getUUID());
        }
        AbstractWorkAreaEntity empty = null;
        for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, around,
                a -> a.isAlive() && owner.equals(a.getPlayerUUID()) && !(a instanceof HomeArea))) {
            if (used.contains(a.getUUID()) || jobOfArea(a) == null || !inTerritory(level, v, a.blockPosition())) continue;
            empty = a;
            break;
        }
        if (empty == null) return null;
        Villager pick = null;
        for (Villager vi : VillageManagement.villagers(level, v.center())) {
            if (!VillageManagement.isFree(vi)) continue;
            if (pick == null || vi.getPersistentData().getBoolean(Popularity.TAG_SETTLER)) pick = vi;
            if (vi.getPersistentData().getBoolean(Popularity.TAG_SETTLER)) break;
        }
        if (pick == null) return null;
        AbstractWorkerEntity w = VillageManagement.convert(level, pick, jobOfArea(empty), owner);
        if (w == null) return null;
        bind(w, empty);
        return w;
    }

    // ------------------------------------------------------------------ órdenes

    @Nullable
    private static AbstractWorkerEntity worker(ServerPlayer player, VillageData.Village v, UUID id) {
        Entity e = player.serverLevel().getEntity(id);
        if (!(e instanceof AbstractWorkerEntity w) || !w.isAlive() || !com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), player.getUUID())) return null;
        if (VillageData.horizontalDistSq(w.blockPosition(), v.center()) <= (double) REACH * REACH) return w;
        WorkerOutput.Rec r = WorkerOutput.get(player.serverLevel()).of(id);
        return r != null && r.outpost && r.village.equals(v.center()) ? w : null;
    }

    private static Component error(String key, Object... args) {
        return Component.translatable("msg.warbandcraft.zone." + key, args).withStyle(ChatFormatting.RED);
    }

    /** Marcar: la zona del oficio del trabajador sobre el rectángulo (x0, z0)–(x1, z1). */
    public static Component mark(ServerPlayer player, VillageData.Village v, UUID workerId, int x0, int z0, int x1, int z1) {
        ServerLevel level = player.serverLevel();
        AbstractWorkerEntity w = worker(player, v, workerId);
        VillageManagement.Job job = w == null ? null : jobOf(w);
        if (job == null) return error("no_worker");
        if (job == VillageManagement.Job.ORGANIZER) return organizerCamp(level, v, w);   // no tiene zona: el almacén
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        BlockPos mid = new BlockPos((ax + bx) / 2, v.center().getY(), (az + bz) / 2);
        boolean outpost = false;
        if (!inside(level, v, ax, az, bx, bz)) {
            if (job != VillageManagement.Job.LUMBERJACK) return error("outside");
            Component far = outpostProblem(level, v, ax, az, bx, bz);
            if (far != null) return far;
            outpost = true;
        }
        if (job == VillageManagement.Job.INNKEEPER) {   // H19: atiende donde marcaste (un click)
            Integer gy = Blueprints.groundY(level, mid.getX(), mid.getZ(), v.center().getY());
            ((com.ale.warbandcraft.worker.InnkeeperEntity) w).setWorkplace(gy == null ? mid : new BlockPos(mid.getX(), gy + 1, mid.getZ()));
            return Component.translatable("msg.warbandcraft.zone.tavern_set").withStyle(ChatFormatting.GREEN);
        }
        if (job == VillageManagement.Job.MERCHANT) {   // H24: atiende donde marcaste (un click)
            Integer gy = Blueprints.groundY(level, mid.getX(), mid.getZ(), v.center().getY());
            ((com.ale.warbandcraft.worker.MerchantEntity) w).setWorkplace(gy == null ? mid : new BlockPos(mid.getX(), gy + 1, mid.getZ()));
            return Component.translatable("msg.warbandcraft.zone.market_set").withStyle(ChatFormatting.GREEN);
        }
        if (job == VillageManagement.Job.SMITH) {
            Integer g = Blueprints.groundY(level, mid.getX(), mid.getZ(), v.center().getY());
            BlockPos base = g == null ? mid : new BlockPos(mid.getX(), g + 1, mid.getZ());
            Component noAnvil = payAnvil(level, v, base);
            if (noAnvil != null) return noAnvil;
            BlockPos place = smithPlace(level, base);
            if (place == null) return error("no_room");
            ((SmithEntity) w).setWorkplace(place);
            ((SmithEntity) w).setManualWorkplace(true);
            return Component.translatable("msg.warbandcraft.zone.smithy_set").withStyle(ChatFormatting.GREEN);
        }
        int max = job == VillageManagement.Job.MINER ? MAX_MINE_SIDE : MAX_SIDE;
        if (bx - ax + 1 < MIN_SIDE || bz - az + 1 < MIN_SIDE) return error("too_small", MIN_SIDE);
        if (bx - ax + 1 > max || bz - az + 1 > max) return error("too_big", max);
        if (job == VillageManagement.Job.MINER) {   // escalera: larga por donde baja, angosta de costado
            int len = Math.max(bx - ax + 1, bz - az + 1), wide = Math.min(bx - ax + 1, bz - az + 1);
            if (len < MIN_MINE_LENGTH || wide > MAX_MINE_WIDTH) return error("mine_shape", MIN_MINE_LENGTH, MAX_MINE_WIDTH);
        }
        return create(player, v, w, job, ax, az, bx, bz, outpost);
    }

    /** Auto: la zona sugerida para el oficio del trabajador. */
    public static Component suggest(ServerPlayer player, VillageData.Village v, UUID workerId) {
        ServerLevel level = player.serverLevel();
        AbstractWorkerEntity w = worker(player, v, workerId);
        VillageManagement.Job job = w == null ? null : jobOf(w);
        if (job == null) return error("no_worker");
        BlockPos c = v.center();
        switch (job) {
            case ORGANIZER -> {
                return organizerCamp(level, v, w);
            }
            case MERCHANT -> {
                BlockPos place = com.ale.warbandcraft.market.Market.workplace(level, v);
                if (place == null) return error("no_market");
                ((com.ale.warbandcraft.worker.MerchantEntity) w).setWorkplace(place);
                return Component.translatable("msg.warbandcraft.zone.market_set").withStyle(ChatFormatting.GREEN);
            }
            case INNKEEPER -> {
                BlockPos place = Tavern.workplace(level, v);
                if (place == null) return error("no_tavern");
                ((com.ale.warbandcraft.worker.InnkeeperEntity) w).setWorkplace(place);
                return Component.translatable("msg.warbandcraft.zone.tavern_set").withStyle(ChatFormatting.GREEN);
            }
            case SMITH -> {
                if (smithyPlace(level, v) == null) {   // sin Herrería: el yunque al lado del almacén sale del almacén
                    BlockPos store = VillageData.get(level).storehouse(v.center());
                    Component noAnvil = payAnvil(level, v, store != null ? store : v.center());
                    if (noAnvil != null) return noAnvil;
                }
                BlockPos place = defaultSmithPlace(level, v);
                if (place == null) return error("no_room");
                ((SmithEntity) w).setWorkplace(place);
                ((SmithEntity) w).setManualWorkplace(false);
                return Component.translatable("msg.warbandcraft.zone.smithy_set").withStyle(ChatFormatting.GREEN);
            }
            case FARMER -> {
                BoundingBox farm = VillageManagement.findFarm(level, c);
                if (farm == null) farm = freeSite(level, v);   // sin huerta hecha: un terreno libre (el granjero ara)
                if (farm == null) return error("no_suggestion");
                return create(player, v, w, job, farm.minX(), farm.minZ(), farm.maxX(), farm.maxZ(), false);
            }
            case ANIMAL_FARMER -> {
                BoundingBox pen = freeSite(level, v);
                if (pen == null) return error("no_suggestion");
                return create(player, v, w, job, pen.minX(), pen.minZ(), pen.maxX(), pen.maxZ(), false);
            }
            case LUMBERJACK -> {
                BlockPos tree = nearestTree(level, v);
                if (tree == null) return error("no_suggestion");
                int h = SUGGEST_LUMBER / 2;
                return create(player, v, w, job, tree.getX() - h, tree.getZ() - h, tree.getX() + h - 1, tree.getZ() + h - 1, false);
            }
            case FISHERMAN -> {
                BlockPos water = nearestWater(level, v);
                if (water == null) return error("no_suggestion");
                int h = SUGGEST_WATER / 2;
                return create(player, v, w, job, water.getX() - h, water.getZ() - h, water.getX() + h, water.getZ() + h, false);
            }
            case COOK -> {
                BlockPos store = VillageData.get(level).storehouse(c);
                BoundingBox house = store == null ? null : VillageStorehouse.biggestHouse(level, c);
                if (house == null) return error("no_suggestion");
                return create(player, v, w, job, house.minX(), house.minZ(), house.maxX(), house.maxZ(), false);
            }
            default -> {
                return error("mark_it");
            }
        }
    }

    /** Borrar una zona tuya (o el lugar de un herrero). */
    public static Component delete(ServerPlayer player, VillageData.Village v, UUID id) {
        Entity e = player.serverLevel().getEntity(id);
        if (e instanceof SmithEntity s && com.ale.warbandcraft.faction.Allies.same(s.getOwnerUUID(), player.getUUID())) {
            s.setWorkplace(null);
            return Component.translatable("msg.warbandcraft.zone.deleted").withStyle(ChatFormatting.YELLOW);
        }
        if (!(e instanceof AbstractWorkAreaEntity a) || !player.getUUID().equals(a.getPlayerUUID())) return error("no_area");
        if (a.getPersistentData().contains(TAG_OUTPOST)) endOutpost(player.serverLevel(), v, a);
        removeChest(player.serverLevel(), v, a);
        a.discard();
        return Component.translatable("msg.warbandcraft.zone.deleted").withStyle(ChatFormatting.YELLOW);
    }

    // ------------------------------------------------------------------ el granjero de un Campamento común

    /** Hasta dónde del Campamento puede quedar el medio de su huerta (pedido de Ale: no solo adentro del Campamento). */
    public static int CAMP_FARM_RANGE = 40;
    /** Desnivel que acepta la huerta de un Campamento (el granjero ara igual con unos escalones). */
    public static int CAMP_FARM_SLOPE = 3;

    /**
     * Trabajador de un Campamento común: la huerta la elige él. Una huerta hecha (tierra de cultivo + agua) cerca, o si no el
     * terreno libre de 9×9 más parejo y más cerca a ≤{@link #CAMP_FARM_RANGE}. Se prueban todas las esquinas, bloque por
     * bloque (antes se salteaba de a 3-6 y un 9×9 justo no se encontraba nunca). Null = no hay lugar.
     */
    @Nullable
    public static BoundingBox campFarmSite(ServerLevel level, BlockPos camp) {
        int s = Blueprints.SITE, range = CAMP_FARM_RANGE;
        BoundingBox farm = VillageManagement.findFarm(level, camp);
        if (farm != null) return farm;
        List<Blueprints.Lot> taken = new ArrayList<>();
        for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, new AABB(camp).inflate(range + s + 16, 64, range + s + 16),
                WorkZones::usesGround)) {
            AABB b = a.getArea();
            int x0 = (int) Math.floor(b.minX), z0 = (int) Math.floor(b.minZ);
            taken.add(new Blueprints.Lot(new BlockPos(x0, 0, z0), (int) Math.ceil(Math.max(b.maxX - x0, b.maxZ - z0))));
        }
        taken.add(new Blueprints.Lot(camp.offset(-2, 0, -2), 5));   // el Campamento mismo
        // El piso de cada columna, una sola vez
        int half = range + s;
        int side = 2 * half + 1, ox = camp.getX() - half, oz = camp.getZ() - half;
        int[] ground = new int[side * side];
        for (int i = 0; i < side; i++) for (int j = 0; j < side; j++) {
            Integer g = farmGround(level, ox + i, oz + j, camp.getY());
            ground[i * side + j] = g == null ? Integer.MIN_VALUE : g;
        }
        BlockPos best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i + s <= side; i++) for (int j = 0; j + s <= side; j++) {
            int cx = ox + i + s / 2, cz = oz + j + s / 2;
            long d2 = (long) (cx - camp.getX()) * (cx - camp.getX()) + (long) (cz - camp.getZ()) * (cz - camp.getZ());
            if (d2 > (long) range * range) continue;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            boolean ok = true;
            for (int dx = 0; dx < s && ok; dx++) for (int dz = 0; dz < s; dz++) {
                int g = ground[(i + dx) * side + j + dz];
                if (g == Integer.MIN_VALUE) {
                    ok = false;
                    break;
                }
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
            if (!ok || max - min > CAMP_FARM_SLOPE) continue;
            BlockPos corner = new BlockPos(ox + i, min, oz + j);
            if (Blueprints.overlaps(corner, s, taken)) continue;
            long score = (max - min) * 10_000L + d2;   // primero lo más parejo, después lo más cerca
            if (score < bestScore) {
                bestScore = score;
                best = corner;
            }
        }
        if (best == null) return null;
        return new BoundingBox(best.getX(), best.getY(), best.getZ(), best.getX() + s - 1, best.getY(), best.getZ() + s - 1);
    }

    /** El piso de una columna donde se puede arar: tierra (o arena, o ya arada), sin agua, sin nada con datos ni sólido encima. */
    @Nullable
    private static Integer farmGround(ServerLevel level, int x, int z, int hintY) {
        Integer g = Blueprints.groundY(level, x, z, hintY);
        if (g == null) return null;
        BlockPos p = new BlockPos(x, g, z);
        BlockState gs = level.getBlockState(p);
        if (!gs.getFluidState().isEmpty() || gs.is(Blocks.DIRT_PATH)
                || !(gs.is(BlockTags.DIRT) || gs.is(Blocks.FARMLAND) || gs.is(BlockTags.SAND))) return null;
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos q = p.above(dy);
            if (!Blueprints.clearable(level.getBlockState(q)) || level.getBlockEntity(q) != null) return null;
        }
        return g;
    }

    /**
     * Las zonas que ocupan el piso (huerta, corral, tala, mina...). La de almacén y la de una cama no: la del Campamento encierra
     * a todos sus vinculados y tapaba casi toda la jurisdicción (pedido de Ale: "dice que no hay 9×9 plano y sí lo hay").
     */
    private static boolean usesGround(AbstractWorkAreaEntity a) {
        return a.isAlive() && a.getArea() != null && !(a instanceof com.talhanation.workers.entities.workarea.StorageArea)
                && !(a instanceof com.talhanation.workers.entities.workarea.HomeArea);
    }

    /** Lo que cuesta la huerta de un Campamento común: azada y semillas, sin cofre (lo que junta va directo al almacén). */
    public static List<ZoneCosts.Need> campFarmNeeds() {
        return ZoneCosts.needs(VillageManagement.Job.FARMER).stream().filter(n -> !ZoneCosts.isChest(n)).toList();
    }

    /** Crea la huerta {@code farm} del granjero {@code w} de un Campamento común y cobra azada + semillas (a su mochila). */
    public static Component campFarm(ServerPlayer player, com.ale.warbandcraft.camp.CampBlockEntity camp, AbstractWorkerEntity w, BoundingBox farm) {
        ServerLevel level = player.serverLevel();
        List<ZoneCosts.Need> needs = campFarmNeeds();
        Component miss = ZoneCosts.missing(camp, needs);
        if (miss != null) return miss;
        // De lo más bajo a lo más alto del terreno (puede tener unos escalones)
        int ground = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (int x = farm.minX(); x <= farm.maxX(); x++) for (int z = farm.minZ(); z <= farm.maxZ(); z++) {
            Integer g = Blueprints.groundY(level, x, z, farm.minY());
            if (g == null) continue;
            ground = Math.min(ground, g);
            top = Math.max(top, g);
        }
        if (ground == Integer.MAX_VALUE) ground = top = camp.getBlockPos().getY() - 1;
        WorkerZone.BoundingBox box = new WorkerZone.BoundingBox(farm.minX(), ground, farm.minZ(), farm.maxX(), top + FLAT_HEIGHT - 1, farm.maxZ());
        AABB aabb = box.aabb();
        if (!level.getEntitiesOfClass(AbstractWorkAreaEntity.class, aabb.inflate(1), a -> usesGround(a) && a.getArea().intersects(aabb)).isEmpty()) {
            return error("overlap");
        }
        CropArea area = make(level, ModEntityTypes.CROPAREA.get(), a -> {
            ItemStack seed = VillageManagement.seedOf(level, new BoundingBox(farm.minX(), box.y0(), farm.minZ(), farm.maxX(), box.y0(), farm.maxZ()));
            if (!seed.isEmpty()) a.setSeedStack(seed);
        });
        if (area == null) return error("failed");
        area.setWidthSize(box.width() + 1);
        area.setHeightSize(box.height());
        area.setDepthSize(box.depth() + 1);
        area.setPlayerUUID(player.getUUID());
        area.setPlayerName(player.getGameProfile().getName());
        WorkerZone.share(area);
        if (!WorkerZone.place(area, box)) {
            area.discard();
            return error("failed");
        }
        area.setDone(false);
        level.addFreshEntity(area);
        bind(w, area);
        for (ZoneCosts.Need n : needs) for (ItemStack it : ZoneCosts.take(camp, n)) give(w, it);
        return Component.translatable("msg.warbandcraft.zone.created", area.getType().getDescription()).withStyle(ChatFormatting.GREEN);
    }

    // ------------------------------------------------------------------ crear la zona de Workers

    private static Component create(ServerPlayer player, VillageData.Village v, AbstractWorkerEntity w, VillageManagement.Job job,
                                    int ax, int az, int bx, int bz, boolean outpost) {
        ServerLevel level = player.serverLevel();
        if (!outpost && !inside(level, v, ax, az, bx, bz)) return error("outside");
        // Lo que la zona saca del almacén (herramienta, semillas/retoños/trigo, el cofre): sin eso no se crea
        com.ale.warbandcraft.camp.CampBlockEntity camp = VillageStorehouse.camp(level, v);
        List<ZoneCosts.Need> needs = ZoneCosts.needs(job);
        if (!needs.isEmpty()) {
            if (camp == null) return Component.translatable("msg.warbandcraft.manage.no_camp").withStyle(ChatFormatting.RED);
            Component miss = ZoneCosts.missing(camp, needs);
            if (miss != null) return miss;
        }
        BlockPos mid = new BlockPos((ax + bx) / 2, v.center().getY(), (az + bz) / 2);
        int hint = hintY(level, v, mid.getX(), mid.getZ(), outpost);
        Integer g = Blueprints.groundY(level, mid.getX(), mid.getZ(), hint);
        int ground = g == null ? hint - 1 : g;
        int y0, height;
        switch (job) {
            case MINER -> {
                y0 = ground - MINE_DEPTH + 1;
                height = MINE_DEPTH;
            }
            case LUMBERJACK -> {
                y0 = ground;
                height = LUMBER_HEIGHT;
            }
            case FISHERMAN -> {
                y0 = ground - 2;
                height = FLAT_HEIGHT;
            }
            case COOK -> {
                BlockPos store = VillageData.get(level).storehouse(v.center());
                y0 = (store != null ? store.getY() : ground + 1) - 1;
                height = KITCHEN_HEIGHT;
            }
            default -> {
                y0 = ground;
                height = FLAT_HEIGHT;
            }
        }
        WorkerZone.BoundingBox box = new WorkerZone.BoundingBox(ax, y0, az, bx, y0 + height - 1, bz);
        if (WorkerZone.existing(level, box.aabb()) != null) return error("overlap");
        if (job == VillageManagement.Job.FISHERMAN && !hasWater(level, box)) return error("no_water");

        AbstractWorkAreaEntity area = switch (job) {
            case FARMER -> make(level, ModEntityTypes.CROPAREA.get(), a -> {
                ItemStack seed = VillageManagement.seedOf(level, new BoundingBox(ax, ground, az, bx, ground, bz));
                if (!seed.isEmpty()) a.setSeedStack(seed);
            });
            case LUMBERJACK -> make(level, ModEntityTypes.LUMBERAREA.get(), a -> {   // tala y vuelve a plantar
                a.setReplant(true);
                a.setSaplingStack(saplingOf(level, box));
            });
            case MINER -> make(level, ModEntityTypes.MININGAREA.get(), a -> {
                a.setMode(MiningArea.MiningMode.STAIRS_DOWN.getIndex());   // pedido de Ale: escalera, no un pozo
                a.setKeepOn(true);   // H18: no queda "terminada" (después siguen los pasillos y la veta)
            });
            case FISHERMAN -> make(level, ModEntityTypes.FISHINGAREA.get(), a -> {});
            case ANIMAL_FARMER -> make(level, ModEntityTypes.ANIMAL_PEN_AREA.get(), a -> a.setAnimalType(animalType(level, box.aabb())));
            case COOK -> make(level, ModEntityTypes.KITCHEN_AREA.get(), a -> {});
            default -> null;
        };
        if (area == null) return error("failed");
        // Workers arma la caja un bloque más corta de lo que dice el tamaño: se le suma uno para que cubra lo marcado
        area.setWidthSize(box.width() + 1);
        area.setHeightSize(box.height());
        area.setDepthSize(box.depth() + 1);
        area.setPlayerUUID(player.getUUID());
        area.setPlayerName(player.getGameProfile().getName());
        WorkerZone.share(area);
        BlockPos store = VillageData.get(level).storehouse(v.center());
        boolean placed = area instanceof MiningArea mine
                ? placeStairs(mine, ax, az, bx, bz, ground, store != null ? store : v.center())
                : WorkerZone.place(area, box);
        if (!placed) {
            area.discard();
            return error("failed");
        }
        if (outpost) area.getPersistentData().putLong(TAG_OUTPOST, v.center().asLong());
        area.setDone(false);
        level.addFreshEntity(area);
        bind(w, area);
        if (area instanceof AnimalPenArea pen) stockPen(level, pen, box);
        // Se cobra: la herramienta al trabajador, lo demás al cofre de la zona
        List<ItemStack> forChest = new ArrayList<>();
        boolean chestPaid = false;
        for (ZoneCosts.Need n : needs) {
            List<ItemStack> taken = ZoneCosts.take(camp, n);
            if (ZoneCosts.isChest(n)) chestPaid = !taken.isEmpty();
            else if (n.toWorker()) for (ItemStack s : taken) give(w, s);
            else forChest.addAll(taken);
        }
        if (chestPaid && !zoneChest(level, v, area, box, player, forChest, outpost)) {
            camp.insert(new ItemStack(Items.CHEST));   // no hubo lugar para el cofre: vuelve al almacén
            for (ItemStack s : forChest) camp.insert(s);
        }
        if (outpost) {
            startOutpost(level, v, w, area);
            return Component.translatable("msg.warbandcraft.zone.outpost_created").withStyle(ChatFormatting.GREEN);
        }
        if (area instanceof AnimalPenArea pen && entityOf(pen.getAnimalType()) != null) {
            return Component.translatable("msg.warbandcraft.zone.pen_created", entityOf(pen.getAnimalType()).getDescription())
                    .withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("msg.warbandcraft.zone.created", area.getType().getDescription()).withStyle(ChatFormatting.GREEN);
    }

    private static <T extends AbstractWorkAreaEntity> T make(ServerLevel level, EntityType<T> type, Consumer<T> setup) {
        T a = type.create(level);
        if (a != null) setup.accept(a);
        return a;
    }

    /** Le da una herramienta al trabajador (lo que no le entra, al piso a sus pies). */
    private static void give(AbstractWorkerEntity w, ItemStack s) {
        ItemStack left = w.addItem(s);
        if (!left.isEmpty()) w.spawnAtLocation(left);
    }

    /** El trabajador queda enganchado a su zona nueva (si no, Workers le elige la más cercana libre). */
    private static void bind(AbstractWorkerEntity w, AbstractWorkAreaEntity a) {
        if (w instanceof FarmerEntity f && a instanceof CropArea c) f.currentCropArea = c;
        else if (w instanceof LumberjackEntity l && a instanceof LumberArea c) l.currentLumberArea = c;
        else if (w instanceof MinerEntity m && a instanceof MiningArea c) m.currentMiningArea = c;
        else if (w instanceof FishermanEntity f && a instanceof FishingArea c) f.currentFishingArea = c;
        else if (w instanceof AnimalFarmerEntity f && a instanceof AnimalPenArea c) f.currentAnimalPen = c;
        else if (w instanceof CookEntity k && a instanceof KitchenArea c) k.currentKitchenArea = c;
    }

    private static boolean hasWater(ServerLevel level, WorkerZone.BoundingBox box) {
        for (BlockPos p : BlockPos.betweenClosed(box.x0(), box.y0(), box.z0(), box.x1(), box.y1(), box.z1())) {
            if (level.getBlockState(p).is(Blocks.WATER)) return true;
        }
        return false;
    }

    /** El animal que más hay en el corral; si no hay ninguno, el del bioma ({@link #biomeAnimal}). */
    private static AnimalPenArea.AnimalTypes animalType(ServerLevel level, AABB box) {
        Map<AnimalPenArea.AnimalTypes, Integer> count = new EnumMap<>(AnimalPenArea.AnimalTypes.class);
        for (Animal a : level.getEntitiesOfClass(Animal.class, box.inflate(2))) {
            AnimalPenArea.AnimalTypes t = typeOf(a.getType());
            if (t != null) count.merge(t, 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElseGet(() -> biomeAnimal(level, BlockPos.containing(box.getCenter())));
    }

    /**
     * El animal de cada bioma (pedido de Ale): montaña = cabras, desierto/badlands = camellos, sabana = caballos, taiga =
     * ovejas, nieve = conejos, selva = gallinas, bosque/pantano = cerdos, el resto (llanuras...) = vacas.
     */
    public static AnimalPenArea.AnimalTypes biomeAnimal(ServerLevel level, BlockPos pos) {
        var b = level.getBiome(pos);
        if (b.is(net.minecraft.tags.BiomeTags.IS_MOUNTAIN) || b.is(net.minecraft.world.level.biome.Biomes.WINDSWEPT_HILLS)
                || b.is(net.minecraft.world.level.biome.Biomes.WINDSWEPT_GRAVELLY_HILLS)) return AnimalPenArea.AnimalTypes.GOAT;
        if (b.is(net.minecraft.world.level.biome.Biomes.DESERT) || b.is(net.minecraft.tags.BiomeTags.IS_BADLANDS)) return AnimalPenArea.AnimalTypes.CAMEL;
        if (b.is(net.minecraft.tags.BiomeTags.IS_SAVANNA)) return AnimalPenArea.AnimalTypes.HORSE;
        if (b.is(net.minecraft.tags.BiomeTags.IS_TAIGA)) return AnimalPenArea.AnimalTypes.SHEEP;
        if (b.is(net.minecraftforge.common.Tags.Biomes.IS_SNOWY)) return AnimalPenArea.AnimalTypes.RABBIT;
        if (b.is(net.minecraft.tags.BiomeTags.IS_JUNGLE)) return AnimalPenArea.AnimalTypes.CHICKEN;
        if (b.is(net.minecraft.tags.BiomeTags.IS_FOREST) || b.is(net.minecraftforge.common.Tags.Biomes.IS_SWAMP)) return AnimalPenArea.AnimalTypes.PIG;
        return AnimalPenArea.AnimalTypes.COW;
    }

    @Nullable
    private static EntityType<? extends Animal> entityOf(AnimalPenArea.AnimalTypes t) {
        return switch (t) {
            case COW -> EntityType.COW;
            case SHEEP -> EntityType.SHEEP;
            case PIG -> EntityType.PIG;
            case CHICKEN -> EntityType.CHICKEN;
            case GOAT -> EntityType.GOAT;
            case RABBIT -> EntityType.RABBIT;
            case HORSE -> EntityType.HORSE;
            case CAMEL -> EntityType.CAMEL;
        };
    }

    /** Corral nuevo: si adentro hay menos de {@link #PEN_START_ANIMALS} de su animal, aparecen los que faltan (para criar). */
    private static void stockPen(ServerLevel level, AnimalPenArea pen, WorkerZone.BoundingBox box) {
        EntityType<? extends Animal> type = entityOf(pen.getAnimalType());
        if (type == null) return;
        int have = level.getEntitiesOfClass(Animal.class, box.aabb().inflate(1), a -> a.getType() == type).size();
        var random = level.getRandom();
        for (int i = have; i < PEN_START_ANIMALS; i++) {
            int x = box.x0() + 1 + random.nextInt(Math.max(1, box.width() - 2));
            int z = box.z0() + 1 + random.nextInt(Math.max(1, box.depth() - 2));
            Integer g = Blueprints.groundY(level, x, z, box.y0() + 1);
            Animal a = type.create(level);
            if (a == null) continue;
            a.moveTo(x + 0.5, (g == null ? box.y0() : g) + 1, z + 0.5, random.nextFloat() * 360, 0);
            a.setPersistenceRequired();
            level.addFreshEntity(a);
        }
    }

    /** Un terreno libre de 9×9 para una zona (corral, o huerta si no hay una hecha), cerca del almacén y en el territorio. */
    @Nullable
    private static BoundingBox freeSite(ServerLevel level, VillageData.Village v) {
        BlockPos store = VillageData.get(level).storehouse(v.center());
        BlockPos from = store != null ? store : v.center();
        List<Blueprints.Lot> taken = new ArrayList<>();
        TownData.Town t = TownData.get(level).at(v.center());
        if (t != null) taken.addAll(t.lots(null));
        int s = Blueprints.SITE;
        for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, new AABB(v.center()).inflate(REACH + 16, 64, REACH + 16),
                WorkZones::usesGround)) {   // las zonas que ya hay, como terrenos ocupados
            AABB b = a.getArea();
            for (int x = (int) Math.floor(b.minX) - s; x < b.maxX; x += s) {
                for (int z = (int) Math.floor(b.minZ) - s; z < b.maxZ; z += s) taken.add(new Blueprints.Lot(new BlockPos(x, 0, z), s));
            }
        }
        for (int tries = 0; tries < 6; tries++) {
            BlockPos site = Blueprints.findSite(level, from, 6, SUGGEST_SEARCH, s, taken);
            if (site == null) return null;
            if (inside(level, v, site.getX(), site.getZ(), site.getX() + s - 1, site.getZ() + s - 1)) {
                return new BoundingBox(site.getX(), site.getY(), site.getZ(), site.getX() + s - 1, site.getY(), site.getZ() + s - 1);
            }
            taken.add(new Blueprints.Lot(site, s));
        }
        return null;
    }

    @Nullable
    private static AnimalPenArea.AnimalTypes typeOf(EntityType<?> t) {
        if (t == EntityType.COW) return AnimalPenArea.AnimalTypes.COW;
        if (t == EntityType.SHEEP) return AnimalPenArea.AnimalTypes.SHEEP;
        if (t == EntityType.PIG) return AnimalPenArea.AnimalTypes.PIG;
        if (t == EntityType.CHICKEN) return AnimalPenArea.AnimalTypes.CHICKEN;
        if (t == EntityType.GOAT) return AnimalPenArea.AnimalTypes.GOAT;
        if (t == EntityType.RABBIT) return AnimalPenArea.AnimalTypes.RABBIT;
        if (t == EntityType.HORSE) return AnimalPenArea.AnimalTypes.HORSE;
        if (t == EntityType.CAMEL) return AnimalPenArea.AnimalTypes.CAMEL;
        return null;
    }

    // ------------------------------------------------------------------ sugerencias

    /** El tronco de árbol (con hojas arriba) más cercano al centro, fuera de la aldea si se puede. */
    @Nullable
    private static BlockPos nearestTree(ServerLevel level, VillageData.Village v) {
        BlockPos center = v.center();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-SUGGEST_SEARCH, -6, -SUGGEST_SEARCH), center.offset(SUGGEST_SEARCH, 12, SUGGEST_SEARCH))) {
            if (!level.isLoaded(p) || !level.getBlockState(p).is(BlockTags.LOGS) || !inTerritory(level, v, p)) continue;
            if (level.getBlockState(p.below()).is(BlockTags.LOGS) || !level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;
            if (!leavesAbove(level, p)) continue;
            double d = p.distSqr(center);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private static boolean leavesAbove(ServerLevel level, BlockPos base) {
        for (int dy = 2; dy <= 10; dy++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(base.above(dy).relative(d)).is(BlockTags.LEAVES)) return true;
            }
        }
        return false;
    }

    /** Agua (con aire arriba y agua alrededor) más cercana al centro. */
    @Nullable
    private static BlockPos nearestWater(ServerLevel level, VillageData.Village v) {
        BlockPos center = v.center();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-SUGGEST_SEARCH, -8, -SUGGEST_SEARCH), center.offset(SUGGEST_SEARCH, 4, SUGGEST_SEARCH))) {
            if (!level.isLoaded(p) || !level.getBlockState(p).is(Blocks.WATER) || !level.getBlockState(p.above()).isAir()
                    || !inTerritory(level, v, p)) continue;
            boolean pond = true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!level.getBlockState(p.relative(d)).is(Blocks.WATER)) pond = false;
            }
            if (!pond) continue;
            double d = p.distSqr(center);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ territorio

    /** ¿{@code p} está en el territorio de la aldea? (sin territorio todavía: a {@link #REACH} del centro). */
    public static boolean inTerritory(ServerLevel level, VillageData.Village v, BlockPos p) {
        Territory t = Territory.get(level);
        if (t.has(v.center())) return t.contains(v.center(), p);
        return VillageData.horizontalDistSq(p, v.center()) <= (double) REACH * REACH;
    }

    /** Las cuatro esquinas del rectángulo están en el territorio. */
    private static boolean inside(ServerLevel level, VillageData.Village v, int ax, int az, int bx, int bz) {
        int y = v.center().getY();
        return inTerritory(level, v, new BlockPos(ax, y, az)) && inTerritory(level, v, new BlockPos(bx, y, az))
                && inTerritory(level, v, new BlockPos(ax, y, bz)) && inTerritory(level, v, new BlockPos(bx, y, bz));
    }

    // ------------------------------------------------------------------ el cofre de cada zona

    /** Marcas en la zona de Workers: dónde está su cofre y su zona de almacén. */
    private static final String TAG_CHEST = "warbandcraft:zone_chest", TAG_STORAGE = "warbandcraft:zone_storage";

    /** El Organizador trabaja en el almacén de la aldea (su Campamento y los cofres vinculados). */
    private static Component organizerCamp(ServerLevel level, VillageData.Village v, AbstractWorkerEntity w) {
        com.ale.warbandcraft.camp.CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp == null) return Component.translatable("msg.warbandcraft.manage.no_camp").withStyle(ChatFormatting.RED);
        ((com.ale.warbandcraft.worker.OrganizerEntity) w).setCamp(camp.getBlockPos());
        return Component.translatable("msg.warbandcraft.organizer.set").withStyle(ChatFormatting.GREEN);
    }

    /** Los cofres de las zonas de trabajo cerca de un Campamento (el Organizador no los toca). */
    public static java.util.Set<BlockPos> zoneChests(ServerLevel level, BlockPos camp) {
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        for (AbstractWorkAreaEntity a : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, new net.minecraft.world.phys.AABB(camp).inflate(96, 48, 96))) {
            if (!a.getPersistentData().contains(TAG_CHEST)) continue;
            BlockPos p = BlockPos.of(a.getPersistentData().getLong(TAG_CHEST));
            out.add(p);
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                out.add(p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st)));
            }
        }
        return out;
    }

    /**
     * Un cofre al lado de la zona (del lado del almacén, adentro del territorio), con su zona de almacén de Workers para
     * que el trabajador deje ahí lo que junta, y vinculado al Campamento de la aldea: lo de adentro cuenta como almacén.
     */
    private static boolean zoneChest(ServerLevel level, VillageData.Village v, AbstractWorkAreaEntity area, WorkerZone.BoundingBox box,
                                     ServerPlayer player, List<ItemStack> contents, boolean outpost) {
        com.ale.warbandcraft.camp.CampBlockEntity camp = VillageStorehouse.camp(level, v);
        BlockPos toward = camp != null ? camp.getBlockPos() : v.center();
        List<BlockPos> ring = new ArrayList<>();
        for (int x = box.x0() - 1; x <= box.x1() + 1; x++) {
            ring.add(new BlockPos(x, 0, box.z0() - 1));
            ring.add(new BlockPos(x, 0, box.z1() + 1));
        }
        for (int z = box.z0(); z <= box.z1(); z++) {
            ring.add(new BlockPos(box.x0() - 1, 0, z));
            ring.add(new BlockPos(box.x1() + 1, 0, z));
        }
        ring.sort(Comparator.comparingDouble(p -> VillageData.horizontalDistSq(p, toward)));
        for (BlockPos c : ring) {
            Integer g = Blueprints.groundY(level, c.getX(), c.getZ(), hintY(level, v, c.getX(), c.getZ(), outpost));
            if (g == null) continue;
            BlockPos p = new BlockPos(c.getX(), g + 1, c.getZ());
            if (!free(level, p) || (!outpost && !inTerritory(level, v, p))) continue;
            level.setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
            Component name = Component.translatable("msg.warbandcraft.zone.chest", area.getType().getDescription());
            if (level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                chest.setCustomName(name);
                for (int i = 0; i < contents.size() && i < chest.getContainerSize(); i++) chest.setItem(i, contents.get(i));
            }
            var storage = WorkerZone.storage(level, new WorkerZone.BoundingBox(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY() + 1, p.getZ()),
                    player.getUUID(), player.getGameProfile().getName(), name);
            if (!outpost && camp != null && !camp.getLinked().contains(p)) camp.toggleLink(p);   // el del puesto se vacía solo al almacén
            area.getPersistentData().putLong(TAG_CHEST, p.asLong());
            if (storage != null) area.getPersistentData().putUUID(TAG_STORAGE, storage.getUUID());
            return true;
        }
        return false;
    }

    /** Se borra la zona: su cofre pasa lo que tiene al almacén, se desvincula y se saca (con su zona de almacén). */
    private static void removeChest(ServerLevel level, VillageData.Village v, AbstractWorkAreaEntity area) {
        var pd = area.getPersistentData();
        if (pd.hasUUID(TAG_STORAGE) && level.getEntity(pd.getUUID(TAG_STORAGE)) instanceof AbstractWorkAreaEntity s) s.discard();
        if (!pd.contains(TAG_CHEST)) return;
        BlockPos p = BlockPos.of(pd.getLong(TAG_CHEST));
        if (!(level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) return;
        com.ale.warbandcraft.camp.CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp != null && camp.getLinked().contains(p)) camp.toggleLink(p);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.removeItemNoUpdate(i);
            if (s.isEmpty()) continue;
            ItemStack left = camp != null ? camp.insert(s) : s;
            if (!left.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, left);
        }
        level.removeBlock(p, false);
    }

    /** El retoño del árbol que más hay en la zona (roble si no hay). */
    private static ItemStack saplingOf(ServerLevel level, WorkerZone.BoundingBox box) {
        Map<String, Integer> count = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(box.x0(), box.y0(), box.z0(), box.x1(), Math.min(box.y1(), box.y0() + 10), box.z1())) {
            BlockState s = level.getBlockState(p);
            if (!s.is(BlockTags.LOGS)) continue;
            var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
            if (key != null && key.getPath().endsWith("_log")) count.merge(key.getNamespace() + ":" + key.getPath().replace("_log", ""), 1, Integer::sum);
        }
        String best = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("minecraft:oak");
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.tryParse(best + "_sapling"));
        return new ItemStack(item == null || item == Items.AIR ? Items.OAK_SAPLING : item);
    }

    // ------------------------------------------------------------------ el lugar del herrero

    /** Herrería de H12 (su yunque) si la aldea la tiene; si no, un yunque nuevo al lado del almacén. */
    @Nullable
    public static BlockPos defaultSmithPlace(ServerLevel level, VillageData.Village v) {
        BlockPos smithy = smithyPlace(level, v);
        if (smithy != null) return smithy;
        BlockPos store = VillageData.get(level).storehouse(v.center());
        return smithPlace(level, store != null ? store : v.center());
    }

    /**
     * Si cerca de {@code base} no hay yunque, hay que poner uno: sale del almacén de la aldea. Devuelve el mensaje de lo
     * que falta, o null si ya hay yunque o se pagó.
     */
    @Nullable
    private static Component payAnvil(ServerLevel level, VillageData.Village v, BlockPos base) {
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -1, -3), base.offset(3, 1, 3))) {
            if (level.getBlockState(p).is(BlockTags.ANVIL)) return null;
        }
        com.ale.warbandcraft.camp.CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp == null) return Component.translatable("msg.warbandcraft.manage.no_camp").withStyle(ChatFormatting.RED);
        Component miss = ZoneCosts.missing(camp, List.of(ZoneCosts.ANVIL));
        if (miss != null) return miss;
        ZoneCosts.take(camp, ZoneCosts.ANVIL);
        return null;
    }

    /** Al lado del yunque de la Herrería de H12, si la aldea la tiene terminada; si no, null. */
    @Nullable
    public static BlockPos smithyPlace(ServerLevel level, VillageData.Village v) {
        TownData.Town t = TownData.get(level).at(v.center());
        if (t == null || !t.has(Upgrade.SMITHY)) return null;
        BlockPos site = t.sites.get(Upgrade.SMITHY.siteKey());
        if (site == null) return null;
        int n = t.siteSize(Upgrade.SMITHY.siteKey()) - 1;
        for (BlockPos p : BlockPos.betweenClosed(site, site.offset(n, 3, n))) {
            if (level.getBlockState(p).is(BlockTags.ANVIL)) {
                BlockPos stand = standNextTo(level, p.immutable());
                if (stand != null) return stand;
            }
        }
        return null;
    }

    /** Un lugar de trabajo cerca de {@code near} (a la altura del piso + 1): usa un yunque que haya a ≤ 3 bloques, o pone uno. */
    @Nullable
    public static BlockPos smithPlace(ServerLevel level, BlockPos near) {
        BlockPos base = near;
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-3, -1, -3), base.offset(3, 1, 3))) {
            if (level.getBlockState(p).is(BlockTags.ANVIL)) {
                BlockPos stand = standNextTo(level, p.immutable());
                if (stand != null) return stand;
            }
        }
        for (int r = 1; r <= 3; r++) {
            for (BlockPos p : BlockPos.betweenClosed(base.offset(-r, -1, -r), base.offset(r, 1, r))) {
                if (!free(level, p)) continue;
                BlockPos anvil = p.immutable();
                level.setBlockAndUpdate(anvil, Blocks.ANVIL.defaultBlockState());
                BlockPos stand = standNextTo(level, anvil);
                if (stand != null) return stand;
                level.removeBlock(anvil, false);
            }
        }
        return null;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        return level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && below.isFaceSturdy(level, p.below(), Direction.UP);
    }

    @Nullable
    public static BlockPos standNextTo(ServerLevel level, BlockPos anvil) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = anvil.relative(d);
            if (free(level, s)) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ la mina en escalera

    /**
     * La zona de mina en escalera de Workers (STAIRS_DOWN) justo sobre el rectángulo: baja a lo largo del lado largo,
     * empezando por la punta más cerca de {@code toward} (el almacén). Workers mide los escalones desde la posición de la
     * zona, que va parada sobre el piso ({@code ground} + 1). Anota hacia dónde baja y dónde termina (para los pasillos).
     */
    private static boolean placeStairs(MiningArea area, int ax, int az, int bx, int bz, int ground, BlockPos toward) {
        int lx = bx - ax + 1, lz = bz - az + 1, len = Math.max(lx, lz), wide = Math.min(lx, lz);
        Direction.Axis run = lx >= lz ? Direction.Axis.X : Direction.Axis.Z;
        Direction bestFacing = null;
        int bestX = 0, bestZ = 0, bestW = 0, bestD = 0;
        double bestDist = Double.MAX_VALUE;
        int[][] sizes = {{len, wide}, {wide, len}};   // ancho = hacia adelante (por donde baja); por las dudas, al revés también
        for (int[] size : sizes) {
            area.setWidthSize(size[0] + 1);
            area.setDepthSize(size[1] + 1);
            area.setHeightSize(len + 1);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                if (lx != lz && facing.getAxis() != run) continue;
                area.setFacing(facing);
                for (int x : new int[]{ax, bx + 1, bx, ax - 1}) {
                    for (int z : new int[]{az, bz + 1, bz, az - 1}) {
                        area.moveTo(x + 0.5, ground + 1, z + 0.5, 0, 0);
                        AABB m = area.createArea();
                        if (m.minX != ax || m.minZ != az || m.maxX != bx + 1 || m.maxZ != bz + 1) continue;
                        double d = VillageData.horizontalDistSq(new BlockPos(x, 0, z), toward);
                        if (d < bestDist) {
                            bestDist = d;
                            bestFacing = facing;
                            bestX = x;
                            bestZ = z;
                            bestW = size[0];
                            bestD = size[1];
                        }
                    }
                }
            }
            if (bestFacing != null) break;
        }
        if (bestFacing == null) return false;
        area.setWidthSize(bestW + 1);
        area.setDepthSize(bestD + 1);
        area.setHeightSize(len + 1);
        area.setFacing(bestFacing);
        area.moveTo(bestX + 0.5, ground + 1, bestZ + 0.5, 0, 0);
        area.area = area.createArea();
        // Baja alejándose de la punta donde está la zona: la otra punta es el final de la escalera
        Direction.Axis axis = bestFacing.getAxis();
        BlockPos end;
        Direction dir;
        if (axis == Direction.Axis.X) {
            boolean farMax = Math.abs(bx - bestX) >= Math.abs(ax - bestX);
            end = new BlockPos(farMax ? bx : ax, ground, (az + bz) / 2);
            dir = farMax ? Direction.EAST : Direction.WEST;
        } else {
            boolean farMax = Math.abs(bz - bestZ) >= Math.abs(az - bestZ);
            end = new BlockPos((ax + bx) / 2, ground, farMax ? bz : az);
            dir = farMax ? Direction.SOUTH : Direction.NORTH;
        }
        var pd = area.getPersistentData();
        pd.putInt(WorkerSupply.TAG_STAGE, 0);
        pd.putInt(WorkerSupply.TAG_DIR, dir.get2DDataValue());
        pd.putLong(WorkerSupply.TAG_END, end.asLong());
        return true;
    }

    // ------------------------------------------------------------------ el puesto de afuera (leñador)

    /** Marca en la zona de un puesto: el centro de su aldea. */
    public static final String TAG_OUTPOST = "warbandcraft:outpost";
    /** Dónde se buscan las zonas de un puesto alrededor de su trabajador. */
    private static final int OUTPOST_SEARCH = 48;

    /** Desde dónde buscar el piso: en la aldea, su altura; afuera, la del terreno ahí. */
    private static int hintY(ServerLevel level, VillageData.Village v, int x, int z, boolean outpost) {
        if (!outpost) return v.center().getY();
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    /** ¿Se puede poner un puesto acá? Null = sí; si no, por qué. */
    @Nullable
    private static Component outpostProblem(ServerLevel level, VillageData.Village v, int ax, int az, int bx, int bz) {
        BlockPos mid = new BlockPos((ax + bx) / 2, v.center().getY(), (az + bz) / 2);
        if (VillageData.horizontalDistSq(mid, v.center()) > (double) OUTPOST_RANGE * OUTPOST_RANGE) return error("outpost_far", OUTPOST_RANGE);
        if (!level.isLoaded(mid)) return error("outpost_far", OUTPOST_RANGE);
        Territory t = Territory.get(level);
        for (VillageData.Village o : VillageData.get(level).all()) {
            if (o.center().equals(v.center())) continue;
            if (VillageData.horizontalDistSq(mid, o.center()) < (double) OUTPOST_OTHER_VILLAGE * OUTPOST_OTHER_VILLAGE
                    || (t.has(o.center()) && t.contains(o.center(), mid))) return error("outpost_village");
        }
        return null;
    }

    /**
     * El leñador se muda al puesto: cama al lado del cofre (deja la de la aldea), queda anotado como puesto (lo que deja en
     * el cofre va al almacén de la aldea) y aparece ahí.
     */
    private static void startOutpost(ServerLevel level, VillageData.Village v, AbstractWorkerEntity w, AbstractWorkAreaEntity area) {
        WorkerOutput.Rec rec = WorkerOutput.get(level).ensure(w.getUUID(), v.center(), w.blockPosition(), level.getGameTime());
        rec.outpost = true;
        BlockPos chest = chestOf(area);
        BlockPos near = chest != null ? chest : BlockPos.containing(area.getArea().getCenter());
        BlockPos head = outpostBed(level, near);
        if (head != null) {
            WorkerSupply.leaveHome(level, w);
            level.getPoiManager().take(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), (h, p) -> p.equals(head), head, 2);
            WorkerSupply.homeAt(level, v.owner() != null ? v.owner() : w.getOwnerUUID(), w, head);
        }
        BlockPos to = (head != null ? head : near).above();
        w.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        w.getNavigation().stop();
        rec.pos = w.blockPosition();
    }

    /** Una cama al lado de {@code near} (el cofre del puesto). Devuelve la cabecera, o null si no hubo lugar. */
    @Nullable
    private static BlockPos outpostBed(ServerLevel level, BlockPos near) {
        for (int r = 1; r <= 3; r++) {
            for (BlockPos c : BlockPos.betweenClosed(near.offset(-r, -1, -r), near.offset(r, 1, r))) {
                if (!free(level, c)) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos foot = c.immutable(), head = foot.relative(d);
                    if (!free(level, head) || head.equals(near)) continue;
                    BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, d);
                    level.setBlock(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                            net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
                    level.setBlock(head, bed.setValue(net.minecraft.world.level.block.BedBlock.PART,
                            net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
                    return head;
                }
            }
        }
        return null;
    }

    /** Se borra la zona de un puesto: el leñador vuelve a la aldea (y a buscar cama ahí). */
    private static void endOutpost(ServerLevel level, VillageData.Village v, AbstractWorkAreaEntity area) {
        WorkerOutput out = WorkerOutput.get(level);
        for (WorkerOutput.Rec r : out.of(v.center())) {
            if (!r.outpost || !(level.getEntity(r.worker) instanceof AbstractWorkerEntity w)) continue;
            AbstractWorkAreaEntity a = areaFor(level, w);
            if (a != null && !a.getUUID().equals(area.getUUID())) continue;
            r.outpost = false;
            WorkerSupply.leaveHome(level, w);
            BlockPos store = VillageData.get(level).storehouse(v.center());
            BlockPos to = (store != null ? store : v.center()).above();
            w.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            w.getNavigation().stop();
        }
    }

    /** El cofre de una zona (null si no tiene). */
    @Nullable
    public static BlockPos chestOf(AbstractWorkAreaEntity area) {
        var pd = area.getPersistentData();
        return pd.contains(TAG_CHEST) ? BlockPos.of(pd.getLong(TAG_CHEST)) : null;
    }
}
