package com.ale.warbandcraft.command;

import com.ale.warbandcraft.route.Travel;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /spawn} (pedido de Ale, para todos): te lleva al spawn del mundo, que en los mundos nuevos es el salón del
 * tutorial. No sirve para zafar de una pelea: no anda si en los últimos {@link #COMBAT_SECONDS} recibiste daño o le
 * pegaste a alguien, ni si algo a {@link #ENEMY_RANGE} bloques te tiene de objetivo.
 */
public final class SpawnCommand {
    /** Segundos desde el último golpe (recibido o dado) para poder usar /spawn. */
    public static int COMBAT_SECONDS = 15;
    /** Distancia a la que un bicho o soldado que te tiene de objetivo no te deja usar /spawn. */
    public static int ENEMY_RANGE = 32;

    /** Último golpe de cada jugador (tiempo del juego), recibido o dado. En memoria. */
    private static final Map<UUID, Long> LAST_FIGHT = new HashMap<>();

    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spawn").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Component why = blocked(player);
            if (why != null) {
                player.displayClientMessage(why.copy().withStyle(ChatFormatting.RED), false);
                return 0;
            }
            ServerLevel overworld = player.server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            if (Travel.isTraveling(player.getUUID())) Travel.cancel(player, null);
            player.stopRiding();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, overworld.getSharedSpawnAngle(), 0.0F);
            player.displayClientMessage(Component.literal("Volviste al spawn. Tus soldados se quedaron donde estaban.")
                    .withStyle(ChatFormatting.GOLD), false);
            return 1;
        }));
    }

    /** Por qué no puede usar /spawn ahora (null = puede). */
    @Nullable
    private static Component blocked(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return null;
        Long last = LAST_FIGHT.get(player.getUUID());
        long now = player.serverLevel().getGameTime();
        if (last != null && now - last < COMBAT_SECONDS * 20L) {
            long left = (COMBAT_SECONDS * 20L - (now - last) + 19) / 20;
            return Component.literal("Estás en una pelea: no podés usar /spawn hasta " + left + " s después del último golpe.");
        }
        boolean hunted = !player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(ENEMY_RANGE),
                m -> m.isAlive() && m.getTarget() == player).isEmpty();
        if (hunted) return Component.literal("Te están persiguiendo: no podés usar /spawn con enemigos encima.");
        return null;
    }

    /** Desde {@code LivingHurtEvent}: un jugador recibe daño, o le pega a alguien (él o su flecha). */
    public static void onHurt(LivingEntity victim, @Nullable Entity attacker) {
        if (victim instanceof ServerPlayer p) LAST_FIGHT.put(p.getUUID(), p.serverLevel().getGameTime());
        if (attacker instanceof ServerPlayer p && attacker != victim) LAST_FIGHT.put(p.getUUID(), p.serverLevel().getGameTime());
    }

    public static void forget(Player player) {
        LAST_FIGHT.remove(player.getUUID());
    }
}
