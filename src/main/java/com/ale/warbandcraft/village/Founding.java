package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fundar una aldea (pedido de Ale): un Campamento común tuyo pasa a ser el Campamento de una aldea nueva. Se pone una
 * campana al lado (el centro de la aldea), llegan unos aldeanos y desde ahí es una aldea tuya como cualquier otra
 * (H16: territorio, modo Gestión, mejoras, impuestos, cuartel, casas automáticas). El barracón y los cofres vinculados
 * del Campamento siguen siendo los mismos.
 *
 * Decidido sin Ale (a revisar): costo, cuántos aldeanos llegan y a qué distancia de otras aldeas se puede.
 */
public final class Founding {
    /** Esmeraldas que cuesta (almacén del Campamento primero, después tu inventario). */
    public static int COST = 40;
    /** Aldeanos que llegan al fundarla (sin oficio; las camas las pone la aldea sola con las casas automáticas). */
    public static int SETTLERS = 3;
    /** Ninguna aldea ni castillo a menos de esto (para que los territorios no se pisen). */
    public static int MIN_DISTANCE = 128;
    /** Ninguna campana de aldea de vanilla todavía sin descubrir a menos de esto. */
    public static int BELL_DISTANCE = 64;
    /** La campana va a esta distancia del Campamento (como mucho). */
    private static int BELL_RADIUS = 6;

    private Founding() {}

    /** Por qué no se puede fundar una aldea en el Campamento {@code camp} de {@code owner}, o null si se puede (sin mirar la plata). */
    @Nullable
    public static Component blocked(ServerLevel level, UUID owner, BlockPos camp) {
        if (!CampRegistry.get(level).isCampOf(owner, camp)) return error("msg.warbandcraft.panel.not_yours");
        if (VillageCamps.villageOf(level, camp) != null) return error("msg.warbandcraft.found.already");
        if (!level.isPositionEntityTicking(camp) || !(level.getBlockEntity(camp) instanceof CampBlockEntity)) {
            return error("msg.warbandcraft.found.unloaded");
        }
        // En las pruebas hay "aldeas" de otras pruebas pegadas: ahí no se mira la distancia
        boolean test = level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer;
        if (!test && VillageData.get(level).nearest(camp, MIN_DISTANCE) != null) return error("msg.warbandcraft.found.too_close", MIN_DISTANCE);
        boolean bell = !test && level.getPoiManager().findAll(t -> t.is(PoiTypes.MEETING), p -> true, camp, BELL_DISTANCE,
                PoiManager.Occupancy.ANY).findAny().isPresent();
        if (bell) return error("msg.warbandcraft.found.too_close", BELL_DISTANCE);
        if (bellSpot(level, camp) == null) return error("msg.warbandcraft.found.no_room");
        return null;
    }

    /** Funda la aldea. Devuelve el mensaje para el jugador. */
    public static Component found(ServerPlayer player, BlockPos campPos) {
        return found(player.serverLevel(), player.getUUID(), campPos, player);
    }

    /** Igual; {@code player} = de quién se cobra lo que falte en el almacén (null = solo el almacén; lo usan las pruebas). */
    public static Component found(ServerLevel level, UUID owner, BlockPos campPos, @Nullable ServerPlayer player) {
        Component why = blocked(level, owner, campPos);
        if (why != null) return why;
        CampBlockEntity camp = (CampBlockEntity) level.getBlockEntity(campPos);
        int inCamp = camp.count(Items.EMERALD);
        int inHand = player == null ? 0 : player.getInventory().countItem(Items.EMERALD);
        if (inCamp + inHand < COST) return error("msg.warbandcraft.found.no_money", COST, inCamp, inHand);
        BlockPos bell = bellSpot(level, campPos);
        Faction faction = factionAt(level, bell);
        if (faction == null) return error("msg.warbandcraft.found.no_faction");

        int paid = camp.take(Items.EMERALD, COST);
        if (paid < COST && player != null) com.ale.warbandcraft.upgrade.Upgrades.takeFromInventory(player, COST - paid);

        level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
        VillageData data = VillageData.get(level);
        VillageData.Village v = new VillageData.Village(bell, faction.id(), level.getGameTime()).withOwner(owner);
        data.add(v);
        data.markFounded(bell);
        data.setStorehouse(bell, campPos);
        camp.setVillage(bell);
        if (player != null) {   // claim de Recruits a tu nombre (te crea la facción si no tenés), como al conquistar
            try {
                com.ale.warbandcraft.faction.RecruitsClaimBridge.claimVillage(level, v, player);
            } catch (Exception e) {
                WarbandCraft.LOGGER.error("No se pudo reclamar el territorio de {} en Recruits", bell.toShortString(), e);
            }
        }
        Territory.ensure(level, v);
        int settlers = 0;
        for (int i = 0; i < SETTLERS; i++) if (spawnSettler(level, bell)) settlers++;
        WarbandCraft.LOGGER.info("{} fundó una aldea en {} (Campamento {})", owner,
                bell.toShortString(), campPos.toShortString());
        return Component.translatable("msg.warbandcraft.found.done", CampRegistry.get(level).displayName(campPos), settlers, COST)
                .withStyle(ChatFormatting.GREEN);
    }

    /** La facción "de la zona": si perdés la aldea, pasa a ser de ella. La del bioma, o la primera si ninguno lo reclama. */
    @Nullable
    private static Faction factionAt(ServerLevel level, BlockPos pos) {
        Faction f = FactionManager.INSTANCE.forBiome(level.getBiome(pos));
        if (f != null) return f;
        return FactionManager.INSTANCE.ordered().isEmpty() ? null : FactionManager.INSTANCE.ordered().get(0);
    }

    /** Lugar para la campana: piso firme, aire arriba, 2 a {@link #BELL_RADIUS} bloques del Campamento, lo más cerca posible. */
    @Nullable
    static BlockPos bellSpot(ServerLevel level, BlockPos camp) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(camp.offset(-BELL_RADIUS, -2, -BELL_RADIUS), camp.offset(BELL_RADIUS, 2, BELL_RADIUS))) {
            double d = p.distSqr(camp);
            if (d < 4 || d >= bestDist) continue;
            if (!standable(level, p)) continue;
            best = p.immutable();
            bestDist = d;
        }
        return best;
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        BlockPos below = p.below();
        return level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                && level.getFluidState(below).isEmpty()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static boolean spawnSettler(ServerLevel level, BlockPos bell) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) return false;
        BlockPos at = null;
        for (int i = 0; i < 20 && at == null; i++) {
            BlockPos p = bell.offset(level.random.nextInt(9) - 4, 0, level.random.nextInt(9) - 4);
            for (int dy = -2; dy <= 2 && at == null; dy++) if (standable(level, p.above(dy))) at = p.above(dy);
        }
        if (at == null) at = bell.above();
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.MOB_SUMMONED, null, null);
        villager.setPersistenceRequired();
        return level.addFreshEntity(villager);
    }

    private static Component error(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.RED);
    }
}
