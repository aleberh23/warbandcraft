package com.ale.warbandcraft.command;

import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.camp.CampStats;
import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.FactionStyle;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenRelationsMessage;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import com.ale.warbandcraft.raid.RaidManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * /warband aldea  → la aldea más cercana: facción, dónde está, guarnición viva.
 * /warband aldeas → cuántas aldeas hay registradas y las 5 más cercanas.
 * /warband facciones → las facciones cargadas.
 * /warband relaciones → qué opina cada facción de vos.
 * /warband saquear | conquistar → qué hacer con una aldea que acabás de ganar (también son los botones del chat).
 * /warband uniformar → vuelve a pintar a tus soldados cercanos con los colores y el estandarte de tu facción (H6).
 * /warband barracon → el barracón de tu Campamento más cercano: cuántos hay, de qué clase y la comida (H8).
 * /warband rutas → la pantalla de Rutas y viaje, igual que la tecla J (H13).
 * /warband panel → el Panel de control, igual que la tecla K (H11).
 * /warband batalla [parar] → orden Batalla a tus soldados cercanos, igual que el botón de la vista aérea (H9).
 * /warband prueba enemigos | mios | limpiar → ejércitos de prueba para las batallas (con trucos, ver {@link TestArmyCommand}).
 */
public final class WarbandCommand {
    private WarbandCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("warband")
                .then(Commands.literal("aldea").executes(WarbandCommand::nearest))
                .then(Commands.literal("aldeas").executes(WarbandCommand::list))
                .then(Commands.literal("facciones").executes(WarbandCommand::factions))
                .then(Commands.literal("relaciones").executes(WarbandCommand::relations))
                .then(Commands.literal("saquear").executes(ctx -> outcome(ctx, true)))
                .then(Commands.literal("conquistar").executes(ctx -> outcome(ctx, false)))
                .then(Commands.literal("uniformar").executes(WarbandCommand::uniform))
                .then(Commands.literal("barracon").executes(WarbandCommand::barracks))
                .then(Commands.literal("rutas").executes(WarbandCommand::routes))
                .then(Commands.literal("panel").executes(WarbandCommand::panel))
                .then(Commands.literal("ejercitos").executes(ctx -> {   // H20
                    com.ale.warbandcraft.network.OpenArmiesMessage.send(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("almacen").executes(WarbandCommand::storage))
                .then(Commands.literal("retirar").executes(WarbandCommand::withdraw))
                .then(Commands.literal("fundar").executes(WarbandCommand::found))
                .then(Commands.literal("mazmorras").executes(WarbandCommand::dungeons))   // H25
                .then(Commands.literal("mercado").executes(WarbandCommand::market))
                .then(Commands.literal("asediar").executes(ctx -> {   // H23: lo mismo que la tecla B
                    com.ale.warbandcraft.battle.BattleChoices.onKey(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("batalla").executes(ctx -> battle(ctx, true))
                        .then(Commands.literal("parar").executes(ctx -> battle(ctx, false))))
                .then(TestArmyCommand.node()));
    }

    /** Fundar una aldea en el Campamento tuyo más cercano (a su jurisdicción como mucho). */
    /** H24: la pantalla Mercado de tu aldea/castillo más cercano. */
    private static int market(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageManagement.near(player);
        if (v == null) {
            ctx.getSource().sendFailure(Component.translatable("msg.warbandcraft.manage.not_here", com.ale.warbandcraft.village.VillageCamps.MANAGE_RADIUS));
            return 0;
        }
        if (com.ale.warbandcraft.market.Market.level(player.serverLevel(), v) == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.warbandcraft.market.no_market"));
            return 0;
        }
        com.ale.warbandcraft.network.OpenMarketMessage.send(player, com.ale.warbandcraft.market.Market.view(player.serverLevel(), v), Component.empty());
        return 1;
    }

    /** H25: las mazmorras que conocés y tu contrato. */
    private static int dungeons(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        com.ale.warbandcraft.dungeon.DungeonData data = com.ale.warbandcraft.dungeon.DungeonData.get(level);
        int n = 0;
        for (com.ale.warbandcraft.dungeon.DungeonData.Dungeon d : data.all()) {
            if (!d.known.contains(player.getUUID())) continue;
            n++;
            int dist = (int) Math.sqrt(com.ale.warbandcraft.village.VillageData.horizontalDistSq(d.entrance, player.blockPosition()));
            ctx.getSource().sendSuccess(() -> Component.literal("• " + d.name + " " + com.ale.warbandcraft.dungeon.Dungeons.where(d.entrance) + " — " + dist + " b"), false);
        }
        for (com.ale.warbandcraft.dungeon.DungeonData.Rumor r : data.rumorsOf(player.getUUID())) {
            n++;
            ctx.getSource().sendSuccess(() -> Component.translatable("gui.warbandcraft.map.rumor", r.name())
                    .append(" " + com.ale.warbandcraft.dungeon.Dungeons.where(r.pos())), false);
        }
        com.ale.warbandcraft.dungeon.DungeonData.Contract c = data.contractOf(player.getUUID());
        if (c != null) ctx.getSource().sendSuccess(() -> com.ale.warbandcraft.dungeon.Rumors.contractText(level, c), false);
        if (n == 0 && c == null) ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.dungeons.none"), false);
        return 1;
    }

    private static int found(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = CampRegistry.get(level).nearestCamp(player.getUUID(), player.blockPosition());
        if (pos == null || !pos.closerThan(player.blockPosition(), CampBlockEntity.WORK_RADIUS)) {
            ctx.getSource().sendFailure(Component.translatable("msg.warbandcraft.found.no_camp", CampBlockEntity.WORK_RADIUS));
            return 0;
        }
        Component result = com.ale.warbandcraft.village.Founding.found(player, pos);
        ctx.getSource().sendSuccess(() -> result, false);
        return 1;
    }

    /** H10: totales del almacén del Campamento tuyo más cercano. */
    private static int storage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos pos = CampRegistry.get(level).nearestCamp(player.getUUID(), player.blockPosition());
        if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof CampBlockEntity camp)) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.storage.none"));
            return 0;
        }
        int[] v = CampStats.compute(level, camp);
        Component food = v[CampStats.FOOD_DAYS] < 0
                ? Component.translatable("gui.warbandcraft.camp.stats.food", v[CampStats.FOOD])
                : Component.translatable("gui.warbandcraft.camp.stats.food_days", v[CampStats.FOOD], v[CampStats.FOOD_DAYS]);
        Component line = Component.translatable("gui.warbandcraft.camp.stats", v[CampStats.EMERALDS], food, v[CampStats.ARMAMENT],
                v[CampStats.SMITHS], v[CampStats.WORKERS]);
        ctx.getSource().sendSuccess(() -> Component.literal(camp.nameString() + ": ").withStyle(ChatFormatting.GOLD)
                .append(line.copy().withStyle(ChatFormatting.WHITE)), false);
        return 1;
    }

    /** H10: lo guardado en tu aldea/castillo, a la mano. */
    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.sendSystemMessage(VillageStock.withdraw(player));
        return 1;
    }

    private static int battle(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.sendSystemMessage(com.ale.warbandcraft.network.BattleOrderMessage.toggle(player, List.of(), on));
        return 1;
    }

    private static int nearest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos here = BlockPos.containing(src.getPosition());
        List<VillageData.Village> villages = VillageData.get(level).byDistance(here);
        if (villages.isEmpty()) {
            src.sendFailure(Component.translatable("cmd.warbandcraft.no_village", VillageManager.SCAN_RADIUS));
            return 0;
        }
        src.sendSuccess(() -> describe(level, villages.get(0), here), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos here = BlockPos.containing(src.getPosition());
        List<VillageData.Village> villages = VillageData.get(level).byDistance(here);
        src.sendSuccess(() -> Component.translatable("cmd.warbandcraft.village_count", villages.size()).withStyle(ChatFormatting.GOLD), false);
        for (int i = 0; i < Math.min(5, villages.size()); i++) {
            VillageData.Village v = villages.get(i);
            src.sendSuccess(() -> Component.literal("• ").append(describe(level, v, here)), false);
        }
        return villages.size();
    }

    private static int factions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            long valid = f.troops().stream().filter(t -> t.resolve() != null).count();
            src.sendSuccess(() -> Component.literal("• ").append(Component.literal(f.name()).withStyle(f.color()))
                    .append(Component.literal(" — " + valid + "/" + f.troops().size() + " tropas, guarnición "
                            + f.garrisonMin() + "-" + f.garrisonMax()).withStyle(ChatFormatting.GRAY)), false);
        }
        return FactionManager.INSTANCE.ordered().size();
    }

    /** En vez de imprimir líneas en el chat, manda una pantalla con una línea ya armada por facción. */
    private static int relations(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Relations rel = Relations.get(ctx.getSource().getServer());
        List<Component> lines = new ArrayList<>();
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            int op = rel.opinion(player.getUUID(), f.id());
            String state = rel.atWar(player.getUUID(), f.id()) ? "war" : op < -10 ? "tense" : op > 10 ? "good" : "neutral";
            ChatFormatting color = switch (state) {
                case "war" -> ChatFormatting.RED;
                case "tense" -> ChatFormatting.YELLOW;
                case "good" -> ChatFormatting.GREEN;
                default -> ChatFormatting.GRAY;
            };
            lines.add(Component.literal(f.name()).withStyle(f.color())
                    .append(Component.literal(": " + op + " ")).append(Component.translatable("cmd.warbandcraft.relation_" + state)
                            .withStyle(color)));
        }
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenRelationsMessage(lines));
        return 1;
    }

    private static int outcome(CommandContext<CommandSourceStack> ctx, boolean sack) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RaidManager.Outcome out = sack
                ? RaidManager.sack(player.serverLevel(), player.getUUID(), player.blockPosition())
                : RaidManager.conquer(player.serverLevel(), player.getUUID(), player.blockPosition());
        if (out.ok()) ctx.getSource().sendSuccess(out::message, false);
        else ctx.getSource().sendFailure(out.message());
        return out.ok() ? 1 : 0;
    }

    /** Radio de /warband uniformar. */
    public static int UNIFORM_RADIUS = 32;

    private static int uniform(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FactionStyle style = FactionStyle.forPlayer(player);
        if (style == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.uniform.no_faction"));
            return 0;
        }
        List<AbstractRecruitEntity> soldiers = player.serverLevel().getEntitiesOfClass(AbstractRecruitEntity.class,
                player.getBoundingBox().inflate(UNIFORM_RADIUS),
                r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), player.getUUID()) && Soldiers.isSoldier(r));
        soldiers.forEach(style::applyTo);
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.uniform.done", soldiers.size())
                .withStyle(ChatFormatting.GREEN), false);
        return soldiers.size();
    }

    private static int routes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), OpenRoutesMessage.of(player, null));
        return 1;
    }

    private static int panel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                com.ale.warbandcraft.network.OpenPanelMessage.of(player, com.ale.warbandcraft.panel.Panel.TAB_CAMPS, null));
        return 1;
    }

    private static int barracks(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        CampRegistry registry = CampRegistry.get(level);
        BlockPos camp = registry.nearestCamp(player.getUUID(), player.blockPosition());
        if (camp == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.barracks.no_camp"));
            return 0;
        }
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        int count = b == null ? 0 : b.soldiers.size();
        int dist = (int) Math.sqrt(camp.distSqr(player.blockPosition()));
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.barracks.header",
                registry.displayName(camp).copy().withStyle(ChatFormatting.GOLD), dist, count, com.ale.warbandcraft.camp.Barracks.capacity(level, camp)), false);
        if (b == null || b.soldiers.isEmpty()) return 0;

        java.util.Map<String, Integer> byClass = new java.util.LinkedHashMap<>();
        for (BarracksData.Soldier s : b.soldiers) {
            String name = "Recluta";
            if (s.kit != null) {
                ClassKit kit = PlayerKits.resolve(player.getServer(), player.getUUID(), s.kit);
                if (kit != null) name = kit.name() + (s.tier < kit.tiers().size() ? " (" + kit.tiers().get(s.tier).name() + ")" : "");
            }
            byClass.merge(name, 1, Integer::sum);
        }
        byClass.forEach((name, n) -> ctx.getSource().sendSuccess(() -> Component.literal("• " + name + " ×" + n), false));
        int guards = b.guards();
        if (guards > 0) ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.barracks.guards", guards), false);
        if (level.isLoaded(camp) && level.getBlockEntity(camp) instanceof CampBlockEntity be) {
            int days = Barracks.foodDays(be, b);
            ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.barracks.food", days)
                    .withStyle(days < 3 ? ChatFormatting.GOLD : ChatFormatting.GREEN), false);
        }
        if (b.hungryDays > 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable("gui.warbandcraft.barracks.food_hungry", b.hungryDays)
                    .withStyle(ChatFormatting.RED), false);
        }
        return count;
    }

    private static MutableComponent describe(ServerLevel level, VillageData.Village v, BlockPos from) {
        Faction f = FactionManager.INSTANCE.get(v.faction());
        Component who = f == null ? Component.literal(v.faction().toString())
                : Component.literal(f.ofName()).withStyle(f.color());
        int dist = (int) Math.sqrt(VillageData.horizontalDistSq(v.center(), from));
        // Solo se pueden contar los soldados de aldeas cargadas (cerca de algún jugador)
        String alive = level.isPositionEntityTicking(v.center())
                ? String.valueOf(VillageManager.garrison(level, v.center()).size()) : "?";
        String kind = v.castle() ? "castle_info" : "village_info";
        MutableComponent line = Component.translatable("cmd.warbandcraft." + kind, who, v.center().toShortString(), dist, alive);
        if (v.isConquered()) line.append(Component.translatable("cmd.warbandcraft.state_conquered").withStyle(ChatFormatting.AQUA));
        else if (v.isLooted(level.getGameTime())) line.append(Component.translatable("cmd.warbandcraft.state_looted").withStyle(ChatFormatting.DARK_RED));
        else if (RaidManager.get(level, v.center()) != null) line.append(Component.translatable("cmd.warbandcraft.state_raid").withStyle(ChatFormatting.RED));
        if (v.isConquered()) {
            // H10: su almacén
            line.append(Component.translatable("cmd.warbandcraft.state_stock", VillageStock.total(level, v).describe(),
                    VillageStock.where(level, v)).withStyle(ChatFormatting.GRAY));
        }
        return line;
    }
}
