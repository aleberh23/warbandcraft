package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.worker.SmithEntity;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.MinerEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import com.talhanation.workers.entities.workarea.AnimalPenArea;
import com.talhanation.workers.entities.workarea.CropArea;
import com.talhanation.workers.entities.workarea.HomeArea;
import com.talhanation.workers.entities.workarea.LumberArea;
import com.talhanation.workers.entities.workarea.MiningArea;
import com.talhanation.workers.init.ModEntityTypes;
import com.talhanation.workers.world.NeededItem;
import com.ale.warbandcraft.camp.WorkerZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * H18: los trabajadores de tus aldeas, automáticos. Villager Workers solo trabaja si tiene todo lo que pide (el minero:
 * pico, pala, antorchas y relleno), va a buscarlo caminando a una zona de almacén y deja lo que junta recién con 128
 * cosas. Acá, cada {@link #EVERY_TICKS}: se le pasa lo que le falta directo del almacén de la aldea a la mochila, se
 * le da de comer, lo que juntó pasa al almacén cada {@link #COLLECT_EVERY_TICKS}, se le busca una cama (zona Hogar de
 * Workers sobre una cama libre de la aldea) y el minero saca cada tanto algo de su veta. Lo que no se pudo queda
 * anotado para el cartelito del modo Gestión ({@link #status}).
 */
public final class WorkerSupply {
    public static int EVERY_TICKS = 100;
    /** Cada cuánto pasa al almacén lo que juntó; se queda con lo de su oficio. */
    public static int COLLECT_EVERY_TICKS = 20 * 30;
    /** El minero: cada cuánto saca algo de la veta, cuántas veces baja la mina y cuánto. */
    public static int MINE_EVERY_TICKS = 20 * 60, MINE_LOWERINGS = 4;
    /** Un aviso ("falta", "almacén lleno") se muestra mientras sea más nuevo que esto. */
    public static int NOTE_TICKS = 20 * 30;
    /** Puntos de comida que se le dan si tiene hambre (un pan = 5). */
    public static int MEAL_POINTS = 6;
    /** Comida que se puede quedar en la mochila. */
    public static int KEEP_FOOD = 4;
    /** Atenciones seguidas sin encontrarlo (con su lugar cargado) hasta darlo por muerto. */
    public static int MISSING_CHECKS = 6;
    /** Un puesto de afuera: más lejos que esto de su zona, vuelve a su cofre. */
    public static int OUTPOST_LEASH = 48;
    /** Se avisa lo producido sin cargar si fue por más que esto (2 min). */
    public static int TELL_GAP_TICKS = 2400;
    /** Lo esperado por día de cada oficio hasta medir lo de verdad: troncos, pan, zanahorias/papas, carne, piedra, pescado. */
    public static int EST_LOGS = 32, EST_BREAD = 16, EST_ROOTS = 40, EST_MEAT = 8, EST_STONE = 48, EST_FISH = 16;
    /** El minero, después de la escalera: pasillos de este largo y cuántos. */
    public static int CORRIDOR_LENGTH = 8, CORRIDORS = 16;
    /** Cada cuánto se mira si la escalera o el pasillo ya están. */
    public static int ADVANCE_EVERY_TICKS = 200;

    private static final String TAG_LOWERED = "warbandcraft:lowered", TAG_BED = "warbandcraft:bed";

    private WorkerSupply() {}

    // ------------------------------------------------------------------ el equipo de cada oficio

    /**
     * Algo que el trabajador tiene que tener siempre: qué sirve, cuántos y qué se muestra si falta. {@code free} = no sale
     * del almacén: le aparece solo (pedido de Ale: "lo único que tolero que me pidan es un hacha"; el pico del minero igual).
     */
    public record Kit(Predicate<ItemStack> match, int count, Item shown, boolean free) {
        Kit(Predicate<ItemStack> match, int count, Item shown) {
            this(match, count, shown, false);
        }
    }

    public static List<Kit> kit(AbstractWorkerEntity w) {
        VillageManagement.Job job = WorkZones.jobOf(w);
        if (job == null) return List.of();
        return switch (job) {
            case MINER -> {
                Item fill = w.getCurrentWorkArea() instanceof MiningArea m && !m.getFillItem().isEmpty() ? m.getFillItem().getItem() : Items.COBBLESTONE;
                yield List.of(new Kit(s -> s.is(ItemTags.PICKAXES), 1, Items.STONE_PICKAXE), new Kit(s -> s.is(ItemTags.SHOVELS), 1, Items.STONE_SHOVEL, true),
                        new Kit(s -> s.is(Items.TORCH), 16, Items.TORCH, true), new Kit(s -> s.is(fill), 16, fill, true));
            }
            case FARMER -> {
                Item seed = w.getCurrentWorkArea() instanceof CropArea c && !c.getSeedStack().isEmpty() ? c.getSeedStack().getItem() : Items.WHEAT_SEEDS;
                yield List.of(new Kit(s -> s.is(ItemTags.HOES), 1, Items.STONE_HOE, true), new Kit(s -> s.is(seed), 32, seed, true));
            }
            case LUMBERJACK -> {
                Item sapling = w.getCurrentWorkArea() instanceof LumberArea l && !l.getSaplingStack().isEmpty() ? l.getSaplingStack().getItem() : Items.OAK_SAPLING;
                // pedido de Ale: los retoños "que los saquen de los árboles que talan" (le aparecen solos)
                yield List.of(new Kit(s -> s.is(ItemTags.AXES), 1, Items.STONE_AXE), new Kit(s -> s.is(ItemTags.SAPLINGS), 8, sapling, true));
            }
            case FISHERMAN -> List.of(new Kit(s -> s.is(Items.FISHING_ROD), 1, Items.FISHING_ROD));
            case ANIMAL_FARMER -> List.of(new Kit(s -> s.is(ItemTags.AXES), 1, Items.STONE_AXE));
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ lo anotado para el cartelito

    /** Lo último que salió mal con cada trabajador (en memoria). */
    private record Note(Kind kind, Component what, long tick) {}

    private enum Kind { MISSING, FULL, HUNGRY }

    private static final Map<UUID, Note> NOTES = new HashMap<>();
    private static final Map<UUID, Long> LAST_COLLECT = new HashMap<>(), LAST_MINE = new HashMap<>(), LAST_ADVANCE = new HashMap<>();

    private static void note(AbstractWorkerEntity w, Kind kind, Component what) {
        NOTES.put(w.getUUID(), new Note(kind, what, w.level().getGameTime()));
    }

    @Nullable
    private static Note noteOf(AbstractWorkerEntity w, Kind kind) {
        Note n = NOTES.get(w.getUUID());
        return n != null && n.kind == kind && w.level().getGameTime() - n.tick <= NOTE_TICKS ? n : null;
    }

    // ------------------------------------------------------------------ cada 5 s

    /**
     * Todas tus aldeas cargadas: sus trabajadores (también los de los puestos de afuera que estén cargados). Los que no se
     * pudieron atender (sin cargar) producen "en papel": lo de su producción por día por el tiempo que pasó, al almacén.
     */
    public static void tick(ServerLevel level) {
        WorkerOutput out = WorkerOutput.get(level);
        long now = level.getGameTime();
        out.freeze(level, now);   // pedido de Ale: sin nadie de la facción conectado no producen (el reloj de cada uno queda quieto)
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (!v.isConquered() || !level.isPositionEntityTicking(v.center())) continue;
            if (!com.ale.warbandcraft.faction.OnlineDays.active(level, v.owner())) continue;
            CampBlockEntity camp = VillageStorehouse.camp(level, v);
            java.util.Set<UUID> served = new java.util.HashSet<>();
            List<ItemStack> paid = new ArrayList<>();
            for (AbstractWorkerEntity w : VillageManagement.workers(level, v.center(), v.owner())) {
                serve(level, v, camp, w, paid);
                served.add(w.getUUID());
            }
            releaseEmptyHomes(level, v);
            if (camp != null) {
                for (WorkerOutput.Rec r : out.of(v.center())) {
                    if (served.contains(r.worker)) continue;
                    if (level.getEntity(r.worker) == null && level.isPositionEntityTicking(r.pos)) {
                        if (out.missing(r, MISSING_CHECKS)) continue;   // murió o lo sacaron
                    } else {
                        out.found(r);
                    }
                    long gap = now - r.lastTick;
                    if (gap <= WorkerOutput.GAP_TICKS) continue;
                    r.lastTick = now;
                    List<ItemStack> got = pay(level, v, camp, out.owed(r, gap, level.getDayTime() / WorkerOutput.DAY));
                    if (gap >= TELL_GAP_TICKS) paid.addAll(got);
                }
            }
            if (!paid.isEmpty()) tellPaid(level, v, paid);
        }
        NOTES.keySet().removeIf(id -> level.getEntity(id) == null && level.getGameTime() - NOTES.get(id).tick > NOTE_TICKS);
    }

    /** Un trabajador: equipo, comida, juntar, cama y la veta del minero. */
    public static void serve(ServerLevel level, VillageData.Village v, @Nullable CampBlockEntity camp, AbstractWorkerEntity w) {
        serve(level, v, camp, w, new ArrayList<>());
    }

    /** Ídem; lo que se paga por el tiempo sin cargar va a {@code paid} (para avisar). */
    static void serve(ServerLevel level, VillageData.Village v, @Nullable CampBlockEntity camp, AbstractWorkerEntity w, List<ItemStack> paid) {
        if (camp == null) return;
        long now = level.getGameTime();
        WorkerOutput out = WorkerOutput.get(level);
        WorkerOutput.Rec rec = out.ensure(w.getUUID(), v.center(), w.blockPosition(), now);
        rec.pos = w.blockPosition();
        rec.village = v.center();
        out.found(rec);
        rec.estimate.clear();
        rec.estimate.putAll(estimate(level, w));
        VillageManagement.Job job = WorkZones.jobOf(w);
        rec.job = job == null ? "" : job.name();
        long day = level.getDayTime() / WorkerOutput.DAY;
        long gap = out.seen(rec, now);
        if (gap > 0) {
            List<ItemStack> got = pay(level, v, camp, out.owed(rec, gap, day));
            if (gap >= TELL_GAP_TICKS) paid.addAll(got);
        }
        supply(camp, w);
        rec.working = working(level, w);
        pay(level, v, camp, out.floorDue(rec, day));   // lo esperado de su oficio es el piso, también cargado
        feed(camp, w);
        Long last = LAST_COLLECT.get(w.getUUID());
        if (last == null || now - last >= COLLECT_EVERY_TICKS) {
            LAST_COLLECT.put(w.getUUID(), now);
            collect(camp, w);
        }
        if (rec.outpost) outpost(level, camp, w, rec);
        else if (w.homeAreaUUID == null) home(level, v, w);
        if (w instanceof MinerEntity miner) mine(level, v, camp, miner, now);
        if (w instanceof com.talhanation.workers.entities.FarmerEntity farmer) water(level, farmer);
    }

    /** Cada cuántos bloques va un agua en la huerta (la tierra arada se moja a 4 de un agua: 9 cubre todo). */
    public static int WATER_SPACING = 9;

    /**
     * Pedido de Ale ("nunca pone agua, ara infinitamente"): Workers solo pone agua con un balde de agua en la mochila y en
     * un punto fijo pensado para 9×9; sin agua la tierra arada se seca y vuelve a tierra, y la ara de nuevo para siempre.
     * Ponemos nosotros el agua (gratis): en ese punto y en una grilla cada {@link #WATER_SPACING} que cubre la huerta
     * entera, salvo donde ya hay agua cerca. Solo donde el agua no se puede escapar (piso y costados firmes).
     */
    public static void water(ServerLevel level, com.talhanation.workers.entities.FarmerEntity farmer) {
        CropArea area = farmer.currentCropArea;
        if (area == null || !area.isAlive()) return;
        WorkZones.fixCropLevel(level, area);   // la huerta metida un bloque en el piso
        if (area.getArea() == null || area.fieldType == CropArea.FieldType.RICE) return;
        AABB box = area.getArea();
        BlockPos center = area.getWaterPosCenter();
        int y = center.getY();
        int x0 = (int) Math.floor(box.minX), z0 = (int) Math.floor(box.minZ);
        int x1 = (int) Math.ceil(box.maxX) - 1, z1 = (int) Math.ceil(box.maxZ) - 1;
        if (x1 < x0 || z1 < z0) return;
        List<BlockPos> spots = new ArrayList<>();
        if (center.getX() >= x0 && center.getX() <= x1 && center.getZ() >= z0 && center.getZ() <= z1) spots.add(center);
        int half = WATER_SPACING / 2;
        for (int x = x0 + half; x <= x1 + half; x += WATER_SPACING) {
            for (int z = z0 + half; z <= z1 + half; z += WATER_SPACING) {
                spots.add(new BlockPos(Math.min(x, x1), y, Math.min(z, z1)));
            }
        }
        boolean placed = false;
        for (BlockPos p : spots) {
            if (waterNear(level, p, half)) continue;
            if (placeWater(level, p)) placed = true;
        }
        if (placed || level.getFluidState(center).is(net.minecraft.tags.FluidTags.WATER)) {
            // ya no hace falta el balde que Workers pidió
            farmer.neededItems.removeIf(n -> n.matcher.test(new ItemStack(Items.WATER_BUCKET)));
        }
    }

    /** ¿Hay agua quieta a la altura {@code p} dentro del cuadrado de lado 2×{@code r}+1? */
    private static boolean waterNear(ServerLevel level, BlockPos p, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (level.getFluidState(p.offset(dx, 0, dz)).is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** Un agua en {@code p} si es tierra (o ya cavado) con piso y costados firmes; lo plantado encima se saca. */
    private static boolean placeWater(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        BlockState s = level.getBlockState(p);
        boolean earth = s.isAir() || s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
        if (!earth || level.getBlockEntity(p) != null) return false;
        if (!solid(level, p.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (!solid(level, p.relative(d))) return false;
        BlockState up = level.getBlockState(p.above());
        if (!up.isAir() && !up.canBeReplaced() && !(up.getBlock() instanceof net.minecraft.world.level.block.BushBlock)) return false;
        if (!up.isAir()) level.destroyBlock(p.above(), true);
        level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        return true;
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty() || s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    /** Lo que le falta (su equipo y lo que pidió Workers), del almacén a su mochila. Lo que no hay queda anotado. */
    public static void supply(CampBlockEntity camp, AbstractWorkerEntity w) {
        List<Kit> kit = kit(w);
        freeSupply(w, kit);
        MutableComponent missing = null;
        for (Kit k : kit) {
            if (k.free) continue;
            int lack = k.count - w.countMatchingItems(k.match);
            if (lack <= 0) continue;
            int got = give(camp, w, k.match, lack);
            if (got == 0 && w.countMatchingItems(k.match) == 0) missing = add(missing, lack, k.shown);   // sin nada de eso no puede
        }
        for (NeededItem n : new ArrayList<>(w.neededItems)) {
            if (!n.required) continue;
            int got = give(camp, w, n.matcher, n.count);
            if (got < n.count) {
                Object key = n.getMatchKey();
                missing = add(missing, n.count - got, key instanceof Item i ? i : null);
            }
        }
        if (missing != null) note(w, Kind.MISSING, missing);
        else if (noteOf(w, Kind.MISSING) != null) NOTES.remove(w.getUUID());
    }

    /**
     * Lo del equipo que no sale del almacén ({@link Kit#free}): las semillas y la azada del granjero (pedido de Ale: "que se
     * encargue él"), la pala, antorchas y relleno del minero, los retoños del leñador. Le aparecen en la mochila (también lo
     * que pidió Workers de eso) y ya no lo va a buscar.
     */
    private static void freeSupply(AbstractWorkerEntity w, List<Kit> kit) {
        List<ItemStack> samples = new ArrayList<>();
        for (Kit k : kit) {
            if (!k.free) continue;
            ItemStack sample = new ItemStack(k.shown);
            samples.add(sample);
            int want = k.count;
            for (NeededItem n : w.neededItems) if (n.matcher.test(sample)) want = Math.max(want, n.count);
            int lack = want - w.countMatchingItems(k.match);
            while (lack > 0) {
                int c = Math.min(lack, sample.getMaxStackSize());
                ItemStack back = w.addItem(new ItemStack(k.shown, c));
                if (!back.isEmpty()) break;   // mochila llena
                lack -= c;
            }
        }
        if (WorkZones.jobOf(w) == VillageManagement.Job.FARMER) samples.add(new ItemStack(Items.WATER_BUCKET));   // el agua la ponemos nosotros
        if (samples.isEmpty()) return;
        w.neededItems.removeIf(n -> samples.stream().anyMatch(n.matcher::test));
    }

    /** ¿Tiene dónde trabajar y su herramienta? (sin eso, no cuenta lo esperado de su oficio). */
    public static boolean working(ServerLevel level, AbstractWorkerEntity w) {
        if (WorkZones.areaFor(level, w) == null) return false;
        for (Kit k : kit(w)) if (!k.free && w.countMatchingItems(k.match) == 0) return false;
        return true;
    }

    private static MutableComponent add(@Nullable MutableComponent list, int count, @Nullable Item item) {
        Component part = Component.literal(count + " ").append(item == null ? Component.translatable("status.warbandcraft.worker.something")
                : new ItemStack(item).getHoverName());
        return list == null ? Component.empty().append(part) : list.append(", ").append(part);
    }

    /** Saca del almacén hasta {@code count} de lo que cumple {@code match} y se lo da. Devuelve cuántos le dio. */
    private static int give(CampBlockEntity camp, AbstractWorkerEntity w, Predicate<ItemStack> match, int count) {
        int left = count;
        for (Container c : camp.storages()) {
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !match.test(s)) continue;
                ItemStack moved = s.split(Math.min(left, s.getCount()));
                ItemStack back = w.addItem(moved.copy());
                int given = moved.getCount() - back.getCount();
                s.grow(back.getCount());   // lo que no entró en la mochila vuelve
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                if (given > 0) NeededItem.applyToNeededItems(moved.copyWithCount(given), w.neededItems);   // ya no lo va a buscar
                left -= given;
                if (!back.isEmpty()) return count - left;   // mochila llena
            }
            if (left <= 0) break;
        }
        return count - left;
    }

    /** Con hambre: come del almacén (lo más barato). */
    public static void feed(CampBlockEntity camp, AbstractWorkerEntity w) {
        if (!w.needsToGetFood()) return;
        int got = Barracks.takeFood(camp, MEAL_POINTS);
        if (got > 0) w.setHunger(Math.min(100F, w.getHunger() + got * 10F));
        else note(w, Kind.HUNGRY, Component.empty());
    }

    /**
     * Lo que juntó (salvo su equipo y un poco de comida) pasa al almacén y queda anotado como producción. Si no entra,
     * queda anotado. El trigo del granjero se hace pan (3 → 1): la aldea no come trigo; sus semillas de más se tiran.
     */
    public static void collect(CampBlockEntity camp, AbstractWorkerEntity w) {
        boolean farmer = WorkZones.jobOf(w) == VillageManagement.Job.FARMER;
        if (farmer) bake(w);
        List<Kit> kit = kit(w);
        int[] kept = new int[kit.size()];
        int food = 0;
        boolean full = false;
        Container inv = w.getInventory();
        for (int i = 6; i < inv.getContainerSize(); i++) {   // 0-5: lo que tiene puesto
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            int keep = 0;
            for (int k = 0; k < kit.size(); k++) {
                if (!kit.get(k).match.test(s)) continue;
                keep = Math.min(s.getCount(), kit.get(k).count - kept[k]);
                kept[k] += keep;
                break;
            }
            if (keep == 0 && s.getItem() instanceof TieredItem) keep = s.getCount();   // una herramienta de más: que la tenga
            if (keep == 0 && Barracks.food(s) != null && food < KEEP_FOOD) {
                keep = Math.min(s.getCount(), KEEP_FOOD - food);
                food += keep;
            }
            if (keep >= s.getCount()) continue;
            if (farmer && WorkerOutput.junk(WorkerOutput.key(s.getItem()))) {   // pedido de Ale: las semillas de más no van al almacén
                s.setCount(keep);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                continue;
            }
            ItemStack out = s.split(s.getCount() - keep);
            int moving = out.getCount();
            Item item = out.getItem();
            ItemStack back = camp.insert(out);
            made(w, new ItemStack(item, moving - back.getCount()));
            if (!back.isEmpty()) {
                s.grow(back.getCount());
                full = true;
            }
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
        inv.setChanged();
        if (full) {
            note(w, Kind.FULL, Component.empty());
        } else {
            w.farmedItems = 0;
            w.forcedDeposit = false;
        }
    }

    /** Anota {@code s} como producción del trabajador. */
    private static void made(AbstractWorkerEntity w, ItemStack s) {
        if (s.isEmpty() || !(w.level() instanceof ServerLevel level)) return;
        WorkerOutput out = WorkerOutput.get(level);
        WorkerOutput.Rec r = out.of(w.getUUID());
        if (r != null) out.made(r, s, level.getDayTime() / WorkerOutput.DAY);
    }

    /** El trigo de la mochila se hace pan (de a 3). */
    static void bake(AbstractWorkerEntity w) {
        Container inv = w.getInventory();
        int wheat = 0;
        for (int i = 6; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.WHEAT)) wheat += inv.getItem(i).getCount();
        int bread = wheat / 3, remove = bread * 3;
        if (bread == 0) return;
        for (int i = 6; i < inv.getContainerSize() && remove > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.WHEAT)) continue;
            int n = Math.min(remove, s.getCount());
            s.shrink(n);
            remove -= n;
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
        ItemStack left = w.addItem(new ItemStack(Items.BREAD, bread));
        if (!left.isEmpty()) w.spawnAtLocation(left);
        inv.setChanged();
    }

    /** Lo producido sin cargar: al almacén de la aldea (lo que no entra, a otro Campamento tuyo o pendiente). */
    static List<ItemStack> pay(ServerLevel level, VillageData.Village v, CampBlockEntity camp, List<ItemStack> stacks) {
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack s : stacks) {
            ItemStack back = camp.insert(s.copy());
            if (!back.isEmpty()) rest.add(back);
        }
        if (!rest.isEmpty() && v.owner() != null) CampRegistry.get(level).deliver(level, v.owner(), rest, camp.getBlockPos());
        return stacks;
    }

    /** "Mientras no estabas, los trabajadores de X dejaron: ..." (al dueño, si está conectado). */
    private static void tellPaid(ServerLevel level, VillageData.Village v, List<ItemStack> paid) {
        if (v.owner() == null) return;
        net.minecraft.server.level.ServerPlayer p = level.getServer().getPlayerList().getPlayer(v.owner());
        if (p == null) return;
        Map<Item, Integer> sum = new HashMap<>();
        for (ItemStack s : paid) sum.merge(s.getItem(), s.getCount(), Integer::sum);
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(sum.entrySet());
        list.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        MutableComponent items = Component.empty();
        for (int i = 0; i < list.size() && i < 6; i++) {
            if (i > 0) items.append(", ");
            items.append(list.get(i).getValue() + " ").append(new ItemStack(list.get(i).getKey()).getHoverName());
        }
        p.sendSystemMessage(Component.translatable("msg.warbandcraft.manage.offline_output", VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v))), items)
                .withStyle(net.minecraft.ChatFormatting.GOLD));
    }

    /** Lo que se espera que dé por día cada oficio (hasta que se mida lo de verdad). Ítem → cantidad. */
    public static Map<String, Double> estimate(ServerLevel level, AbstractWorkerEntity w) {
        Map<String, Double> out = new HashMap<>();
        VillageManagement.Job job = WorkZones.jobOf(w);
        if (job == null) return out;
        AbstractWorkAreaEntity area = WorkZones.areaFor(level, w);
        switch (job) {
            case LUMBERJACK -> {
                Item sapling = area instanceof LumberArea l && !l.getSaplingStack().isEmpty() ? l.getSaplingStack().getItem() : Items.OAK_SAPLING;
                out.put(WorkerOutput.key(logOf(sapling)), (double) EST_LOGS);
            }
            case FARMER -> {
                Item seed = area instanceof CropArea c && !c.getSeedStack().isEmpty() ? c.getSeedStack().getItem() : Items.WHEAT_SEEDS;
                if (seed == Items.CARROT || seed == Items.POTATO) out.put(WorkerOutput.key(seed), (double) EST_ROOTS);
                else if (seed == Items.BEETROOT_SEEDS) out.put(WorkerOutput.key(Items.BEETROOT), (double) EST_ROOTS);
                else out.put(WorkerOutput.key(Items.BREAD), (double) EST_BREAD);
            }
            case ANIMAL_FARMER -> {
                AnimalPenArea.AnimalTypes type = area instanceof AnimalPenArea pen ? pen.getAnimalType() : AnimalPenArea.AnimalTypes.COW;
                switch (type) {
                    case COW -> {
                        out.put(WorkerOutput.key(Items.BEEF), (double) EST_MEAT);
                        out.put(WorkerOutput.key(Items.LEATHER), EST_MEAT / 2.0);
                    }
                    case SHEEP -> {
                        out.put(WorkerOutput.key(Items.MUTTON), (double) EST_MEAT);
                        out.put(WorkerOutput.key(Items.WHITE_WOOL), (double) EST_MEAT);
                    }
                    case PIG -> out.put(WorkerOutput.key(Items.PORKCHOP), EST_MEAT * 1.5);
                    case CHICKEN -> {
                        out.put(WorkerOutput.key(Items.EGG), (double) EST_MEAT);
                        out.put(WorkerOutput.key(Items.FEATHER), (double) EST_MEAT);
                    }
                    case RABBIT -> {
                        out.put(WorkerOutput.key(Items.RABBIT), (double) EST_MEAT);
                        out.put(WorkerOutput.key(Items.RABBIT_HIDE), EST_MEAT / 2.0);
                    }
                    case GOAT -> out.put(WorkerOutput.key(Items.MUTTON), (double) EST_MEAT);
                    default -> out.put(WorkerOutput.key(Items.LEATHER), (double) EST_MEAT);
                }
            }
            case MINER -> {
                boolean deep = area != null && area.getArea() != null && area.getArea().minY < 0;
                out.put(WorkerOutput.key(deep ? Items.COBBLED_DEEPSLATE : Items.COBBLESTONE), (double) EST_STONE);
                out.put(WorkerOutput.key(Items.COAL), EST_STONE / 8.0);
                out.put(WorkerOutput.key(Items.RAW_IRON), EST_STONE / 10.0);
            }
            case FISHERMAN -> out.put(WorkerOutput.key(Items.COD), (double) EST_FISH);
            default -> {
            }
        }
        return out;
    }

    /** El tronco del árbol de un retoño (roble si no se sabe). */
    private static Item logOf(Item sapling) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(sapling);
        if (id == null) return Items.OAK_LOG;
        String path = id.getPath().replace("_sapling", "_log").replace("_propagule", "_log");
        Item log = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(id.getNamespace(), path));
        return log == null || log == Items.AIR ? Items.OAK_LOG : log;
    }

    /**
     * Un puesto de afuera (leñador fuera del territorio): lo que deja en su cofre pasa en el acto al almacén de la aldea, y
     * si se alejó mucho de su zona vuelve a su cofre (no vuelve a la aldea: su cama está ahí).
     */
    static void outpost(ServerLevel level, CampBlockEntity camp, AbstractWorkerEntity w, WorkerOutput.Rec rec) {
        AbstractWorkAreaEntity area = WorkZones.areaFor(level, w);
        if (area == null || area.getArea() == null) return;
        BlockPos chestPos = WorkZones.chestOf(area);
        if (chestPos != null && level.isLoaded(chestPos) && level.getBlockEntity(chestPos) instanceof Container chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (s.isEmpty()) continue;
                if (s.is(ItemTags.SAPLINGS) && w.countMatchingItems(x -> x.is(ItemTags.SAPLINGS)) < 8) continue;   // para replantar
                int moving = s.getCount();
                Item item = s.getItem();
                ItemStack back = camp.insert(s.copy());
                made(w, new ItemStack(item, moving - back.getCount()));
                chest.setItem(i, back);
                if (!back.isEmpty()) note(w, Kind.FULL, Component.empty());
            }
            chest.setChanged();
        }
        if (w.distanceToSqr(area.getArea().getCenter()) > (double) OUTPOST_LEASH * OUTPOST_LEASH && !w.isPassenger()) {
            BlockPos to = chestPos != null ? chestPos.above() : BlockPos.containing(area.getArea().getCenter());
            w.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            w.getNavigation().stop();
        }
    }

    // ------------------------------------------------------------------ cama

    /** Sin casa: la cama libre más cercana de la aldea se le reserva y se le arma una zona Hogar encima. */
    static void home(ServerLevel level, VillageData.Village v, AbstractWorkerEntity w) {
        PoiManager poi = level.getPoiManager();
        Optional<BlockPos> bed = poi.take(h -> h.is(PoiTypes.HOME), (h, p) -> true, w.blockPosition(), VillageManagement.RADIUS);
        if (bed.isEmpty()) return;
        if (!homeAt(level, v.owner(), w, bed.get())) poi.release(bed.get());
    }

    /** Zona Hogar de Workers sobre la cama {@code b} (ya reservada), con {@code w} de dueño. */
    public static boolean homeAt(ServerLevel level, UUID owner, AbstractWorkerEntity w, BlockPos b) {
        HomeArea home = ModEntityTypes.HOMEAREA.get().create(level);
        if (home == null) return false;
        home.setWidthSize(4);   // 3×3 alrededor de la cama (Workers la arma un bloque más corta)
        home.setDepthSize(4);
        home.setHeightSize(3);
        home.setPlayerUUID(owner);
        com.ale.warbandcraft.camp.WorkerZone.share(home);
        if (!com.ale.warbandcraft.camp.WorkerZone.place(home, new com.ale.warbandcraft.camp.WorkerZone.BoundingBox(
                b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY() + 2, b.getZ() + 1))) return false;
        home.setResident(w.getUUID(), w.getName().getString());
        home.assignedBedPos = b;
        home.getPersistentData().putLong(TAG_BED, b.asLong());
        level.addFreshEntity(home);
        w.homeAreaUUID = home.getUUID();
        return true;
    }

    /** Una zona Hogar nuestra cuyo trabajador murió: la cama vuelve a estar libre. */
    private static void releaseEmptyHomes(ServerLevel level, VillageData.Village v) {
        for (HomeArea h : level.getEntitiesOfClass(HomeArea.class, new AABB(v.center()).inflate(VillageManagement.RADIUS, 32, VillageManagement.RADIUS),
                h -> h.isAlive() && h.getPersistentData().contains(TAG_BED) && !h.isOccupied())) {
            BlockPos b = BlockPos.of(h.getPersistentData().getLong(TAG_BED));
            try {
                level.getPoiManager().release(b);
            } catch (RuntimeException ignored) {
                // la cama ya no estaba ocupada (o la rompieron)
            }
            h.discard();
        }
    }

    /** Deja su zona Hogar (la cama vuelve a estar libre): se muda a un puesto o vuelve de uno. */
    public static void leaveHome(ServerLevel level, AbstractWorkerEntity w) {
        if (w.homeAreaUUID == null) return;
        if (level.getEntity(w.homeAreaUUID) instanceof HomeArea h) {
            if (h.getPersistentData().contains(TAG_BED)) {
                try {
                    level.getPoiManager().release(BlockPos.of(h.getPersistentData().getLong(TAG_BED)));
                } catch (RuntimeException ignored) {
                    // ya estaba libre
                }
            }
            h.discard();
        }
        w.homeAreaUUID = null;
    }

    /** ¿Tiene cama? */
    public static boolean hasHome(AbstractWorkerEntity w) {
        return w.homeAreaUUID != null;
    }

    // ------------------------------------------------------------------ el minero

    /** Una cosa de la veta: qué sale y su peso (arriba y abajo de y = 0). */
    private record Ore(Item item, int weight, int deepWeight) {}

    private static final List<Ore> VEIN = List.of(
            new Ore(Items.COBBLESTONE, 60, 50),
            new Ore(Items.COAL, 20, 18),
            new Ore(Items.RAW_IRON, 14, 18),
            new Ore(Items.RAW_GOLD, 5, 9),
            new Ore(Items.DIAMOND, 1, 5));

    /** Lo que sale de la veta a esta altura. */
    public static ItemStack vein(ServerLevel level, int y) {
        boolean deep = y < 0;
        int total = 0;
        for (Ore o : VEIN) total += deep ? o.deepWeight : o.weight;
        int roll = level.random.nextInt(total);
        for (Ore o : VEIN) {
            roll -= deep ? o.deepWeight : o.weight;
            if (roll < 0) return new ItemStack(o.item == Items.COBBLESTONE && deep ? Items.COBBLED_DEEPSLATE : o.item);
        }
        return new ItemStack(Items.COBBLESTONE);
    }

    /**
     * El minero, trabajando en su mina (de día, con pico, cerca): cada {@link #MINE_EVERY_TICKS} saca algo de la veta
     * al almacén. Mina en escalera (la de Workers): terminada la escalera, sigue con pasillos de {@link #CORRIDOR_LENGTH}
     * por el territorio ({@link #CORRIDORS}). Mina vieja (pozo): si quedó vacía, se baja (hasta {@link #MINE_LOWERINGS} veces).
     */
    static void mine(ServerLevel level, VillageData.Village v, CampBlockEntity camp, MinerEntity miner, long now) {
        MiningArea area = miner.currentMiningArea;
        if (area == null || !area.isAlive()) return;
        if (!area.getKeepOn()) area.setKeepOn(true);   // que no quede "terminada" para siempre
        if (area.getPersistentData().contains(TAG_STAGE)
                && now - LAST_ADVANCE.getOrDefault(area.getUUID(), 0L) >= ADVANCE_EVERY_TICKS) {
            LAST_ADVANCE.put(area.getUUID(), now);
            advance(level, v, area);
        }
        if (miner.needsToSleep() || miner.countMatchingItems(s -> s.is(ItemTags.PICKAXES)) == 0) return;
        if (miner.distanceToSqr(area.getArea().getCenter()) > 32 * 32) return;
        if (now - LAST_MINE.getOrDefault(miner.getUUID(), now - MINE_EVERY_TICKS / 2) < MINE_EVERY_TICKS) return;
        LAST_MINE.put(miner.getUUID(), now);
        ItemStack found = vein(level, (int) area.getArea().minY);
        int count = found.getCount();
        Item item = found.getItem();
        ItemStack back = camp.insert(found);
        made(miner, new ItemStack(item, count - back.getCount()));
        if (!back.isEmpty()) note(miner, Kind.FULL, Component.empty());
        if (!area.getPersistentData().contains(TAG_STAGE) && isEmpty(level, area.getArea())) lower(area);
    }

    /** Marcas de la mina en escalera: etapa (0 = escalera, n = pasillo n), hacia dónde va, el final y el medio de la escalera. */
    public static final String TAG_STAGE = "warbandcraft:mine_stage", TAG_DIR = "warbandcraft:mine_dir",
            TAG_END = "warbandcraft:mine_end";

    /** La escalera terminada → primer pasillo; pasillo vacío → el siguiente (derecho, y si se sale del territorio, dobla). */
    public static void advance(ServerLevel level, VillageData.Village v, MiningArea area) {
        CompoundTag pd = area.getPersistentData();
        int stage = pd.getInt(TAG_STAGE);
        if (stage >= CORRIDORS) return;
        Direction dir = Direction.from2DDataValue(pd.getInt(TAG_DIR));
        if (stage == 0) {
            if (!stairsDone(level, area)) return;
            BlockPos end = BlockPos.of(pd.getLong(TAG_END));
            Integer floor = stairFloor(level, area, end);
            if (floor == null) return;
            corridor(level, v, area, new BlockPos(end.getX(), floor, end.getZ()), dir, 1);
        } else if (isEmpty(level, area.getArea())) {
            corridor(level, v, area, BlockPos.of(pd.getLong(TAG_END)), dir, stage + 1);
        }
    }

    /** ¿Workers ya sacó todo lo de la escalera? */
    private static boolean stairsDone(ServerLevel level, MiningArea area) {
        AABB box = area.getArea();
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
            if (!area.isStairsAir(p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty() && !area.shouldIgnore(s) && s.getDestroySpeed(level, p) >= 0) return false;
        }
        return true;
    }

    /** El escalón de abajo de todo en la columna {@code end} (el aire más bajo con piso firme): su altura, o null. */
    @Nullable
    private static Integer stairFloor(ServerLevel level, MiningArea area, BlockPos end) {
        AABB box = area.getArea();
        for (int y = (int) box.minY; y < box.maxY; y++) {
            BlockPos p = new BlockPos(end.getX(), y, end.getZ());
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return y;
        }
        return null;
    }

    /**
     * El pasillo número {@code stage}: {@link #CORRIDOR_LENGTH} hacia {@code dir} desde {@code from} (3 de ancho y de alto);
     * si se sale del territorio prueba doblar a la derecha y a la izquierda. Sin lugar, la mina queda como está.
     */
    private static void corridor(ServerLevel level, VillageData.Village v, MiningArea area, BlockPos from, Direction dir, int stage) {
        CompoundTag pd = area.getPersistentData();
        for (Direction d : new Direction[]{dir, dir.getClockWise(), dir.getCounterClockWise()}) {
            BlockPos end = from.relative(d, CORRIDOR_LENGTH);
            if (!WorkZones.inTerritory(level, v, end) || !WorkZones.inTerritory(level, v, from.relative(d))) continue;
            Direction side = d.getClockWise();
            BlockPos a = from.relative(d).relative(side, -1), b = end.relative(side);
            WorkerZone.BoundingBox box = new WorkerZone.BoundingBox(Math.min(a.getX(), b.getX()), from.getY(), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), from.getY() + 2, Math.max(a.getZ(), b.getZ()));
            area.setMode(MiningArea.MiningMode.CUSTOM.getIndex());
            area.setWidthSize(box.width() + 1);
            area.setHeightSize(box.height());
            area.setDepthSize(box.depth() + 1);
            if (!WorkerZone.place(area, box)) continue;
            area.resetWork();
            pd.putInt(TAG_STAGE, stage);
            pd.putInt(TAG_DIR, d.get2DDataValue());
            pd.putLong(TAG_END, end.asLong());
            return;
        }
        pd.putInt(TAG_STAGE, CORRIDORS);   // no hay para dónde seguir
    }

    /** ¿Ya no queda nada para picar en la caja (salvo el piso)? */
    private static boolean isEmpty(ServerLevel level, AABB box) {
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY + 1, box.minZ),
                BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1))) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static void lower(MiningArea area) {
        CompoundTag pd = area.getPersistentData();
        int done = pd.getInt(TAG_LOWERED);
        if (done >= MINE_LOWERINGS) return;
        pd.putInt(TAG_LOWERED, done + 1);
        area.setPos(area.getX(), area.getY() - WorkZones.MINE_DEPTH, area.getZ());
        area.area = area.createArea();
        area.resetWork();
    }

    // ------------------------------------------------------------------ el cartelito

    /** Estado de un trabajador para el modo Gestión: texto y color. */
    public record Status(Component text, int color) {}

    public static final int GREEN = 0xFF55FF55, YELLOW = 0xFFFFD24A, RED = 0xFFFF5555, GREY = 0xFFAAAAAA;

    public static Status status(ServerLevel level, AbstractWorkerEntity w) {
        boolean smith = w instanceof SmithEntity;
        if (!WorkZones.hasPlace(w) && WorkZones.areaFor(level, w) == null) {
            return new Status(Component.translatable(smith ? "status.warbandcraft.worker.no_anvil" : "status.warbandcraft.worker.no_zone"), RED);
        }
        Note n;
        if ((n = noteOf(w, Kind.MISSING)) != null) return new Status(Component.translatable("status.warbandcraft.worker.missing", n.what), RED);
        if (noteOf(w, Kind.FULL) != null) return new Status(Component.translatable("status.warbandcraft.worker.full"), RED);
        if (noteOf(w, Kind.HUNGRY) != null && w.needsToGetFood()) return new Status(Component.translatable("status.warbandcraft.worker.hungry"), RED);
        Component noBed = hasHome(w) ? Component.empty() : Component.translatable("status.warbandcraft.worker.no_home");
        if (w.needsToSleep()) {
            return new Status(Component.translatable("status.warbandcraft.worker.sleeping").append(noBed), hasHome(w) ? GREY : YELLOW);
        }
        if (w instanceof com.ale.warbandcraft.worker.OrganizerEntity o && !o.getMissing().isEmpty()) {
            return new Status(com.ale.warbandcraft.camp.Organizer.missingText(o.getMissing()), YELLOW);
        }
        if (!smith && w.needsToGetItems()) {
            MutableComponent list = null;
            for (NeededItem ni : w.neededItems) {
                if (!ni.required) continue;
                Object key = ni.getMatchKey();
                list = add(list, ni.count, key instanceof Item i ? i : null);
            }
            if (list != null) return new Status(Component.translatable("status.warbandcraft.worker.fetching", list), YELLOW);
        }
        return new Status(Component.translatable("status.warbandcraft.worker.working").append(noBed), hasHome(w) ? GREEN : YELLOW);
    }

    /** Para las pruebas: se olvida lo anotado. */
    public static void clear() {
        NOTES.clear();
        LAST_COLLECT.clear();
        LAST_MINE.clear();
        LAST_ADVANCE.clear();
    }
}
