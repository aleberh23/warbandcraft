package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.castle.CastleKind;
import com.ale.warbandcraft.castle.CastleShape;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.nav.NavPath;
import com.ale.warbandcraft.village.CastleData;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Castillos por facción (pedido de Ale): cada forma, girada, tiene el adentro donde dice su forma, muros que se
 * escalan, un portón por el que se entra caminando, lugares para arqueros arriba, armería y tesoro. El clásico toma
 * los materiales de la facción. Se arman lejos (a 4000+ bloques) para no pisar nada.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class CastleGameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, batch = "castle_fortress", timeoutTicks = 200)
    public static void castleFortress(GameTestHelper h) {
        checkCastle(h, "valdren", CastleKind.FORTRESS, Rotation.CLOCKWISE_90, 4000, 0);
    }

    @GameTest(template = TEMPLATE, batch = "castle_longfort", timeoutTicks = 200)
    public static void castleLongFort(GameTestHelper h) {
        checkCastle(h, "norte", CastleKind.LONGFORT, Rotation.CLOCKWISE_180, 4400, 0);
    }

    @GameTest(template = TEMPLATE, batch = "castle_kasbah", timeoutTicks = 200)
    public static void castleKasbah(GameTestHelper h) {
        checkCastle(h, "sultanato", CastleKind.KASBAH, Rotation.COUNTERCLOCKWISE_90, 4800, 0);
    }

    @GameTest(template = TEMPLATE, batch = "castle_swamp", timeoutTicks = 200)
    public static void castleSwampFort(GameTestHelper h) {
        checkCastle(h, "pantanos", CastleKind.SWAMPFORT, Rotation.NONE, 5200, 0);
    }

    /** El clásico girado con los materiales del Sultanato: arenisca y nada de adoquín. */
    @GameTest(template = TEMPLATE, batch = "castle_classic", timeoutTicks = 200)
    public static void castleClassicRestyled(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = checkCastle(h, "sultanato", CastleKind.CLASSIC, Rotation.CLOCKWISE_90, 5600, 0);
        CastleShape s = CastleManager.shape(level, v);
        int cobble = 0, sand = 0;
        for (BlockPos p : BlockPos.betweenClosed(s.minX() - 8, s.floorY() - 2, s.minZ() - 8, s.maxX() + 8, s.floorY() + 20, s.maxZ() + 8)) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE)) cobble++;
            if (st.is(Blocks.SANDSTONE) || st.is(Blocks.SMOOTH_SANDSTONE)) sand++;
        }
        check(cobble == 0, "el clásico del Sultanato quedó con " + cobble + " de adoquín");
        check(sand > 1000, "el clásico del Sultanato tiene poca arenisca: " + sand);
        cleanup(level, v);
        h.succeed();
    }

    /** Lo que tiene que tener todo castillo; deja el castillo armado (lo limpia cada prueba al final). */
    private static VillageData.Village checkCastle(GameTestHelper h, String factionId, CastleKind kind, Rotation rot, int x, int z) {
        ServerLevel level = h.getLevel();
        Faction f = FactionManager.INSTANCE.get(new ResourceLocation(WarbandCraft.MODID, factionId));
        check(f != null, "no se cargó la facción " + factionId);
        VillageData.Village v = CastleManager.place(level, new BlockPos(x, 80, z), f, kind, rot);
        check(v != null, "no se pudo plantar el castillo " + kind.id);
        try {
            CastleShape s = CastleManager.shape(level, v);
            check(CastleData.get(level).shape(v.center()) != null, "no se guardó la forma");
            check(s.kind() == kind && s.rotation() == rot, "forma guardada rara: " + s.kind() + " " + s.rotation());
            BlockPos c = v.center();
            check(CastleManager.insideWalls(level, v, c), "el centro no figura adentro de los muros");
            check(!CastleManager.insideWalls(level, v, new BlockPos(s.maxX() + 6, c.getY(), c.getZ())), "afuera del muro figura adentro");
            check(Breach.walls(level, v).walled() && Breach.walls(level, v).radius() == s.radius(), "Breach no usa la forma del castillo");
            check(!BattleGeneral.wallPosts(level, c).isEmpty(), "no hay lugares para arqueros arriba");
            check(VillageManager.garrison(level, c).size() >= CastleManager.GARRISON_MIN, "guarnición chica");

            int barrels = 0, chests = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.getX() - 24, c.getY() - 3, c.getZ() - 24, c.getX() + 24, c.getY() + 24, c.getZ() + 24)) {
                BlockState st = level.getBlockState(p);
                if (st.is(Blocks.BARREL)) barrels++;
                else if (st.is(Blocks.CHEST)) chests++;
            }
            check(barrels >= 3, "pocas armerías: " + barrels);
            check(chests >= 1, "falta el cofre del tesoro");

            // Muros que se escalan (los dibujados; el clásico es el plano de siempre): desde afuera, por los 4 lados, la primera columna alta no pasa del largo de una escalera
            for (Direction d : kind == CastleKind.CLASSIC ? new Direction[0] : Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new)) {
                for (int dist = s.halfX() + 1; dist >= 4; dist--) {   // desde el borde de lo allanado (más afuera puede haber lomas)
                    BlockPos col = c.relative(d, dist);
                    int top = topY(level, col.getX(), col.getZ(), c.getY() + 22, c.getY() - 3);
                    if (top <= c.getY()) continue;
                    check(top - c.getY() + 1 <= Breach.MAX_LADDER, kind.id + ": muro de " + (top - c.getY() + 1) + " al " + d);
                    break;
                }
            }

            if (kind != CastleKind.CLASSIC) {   // se entra caminando por el portón (ni escaleras ni romper)
                Direction gate = rot.rotate(Direction.SOUTH);
                BlockPos start = c.relative(gate, s.halfX() + 1);
                java.util.List<com.ale.warbandcraft.nav.Step> path = NavPath.find(level, start, Vec3.atBottomCenterOf(c), 1.5, NavCaps.INVADER,
                        NavPath.Bounds.around(c, 30), false);
                check(path != null, kind.id + ": no se llega caminando del portón (" + gate + ") al patio");
            }
        } catch (RuntimeException e) {
            cleanup(level, v);
            throw e;
        }
        if (kind != CastleKind.CLASSIC) {
            cleanup(level, v);
            h.succeed();
        }
        return v;
    }

    /** El bloque sólido más alto de la columna entre {@code from} y {@code to}, o {@code to} si no hay. */
    private static int topY(ServerLevel level, int x, int z, int from, int to) {
        for (int y = from; y > to; y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && st.blocksMotion()) return y;
        }
        return to;
    }

    private static void cleanup(ServerLevel level, VillageData.Village v) {
        for (AbstractRecruitEntity s : VillageManager.garrison(level, v.center())) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
        }
        VillageData.get(level).remove(v.center());
        CastleData.get(level).removeShape(v.center());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
