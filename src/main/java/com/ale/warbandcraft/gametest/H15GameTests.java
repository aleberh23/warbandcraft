package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.invasion.Ambush;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Patrols;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.ale.warbandcraft.village.VillageStock;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas del Hito 15a (IA viva): leva y almacén de las facciones, robo, invasiones autoresueltas (ganar con bajas,
 * perder la aldea) y la pelea real (el ejército aparece marcado; si cae, se gana; sin defensores, se pierde).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H15GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    @GameTest(template = TEMPLATE)
    public static void levyAndTreasury(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        try {
            int before = data.levy(FACTION);
            data.setLevy(FACTION, Math.min(before, Invasions.LEVY_CAP - Invasions.LEVY_PER_SETTLEMENT));
            int from = data.levy(FACTION);
            Invasions.growLevy(level);
            check(data.levy(FACTION) >= from + Invasions.LEVY_PER_SETTLEMENT, "la leva no creció con la aldea");
            data.setLevy(FACTION, Invasions.LEVY_CAP - 1);
            Invasions.growLevy(level);
            check(data.levy(FACTION) == Invasions.LEVY_CAP, "la leva pasó el tope: " + data.levy(FACTION));

            VillageData.get(level).setStock(center, VillageStock.Stock.EMPTY);
            Invasions.onNewDay(level, false);
            VillageStock.Stock s = VillageData.get(level).stock(center);
            check(s.emeralds() > 0 && s.food() > 0, "la aldea de la facción no juntó su almacén: " + s);
            List<ItemStack> stolen = Invasions.stealStock(level, v);
            check(!stolen.isEmpty() && VillageData.get(level).stock(center).isEmpty(), "el robo no se llevó el almacén entero");
        } finally {
            VillageData.get(level).add(v.withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void autoresolveWinsOrLoses(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        BlockPos camp = camp(h);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        TownData.get(level).getOrCreate(center).built.add(Upgrade.WALL);
        try {
            BarracksData.Barracks b = BarracksData.get(level).getOrCreate(camp, owner);
            for (int i = 0; i < 12; i++) b.soldiers.add(guard(center, i, 5));
            InvasionData.Invasion small = invasion(level, center, owner, 6);
            Invasions.Result won = Invasions.autoresolve(level, small, v);
            check(won.defended(), "12 guardias con muralla perdieron contra 6: " + won);
            check(owner.equals(VillageData.get(level).at(center).owner()), "la aldea cambió de dueño al ganar");
            check(InvasionData.get(level).at(center) == null, "la invasión sigue después de resolverse");
            check(b.soldiers.size() == 12 - won.guardsLost(), "las bajas no salieron del barracón");

            b.soldiers.clear();
            TownData.get(level).remove(center);
            InvasionData.Invasion big = invasion(level, center, owner, 16);
            Invasions.Result lost = Invasions.autoresolve(level, big, v);
            check(!lost.defended(), "sin guardias resistió a 16");
            VillageData.Village after = VillageData.get(level).at(center);
            check(after != null && after.owner() == null && after.faction().equals(FACTION), "la aldea no pasó a la facción");
        } finally {
            TownData.get(level).remove(center);
            InvasionData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void realFight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        List<AbstractRecruitEntity> spawned = List.of();
        try {
            InvasionData.Invasion inv = invasion(level, center, owner, 3);
            spawned = Invasions.startFight(level, inv);
            check(spawned.size() == 3, "aparecieron " + spawned.size() + " invasores de 3");
            for (AbstractRecruitEntity s : spawned) {
                check(s.getPersistentData().getLong(Invasions.TAG_INVADER) == center.asLong(), "un invasor no está marcado");
                check(s.getPersistentData().getLong(VillageManager.TAG_VILLAGE) == center.asLong(), "un invasor no va a la aldea");
            }
            check(inv.state == InvasionData.State.FIGHTING, "la invasión no pasó a pelea");
            for (AbstractRecruitEntity s : spawned) s.discard();
            Invasions.fightTick(level, inv, v, level.getGameTime());
            check(InvasionData.get(level).at(center) == null, "cayeron todos y la invasión sigue");
            check(owner.equals(VillageData.get(level).at(center).owner()), "ganaste y perdiste la aldea");

            // Otra: sin nadie tuyo cerca, cuando un invasor llega al almacén (acá, el centro) la aldea se pierde y quedan de guarnición
            InvasionData.Invasion second = invasion(level, center, owner, 2);
            spawned = Invasions.startFight(level, second);
            Invasions.fightTick(level, second, v, level.getGameTime());
            check(second.noDefendersSince != Long.MIN_VALUE, "no notó que no hay defensores");
            check(owner.equals(VillageData.get(level).at(center).owner()), "se perdió antes de que lleguen al almacén");
            spawned.get(0).teleportTo(center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5);
            Invasions.fightTick(level, second, v, level.getGameTime());
            VillageData.Village after = VillageData.get(level).at(center);
            check(after != null && after.owner() == null, "sin defensores la aldea no se perdió");
            for (AbstractRecruitEntity s : spawned) check(!s.getPersistentData().contains(Invasions.TAG_INVADER), "un invasor no quedó de guarnición");
        } finally {
            for (AbstractRecruitEntity s : spawned) s.discard();
            InvasionData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ H15b

    private static final ResourceLocation OTHER = new ResourceLocation(WarbandCraft.MODID, "sultanato");

    @GameTest(template = TEMPLATE)
    public static void aiMarchTakesOrFails(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, OTHER, 0);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        try {
            long now = level.getGameTime();
            InvasionData.March weak = new InvasionData.March(UUID.randomUUID(), FACTION, center, center.east(300), 1, now - 10, now);
            data.putMarch(weak);
            AiWars.Result r1 = AiWars.resolve(level, weak);
            check(!r1.taken(), "1 soldado tomó una aldea: " + r1);
            check(VillageData.get(level).at(center).faction().equals(OTHER), "la aldea cambió de facción sin perder");

            InvasionData.March strong = new InvasionData.March(UUID.randomUUID(), FACTION, center, center.east(300), 40, now - 10, now);
            data.putMarch(strong);
            AiWars.Result r2 = AiWars.resolve(level, strong);
            check(r2.taken(), "40 soldados no tomaron la aldea: " + r2);
            check(VillageData.get(level).at(center).faction().equals(FACTION), "la aldea no pasó a la facción atacante");
            check(data.marches().stream().noneMatch(m -> m.target.equals(center)), "la marcha sigue después de llegar");
            List<AbstractRecruitEntity> garrison = VillageManager.garrison(level, center);
            check(!garrison.isEmpty(), "la aldea tomada quedó sin guarnición");
            for (AbstractRecruitEntity s : garrison) {
                check(FACTION.toString().equals(s.getPersistentData().getString(VillageManager.TAG_FACTION)), "la guarnición nueva no es del atacante");
                s.discard();
            }
            check(data.pendingGarrison(center) == 0, "quedó guarnición pendiente con la aldea cargada");
        } finally {
            for (AbstractRecruitEntity s : VillageManager.garrison(level, center)) s.discard();
            VillageData.get(level).add(new VillageData.Village(center, OTHER, 0).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void ambushOnTheRoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        // Un ejército que va de 400 bloques al este hacia acá y ya hizo la mitad del camino
        InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, 3, now + 1000, center.east(400));
        inv.startTick = now - 1000;
        data.put(inv);
        List<AbstractRecruitEntity> spawned = List.of();
        try {
            check(Ambush.find(level, owner, Vec3.atCenterOf(center)) == null, "el ejército está lejos y te emboscó igual");
            Vec3 mid = Vec3.atCenterOf(center.east(200));
            Ambush.Target t = Ambush.find(level, owner, mid);
            check(t != null && t.size() == 3, "no encontró el ejército a mitad de camino");
            check(Ambush.find(level, UUID.randomUUID(), mid) == null, "emboscó a otro jugador");

            int levyBefore = data.levy(FACTION);
            spawned = Ambush.spring(level, null, center, t);
            check(spawned.size() == 3, "aparecieron " + spawned.size() + " emboscadores de 3");
            check(data.at(center) == null, "la invasión sigue marchando después de la emboscada");
            for (AbstractRecruitEntity s : spawned) check(s.getPersistentData().getBoolean(Ambush.TAG_FIELD), "un emboscador sin marca");

            // Sin jugadores cerca (en las pruebas no hay), vuelven a su leva
            Ambush.giveUp(level);
            for (AbstractRecruitEntity s : spawned) check(s.isRemoved(), "un emboscador quedó solo en el campo");
            check(data.levy(FACTION) >= Math.min(Invasions.LEVY_CAP, levyBefore + 3), "no volvieron a la leva");
        } finally {
            for (AbstractRecruitEntity s : spawned) s.discard();
            data.remove(center);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void patrolWalksItsRoute(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0);
        VillageData.get(level).add(v);
        List<AbstractRecruitEntity> patrol = List.of();
        try {
            patrol = Patrols.spawn(level, v);
            check(patrol.size() >= Patrols.MIN_SIZE, "salió una patrulla de " + patrol.size());
            AbstractRecruitEntity s = patrol.get(0);
            check(Patrols.isPatrol(s), "sin marca de patrulla");
            check(s.getHoldPos() != null, "la patrulla no tiene a dónde ir");
            Vec3 first = s.getHoldPos();
            check(Math.abs(Math.sqrt(Math.pow(first.x - center.getX() - 0.5, 2) + Math.pow(first.z - center.getZ() - 0.5, 2))
                    - Patrols.ROUTE_RADIUS) < 2, "el primer punto no está a " + Patrols.ROUTE_RADIUS + " bloques");
            // Llega al punto: pasa al siguiente
            s.setPos(first.x, s.getY(), first.z);
            check(!Patrols.step(level, s, center), "terminó el recorrido en el primer punto");
            check(s.getHoldPos() != null && s.getHoldPos().distanceToSqr(first) > 4, "no pasó al siguiente punto");
        } finally {
            for (AbstractRecruitEntity s : patrol) s.discard();
            VillageData.get(level).add(v.withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void armiesOnTheMap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, 5, now + 100, center.east(100));
        data.put(inv);
        InvasionData.March m = new InvasionData.March(UUID.randomUUID(), OTHER, center.west(7), center.west(300), 9, now, now + 100);
        data.putMarch(m);
        try {
            net.minecraftforge.common.util.FakePlayer player = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                    new com.mojang.authlib.GameProfile(owner, "ale-h15-mapa"));
            List<com.ale.warbandcraft.network.MapPointsMessage.Army> armies = com.ale.warbandcraft.network.MapPointsMessage.armies(player);
            check(armies.stream().anyMatch(a -> a.to().equals(center) && a.hostile()), "la invasión contra vos no está en el mapa (o no es enemiga)");
            check(armies.stream().anyMatch(a -> a.to().equals(center.west(7)) && !a.hostile()), "la marcha de otra facción no está en el mapa");
            Vec3 half = AiWars.position(center.east(100), center, now - 50, now + 50, now);
            check(Math.abs(half.x - (center.getX() + 50.5)) < 1, "a mitad de tiempo no está a mitad de camino: " + half);
        } finally {
            data.remove(center);
            data.removeMarch(m.id);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ revisión con Ale

    @GameTest(template = TEMPLATE)
    public static void aiBattleWithIntervention(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, OTHER, 0);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        UUID helper = owner(h);
        List<AbstractRecruitEntity> defenders = new java.util.ArrayList<>();
        List<AbstractRecruitEntity> attackers = List.of();
        long now = level.getGameTime();
        InvasionData.March m = new InvasionData.March(UUID.randomUUID(), FACTION, center, center.east(300), 3, now - 10, now);
        data.putMarch(m);
        try {
            var other = com.ale.warbandcraft.faction.FactionManager.INSTANCE.get(OTHER);
            check(other != null, "no está la facción " + OTHER);
            for (int i = 0; i < 2; i++) {
                AbstractRecruitEntity s = VillageManager.spawnTroop(level, center, other, other.pickTroop(level.getRandom()), level.getRandom(), 3);
                if (s != null) defenders.add(s);
            }
            check(defenders.size() == 2, "no aparecieron los defensores");
            attackers = AiWars.startBattle(level, m);
            check(m.fighting && attackers.size() == 3, "la batalla no empezó con 3 atacantes: " + attackers.size());
            check(m.defenders == 2, "contó " + m.defenders + " defensores de 2");
            check(AiWars.enemies(attackers.get(0), defenders.get(0)) && AiWars.enemies(defenders.get(0), attackers.get(0)),
                    "los dos bandos no se reconocen como enemigos");
            check(!AiWars.enemies(attackers.get(0), attackers.get(1)), "los atacantes se pelean entre ellos");

            // Te metés: le pegás a un defensor → ayudás al atacante; el defensor te quiere menos
            var rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
            int before = rel.opinion(helper, OTHER), beforeAtt = rel.opinion(helper, FACTION);
            check(AiWars.onHit(level, defenders.get(0), helper, attackers.get(0)), "pegarle a un defensor no cuenta como meterse");
            check(m.helpedAttack.contains(helper), "no quedó anotado que ayudaste al atacante");
            check(rel.opinion(helper, OTHER) == before + AiWars.JOIN_OPINION, "pegarle no bajó la opinión del defensor");

            // Caen los defensores: la aldea pasa al atacante, que queda de guarnición, y te agradece
            for (AbstractRecruitEntity s : defenders) s.discard();
            AiWars.battleTick(level, m);
            check(VillageData.get(level).at(center).faction().equals(FACTION), "sin defensores la aldea no cambió de facción");
            check(data.battleAt(center) == null, "la batalla sigue después de terminar");
            for (AbstractRecruitEntity s : attackers) check(!s.getPersistentData().contains(AiWars.TAG_AI_ATTACK), "un atacante no quedó de guarnición");
            check(rel.opinion(helper, FACTION) == Math.min(100, beforeAtt + AiWars.HELP_OPINION), "el ganador no te agradeció la ayuda");
        } finally {
            for (AbstractRecruitEntity s : defenders) s.discard();
            for (AbstractRecruitEntity s : attackers) s.discard();
            data.removeMarch(m.id);
            VillageData.get(level).add(new VillageData.Village(center, OTHER, 0).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void reconquestDeclaresWar(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        var rel = com.ale.warbandcraft.faction.Relations.get(level.getServer());
        try {
            rel.add(owner, FACTION, -rel.opinion(owner, FACTION));
            check(!rel.atWar(owner, FACTION), "empezó en guerra");
            InvasionData.Invasion inv = Invasions.reconquer(level, owner, FACTION, 6, 100);
            check(inv != null && inv.target.equals(center), "no mandó el ejército contra la aldea que era suya");
            check(rel.atWar(owner, FACTION) && rel.opinion(owner, FACTION) <= Invasions.RECONQUER_OPINION, "no te declaró la guerra");
            check(Invasions.reconquer(level, owner, OTHER, 6, 100) == null, "otra facción quiso recuperar algo que no era suyo");
        } finally {
            InvasionData.get(level).remove(center);
            rel.add(owner, FACTION, -rel.opinion(owner, FACTION));
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void travelFollowsTheRoute(GameTestHelper h) {
        BlockPos o = h.absolutePos(CENTER);
        BlockPos corner = o.east(300), end = corner.south(300);
        com.ale.warbandcraft.route.RouteData.Route r = new com.ale.warbandcraft.route.RouteData.Route(UUID.randomUUID(), owner(h), "L",
                List.of(o, corner, end), o, end, 600, false);
        int ticks = com.ale.warbandcraft.route.Routes.ticks(r);
        com.ale.warbandcraft.route.Travel.Path there = com.ale.warbandcraft.route.Travel.Path.of(o, List.of(r));
        Vec3 half = there.position(ticks / 2.0);
        check(Math.abs(half.x - (corner.getX() + 0.5)) < 2 && Math.abs(half.z - (corner.getZ() + 0.5)) < 2,
                "a mitad del viaje no está en la esquina de la ruta: " + half);
        check(there.position(ticks).distanceTo(Vec3.atBottomCenterOf(end)) < 1, "al final no está en el destino");
        // Al revés: sale del otro extremo
        com.ale.warbandcraft.route.Travel.Path back = com.ale.warbandcraft.route.Travel.Path.of(end, List.of(r));
        check(back.position(0).distanceTo(Vec3.atBottomCenterOf(end)) < 1, "de vuelta no sale del otro extremo");
        check(back.position(ticks / 4.0).x > corner.getX() - 1, "de vuelta no va primero hacia la esquina");
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    private static InvasionData.Invasion invasion(ServerLevel level, BlockPos center, UUID owner, int size) {
        InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, size, level.getGameTime(), center);
        InvasionData.get(level).put(inv);
        return inv;
    }

    private static BarracksData.Soldier guard(BlockPos center, int i, int xp) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "recruits:recruit");
        return new BarracksData.Soldier(tag, null, true, Vec3.atCenterOf(center).add(i % 4, 0, i / 4), 0F, 3, "Guardia " + i,
                null, 0, xp);
    }

    private static BlockPos camp(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(new BlockPos(1, 1, 1)) instanceof CampBlockEntity camp)) throw new GameTestAssertException("sin Campamento");
        camp.setOwner(owner(h));
        return camp.getBlockPos();
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h15@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
