package com.ale.warbandcraft.command;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Peace;
import com.ale.warbandcraft.invasion.Hosts;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Pedido de Ale: comandos de operador para destrabar una partida (cancelar huestes, hacer la paz gratis). */
public final class AdminCommand {
    private AdminCommand() {}

    /** /warband huestes cancelar: todas las huestes del mundo se van (acampadas o peleando). */
    public static LiteralArgumentBuilder<CommandSourceStack> hosts() {
        return Commands.literal("huestes").requires(s -> s.hasPermission(2))
                .then(Commands.literal("cancelar").executes(ctx -> {
                    int n = Hosts.cancelAll(ctx.getSource().getServer().overworld());
                    ctx.getSource().sendSuccess(() -> Component.literal(n == 0 ? "No hay huestes." : "Huestes canceladas: " + n)
                            .withStyle(ChatFormatting.GREEN), true);
                    return n;
                }));
    }

    /** /warband paz forzar &lt;facción&gt;: la paz con esa facción para vos y tu facción, sin pagar. */
    public static LiteralArgumentBuilder<CommandSourceStack> forcePeace() {
        return Commands.literal("forzar").requires(s -> s.hasPermission(2))
                .then(Commands.argument("faccion", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(FactionManager.INSTANCE.ordered().stream().map(f -> f.id().getPath()), b))
                        .executes(AdminCommand::peace));
    }

    private static int peace(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "faccion");
        Faction f = null;
        for (Faction x : FactionManager.INSTANCE.ordered()) {
            if (x.id().getPath().equalsIgnoreCase(name) || x.id().toString().equalsIgnoreCase(name)) f = x;
        }
        if (f == null) {
            ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "warbandcraft:" + name);
            f = FactionManager.INSTANCE.get(id);
        }
        if (f == null) {
            ctx.getSource().sendFailure(Component.literal("No existe la facción " + name));
            return 0;
        }
        Peace.make(p.serverLevel().getServer().overworld(), p.getUUID(), f.id());
        return 1;
    }
}
