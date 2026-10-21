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
 * H16: el Herrero, un trabajador nuestro hecho sobre la base de Villager Workers (no copia su código: hereda de su clase
 * base, así duerme, huye y se maneja como los demás). Trabaja de día parado en su lugar de trabajo (al lado de un
 * yunque): cada {@link SmithWorkGoal#WORK_TICKS} convierte 1 lingote de hierro + 1 carbón del almacén de su aldea o
 * Campamento en {@link #ARMAMENT_PER_IRON} de armamento, hasta {@link #IRON_PER_DAY} lingotes por día.
 */
public class SmithEntity extends AbstractWorkerEntity {
    public static int IRON_PER_DAY = 8, ARMAMENT_PER_IRON = 2;

    /** Dónde trabaja (se para ahí, al lado del yunque) y el Campamento del que saca y al que deja. */
    @Nullable
    private BlockPos workplace;
    @Nullable
    private BlockPos camp;
    private long forgeDay = Long.MIN_VALUE;
    /** Le marcaste el lugar a mano (no se muda solo a la Herrería). */
    private boolean manualWorkplace;
    private int forgedToday;

    public SmithEntity(EntityType<? extends AbstractWorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder setAttributes() {
        return CookEntity.setAttributes();   // los mismos números que los trabajadores de Workers
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new SmithWorkGoal(this));
    }

    @Override
    public void initSpawn() {
        super.initSpawn();
        setCustomName(Component.translatable("entity.warbandcraft.smith"));
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
    }

    @Override
    public boolean shouldLoadChunk() {
        return false;
    }

    @Override
    @Nullable
    public AbstractWorkAreaEntity getCurrentWorkArea() {
        return null;   // no usa zonas de Workers: su lugar es {@link #getWorkplace()}
    }

    @Override
    public Predicate<ItemEntity> getAllowedItems() {
        return e -> false;
    }

    @Override
    public List<Item> inventoryInputHelp() {
        return List.of(Items.IRON_INGOT, Items.COAL, Items.CHARCOAL);
    }

    @Nullable
    public BlockPos getWorkplace() {
        return workplace;
    }

    public void setWorkplace(@Nullable BlockPos pos) {
        this.workplace = pos;
    }

    public boolean isManualWorkplace() {
        return manualWorkplace;
    }

    public void setManualWorkplace(boolean manual) {
        this.manualWorkplace = manual;
    }

    @Nullable
    public BlockPos getCamp() {
        return camp;
    }

    public void setCamp(@Nullable BlockPos camp) {
        this.camp = camp;
    }

    /** Cuántos lingotes puede trabajar todavía hoy. */
    public int ironLeftToday(long day) {
        if (day != forgeDay) {
            forgeDay = day;
            forgedToday = 0;
        }
        return Math.max(0, IRON_PER_DAY - forgedToday);
    }

    public void forged() {
        forgedToday++;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (workplace != null) tag.putLong("WbWorkplace", workplace.asLong());
        if (camp != null) tag.putLong("WbCamp", camp.asLong());
        tag.putLong("WbForgeDay", forgeDay);
        tag.putInt("WbForged", forgedToday);
        tag.putBoolean("WbManual", manualWorkplace);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        workplace = tag.contains("WbWorkplace") ? BlockPos.of(tag.getLong("WbWorkplace")) : null;
        camp = tag.contains("WbCamp") ? BlockPos.of(tag.getLong("WbCamp")) : null;
        forgeDay = tag.contains("WbForgeDay") ? tag.getLong("WbForgeDay") : Long.MIN_VALUE;
        forgedToday = tag.getInt("WbForged");
        manualWorkplace = tag.getBoolean("WbManual");
    }
}
