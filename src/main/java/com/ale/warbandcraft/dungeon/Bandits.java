package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * H25: los bandidos asaltan tus envíos (H13) cada tanto, solo si su guarida queda cerca del camino (pedido de Ale).
 * Al llegar un envío se mira el trazado de las rutas por donde fue; con una guarida activa a ≤ {@link #AMBUSH_RANGE} hay
 * {@link #AMBUSH_CHANCE} de asalto: se pierde parte de los ítems (van al cofre del jefe de la guarida: si la limpiás, los
 * recuperás) y alguno de los soldados. Limpiar la guarida lo corta.
 */
public final class Bandits {
    /** Distancia del camino a la guarida para que pueda asaltarlo. */
    public static int AMBUSH_RANGE = 80;
    /** Chance de asalto de un envío que pasa cerca de una guarida. */
    public static double AMBUSH_CHANCE = 0.25;
    /** Parte de cada pila de ítems que se llevan. */
    public static double ITEM_SHARE = 0.4;
    /** Chance de perder a cada soldado del envío. */
    public static double SOLDIER_LOSS = 0.15;
    /** Lo robado que guarda una guarida, como mucho (pilas). */
    public static int MAX_STOLEN = 54;

    /** Pedido de Ale: lo que pega un bandido (a vos, a tus soldados, a cualquiera); llevan equipo peor y pegaban de más. */
    public static double DAMAGE_FACTOR = 0.6;
    /** Pedido de Ale: chance de que un bandido de guarida que matás vos (o tus soldados) suelte 1 esmeralda (los de patrulla ya sueltan). */
    public static double DROP_EMERALD_CHANCE = 0.35;

    public static boolean isBandit(net.minecraft.world.entity.Entity e) {
        return e instanceof com.talhanation.recruits.entities.AbstractRecruitEntity
                && Dungeons.BANDITS.toString().equals(e.getPersistentData().getString(com.ale.warbandcraft.village.VillageManager.TAG_FACTION));
    }

    /** Daño de un bandido, rebajado. */
    public static float damage(@javax.annotation.Nullable net.minecraft.world.entity.Entity attacker, float amount) {
        return isBandit(attacker) ? (float) (amount * DAMAGE_FACTOR) : amount;
    }

    /** Un bandido cayó: cada tanto suelta una esmeralda (si no es de patrulla, que ya suelta las suyas). */
    public static void dropEmerald(ServerLevel level, com.talhanation.recruits.entities.AbstractRecruitEntity dead, @javax.annotation.Nullable net.minecraft.world.entity.Entity killer) {
        if (!isBandit(dead) || dead.getPersistentData().contains(com.ale.warbandcraft.invasion.Patrols.TAG_PATROL)) return;
        if (com.ale.warbandcraft.raid.RaidManager.responsiblePlayer(killer) == null) return;
        if (level.random.nextDouble() < DROP_EMERALD_CHANCE) dead.spawnAtLocation(new ItemStack(net.minecraft.world.item.Items.EMERALD));
    }

    /** Envíos que ya se tiraron (en memoria: un envío que espera a que se cargue el Campamento no se asalta dos veces). */
    private static final Set<UUID> ROLLED = new HashSet<>();

    private Bandits() {}

    /** Llegó {@code s}: ¿lo asaltaron en el camino? Devuelve el envío como queda (el mismo si no pasó nada). */
    public static RouteData.Shipment ambush(ServerLevel level, RouteData.Shipment s) {
        if (level.dimension() != Level.OVERWORLD || !ROLLED.add(s.id())) return s;
        DungeonData.Dungeon lair = lairNear(level, path(level, s));
        if (lair == null) return s;
        RandomSource random = level.getRandom();
        if (random.nextDouble() >= AMBUSH_CHANCE) return s;

        List<ItemStack> kept = new ArrayList<>();
        int stolenItems = 0;
        for (ItemStack stack : s.items()) {
            int take = stack.getCount() == 1 ? (random.nextDouble() < ITEM_SHARE ? 1 : 0) : (int) Math.floor(stack.getCount() * ITEM_SHARE);
            ItemStack rest = stack.copy();
            if (take > 0) {
                ItemStack loot = rest.split(take);
                stolenItems += take;
                if (lair.stolen.size() < MAX_STOLEN) lair.stolen.add(loot);
            }
            if (!rest.isEmpty()) kept.add(rest);
        }
        List<BarracksData.Soldier> soldiers = new ArrayList<>();
        int lost = 0;
        for (BarracksData.Soldier sheet : s.soldiers()) {
            if (random.nextDouble() < SOLDIER_LOSS) lost++;
            else soldiers.add(sheet);
        }
        if (stolenItems == 0 && lost == 0) return s;
        DungeonData.get(level).changed();
        RouteData.Shipment after = new RouteData.Shipment(s.id(), s.owner(), s.from(), s.to(), s.toCamp(), s.departTick(), s.arriveTick(),
                soldiers, kept);
        RouteData.get(level).replace(after);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(s.owner());
        if (player != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.dungeon.ambushed", lair.name, Dungeons.where(lair.entrance),
                    stolenItems, lost).withStyle(ChatFormatting.RED));
        }
        lair.known.add(s.owner());   // ahora sabés dónde están
        return after;
    }

    /** Los puntos por donde fue un envío: el trazado de sus rutas, o la línea recta si ya no están. */
    static List<BlockPos> path(ServerLevel level, RouteData.Shipment s) {
        List<BlockPos> out = new ArrayList<>();
        Routes.Destination dest = Routes.destination(level, s.owner(), s.from(), s.to());
        if (dest != null) for (RouteData.Route r : dest.path()) out.addAll(r.points());
        if (out.size() < 2) {
            out.clear();
            out.add(s.from());
            out.add(s.to());
        }
        return out;
    }

    /** La guarida activa más cercana a ese camino (a ≤ {@link #AMBUSH_RANGE}), o null. */
    static DungeonData.Dungeon lairNear(ServerLevel level, List<BlockPos> path) {
        DungeonData.Dungeon best = null;
        double bestSq = (double) AMBUSH_RANGE * AMBUSH_RANGE;
        for (DungeonData.Dungeon d : DungeonData.get(level).all()) {
            if (!d.activeLair()) continue;
            for (int i = 0; i + 1 < path.size(); i++) {
                double sq = segmentDistSq(d.entrance, path.get(i), path.get(i + 1));
                if (sq <= bestSq) {
                    bestSq = sq;
                    best = d;
                }
            }
        }
        return best;
    }

    /** Distancia² horizontal de {@code p} al tramo a-b. */
    static double segmentDistSq(BlockPos p, BlockPos a, BlockPos b) {
        double ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ(), px = p.getX(), pz = p.getZ();
        double dx = bx - ax, dz = bz - az;
        double len = dx * dx + dz * dz;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len));
        double cx = ax + t * dx - px, cz = az + t * dz - pz;
        return cx * cx + cz * cz;
    }

    public static void clearMemory() {
        ROLLED.clear();
    }
}
