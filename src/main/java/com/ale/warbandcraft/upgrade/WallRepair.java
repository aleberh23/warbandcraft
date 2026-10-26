package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reparar la empalizada/muralla de H12 (después de que un zapador la voló, o de que alguien la rompió): se vuelve a
 * medir el anillo y se ponen los bloques que faltan, con una obra del constructor como cualquier mejora
 * ({@link Upgrade#REPAIR}). Las torres no se miden de nuevo (sus columnas se saltean): solo el anillo.
 */
public final class WallRepair {
    /** Costo: 1 esmeralda cada tantos bloques, con un mínimo. */
    public static int BLOCKS_PER_EMERALD = 8, MIN_COST = 2;
    /** La cuenta se reusa este tiempo (el modo Gestión pregunta cada 2 s). */
    private static int CACHE_TICKS = 100;

    /** Lo roto: cuántos bloques faltan y cuánto cuesta. */
    public record Damage(int missing, int cost) {
        public static final Damage NONE = new Damage(0, 0);
    }

    private record Cached(long tick, Damage damage) {}

    private static final Map<String, Cached> CACHE = new HashMap<>();

    private WallRepair() {}

    /** ¿Tiene muros para reparar? (el anillo de H12, o lo dibujado en H22c: muralla, portones, torres) */
    public static boolean hasWalls(@Nullable TownData.Town t) {
        return t != null && (hasRing(t) || !t.defense.isEmpty());
    }

    private static boolean hasRing(TownData.Town t) {
        return t.ringWalls && t.ringRadius > 0 && (t.has(Upgrade.PALISADE) || t.has(Upgrade.WALL));
    }

    /** Los bloques del anillo que faltan (con la aldea cargada; si no, vacío). */
    public static Blueprints.Plan missing(ServerLevel level, VillageData.Village v) {
        Blueprints.Plan out = new Blueprints.Plan();
        TownData.Town t = TownData.get(level).at(v.center());
        if (v.castle() || !hasWalls(t) || !level.isPositionEntityTicking(v.center())) return out;
        for (Map.Entry<Long, BlockState> e : t.defense.entrySet()) {   // H22c: lo dibujado, como quedó
            BlockPos p = BlockPos.of(e.getKey());
            if (!level.isLoaded(p)) continue;
            BlockState now = level.getBlockState(p);
            if (now.is(e.getValue().getBlock())) continue;
            if (!Blueprints.clearable(now) || level.getBlockEntity(p) != null) continue;
            out.put(p, e.getValue());
        }
        if (!hasRing(t)) return out;
        Blueprints.Plan ring = Blueprints.ring(level, v.center(), t.ringRadius, t.has(Upgrade.WALL));
        Map<Long, Boolean> towerColumn = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : ring.blocks.entrySet()) {
            BlockPos p = e.getKey();
            BlockState want = e.getValue();
            BlockState now = level.getBlockState(p);
            if (now.is(want.getBlock())) continue;
            if (!Blueprints.clearable(now) || level.getBlockEntity(p) != null) continue;
            long col = BlockPos.asLong(p.getX(), 0, p.getZ());
            if (t.cutColumns.contains(col)) continue;   // la rompió entera un jugador de la aldea
            if (towerColumn.computeIfAbsent(col, k -> nearTower(level, p))) continue;
            out.put(p, want);
        }
        return out;
    }

    /** ¿La columna es parte de una torre? (ladrillos más altos que la muralla, o la escalera de adentro, cerca) */
    private static boolean nearTower(ServerLevel level, BlockPos p) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++) {
            q.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
            BlockState s = level.getBlockState(q);
            if (s.is(Blocks.LADDER) || dy >= 5 && s.is(Blocks.STONE_BRICKS)) return true;
        }
        return false;
    }

    /** Cuánto está roto (cacheado unos segundos). */
    public static Damage damage(ServerLevel level, VillageData.Village v) {
        String key = level.dimension().location() + "@" + v.center().asLong();
        Cached c = CACHE.get(key);
        long now = level.getGameTime();
        if (c != null && now - c.tick() < CACHE_TICKS && now >= c.tick()) return c.damage();
        int n = missing(level, v).size();
        Damage d = n == 0 ? Damage.NONE : new Damage(n, Math.max(MIN_COST, (n + BLOCKS_PER_EMERALD - 1) / BLOCKS_PER_EMERALD));
        CACHE.put(key, new Cached(now, d));
        return d;
    }

    public static void forget(BlockPos center) {
        CACHE.keySet().removeIf(k -> k.endsWith("@" + center.asLong()));
    }

    /** Encarga la reparación: paga (almacén y después inventario) y arranca la obra. */
    public static Component order(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return error("upgrade.warbandcraft.not_yours");
        TownData.Town t = TownData.get(level).at(center);
        if (v.castle() || !hasWalls(t)) return error("upgrade.warbandcraft.repair.no_walls");
        if (!level.isPositionEntityTicking(center)) return error("upgrade.warbandcraft.not_loaded");
        if (t.job != null) return Component.translatable("upgrade.warbandcraft.busy", t.job.upgrade.name(false)).withStyle(ChatFormatting.RED);
        forget(center);
        Blueprints.Plan plan = missing(level, v);
        if (plan.size() == 0) return error("upgrade.warbandcraft.repair.nothing");
        int cost = Math.max(MIN_COST, (plan.size() + BLOCKS_PER_EMERALD - 1) / BLOCKS_PER_EMERALD);
        int inStock = VillageStock.total(level, v).emeralds();
        int inHand = player.getInventory().countItem(Items.EMERALD);
        if (inStock + inHand < cost) {
            return Component.translatable("upgrade.warbandcraft.no_money", cost, inStock, inHand).withStyle(ChatFormatting.RED);
        }
        int fromStock = VillageStock.takeEmeralds(level, v, cost);
        if (cost - fromStock > 0) Upgrades.takeFromInventory(player, cost - fromStock);
        Upgrades.start(level, t, Upgrade.REPAIR, plan);
        forget(center);
        return Component.translatable("upgrade.warbandcraft.repair.started", plan.size(), cost).withStyle(ChatFormatting.GREEN);
    }

    /** Aviso al dueño si después de una pelea quedó el muro roto. */
    public static Optional<Component> warning(ServerLevel level, VillageData.Village v) {
        forget(v.center());
        Damage d = damage(level, v);
        if (d.missing() == 0) return Optional.empty();
        return Optional.of(Component.translatable("upgrade.warbandcraft.repair.warning", d.missing(), d.cost()).withStyle(ChatFormatting.GOLD));
    }

    // ------------------------------------------------------------------ lo que cambia el jugador a mano (pedido de Ale)

    /** Hasta dónde arriba/abajo del bloque roto se busca muralla del anillo que siga en pie en la columna. */
    private static int COLUMN_SCAN = 6;

    /**
     * Un jugador de la aldea rompió un bloque: si con eso la columna de muralla queda sin nada en pie, ahí ya no hay
     * muralla (no se repara ni cuenta). Romper una parte sigue siendo muralla rota. Lo que rompe un enemigo o una
     * explosión sigue anotado para reparar.
     */
    public static void onPlayerBreak(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        TownData data = TownData.get(level);
        long col = BlockPos.asLong(pos.getX(), 0, pos.getZ());
        for (TownData.Town t : data.all()) {
            boolean drawn = t.defense.containsKey(pos.asLong());
            boolean ring = !drawn && ringBlockAt(t, pos, state);
            if (!drawn && !ring) continue;
            if (!mine(level, t, player)) return;
            if (standing(level, t, pos)) return;
            t.defense.keySet().removeIf(l -> BlockPos.getX(l) == pos.getX() && BlockPos.getZ(l) == pos.getZ());
            t.gates.removeIf(g -> g.getX() == pos.getX() && g.getZ() == pos.getZ());
            if (hasRing(t) && (ring || ringBody(state) && nearRing(t, pos))) t.cutColumns.add(col);
            if (t.defense.isEmpty() && !t.ringWalls) {
                t.built.remove(Upgrade.PALISADE);
                t.built.remove(Upgrade.WALL);
            }
            data.setDirty();
            forget(t.center);
            return;
        }
    }

    /** Un jugador de la aldea puso un bloque arriba de la muralla: pasa a ser muralla (se repara si lo rompen). */
    public static void onPlayerPlace(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        if (state.isAir() || !(state.isCollisionShapeFullBlock(level, pos) || state.getBlock() instanceof net.minecraft.world.level.block.WallBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock)) return;
        if (level.getBlockEntity(pos) != null) return;
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        TownData data = TownData.get(level);
        for (TownData.Town t : data.all()) {
            BlockState want = t.defense.get(below.asLong());
            boolean wall = want != null ? under.is(want.getBlock()) : ringBlockAt(t, below, under);
            if (!wall) continue;
            if (!mine(level, t, player)) return;
            t.defense.put(pos.asLong(), state);
            data.setDirty();
            forget(t.center);
            return;
        }
    }

    /** ¿El jugador es de la aldea (dueño o compañero)? Los castillos y las aldeas de facción, no. */
    private static boolean mine(ServerLevel level, TownData.Town t, ServerPlayer player) {
        VillageData.Village v = VillageData.get(level).at(t.center);
        return v != null && !v.castle() && v.owner() != null && com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner());
    }

    /** ¿Queda algo de muralla en pie en la columna de {@code pos}, sin contar {@code pos}? */
    private static boolean standing(ServerLevel level, TownData.Town t, BlockPos pos) {
        for (Map.Entry<Long, BlockState> e : t.defense.entrySet()) {
            long l = e.getKey();
            if (BlockPos.getX(l) != pos.getX() || BlockPos.getZ(l) != pos.getZ() || l == pos.asLong()) continue;
            if (level.getBlockState(BlockPos.of(l)).is(e.getValue().getBlock())) return true;
        }
        if (!hasRing(t) || !nearRing(t, pos)) return false;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dy = -COLUMN_SCAN; dy <= COLUMN_SCAN; dy++) {
            if (dy == 0) continue;
            q.set(pos.getX(), pos.getY() + dy, pos.getZ());
            if (ringBody(level.getBlockState(q))) return true;
        }
        return false;
    }

    /** ¿{@code pos} es un bloque del anillo de H12 (material del muro, a la distancia del anillo, columna no cortada)? */
    private static boolean ringBlockAt(TownData.Town t, BlockPos pos, BlockState state) {
        return hasRing(t) && ringBody(state) && nearRing(t, pos) && !t.cutColumns.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    private static boolean ringBody(BlockState s) {
        return s.is(Blocks.SPRUCE_LOG) || s.is(Blocks.STONE_BRICKS) || s.is(Blocks.STONE_BRICK_WALL)
                || s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    private static boolean nearRing(TownData.Town t, BlockPos pos) {
        double dx = pos.getX() - t.center.getX(), dz = pos.getZ() - t.center.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        return d >= t.ringRadius - Blueprints.RING_DETOUR_IN - 2 && d <= t.ringRadius + Blueprints.RING_DETOUR_OUT + 2;
    }

    private static Component error(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }
}
