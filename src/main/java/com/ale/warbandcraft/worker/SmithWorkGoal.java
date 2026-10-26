package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Scrap;
import com.ale.warbandcraft.kit.Armament;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * El trabajo del Herrero (H16): de día va a su lugar de trabajo y martilla. Cada {@link #WORK_TICKS} saca 1 lingote de
 * hierro y 1 carbón (o carbón vegetal) del almacén y deja {@link SmithEntity#ARMAMENT_PER_IRON} de armamento. Si hay
 * piezas marcadas para desguazar en el almacén (Campamento o vinculados), esa vuelta desguaza {@link Scrap#PIECES_PER_JOB} en vez
 * del lingote, y no cuenta para el tope del día.
 */
public class SmithWorkGoal extends Goal {
    /** Cada cuánto termina un lingote (30 s) y a qué distancia del lugar cuenta como "llegó". */
    public static int WORK_TICKS = 20 * 30;
    private static double ARRIVE = 2.0;

    private final SmithEntity smith;
    private int work;
    private int repath;

    public SmithWorkGoal(SmithEntity smith) {
        this.smith = smith;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Cada tanto: si la aldea ya tiene la Herrería (H12), se muda a trabajar ahí (salvo lugar marcado a mano). */
    private int relocate;

    private void relocate(ServerLevel level) {
        if (smith.isManualWorkplace() || smith.getCamp() == null) return;
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageCamps.villageOf(level, smith.getCamp());
        if (v == null) return;
        BlockPos smithy = com.ale.warbandcraft.village.WorkZones.smithyPlace(level, v);
        if (smithy != null && (smith.getWorkplace() == null || smith.getWorkplace().distSqr(smithy) > 9)) smith.setWorkplace(smithy);
    }

    @Override
    public boolean canUse() {
        if (smith.level() instanceof ServerLevel lv && ++relocate >= 200) {
            relocate = 0;
            relocate(lv);
        }
        if (!(smith.level() instanceof ServerLevel level) || smith.getWorkplace() == null || !smith.shouldWork()) return false;
        if (smith.needsToSleep() || smith.isFleeing) return false;
        if (smith.ironLeftToday(level.getDayTime() / 24000L) > 0) return true;
        // Sin lingotes del día: sigue si hay algo marcado para desguazar (eso no tiene tope). Se mira cada 1 s.
        if (++scrapCheck >= 20) {
            scrapCheck = 0;
            CampBlockEntity camp = camp(level, smith);
            scrapWaiting = camp != null && Scrap.any(camp);
        }
        return scrapWaiting;
    }

    private int scrapCheck;
    private boolean scrapWaiting;

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        smith.getNavigation().stop();
    }

    @Override
    public void tick() {
        BlockPos place = smith.getWorkplace();
        if (place == null || !(smith.level() instanceof ServerLevel level)) return;
        Vec3 spot = Vec3.atBottomCenterOf(place);
        if (smith.position().distanceToSqr(spot) > ARRIVE * ARRIVE) {
            if (--repath <= 0) {
                repath = 20;
                smith.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.6);
            }
            return;
        }
        smith.getNavigation().stop();
        BlockPos anvil = anvilNear(level, place);
        if (anvil != null) smith.getLookControl().setLookAt(Vec3.atCenterOf(anvil));
        if (++work % 20 == 0) {
            smith.swing(InteractionHand.MAIN_HAND);
            if (work % 40 == 0) level.playSound(null, place, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.25F, 1.0F);
        }
        if (work >= WORK_TICKS) {
            work = 0;
            forge(level);
        }
    }

    /** Un lingote: del almacén, hierro + carbón → armamento. */
    public boolean forge(ServerLevel level) {
        CampBlockEntity camp = camp(level, smith);
        if (camp == null) return false;
        int before = camp.count(Armament.item());
        try {
            return forgeWith(level, camp);
        } finally {   // para /warband aportes
            int got = camp.count(Armament.item()) - before;
            if (got > 0) com.ale.warbandcraft.village.WorkerOutput.log(level, smith.getUUID(), new ItemStack(Armament.item(), got));
        }
    }

    private boolean forgeWith(ServerLevel level, CampBlockEntity camp) {
        // Pedido de Ale ("cuando se terminan las piezas se queda parado"): desguazar no gasta los lingotes del día
        if (Scrap.work(camp, Scrap.PIECES_PER_JOB) > 0) return true;
        scrapWaiting = false;
        if (smith.ironLeftToday(level.getDayTime() / 24000L) <= 0) return false;
        ItemStack left = workOnce(camp);
        if (left == null) return false;
        if (!left.isEmpty()) smith.spawnAtLocation(left);
        smith.forged();
        return true;
    }

    /**
     * Una vuelta de trabajo con el almacén {@code camp}: desguaza lo marcado o, si no hay, 1 lingote + 1 carbón → armamento.
     * Devuelve lo que no entró en el almacén (vacío = todo bien), o null si no había con qué trabajar.
     */
    @org.jetbrains.annotations.Nullable
    public static ItemStack workOnce(CampBlockEntity camp) {
        if (Scrap.work(camp, Scrap.PIECES_PER_JOB) > 0) return ItemStack.EMPTY;   // primero lo marcado para desguazar
        Item coal = camp.count(Items.COAL) > 0 ? Items.COAL : camp.count(Items.CHARCOAL) > 0 ? Items.CHARCOAL : null;
        if (coal == null || camp.count(Items.IRON_INGOT) <= 0) return null;
        camp.take(Items.IRON_INGOT, 1);
        camp.take(coal, 1);
        return camp.insert(new ItemStack(Armament.item(), SmithEntity.ARMAMENT_PER_IRON));
    }

    public static CampBlockEntity camp(ServerLevel level, SmithEntity smith) {
        BlockPos pos = smith.getCamp();
        if (pos == null || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof CampBlockEntity c ? c : null;
    }

    public static BlockPos anvilNear(ServerLevel level, BlockPos place) {
        for (BlockPos p : BlockPos.betweenClosed(place.offset(-2, -1, -2), place.offset(2, 1, 2))) {
            if (level.getBlockState(p).is(Blocks.ANVIL) || level.getBlockState(p).is(Blocks.CHIPPED_ANVIL)
                    || level.getBlockState(p).is(Blocks.DAMAGED_ANVIL)) return p.immutable();
        }
        return null;
    }
}
