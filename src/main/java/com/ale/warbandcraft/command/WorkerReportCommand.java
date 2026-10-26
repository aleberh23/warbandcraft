package com.ale.warbandcraft.command;

import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.WorkerOutput;
import com.ale.warbandcraft.village.WorkerSupply;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pedido de Ale ("no veo que produzcan"): {@code /warband aportes [días]}, solo operadores. Lo que dejó cada trabajador en
 * el almacén en los últimos días (de {@link WorkerOutput.Rec#history}: lo que juntó, la veta del minero, lo pagado sin
 * cargar y el piso de lo esperado), agrupado por aldea / Campamento, con lo esperado por día y cómo está ahora.
 */
public final class WorkerReportCommand {
    private WorkerReportCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("aportes").requires(s -> s.hasPermission(2))
                .executes(ctx -> run(ctx, WorkerOutput.HISTORY_DAYS))
                .then(Commands.argument("dias", IntegerArgumentType.integer(1, 30))
                        .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "dias"))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, int days) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        long today = level.getDayTime() / WorkerOutput.DAY;
        Map<BlockPos, List<WorkerOutput.Rec>> byPlace = new LinkedHashMap<>();
        for (WorkerOutput.Rec r : WorkerOutput.get(level).all()) byPlace.computeIfAbsent(r.village, k -> new ArrayList<>()).add(r);
        if (byPlace.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No hay trabajadores anotados en esta dimensión.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Aportes al almacén, últimos " + days + " días (hoy = día " + today + "):")
                .withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<BlockPos, List<WorkerOutput.Rec>> e : byPlace.entrySet()) {
            Component place = placeName(level, e.getKey());
            src.sendSuccess(() -> Component.literal("■ ").append(place).append(" " + where(e.getKey())).withStyle(ChatFormatting.YELLOW), false);
            for (WorkerOutput.Rec r : e.getValue()) {
                for (Component line : lines(level, r, today, days)) src.sendSuccess(() -> line, false);
            }
        }
        return byPlace.size();
    }

    /** Las líneas de un trabajador: oficio, estado, total del período, por día y lo esperado. */
    private static List<Component> lines(ServerLevel level, WorkerOutput.Rec r, long today, int days) {
        List<Component> out = new ArrayList<>();
        Map<String, Integer> total = new HashMap<>();
        StringBuilder perDay = new StringBuilder();
        for (long d = today - days + 1; d <= today; d++) {
            Map<String, Integer> m = r.history.get(d);
            int sum = 0;
            if (m != null) for (Map.Entry<String, Integer> x : m.entrySet()) {
                total.merge(x.getKey(), x.getValue(), Integer::sum);
                sum += x.getValue();
            }
            if (perDay.length() > 0) perDay.append(" · ");
            perDay.append(sum);
        }
        String status;
        if (level.getEntity(r.worker) instanceof AbstractWorkerEntity w) {
            status = WorkerSupply.status(level, w).text().getString() + (r.working ? "" : " (sin zona o sin herramienta)");
        } else {
            status = "sin cargar";
        }
        out.add(Component.literal("  • ").append(jobName(r)).append(" " + where(r.pos)
                + (r.outpost ? " (puesto)" : "") + " — " + status).withStyle(ChatFormatting.WHITE));
        out.add(Component.literal("    Total: ").append(items(total)).withStyle(total.isEmpty() ? ChatFormatting.RED : ChatFormatting.GREEN));
        out.add(Component.literal("    Por día (cosas): " + perDay).withStyle(ChatFormatting.GRAY));
        Map<String, Integer> rate = new HashMap<>();
        r.rate().forEach((k, n) -> rate.put(k, (int) Math.round(n)));
        out.add(Component.literal("    Esperado por día: ").append(items(rate)).withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    private static Component jobName(WorkerOutput.Rec r) {
        try {
            return VillageManagement.Job.valueOf(r.job).type().getDescription();
        } catch (IllegalArgumentException | NullPointerException e) {
            return Component.literal("Trabajador");
        }
    }

    /** "120 Adoquín, 12 Carbón..." de mayor a menor, o "nada". */
    private static Component items(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
        list.removeIf(x -> x.getValue() <= 0);
        if (list.isEmpty()) return Component.literal("nada");
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        MutableComponent out = Component.empty();
        for (int i = 0; i < list.size() && i < 8; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(list.get(i).getKey()));
            if (item == null) continue;
            if (i > 0) out.append(", ");
            out.append(list.get(i).getValue() + " ").append(new ItemStack(item).getHoverName());
        }
        if (list.size() > 8) out.append(" y " + (list.size() - 8) + " cosas más");
        return out;
    }

    private static Component placeName(ServerLevel level, BlockPos pos) {
        VillageData.Village v = VillageData.get(level).at(pos);
        if (v != null) return VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
        return CampRegistry.get(level).displayName(pos);
    }

    private static String where(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }
}
