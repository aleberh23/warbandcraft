package com.ale.warbandcraft.worker;

import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.CookEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Pedido de Ale: el trabajador de Mantenimiento, otro nuestro sobre la base de Villager Workers (como el Organizador).
 * Vive en la caseta de mantenimiento de su aldea y de día la embellece ({@link com.ale.warbandcraft.village.Maintenance},
 * {@link MaintainerGoal}). Guarda la aldea (su centro) y en memoria qué está haciendo o qué le falta.
 */
public class MaintainerEntity extends AbstractWorkerEntity {
    @Nullable
    private BlockPos town;
    /** Lo que le falta en el almacén (vacío = nada) y lo que está haciendo (para el cartelito; en memoria). */
    String missing = "", doing = "";

    public MaintainerEntity(EntityType<? extends AbstractWorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder setAttributes() {
        return CookEntity.setAttributes();
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new MaintainerGoal(this));
    }

    @Override
    public void initSpawn() {
        super.initSpawn();
        setCustomName(Component.translatable("entity.warbandcraft.maintainer"));
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SHOVEL));
    }

    @Override
    public boolean shouldLoadChunk() {
        return false;
    }

    @Override
    @Nullable
    public AbstractWorkAreaEntity getCurrentWorkArea() {
        return null;   // trabaja en toda la aldea
    }

    @Override
    public Predicate<ItemEntity> getAllowedItems() {
        return e -> false;
    }

    @Override
    public List<Item> inventoryInputHelp() {
        return List.of(Items.BREAD);
    }

    /** El centro de su aldea (null = todavía sin aldea). */
    @Nullable
    public BlockPos getTown() {
        return town;
    }

    public void setTown(@Nullable BlockPos town) {
        this.town = town;
    }

    public String getMissing() {
        return missing;
    }

    public String getDoing() {
        return doing;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (town != null) tag.putLong("WbTown", town.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        town = tag.contains("WbTown") ? BlockPos.of(tag.getLong("WbTown")) : null;
    }
}
