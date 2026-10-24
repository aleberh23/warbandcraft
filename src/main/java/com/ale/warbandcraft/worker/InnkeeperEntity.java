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
        this.goalSelector.addGoal(1, new InnkeeperGoal(this));   // antes que buscar/dejar cosas (2): de día no se va del mostrador
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
        boolean owner = com.ale.warbandcraft.faction.Allies.same(getOwnerUUID(), player.getUUID());
        if (owner && player.isShiftKeyDown()) return super.mobInteract(player, hand);
        if (player instanceof net.minecraft.server.level.ServerPlayer sp && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            com.ale.warbandcraft.dungeon.Rumors.interact(sp, this);
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level().isClientSide);
    }

    /** Modificador que lo deja quieto mientras atiende (UUID fijo; transitorio: no se guarda). */
    private static final java.util.UUID STAY_ID = java.util.UUID.fromString("5c1f2e7a-3b4d-4e8f-9a10-7b2c3d4e5f60");
    /** Ticks que lleva sin llegar a su lugar. */
    private int awayTicks;

    /**
     * Pedido de Ale: de día el Tabernero está fijo en la taberna. Se mira él solo cada 1 s (sin buscar entidades): en su
     * lugar queda quieto (velocidad 0); si no llega en {@link com.ale.warbandcraft.village.Tavern#WALK_IN_TICKS} o se fue
     * lejos, aparece ahí. De noche, huyendo o peleando se mueve normal.
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || tickCount % 20 != 0) return;
        BlockPos place = workplace;
        boolean always = getPersistentData().contains(com.ale.warbandcraft.village.WorldTaverns.TAG)
                || getPersistentData().contains(com.ale.warbandcraft.village.FactionInn.TAG);   // el de una taberna del mundo o de facción, también de noche
        if (place == null || (!always && !level().isDay()) || isFleeing || getTarget() != null || isPassenger()) {
            stay(false);
            awayTicks = 0;
            return;
        }
        net.minecraft.world.phys.Vec3 spot = net.minecraft.world.phys.Vec3.atBottomCenterOf(place);
        double d = position().distanceToSqr(spot);
        if (d <= 2.25) {
            awayTicks = 0;
            stay(true);
            return;
        }
        stay(false);
        awayTicks += 20;
        int far = com.ale.warbandcraft.village.Tavern.TELEPORT_DISTANCE;
        if (awayTicks > com.ale.warbandcraft.village.Tavern.WALK_IN_TICKS || d > (double) far * far) {
            if (isSleeping()) stopSleeping();
            teleportTo(spot.x, spot.y, spot.z);
            getNavigation().stop();
            awayTicks = 0;
            stay(true);
        }
    }

    private void stay(boolean on) {
        var speed = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null || (speed.getModifier(STAY_ID) != null) == on) return;
        if (on) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(STAY_ID, "warbandcraft innkeeper stay",
                    -1.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL));
            getNavigation().stop();
        } else {
            speed.removeModifier(STAY_ID);
        }
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
