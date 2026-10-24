package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.worker.SmithEntity;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.MinerEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import com.talhanation.workers.entities.workarea.CropArea;
import com.talhanation.workers.entities.workarea.HomeArea;
import com.talhanation.workers.entities.workarea.LumberArea;
import com.talhanation.workers.entities.workarea.MiningArea;
import com.talhanation.workers.init.ModEntityTypes;
import com.talhanation.workers.world.NeededItem;
import net.minecraft.core.BlockPos;
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

    private static final String TAG_LOWERED = "warbandcraft:lowered", TAG_BED = "warbandcraft:bed";

    private WorkerSupply() {}

    // ------------------------------------------------------------------ el equipo de cada oficio

    /** Algo que el trabajador tiene que tener siempre: qué sirve, cuántos y qué se muestra si falta. */
    public record Kit(Predicate<ItemStack> match, int count, Item shown) {}

    public static List<Kit> kit(AbstractWorkerEntity w) {
        VillageManagement.Job job = WorkZones.jobOf(w);
        if (job == null) return List.of();
        return switch (job) {
            case MINER -> {
                Item fill = w.getCurrentWorkArea() instanceof MiningArea m && !m.getFillItem().isEmpty() ? m.getFillItem().getItem() : Items.COBBLESTONE;
                yield List.of(new Kit(s -> s.is(ItemTags.PICKAXES), 1, Items.STONE_PICKAXE), new Kit(s -> s.is(ItemTags.SHOVELS), 1, Items.STONE_SHOVEL),
                        new Kit(s -> s.is(Items.TORCH), 16, Items.TORCH), new Kit(s -> s.is(fill), 16, fill));
            }
            case FARMER -> {
                Item seed = w.getCurrentWorkArea() instanceof CropArea c && !c.getSeedStack().isEmpty() ? c.getSeedStack().getItem() : Items.WHEAT_SEEDS;
                yield List.of(new Kit(s -> s.is(ItemTags.HOES), 1, Items.STONE_HOE), new Kit(s -> s.is(seed), 32, seed));
            }
            case LUMBERJACK -> {
                Item sapling = w.getCurrentWorkArea() instanceof LumberArea l && !l.getSaplingStack().isEmpty() ? l.getSaplingStack().getItem() : Items.OAK_SAPLING;
                yield List.of(new Kit(s -> s.is(ItemTags.AXES), 1, Items.STONE_AXE), new Kit(s -> s.is(sapling), 8, sapling));
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
    private static final Map<UUID, Long> LAST_COLLECT = new HashMap<>(), LAST_MINE = new HashMap<>();

    private static void note(AbstractWorkerEntity w, Kind kind, Component what) {
        NOTES.put(w.getUUID(), new Note(kind, what, w.level().getGameTime()));
    }

    @Nullable
    private static Note noteOf(AbstractWorkerEntity w, Kind kind) {
        Note n = NOTES.get(w.getUUID());
        return n != null && n.kind == kind && w.level().getGameTime() - n.tick <= NOTE_TICKS ? n : null;
    }

    // ------------------------------------------------------------------ cada 5 s

    /** Todas tus aldeas cargadas: sus trabajadores. */
    public static void tick(ServerLevel level) {
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (!v.isConquered() || !level.isPositionEntityTicking(v.center())) continue;
            CampBlockEntity camp = VillageStorehouse.camp(level, v);
            for (AbstractWorkerEntity w : VillageManagement.workers(level, v.center(), v.owner())) serve(level, v, camp, w);
            releaseEmptyHomes(level, v);
        }
        NOTES.keySet().removeIf(id -> level.getEntity(id) == null && level.getGameTime() - NOTES.get(id).tick > NOTE_TICKS);
    }

    /** Un trabajador: equipo, comida, juntar, cama y la veta del minero. */
    public static void serve(ServerLevel level, VillageData.Village v, @Nullable CampBlockEntity camp, AbstractWorkerEntity w) {
        if (camp == null) return;
        long now = level.getGameTime();
        supply(camp, w);
        feed(camp, w);
        Long last = LAST_COLLECT.get(w.getUUID());
        if (last == null || now - last >= COLLECT_EVERY_TICKS) {
            LAST_COLLECT.put(w.getUUID(), now);
            collect(camp, w);
        }
        if (w.homeAreaUUID == null) home(level, v, w);
        if (w instanceof MinerEntity miner) mine(level, camp, miner, now);
    }

    /** Lo que le falta (su equipo y lo que pidió Workers), del almacén a su mochila. Lo que no hay queda anotado. */
    static void supply(CampBlockEntity camp, AbstractWorkerEntity w) {
        MutableComponent missing = null;
        for (Kit k : kit(w)) {
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
    static void feed(CampBlockEntity camp, AbstractWorkerEntity w) {
        if (!w.needsToGetFood()) return;
        int got = Barracks.takeFood(camp, MEAL_POINTS);
        if (got > 0) w.setHunger(Math.min(100F, w.getHunger() + got * 10F));
        else note(w, Kind.HUNGRY, Component.empty());
    }

    /** Lo que juntó (salvo su equipo y un poco de comida) pasa al almacén. Si no entra, queda anotado. */
    static void collect(CampBlockEntity camp, AbstractWorkerEntity w) {
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
            ItemStack out = s.split(s.getCount() - keep);
            ItemStack back = camp.insert(out);
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
        home.setTeamAccess(false);
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
     * al almacén. Si la mina quedó vacía, se baja (hasta {@link #MINE_LOWERINGS} veces); después sigue sacando de la veta.
     */
    static void mine(ServerLevel level, CampBlockEntity camp, MinerEntity miner, long now) {
        MiningArea area = miner.currentMiningArea;
        if (area == null || !area.isAlive()) return;
        if (!area.getKeepOn()) area.setKeepOn(true);   // que no quede "terminada" para siempre
        if (miner.needsToSleep() || miner.countMatchingItems(s -> s.is(ItemTags.PICKAXES)) == 0) return;
        if (miner.distanceToSqr(area.getArea().getCenter()) > 32 * 32) return;
        if (now - LAST_MINE.getOrDefault(miner.getUUID(), now - MINE_EVERY_TICKS / 2) < MINE_EVERY_TICKS) return;
        LAST_MINE.put(miner.getUUID(), now);
        ItemStack found = vein(level, (int) area.getArea().minY);
        ItemStack back = camp.insert(found);
        if (!back.isEmpty()) note(miner, Kind.FULL, Component.empty());
        if (isEmpty(level, area.getArea())) lower(area);
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
    }
}
