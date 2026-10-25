package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Cofre del Campamento: acá se guardan los recursos con los que se arman las tropas.
 * Se le pueden vincular cofres cercanos (H6) para que el almacén no se quede corto.
 */
public class CampBlockEntity extends BaseContainerBlockEntity {
    public static final int SIZE = 54;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    @Nullable
    private UUID owner;
    /** Cofres vinculados (H6): suman espacio al almacén del Campamento. */
    private final List<BlockPos> linked = new ArrayList<>();
    /** H10: último día en que trabajaron los herreros ({@link #NEVER} = todavía nunca). */
    private long lastForgeDay = NEVER;
    public static final long NEVER = Long.MIN_VALUE;
    /** H16: centro de la aldea/castillo de la que este Campamento es el almacén (null = Campamento común). */
    @Nullable
    private BlockPos village;
    /** Desguace: piezas que los herreros convierten en armamento si las ven en el almacén ({@link Scrap}). */
    private final java.util.Set<net.minecraft.resources.ResourceLocation> scrap = new java.util.LinkedHashSet<>();

    public CampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CAMP.get(), pos, state);
    }

    // ---------- Dueño ----------

    public void setOwner(UUID owner) {
        this.owner = owner;
        this.setChanged();
        registerInWorld();
    }

    /** Anota el Campamento en el registro del mundo, para que le lleguen botín e impuestos (H3) y se sepa su nombre (H8). */
    private void registerInWorld() {
        if (owner != null && level instanceof ServerLevel server) {
            CampRegistry registry = CampRegistry.get(server);
            registry.register(owner, worldPosition);
            if (getCustomName() != null) registry.setName(worldPosition, getCustomName().getString());
        }
    }

    // ---------- Nombre (H8) ----------

    /** Hasta cuántas letras puede tener el nombre de un Campamento. */
    public static final int MAX_NAME_LENGTH = 32;

    /** Le pone nombre al Campamento (vacío = no cambia nada). Devuelve si lo cambió. */
    public boolean rename(String name) {
        String clean = cleanName(name);
        if (clean.isEmpty()) return false;
        setCustomName(Component.literal(clean));
        setChanged();
        registerInWorld();
        return true;
    }

    /** El nombre sin espacios de más y cortado a {@link #MAX_NAME_LENGTH} ("" = no sirve). */
    public static String cleanName(String name) {
        String clean = name.strip();
        return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH).strip() : clean;
    }

    /**
     * Renombra el Campamento en {@code pos} aunque no esté cargado (Panel): si no lo está, el nombre queda en el
     * registro y el bloque lo toma al cargarse ({@link #onLoad}). Devuelve si lo cambió.
     */
    public static boolean renameAt(ServerLevel level, BlockPos pos, String name) {
        String clean = cleanName(name);
        if (clean.isEmpty()) return false;
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CampBlockEntity camp) return camp.rename(clean);
        CampRegistry.get(level).setName(pos, clean);
        return true;
    }

    /** El nombre que se ve: el propio o "Campamento". */
    public String nameString() {
        return getName().getString();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Si lo renombraron desde lejos (Panel), manda el nombre del registro
        if (owner != null && level instanceof ServerLevel server) {
            String known = CampRegistry.get(server).nameOf(worldPosition);
            if (known != null && (getCustomName() == null || !known.equals(getCustomName().getString()))) {
                setCustomName(Component.literal(known));
                setChanged();
            }
        }
        registerInWorld();
    }

    /** H16: centro de la aldea/castillo de la que es el almacén, o null. */
    @Nullable
    public BlockPos getVillage() {
        return village;
    }

    public void setVillage(@Nullable BlockPos center) {
        this.village = center;
        setChanged();
    }

    /** H16: la aldea se perdió: el Campamento deja de ser de alguien (se saca del registro sin soltar a nadie). */
    public void release() {
        if (owner != null && level instanceof ServerLevel server) CampRegistry.get(server).unregister(owner, worldPosition);
        owner = null;
        setChanged();
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    /** Un Campamento sin dueño (p. ej. puesto con comandos) lo puede usar cualquiera; uno con dueño, él y los de su facción. */
    public boolean canBeUsedBy(UUID player) {
        return owner == null || com.ale.warbandcraft.faction.Allies.same(owner, player);
    }

    /** Lo mismo que {@link #canBeUsedBy} (antes el barracón era lo único compartido). */
    public boolean barracksUsableBy(UUID player) {
        return canBeUsedBy(player);
    }

    // ---------- Cofres vinculados ----------

    /**
     * Jurisdicción del Campamento: el único radio de trabajo (H8). Dentro de él se arman soldados, se
     * vinculan cofres y "Guardar a los de cerca" los encuentra. La vista aérea lo dibuja al seleccionarlo.
     */
    public static int WORK_RADIUS = 24;
    /** Hasta dónde puede estar un cofre vinculado (desde el Campamento). */
    public static int LINK_RADIUS = WORK_RADIUS;
    public static int MAX_LINKED = 16;
    /** H16: el Campamento de una aldea vincula más cofres (los de sus zonas de trabajo). */
    public static int VILLAGE_MAX_LINKED = 48;

    /**
     * H16: ¿{@code pos} está en la jurisdicción del Campamento {@code camp}? Uno común: a ≤ {@link #WORK_RADIUS}. El de
     * una aldea/castillo: su territorio (los chunks de {@link com.ale.warbandcraft.village.Territory}).
     */
    public static boolean covers(ServerLevel level, BlockPos camp, BlockPos pos) {
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageCamps.villageOf(level, camp);
        com.ale.warbandcraft.village.Territory t = com.ale.warbandcraft.village.Territory.get(level);
        if (v != null && t.has(v.center())) return t.contains(v.center(), pos);
        return pos.distSqr(camp) <= (double) WORK_RADIUS * WORK_RADIUS;
    }

    /** H16: la caja que encierra la jurisdicción (para buscar entidades; después filtrar con {@link #covers}). */
    public static net.minecraft.world.phys.AABB area(ServerLevel level, BlockPos camp) {
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageCamps.villageOf(level, camp);
        List<net.minecraft.world.level.ChunkPos> chunks = v == null ? List.of() : com.ale.warbandcraft.village.Territory.get(level).of(v.center());
        if (chunks.isEmpty()) return new net.minecraft.world.phys.AABB(camp).inflate(WORK_RADIUS);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (net.minecraft.world.level.ChunkPos c : chunks) {
            x0 = Math.min(x0, c.getMinBlockX());
            z0 = Math.min(z0, c.getMinBlockZ());
            x1 = Math.max(x1, c.getMaxBlockX() + 1);
            z1 = Math.max(z1, c.getMaxBlockZ() + 1);
        }
        return new net.minecraft.world.phys.AABB(x0, camp.getY() - 48, z0, x1, camp.getY() + 64, z1);
    }

    public enum LinkResult { LINKED, UNLINKED, TOO_FAR, FULL, NOT_A_CHEST }

    /**
     * Vincula (o desvincula, si ya estaba) un cofre, barril o similar al Campamento: sus espacios pasan a
     * contar como almacén para armar soldados y para recibir botín e impuestos. Un cofre doble vincula
     * sus dos mitades.
     */
    public LinkResult toggleLink(BlockPos pos) {
        if (level == null || !(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity)) {
            return LinkResult.NOT_A_CHEST;
        }
        List<BlockPos> halves = new ArrayList<>();
        halves.add(pos.immutable());
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            halves.add(pos.relative(ChestBlock.getConnectedDirection(state)).immutable());
        }
        if (linked.contains(pos)) {
            linked.removeAll(halves);
            setChanged();
            return LinkResult.UNLINKED;
        }
        for (BlockPos p : halves) {
            if (!(level instanceof ServerLevel server ? covers(server, worldPosition, p) : p.distSqr(worldPosition) <= (double) LINK_RADIUS * LINK_RADIUS)) {
                return LinkResult.TOO_FAR;
            }
        }
        int adding = (int) halves.stream().filter(p -> !linked.contains(p)).count();
        if (linked.size() + adding > (village != null ? VILLAGE_MAX_LINKED : MAX_LINKED)) return LinkResult.FULL;
        for (BlockPos p : halves) if (!linked.contains(p)) linked.add(p);
        setChanged();
        return LinkResult.LINKED;
    }

    public long lastForgeDay() {
        return lastForgeDay;
    }

    public void setLastForgeDay(long day) {
        lastForgeDay = day;
        setChanged();
    }

    public boolean hasScrap() {
        return !scrap.isEmpty();
    }

    public boolean isScrap(Item item) {
        return scrap.contains(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item));
    }

    public java.util.Set<net.minecraft.resources.ResourceLocation> scrapList() {
        return java.util.Set.copyOf(scrap);
    }

    /** Marca o desmarca una pieza para desguazar. Devuelve si quedó marcada. */
    public boolean toggleScrap(Item item) {
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        boolean on = !scrap.remove(id);
        if (on) scrap.add(id);
        setChanged();
        return on;
    }

    public List<BlockPos> getLinked() {
        return List.copyOf(linked);
    }

    /**
     * Todo el almacén: el cofre propio primero y después los vinculados que estén cargados.
     * Un vinculado que ya no es un cofre (lo rompieron) se olvida.
     */
    public List<Container> storages() {
        List<Container> out = new ArrayList<>();
        out.add(this);
        if (level == null) return out;
        boolean pruned = false;
        for (Iterator<BlockPos> it = linked.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest) {
                out.add(chest);
            } else {
                it.remove();
                pruned = true;
            }
        }
        if (pruned) setChanged();
        return out;
    }

    /** Espacios de los cofres vinculados cargados (sin contar los 54 propios). */
    public int linkedSlots() {
        int total = 0;
        for (Container c : storages()) if (c != this) total += c.getContainerSize();
        return total;
    }

    // ---------- Recursos (cofre propio + vinculados) ----------

    /** Recorre todo lo que hay en el almacén. */
    public void forEachStack(Consumer<ItemStack> action) {
        for (Container c : storages()) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (!stack.isEmpty()) action.accept(stack);
            }
        }
    }

    public int count(Item item) {
        int[] total = {0};
        forEachStack(stack -> {
            if (stack.is(item)) total[0] += stack.getCount();
        });
        return total[0];
    }

    /** Saca hasta {@code amount} unidades de {@code item}. Devuelve cuántas sacó. */
    public int take(Item item, int amount) {
        int remaining = amount;
        for (Container c : storages()) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = c.getItem(i);
                if (stack.is(item)) {
                    int n = Math.min(remaining, stack.getCount());
                    stack.shrink(n);
                    remaining -= n;
                    if (stack.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                    changed = true;
                }
            }
            if (changed) c.setChanged();
            if (remaining == 0) break;
        }
        return amount - remaining;
    }

    /** Guarda un stack en el almacén (primero completa pilas, después usa espacios vacíos). Devuelve lo que no entró. */
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        List<Container> all = storages();
        for (Container c : all) {
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack slot = c.getItem(i);
                if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, rest)) {
                    int max = Math.min(slot.getMaxStackSize(), c.getMaxStackSize());
                    int n = Math.min(rest.getCount(), max - slot.getCount());
                    if (n > 0) {
                        slot.grow(n);
                        rest.shrink(n);
                        c.setChanged();
                    }
                }
            }
        }
        for (Container c : all) {
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                if (c.getItem(i).isEmpty() && c.canPlaceItem(i, rest)) {
                    int n = Math.min(rest.getCount(), Math.min(rest.getMaxStackSize(), c.getMaxStackSize()));
                    c.setItem(i, rest.split(n));
                    c.setChanged();
                }
            }
        }
        return rest;
    }

    // ---------- Container ----------

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.warbandcraft.camp");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new CampMenu(id, inventory, this, worldPosition, canBeUsedBy(inventory.player.getUUID()),
                barracksUsableBy(inventory.player.getUUID()), village != null, new CampStats(this));
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack result = ContainerHelper.removeItem(items, slot, amount);
        if (!result.isEmpty()) setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // ---------- Guardado ----------

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        linked.clear();
        for (long l : tag.getLongArray("Linked")) linked.add(BlockPos.of(l));
        lastForgeDay = tag.contains("LastForgeDay") ? tag.getLong("LastForgeDay") : NEVER;
        village = tag.contains("Village") ? BlockPos.of(tag.getLong("Village")) : null;
        scrap.clear();
        for (net.minecraft.nbt.Tag t : tag.getList("Scrap", net.minecraft.nbt.Tag.TAG_STRING)) {
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(t.getAsString());
            if (id != null) scrap.add(id);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        if (owner != null) tag.putUUID("Owner", owner);
        if (!linked.isEmpty()) tag.putLongArray("Linked", linked.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLong("LastForgeDay", lastForgeDay);
        if (village != null) tag.putLong("Village", village.asLong());
        if (!scrap.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (net.minecraft.resources.ResourceLocation id : scrap) list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            tag.put("Scrap", list);
        }
    }
}
