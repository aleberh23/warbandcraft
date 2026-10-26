package com.ale.warbandcraft.worker;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Organizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;

/**
 * El trabajo del Organizador: de día arma el plan ({@link Organizer#plan}) y hace viajes de verdad: camina hasta el cofre
 * (o el Campamento) de donde saca, abre la tapa, levanta hasta {@link Organizer#STACKS_PER_TRIP} pilas (la primera se ve en
 * la mano), camina hasta el cofre que les toca, las deja y lo ordena adentro. Trabado {@link #STUCK_TICKS} sin acercarse,
 * aparece al lado del cofre. Si lo cortan con algo en la mano (noche, huye), lo deja en el Campamento.
 */
public class OrganizerGoal extends Goal {
    /** A esta distancia del cofre ya llega; cuánto queda con la tapa abierta; cada cuánto rehace el plan. */
    public static double REACH = 2.5;
    public static int OPEN_TICKS = 12, PLAN_TICKS = 200;
    /** Sin acercarse 1 bloque en tanto, aparece al lado del cofre. */
    public static int STUCK_TICKS = 20 * 15;

    private enum Phase { IDLE, TO_SOURCE, AT_SOURCE, TO_TARGET, AT_TARGET }

    private final OrganizerEntity org;
    @Nullable
    private Organizer.Plan plan;
    private long planTick = Long.MIN_VALUE;
    @Nullable
    private Organizer.Task task;
    private Phase phase = Phase.IDLE;
    private int wait, repath, check;
    private double best;
    private int bestSince;
    private boolean hasWork;

    public OrganizerGoal(OrganizerEntity org) {
        this.org = org;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    static CampBlockEntity camp(ServerLevel level, OrganizerEntity org) {
        BlockPos pos = org.getCamp();
        if (pos == null || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof CampBlockEntity c ? c : null;
    }

    private boolean awake() {
        return org.shouldWork() && !org.needsToSleep() && !org.isFleeing;
    }

    @Override
    public boolean canUse() {
        if (!(org.level() instanceof ServerLevel level) || !awake()) return false;
        CampBlockEntity camp = camp(level, org);
        if (camp == null) return false;
        if (!org.carried.isEmpty()) return true;
        if (++check >= 20) {   // ¿hay algo para mover? (cada 1 s)
            check = 0;
            Organizer.Plan p = plan(level, camp);
            hasWork = Organizer.next(level, camp, p) != null;
        }
        return hasWork;
    }

    @Override
    public boolean canContinueToUse() {
        return org.level() instanceof ServerLevel level && awake() && camp(level, org) != null && (phase != Phase.IDLE || !org.carried.isEmpty() || hasWork);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        phase = org.carried.isEmpty() ? Phase.IDLE : Phase.TO_TARGET;
        task = null;
    }

    @Override
    public void stop() {
        org.getNavigation().stop();
        if (org.level() instanceof ServerLevel level && !org.carried.isEmpty()) dropAtCamp(level, org);
        if (phase == Phase.AT_SOURCE && task != null && org.level() instanceof ServerLevel level) closeLid(level, task.from());
        if (phase == Phase.AT_TARGET && org.carryTo != null && org.level() instanceof ServerLevel level) closeLid(level, org.carryTo);
        phase = Phase.IDLE;
        task = null;
        hasWork = false;
    }

    /** Lo que lleva, al Campamento (o al piso si no entra). */
    static void dropAtCamp(ServerLevel level, OrganizerEntity org) {
        CampBlockEntity camp = camp(level, org);
        for (ItemStack s : org.carried) {
            ItemStack left = camp != null ? camp.insert(s) : s;
            if (!left.isEmpty()) org.spawnAtLocation(left);
        }
        org.carried.clear();
        org.carryTo = null;
        org.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private Organizer.Plan plan(ServerLevel level, CampBlockEntity camp) {
        if (plan == null || level.getGameTime() - planTick >= PLAN_TICKS) {
            plan = Organizer.plan(level, camp, org.assign);
            planTick = level.getGameTime();
            org.assign.clear();
            org.assign.putAll(plan.assign());
            org.missing = plan.missing().isEmpty() ? EnumSet.noneOf(Organizer.Category.class) : EnumSet.copyOf(plan.missing());
        }
        return plan;
    }

    @Override
    public void tick() {
        if (!(org.level() instanceof ServerLevel level)) return;
        CampBlockEntity camp = camp(level, org);
        if (camp == null) return;
        Organizer.Plan p = plan(level, camp);
        switch (phase) {
            case IDLE -> {
                task = Organizer.next(level, camp, p);
                if (task == null) {
                    hasWork = false;
                    return;
                }
                phase = Phase.TO_SOURCE;
                resetStuck();
            }
            case TO_SOURCE -> {
                if (walk(level, task.from())) {
                    org.getNavigation().stop();
                    openLid(level, camp, p, task.from());
                    wait = OPEN_TICKS;
                    phase = Phase.AT_SOURCE;
                }
            }
            case AT_SOURCE -> {
                org.getLookControl().setLookAt(Vec3.atCenterOf(task.from()));
                if (--wait > 0) return;
                List<Container> to = Organizer.at(level, camp, p, task.to());
                int room = Organizer.room(to, task.sample());
                org.carried.clear();
                if (room > 0) org.carried.addAll(Organizer.pick(Organizer.at(level, camp, p, task.from()), task.sample(), room));
                org.swing(InteractionHand.MAIN_HAND);
                closeLid(level, task.from());
                if (org.carried.isEmpty()) {
                    phase = Phase.IDLE;
                    return;
                }
                org.carryTo = task.to();
                org.setItemSlot(EquipmentSlot.MAINHAND, org.carried.get(0).copyWithCount(1));
                phase = Phase.TO_TARGET;
                resetStuck();
            }
            case TO_TARGET -> {
                if (org.carryTo == null) {
                    dropAtCamp(level, org);
                    phase = Phase.IDLE;
                    return;
                }
                if (walk(level, org.carryTo)) {
                    org.getNavigation().stop();
                    openLid(level, camp, p, org.carryTo);
                    wait = OPEN_TICKS;
                    phase = Phase.AT_TARGET;
                }
            }
            case AT_TARGET -> {
                org.getLookControl().setLookAt(Vec3.atCenterOf(org.carryTo));
                if (--wait > 0) return;
                deliver(level, camp, p, org);
                org.swing(InteractionHand.MAIN_HAND);
                closeLid(level, org.carryTo);
                org.carryTo = null;
                phase = Phase.IDLE;
                hasWork = true;
            }
        }
    }

    /** Deja lo que lleva en el destino y lo ordena adentro; lo que no entra, al Campamento. */
    static void deliver(ServerLevel level, CampBlockEntity camp, Organizer.Plan p, OrganizerEntity org) {
        List<Container> to = Organizer.at(level, camp, p, org.carryTo);
        for (ItemStack s : org.carried) {
            ItemStack left = to.isEmpty() ? s : Organizer.put(to, s);
            if (!left.isEmpty()) left = camp.insert(left);
            if (!left.isEmpty()) org.spawnAtLocation(left);
        }
        org.carried.clear();
        if (!org.carryTo.equals(camp.getBlockPos())) Organizer.sort(to);
        org.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /** Camina hasta el cofre. True = ya llegó. */
    private boolean walk(ServerLevel level, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        double d = org.position().add(0, 0.5, 0).distanceTo(c);
        if (d <= REACH) return true;
        if (d < best - 1) {
            best = d;
            bestSince = 0;
        } else if (++bestSince > STUCK_TICKS) {   // trabado: aparece al lado
            BlockPos spot = standNear(level, pos);
            org.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            org.getNavigation().stop();
            resetStuck();
            return true;
        }
        if (--repath <= 0) {
            repath = 20;
            Path path = org.getNavigation().createPath(pos, 1);
            if (path != null) org.getNavigation().moveTo(path, 0.6);
            else org.getNavigation().moveTo(c.x, c.y, c.z, 0.6);
        }
        return false;
    }

    private void resetStuck() {
        best = Double.MAX_VALUE;
        bestSince = 0;
        repath = 0;
    }

    private static BlockPos standNear(ServerLevel level, BlockPos pos) {
        for (int r = 1; r <= 2; r++) {
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) return p.immutable();
            }
        }
        return pos.above();
    }

    private void openLid(ServerLevel level, CampBlockEntity camp, Organizer.Plan p, BlockPos pos) {
        Organizer.Unit u = p.unit(pos);
        if (u != null) Organizer.lid(level, u.parts(), true);
        else if (pos.equals(camp.getBlockPos())) level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_OPEN,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.4F, 1.0F);
    }

    private void closeLid(ServerLevel level, BlockPos pos) {
        Organizer.Unit u = plan == null ? null : plan.unit(pos);
        if (u != null) Organizer.lid(level, u.parts(), false);
    }

    /**
     * Para las pruebas: hace los viajes en el acto (sin caminar), hasta {@code max} o hasta que esté todo en su lugar.
     * Devuelve cuántos hizo.
     */
    public static int runNow(ServerLevel level, OrganizerEntity org, int max) {
        CampBlockEntity camp = camp(level, org);
        if (camp == null) return 0;
        int n = 0;
        for (; n < max; n++) {
            Organizer.Plan p = Organizer.plan(level, camp, org.assign);
            org.assign.clear();
            org.assign.putAll(p.assign());
            org.missing = p.missing().isEmpty() ? EnumSet.noneOf(Organizer.Category.class) : EnumSet.copyOf(p.missing());
            Organizer.Task t = Organizer.next(level, camp, p);
            if (t == null) break;
            int room = Organizer.room(Organizer.at(level, camp, p, t.to()), t.sample());
            org.carried.clear();
            org.carried.addAll(Organizer.pick(Organizer.at(level, camp, p, t.from()), t.sample(), room));
            if (org.carried.isEmpty()) break;
            org.carryTo = t.to();
            deliver(level, camp, p, org);
            org.carryTo = null;
        }
        return n;
    }
}
