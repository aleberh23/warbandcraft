package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.invasion.Assault;
import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.sapper.SapperRules;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.upgrade.WallRepair;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pruebas de las invasiones de verdad (asedios): el ejército aparece afuera de los muros, en columnas, con general y
 * zapadores; se retira con muchas bajas; la medición encuentra el muro y pone la escalera (y tira la almena); el
 * muro roto se detecta y se repara; los zapadores le bajan el valor a los muros en la autoresolución.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H17GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    /** El TNT de asedio no hace cráter: abre un paso de 1 x 2 que atraviesa un muro de 3 de grueso, y nada más. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void siegeTntOpensOnlyThePassage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        clearBarriers(h);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        Integer feet = Ground.surfaceY(level, origin.getX() + 2, origin.getZ() + 4, origin.getY() + 1, 4, 4);
        check(feet != null, "no encontró el piso");
        for (int x = 4; x <= 6; x++) for (int z = 2; z <= 6; z++) for (int y = feet; y < feet + 4; y++) {
            level.setBlockAndUpdate(new BlockPos(origin.getX() + x, y, origin.getZ() + z), Blocks.STONE_BRICKS.defaultBlockState());
        }
        BlockPos foot = new BlockPos(origin.getX() + 3, feet, origin.getZ() + 4);
        List<BlockPos> hole = com.ale.warbandcraft.battle.SiegeLanes.holeCells(foot, foot.east(4));
        check(hole.size() == 6, "el paso tendría que ser de 3 de largo y 2 de alto: " + hole);
        SapperRules.markHole(SapperRules.spawnTnt(level, Vec3.atBottomCenterOf(foot), Vec3.ZERO, 1, null, null), hole);
        h.runAfterDelay(20, () -> {
            for (BlockPos p : hole) check(level.getBlockState(p).isAir(), "quedó un bloque en el paso: " + p);
            check(level.getBlockState(foot.east().above(2)).is(Blocks.STONE_BRICKS), "rompió arriba del paso");
            check(level.getBlockState(foot.east().north()).is(Blocks.STONE_BRICKS), "rompió al costado del paso");
            check(!level.getBlockState(foot.below()).isAir(), "hizo un pozo abajo del TNT");
            h.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void probeFindsWallAndPlacesLadder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        clearBarriers(h);
        BlockPos center = h.absolutePos(CENTER);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        Integer feet = Ground.surfaceY(level, center.getX(), center.getZ(), center.getY(), 4, 4);
        check(feet != null, "no encontró el piso");
        // Muro de 3 de alto en x = 7 (relativo), de z = 1 a 7, con una almena arriba en la fila del medio
        int wx = origin.getX() + 7;
        for (int z = 1; z <= 7; z++) for (int y = feet; y < feet + 3; y++) {
            level.setBlockAndUpdate(new BlockPos(wx, y, origin.getZ() + z), Blocks.STONE_BRICKS.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(wx, feet + 3, center.getZ()), Blocks.STONE_BRICK_WALL.defaultBlockState());

        // Desde afuera (este, ángulo 0) hacia el centro
        Breach.Probe p = Breach.probe(level, center, 0, 4.4, 0.5);
        check(p.blocked(), "no encontró el muro");
        check(p.wall().getX() == wx && p.outside().getX() == wx + 1, "el muro/afuera no son los esperados: " + p);
        check(p.feetY() == feet, "midió mal la altura de los pies: " + p.feetY() + " en vez de " + feet);

        StringBuilder before = new StringBuilder();
        for (int y = feet - 1; y <= feet + 5; y++) {
            before.append(" y").append(y - feet).append(": afuera=").append(level.getBlockState(new BlockPos(wx + 1, y, center.getZ())).getBlock())
                    .append(" muro=").append(level.getBlockState(new BlockPos(wx, y, center.getZ())).getBlock());
        }
        Breach.Ladder ladder = Breach.placeLadder(level, p);
        check(ladder != null, "no puso la escalera:" + before);
        check(ladder.intoWall() == Direction.WEST, "la escalera no mira al muro: " + ladder.intoWall());
        check(ladder.topFeet() == feet + 3, "la escalera no llega arriba: " + ladder.topFeet());
        for (int y = feet; y < feet + 3; y++) {
            var s = level.getBlockState(new BlockPos(wx + 1, y, center.getZ()));
            check(s.is(Blocks.LADDER) && s.getValue(LadderBlock.FACING) == Direction.EAST, "falta la escalera en y=" + y);
        }
        check(level.getBlockState(new BlockPos(wx, feet + 3, center.getZ())).isAir(), "no tiró la almena de arriba");

        // Del otro lado no hay muro: abierto
        check(!Breach.probe(level, center, Math.PI, 4.4, 0.5).blocked(), "encontró muro donde no hay");

        Breach.removeLadder(level, ladder.base());
        for (int y = feet; y < feet + 3; y++) {
            check(!level.getBlockState(new BlockPos(wx + 1, y, center.getZ())).is(Blocks.LADDER), "quedó la escalera en y=" + y);
        }

        // Dos bloques de muro que se tocan por la esquina (como en los anillos de H12): en diagonal no se pasa
        for (int y = feet; y < feet + 3; y++) {
            level.setBlockAndUpdate(new BlockPos(origin.getX() + 7, y, origin.getZ() + 8), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(origin.getX() + 8, y, origin.getZ() + 7), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(origin.getX() + 7, y, origin.getZ() + 7), Blocks.AIR.defaultBlockState());
        }
        Breach.Probe diag = Breach.probe(level, center, Math.PI / 4, 5.6, 0.5);
        check(diag.blocked(), "en diagonal pasó entre dos bloques que se tocan por la esquina");

        // Agua: no es paso
        for (int z = 0; z <= 8; z++) level.setBlockAndUpdate(new BlockPos(origin.getX() + 1, feet - 1, origin.getZ() + z), Blocks.WATER.defaultBlockState());
        check(Breach.probe(level, center, Math.PI, 4.4, 0.5).blocked(), "midió el agua como paso libre");
        h.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void armySpawnsOutsideInColumns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData.Town town = TownData.get(level).getOrCreate(center);
        town.built.add(Upgrade.PALISADE);
        town.ringRadius = 4;
        town.ringWalls = true;   // H22: el anillo automático de H12
        List<AbstractRecruitEntity> spawned = new ArrayList<>();
        InvasionData data = InvasionData.get(level);
        try {
            InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, 10, level.getGameTime(), center.offset(200, 0, 0));
            inv.kind = InvasionData.Kind.ARMY;
            data.put(inv);
            spawned.addAll(Invasions.startFight(level, inv));
            check(spawned.size() == 10, "aparecieron " + spawned.size() + " de 10");
            check(inv.startSize == 10, "no anotó cuántos aparecieron");
            int generals = 0, sappers = 0;
            Set<Integer> groups = new HashSet<>();
            for (AbstractRecruitEntity s : spawned) {
                if (s.getPersistentData().getBoolean(Assault.TAG_GENERAL)) generals++;
                else groups.add(Assault.groupIndex(s));
                if (SapperRules.isSapper(s)) sappers++;
                check(horizontal(s, center) >= town.ringRadius + 5, "uno apareció adentro o pegado al muro: a " + horizontal(s, center));
            }
            check(generals == 1, "generales: " + generals);
            check(sappers >= 1, "el ejército no trae zapadores");
            check(groups.size() == 2, "columnas: " + groups);
            // Las dos columnas, de lados distintos
            double a0 = angleOf(spawned, 0, center), a1 = angleOf(spawned, 1, center);
            double diff = Math.abs(Math.atan2(Math.sin(a0 - a1), Math.cos(a0 - a1)));
            check(Math.toDegrees(diff) >= 40, "las columnas aparecieron del mismo lado: " + Math.toDegrees(diff) + "°");
            // (Ya no se mira que la principal venga de sus tierras: desde "el lado más débil" de SiegePush puede rodear.)

            Invasions.fightTick(level, inv, v, level.getGameTime());
            check(Assault.phase(level, center) == Assault.Phase.FORM, "el ejército no formó antes del asalto: " + Assault.phase(level, center));

            // Muchas bajas: se retiran; los que llegan lejos se van y vuelven a la leva
            for (int i = 0; i < 7; i++) spawned.get(spawned.size() - 1 - i).discard();
            Invasions.fightTick(level, inv, v, level.getGameTime());
            check(Assault.phase(level, center) == Assault.Phase.RETREAT, "con 7 de 10 caídos no se retiran: " + Assault.phase(level, center));
            int levy = data.levy(FACTION);
            for (AbstractRecruitEntity s : spawned) {
                if (!s.isRemoved()) teleport(s, center.getX() + 0.5 + town.ringRadius + Assault.RETREAT_DISTANCE, s.getY(), center.getZ() + 0.5);
            }
            Invasions.fightTick(level, inv, v, level.getGameTime());
            check(data.at(center) == null, "la invasión sigue después de retirarse");
            check(data.levy(FACTION) >= Math.min(Invasions.LEVY_CAP, levy + 3), "los que se retiraron no volvieron a la leva");
            check(owner.equals(VillageData.get(level).at(center).owner()), "se retiraron y perdiste la aldea");
            check(Assault.phase(level, center) == null, "quedó el plan del asalto en memoria");
        } finally {
            for (AbstractRecruitEntity s : spawned) {
                if (s.getVehicle() != null) s.getVehicle().discard();
                s.discard();
            }
            data.remove(center);
            TownData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void brokenWallIsRepaired(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        clearBarriers(h);
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData.Town town = TownData.get(level).getOrCreate(center);
        town.built.add(Upgrade.PALISADE);
        town.built.add(Upgrade.WALL);
        town.ringRadius = 4;
        town.ringWalls = true;   // H22: el anillo automático de H12
        try {
            Blueprints.Plan ring = Blueprints.ring(level, center, 4, true);
            check(ring.size() > 20, "el anillo de prueba salió chico: " + ring.size());
            for (Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e : ring.blocks.entrySet()) {
                level.setBlockAndUpdate(e.getKey(), e.getValue());
            }
            WallRepair.forget(center);
            check(WallRepair.missing(level, v).size() == 0, "la muralla entera aparece rota: " + WallRepair.missing(level, v).size());

            // Un agujero de 3 bloques (como el de un zapador)
            BlockPos hole = ring.blocks.keySet().iterator().next();
            for (int dy = 0; dy < 3; dy++) level.setBlockAndUpdate(hole.above(dy), Blocks.AIR.defaultBlockState());
            WallRepair.forget(center);
            WallRepair.Damage d = WallRepair.damage(level, v);
            check(d.missing() >= 2, "no detectó el agujero: " + d);
            check(d.cost() >= WallRepair.MIN_COST, "costo raro: " + d);

            Blueprints.Plan fix = WallRepair.missing(level, v);
            Upgrades.start(level, town, Upgrade.REPAIR, fix);
            town.job = new TownData.Job(Upgrade.REPAIR, level.getGameTime() - 10_000, 20, town.job.positions, town.job.states, town.job.palette);
            Upgrades.tick(level);
            check(town.job == null, "la reparación no terminó");
            check(!town.has(Upgrade.REPAIR), "la reparación quedó como mejora hecha");
            WallRepair.forget(center);
            check(WallRepair.missing(level, v).size() == 0, "después de reparar sigue rota: " + WallRepair.missing(level, v).size());
        } finally {
            TownData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void sappersWeakenWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData.get(level).getOrCreate(center).built.add(Upgrade.WALL);
        try {
            List<BarracksData.Soldier> none = List.of();
            double plain = Invasions.defense(level, v, none, false), sapped = Invasions.defense(level, v, none, true);
            check(sapped < plain, "con zapadores la muralla vale lo mismo: " + sapped + " / " + plain);
            check(sapped > Invasions.defense(level, new VillageData.Village(center.offset(500, 0, 0), FACTION, 0), none, true) - 1e-6,
                    "con zapadores la muralla vale menos que nada");
        } finally {
            TownData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static void clearBarriers(GameTestHelper h) {
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 9; z++) for (int y = 2; y <= 8; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static double horizontal(Entity e, BlockPos c) {
        double dx = e.getX() - (c.getX() + 0.5), dz = e.getZ() - (c.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double angleOf(List<AbstractRecruitEntity> all, int group, BlockPos c) {
        double x = 0, z = 0;
        int n = 0;
        for (AbstractRecruitEntity s : all) {
            if (s.getPersistentData().getBoolean(Assault.TAG_GENERAL) || Assault.groupIndex(s) != group) continue;
            x += s.getX();
            z += s.getZ();
            n++;
        }
        check(n > 0, "la columna " + group + " está vacía");
        return Math.atan2(z / n - c.getZ() - 0.5, x / n - c.getX() - 0.5);
    }

    private static void teleport(AbstractRecruitEntity s, double x, double y, double z) {
        Entity body = s.getVehicle() != null ? s.getVehicle() : s;
        body.teleportTo(x, y, z);
        if (body != s) s.teleportTo(x, y, z);
        s.setPos(new Vec3(x, y, z));
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h17@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
