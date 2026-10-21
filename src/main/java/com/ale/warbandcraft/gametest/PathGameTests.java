package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.ai.Formation;
import com.ale.warbandcraft.ai.Unstuck;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del destrabador de soldados y del reparto en grilla de la formación Libre.
 * En la estructura "empty" el piso de piedra queda en y=1 relativo: los soldados se paran en y=2.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class PathGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos PIT = new BlockPos(3, 2, 4);

    /** Encerrado entre paredes con un destino afuera: primero salta y después sale del pozo hacia su destino. */
    @GameTest(template = TEMPLATE)
    public static void stuckSoldierHopsOut(GameTestHelper h) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int y = 0; y < 3; y++) h.setBlock(PIT.offset(dx, y, dz), Blocks.STONE);
            }
        }
        AbstractRecruitEntity r = spawnRecruit(h, PIT);
        Vec3 dest = Vec3.atBottomCenterOf(h.absolutePos(PIT.east(5)));
        r.setHoldPos(dest);
        r.setFollowState(3);
        double before = r.position().distanceTo(dest);

        List<Unstuck.Action> actions = new ArrayList<>();
        for (int i = 0; i < 12; i++) actions.add(Unstuck.update(h.getLevel(), r, Unstuck.INTERVAL));
        check(actions.contains(Unstuck.Action.JUMP), "primero tendría que saltar: " + actions);
        check(actions.contains(Unstuck.Action.HOP), "tendría que haber salido con un saltito: " + actions);
        check(r.getX() > h.absolutePos(PIT).getX() + 1.5, "sigue adentro del pozo: " + r.position());
        check(r.position().distanceTo(dest) < before, "no se acercó a su destino");
        h.succeed();
    }

    /** Ya en su puesto, o peleando: no se lo toca. */
    @GameTest(template = TEMPLATE)
    public static void arrivedOrFightingIsLeftAlone(GameTestHelper h) {
        AbstractRecruitEntity guard = spawnRecruit(h, PIT);
        guard.setHoldPos(guard.position());
        guard.setFollowState(3);
        Vec3 at = guard.position();
        for (int i = 0; i < 30; i++) check(Unstuck.update(h.getLevel(), guard, Unstuck.INTERVAL) == Unstuck.Action.NONE, "tocó a uno que ya llegó");
        check(guard.position().equals(at), "movió a uno que ya llegó");

        AbstractRecruitEntity fighter = spawnRecruit(h, PIT.east(3));
        fighter.setHoldPos(Vec3.atBottomCenterOf(h.absolutePos(PIT.east(8))));
        fighter.setFollowState(3);
        Zombie z = h.spawn(EntityType.ZOMBIE, PIT.east(4));
        fighter.setTarget(z);
        check(Unstuck.destination(h.getLevel(), fighter) == null, "a uno que pelea no se lo debería mover");
        h.succeed();
    }

    /** Formación Libre: 9 al mismo punto → 9 lugares distintos, separados y sobre el piso. */
    @GameTest(template = TEMPLATE)
    public static void spreadGivesDistinctSpots(GameTestHelper h) {
        Vec3 center = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 2, 4)));
        List<Vec3> spots = Formation.spread(h.getLevel(), center, 9);
        check(spots.size() == 9, "no son 9 lugares");
        for (int i = 0; i < spots.size(); i++) {
            check(Math.abs(spots.get(i).y - center.y) < 0.01, "un lugar no quedó sobre el piso: " + spots.get(i));
            for (int j = i + 1; j < spots.size(); j++) {
                check(spots.get(i).distanceTo(spots.get(j)) >= Formation.SPACING - 0.01, "dos lugares quedaron pegados");
            }
        }
        h.succeed();
    }

    private static AbstractRecruitEntity spawnRecruit(GameTestHelper h, BlockPos pos) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, pos);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity r = (AbstractRecruitEntity) e;
        r.setOwnerUUID(Optional.of(UUID.nameUUIDFromBytes(("warbandcraft-path@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes())));
        r.setIsOwned(true);
        return r;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
