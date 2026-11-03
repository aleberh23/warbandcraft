package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.event.WorkerSafetyEvents;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.Housing;
import com.ale.warbandcraft.village.InsideWalls;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pedido de Ale: la guarnición, el cuartel y los aldeanos esperan adentro de las murallas; los arqueros tiran desde su
 * lugar (no salen a pelear); nadie aparece arriba de una torre (van caminando, y si no hay camino prueban otra cosa);
 * tus órdenes al cuartel se respetan; a los trabajadores de afuera no los atacan; sin camas que sobren no llega gente.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class KeepInsideGameTests {
    private static final String BIG = "empty_big";
    private static final BlockPos CENTER = new BlockPos(8, 1, 8);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");
    /** La muralla de prueba: un anillo de piedra a esta distancia del centro. */
    private static final int WALL = 6;

    /** Una muralla dibujada en anillo: adentro / afuera; un hueco (portón) toma la de los costados; sin muralla, la aldea. */
    @GameTest(template = BIG)
    public static void wallsDefineInside(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        try {
            TownData.Town t = ring(h, true);
            check(InsideWalls.walled(level, v), "con el anillo no hay muralla");
            BlockPos c = v.center();
            check(InsideWalls.inside(level, v, c.east(3)), "a 3 del centro no está adentro");
            check(!InsideWalls.inside(level, v, c.east(WALL + 1)), "afuera de la muralla cuenta como adentro");
            check(!InsideWalls.inside(level, v, c.north(WALL + 1)), "por el portón (hueco) afuera cuenta como adentro");
            check(InsideWalls.inside(level, v, c.north(3)), "adentro, del lado del portón, no está adentro");
            check(InsideWalls.insideOrOnWall(level, v, c.east(WALL)), "arriba de la muralla no cuenta para los arqueros");
            // Sin muralla: la aldea entera
            t.defense.clear();
            t.gates.clear();
            InsideWalls.clear();
            check(!InsideWalls.walled(level, v), "sin muralla sigue amurallada");
            check(InsideWalls.inside(level, v, c.east(WALL + 2)), "sin muralla, cerca del centro no es la aldea");
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /**
     * Cuartel: un zombi afuera de la muralla no lo van a buscar (se queda en su lugar); adentro, sí. El arquero le tira al
     * de afuera sin cambiar de orden (no sale a pelear). Si le das otra orden, la respeta.
     */
    @GameTest(template = BIG, timeoutTicks = 200)
    public static void quartersWaitInsideAndArchersHold(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            ring(h, true);   // solo los datos: un muro de verdad le taparía la vista al arquero
            AbstractRecruitEntity melee = soldier(h, "recruits:recruit", new BlockPos(8, 1, 6), Items.IRON_SWORD, v);
            AbstractRecruitEntity archer = soldier(h, "recruits:bowman", new BlockPos(9, 1, 9), Items.BOW, v);
            Zombie outside = h.spawn(EntityType.ZOMBIE, new BlockPos(8, 1, 8 + WALL + 2));
            outside.setNoAi(true);
            outside.setInvulnerable(true);
            Quarters.tick(level);
            check(melee.getTarget() != outside, "el de a pie salió a buscar a un zombi afuera de la muralla");
            check(melee.getFollowState() == 3, "el de a pie no siguió en su lugar: estado " + melee.getFollowState());
            check(archer.getTarget() == outside, "el arquero no le tira al zombi que tiene a tiro: objetivo " + archer.getTarget()
                    + ", estado " + archer.getFollowState() + "/" + archer.getState() + ", marcas " + archer.getPersistentData()
                    + ", vista " + archer.hasLineOfSight(outside) + ", distancia " + archer.distanceTo(outside)
                    + ", enemigos " + Quarters.enemiesIn(level, v).contains(outside));
            check(archer.getFollowState() == 3, "el arquero cambió de orden para pelear: estado " + archer.getFollowState());

            Zombie in = h.spawn(EntityType.ZOMBIE, new BlockPos(6, 1, 8));
            in.setNoAi(true);
            in.setInvulnerable(true);
            Quarters.tick(level);
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, in.getBlockX(), in.getBlockZ());
            check(melee.getTarget() == in, "el de a pie no fue contra el zombi de adentro: zombi y=" + in.getBlockY() + ", techo y=" + top
                    + " (" + level.getBlockState(new BlockPos(in.getBlockX(), top - 1, in.getBlockZ())) + "), objetivo " + melee.getTarget()
                    + ", marcas " + melee.getPersistentData());
            check(melee.getFollowState() == 0, "el de a pie no salió a pelear adentro");
            check(archer.getFollowState() == 3, "el arquero se movió de su lugar");

            in.discard();
            outside.discard();
            // Recruits lo pone a descansar (estado 0 y después 2 donde está): no es una orden tuya, sigue patrullando
            melee.setTarget(null);
            melee.setFollowState(0);
            Quarters.tick(level);
            check(!melee.getPersistentData().getBoolean(Quarters.TAG_ORDERED), "descansar (estado 0) contó como orden tuya");
            check(melee.getFollowState() == 3, "después de pelear no volvió a patrullar: estado " + melee.getFollowState());
            melee.setFollowState(2);
            Quarters.tick(level);
            check(!melee.getPersistentData().getBoolean(Quarters.TAG_ORDERED), "quedarse quieto solo (estado 2) contó como orden tuya");
            check(melee.getFollowState() == 3, "quieto solo no volvió a patrullar: estado " + melee.getFollowState());
            // Mandarlo a otro lado sí es una orden
            melee.setFollowState(3);
            melee.setHoldPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(14, 1, 14))));
            Quarters.tick(level);
            check(melee.getPersistentData().getBoolean(Quarters.TAG_ORDERED), "mandarlo a otro lado no contó como orden");

            // Una orden tuya: seguirte. El cuartel no la deshace
            archer.setFollowState(1);
            Quarters.tick(level);
            Quarters.tick(level);
            check(archer.getFollowState() == 1, "el cuartel deshizo tu orden: estado " + archer.getFollowState());
            check(archer.getPersistentData().getBoolean(Quarters.TAG_ORDERED), "no quedó a tus órdenes");
            melee.discard();
            archer.discard();
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Arquero de torre: lejos camina (no aparece arriba); al pie de la torre aparece arriba. */
    @GameTest(template = BIG, timeoutTicks = 200)
    public static void towerArcherWalksToFootThenGoesUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        openSky(h);
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            TownData data = TownData.get(level);
            TownData.Town t = data.getOrCreate(v.center());
            // Torre maciza sin escalera: techo de 3×3 a 9 del piso
            BlockPos towerAt = new BlockPos(3, 1, 3);
            for (int x = 2; x <= 4; x++) for (int z = 2; z <= 4; z++) for (int y = 1; y <= 9; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
            t.towers.add(h.absolutePos(towerAt));
            data.changed();
            List<BlockPos> posts = Quarters.posts(level, v);
            check(!posts.isEmpty(), "la torre no tiene puestos");
            // Lejos de la torre: no aparece arriba (camina hasta el pie)
            AbstractRecruitEntity far = soldier(h, "recruits:bowman", new BlockPos(14, 1, 14), Items.BOW, v);
            double y0 = far.getY();
            Quarters.tick(level);
            Quarters.tick(level);
            check(far.getPersistentData().getBoolean("warbandcraft:quarters_tower"), "el arquero no tomó la torre");
            check(far.getY() < y0 + 2, "el arquero lejos apareció arriba de la torre");
            // Ya al pie de la torre: sube (aparece arriba)
            far.teleportTo(h.absolutePos(new BlockPos(5, 1, 3)).getX() + 0.5, y0, h.absolutePos(new BlockPos(5, 1, 3)).getZ() + 0.5);
            Quarters.tick(level);
            check(far.getY() >= y0 + 8, "el arquero al pie de la torre no subió (y " + (far.getY() - y0) + ")");
            far.discard();
        } finally {
            cleanup(h, v);
        }
        h.succeed();
    }

    /** Un portón cerrado que no es de ninguna aldea (empalizada vieja, puesto a mano): el que lo golpea lo rompe. */
    @GameTest(template = BIG, timeoutTicks = 100)
    public static void looseGateGetsBashed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos gate = new BlockPos(8, 1, 8);
        h.setBlock(gate, Blocks.SPRUCE_FENCE_GATE);
        AbstractRecruitEntity s = (AbstractRecruitEntity) h.spawn(EntityType.byString("recruits:recruit").orElseThrow(), new BlockPos(8, 1, 7));
        s.setNoAi(true);
        int before = com.ale.warbandcraft.upgrade.Works.GATE_BASH_TICKS;
        com.ale.warbandcraft.upgrade.Works.GATE_BASH_TICKS = 30;
        try {
            for (int i = 0; i < 3; i++) com.ale.warbandcraft.upgrade.Works.bashFromPath(level, h.absolutePos(gate), s);
        } finally {
            com.ale.warbandcraft.upgrade.Works.GATE_BASH_TICKS = before;
        }
        check(!h.getBlockState(gate).is(Blocks.SPRUCE_FENCE_GATE), "el portón suelto no se rompió");
        s.discard();
        h.succeed();
    }

    /** Un leñador afuera de la aldea: un zombi no lo puede elegir ni lastimar. */
    @GameTest(template = BIG, timeoutTicks = 100)
    public static void workersOutsideAreSafe(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        AbstractRecruitEntity worker = (AbstractRecruitEntity) h.spawn(com.talhanation.workers.init.ModEntityTypes.LUMBERJACK.get(), new BlockPos(4, 1, 4));
        worker.setOwnerUUID(java.util.Optional.of(owner(h)));
        worker.setIsOwned(true);
        worker.setNoAi(true);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(6, 1, 4));
        z.setNoAi(true);
        check(WorkerSafetyEvents.protectedWorker(worker), "el leñador lejos de toda aldea no está protegido");
        z.setTarget(worker);
        check(z.getTarget() != worker, "el zombi eligió al leñador de afuera");
        float hp = worker.getHealth();
        worker.hurt(level.damageSources().mobAttack(z), 4);
        check(worker.getHealth() >= hp, "el zombi lastimó al leñador de afuera");
        worker.discard();
        z.discard();
        h.succeed();
    }

    /** Camas: llega gente solo si sobra una después de los que ya no tienen (y de los chicos). */
    @GameTest(template = BIG, timeoutTicks = 20)
    public static void settlersNeedSpareBeds(GameTestHelper h) {
        check(data(0, 0).spareBeds() == 0, "sin camas sobra una");
        check(data(1, 1).spareBeds() == 0, "con una cama libre y uno sin cama sobra una");
        check(data(3, 1).spareBeds() == 2, "con 3 libres y uno sin cama no sobran 2");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static Housing.Data data(int freeBeds, int homeless) {
        return new Housing.Data(homeless, 0, freeBeds, freeBeds, homeless, 0, 0, 0, List.of(), List.of(), null,
                false, 0, 0, 0, Component.empty(), -1);
    }

    /** Anillo de ladrillos de piedra a {@link #WALL} del centro, con un hueco (portón) al norte. */
    private static TownData.Town ring(GameTestHelper h, boolean dataOnly) {
        ServerLevel level = h.getLevel();
        TownData data = TownData.get(level);
        TownData.Town t = data.getOrCreate(h.absolutePos(CENTER));
        BlockPos c = h.absolutePos(CENTER);
        for (int a = 0; a < 360; a += 3) {
            double r = Math.toRadians(a);
            int x = (int) Math.round(Math.cos(r) * WALL), z = (int) Math.round(Math.sin(r) * WALL);
            BlockPos p = c.offset(x, 0, z);
            if (z <= -WALL + 1 && Math.abs(x) <= 1) {   // hueco del portón al norte
                if (!t.gates.contains(p)) t.gates.add(p);
                continue;
            }
            for (int y = 0; y < 3; y++) {
                t.defense.put(p.above(y).asLong(), Blocks.STONE_BRICKS.defaultBlockState());
                if (!dataOnly) level.setBlockAndUpdate(p.above(y), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        data.changed();
        InsideWalls.clear();
        return t;
    }

    private static AbstractRecruitEntity soldier(GameTestHelper h, String type, BlockPos at, net.minecraft.world.item.Item weapon, VillageData.Village v) {
        EntityType<?> t = EntityType.byString(type).orElseThrow(() -> new GameTestAssertException("Recruits no está cargado"));
        if (!(h.spawn(t, at) instanceof AbstractRecruitEntity s)) throw new GameTestAssertException(type + " no es un soldado");
        s.setOwnerUUID(java.util.Optional.of(v.owner()));
        s.setIsOwned(true);
        s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
        if (weapon == Items.BOW) s.getInventory().setItem(7, new ItemStack(Items.ARROW, 32));
        s.getPersistentData().putLong(Quarters.TAG_QUARTERS, v.center().asLong());
        return s;
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), FACTION, 0).withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static void cleanup(GameTestHelper h, VillageData.Village v) {
        ServerLevel level = h.getLevel();
        VillageStorehouse.forget(level, v);
        TownData.get(level).remove(v.center());
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        InsideWalls.clear();
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-inside@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    /** Las pruebas vienen con un techo de barreras: los soldados miden el piso desde arriba. */
    private static void openSky(GameTestHelper h) {
        for (int x = -1; x <= 17; x++) for (int z = -1; z <= 17; z++) for (int y = 1; y <= 19; y++) {
            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER)) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
