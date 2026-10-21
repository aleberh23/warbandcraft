package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.ale.warbandcraft.init.ModMenus;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import com.ale.warbandcraft.kit.KitOverride;
import com.ale.warbandcraft.kit.PlayerKits;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pestaña "Kits" del Campamento (H6): elegir clase y nivel, y armar su equipo con espacios "fantasma"
 * (ponés un ítem y queda una copia como plantilla; el tuyo no se gasta).
 *
 * Los botones (clase, nivel, guardar, volver al original) usan {@link #clickMenuButton}, el mecanismo
 * de vanilla para botones de un menú. Los espacios fantasma se manejan en {@link #clicked}, que corre
 * igual en cliente y servidor, así la predicción del cliente coincide.
 */
public class KitEditorMenu extends AbstractContainerMenu {
    public static final int GHOST = KitOverride.SLOTS.length + KitOverride.BACKPACK_SIZE;
    public static final int BTN_TIER = 100, BTN_SAVE = 200, BTN_RESET = 201;

    // Posiciones dentro de la pantalla (las usa también KitEditorScreen)
    public static final int WIDTH = 300, HEIGHT = 250;
    public static final int EQUIP_X = 92, EQUIP_Y = 60;
    public static final int PACK_X = 92, PACK_Y = 90;
    public static final int INV_X = 92, INV_Y = 170;

    /** Lo que el cliente necesita saber de cada clase. El nombre se puede cambiar (renombrar). */
    public static final class ClassInfo {
        public final ResourceLocation id;
        public String name;
        public final ResourceLocation entity;
        public final List<String> tierNames;
        public final List<List<ItemStack>> tierExtraCost;

        public ClassInfo(ResourceLocation id, String name, ResourceLocation entity, List<String> tierNames,
                         List<List<ItemStack>> tierExtraCost) {
            this.id = id;
            this.name = name;
            this.entity = entity;
            this.tierNames = tierNames;
            this.tierExtraCost = tierExtraCost;
        }

        public static List<ClassInfo> of(List<ClassKit> kits) {
            List<ClassInfo> out = new ArrayList<>();
            for (ClassKit k : kits) {
                List<String> names = new ArrayList<>();
                List<List<ItemStack>> extra = new ArrayList<>();
                for (ClassKit.Tier t : k.tiers()) {
                    names.add(t.name());
                    extra.add(t.extraCost());
                }
                out.add(new ClassInfo(k.id(), k.name(), k.entity(), names, extra));
            }
            return out;
        }
    }

    private final BlockPos campPos;
    private final List<ClassInfo> classes;
    private final boolean server;
    private final Player player;
    private final SimpleContainer ghost = new SimpleContainer(GHOST);
    private final DataSlot selClass = DataSlot.standalone();
    private final DataSlot selTier = DataSlot.standalone();
    /** 1 = el Campamento alcanza para armar uno con este kit, 0 = no. */
    private final DataSlot affordable = DataSlot.standalone();
    /** Cuánto armamento usaría para cubrir las piezas que faltan (H10). */
    private final DataSlot armamentUsed = DataSlot.standalone();

    public KitEditorMenu(int id, Inventory inventory, BlockPos campPos, List<ClassInfo> classes, boolean server) {
        super(ModMenus.KIT_EDITOR.get(), id);
        this.campPos = campPos;
        this.classes = classes;
        this.server = server;
        this.player = inventory.player;

        for (int i = 0; i < KitOverride.SLOTS.length; i++) addSlot(new GhostSlot(ghost, i, EQUIP_X + i * 18, EQUIP_Y));
        for (int i = 0; i < KitOverride.BACKPACK_SIZE; i++) {
            addSlot(new GhostSlot(ghost, KitOverride.SLOTS.length + i, PACK_X + i * 18, PACK_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, 9 + col + row * 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, INV_X + col * 18, INV_Y + 58));

        addDataSlot(selClass);
        addDataSlot(selTier);
        addDataSlot(affordable);
        addDataSlot(armamentUsed);
        if (server) load();
    }

    // ---------- Red ----------

    public static void write(FriendlyByteBuf buf, BlockPos pos, List<ClassInfo> classes) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(classes.size());
        for (ClassInfo c : classes) {
            buf.writeResourceLocation(c.id);
            buf.writeUtf(c.name);
            buf.writeResourceLocation(c.entity);
            buf.writeVarInt(c.tierNames.size());
            for (int t = 0; t < c.tierNames.size(); t++) {
                buf.writeUtf(c.tierNames.get(t));
                List<ItemStack> extra = c.tierExtraCost.get(t);
                buf.writeVarInt(extra.size());
                for (ItemStack s : extra) buf.writeItem(s);
            }
        }
    }

    public static KitEditorMenu fromNetwork(int id, Inventory inventory, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int n = buf.readVarInt();
        List<ClassInfo> classes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ResourceLocation kit = buf.readResourceLocation();
            String name = buf.readUtf();
            ResourceLocation entity = buf.readResourceLocation();
            int tiers = buf.readVarInt();
            List<String> names = new ArrayList<>(tiers);
            List<List<ItemStack>> extra = new ArrayList<>(tiers);
            for (int t = 0; t < tiers; t++) {
                names.add(buf.readUtf());
                int e = buf.readVarInt();
                List<ItemStack> list = new ArrayList<>(e);
                for (int j = 0; j < e; j++) list.add(buf.readItem());
                extra.add(list);
            }
            classes.add(new ClassInfo(kit, name, entity, names, extra));
        }
        return new KitEditorMenu(id, inventory, pos, classes, false);
    }

    // ---------- Lo que lee la pantalla ----------

    public BlockPos campPos() {
        return campPos;
    }

    public List<ClassInfo> classes() {
        return classes;
    }

    public int selectedClass() {
        return Math.min(Math.max(0, selClass.get()), Math.max(0, classes.size() - 1));
    }

    public int selectedTier() {
        return Math.max(0, selTier.get());
    }

    public boolean affordable() {
        return affordable.get() == 1;
    }

    /** Armamento que usaría (H10): para mostrar "Alcanza (usa 8 de armamento)". */
    public int armamentUsed() {
        return armamentUsed.get();
    }

    public Map<EquipmentSlot, Item> equipment() {
        Map<EquipmentSlot, Item> eq = new EnumMap<>(EquipmentSlot.class);
        for (int i = 0; i < KitOverride.SLOTS.length; i++) {
            ItemStack s = ghost.getItem(i);
            if (!s.isEmpty()) eq.put(KitOverride.SLOTS[i], s.getItem());
        }
        return eq;
    }

    public List<ItemStack> backpack() {
        List<ItemStack> inv = new ArrayList<>();
        for (int i = KitOverride.SLOTS.length; i < GHOST; i++) {
            ItemStack s = ghost.getItem(i);
            if (!s.isEmpty()) inv.add(s.copy());
        }
        return inv;
    }

    /** Equipo + mochila + costo extra del nivel, agrupado por ítem (lo que cobraría el Campamento). */
    public Map<Item, Integer> cost() {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        equipment().values().forEach(item -> cost.merge(item, 1, Integer::sum));
        backpack().forEach(s -> cost.merge(s.getItem(), s.getCount(), Integer::sum));
        if (!classes.isEmpty()) {
            ClassInfo c = classes.get(selectedClass());
            if (selectedTier() < c.tierExtraCost.size()) {
                c.tierExtraCost.get(selectedTier()).forEach(s -> cost.merge(s.getItem(), s.getCount(), Integer::sum));
            }
        }
        return cost;
    }

    // ---------- Botones ----------

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (classes.isEmpty()) return false;
        if (id >= 0 && id < classes.size()) {
            selClass.set(id);
            selTier.set(0);
            load();
            return true;
        }
        int tiers = classes.get(selectedClass()).tierNames.size();
        if (id >= BTN_TIER && id < BTN_TIER + tiers) {
            selTier.set(id - BTN_TIER);
            load();
            return true;
        }
        if (id == BTN_SAVE) {
            save();
            return true;
        }
        if (id == BTN_RESET) {
            reset();
            return true;
        }
        return false;
    }

    /** Carga en los espacios fantasma el kit actual (propio u original) de la clase y nivel elegidos. */
    private void load() {
        if (!server || classes.isEmpty()) return;
        ClassInfo info = classes.get(selectedClass());
        ClassKit kit = PlayerKits.resolve(player.getServer(), player.getUUID(), info.id);
        ghost.clearContent();
        if (kit != null && selectedTier() < kit.tiers().size()) {
            ClassKit.Tier tier = kit.tiers().get(selectedTier());
            for (int i = 0; i < KitOverride.SLOTS.length; i++) {
                Item item = tier.equipment().get(KitOverride.SLOTS[i]);
                if (item != null) ghost.setItem(i, new ItemStack(item));
            }
            int slot = KitOverride.SLOTS.length;
            for (ItemStack s : tier.inventory()) {
                if (slot >= GHOST) break;
                ghost.setItem(slot++, s.copy());
            }
        }
        updateAffordable();
    }

    private void save() {
        if (!server || !(player instanceof ServerPlayer sp)) return;
        ClassInfo info = classes.get(selectedClass());
        ClassKit base = KitManager.INSTANCE.get(info.id);
        if (base == null || selectedTier() >= base.tiers().size()) return;
        Component error = PlayerKits.validate(info.entity, equipment(), backpack());
        if (error != null) {
            sp.displayClientMessage(error.copy().withStyle(ChatFormatting.RED), true);
            return;
        }
        KitOverride override = new KitOverride(equipment(), backpack());
        ClassKit.Tier baseTier = base.tiers().get(selectedTier());
        PlayerFactionData.get(sp.getServer()).setOverride(sp.getUUID(), info.id, selectedTier(),
                override.sameAs(baseTier) ? null : override);
        sp.displayClientMessage(Component.translatable("msg.warbandcraft.kit.saved", info.name,
                info.tierNames.get(selectedTier())).withStyle(ChatFormatting.GREEN), true);
        PlayerKits.rearmNear((ServerLevel) sp.level(), sp.getUUID(), true);
    }

    private void reset() {
        if (!server || !(player instanceof ServerPlayer sp)) return;
        ClassInfo info = classes.get(selectedClass());
        PlayerFactionData data = PlayerFactionData.get(sp.getServer());
        if (data.override(sp.getUUID(), info.id, selectedTier()) != null) {
            data.setOverride(sp.getUUID(), info.id, selectedTier(), null);
            sp.displayClientMessage(Component.translatable("msg.warbandcraft.kit.reset", info.name)
                    .withStyle(ChatFormatting.YELLOW), true);
            PlayerKits.rearmNear((ServerLevel) sp.level(), sp.getUUID(), true);
        }
        load();
    }

    private void updateAffordable() {
        if (!server) return;
        CampBlockEntity camp = player.level().getBlockEntity(campPos) instanceof CampBlockEntity c ? c : null;
        boolean ok = false;
        int arm = 0;
        if (camp != null) {
            // H10: lo que falte de metal se cubre con armamento
            Armament.Plan plan = Armament.INSTANCE.plan(Armament.poolOf(camp), cost(), selectedTier() == 0);
            ok = plan.ok();
            arm = plan.armament();
        }
        affordable.set(ok ? 1 : 0);
        armamentUsed.set(arm);
    }

    // ---------- Espacios fantasma ----------

    @Override
    public void clicked(int slotId, int button, ClickType type, Player p) {
        if (slotId >= 0 && slotId < GHOST) {
            if (type == ClickType.PICKUP) ghostClick(slotId, button, getCarried());
            return;
        }
        super.clicked(slotId, button, type, p);
    }

    private void ghostClick(int slot, int button, ItemStack carried) {
        ItemStack current = ghost.getItem(slot);
        boolean backpackSlot = slot >= KitOverride.SLOTS.length;
        if (carried.isEmpty()) {
            if (button == 1 && backpackSlot && current.getCount() > 1) {
                ItemStack less = current.copy();
                less.shrink(1);
                ghost.setItem(slot, less);
            } else {
                ghost.setItem(slot, ItemStack.EMPTY);
            }
        } else {
            if (!backpackSlot && !fits(KitOverride.SLOTS[slot], carried)) return;
            Item item = carried.getItem();
            int count;
            if (!backpackSlot) count = 1;
            else if (button == 1) count = current.is(item) ? Math.min(current.getCount() + 1, item.getMaxStackSize(carried)) : 1;
            else count = carried.getCount();
            ghost.setItem(slot, new ItemStack(item, count));
        }
        updateAffordable();
    }

    /** Cabeza y manos aceptan cualquier cosa (el Abanderado lleva el estandarte en la cabeza). */
    private static boolean fits(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) return true;
        return Mob.getEquipmentSlotForItem(stack) == slot;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY; // sin shift-click: los espacios fantasma se llenan a mano
    }

    @Override
    public boolean stillValid(Player p) {
        if (p.distanceToSqr(campPos.getX() + 0.5, campPos.getY() + 0.5, campPos.getZ() + 0.5) > CampTabs.REACH * CampTabs.REACH) {
            return false;
        }
        return !server || p.level().getBlockEntity(campPos) instanceof CampBlockEntity;
    }

    /** Espacio de plantilla: no se puede sacar ni poner nada de verdad (lo maneja {@link #ghostClick}). */
    private static final class GhostSlot extends Slot {
        GhostSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    @Nullable
    public ClassInfo current() {
        return classes.isEmpty() ? null : classes.get(selectedClass());
    }
}
