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
 * H19: el Tabernero, otro trabajador nuestro sobre la base de Villager Workers (como el Herrero). De día atiende la
 * taberna de su aldea; sin tabernero no llegan reclutas ({@link com.ale.warbandcraft.village.Tavern}).
 */
public class InnkeeperEntity extends AbstractWorkerEntity {
    /** Dónde atiende (adentro de la taberna) y el Campamento de su aldea. */
    @Nullable
    private BlockPos workplace;
    @Nullable
    private BlockPos camp;

    public InnkeeperEntity(EntityType<? extends AbstractWorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder setAttributes() {
        return CookEntity.setAttributes();
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new InnkeeperGoal(this));
    }

    @Override
    public void initSpawn() {
        super.initSpawn();
        setCustomName(Component.translatable("entity.warbandcraft.innkeeper"));
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.HONEY_BOTTLE));
    }

    /** H25: click (el dueño, sin agacharse; o cualquier otro) = rumores y trabajos. Agachado, el dueño ve la pantalla de Workers. */
    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        boolean owner = java.util.Objects.equals(getOwnerUUID(), player.getUUID());
        if (owner && player.isShiftKeyDown()) return super.mobInteract(player, hand);
        if (player instanceof net.minecraft.server.level.ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            com.ale.warbandcraft.dungeon.Rumors.interact(sp, this);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean shouldLoadChunk() {
        return false;
    }

    @Override
    @Nullable
    public AbstractWorkAreaEntity getCurrentWorkArea() {
        return null;   // su lugar es la taberna (getWorkplace)
    }

    @Override
    public Predicate<ItemEntity> getAllowedItems() {
        return e -> false;
    }

    @Override
    public List<Item> inventoryInputHelp() {
        return List.of(Items.BREAD);
    }

    @Nullable
    public BlockPos getWorkplace() {
        return workplace;
    }

    public void setWorkplace(@Nullable BlockPos pos) {
        this.workplace = pos;
    }

    @Nullable
    public BlockPos getCamp() {
        return camp;
    }

    public void setCamp(@Nullable BlockPos camp) {
        this.camp = camp;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (workplace != null) tag.putLong("WbWorkplace", workplace.asLong());
        if (camp != null) tag.putLong("WbCamp", camp.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        workplace = tag.contains("WbWorkplace") ? BlockPos.of(tag.getLong("WbWorkplace")) : null;
        camp = tag.contains("WbCamp") ? BlockPos.of(tag.getLong("WbCamp")) : null;
    }
}
