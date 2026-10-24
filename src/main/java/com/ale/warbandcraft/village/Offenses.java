package com.ale.warbandcraft.village;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.raid.RaidManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ofensas en una aldea de facción (pedido de Ale): romper heno o cultivos, o abrir un cofre/barril, con un testigo
 * (soldado de la guarnición o aldeano que te ve) = te echan. Tenés unos segundos para salir; si seguís adentro, ataque
 * ({@link RaidManager#start}) y la guarnición contra vos. Si volvés mientras dura la expulsión, la cuenta sigue.
 */
public final class Offenses {
    private Offenses() {}

    /** Segundos adentro de la aldea que tenés para irte después de una ofensa. */
    public static int LEAVE_SECONDS = 15;
    /** Cuánto dura la expulsión (segundos de juego desde la última ofensa): volver antes sigue la cuenta. */
    public static int BAN_SECONDS = 180;
    /** Hasta dónde ve un testigo (bloques). */
    public static int WITNESS_RANGE = 16;
    /** Opinión de la facción por cada ofensa vista. */
    public static int OPINION = -3;

    /** Jugador → expulsión en curso. */
    private static final Map<UUID, Ban> BANS = new HashMap<>();

    private static final class Ban {
        final long center;
        int left;
        long until;
        Ban(long center, int left, long until) { this.center = center; this.left = left; this.until = until; }
    }

    public static boolean isOffenseBlock(BlockState state) {
        return state.is(Blocks.HAY_BLOCK) || state.getBlock() instanceof CropBlock || state.getBlock() instanceof StemBlock;
    }

    /** Rompió heno/cultivos o abrió un contenedor en {@code pos}. */
    public static void onOffense(ServerPlayer player, BlockPos pos, boolean theft) {
        if (player.isCreative() || player.isSpectator()) return;
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village v = data.nearest(pos, VillageManager.ENTER_RADIUS);
        if (v == null || v.isConquered() || data.isTest(v.center()) || RaidManager.get(level, v.center()) != null) return;
        if (theft && level.getBlockEntity(pos) instanceof com.ale.warbandcraft.camp.CampBlockEntity) return;
        LivingEntity witness = witness(level, player, v);
        if (witness == null) return;
        Faction faction = FactionManager.INSTANCE.get(v.faction());
        Relations.get(level.getServer()).add(player.getUUID(), v.faction(), OPINION);
        long until = level.getGameTime() + BAN_SECONDS * 20L;
        Ban ban = BANS.get(player.getUUID());
        if (ban != null && ban.center == v.center().asLong()) ban.until = until;
        else BANS.put(player.getUUID(), new Ban(v.center().asLong(), LEAVE_SECONDS, until));
        witness.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, player.getEyePosition());
        player.sendSystemMessage(Component.translatable(theft ? "msg.warbandcraft.offense.theft" : "msg.warbandcraft.offense.damage",
                witness.getDisplayName(), of(faction), BANS.get(player.getUUID()).left).withStyle(ChatFormatting.RED));
    }

    /** Un soldado de la guarnición o un aldeano despierto, cerca y con vista al jugador. */
    private static LivingEntity witness(ServerLevel level, ServerPlayer player, VillageData.Village v) {
        double r2 = WITNESS_RANGE * WITNESS_RANGE;
        for (AbstractRecruitEntity g : VillageManager.garrison(level, v.center())) {
            if (g.distanceToSqr(player) <= r2 && g.hasLineOfSight(player)) return g;
        }
        for (AbstractVillager a : level.getEntitiesOfClass(AbstractVillager.class, player.getBoundingBox().inflate(WITNESS_RANGE))) {
            if (!a.isSleeping() && a.distanceToSqr(player) <= r2 && a.hasLineOfSight(player)) return a;
        }
        return null;
    }

    /** Cada 1 s por jugador (desde {@code VillageEvents}): cuenta para irse, y si no se fue, ataque. */
    public static void tick(ServerPlayer player) {
        Ban ban = BANS.get(player.getUUID());
        if (ban == null) return;
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).nearest(BlockPos.of(ban.center), 1);
        if (v == null || v.isConquered() || RaidManager.get(level, v.center()) != null || level.getGameTime() > ban.until
                || VillageManager.garrison(level, v.center()).isEmpty()) {
            BANS.remove(player.getUUID());
            return;
        }
        if (player.isCreative() || player.isSpectator() || com.ale.warbandcraft.route.Travel.isTraveling(player.getUUID())) return;
        VillageData.Village here = VillageData.get(level).nearest(player.blockPosition(), VillageManager.ENTER_RADIUS);
        if (here != v) return;   // afuera: la cuenta espera
        Faction faction = FactionManager.INSTANCE.get(v.faction());
        if (ban.left > 0) {
            player.displayClientMessage(Component.translatable("msg.warbandcraft.offense.warn", of(faction), ban.left)
                    .withStyle(ChatFormatting.RED), true);
            ban.left--;
            return;
        }
        BANS.remove(player.getUUID());
        if (RaidManager.start(level, v, player.getUUID(), player) == null) return;
        for (AbstractRecruitEntity g : VillageManager.garrison(level, v.center())) {
            if (g.getTarget() == null || !g.getTarget().isAlive()) g.setTarget(player);
        }
        player.sendSystemMessage(Component.translatable("msg.warbandcraft.offense.attack", of(faction)).withStyle(ChatFormatting.RED));
    }

    public static void forget(UUID player) {
        BANS.remove(player);
    }

    private static Component of(Faction faction) {
        return Component.literal(faction != null ? faction.ofName() : "?")
                .withStyle(faction != null ? faction.color() : ChatFormatting.WHITE, ChatFormatting.BOLD);
    }
}
