package com.ale.warbandcraft.command;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.kit.ClassKit;
import com.ale.warbandcraft.kit.KitManager;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ejércitos de prueba para probar las batallas (H9), solo con trucos (nivel 2):
 * /warband prueba enemigos [tipo] [cantidad] [facción] → un ejército enemigo a 28 bloques adelante; el ataque empieza solo.
 * /warband prueba mios [tipo] [cantidad]               → soldados tuyos gratis al lado tuyo, ya con la orden Batalla.
 * /warband prueba limpiar                              → saca todos los soldados y ejércitos de prueba.
 * /warband prueba invasion [incursion|ejercito] [cantidad] → H15a: guerra con la facción más cercana y una incursión (o un
 *                                                         ejército con general, zapadores y escaleras) contra tu aldea: llega en el acto.
 * /warband prueba guerra                               → H15b: otra facción manda 10 soldados contra la aldea de facción más cercana (60 s).
 * /warband prueba reconquista                         → H15: la facción de tu aldea más cercana la quiere recuperar (guerra + ejército, llega en el acto).
 *
 * El ejército enemigo es una "aldea" sin campana marcada como prueba ({@link VillageData#isTest}): usa todo lo de un
 * ataque normal (general, rendirse, prisioneros) pero no cambia opiniones, no recibe refuerzos y al ganarla se borra.
 */
public final class TestArmyCommand {
    /** Marca en todos los soldados de prueba (de los dos bandos), para {@code limpiar}. */
    public static final String TAG_TEST = "warbandcraft:test_army";
    public static int ENEMY_DISTANCE = 28;
    public static int DEFAULT_COUNT = 8;
    public static int MAX_COUNT = 40;
    /** Qué tan desparramados aparecen los enemigos alrededor del centro. */
    public static int ENEMY_SPREAD = 6;

    /** Tipos de ejército: las clases se reparten en este orden, una vuelta tras otra. */
    private static final Map<String, List<String>> TYPES = Map.of(
            "mixto", List.of("infante", "arquero", "infante", "caballero", "lancero", "arquero", "zapador", "infante"),
            "infanteria", List.of("infante", "lancero", "infante"),
            "arqueros", List.of("arquero", "arquero", "ballestero"),
            "caballeria", List.of("caballero"),
            "zapadores", List.of("zapador"));

    private TestArmyCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("prueba").requires(src -> src.hasPermission(2))
                .then(Commands.literal("enemigos")
                        .executes(ctx -> enemies(ctx, "mixto", DEFAULT_COUNT, null, LEVEL_BASIC))
                        .then(Commands.argument("tipo", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TYPES.keySet(), b))
                                .executes(ctx -> enemies(ctx, StringArgumentType.getString(ctx, "tipo"), DEFAULT_COUNT, null, LEVEL_BASIC))
                                .then(withLevels(Commands.argument("cantidad", IntegerArgumentType.integer(1, MAX_COUNT)),
                                        (ctx, lvl) -> enemies(ctx, StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "cantidad"), null, lvl),
                                        (ctx, lvl) -> enemies(ctx, StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "cantidad"), StringArgumentType.getString(ctx, "faccion"), lvl)))))
                .then(Commands.literal("mios")
                        .executes(ctx -> mine(ctx, "mixto", DEFAULT_COUNT, LEVEL_BASIC))
                        .then(Commands.argument("tipo", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(TYPES.keySet(), b))
                                .executes(ctx -> mine(ctx, StringArgumentType.getString(ctx, "tipo"), DEFAULT_COUNT, LEVEL_BASIC))
                                .then(withLevels(Commands.argument("cantidad", IntegerArgumentType.integer(1, MAX_COUNT)),
                                        (ctx, lvl) -> mine(ctx, StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "cantidad"), lvl), null))))
                .then(Commands.literal("limpiar").executes(TestArmyCommand::clean))
                .then(Commands.literal("hueste")   // H23: una hueste acampada adelante tuyo
                        .executes(ctx -> host(ctx, 40, false))
                        .then(Commands.argument("cantidad", IntegerArgumentType.integer(1, PERF_MAX))
                                .executes(ctx -> host(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"), false))))
                .then(Commands.literal("rendimiento")   // H23: muchos soldados peleando + medidor
                        .executes(ctx -> host(ctx, 60, true))
                        .then(Commands.argument("cantidad", IntegerArgumentType.integer(1, PERF_MAX))
                                .executes(ctx -> host(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"), true))))
                .then(Commands.literal("medir").executes(TestArmyCommand::togglePerf))
                .then(Commands.literal("llegar").executes(TestArmyCommand::arriveNow))   // H24: tus ejércitos en camino llegan ya
                .then(Commands.literal("tactica")   // H24: forzar la táctica de la IA (auto = que elija sola)
                        .then(Commands.argument("nombre", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(tacticNames(), b))
                                .executes(ctx -> forceTactic(ctx, StringArgumentType.getString(ctx, "nombre")))))
                .then(Commands.literal("guarida").executes(ctx -> dungeon(ctx, com.ale.warbandcraft.dungeon.DungeonData.Kind.LAIR)))     // H25
                .then(Commands.literal("tumulo").executes(ctx -> dungeon(ctx, com.ale.warbandcraft.dungeon.DungeonData.Kind.BARROW)))    // H25
                .then(Commands.literal("guerra").executes(TestArmyCommand::aiWar))
                .then(Commands.literal("patrulla")   // patrulla errante de una facción (o bandidos) que pasa cerca
                        .executes(ctx -> patrol(ctx, null))
                        .then(Commands.argument("faccion", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(
                                        FactionManager.INSTANCE.ordered().stream().map(f -> f.id().getPath()), java.util.stream.Stream.of("bandidos")), b))
                                .executes(ctx -> patrol(ctx, StringArgumentType.getString(ctx, "faccion")))))
                .then(Commands.literal("reconquista").executes(TestArmyCommand::reconquest))
                .then(Commands.literal("castillo")   // castillos por facción: uno adelante tuyo (facción, tipo y giro opcionales)
                        .executes(ctx -> castle(ctx, null, null, null))
                        .then(Commands.argument("faccion", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(FactionManager.INSTANCE.ordered().stream().map(f -> f.id().getPath()), b))
                                .executes(ctx -> castle(ctx, StringArgumentType.getString(ctx, "faccion"), null, null))
                                .then(Commands.argument("tipo", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(
                                                com.ale.warbandcraft.castle.CastleKind.values()).map(k -> k.id), b))
                                        .executes(ctx -> castle(ctx, StringArgumentType.getString(ctx, "faccion"), StringArgumentType.getString(ctx, "tipo"), null))
                                        .then(Commands.argument("giro", IntegerArgumentType.integer(0, 270))
                                                .executes(ctx -> castle(ctx, StringArgumentType.getString(ctx, "faccion"),
                                                        StringArgumentType.getString(ctx, "tipo"), IntegerArgumentType.getInteger(ctx, "giro")))))))
                .then(Commands.literal("marcha")   // H20: un ejército enemigo marchando cerca, para la batalla de campo
                        .executes(ctx -> march(ctx, 12))
                        .then(Commands.argument("cantidad", IntegerArgumentType.integer(1, com.ale.warbandcraft.invasion.Invasions.MAX_GENERAL_ARMY))
                                .executes(ctx -> march(ctx, IntegerArgumentType.getInteger(ctx, "cantidad")))))
                .then(Commands.literal("invasion")
                        .executes(ctx -> invasion(ctx, 8, RAID))
                        .then(Commands.argument("cantidad", IntegerArgumentType.integer(1, com.ale.warbandcraft.invasion.Invasions.MAX_ARMY))
                                .executes(ctx -> invasion(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"), RAID)))
                        .then(Commands.literal("incursion")
                                .executes(ctx -> invasion(ctx, 8, RAID))
                                .then(Commands.argument("cantidad", IntegerArgumentType.integer(1, com.ale.warbandcraft.invasion.Invasions.MAX_ARMY))
                                        .executes(ctx -> invasion(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"), RAID))))
                        .then(Commands.literal("ejercito")
                                .executes(ctx -> invasion(ctx, 16, ARMY))
                                .then(Commands.argument("cantidad", IntegerArgumentType.integer(4, com.ale.warbandcraft.invasion.Invasions.MAX_GENERAL_ARMY))
                                        .executes(ctx -> invasion(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"), ARMY)))));
    }

    private static final com.ale.warbandcraft.invasion.InvasionData.Kind RAID = com.ale.warbandcraft.invasion.InvasionData.Kind.RAID,
            ARMY = com.ale.warbandcraft.invasion.InvasionData.Kind.ARMY;

    /** H15b: la facción de la aldea de facción más cercana recibe un ejército de otra facción (llega en 60 s). */
    private static int patrol(CommandContext<CommandSourceStack> ctx, String faction) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.ale.warbandcraft.faction.Faction f = null;
        if (faction != null) {
            f = FactionManager.INSTANCE.get(new net.minecraft.resources.ResourceLocation(com.ale.warbandcraft.WarbandCraft.MODID, faction));
            if (f == null) {
                ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.patrol_faction", faction));
                return 0;
            }
        }
        int n = com.ale.warbandcraft.invasion.Patrols.spawnRoaming(player.serverLevel(), player, f, 40, 56).size();
        if (n == 0) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.patrol_none"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.patrol", n), false);
        return 1;
    }

    /** Un castillo de verdad (no de prueba: se puede asediar y conquistar) unos 45 bloques adelante tuyo. */
    private static int castle(CommandContext<CommandSourceStack> ctx, String faction, String kind, Integer degrees) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        com.ale.warbandcraft.faction.Faction f = faction == null ? FactionManager.INSTANCE.forBiome(level.getBiome(player.blockPosition()))
                : FactionManager.INSTANCE.get(new net.minecraft.resources.ResourceLocation(com.ale.warbandcraft.WarbandCraft.MODID, faction));
        if (f == null && faction == null && !FactionManager.INSTANCE.ordered().isEmpty()) f = FactionManager.INSTANCE.ordered().get(0);
        if (f == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.patrol_faction", String.valueOf(faction)));
            return 0;
        }
        com.ale.warbandcraft.castle.CastleKind k = kind == null ? null : com.ale.warbandcraft.castle.CastleKind.byId(kind);
        if (kind != null && k == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.castle_kind", kind));
            return 0;
        }
        if (k == null) k = level.random.nextInt(100) < com.ale.warbandcraft.village.CastleManager.CLASSIC_CHANCE_PERCENT
                ? com.ale.warbandcraft.castle.CastleKind.CLASSIC : com.ale.warbandcraft.castle.CastleKind.drawnFor(f.id());
        net.minecraft.world.level.block.Rotation rot = degrees == null ? net.minecraft.world.level.block.Rotation.getRandom(level.random)
                : switch ((degrees / 90) % 4) {
                    case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                    case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                    case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                    default -> net.minecraft.world.level.block.Rotation.NONE;
                };
        BlockPos at = player.blockPosition().relative(player.getDirection(), 45);
        VillageData.Village v = com.ale.warbandcraft.village.CastleManager.place(level, at, f, k, rot);
        if (v == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.castle_none"));
            return 0;
        }
        String kindId = k.id, factionName = f.name(), where = v.center().toShortString();
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.castle", kindId, factionName, where, rot.name()), false);
        return 1;
    }

    private static int aiWar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village target = null;
        for (VillageData.Village v : data.byDistance(player.blockPosition())) {
            if (!v.isConquered() && !data.isTest(v.center())) {
                target = v;
                break;
            }
        }
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_npc_village"));
            return 0;
        }
        net.minecraft.resources.ResourceLocation attacker = null;
        for (var f : FactionManager.INSTANCE.ordered()) {
            if (!f.id().equals(target.faction())) {
                attacker = f.id();
                break;
            }
        }
        if (attacker == null) return 0;
        com.ale.warbandcraft.invasion.InvasionData inv = com.ale.warbandcraft.invasion.InvasionData.get(level);
        long day = level.getDayTime() / 24000L;
        inv.setWar(attacker, target.faction(), day + 3);
        long now = level.getGameTime();
        double a = level.getRandom().nextDouble() * Math.PI * 2;
        BlockPos from = target.center().offset((int) (Math.cos(a) * 120), 0, (int) (Math.sin(a) * 120));
        inv.putMarch(new com.ale.warbandcraft.invasion.InvasionData.March(java.util.UUID.randomUUID(), attacker, target.center(), from,
                10, now, now + 20 * 60));
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.ai_war"), false);
        return 1;
    }

    /** H20: a cuántos bloques adelante tuyo aparece la marcha, y cuánto tarda en irse (anda muy despacio). */
    private static final int MARCH_AHEAD = 40, MARCH_MINUTES = 120, MARCH_FAR = 300;   // ~2 bloques por minuto

    /**
     * H20: un ejército de la facción más cercana (en guerra con vos) marchando muy despacio a {@link #MARCH_AHEAD} bloques
     * adelante tuyo. Invocá tu ejército, Cargá, y la marcha se vuelve una batalla de campo. Sale en el mapa M.
     */
    private static int march(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        net.minecraft.resources.ResourceLocation faction = null;
        for (VillageData.Village v : data.byDistance(player.blockPosition())) {
            if (!v.isConquered() && !data.isTest(v.center())) {
                faction = v.faction();
                break;
            }
        }
        if (faction == null && !FactionManager.INSTANCE.ordered().isEmpty()) faction = FactionManager.INSTANCE.ordered().get(0).id();
        if (faction == null) return 0;
        com.ale.warbandcraft.faction.Relations rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
        int op = rel.opinion(player.getUUID(), faction);
        if (op > com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD) {
            rel.add(player.getUUID(), faction, com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD - op);
        }
        net.minecraft.world.phys.Vec3 look = new net.minecraft.world.phys.Vec3(player.getLookAngle().x, 0, player.getLookAngle().z);
        look = look.lengthSqr() < 1.0E-4 ? new net.minecraft.world.phys.Vec3(0, 0, 1) : look.normalize();
        BlockPos from = BlockPos.containing(player.position().add(look.scale(MARCH_AHEAD)));
        BlockPos to = BlockPos.containing(player.position().add(look.scale(MARCH_FAR)));
        long now = level.getGameTime();
        com.ale.warbandcraft.invasion.InvasionData.get(level).putMarch(new com.ale.warbandcraft.invasion.InvasionData.March(
                java.util.UUID.randomUUID(), faction, to, from, count, now, now + 20L * 60 * MARCH_MINUTES));
        var f = FactionManager.INSTANCE.get(faction);
        Component name = f == null ? Component.literal(faction.toString()) : Component.literal(f.name()).withStyle(f.color());
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.march", name, count, from.getX(), from.getZ())
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    /** H23: hasta cuántos soldados por bando en las pruebas de hueste y rendimiento; a cuántos bloques pelea la de rendimiento. */
    public static int PERF_MAX = 200, HOST_AHEAD = 50;

    /**
     * H23: guerra con la facción más cercana y una hueste de {@code count} acampada {@link #HOST_AHEAD} bloques adelante
     * tuyo, contra tu aldea más cercana (3 días, como siempre). Con {@code fight}: además {@code count} soldados tuyos
     * gratis, la batalla empieza ya y se prende el medidor de rendimiento.
     */
    private static int host(CommandContext<CommandSourceStack> ctx, int count, boolean fight) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village target = null;
        net.minecraft.resources.ResourceLocation faction = null;
        for (VillageData.Village v : data.byDistance(player.blockPosition())) {
            if (target == null && player.getUUID().equals(v.owner())) target = v;
            if (faction == null && !v.isConquered() && !data.isTest(v.center())) faction = v.faction();
        }
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_village"));
            return 0;
        }
        if (faction == null) faction = target.faction();
        com.ale.warbandcraft.faction.Relations rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
        int op = rel.opinion(player.getUUID(), faction);
        if (op > com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD) {
            rel.add(player.getUUID(), faction, com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD - op);
        }
        var old = com.ale.warbandcraft.invasion.Hosts.hostAt(level, target.center());
        if (old != null) com.ale.warbandcraft.invasion.InvasionData.get(level).removeHost(old.id);
        Vec3 look = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z);
        look = look.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : look.normalize();
        BlockPos camp;
        Vec3 axis = null;
        if (fight) {   // rendimiento: la batalla empieza ya, adelante tuyo
            camp = BlockPos.containing(player.position().add(look.scale(HOST_AHEAD)));
            camp = new BlockPos(camp.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    camp.getX(), camp.getZ()), camp.getZ());
        } else {   // como de verdad: un campo de batalla lejos de la aldea, primero de tu lado
            double angle = Math.atan2(player.getZ() - target.center().getZ(), player.getX() - target.center().getX());
            var field = com.ale.warbandcraft.invasion.Hosts.findField(level, target.center(), angle);
            camp = field == null ? null : field.muster();
            axis = field == null ? null : field.axis();
            if (camp == null) {
                ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_field"));
                return 0;
            }
        }
        var h = com.ale.warbandcraft.invasion.Hosts.place(level, faction, player.getUUID(), target.center(), camp, count,
                com.ale.warbandcraft.invasion.Hosts.DEADLINE_DAYS * 24000L);
        if (axis != null) com.ale.warbandcraft.invasion.Hosts.setAxis(level, h, axis);
        if (!fight) return 1;
        mine(ctx, "mixto", count, LEVEL_BASIC);
        for (Entity e : level.getEntities((Entity) null, new AABB(player.blockPosition()).inflate(16),
                e -> e instanceof AbstractRecruitEntity && e.getPersistentData().getBoolean(TAG_TEST))) {
            BattleGeneral.setBattle((AbstractRecruitEntity) e, false);   // que peleen solos, no con el general de un ataque
        }
        for (var s : com.ale.warbandcraft.invasion.Hosts.startBattle(level, h, player.position())) s.getPersistentData().putBoolean(TAG_TEST, true);
        PERF.add(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.perf", count,
                com.ale.warbandcraft.battle.BattleSize.MAX_PER_SIDE).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static List<String> tacticNames() {
        List<String> out = new java.util.ArrayList<>();
        out.add("auto");
        for (var t : com.ale.warbandcraft.battle.tactics.Tactics.Tactic.values()) out.add(t.name().toLowerCase());
        return out;
    }

    private static int forceTactic(CommandContext<CommandSourceStack> ctx, String name) {
        if (name.equals("auto")) {
            com.ale.warbandcraft.battle.tactics.Tactics.forced = null;
        } else {
            try {
                com.ale.warbandcraft.battle.tactics.Tactics.forced = com.ale.warbandcraft.battle.tactics.Tactics.Tactic.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.getSource().sendFailure(Component.literal(String.join(", ", tacticNames())));
                return 0;
            }
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.tactic", name), false);
        return 1;
    }

    /** Admin: tus ejércitos que vienen en camino (cuenta regresiva de Invocar) llegan en el próximo segundo. */
    private static int arriveNow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        int n = 0;
        for (var a : com.ale.warbandcraft.army.ArmyData.get(level).of(player.getUUID())) {
            if (a.state != com.ale.warbandcraft.army.ArmyData.State.COMING) continue;
            a.arriveTick = level.getGameTime();
            n++;
        }
        com.ale.warbandcraft.army.ArmyData.get(level).changed();
        int count = n;
        if (count == 0) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.arrive_none"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.arrive", count).withStyle(ChatFormatting.GREEN), false);
        return count;
    }

    /** H23: jugadores con el medidor de rendimiento prendido. */
    private static final java.util.Set<java.util.UUID> PERF = new java.util.HashSet<>();

    private static int togglePerf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!PERF.remove(player.getUUID())) PERF.add(player.getUUID());
        return 1;
    }

    /** Cada segundo: a los que tienen el medidor, los ms por tick del servidor, los TPS y cuántos soldados hay cargados. */
    public static void perfTick(net.minecraft.server.MinecraftServer server) {
        if (PERF.isEmpty()) return;
        double ms = server.getAverageTickTime();
        double tps = Math.min(20, 1000 / Math.max(ms, 1e-3));
        for (java.util.UUID id : PERF) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            int soldiers = 0;
            for (Entity e : p.serverLevel().getAllEntities()) if (e instanceof AbstractRecruitEntity && e.isAlive()) soldiers++;
            ChatFormatting color = ms > 50 ? ChatFormatting.RED : ms > 35 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
            p.displayClientMessage(Component.translatable("msg.warbandcraft.perf", String.format("%.1f", ms), String.format("%.1f", tps), soldiers)
                    .withStyle(color), true);
        }
    }

    /** H15: la facción a la que le sacaste tu aldea/castillo más cercano te declara la guerra para recuperarlo (llega en el acto). */
    private static int reconquest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        for (VillageData.Village v : VillageData.get(level).byDistance(player.blockPosition())) {
            if (!player.getUUID().equals(v.owner())) continue;
            if (com.ale.warbandcraft.invasion.Invasions.reconquer(level, player.getUUID(), v.faction(), 8, 0) != null) {
                com.ale.warbandcraft.invasion.Invasions.tick(level);   // prueba: llega ya
                return 1;
            }
        }
        ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_village"));
        return 0;
    }

    // ------------------------------------------------------------------ H15a: invasión de prueba

    /** Pone en guerra a la facción más cercana y manda un ejército contra tu aldea/castillo más cercano (llega en el acto). */
    private static int invasion(CommandContext<CommandSourceStack> ctx, int count, com.ale.warbandcraft.invasion.InvasionData.Kind kind)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        VillageData.Village mine = null, theirs = null;
        for (VillageData.Village v : data.byDistance(player.blockPosition())) {
            if (mine == null && player.getUUID().equals(v.owner())) mine = v;
            if (theirs == null && !v.isConquered() && !data.isTest(v.center())) theirs = v;
        }
        if (mine == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_village"));
            return 0;
        }
        net.minecraft.resources.ResourceLocation faction = theirs != null ? theirs.faction()
                : FactionManager.INSTANCE.ordered().isEmpty() ? null : FactionManager.INSTANCE.ordered().get(0).id();
        if (faction == null) return 0;
        com.ale.warbandcraft.faction.Relations rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
        int op = rel.opinion(player.getUUID(), faction);
        if (op > com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD) {
            rel.add(player.getUUID(), faction, com.ale.warbandcraft.faction.Relations.WAR_THRESHOLD - op);
        }
        com.ale.warbandcraft.invasion.InvasionData.Invasion inv =
                com.ale.warbandcraft.invasion.Invasions.launch(level, player.getUUID(), faction, count, 0, null, kind);
        if (inv == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_village"));
            return 0;
        }
        com.ale.warbandcraft.invasion.Invasions.tick(level);   // prueba: llega ya (pelea real si estás a menos de 100 bloques)
        return 1;
    }

    // ------------------------------------------------------------------ enemigos

    // H26: nivel de los soldados de prueba: basico / veterano / elite (índice del nivel del kit) o mixto (-1, al azar)
    private static final int LEVEL_BASIC = 0, LEVEL_MIXED = -1;
    private static final Map<String, Integer> LEVELS = Map.of("basico", 0, "veterano", 1, "elite", 2, "mixto", LEVEL_MIXED);

    private interface LevelCommand {
        int run(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException;
    }

    /** Debajo de la cantidad: [faccion] o un nivel (literal) y después [faccion]. Sin nivel = básico. */
    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> withLevels(
            com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> count, LevelCommand plain, @Nullable LevelCommand withFaction) {
        count.executes(ctx -> plain.run(ctx, LEVEL_BASIC));
        if (withFaction != null) count.then(factionArg(ctx -> withFaction.run(ctx, LEVEL_BASIC)));
        for (Map.Entry<String, Integer> e : LEVELS.entrySet()) {
            int lvl = e.getValue();
            var lit = Commands.literal(e.getKey()).executes(ctx -> plain.run(ctx, lvl));
            if (withFaction != null) lit.then(factionArg(ctx -> withFaction.run(ctx, lvl)));
            count.then(lit);
        }
        return count;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> factionArg(
            com.mojang.brigadier.Command<CommandSourceStack> run) {
        return Commands.argument("faccion", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(FactionManager.INSTANCE.ordered().stream().map(f -> f.id().getPath()), b))
                .executes(run);
    }

    /** El índice de nivel del kit para este soldado (mixto: al azar entre los que existen). */
    private static int tierFor(ClassKit kit, int level, RandomSource random) {
        int n = kit.tiers().size();
        int t = level == LEVEL_MIXED ? random.nextInt(n) : Math.min(level, n - 1);
        while (t > 0 && !kit.tiers().get(t).valid()) t--;
        return t;
    }

    private static int enemies(CommandContext<CommandSourceStack> ctx, String type, int count, @Nullable String factionName, int lvl)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<String> pattern = TYPES.get(type);
        if (pattern == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.unknown_type", type));
            return 0;
        }

        // Centro: adelante del jugador, sobre el piso
        Vec3 look = player.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : dir.normalize();
        int x = (int) Math.floor(player.getX() + dir.x * ENEMY_DISTANCE);
        int z = (int) Math.floor(player.getZ() + dir.z * ENEMY_DISTANCE);
        Integer y = Ground.surfaceY(level, x, z, player.getBlockY(), 20, 30);
        if (y == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.no_ground"));
            return 0;
        }
        BlockPos center = new BlockPos(x, y, z);
        VillageData data = VillageData.get(level);
        if (data.nearest(center, VillageManager.MERGE_RADIUS) != null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.village_near"));
            return 0;
        }

        Faction faction = factionName != null
                ? FactionManager.INSTANCE.get(ResourceLocation.tryParse(factionName.contains(":") ? factionName : WarbandCraft.MODID + ":" + factionName))
                : FactionManager.INSTANCE.forBiome(level.getBiome(center));
        if (faction == null && factionName == null && !FactionManager.INSTANCE.ordered().isEmpty()) {
            faction = FactionManager.INSTANCE.ordered().get(0);
        }
        if (faction == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.unknown_faction", String.valueOf(factionName)));
            return 0;
        }

        VillageData.Village village = new VillageData.Village(center, faction.id(), level.getGameTime());
        data.add(village);
        data.markTest(center);

        RandomSource random = level.getRandom();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            ResourceLocation kitId = ResourceLocation.tryParse(WarbandCraft.MODID + ":" + pattern.get(i % pattern.size()));
            ClassKit kit = KitManager.INSTANCE.get(kitId);
            if (kit == null) continue;
            int tier = tierFor(kit, lvl, random);
            Troop troop = new Troop(kit.name(), kitId, tier, 1, null, Map.of(), Optional.empty(), Optional.empty(), true);
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, center, faction, troop, random, ENEMY_SPREAD);
            if (s == null) continue;
            s.setXpLevel(Math.max(1, kit.tiers().get(tier).minLevel()));
            s.getPersistentData().putBoolean(TAG_TEST, true);
            if (s.getVehicle() != null) s.getVehicle().getPersistentData().putBoolean(TAG_TEST, true);
            spawned++;
        }
        if (spawned == 0) {
            data.remove(center);
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.none_spawned"));
            return 0;
        }

        Faction f = faction;
        int n = spawned;
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.enemy",
                Component.literal(f.ofName()).withStyle(f.color()), n, ENEMY_DISTANCE).withStyle(ChatFormatting.GOLD), false);
        RaidManager.start(level, village, player.getUUID(), player);
        return spawned;
    }

    // ------------------------------------------------------------------ tuyos

    private static int mine(CommandContext<CommandSourceStack> ctx, String type, int count, int lvl) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<String> pattern = TYPES.get(type);
        if (pattern == null) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.unknown_type", type));
            return 0;
        }

        RandomSource random = level.getRandom();
        BlockPos camp = CampRegistry.get(level).nearestCamp(player.getUUID(), player.blockPosition());
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            ResourceLocation kitId = ResourceLocation.tryParse(WarbandCraft.MODID + ":" + pattern.get(i % pattern.size()));
            ClassKit kit = PlayerKits.resolve(level.getServer(), player.getUUID(), kitId);
            if (kit == null || kit.tiers().isEmpty() || !kit.tiers().get(0).valid()) continue;
            EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(kit.entity());
            if (entityType == null) continue;
            Entity created = entityType.create(level);
            if (!(created instanceof AbstractRecruitEntity s)) {
                if (created != null) created.discard();
                continue;
            }

            BlockPos near = player.blockPosition().offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
            Vec3 at = Ground.nearestStandable(level, near, 4);
            if (at == null) at = player.position();
            s.moveTo(at.x, at.y, at.z, player.getYRot(), 0F);
            try {
                s.finalizeSpawn(level, level.getCurrentDifficultyAt(s.blockPosition()), MobSpawnType.COMMAND, null, null);
            } catch (ClassCastException e) {
                // Bug de Recruits con el Zapador (ver VillageManager): el equipo lo ponemos nosotros
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) s.setItemSlot(slot, ItemStack.EMPTY);
            s.getInventory().clearContent();
            s.setOwnerUUID(Optional.of(player.getUUID()));
            s.setIsOwned(true);
            s.setPersistenceRequired();
            level.addFreshEntity(s);
            try {
                s.updateTeam();
            } catch (Exception e) {
                WarbandCraft.LOGGER.warn("Soldado de prueba: no se pudo poner el equipo de Recruits: {}", e.toString());
            }

            int tier = tierFor(kit, lvl, random);
            s.setXpLevel(Math.max(1, kit.tiers().get(tier).minLevel()));
            ClassAssigner.equipFree(level, player.getUUID(), s, kit, tier);
            if (camp != null) Barracks.attach(s, camp);
            BattleGeneral.setBattle(s, true);
            s.getPersistentData().putBoolean(TAG_TEST, true);
            if (s.getVehicle() != null) s.getVehicle().getPersistentData().putBoolean(TAG_TEST, true);
            spawned++;
        }
        recount(level, player);
        int n = spawned;
        if (n == 0) {
            ctx.getSource().sendFailure(Component.translatable("cmd.warbandcraft.test.none_spawned"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.mine", n).withStyle(ChatFormatting.AQUA), false);
        return n;
    }

    // ------------------------------------------------------------------ limpiar

    /** H25: una guarida o un túmulo con la boca 12 bloques adelante tuyo, mirando hacia adelante. Queda en tu mapa. */
    private static int dungeon(CommandContext<CommandSourceStack> ctx, com.ale.warbandcraft.dungeon.DungeonData.Kind kind) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        net.minecraft.core.Direction dir = player.getDirection();
        BlockPos at = player.blockPosition().relative(dir, 12);
        com.ale.warbandcraft.dungeon.DungeonData data = com.ale.warbandcraft.dungeon.DungeonData.get(level);
        com.ale.warbandcraft.dungeon.DungeonData.Dungeon d = com.ale.warbandcraft.dungeon.DungeonBuilder.build(level, kind,
                new BlockPos(at.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ()),
                dir, data.newId(), level.getRandom());
        d.name = kind == com.ale.warbandcraft.dungeon.DungeonData.Kind.BARROW ? "Túmulo de prueba" : "Guarida de prueba";
        d.known.add(player.getUUID());
        data.add(d);
        com.ale.warbandcraft.dungeon.Dungeons.populate(level, d, level.getRandom());
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.dungeon", d.name,
                kind == com.ale.warbandcraft.dungeon.DungeonData.Kind.BARROW ? com.ale.warbandcraft.dungeon.Barrows.answerText(d) : Component.literal("-")), false);
        return 1;
    }

    private static int clean(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        VillageData data = VillageData.get(level);
        List<Long> armies = List.copyOf(data.tests());
        for (long key : armies) {
            BlockPos center = BlockPos.of(key);
            RaidManager.discard(level, center);
            data.remove(center);
        }

        int removed = 0;
        for (Entity e : level.getEntities((Entity) null, new AABB(player.blockPosition()).inflate(256),
                e -> e.getPersistentData().getBoolean(TAG_TEST))) {
            if (e instanceof AbstractRecruitEntity) removed++;
            e.ejectPassengers();
            e.discard();
        }
        recount(level, player);
        int n = removed;
        ctx.getSource().sendSuccess(() -> Component.translatable("cmd.warbandcraft.test.cleaned", n, armies.size())
                .withStyle(ChatFormatting.GREEN), false);
        return removed;
    }

    private static void recount(ServerLevel level, ServerPlayer player) {
        try {
            if (RecruitEvents.recruitsPlayerUnitManager != null) {
                RecruitEvents.recruitsPlayerUnitManager.recountRecruits(level.getServer(), player.getUUID());
            }
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudo actualizar el conteo de Recruits: {}", e.toString());
        }
    }
}
