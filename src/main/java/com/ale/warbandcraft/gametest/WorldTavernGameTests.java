package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.village.WorldTaverns;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pedido de Ale: las tabernas del mundo (Dungeons and Taverns) tienen Tabernero, mercenarios para contratar y guardias
 * que pelean con los bichos y con quien le pegue a alguien de la taberna.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class WorldTavernGameTests {
    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, batch = "world_tavern")
    public static void worldTavernComesAlive(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(1, 1, 1)), b = h.absolutePos(new BlockPos(7, 4, 7));
        BoundingBox house = BoundingBox.fromCorners(a, b);
        WorldTaverns data = WorldTaverns.get(level);
        long id = house.getCenter().asLong();
        WorldTaverns.Inn inn = data.register(level, id, house, "test");
        inn.mercs = 2;
        Zombie zombie = null;
        Villager villager = null;
        AbstractRecruitEntity hired = null;
        try {
            WorldTaverns.tickInn(level, data, inn);
            List<AbstractRecruitEntity> people = WorldTaverns.people(level, inn);
            long keepers = people.stream().filter(r -> r instanceof InnkeeperEntity && role(r).equals(WorldTaverns.KEEPER)).count();
            List<AbstractRecruitEntity> guards = people.stream().filter(r -> role(r).equals(WorldTaverns.GUARD)).toList();
            List<AbstractRecruitEntity> mercs = people.stream().filter(r -> role(r).equals(WorldTaverns.MERC)).toList();
            check(keepers == 1, "Taberneros: " + keepers);
            check(guards.size() == WorldTaverns.GUARDS, "Guardias: " + guards.size());
            check(mercs.size() == 2, "Mercenarios: " + mercs.size());
            check(mercs.stream().noneMatch(AbstractRecruitEntity::getIsOwned), "un mercenario ya tenía dueño");

            // Contratar a uno (lo que hace la pantalla de Recruits): deja de ser de la taberna y no vuelve a aparecer
            hired = mercs.get(0);
            hired.setOwnerUUID(Optional.of(UUID.nameUUIDFromBytes("tavern-test".getBytes())));
            hired.setIsOwned(true);
            WorldTaverns.tickInn(level, data, inn);
            check(!WorldTaverns.isTavernPerson(hired) && !hired.isInvulnerable(), "el contratado sigue siendo de la taberna");
            check(inn.mercs == 1, "mercenarios que quedan: " + inn.mercs);
            long left = WorldTaverns.people(level, inn).stream().filter(r -> role(r).equals(WorldTaverns.MERC)).count();
            check(left == 1, "apareció otro mercenario en lugar del contratado: " + left);

            // Un bicho cerca: algún guardia va contra él
            zombie = EntityType.ZOMBIE.create(level);
            check(zombie != null, "no se pudo crear el zombi");
            zombie.moveTo(h.absolutePos(new BlockPos(4, 1, 8)).getCenter());
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            WorldTaverns.tickInn(level, data, inn);
            Zombie z = zombie;
            List<AbstractRecruitEntity> now = WorldTaverns.people(level, inn);
            check(now.stream().anyMatch(r -> role(r).equals(WorldTaverns.GUARD) && r.getTarget() == z), "ningún guardia fue contra el zombi");
            check(now.stream().filter(r -> role(r).equals(WorldTaverns.MERC)).allMatch(r -> r.getTarget() == z && !r.isInvulnerable()),
                    "los mercenarios no defienden la taberna del zombi");

            // Un jugador le pega a un aldeano de la taberna: es enemigo de los guardias
            villager = EntityType.VILLAGER.create(level);
            check(villager != null, "no se pudo crear el aldeano");
            villager.moveTo(h.absolutePos(new BlockPos(3, 1, 3)).getCenter());
            level.addFreshEntity(villager);
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("tavern-bully".getBytes()), "tavern-bully"));
            player.moveTo(h.absolutePos(new BlockPos(5, 1, 5)).getCenter());
            villager.hurt(level.damageSources().playerAttack(player), 1F);
            List<LivingEntity> foes = WorldTaverns.enemies(level, inn, WorldTaverns.people(level, inn));
            check(foes.contains(player), "los guardias no ven como enemigo al que le pegó al aldeano");

            // Un guardia muerto tarda en volver
            WorldTaverns.onDeath(level, guards.get(0));
            check(inn.guardsDown == 1, "no se anotó el guardia caído");
        } finally {
            for (AbstractRecruitEntity r : WorldTaverns.people(level, inn)) r.discard();
            if (zombie != null) zombie.discard();
            if (villager != null) villager.discard();
            if (hired != null) hired.discard();
            data.remove(id);
        }
        h.succeed();
    }

    private static String role(AbstractRecruitEntity r) {
        return r.getPersistentData().getString(WorldTaverns.TAG_ROLE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
