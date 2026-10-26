package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.nav.NavPath;
import com.ale.warbandcraft.nav.Step;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Pruebas de la malla de navegación (asedios estilo Bannerlord): por la puerta abierta; muro cerrado → el plan pasa
 * trepando (escalera nueva) o rompiendo; una escalera puesta es un camino; el agua se rodea si se puede; las puertas las
 * abren los tuyos y no los invasores; y un soldado de verdad sube la escalera y pasa al otro lado del muro.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class NavGameTests {
    private static final String TEMPLATE = "empty";
    /** El muro va de punta a punta en x = 4 (relativo). */
    private static final int WALL_X = 4;

    @GameTest(template = TEMPLATE)
    public static void pathUsesTheGap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        wall(h, feet, 3, 6);   // hueco en z = 6
        List<Step> p = NavPath.find(level, abs(h, 1, feet, 2), center(h, 7, feet, 2), 0.6, NavCaps.INVADER, bounds(h), false);
        if (p == null) {
            StringBuilder sb = new StringBuilder("no encontró el camino por el hueco. pies=" + feet);
            for (int x = 0; x <= 8; x++) {
                BlockPos a = abs(h, x, feet, 2);
                sb.append(" x").append(x).append("=").append(java.util.Arrays.toString(com.ale.warbandcraft.nav.NavGrid.standable(level, a.getX(), a.getZ(), feet - 6, feet + 6)));
            }
            BlockPos a = abs(h, 1, feet, 2);
            sb.append(" top=").append(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, a.getX(), a.getZ()));
            sb.append(" bajo=").append(level.getBlockState(a.below())).append(" en=").append(level.getBlockState(a));
            throw new GameTestAssertException(sb.toString());
        }
        boolean throughGap = false;
        for (Step s : p) {
            BlockPos r = rel(h, s.blockPos());
            if (r.getX() == WALL_X) throughGap = r.getZ() == 6;
            check(s.kind() != Step.Kind.CLIMB && s.kind() != Step.Kind.BREAK, "con hueco igual planeó trepar/romper");
        }
        check(throughGap, "no pasó por el hueco");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void closedWallPlansLadderOrBreach(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        wall(h, feet, 3, -1);
        check(NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.INVADER, bounds(h), false) == null,
                "pasó un muro cerrado sin escalera");
        List<Step> climb = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.siege(false), bounds(h), false);
        check(climb != null && NavPath.first(climb, Step.Kind.CLIMB) > 0, "el plan del asedio no trepa el muro");
        Step c = climb.get(NavPath.first(climb, Step.Kind.CLIMB));
        check(rel(h, BlockPos.of(c.wall())).getX() == WALL_X, "la escalera no va contra el muro");
        // Con zapador: romper sale más barato que trepar 3 de alto? da igual cuál, pero tiene que haber plan
        List<Step> brk = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.siege(true), bounds(h), false);
        check(brk != null && (NavPath.first(brk, Step.Kind.BREAK) > 0 || NavPath.first(brk, Step.Kind.CLIMB) > 0), "con zapador no hay plan");

        // Con la escalera puesta, es un camino normal (por los peldaños)
        BlockPos foot = climb.get(NavPath.first(climb, Step.Kind.CLIMB) - 1).blockPos();
        Breach.Ladder l = Breach.placeLadder(level, new Breach.Probe(true, foot, BlockPos.of(c.wall()), foot.getY(), 0));
        check(l != null, "no se pudo poner la escalera");
        List<Step> walk = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.INVADER, bounds(h), false);
        check(walk != null && NavPath.first(walk, Step.Kind.LADDER) > 0, "la escalera puesta no es camino");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void waterIsAvoided(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        // Agua en x = 4 de z = 0 a 6; por z = 7-8 se rodea
        for (int z = 0; z <= 6; z++) h.setBlock(new BlockPos(WALL_X, feet - 1 - origin(h).getY(), z), Blocks.WATER);
        List<Step> p = NavPath.find(level, abs(h, 1, feet, 1), center(h, 7, feet, 1), 0.6, NavCaps.INVADER, bounds(h), false);
        check(p != null && NavPath.water(p) == 0, "cruzó el agua pudiendo rodearla");
        // Sin vuelta: cruza (en bote)
        for (int z = 7; z <= 8; z++) h.setBlock(new BlockPos(WALL_X, feet - 1 - origin(h).getY(), z), Blocks.WATER);
        List<Step> q = NavPath.find(level, abs(h, 1, feet, 1), center(h, 7, feet, 1), 0.6, NavCaps.INVADER, bounds(h), false);
        check(q != null && NavPath.water(q) > 0, "sin vuelta no cruzó el agua");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void doorsOnlyForTheOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        wall(h, feet, 3, 4);
        int y = feet - origin(h).getY();
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        h.setBlock(new BlockPos(WALL_X, y, 4), door);
        h.setBlock(new BlockPos(WALL_X, y + 1, 4), door.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        List<Step> mine = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.SOLDIER, bounds(h), false);
        check(mine != null && NavPath.first(mine, Step.Kind.DOOR) > 0, "tu soldado no pasa por la puerta");
        check(NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.INVADER, bounds(h), false) == null,
                "el invasor abrió tu puerta");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void soldierClimbsTheLadder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        wall(h, feet, 3, -1);
        List<Step> plan = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.siege(false), bounds(h), false);
        check(plan != null, "no hay plan");
        int k = NavPath.first(plan, Step.Kind.CLIMB);
        BlockPos foot = plan.get(k - 1).blockPos();
        check(Breach.placeLadder(level, new Breach.Probe(true, foot, BlockPos.of(plan.get(k).wall()), foot.getY(), 0)) != null,
                "no se pudo poner la escalera");

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "no está el recluta de Recruits");
        if (!(type.create(level) instanceof AbstractRecruitEntity s)) throw new GameTestAssertException("no se creó el recluta");
        BlockPos start = abs(h, 1, feet, 4);
        s.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        s.finalizeSpawn(level, level.getCurrentDifficultyAt(start), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(s);
        Vec3 goal = center(h, 7, feet, 4);
        check(Mover.go(s, goal, 0.6, NavCaps.INVADER, bounds(h), "test"), "no arrancó el camino");
        h.succeedWhen(() -> {
            if (s.getX() < origin(h).getX() + WALL_X + 1) throw new GameTestAssertException("todavía no pasó el muro: " + s.position()
                    + " paso " + (Mover.run(s) == null ? "-" : Mover.run(s).i));
            s.discard();
        });
    }

    /** Pedido de Ale: tu soldado mandado arriba de un muro/torre (sin ser del cuartel) sube por la escalera y se queda. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void orderedSoldierClimbsAndStays(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        wall(h, feet, 3, -1);
        List<Step> plan = NavPath.find(level, abs(h, 1, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.siege(false), bounds(h), false);
        check(plan != null, "no hay plan");
        int k = NavPath.first(plan, Step.Kind.CLIMB);
        BlockPos foot = plan.get(k - 1).blockPos();
        BlockPos wallAt = BlockPos.of(plan.get(k).wall());
        check(Breach.placeLadder(level, new Breach.Probe(true, foot, wallAt, foot.getY(), 0)) != null, "no se pudo poner la escalera");

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        if (type == null || !(type.create(level) instanceof AbstractRecruitEntity s)) throw new GameTestAssertException("no se creó el recluta");
        BlockPos start = abs(h, 1, feet, 4);
        s.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        s.finalizeSpawn(level, level.getCurrentDifficultyAt(start), MobSpawnType.EVENT, null, null);
        level.addFreshEntity(s);
        Vec3 top = new Vec3(wallAt.getX() + 0.5, feet + 3, wallAt.getZ() + 0.5);
        s.setHoldPos(top);
        s.setFollowState(3);
        h.onEachTick(() -> {
            if (level.getGameTime() % 10 == 0) com.ale.warbandcraft.ai.Unstuck.update(level, s, 10);
        });
        h.succeedWhen(() -> {
            if (Math.abs(s.getY() - top.y) > 0.6 || s.position().distanceToSqr(top.x, s.getY(), top.z) > 2.25)
                throw new GameTestAssertException("todavía no está arriba: " + s.position() + " (arriba = " + top + ")");
            s.discard();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void tunnelLetsThemIn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int feet = setup(h);
        int y0 = feet - origin(h).getY();
        for (int z = 0; z <= 8; z++) for (int y = y0; y < y0 + 3; y++) h.setBlock(new BlockPos(3, y, z), Blocks.STONE_BRICKS);
        BlockPos foot = abs(h, 2, feet, 4), wall = abs(h, 3, feet, 4), center = abs(h, 8, feet, 4);
        check(NavPath.find(level, abs(h, 0, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.INVADER, bounds(h), false) == null,
                "pasó el muro sin túnel");
        Breach.Walls w = new Breach.Walls(4, true, 1, 6, 10, 2);
        List<Breach.TunnelCell> t = Breach.tunnel(level, foot, wall, center, w);
        check(t != null && t.size() >= 6, "no planeó el túnel: " + t);
        for (Breach.TunnelCell c : t) for (int k = 0; k < c.height(); k++) level.destroyBlock(c.feet().above(k), false);
        check(level.getBlockState(abs(h, 3, feet, 4)).is(Blocks.STONE_BRICKS), "el túnel rompió el muro (tiene que pasar por abajo)");
        List<Step> p = NavPath.find(level, abs(h, 0, feet, 4), center(h, 7, feet, 4), 0.6, NavCaps.INVADER,
                new NavPath.Bounds(origin(h).getX() - 1, origin(h).getX() + 9, origin(h).getZ(), origin(h).getZ() + 8), false);
        if (p == null || NavPath.first(p, Step.Kind.CLIMB) >= 0) {
            StringBuilder sb = new StringBuilder("no hay camino por el túnel. pies=" + feet + " celdas=");
            for (Breach.TunnelCell c : t) sb.append(rel(h, c.feet()).toShortString()).append("/").append(c.height()).append(" ");
            for (int x = -1; x <= 9; x++) {
                BlockPos a = abs(h, x, feet, 4);
                sb.append(" x").append(x).append("=").append(java.util.Arrays.toString(com.ale.warbandcraft.nav.NavGrid.standable(level, a.getX(), a.getZ(), feet - 6, feet + 4)));
            }
            throw new GameTestAssertException(sb.toString());
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** Saca las barreras del techo y los costados, y devuelve la altura de los pies (absoluta). */
    private static int setup(GameTestHelper h) {
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 9; z++) for (int y = 2; y <= 9; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos c = h.absolutePos(new BlockPos(4, 1, 4));
        Integer y = com.ale.warbandcraft.util.Ground.surfaceY(h.getLevel(), c.getX(), c.getZ(), c.getY(), 4, 4);
        if (y == null) throw new GameTestAssertException("no hay piso");
        return y;
    }

    /** Muro de {@code height} de alto en x = 4, de z = 0 a 8, con un hueco en {@code gapZ} (-1: sin hueco). */
    private static void wall(GameTestHelper h, int feet, int height, int gapZ) {
        int y0 = feet - origin(h).getY();
        for (int z = 0; z <= 8; z++) {
            if (z == gapZ) continue;
            for (int y = y0; y < y0 + height; y++) h.setBlock(new BlockPos(WALL_X, y, z), Blocks.STONE_BRICKS);
        }
    }

    private static BlockPos origin(GameTestHelper h) {
        return h.absolutePos(BlockPos.ZERO);
    }

    private static BlockPos abs(GameTestHelper h, int x, int feet, int z) {
        BlockPos o = origin(h);
        return new BlockPos(o.getX() + x, feet, o.getZ() + z);
    }

    private static Vec3 center(GameTestHelper h, int x, int feet, int z) {
        return Vec3.atBottomCenterOf(abs(h, x, feet, z));
    }

    private static BlockPos rel(GameTestHelper h, BlockPos p) {
        return p.subtract(origin(h));
    }

    private static NavPath.Bounds bounds(GameTestHelper h) {
        BlockPos o = origin(h);
        return new NavPath.Bounds(o.getX(), o.getX() + 8, o.getZ(), o.getZ() + 8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
