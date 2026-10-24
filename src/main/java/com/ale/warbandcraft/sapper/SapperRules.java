package com.ale.warbandcraft.sapper;

import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampFinder;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reglas comunes del Zapador (H6): petardo (TNT en una puerta o muro) y cañón de campaña.
 * Solo se pueden usar contra aldeas o castillos que no sean tuyos; usarlos empieza el ataque.
 */
public final class SapperRules {
    /** Persistent data del Zapador: bloque a volar / lugar donde armar el cañón (BlockPos como long). */
    public static final String TAG_BLAST = "warbandcraft:blast";
    public static final String TAG_DEPLOY = "warbandcraft:deploy";
    /** Marca del TNT de un jugador con doctrina de Ingeniería: explota más fuerte. */
    public static final String TAG_STRONG_TNT = "warbandcraft:strong";
    /**
     * TNT de un asedio (zapador o cañón de la IA): en vez del cráter, abre solo este paso (BlockPos como long[]): 1 de
     * ancho y 2 de alto, atravesando el muro.
     */
    public static final String TAG_HOLE = "warbandcraft:hole";
    /** El TNT del asedio abre el paso si explota a tanto (o menos) de su primer bloque. */
    public static double HOLE_REACH = 4.5;
    /** Lo más duro que abre el TNT de asedio (igual que lo que la malla planea romper). */
    public static float HOLE_HARDNESS = 10;

    /** Hasta dónde llega una aldea o castillo desde su centro, para permitir TNT. */
    public static double TARGET_RADIUS = 48;
    /** Alcance del cañón (horizontal). */
    public static double CANNON_RANGE = 40;
    /** Lo que cuesta armar un cañón (de la mochila del Zapador o del Campamento). */
    public static final Map<Item, Integer> CANNON_COST = Map.of(Items.IRON_INGOT, 3, Items.IRON_BLOCK, 1);

    private SapperRules() {}

    public static boolean isSapper(AbstractRecruitEntity r) {
        return r instanceof SiegeEngineerEntity
                || "warbandcraft:zapador".equals(r.getPersistentData().getString(ClassAssigner.TAG_CLASS));
    }

    /** La aldea o castillo enemigo que contiene {@code pos}, o null si no se puede usar TNT ahí. */
    @Nullable
    public static VillageData.Village enemyTarget(ServerLevel level, BlockPos pos, UUID player) {
        VillageData.Village v = VillageData.get(level).nearest(pos, TARGET_RADIUS);
        if (v == null || com.ale.warbandcraft.faction.Allies.same(player, v.owner())) return null;
        return v;
    }

    /** Volar algo de una aldea es atacarla: arranca el ataque si no había uno. */
    public static void startRaid(ServerLevel level, VillageData.Village village, UUID player) {
        if (village.isConquered()) return;
        RaidManager.start(level, village, player, level.getServer().getPlayerList().getPlayer(player));
    }

    @Nullable
    public static Doctrine doctrineOf(ServerLevel level, @Nullable UUID player) {
        return player == null ? null : PlayerFactionData.get(level.getServer()).doctrine(player);
    }

    /** Saca 1 TNT de la mochila del soldado. */
    public static boolean takeTnt(AbstractRecruitEntity soldier) {
        SimpleContainer inv = soldier.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.TNT)) {
                s.shrink(1);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    public static PrimedTnt spawnTnt(ServerLevel level, Vec3 pos, Vec3 velocity, int fuse,
                                     @Nullable LivingEntity source, @Nullable Doctrine doctrine) {
        PrimedTnt tnt = new PrimedTnt(level, pos.x, pos.y, pos.z, source);
        tnt.setFuse(fuse);
        tnt.setDeltaMovement(velocity);
        if (doctrine == Doctrine.ENGINEERING) tnt.getPersistentData().putBoolean(TAG_STRONG_TNT, true);
        level.addFreshEntity(tnt);
        level.playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
        return tnt;
    }

    /** Marca el TNT con el paso que tiene que abrir. */
    public static void markHole(PrimedTnt tnt, java.util.List<net.minecraft.core.BlockPos> hole) {
        long[] a = new long[hole.size()];
        for (int i = 0; i < a.length; i++) a[i] = hole.get(i).asLong();
        tnt.getPersistentData().putLongArray(TAG_HOLE, a);
    }

    /** Explotó un TNT de asedio en {@code at}: si fue al lado del muro, se abre su paso (lo que se pueda romper). */
    public static void openHole(ServerLevel level, PrimedTnt tnt, Vec3 at) {
        long[] a = tnt.getPersistentData().getLongArray(TAG_HOLE);
        if (a.length == 0 || Vec3.atCenterOf(net.minecraft.core.BlockPos.of(a[0])).distanceTo(at) > HOLE_REACH) return;
        for (long l : a) {
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(l);
            var s = level.getBlockState(p);
            float hard = s.getDestroySpeed(level, p);
            if (s.isAir() || hard < 0 || hard > HOLE_HARDNESS || level.getBlockEntity(p) != null) continue;
            level.destroyBlock(p, false, tnt.getOwner());
        }
    }

    /** Cobra el cañón: primero de la mochila del Zapador, si no del Campamento más cercano. */
    public static boolean payCannon(ServerLevel level, AbstractRecruitEntity sapper, UUID owner) {
        SimpleContainer inv = sapper.getInventory();
        boolean inBackpack = true;
        for (Map.Entry<Item, Integer> e : CANNON_COST.entrySet()) {
            if (inv.countItem(e.getKey()) < e.getValue()) inBackpack = false;
        }
        if (inBackpack) {
            for (Map.Entry<Item, Integer> e : CANNON_COST.entrySet()) {
                int left = e.getValue();
                for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.is(e.getKey())) {
                        int n = Math.min(left, s.getCount());
                        s.shrink(n);
                        left -= n;
                    }
                }
            }
            return true;
        }
        CampBlockEntity camp = CampFinder.findNearest(level, sapper.blockPosition(), owner);
        if (camp == null) return false;
        // H10: lo que falte de hierro se paga con armamento
        if (!Armament.INSTANCE.plan(Armament.poolOf(camp), CANNON_COST).ok()) return false;
        Armament.INSTANCE.pay(camp, CANNON_COST);
        return true;
    }

    public static List<ItemStack> cannonRefund() {
        return CANNON_COST.entrySet().stream().map(e -> new ItemStack(e.getKey(), e.getValue())).toList();
    }

    public static void tell(ServerLevel level, @Nullable UUID player, Component msg) {
        if (player == null) return;
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online != null) online.displayClientMessage(msg.copy().withStyle(ChatFormatting.GOLD), true);
    }
}
