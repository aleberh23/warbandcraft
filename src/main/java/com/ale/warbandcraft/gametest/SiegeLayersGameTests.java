package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.InsideWalls;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pedido de Ale (4/11: "¿y si hay varias capas de muralla? ¿siguen intentando pasar?"): una aldea con dos murallas
 * cerradas (sin portones ni huecos), una adentro de la otra, y un ejército de verdad (carriles, escaleras, zapadores). Tiene
 * que llegar alguno adentro de la de adentro. Se arma lejos de todo (a 9000) con los chunks cargados a la fuerza.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class SiegeLayersGameTests {
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");
    private static final int INNER = 10, OUTER = 17, HEIGHT = 4, AREA = 70, FLAT = 50;

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void invadersCrossTwoWalls(GameTestHelper h) {
        site(h, 9000, 9000, "ARMY");
    }

    /** Sin zapadores (incursión): solo escaleras, una muralla después de la otra. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void raidersClimbTwoWalls(GameTestHelper h) {
        site(h, 9700, 9000, "RAID");
    }

    private static void site(GameTestHelper h, int cx, int cz, String kind) {
        ServerLevel level = h.getLevel();
        List<ChunkPos> forced = new ArrayList<>();
        for (int x = (cx - AREA) >> 4; x <= (cx + AREA + 220) >> 4; x++) {
            for (int z = (cz - AREA) >> 4; z <= (cz + AREA) >> 4; z++) {
                level.setChunkForced(x, z, true);
                forced.add(new ChunkPos(x, z));
            }
        }
        // El mundo de pruebas no es plano lejos del origen (árboles, lomas): se allana una plaza y las murallas van encima
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        for (int dx = -FLAT; dx <= FLAT; dx++) {
            for (int dz = -FLAT; dz <= FLAT; dz++) {
                for (int k = -6; k <= 30; k++) {
                    BlockPos p = new BlockPos(cx + dx, y + k, cz + dz);
                    level.setBlock(p, k < 0 ? Blocks.STONE.defaultBlockState() : k == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        y++;
        BlockPos center = new BlockPos(cx, y, cz);
        UUID owner = UUID.nameUUIDFromBytes(("warbandcraft-capas@" + cx).getBytes());
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData.Town t = TownData.get(level).getOrCreate(center);
        for (int r : new int[]{INNER, OUTER}) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                for (int dz = -r - 1; dz <= r + 1; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (Math.abs(d - r) > 0.75) continue;
                    for (int k = 0; k < HEIGHT; k++) {
                        BlockPos p = new BlockPos(cx + dx, y + k, cz + dz);
                        level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
                        t.defense.put(p.asLong(), Blocks.STONE_BRICKS.defaultBlockState());
                    }
                }
            }
        }
        TownData.get(level).changed();
        InsideWalls.clear();
        com.ale.warbandcraft.nav.NavGrid.clear();

        InvasionData data = InvasionData.get(level);
        InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, 12, level.getGameTime(), center.offset(200, 0, 0));
        inv.kind = InvasionData.Kind.valueOf(kind);
        data.put(inv);
        List<AbstractRecruitEntity> spawned = new ArrayList<>(Invasions.startFight(level, inv));
        if (spawned.isEmpty()) throw new GameTestAssertException("no apareció el ejército");
        for (AbstractRecruitEntity s : spawned)
            if (dist(s, center) < OUTER + 1) throw new GameTestAssertException("uno apareció adentro de la muralla de afuera: a " + Math.round(dist(s, center)));
        long[] tick = {0};
        Runnable cleanup = () -> {
            for (AbstractRecruitEntity s : Invasions.invaders(level, center)) {
                if (s.getVehicle() != null) s.getVehicle().discard();
                s.discard();
            }
            for (AbstractRecruitEntity s : spawned) s.discard();
            com.ale.warbandcraft.invasion.Assault.end(level, inv);
            data.remove(center);
            TownData.get(level).remove(center);
            VillageData.get(level).remove(center);
            for (ChunkPos c : forced) level.setChunkForced(c.x, c.z, false);
            InsideWalls.clear();
        };
        h.onEachTick(() -> {
            if (++tick[0] % 20 == 0 && data.at(center) != null) Invasions.fightTick(level, inv, v, level.getGameTime());
            if (tick[0] == 20 * 60 * 4 - 40) {   // a punto de vencer: decir dónde quedó cada uno
                StringBuilder b = new StringBuilder();
                for (AbstractRecruitEntity s : Invasions.invaders(level, center))
                    b.append(Math.round(dist(s, center))).append(' ');
                cleanup.run();
                throw new GameTestAssertException("nadie pasó las dos murallas (" + kind + "); distancias al centro: " + b
                        + "(adentro < " + INNER + ", entre murallas < " + OUTER + ")");
            }
        });
        h.succeedWhen(() -> {
            for (AbstractRecruitEntity s : Invasions.invaders(level, center)) {
                if (dist(s, center) < INNER - 1.5 && !com.ale.warbandcraft.sapper.SapperRules.isSapper(s)) {   // no el zapador en su túnel
                    WarbandCraft.LOGGER.info("Capas de muralla ({}): {} llegó adentro en {} ticks", kind, s.getName().getString(), tick[0]);
                    cleanup.run();
                    return;
                }
            }
            throw new GameTestAssertException("todavía nadie adentro");
        });
    }

    private static double dist(AbstractRecruitEntity s, BlockPos c) {
        double dx = s.getX() - (c.getX() + 0.5), dz = s.getZ() - (c.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
