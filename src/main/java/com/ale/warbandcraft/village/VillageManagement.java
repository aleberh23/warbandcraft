package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampStats;
import com.ale.warbandcraft.camp.Forge;
import com.ale.warbandcraft.camp.WorkerZone;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.worker.SmithEntity;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.workarea.CropArea;
import com.talhanation.workers.init.ModEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * H16: el modo Gestión de una aldea o castillo tuyo (vista aérea, botón Gestión). Arma los datos (almacén,
 * barracón, aldeanos libres, trabajadores, mejoras, rutas) y hace las órdenes: darle un oficio de Villager Workers a
 * un aldeano libre.
 */
public final class VillageManagement {
    /** Hasta dónde se puede estar del centro para gestionar (la cámara aérea puede ir más lejos que tu cuerpo). */
    public static int REACH = VillageCamps.MANAGE_RADIUS + 32;
    /** Dónde se buscan aldeanos y trabajadores de la aldea. */
    public static int RADIUS = VillageCamps.MANAGE_RADIUS;
    /** La huerta para el Granjero: a esta distancia del centro, y hasta este tamaño. */
    public static int FARM_SEARCH = 48, FARM_MAX = 24;

    private VillageManagement() {}

    /** Los oficios que se pueden dar (entidades de Villager Workers, y el Herrero nuestro). El orden es el de los botones. */
    public enum Job {
        FARMER(() -> ModEntityTypes.FARMER.get()),
        LUMBERJACK(() -> ModEntityTypes.LUMBERJACK.get()),
        MINER(() -> ModEntityTypes.MINER.get()),
        FISHERMAN(() -> ModEntityTypes.FISHERMAN.get()),
        ANIMAL_FARMER(() -> ModEntityTypes.ANIMAL_FARMER.get()),
        COOK(() -> ModEntityTypes.COOK.get()),
        /** H16: el nuestro, hecho sobre la base de Workers: hace armamento al lado de un yunque. */
        SMITH(() -> com.ale.warbandcraft.init.ModEntities.SMITH.get()),
        /** H19: atiende la taberna (sin él no llegan reclutas). */
        INNKEEPER(() -> com.ale.warbandcraft.init.ModEntities.INNKEEPER.get()),
        /** H24: atiende el mercado (sin él no se le vende a otros jugadores). */
        MERCHANT(() -> com.ale.warbandcraft.init.ModEntities.MERCHANT.get()),
        /** Ordena los cofres del almacén (uno por almacén). */
        ORGANIZER(() -> com.ale.warbandcraft.init.ModEntities.ORGANIZER.get());

        private final Supplier<EntityType<? extends AbstractWorkerEntity>> type;

        Job(Supplier<EntityType<? extends AbstractWorkerEntity>> type) {
            this.type = type;
        }

        public EntityType<? extends AbstractWorkerEntity> type() {
            return type.get();
        }
    }

    // ------------------------------------------------------------------ datos

    /** {@code employed} = tiene un oficio de vanilla (también se le puede dar uno de Workers: pierde sus tratos). */
    public record VillagerRow(UUID id, Component name, boolean employed) {}

    /** {@code jobIndex} = ordinal de {@link Job} (-1 = otro). */
    public record WorkerRow(UUID id, Component name, Component job, boolean hasArea, int jobIndex, Component status, int statusColor) {}

    public record RouteRow(Component name, int ticks) {}

    /** H22: el terreno de un edificio o torre (clave de {@code TownData.Town.sites} o "tower:pos", esquina con y = piso, giro, lado). */
    public record SiteRow(String key, BlockPos corner, int rot, int size) {}

    /**
     * Todo lo del modo Gestión. {@code stats} = índices de {@link CampStats} (null = el Campamento todavía no está);
     * {@code house} = la caja de la casa del almacén (min y max, null = no hay); {@code storehouse} = el Campamento.
     */
    public record Data(BlockPos center, Component name, boolean castle, @Nullable BlockPos storehouse,
                       @Nullable BlockPos houseMin, @Nullable BlockPos houseMax, @Nullable int[] stats,
                       VillageStock.Stock daily, int soldiers, int capacity, int guards, int invasionTicks, Component invader,
                       List<VillagerRow> free, int employed, List<WorkerRow> workers,
                       @Nullable Panel.VillageDetail upgrades, List<RouteRow> routes, Component sendTo, List<WorkZones.AreaRow> areas,
                       long[] territory, Housing.Data housing, int quarters, int quartersCapacity, int patrolling,
                       int towerPosts, int onTowers,
                       List<Quarters.TroopRow> troops, Popularity.Info growth, List<SiteRow> sites, Component production) {}

    /** Tu aldea/castillo con centro {@code center}, si estás cerca. */
    @Nullable
    public static VillageData.Village access(ServerPlayer player, BlockPos center) {
        VillageData.Village v = VillageData.get(player.serverLevel()).at(center);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return null;
        if (VillageData.horizontalDistSq(player.blockPosition(), center) > (double) REACH * REACH) return null;
        return v;
    }

    /** Tu aldea/castillo más cercano a ≤ {@link VillageCamps#MANAGE_RADIUS} bloques, o null. */
    @Nullable
    public static VillageData.Village near(ServerPlayer player) {
        for (VillageData.Village v : VillageData.get(player.serverLevel()).byDistance(player.blockPosition())) {
            if (VillageData.horizontalDistSq(v.center(), player.blockPosition()) > (double) VillageCamps.MANAGE_RADIUS * VillageCamps.MANAGE_RADIUS) return null;
            if (com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return v;
        }
        return null;
    }

    public static Data data(ServerPlayer player, VillageData.Village v) {
        ServerLevel level = player.serverLevel();
        UUID owner = player.getUUID();
        BlockPos camp = VillageCamps.campOf(level, v);
        int[] stats = camp == null ? null : Panel.stats(level, camp);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);

        BlockPos houseMin = null, houseMax = null;
        BlockPos storehouse = VillageData.get(level).storehouse(v.center());
        if (storehouse != null) {
            BoundingBox house = v.castle() ? null : VillageStorehouse.biggestHouse(level, v.center());
            if (house == null || !house.isInside(storehouse)) house = new BoundingBox(storehouse).inflatedBy(1);
            houseMin = new BlockPos(house.minX(), house.minY(), house.minZ());
            houseMax = new BlockPos(house.maxX(), house.maxY(), house.maxZ());
        }

        List<VillagerRow> free = new ArrayList<>();
        List<VillagerRow> withJob = new ArrayList<>();
        for (Villager vi : villagers(level, v.center())) {
            if (isFree(vi)) free.add(new VillagerRow(vi.getUUID(), vi.getName(), false));
            else withJob.add(new VillagerRow(vi.getUUID(), vi.getName(), true));
        }
        int employed = withJob.size();
        free.addAll(withJob);   // H21: los que tienen oficio van al final de la lista
        List<WorkerRow> workers = new ArrayList<>();
        for (AbstractWorkerEntity w : workers(level, v.center(), owner)) {
            Job job = WorkZones.jobOf(w);
            WorkerSupply.Status st = WorkerSupply.status(level, w);
            workers.add(new WorkerRow(w.getUUID(), w.getName(), w.getType().getDescription(), WorkZones.hasPlace(w), job == null ? -1 : job.ordinal(),
                    st.text(), st.color()));
        }

        List<RouteRow> routes = new ArrayList<>();
        for (Routes.Destination d : Routes.reachable(level, owner, v.center())) routes.add(new RouteRow(d.node().name(), d.ticks()));
        Routes.Destination to = Panel.nearestCamp(level, owner, v.center());
        InvasionData.Invasion inv = InvasionData.get(level).at(v.center());
        Routes.Node node = Routes.node(level, owner, v.center());

        return new Data(v.center(), node == null ? Component.literal(VillageCamps.nameFor(v)) : node.name(), v.castle(), camp,
                houseMin, houseMax, stats, VillageStock.daily(level, v), b == null ? 0 : b.soldiers.size(),
                VillageCamps.capacity(level, v), Panel.guardsAt(level, owner, v.center()),
                inv == null ? -1 : (int) Math.max(0, inv.arriveTick - level.getGameTime()),
                inv == null ? Component.empty() : Invasions.factionName(inv.faction),
                free, employed, workers, Panel.villageDetail(player, v.center()), routes,
                to == null ? Component.empty() : to.node().name(), WorkZones.areas(level, v.center(), owner),
                Territory.get(level).of(v.center()).stream().mapToLong(net.minecraft.world.level.ChunkPos::toLong).toArray(),
                Housing.of(level, v.center()), Quarters.count(level, v), Quarters.capacity(level, v),
                Quarters.loaded(level, v.center()).size(), Quarters.posts(level, v).size(), Quarters.onTowers(level, v.center()),
                Tavern.withRow(level, v, Quarters.rows(level, owner, v)), Popularity.info(level, v), sites(level, v.center()),
                production(level, v.center()));
    }

    /** Pedido de Ale: lo que producen por día todos los trabajadores de la aldea (lo que más dan primero). */
    public static Component production(ServerLevel level, BlockPos center) {
        List<java.util.Map.Entry<net.minecraft.world.item.Item, Double>> list = new ArrayList<>(WorkerOutput.get(level).total(center).entrySet());
        list.removeIf(e -> e.getValue() < 0.5);
        if (list.isEmpty()) return Component.translatable("status.warbandcraft.worker.produces_nothing");
        list.sort(java.util.Map.Entry.<net.minecraft.world.item.Item, Double>comparingByValue().reversed());
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        for (int i = 0; i < list.size() && i < 8; i++) {
            if (i > 0) out.append(", ");
            out.append(Math.round(list.get(i).getValue()) + " ").append(new ItemStack(list.get(i).getKey()).getHoverName());
        }
        return out;
    }

    /** H22: los terrenos de los edificios de la aldea (para dibujarlos en Construir). */
    private static List<SiteRow> sites(ServerLevel level, BlockPos center) {
        List<SiteRow> out = new ArrayList<>();
        com.ale.warbandcraft.upgrade.TownData.Town t = com.ale.warbandcraft.upgrade.TownData.get(level).at(center);
        if (t == null) return out;
        t.sites.forEach((k, p) -> out.add(new SiteRow(k, p, t.rotations.getOrDefault(k, 0), t.siteSize(k))));
        for (BlockPos c : t.towers) out.add(new SiteRow("tower:" + c.asLong(), c.offset(-2, 0, -2), 0, 5));
        return out;
    }

    /** Aldeanos adultos de vanilla cerca del centro. */
    public static List<Villager> villagers(ServerLevel level, BlockPos center) {
        List<Villager> out = level.getEntitiesOfClass(Villager.class, new AABB(center).inflate(RADIUS, 32, RADIUS),
                vi -> vi.isAlive() && !vi.isBaby());
        out.sort(Comparator.comparingDouble(vi -> vi.distanceToSqr(center.getX(), center.getY(), center.getZ())));
        return out;
    }

    /** Libre = sin oficio (o tonto del pueblo): no tiene puesto de trabajo. */
    public static boolean isFree(Villager v) {
        VillagerProfession p = v.getVillagerData().getProfession();
        return p == VillagerProfession.NONE || p == VillagerProfession.NITWIT;
    }

    /** Tus trabajadores de Villager Workers cerca del centro. */
    public static List<AbstractWorkerEntity> workers(ServerLevel level, BlockPos center, UUID owner) {
        List<AbstractWorkerEntity> out = level.getEntitiesOfClass(AbstractWorkerEntity.class, new AABB(center).inflate(RADIUS, 32, RADIUS),
                w -> w.isAlive() && com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), owner)
                        && !w.getPersistentData().contains(com.ale.warbandcraft.camp.CampWorkers.TAG));   // los de un Campamento común, no
        for (WorkerOutput.Rec r : WorkerOutput.get(level).of(center)) {   // los de los puestos de afuera (si están cargados)
            if (r.outpost && level.getEntity(r.worker) instanceof AbstractWorkerEntity w && w.isAlive()
                    && com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), owner) && !out.contains(w)) out.add(w);
        }
        return out;
    }

    // ------------------------------------------------------------------ órdenes

    /**
     * Un aldeano libre pasa a ser un trabajador tuyo con el oficio {@code job} (gratis). La primera vez se crea la zona de
     * almacén alrededor del Campamento de la aldea y se le busca su zona de trabajo ({@link WorkZones#suggest}). Devuelve el mensaje para el jugador.
     */
    public static Component assign(ServerPlayer player, VillageData.Village v, UUID villagerId, Job job) {
        ServerLevel level = player.serverLevel();
        Entity e = level.getEntity(villagerId);
        if (!(e instanceof Villager villager) || !villager.isAlive() || villager.isBaby()
                || VillageData.horizontalDistSq(villager.blockPosition(), v.center()) > (double) RADIUS * RADIUS) {
            return Component.translatable("msg.warbandcraft.manage.no_villager").withStyle(ChatFormatting.RED);
        }
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (job == Job.SMITH) {
            if (camp == null) return Component.translatable("msg.warbandcraft.manage.no_camp").withStyle(ChatFormatting.RED);
            if (smithsOf(level, camp.getBlockPos()) >= Forge.MAX_SMITHS) {
                return Component.translatable("msg.warbandcraft.manage.smiths_full", Forge.MAX_SMITHS).withStyle(ChatFormatting.RED);
            }
        }
        if (job == Job.ORGANIZER) {   // uno por almacén
            if (camp == null) return Component.translatable("msg.warbandcraft.manage.no_camp").withStyle(ChatFormatting.RED);
            if (organizersOf(level, camp.getBlockPos()) >= 1) return Component.translatable("msg.warbandcraft.organizer.full").withStyle(ChatFormatting.RED);
        }
        if (job == Job.MERCHANT) {   // H24: tantos mercaderes como el nivel del mercado
            Component full = com.ale.warbandcraft.market.Market.canHire(level, v);
            if (full != null) return full;
        }
        AbstractWorkerEntity worker = convert(level, villager, job, player.getUUID());
        if (worker == null) return Component.translatable("msg.warbandcraft.manage.failed").withStyle(ChatFormatting.RED);
        if (worker instanceof SmithEntity smith && camp != null) smith.setCamp(camp.getBlockPos());
        if (worker instanceof com.ale.warbandcraft.worker.InnkeeperEntity keeper && camp != null) keeper.setCamp(camp.getBlockPos());
        if (worker instanceof com.ale.warbandcraft.worker.MerchantEntity merchant && camp != null) merchant.setCamp(camp.getBlockPos());
        if (worker instanceof com.ale.warbandcraft.worker.OrganizerEntity org && camp != null) org.setCamp(camp.getBlockPos());

        if (camp != null) WorkerZone.create(level, camp, player.getUUID(), player.getGameProfile().getName());
        // Su zona de trabajo sugerida (huerta, árboles, agua, la casa del almacén, el yunque); si no hay, la marcás vos
        Component zone = WorkZones.suggest(player, v, worker.getUUID());
        boolean placed = WorkZones.hasPlace(worker);
        return Component.translatable(placed ? "msg.warbandcraft.manage.assigned_zone" : "msg.warbandcraft.manage.assigned",
                worker.getType().getDescription()).withStyle(ChatFormatting.GREEN)
                .append(placed ? Component.empty() : Component.literal(" ").append(zone));
    }

    /** Herreros del almacén {@code camp}: los nuestros atados a él + los aldeanos herreros de H10 a su alrededor. */
    public static int smithsOf(ServerLevel level, BlockPos camp) {
        return Forge.smiths(level, camp).size() + level.getEntitiesOfClass(SmithEntity.class, new AABB(camp).inflate(96),
                s -> s.isAlive() && camp.equals(s.getCamp())).size();
    }

    /** Organizadores atados al almacén {@code camp}. */
    public static int organizersOf(ServerLevel level, BlockPos camp) {
        return level.getEntitiesOfClass(com.ale.warbandcraft.worker.OrganizerEntity.class, new AABB(camp).inflate(96),
                o -> o.isAlive() && camp.equals(o.getCamp())).size();
    }

    /** Cambia el aldeano por un trabajador de Workers tuyo, en el mismo lugar. */
    @Nullable
    public static AbstractWorkerEntity convert(ServerLevel level, Villager villager, Job job, UUID owner) {
        AbstractWorkerEntity w = job.type().create(level);
        if (w == null) return null;
        w.moveTo(villager.getX(), villager.getY(), villager.getZ(), villager.getYRot(), villager.getXRot());
        try {
            w.finalizeSpawn(level, level.getCurrentDifficultyAt(villager.blockPosition()), MobSpawnType.CONVERSION, null, null);
        } catch (RuntimeException ignored) {
            // Algunas entidades de Recruits/Workers fallan acá (ver SiegeEngineer): se sigue igual.
        }
        w.setOwnerUUID(Optional.of(owner));
        w.setIsOwned(true);
        w.setFollowState(0);   // Workers trabaja con estado 0 (o 6)
        if (villager.hasCustomName()) w.setCustomName(villager.getCustomName());
        level.addFreshEntityWithPassengers(w);
        // H18: su cama de aldeano queda para él (no se suelta: la toma su zona Hogar de Workers)
        villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                .filter(g -> g.dimension() == level.dimension())
                .ifPresent(g -> WorkerSupply.homeAt(level, owner, w, g.pos()));
        for (var m : List.of(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE, net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT)) {
            villager.releasePoi(m);
        }
        villager.discard();
        RecruitEvents.recruitsPlayerUnitManager.recountRecruits(level.getServer(), owner);
        return w;
    }

    /** La huerta más cercana al centro que todavía no tiene zona de Workers, o null. */
    @Nullable
    public static BoundingBox findFarm(ServerLevel level, BlockPos center) {
        List<BlockPos> farmland = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-FARM_SEARCH, -8, -FARM_SEARCH), center.offset(FARM_SEARCH, 8, FARM_SEARCH))) {
            if (level.isLoaded(p) && level.getBlockState(p).is(Blocks.FARMLAND)) farmland.add(p.immutable());
        }
        farmland.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos start : farmland) {
            if (seen.contains(start)) continue;
            BoundingBox plot = flood(level, start, seen);
            AABB box = new AABB(plot.minX(), plot.minY(), plot.minZ(), plot.maxX() + 1, plot.minY() + 3, plot.maxZ() + 1);
            if (WorkerZone.existing(level, box) == null) return plot;
        }
        return null;
    }

    /** Tierra de cultivo y agua unidas a {@code start}, a la misma altura (hasta {@link #FARM_MAX} de lado). */
    private static BoundingBox flood(ServerLevel level, BlockPos start, Set<BlockPos> seen) {
        int minX = start.getX(), maxX = minX, minZ = start.getZ(), maxZ = minZ;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (seen.contains(n) || Math.abs(n.getX() - start.getX()) > FARM_MAX || Math.abs(n.getZ() - start.getZ()) > FARM_MAX) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(Blocks.FARMLAND) || s.is(Blocks.WATER)) {
                    seen.add(n);
                    queue.add(n);
                }
            }
        }
        return new BoundingBox(minX, start.getY(), minZ, maxX, start.getY(), maxZ);
    }

    /** La semilla de lo que hay plantado en la huerta (trigo si no hay nada). */
    static ItemStack seedOf(ServerLevel level, BoundingBox plot) {
        for (BlockPos p : BlockPos.betweenClosed(plot.minX(), plot.minY() + 1, plot.minZ(), plot.maxX(), plot.minY() + 1, plot.maxZ())) {
            Block b = level.getBlockState(p).getBlock();
            if (b == Blocks.CARROTS) return new ItemStack(Items.CARROT);
            if (b == Blocks.POTATOES) return new ItemStack(Items.POTATO);
            if (b == Blocks.BEETROOTS) return new ItemStack(Items.BEETROOT_SEEDS);
            if (b == Blocks.WHEAT) return new ItemStack(Items.WHEAT_SEEDS);
        }
        return new ItemStack(Items.WHEAT_SEEDS);
    }
}
