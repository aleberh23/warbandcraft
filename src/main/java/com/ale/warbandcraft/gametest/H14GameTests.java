package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.route.RoadPlanner;
import com.ale.warbandcraft.route.Roads;
import com.ale.warbandcraft.route.RouteData;
import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas del Hito 14 (caminos): grava de 3 de ancho sobre piso plano, puente sobre agua, túnel en una pared,
 * sin escalones de 2, y la obra que deja la ruta "con camino" (viaje más corto).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H14GameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void flatRoadIsThreeWideGravel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        int g = ground(h);
        BlockPos from = h.absolutePos(new BlockPos(4, 1, 0)), to = h.absolutePos(new BlockPos(4, 1, 8));
        RoadPlanner.Result res = RoadPlanner.plan(level, List.of(from.atY(g), to.atY(g)));
        for (int z = 0; z <= 8; z++) {
            for (int x = 3; x <= 5; x++) {
                BlockState s = res.plan.blocks.get(new BlockPos(from.getX() - 4 + x, g, from.getZ() + z));
                check(s != null && s.is(Blocks.GRAVEL), "falta grava en x=" + x + " z=" + z);
            }
        }
        check(res.bridges == 0 && res.tunnels == 0 && res.docks == 0, "piso plano sin puentes ni túneles");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void waterGetsBridgeAndWallGetsTunnel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        int g = ground(h);
        int gy = g - h.absolutePos(BlockPos.ZERO).getY();   // el piso, en relativo
        // Canal de agua en z=2, pared de piedra de 5 de alto en z=5..8
        for (int x = 0; x <= 8; x++) {
            h.setBlock(new BlockPos(x, gy, 2), Blocks.WATER);
            for (int z = 5; z <= 8; z++) for (int y = gy + 1; y <= gy + 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        }
        BlockPos from = h.absolutePos(new BlockPos(4, gy, 0)), to = h.absolutePos(new BlockPos(4, gy, 8));
        RoadPlanner.Result res = RoadPlanner.plan(level, List.of(from, to));
        check(res.bridges == 1, "debería haber 1 puente, hay " + res.bridges);
        BlockState deck = res.plan.blocks.get(h.absolutePos(new BlockPos(4, gy + 1, 2)));
        check(deck != null && deck.is(Blocks.OAK_PLANKS), "el puente no tiene tablones sobre el agua: " + deck);
        BlockState rail = res.plan.blocks.get(h.absolutePos(new BlockPos(2, gy + 2, 2)));
        BlockState rail2 = res.plan.blocks.get(h.absolutePos(new BlockPos(6, gy + 2, 2)));
        check((rail != null && rail.is(Blocks.OAK_FENCE)) || (rail2 != null && rail2.is(Blocks.OAK_FENCE)), "el puente no tiene barandas");
        check(res.tunnels >= 1, "la pared de piedra debería tener un túnel");
        // Adentro de la pared: aire cavado y techo sin tocar
        boolean dug = false;
        for (int z = 5; z <= 8; z++) for (int y = gy + 1; y <= gy + 5; y++) {
            BlockState s = res.plan.blocks.get(h.absolutePos(new BlockPos(4, y, z)));
            if (s != null && s.isAir()) dug = true;
        }
        check(dug, "no cavó la pared");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void noTwoBlockSteps(GameTestHelper h) {
        int[] r = RoadPlanner.smooth(new int[]{0, 0, 0, 3, 3, 3, 3, 0, 0, 0}, new boolean[10]);
        for (int i = 1; i < r.length; i++) check(Math.abs(r[i] - r[i - 1]) <= 1, "escalón de " + Math.abs(r[i] - r[i - 1]) + " en " + i);
        int[] bump = RoadPlanner.smooth(new int[]{5, 5, 6, 5, 5}, new boolean[5]);
        for (int v : bump) check(v == 5, "el montículo de 1 no se aplanó");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void finishedRoadMakesTravelFaster(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos a = camp(h, new BlockPos(1, 1, 1)), b = camp(h, new BlockPos(1, 1, 13));
        UUID id = UUID.randomUUID();
        List<BlockPos> points = List.of(a, b);
        check(Routes.register(level, owner, id, "Camino Real", points) == null, "no se registró la ruta");
        RouteData data = RouteData.get(level);
        RouteData.Route r = data.route(id);
        int slow = Routes.ticks(r);

        // Sin esmeraldas no arranca
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(owner, "ale-h14"));
        player.getInventory().clearContent();
        Component msg = Roads.order(player, id);
        check(data.roadJob(id) == null, "empezó el camino sin esmeraldas: " + msg.getString());

        // Una obra chiquita, ya vencida: pone sus bloques y la ruta queda con camino
        Blueprints.Plan plan = new Blueprints.Plan();
        BlockPos spot = h.absolutePos(new BlockPos(5, 2, 5));
        plan.put(spot, Blocks.GRAVEL.defaultBlockState());
        RouteData.RoadJob job = Roads.start(level, r, plan);
        RouteData.RoadJob old = new RouteData.RoadJob(id, level.getGameTime() - job.durationTicks - 1, job.durationTicks,
                job.positions, job.states, job.palette, job.placed);
        data.putRoadJob(old);
        Roads.placeDue(level, data, old, old.size());
        Roads.tick(level);
        check(level.getBlockState(spot).is(Blocks.GRAVEL), "no puso el bloque del camino");
        check(data.route(id).road(), "la ruta no quedó con camino");
        check(data.roadJob(id) == null, "la obra no terminó");
        RouteData.Route done = data.route(id);
        RouteData.Route longRoad = new RouteData.Route(id, owner, "x", done.points(), done.a(), done.b(), 600, true);
        RouteData.Route longDirt = new RouteData.Route(id, owner, "x", done.points(), done.a(), done.b(), 600, false);
        check(Math.abs(Routes.ticks(longRoad) * 2 - Routes.ticks(longDirt)) <= 1, "con camino el viaje no tarda la mitad: "
                + Routes.ticks(longRoad) + " vs " + Routes.ticks(longDirt));
        check(Routes.ticks(done) <= slow, "con camino tarda más");

        // Mismo trazado: conserva el camino; otro trazado: lo pierde
        check(Routes.register(level, owner, id, "Camino Real", points) == null && data.route(id).road(), "re-registrar igual le sacó el camino");
        check(Routes.register(level, owner, id, "Camino Real", List.of(a, a.east(3), b)) == null, "no se re-registró");
        check(!data.route(id).road(), "con otro trazado debería perder el camino");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** Saca el techo de barreras de la prueba (el camino necesita cielo para medir el piso). */
    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 9; z++) for (int y = 2; y <= 8; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    /** El piso de la prueba (absoluto). */
    private static int ground(GameTestHelper h) {
        BlockPos c = h.absolutePos(new BlockPos(4, 1, 4));
        Integer g = Blueprints.groundY(h.getLevel(), c.getX(), c.getZ(), c.getY());
        check(g != null, "sin piso en la prueba");
        return g;
    }

    private static BlockPos camp(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(rel) instanceof CampBlockEntity camp)) throw new GameTestAssertException("sin Campamento");
        camp.setOwner(owner(h));
        return camp.getBlockPos();
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h14@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
