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

/** {@code /spawn} (pedido de Ale, para todos): te lleva al spawn del mundo, que en los mundos nuevos es el salón del tutorial. */
public final class SpawnCommand {
    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("spawn").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
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
}
