package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.WorkZones;
import com.ale.warbandcraft.village.WorkerSupply;
import com.ale.warbandcraft.worker.SmithEntity;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trabajador de un Campamento común (pedido de Ale): se contrata en una taberna (al Tabernero) y va solo a tu Campamento,
 * como un soldado al barracón (si se traba, aparece ahí; si te alejás, llega "en papel" y aparece cuando el Campamento se
 * carga). Ahí espera quieto hasta que en la pestaña Cofre → "Trabajador" le das oficio: Herrero (al lado de un yunque a
 * ≤24 del Campamento) o Granjero (la huerta la elige él). Hasta uno de cada oficio por Campamento ({@link #MAX_PER_CAMP});
 * cada uno necesita una cama libre a ≤24 y come del almacén. Los Campamentos de aldea no: ahí está el modo Gestión.
 */
public final class CampWorkers {
    /** Marca en el aldeano / trabajador: el Campamento (long). */
    public static final String TAG = "warbandcraft:camp_worker";
    public static final String KIND = "camp_worker";
    /** A esta distancia del Campamento ya llegó. */
    public static int ARRIVE = 4;
    /** Sin acercarse {@link #STUCK_PROGRESS} bloque en tantos segundos, aparece en el Campamento. */
    public static int STUCK_SECONDS = 10;
    public static double STUCK_PROGRESS = 1;
    /** Si el dueño está más lejos que esto del que camina (o el Campamento está lejos al contratarlo), llega "en papel". */
    public static int OWNER_RANGE = 96;
    /** Un trabajador más lejos que esto de su Campamento vuelve. */
    public static int LEASH = 48;
    /** Revisiones seguidas sin encontrarlo con el Campamento cargado hasta darlo por muerto. */
    public static int MISSING_CHECKS = 20;
    /** Cada cuánto (en revisiones de 1 s) pasa al almacén lo que juntó. */
    public static int COLLECT_EVERY = 30;

    /** Un Herrero y un Granjero (pedido de Ale): dos lugares por Campamento, uno por oficio. */
    public static final int MAX_PER_CAMP = 2;
    public static final String SMITH = "smith", FARMER = "farmer";

    private CampWorkers() {}

    // ------------------------------------------------------------------ guardado

    public static final class Rec {
        public UUID worker;
        public UUID owner;
        /** En camino sin cargar: aparece en el Campamento cuando se carga. */
        public boolean away;
        @Nullable
        public BlockPos bed;
        /** {@link #SMITH}, {@link #FARMER} o "" (todavía sin oficio). */
        public String job = "";
        int missing;
    }

    public static final class Data extends SavedData {
        private static final String FILE = "warbandcraft_camp_workers";
        final Map<Long, List<Rec>> byCamp = new HashMap<>();

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Data::load, Data::new, FILE);
        }

        /** Los de un Campamento (lista vacía si no tiene). */
        public List<Rec> of(BlockPos camp) {
            return byCamp.getOrDefault(camp.asLong(), List.of());
        }

        /** El primero de un Campamento, o null. */
        @Nullable
        public Rec at(BlockPos camp) {
            List<Rec> l = of(camp);
            return l.isEmpty() ? null : l.get(0);
        }

        /** El que tiene ese oficio, o null. */
        @Nullable
        public Rec withJob(BlockPos camp, String job) {
            for (Rec r : of(camp)) if (job.equals(r.job)) return r;
            return null;
        }

        void add(BlockPos camp, Rec r) {
            byCamp.computeIfAbsent(camp.asLong(), k -> new ArrayList<>()).add(r);
            setDirty();
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            for (Tag t : tag.getList("Workers", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Rec r = new Rec();
                r.worker = c.getUUID("Worker");
                r.owner = c.getUUID("Owner");
                r.away = c.getBoolean("Away");
                r.bed = c.contains("Bed") ? BlockPos.of(c.getLong("Bed")) : null;
                r.job = c.getString("Job");
                d.byCamp.computeIfAbsent(c.getLong("Camp"), k -> new ArrayList<>()).add(r);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            byCamp.forEach((camp, recs) -> recs.forEach(r -> {
                CompoundTag c = new CompoundTag();
                c.putLong("Camp", camp);
                c.putUUID("Worker", r.worker);
                c.putUUID("Owner", r.owner);
                c.putBoolean("Away", r.away);
                if (r.bed != null) c.putLong("Bed", r.bed.asLong());
                c.putString("Job", r.job);
                list.add(c);
            }));
            tag.put("Workers", list);
            return tag;
        }
    }

    // ------------------------------------------------------------------ contratar

    /** ¿Este Campamento puede recibir un trabajador? (común, tuyo, con lugar: uno por oficio). */
    public static boolean canTake(ServerLevel level, UUID player, BlockPos camp) {
        return CampRegistry.get(level).isCampOf(player, camp) && com.ale.warbandcraft.village.VillageCamps.villageOf(level, camp) == null
                && Data.get(level).of(camp).size() < MAX_PER_CAMP;
    }

    /** Lo que se muestra del Campamento al elegirlo: nombre y distancia. */
    public static Component campLabel(ServerLevel level, BlockPos camp, BlockPos from) {
        int d = (int) Math.sqrt(camp.distSqr(from));
        return Component.translatable("gui.warbandcraft.camp_worker.camp", CampRegistry.get(level).displayName(camp), d);
    }

    /** Contratado: aparece en {@code from} y camina al Campamento (o llega "en papel" si está lejos). */
    public static void hire(ServerPlayer player, BlockPos camp, Vec3 from) {
        ServerLevel level = player.serverLevel();
        Rec r = new Rec();
        r.owner = player.getUUID();
        r.worker = UUID.randomUUID();
        boolean near = level.isPositionEntityTicking(camp) && camp.distToCenterSqr(from) <= (double) OWNER_RANGE * OWNER_RANGE;
        if (near) {
            Villager v = spawn(level, from, camp);
            if (v != null) r.worker = v.getUUID();
            else r.away = true;
        } else {
            r.away = true;
        }
        Data.get(level).add(camp, r);
    }

    @Nullable
    private static Villager spawn(ServerLevel level, Vec3 at, BlockPos camp) {
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return null;
        v.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360F, 0);
        v.setVillagerData(v.getVillagerData().setType(VillagerType.byBiome(level.getBiome(BlockPos.containing(at))))
                .setProfession(VillagerProfession.NONE));
        v.setCustomName(Component.translatable("entity.warbandcraft.camp_worker"));
        v.setPersistenceRequired();
        v.getPersistentData().putLong(TAG, camp.asLong());
        level.addFreshEntity(v);
        return v;
    }

    // ------------------------------------------------------------------ cada 1 s

    private record Progress(double best, long since) {}

    private static final Map<UUID, Progress> WALKING = new HashMap<>();
    private static int ticks;

    public static void tick(ServerLevel level) {
        Data d = Data.get(level);
        if (d.byCamp.isEmpty()) return;
        ticks++;
        for (Map.Entry<Long, List<Rec>> e : new ArrayList<>(d.byCamp.entrySet())) {
            for (Rec r : new ArrayList<>(e.getValue())) tickOne(level, d, BlockPos.of(e.getKey()), r);
        }
    }

    private static void tickOne(ServerLevel level, Data d, BlockPos camp, Rec r) {
        {
            boolean campLoaded = level.isPositionEntityTicking(camp);
            CampBlockEntity be = campLoaded && level.getBlockEntity(camp) instanceof CampBlockEntity c ? c : null;
            if (campLoaded && be == null) {   // rompieron el Campamento: queda suelto
                Entity ent = level.getEntity(r.worker);
                if (ent != null) ent.getPersistentData().remove(TAG);
                if (ent instanceof Villager v) v.setNoAi(false);
                forget(level, d, camp, r);
                return;
            }
            if (r.away) {
                if (be == null) return;
                Villager v = spawn(level, Vec3.atBottomCenterOf(spotNear(level, camp)), camp);
                if (v == null) return;
                v.setNoAi(true);
                r.worker = v.getUUID();
                r.away = false;
                d.setDirty();
                return;
            }
            Entity ent = level.getEntity(r.worker);
            if (ent == null || !ent.isAlive()) {
                if (campLoaded && ++r.missing >= MISSING_CHECKS) forget(level, d, camp, r);   // murió
                return;
            }
            r.missing = 0;
            if (ent instanceof Villager v) walkOrWait(level, d, camp, r, v);
            else if (ent instanceof AbstractWorkerEntity w && be != null) serve(level, be, r, w);
        }
    }

    /** El que todavía no tiene oficio: camina al Campamento y ahí espera quieto. */
    private static void walkOrWait(ServerLevel level, Data d, BlockPos camp, Rec r, Villager v) {
        if (v.getVillagerData().getProfession() != VillagerProfession.NONE) {   // que no tome un oficio de vanilla en el camino
            v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.NONE));
            v.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            v.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        }
        double dist = Math.sqrt(v.distanceToSqr(Vec3.atBottomCenterOf(camp)));
        if (dist <= ARRIVE) {
            WALKING.remove(v.getUUID());
            if (!v.isNoAi()) {
                v.getNavigation().stop();
                v.setNoAi(true);   // llegó: queda parado hasta que le des oficio
            }
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(r.owner);
        if (owner == null || owner.level() != level || owner.distanceTo(v) > OWNER_RANGE) {   // te fuiste: llega solo
            WALKING.remove(v.getUUID());
            v.discard();
            r.away = true;
            d.setDirty();
            return;
        }
        v.setNoAi(false);
        long now = level.getGameTime();
        Progress p = WALKING.get(v.getUUID());
        if (p == null || dist < p.best() - STUCK_PROGRESS) {
            WALKING.put(v.getUUID(), new Progress(dist, now));
        } else if (now - p.since() > STUCK_SECONDS * 20L && level.isPositionEntityTicking(camp)) {
            BlockPos spot = spotNear(level, camp);   // trabado: aparece en el Campamento
            v.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            WALKING.remove(v.getUUID());
            return;
        }
        v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(camp, 0.6F, ARRIVE - 1));
    }

    /** Con oficio: equipo y semillas del almacén, comida, lo que junta al almacén, su cama, y que no se vaya lejos. */
    private static void serve(ServerLevel level, CampBlockEntity camp, Rec r, AbstractWorkerEntity w) {
        paper(level, camp, r, w);
        WorkerSupply.supply(camp, w);
        WorkerSupply.feed(camp, w);
        if ((ticks + w.getId()) % COLLECT_EVERY == 0) WorkerSupply.collect(camp, w);
        if (w.homeAreaUUID == null && r.bed != null) WorkerSupply.homeAt(level, r.owner, w, r.bed);   // se perdió la zona Hogar
        if (w.distanceToSqr(Vec3.atCenterOf(camp.getBlockPos())) > (double) LEASH * LEASH && !w.isPassenger() && !w.isSleeping()) {
            BlockPos spot = spotNear(level, camp.getBlockPos());
            w.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            w.getNavigation().stop();
        }
    }

    // ------------------------------------------------------------------ producción sin cargar (como en las aldeas)

    /** Comida por día que come sin cargar (puntos: un pan = 5). Si no alcanza, produce en proporción. */
    public static int FOOD_PER_DAY = 6;

    /** Fracción de lingote que le quedó al Herrero de la última cuenta (en memoria). */
    private static final Map<UUID, Double> SMITH_CARRY = new HashMap<>();

    /**
     * Lo que hizo mientras el Campamento no estaba cargado: al volver a atenderlo, por el tiempo que pasó (máx. 7 días), come
     * del almacén y deja lo de su producción por día ({@link com.ale.warbandcraft.village.WorkerOutput}, lo medido o lo
     * esperado del oficio). El Herrero, en cambio, hace sus vueltas de verdad con el hierro, el carbón y lo marcado.
     */
    private static void paper(ServerLevel level, CampBlockEntity camp, Rec r, AbstractWorkerEntity w) {
        com.ale.warbandcraft.village.WorkerOutput out = com.ale.warbandcraft.village.WorkerOutput.get(level);
        long now = level.getGameTime();
        com.ale.warbandcraft.village.WorkerOutput.Rec rec = out.ensure(w.getUUID(), camp.getBlockPos(), w.blockPosition(), now);
        rec.pos = w.blockPosition();
        rec.estimate.clear();
        rec.estimate.putAll(WorkerSupply.estimate(level, w));
        long gap = out.seen(rec, now);
        if (gap <= 0) return;
        long ticks = Math.min(gap, (long) com.ale.warbandcraft.village.WorkerOutput.MAX_OFFLINE_DAYS * com.ale.warbandcraft.village.WorkerOutput.DAY);
        double days = ticks / (double) com.ale.warbandcraft.village.WorkerOutput.DAY;
        int need = (int) Math.ceil(days * FOOD_PER_DAY);
        double fed = need <= 0 ? 1 : Math.min(1, Barracks.takeFood(camp, need) / (double) need);
        List<net.minecraft.world.item.ItemStack> made = new ArrayList<>();
        if (w instanceof SmithEntity) {
            double jobs = days * fed * SmithEntity.IRON_PER_DAY + SMITH_CARRY.getOrDefault(w.getUUID(), 0D);
            int n = (int) Math.floor(jobs);
            SMITH_CARRY.put(w.getUUID(), jobs - n);
            int before = camp.count(com.ale.warbandcraft.kit.Armament.item());
            for (int i = 0; i < n; i++) {
                net.minecraft.world.item.ItemStack left = com.ale.warbandcraft.worker.SmithWorkGoal.workOnce(camp);
                if (left == null) break;
                if (!left.isEmpty()) made.add(left);
            }
            int got = camp.count(com.ale.warbandcraft.kit.Armament.item()) - before;
            if (got > 0) made.add(new net.minecraft.world.item.ItemStack(com.ale.warbandcraft.kit.Armament.item(), got));
        } else {
            for (net.minecraft.world.item.ItemStack s : out.owed(rec, (long) (ticks * fed))) {
                net.minecraft.world.item.ItemStack back = camp.insert(s.copy());
                if (!back.isEmpty()) CampRegistry.get(level).deliver(level, r.owner, List.of(back), camp.getBlockPos());
                made.add(s);
            }
        }
        if (gap >= WorkerSupply.TELL_GAP_TICKS && !made.isEmpty()) tellPaid(level, camp.getBlockPos(), r.owner, made, fed < 1);
    }

    /** "Mientras no estabas, el trabajador de X dejó: ..." (al dueño, si está conectado). */
    private static void tellPaid(ServerLevel level, BlockPos camp, UUID owner, List<net.minecraft.world.item.ItemStack> made, boolean hungry) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p == null) return;
        Map<net.minecraft.world.item.Item, Integer> sum = new java.util.LinkedHashMap<>();
        for (net.minecraft.world.item.ItemStack s : made) sum.merge(s.getItem(), s.getCount(), Integer::sum);
        net.minecraft.network.chat.MutableComponent items = Component.empty();
        int i = 0;
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : sum.entrySet()) {
            if (i++ >= 6) break;
            if (i > 1) items.append(", ");
            items.append(e.getValue() + " ").append(new net.minecraft.world.item.ItemStack(e.getKey()).getHoverName());
        }
        p.sendSystemMessage(Component.translatable("msg.warbandcraft.camp_worker.offline_output", CampRegistry.get(level).displayName(camp), items)
                .withStyle(ChatFormatting.GOLD));
        if (hungry) p.sendSystemMessage(Component.translatable("msg.warbandcraft.camp_worker.hungry").withStyle(ChatFormatting.YELLOW));
    }

    private static void forget(ServerLevel level, Data d, BlockPos camp, Rec r) {
        com.ale.warbandcraft.village.WorkerOutput.get(level).remove(r.worker);
        SMITH_CARRY.remove(r.worker);
        if (r.bed != null) release(level, r.bed);
        WALKING.remove(r.worker);
        List<Rec> l = d.byCamp.get(camp.asLong());
        if (l != null) {
            l.remove(r);
            if (l.isEmpty()) d.byCamp.remove(camp.asLong());
        }
        d.setDirty();
    }

    private static void release(ServerLevel level, BlockPos bed) {
        try {
            level.getPoiManager().release(bed);
        } catch (RuntimeException ignored) {
            // ya estaba libre o rompieron la cama
        }
    }

    /** Un lugar para pararse al lado del Campamento. */
    private static BlockPos spotNear(ServerLevel level, BlockPos camp) {
        for (int r = 1; r <= 3; r++) {
            for (BlockPos p : BlockPos.betweenClosed(camp.offset(-r, -1, -r), camp.offset(r, 1, r))) {
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return p.immutable();
            }
        }
        return camp.above();
    }

    // ------------------------------------------------------------------ pantalla (pestaña Cofre → Trabajador)

    /**
     * Los del Campamento y los botones que tocan: al que espera, Herrero / Granjero (el oficio que no esté ocupado); a cada
     * uno, Despedir. El id lleva la acción de cada botón en orden ("pos:S,F,d0,d1": S/F = oficio, dN = despedir al N).
     */
    public static void open(ServerPlayer player, CampBlockEntity camp) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = camp.getBlockPos();
        Data d = Data.get(level);
        List<Rec> recs = d.of(pos);
        List<Component> lines = new ArrayList<>();
        List<Component> options = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (recs.isEmpty()) lines.add(Component.translatable("gui.warbandcraft.camp_worker.none"));
        boolean waiting = false;
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            Entity ent = r.away ? null : level.getEntity(r.worker);
            if (ent instanceof AbstractWorkerEntity w) {
                WorkerSupply.Status st = WorkerSupply.status(level, w);
                lines.add(Component.translatable("gui.warbandcraft.camp_worker.job", w.getType().getDescription())
                        .append(" · ").append(st.text().copy().withStyle(x -> x.withColor(st.color() & 0xFFFFFF))));
                options.add(Component.translatable("gui.warbandcraft.camp_worker.dismiss_job", w.getType().getDescription()));
            } else {
                boolean here = here(ent, pos);
                waiting |= here;
                lines.add(Component.translatable(here ? "gui.warbandcraft.camp_worker.waiting" : "gui.warbandcraft.camp_worker.coming"));
                options.add(Component.translatable("gui.warbandcraft.camp_worker.dismiss_new"));
            }
            actions.add("d" + i);
        }
        if (recs.size() < MAX_PER_CAMP) lines.add(Component.translatable("gui.warbandcraft.camp_worker.room"));
        if (waiting) {
            lines.add(Component.translatable(freeBed(level, pos) != null ? "gui.warbandcraft.camp_worker.bed_ok" : "gui.warbandcraft.camp_worker.bed_no"));
            List<Component> jobs = new ArrayList<>();
            List<String> jobActions = new ArrayList<>();
            if (d.withJob(pos, SMITH) == null) {
                lines.add(Component.translatable(anvil(level, pos) != null ? "gui.warbandcraft.camp_worker.anvil_ok" : "gui.warbandcraft.camp_worker.anvil_no"));
                jobs.add(Component.translatable("gui.warbandcraft.camp_worker.smith"));
                jobActions.add("S");
            }
            if (d.withJob(pos, FARMER) == null) {
                jobs.add(Component.translatable("gui.warbandcraft.camp_worker.farmer"));
                jobActions.add("F");
            }
            options.addAll(0, jobs);
            actions.addAll(0, jobActions);
        }
        options.add(Component.translatable("gui.warbandcraft.camp_worker.close"));
        BattleChoices.send(player, KIND, pos.asLong() + ":" + String.join(",", actions), Component.translatable("gui.warbandcraft.camp_worker.title",
                CampRegistry.get(level).displayName(pos)), lines, options, true);
    }

    private static boolean here(@Nullable Entity ent, BlockPos camp) {
        return ent instanceof Villager v && v.distanceToSqr(Vec3.atBottomCenterOf(camp)) <= (double) (ARRIVE + 2) * (ARRIVE + 2);
    }

    public static void answer(ServerPlayer player, String id, int option) {
        int colon = id.indexOf(':');
        if (colon < 0 || option < 0) return;
        String[] actions = id.substring(colon + 1).split(",");
        if (option >= actions.length || actions[option].isEmpty()) return;   // Cerrar
        BlockPos pos;
        try {
            pos = BlockPos.of(Long.parseLong(id.substring(0, colon)));
        } catch (NumberFormatException e) {
            return;
        }
        CampBlockEntity camp = CampTabs.access(player, pos, true);
        if (camp == null) return;
        String a = actions[option];
        Component msg = null;
        if (a.equals("S")) msg = assign(player, camp, VillageManagement.Job.SMITH);
        else if (a.equals("F")) msg = assign(player, camp, VillageManagement.Job.FARMER);
        else if (a.startsWith("d")) {
            List<Rec> recs = Data.get(player.serverLevel()).of(pos);
            try {
                int i = Integer.parseInt(a.substring(1));
                if (i < recs.size()) msg = dismiss(player, camp, recs.get(i));
            } catch (NumberFormatException ignored) {
                // botón viejo
            }
        }
        if (msg != null) player.sendSystemMessage(msg);
    }

    /** Una cama libre a ≤24 del Campamento (sin reservarla). */
    @Nullable
    public static BlockPos freeBed(ServerLevel level, BlockPos camp) {
        return level.getPoiManager().getInRange(h -> h.is(PoiTypes.HOME), camp, CampBlockEntity.WORK_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                .map(p -> p.getPos()).filter(p -> CampBlockEntity.covers(level, camp, p)).findFirst().orElse(null);
    }

    /** Un yunque a ≤24 del Campamento (el más cercano). */
    @Nullable
    public static BlockPos anvil(ServerLevel level, BlockPos camp) {
        int r = CampBlockEntity.WORK_RADIUS;
        BlockPos best = null;
        for (BlockPos p : BlockPos.betweenClosed(camp.offset(-r, -6, -r), camp.offset(r, 6, r))) {
            if (level.isLoaded(p) && level.getBlockState(p).is(BlockTags.ANVIL) && CampBlockEntity.covers(level, camp, p)
                    && (best == null || p.distSqr(camp) < best.distSqr(camp))) best = p.immutable();
        }
        return best;
    }

    /** Le da oficio al que espera en el Campamento. */
    public static Component assign(ServerPlayer player, CampBlockEntity camp, VillageManagement.Job job) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = camp.getBlockPos();
        Data d = Data.get(level);
        String jobKey = job == VillageManagement.Job.SMITH ? SMITH : FARMER;
        if (d.withJob(pos, jobKey) != null) return Component.translatable("msg.warbandcraft.camp_worker.job_taken").withStyle(ChatFormatting.RED);
        Rec r = null;
        Villager v = null;
        for (Rec x : d.of(pos)) {
            if (x.away || !x.job.isEmpty()) continue;
            Entity e = level.getEntity(x.worker);
            if (here(e, pos)) {
                r = x;
                v = (Villager) e;
                break;
            }
        }
        if (r == null) return Component.translatable("msg.warbandcraft.camp_worker.not_here").withStyle(ChatFormatting.RED);
        if (freeBed(level, pos) == null) return Component.translatable("msg.warbandcraft.camp_worker.no_bed", CampBlockEntity.WORK_RADIUS).withStyle(ChatFormatting.RED);
        BlockPos stand = null;
        BoundingBox farm = null;
        if (job == VillageManagement.Job.SMITH) {
            BlockPos anvil = anvil(level, pos);
            if (anvil == null) return Component.translatable("msg.warbandcraft.camp_worker.no_anvil", CampBlockEntity.WORK_RADIUS).withStyle(ChatFormatting.RED);
            stand = WorkZones.standNextTo(level, anvil);
            if (stand == null) return Component.translatable("msg.warbandcraft.camp_worker.anvil_blocked").withStyle(ChatFormatting.RED);
        } else {
            farm = WorkZones.campFarmSite(level, pos);
            if (farm == null) return Component.translatable("msg.warbandcraft.camp_worker.no_farm").withStyle(ChatFormatting.RED);
            Component miss = com.ale.warbandcraft.village.ZoneCosts.missing(camp, WorkZones.campFarmNeeds());
            if (miss != null) return miss;
        }
        Optional<BlockPos> bed = level.getPoiManager().take(h -> h.is(PoiTypes.HOME), (h, p) -> CampBlockEntity.covers(level, pos, p),
                pos, CampBlockEntity.WORK_RADIUS);
        if (bed.isEmpty()) return Component.translatable("msg.warbandcraft.camp_worker.no_bed", CampBlockEntity.WORK_RADIUS).withStyle(ChatFormatting.RED);
        v.setNoAi(false);
        AbstractWorkerEntity w = VillageManagement.convert(level, v, job, r.owner);
        if (w == null) {
            release(level, bed.get());
            v.setNoAi(true);
            return Component.translatable("msg.warbandcraft.manage.failed").withStyle(ChatFormatting.RED);
        }
        w.getPersistentData().putLong(TAG, pos.asLong());
        r.worker = w.getUUID();
        r.job = jobKey;
        r.bed = bed.get();
        if (!WorkerSupply.homeAt(level, r.owner, w, bed.get())) {
            release(level, bed.get());
            r.bed = null;
        }
        d.setDirty();
        WorkerZone.create(level, camp, player.getUUID(), player.getGameProfile().getName());   // lo que junte, al almacén
        if (w instanceof SmithEntity smith) {
            smith.setCamp(pos);
            smith.setWorkplace(stand);
            smith.setManualWorkplace(true);
            return Component.translatable("msg.warbandcraft.camp_worker.smith_ok").withStyle(ChatFormatting.GREEN);
        }
        Component zone = WorkZones.campFarm(player, camp, w, farm);
        return Component.translatable("msg.warbandcraft.camp_worker.farmer_ok").withStyle(ChatFormatting.GREEN).append(" ").append(zone);
    }

    /** Se va: libera la cama y su huerta. */
    public static Component dismiss(ServerPlayer player, CampBlockEntity camp, Rec r) {
        ServerLevel level = player.serverLevel();
        Data d = Data.get(level);
        if (!d.of(camp.getBlockPos()).contains(r)) return null;
        Entity ent = r.away ? null : level.getEntity(r.worker);
        if (ent instanceof AbstractWorkerEntity w) {
            var area = WorkZones.areaFor(level, w);
            if (area != null) area.discard();
            WorkerSupply.leaveHome(level, w);
        }
        if (ent != null) ent.discard();
        forget(level, d, camp.getBlockPos(), r);
        return Component.translatable("msg.warbandcraft.camp_worker.dismissed").withStyle(ChatFormatting.YELLOW);
    }

    public static void clear() {
        WALKING.clear();
        ticks = 0;
    }
}
