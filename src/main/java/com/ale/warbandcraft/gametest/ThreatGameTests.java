package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Peace;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Threat;
import com.ale.warbandcraft.village.Ruins;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas de las amenazas por cercanía ({@link Threat}), quemar aldeas ({@link Ruins}) y hacer la paz ({@link Peace}).
 * Cada una con su jugador inventado (UUID distinto) y limpiando lo que guardó.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class ThreatGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);

    /** La cercanía: pegada = todo, lejos = casi nada; varias opciones no se suman y la tirada elige una. */
    @GameTest(template = TEMPLATE)
    public static void threatByDistance(GameTestHelper h) {
        check(Threat.proximity(100) == 1, "pegada debería ser 1");
        check(Threat.proximity(Threat.FAR_DISTANCE + 100) == Threat.REMOTE_FACTOR, "lejos debería ser " + Threat.REMOTE_FACTOR);
        double mid = Threat.proximity((Threat.NEAR_DISTANCE + Threat.FAR_DISTANCE) / 2.0);
        check(mid < 1 && mid > Threat.REMOTE_FACTOR, "a mitad de camino: " + mid);
        check(Threat.roll(List.of(), RandomSource.create(1)) == null, "sin opciones salió algo");
        ResourceLocation f = new ResourceLocation(WarbandCraft.MODID, "norte");
        UUID u = UUID.randomUUID();
        List<Threat.Option> one = List.of(new Threat.Option(f, Threat.Kind.INVASION, 1.0, u));
        check(Threat.roll(one, RandomSource.create(2)) == one.get(0), "con chance 1 no salió la única opción");
        List<Threat.Option> none = List.of(new Threat.Option(f, Threat.Kind.HOST, 0.0, u));
        check(Threat.roll(none, RandomSource.create(3)) == null, "con chance 0 salió algo");
        h.succeed();
    }

    /** Una aldea de facción cerca de la tuya, en guerra y con leva: invasión con toda la chance; con tregua, nada. */
    @GameTest(template = TEMPLATE)
    public static void threatNearVillageAndTruce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = faction("pantanos");
        UUID me = id(h, "near");
        BlockPos mine = h.absolutePos(CENTER);
        BlockPos theirs = h.absolutePos(new BlockPos(1, 1, 1));
        VillageData data = VillageData.get(level);
        data.add(new VillageData.Village(mine, f.id(), level.getGameTime()).withOwner(me));
        data.add(new VillageData.Village(theirs, f.id(), level.getGameTime()));
        Relations rel = Relations.get(level.getServer());
        rel.set(me, f.id(), -60);
        InvasionData inv = InvasionData.get(level);
        int levy = inv.levy(f.id());
        inv.setLevy(f.id(), Math.max(levy, 20));
        try {
            List<VillageData.Village> group = List.of(data.at(mine));
            Threat.Option o = find(Threat.options(level, group), f.id(), Threat.Kind.INVASION);
            check(o != null, "no ofrece invasión con la aldea enemiga pegada");
            check(Math.abs(o.chance() - Threat.INVASION_CHANCE) < 1e-6, "la chance pegada debería ser la entera: " + o.chance());
            check(o.owner().equals(me), "la invasión no va contra el dueño");
            Peace.make(level, me, f.id());
            check(find(Threat.options(level, group), f.id(), null) == null, "con tregua igual ofrece algo");
            check(!rel.atWar(me, f.id()), "después de la paz sigue en guerra");
        } finally {
            data.remove(mine);
            data.remove(theirs);
            rel.setTruce(me, f.id(), -1);
            inv.setLevy(f.id(), levy);
        }
        h.succeed();
    }

    /** La paz: precio según el odio, opinión arriba, tregua, se vuelve la invasión en camino; atacar la rompe. */
    @GameTest(template = TEMPLATE)
    public static void peaceTurnsBackInvasions(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = faction("norte");
        UUID me = id(h, "peace");
        Relations rel = Relations.get(level.getServer());
        rel.set(me, f.id(), -80);
        check(Peace.price(level, me, f.id()) == Peace.PRICE_BASE + Peace.PRICE_PER_HATE * 40, "precio: " + Peace.price(level, me, f.id()));
        InvasionData data = InvasionData.get(level);
        BlockPos target = h.absolutePos(CENTER);
        int levy = data.levy(f.id());
        data.put(new InvasionData.Invasion(target, f.id(), me, 7, level.getGameTime() + 6000, target.offset(50, 0, 0)));
        Peace.make(level, me, f.id());
        long day = level.getDayTime() / 24000L;
        check(rel.opinion(me, f.id()) == Peace.PEACE_OPINION, "opinión después de la paz: " + rel.opinion(me, f.id()));
        check(rel.truce(me, f.id(), day + Peace.TRUCE_DAYS), "no quedó la tregua");
        check(data.at(target) == null, "la invasión en camino no se volvió");
        check(data.levy(f.id()) == levy + 7, "los invasores no volvieron a la leva");
        Peace.breakTruce(level, me, f.id());
        check(!rel.truce(me, f.id(), day), "atacar no rompió la tregua");
        data.setLevy(f.id(), levy);
        h.succeed();
    }

    /** Quemar: mueren los aldeanos, se van campana, vidrios y camas, deja de ser aldea y no vuelve a aparecer ahí. */
    @GameTest(template = TEMPLATE)
    public static void burnLeavesRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = faction("valdren");
        BlockPos c = h.absolutePos(CENTER);
        h.setBlock(CENTER.above(), Blocks.BELL);
        h.setBlock(CENTER.above().east(), Blocks.GLASS);
        h.setBlock(CENTER.above().west(), Blocks.WHITE_WOOL);
        h.setBlock(CENTER.above().north(), Blocks.TORCH);
        Villager villager = EntityType.VILLAGER.create(level);
        check(villager != null, "no se creó el aldeano");
        BlockPos at = h.absolutePos(CENTER.above().south().south());
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(villager);
        VillageData data = VillageData.get(level);
        VillageData.Village v = new VillageData.Village(c, f.id(), level.getGameTime());
        data.add(v);
        try {
            int dead = Ruins.burn(level, v, 3);
            Ruins.finishAll();
            check(dead >= 1 && !villager.isAlive(), "el aldeano sigue vivo");
            h.assertBlockNotPresent(Blocks.BELL, CENTER.above());
            h.assertBlockNotPresent(Blocks.GLASS, CENTER.above().east());
            h.assertBlockNotPresent(Blocks.WHITE_WOOL, CENTER.above().west());
            h.assertBlockNotPresent(Blocks.TORCH, CENTER.above().north());
            check(data.at(c) == null, "sigue siendo aldea");
            check(Ruins.get(level).near(c, 1) != null, "no quedó anotada la ruina");
            // Una campana nueva en las ruinas no hace una aldea nueva
            h.setBlock(CENTER.above(), Blocks.BELL);
            List<VillageData.Village> added = VillageManager.scan(level, c, 8, data, b -> f);
            check(added.stream().noneMatch(x -> x.center().closerThan(c, 8)), "apareció una aldea en las ruinas");
        } finally {
            Ruins.get(level).forget(c);
            data.remove(c);
            h.setBlock(CENTER.above(), Blocks.AIR);
        }
        h.succeed();
    }

    private static Threat.Option find(List<Threat.Option> options, ResourceLocation f, Threat.Kind kind) {
        for (Threat.Option o : options) if (o.faction().equals(f) && (kind == null || o.kind() == kind)) return o;
        return null;
    }

    private static UUID id(GameTestHelper h, String name) {
        return UUID.nameUUIDFromBytes(("wb-threat-" + name + "@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static Faction faction(String name) {
        Faction f = FactionManager.INSTANCE.get(new ResourceLocation(WarbandCraft.MODID, name));
        check(f != null, "no se cargó la facción " + name);
        return f;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
