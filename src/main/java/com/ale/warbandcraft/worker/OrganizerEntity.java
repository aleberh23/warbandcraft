package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.camp.Organizer;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.CookEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * El Organizador (pedido de Ale), otro trabajador nuestro sobre la base de Villager Workers (como el Herrero): de día lleva
 * de cofre en cofre lo del almacén de su Campamento ({@link Organizer}, {@link OrganizerGoal}). Guarda qué categoría tiene
 * cada cofre (así no los cambia de un día para el otro) y lo que lleva en la mano si lo cortan a mitad de viaje.
 */
public class OrganizerEntity extends AbstractWorkerEntity {
    @Nullable
    private BlockPos camp;
    /** Qué guarda cada cofre (por la mitad más chica). */
    final Map<BlockPos, Organizer.Category> assign = new HashMap<>();
    /** Lo que lleva y adónde. */
    final List<ItemStack> carried = new ArrayList<>();
    @Nullable
    BlockPos carryTo;
    /** Lo que no entra en ningún lado (en memoria, del último plan). */
    Set<Organizer.Category> missing = EnumSet.noneOf(Organizer.Category.class);

    public OrganizerEntity(EntityType<? extends AbstractWorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder setAttributes() {
        return CookEntity.setAttributes();
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new OrganizerGoal(this));
    }

    @Override
    public void initSpawn() {
        super.initSpawn();
        setCustomName(Component.translatable("entity.warbandcraft.organizer"));
    }

    @Override
    public boolean shouldLoadChunk() {
        return false;
    }

    @Override
    @Nullable
    public AbstractWorkAreaEntity getCurrentWorkArea() {
        return null;   // trabaja en los cofres de su Campamento
    }

    @Override
    public Predicate<ItemEntity> getAllowedItems() {
        return e -> false;
    }

    @Override
    public List<Item> inventoryInputHelp() {
        return List.of(Items.BREAD);
    }

    @Override
    public void remove(RemovalReason reason) {
        // Muerto o despedido con algo en la mano: al Campamento (o al piso)
        if (reason.shouldDestroy() && !carried.isEmpty() && level() instanceof net.minecraft.server.level.ServerLevel sl) OrganizerGoal.dropAtCamp(sl, this);
        super.remove(reason);
    }

    @Nullable
    public BlockPos getCamp() {
        return camp;
    }

    public void setCamp(@Nullable BlockPos camp) {
        if (camp == null || !camp.equals(this.camp)) assign.clear();
        this.camp = camp;
    }

    public Set<Organizer.Category> getMissing() {
        return missing;
    }

    public Map<BlockPos, Organizer.Category> getAssign() {
        return assign;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (camp != null) tag.putLong("WbCamp", camp.asLong());
        ListTag a = new ListTag();
        assign.forEach((p, c) -> {
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", p.asLong());
            t.putString("Cat", c.key);
            a.add(t);
        });
        tag.put("WbSort", a);
        ListTag carry = new ListTag();
        for (ItemStack s : carried) carry.add(s.save(new CompoundTag()));
        tag.put("WbCarried", carry);
        if (carryTo != null) tag.putLong("WbCarryTo", carryTo.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        camp = tag.contains("WbCamp") ? BlockPos.of(tag.getLong("WbCamp")) : null;
        assign.clear();
        for (Tag t : tag.getList("WbSort", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Organizer.Category cat = Organizer.Category.byKey(c.getString("Cat"));
            if (cat != null) assign.put(BlockPos.of(c.getLong("Pos")), cat);
        }
        carried.clear();
        for (Tag t : tag.getList("WbCarried", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.of((CompoundTag) t);
            if (!s.isEmpty()) carried.add(s);
        }
        carryTo = tag.contains("WbCarryTo") ? BlockPos.of(tag.getLong("WbCarryTo")) : null;
    }
}
