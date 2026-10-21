package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.market.Market;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import com.talhanation.workers.entities.CookEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * H24: el Mercader, otro trabajador nuestro sobre la base de Villager Workers (como el Herrero y el Tabernero). Atiende
 * un puesto del mercado de su aldea. Click derecho: al dueño le abre la pantalla Mercado (agachado, la de Workers); a
 * otro jugador, la de comercio de vanilla con lo que sobra en el almacén ({@link Market}).
 */
public class MerchantEntity extends AbstractWorkerEntity {
    /** Dónde atiende (en el mercado) y el Campamento de su aldea. */
    @Nullable
    private BlockPos workplace;
    @Nullable
    private BlockPos camp;

    public MerchantEntity(EntityType<? extends AbstractWorkerEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder setAttributes() {
        return CookEntity.setAttributes();
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(3, new MerchantGoal(this));
    }

    @Override
    public void initSpawn() {
        super.initSpawn();
        setCustomName(Component.translatable("entity.warbandcraft.merchant"));
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND && com.ale.warbandcraft.market.FactionMarket.is(this)) {
            com.ale.warbandcraft.market.FactionMarket.interact(sp, this);   // mercader de una aldea de facción
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) {
            boolean owner = java.util.Objects.equals(getOwnerUUID(), player.getUUID());
            if (!owner || !player.isShiftKeyDown()) {
                Market.interact(sp, this);
                return InteractionResult.SUCCESS;
            }
        } else if (level().isClientSide && !(player.isShiftKeyDown() && java.util.Objects.equals(getOwnerUUID(), player.getUUID()))) {
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean shouldLoadChunk() {
        return false;
    }

    @Override
    @Nullable
    public AbstractWorkAreaEntity getCurrentWorkArea() {
        return null;   // su lugar es el mercado (getWorkplace)
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

    /** Está atendiendo: de día, despierto y cerca de su puesto. */
    public boolean attending() {
        if (!isAlive() || isSleeping() || needsToSleep() || workplace == null) return false;
        return position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(workplace)) <= Market.ATTEND_RANGE * Market.ATTEND_RANGE;
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
