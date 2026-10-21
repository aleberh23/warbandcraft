package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.tactics.Formation;
import com.ale.warbandcraft.battle.tactics.Morale;
import com.ale.warbandcraft.battle.tactics.Tactics;
import com.ale.warbandcraft.battle.tactics.Tactics.Tactic;
import com.ale.warbandcraft.battle.tactics.TeamAI;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Pruebas del Hito 24 (IA de batalla estilo Bannerlord): qué táctica elige el general según la situación, la geometría de
 * las formaciones, los golpes por el costado y por detrás, y una batalla chica: la infantería avanza en línea y los
 * arqueros van detrás.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H24GameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE)
    public static void generalPicksTactic(GameTestHelper h) {
        int difficulty = Tactics.DIFFICULTY;
        Tactics.DIFFICULTY = 2;   // sin azar
        try {
            RandomSource r = RandomSource.create(3);
            check(pick(Tactics.View.of(10, 2, 2, 5, 2, 1, 2.0), false, false, r) == Tactic.FULL_ATTACK, "más fuerte: tendría que atacar");
            check(pick(Tactics.View.of(8, 2, 0, 12, 2, 2, 0.6), false, false, r) == Tactic.DEFENSIVE_LINE, "más débil: tendría que defenderse en línea");
            check(pick(Tactics.View.of(8, 2, 0, 12, 2, 2, 0.6), false, true, r) == Tactic.HOLD_HILL, "más débil con una loma: tendría que subirse");
            check(pick(Tactics.View.of(3, 8, 0, 8, 1, 0, 1.0), false, false, r) == Tactic.RANGED_HARASS, "con muchos arqueros: tendría que hostigar");
            check(pick(Tactics.View.of(2, 0, 8, 8, 2, 0, 1.2), false, false, r) == Tactic.CAVALRY_CHARGE, "con mucha caballería: carga de caballería");
            check(Tactics.choose(Tactics.View.of(5, 0, 0, 20, 0, 0, 0.2), null, false, false, true, 20 * 60, r) == Tactic.RETREAT,
                    "perdiendo por mucho: tendría que retirarse");
            check(pick(Tactics.View.of(2, 0, 0, 4, 0, 0, 1.0), false, false, r) == Tactic.CHARGE, "ejército chico: carga");
            check(pick(Tactics.View.of(8, 0, 0, 4, 8, 0, 0.8), false, false, r) == Tactic.DEFENSIVE_LINE, "más débil contra arqueros: línea");
            check(pick(Tactics.View.of(8, 0, 0, 4, 8, 0, 0.8).underFire(true), false, false, r) == Tactic.FULL_ATTACK,
                    "bajo flechas sin poder responder: tendría que salir a atacar");
            check(Tactics.choose(Tactics.View.of(10, 2, 2, 8, 2, 1, 1.3), null, true, false, false, 0, r) == Tactic.DEFENSIVE_LINE,
                    "defendiendo su aldea: línea defensiva aunque sea un poco más fuerte");
        } finally {
            Tactics.DIFFICULTY = difficulty;
        }
        h.succeed();
    }

    private static Tactic pick(Tactics.View v, boolean defending, boolean hill, RandomSource r) {
        return Tactics.choose(v, null, defending, hill, false, 0, r);
    }

    @GameTest(template = TEMPLATE)
    public static void formationShapes(GameTestHelper h) {
        Vec3 front = new Vec3(0, 0, 0), north = new Vec3(0, 0, 1);
        List<Vec3> line = Formation.slots(front, north, 12, Formation.Kind.INFANTRY, Formation.Arrangement.LINE);
        check(line.size() == 12, "faltan puestos");
        check(Math.abs(line.get(0).z) < 1e-6 && Math.abs(line.get(6).z + 1.8) < 1e-6, "la línea de 12 no tiene 2 filas");
        check(Math.abs(Math.abs(line.get(1).x - line.get(0).x) - Tactics.LINE_SPACING) < 1e-6, "la separación de la línea no es la de la configuración");
        List<Vec3> wall = Formation.slots(front, north, 12, Formation.Kind.INFANTRY, Formation.Arrangement.SHIELD_WALL);
        double deepest = 0;
        for (Vec3 p : wall) deepest = Math.min(deepest, p.z);
        check(deepest < line.get(11).z, "el muro de escudos no es más profundo que la línea");
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void flankAndRearHits(GameTestHelper h) {
        Vec3 at = Vec3.ZERO;
        check(Morale.angle(0, at, new Vec3(0, 0, 5)) < 1, "de frente no es de frente");
        check(Math.abs(Morale.angle(0, at, new Vec3(5, 0, 0)) - 90) < 1, "de costado no es de costado");
        check(Morale.angle(0, at, new Vec3(0, 0, -5)) > 179, "por detrás no es por detrás");
        h.succeed();
    }

    /** Ataque total: la infantería avanza en línea hacia el enemigo y los arqueros van detrás de ella. */
    @GameTest(template = TEMPLATE, batch = "h24_battle")
    public static void infantryAdvancesArchersBehind(GameTestHelper h) {
        double charge = Tactics.CHARGE_DISTANCE;
        Tactics.CHARGE_DISTANCE = 2;
        List<AbstractRecruitEntity> own = new ArrayList<>();
        List<LivingEntity> enemies = new ArrayList<>();
        String key = "test24:" + h.absolutePos(BlockPos.ZERO).toShortString();
        try {
            for (int i = 0; i < 4; i++) own.add(soldier(h, new BlockPos(2 + i, 2, 1), false));
            for (int i = 0; i < 2; i++) own.add(soldier(h, new BlockPos(3 + i, 2, 0), true));
            for (int i = 0; i < 3; i++) enemies.add(soldier(h, new BlockPos(3 + i, 2, 8), false));
            Vec3 home = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 2, -20)));
            TeamAI.tick(h.getLevel(), key, own, enemies, new TeamAI.Context(home, null, true), Tactic.FULL_ATTACK);
            TeamAI ai = TeamAI.get(key);
            check(ai != null && ai.tactic() == Tactic.FULL_ATTACK, "no tomó la táctica");
            Formation inf = ai.formation(Formation.Kind.INFANTRY), ranged = ai.formation(Formation.Kind.RANGED);
            check(inf != null && inf.behavior() == Formation.Behavior.ADVANCE, "la infantería no avanza");
            check(ranged != null && ranged.behavior() == Formation.Behavior.SCREEN, "los arqueros no van detrás de la infantería");
            double z0 = h.absolutePos(new BlockPos(0, 2, 1)).getZ();
            check(ai.position(Formation.Kind.INFANTRY).z > z0 + 1, "la línea no se movió hacia el enemigo");
            check(ai.position(Formation.Kind.RANGED).z < ai.position(Formation.Kind.INFANTRY).z, "los arqueros no quedaron detrás");
            for (AbstractRecruitEntity s : inf.members()) check(s.getFollowState() == 3 && s.getHoldPos() != null, "un infante no tiene su puesto");
        } finally {
            Tactics.CHARGE_DISTANCE = charge;
            TeamAI.forget(key);
            for (AbstractRecruitEntity s : own) s.discard();
            for (LivingEntity e : enemies) e.discard();
        }
        h.succeed();
    }

    private static AbstractRecruitEntity soldier(GameTestHelper h, BlockPos pos, boolean bow) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", bow ? "bowman" : "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, pos);
        check(e instanceof AbstractRecruitEntity, "no es un recluta");
        AbstractRecruitEntity r = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
        r.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bow ? Items.BOW : Items.IRON_SWORD));
        return r;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
