package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.GarrisonDefense;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageEconomy;
import com.ale.warbandcraft.village.VillageManager;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pruebas automáticas del Hito 3 (ataques a aldeas: empezar, ganar, saquear, conquistar, impuestos,
 * guerra y refuerzos). Corren con {@code gradlew runGameTestServer} (o probar.bat).
 *
 * Cada prueba usa su propio jugador falso (UUID distinto) para que las opiniones de las facciones no se mezclen.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H3GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);
    private static final BlockPos CAMP_POS = new BlockPos(0, 1, 0);

    // ------------------------------------------------------------------ empezar el ataque

    /** Pegarle a un soldado de guarnición empieza el ataque: la facción te quiere menos y toda la guarnición va por vos. */
    @GameTest(template = TEMPLATE)
    public static void h3GolpeEmpiezaAtaque(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "golpe");
        List<AbstractRecruitEntity> garrison = village(h, "norte", 3);
        AbstractRecruitEntity victim = garrison.get(0);

        victim.hurt(level.damageSources().playerAttack(player), 1.0F);

        RaidManager.Raid raid = RaidManager.get(level, center(h));
        check(raid != null, "pegarle a la guarnición no empezó el ataque");
        check(raid.attacker.equals(player.getUUID()), "el ataque no quedó a nombre del jugador");
        check(raid.state == RaidManager.State.FIGHTING, "el ataque no está en curso");
        int opinion = Relations.get(level.getServer()).opinion(player.getUUID(), faction("norte").id());
        check(opinion == Relations.RAID_STARTED, "la opinión del Norte debería ser " + Relations.RAID_STARTED + " y es " + opinion);
        for (AbstractRecruitEntity s : garrison) {
            check(GarrisonDefense.isHostile(s, player), "un soldado no considera enemigo al atacante");
            check(s == victim || s.getTarget() == player, "un compañero no salió a pelear contra el atacante");
        }
        endRaid(h);
        h.succeed();
    }

    // ------------------------------------------------------------------ ganar y saquear

    /**
     * Ataque → muere toda la guarnición → victoria → saquear: esmeraldas, hierro, pan y el equipo de los caídos
     * van al Campamento; la aldea queda saqueada; todas las facciones te quieren menos.
     */
    @GameTest(template = TEMPLATE)
    public static void h3VictoriaYSaqueo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "saqueo");
        CampBlockEntity camp = camp(h, player.getUUID());
        List<AbstractRecruitEntity> garrison = village(h, "valdren", 2);
        win(h, player, garrison);

        RaidManager.Outcome out = RaidManager.sack(level, player.getUUID(), center(h));
        check(out.ok(), "saquear falló: " + out.message().getString());
        check(camp.count(Items.EMERALD) >= 3, "no llegaron esmeraldas al Campamento: " + camp.count(Items.EMERALD));
        check(camp.count(Items.IRON_INGOT) >= 2, "no llegó hierro al Campamento");
        check(camp.count(Items.BREAD) >= 8, "no llegó comida al Campamento");
        check(hasArmorOrWeapon(camp), "el equipo de los caídos no llegó al Campamento");

        VillageData.Village v = VillageData.get(level).at(center(h));
        check(v != null && v.isLooted(level.getGameTime()), "la aldea no quedó saqueada");
        check(!v.isConquered(), "saquear no debería conquistar");
        Relations rel = Relations.get(level.getServer());
        int target = rel.opinion(player.getUUID(), faction("valdren").id());
        int other = rel.opinion(player.getUUID(), faction("norte").id());
        check(target == Relations.RAID_STARTED + Relations.SACKED_TARGET, "opinión de Valdren: " + target);
        check(other == Relations.SACKED_OTHERS, "opinión de las otras facciones: " + other);
        check(VillageEconomy.reinforce(level, v, true) == null, "una aldea saqueada recibió refuerzos");
        check(RaidManager.get(level, center(h)) == null, "el ataque siguió abierto después de saquear");
        h.succeed();
    }

    // ------------------------------------------------------------------ conquistar e impuestos

    /** Conquistar: la aldea pasa a ser del jugador, y cada día paga impuestos en esmeraldas al Campamento. */
    @GameTest(template = TEMPLATE)
    public static void h3ConquistaEImpuestos(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "conquista");
        CampBlockEntity camp = camp(h, player.getUUID());
        List<AbstractRecruitEntity> garrison = village(h, "sultanato", 1);
        win(h, player, garrison);

        RaidManager.Outcome out = RaidManager.conquer(level, player.getUUID(), center(h));
        check(out.ok(), "conquistar falló: " + out.message().getString());
        VillageData.Village v = VillageData.get(level).at(center(h));
        check(v != null && player.getUUID().equals(v.owner()), "la aldea no quedó a nombre del jugador");
        int opinion = Relations.get(level.getServer()).opinion(player.getUUID(), faction("sultanato").id());
        check(opinion == Relations.RAID_STARTED + Relations.CONQUERED_TARGET, "opinión del Sultanato: " + opinion);

        // H10: los impuestos quedan en el almacén de la aldea (no llegan solos al Campamento)
        int before = camp.count(Items.EMERALD);
        Map<UUID, VillageStock.Stock> paid = VillageStock.produceAll(level);
        int got = paid.getOrDefault(player.getUUID(), VillageStock.Stock.EMPTY).emeralds();
        check(got >= VillageEconomy.BASE_TAX, "la aldea pagó " + got + " de impuestos");
        check(VillageStock.total(level, v).emeralds() == got, "los impuestos no quedaron en la aldea");   // en espera o ya en el barril
        check(camp.count(Items.EMERALD) == before, "los impuestos llegaron solos al Campamento");
        check(VillageEconomy.reinforce(level, v, true) == null, "una aldea conquistada recibió soldados de la facción");
        forget(level, v);
        h.succeed();
    }

    // ------------------------------------------------------------------ guerra

    /** Con la opinión por el piso la facción está en guerra: sus guarniciones atacan al jugador sin que haya ataque. */
    @GameTest(template = TEMPLATE)
    public static void h3GuerraHaceHostil(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "guerra");
        AbstractRecruitEntity soldier = village(h, "pantanos", 1).get(0);
        Relations rel = Relations.get(level.getServer());

        check(!GarrisonDefense.isHostile(soldier, player), "la guarnición ataca a un jugador neutral");
        rel.add(player.getUUID(), faction("pantanos").id(), Relations.WAR_THRESHOLD);
        check(rel.atWar(player.getUUID(), faction("pantanos").id()), "con " + Relations.WAR_THRESHOLD + " debería haber guerra");
        check(GarrisonDefense.isHostile(soldier, player), "en guerra, la guarnición no ataca al jugador");
        check(!GarrisonDefense.isHostile(village(h, "norte", 1, new BlockPos(6, 1, 6)).get(0), player),
                "una facción que no está en guerra también ataca");

        for (int i = 0; i < 25; i++) rel.dailyRecovery();
        check(!rel.atWar(player.getUUID(), faction("pantanos").id()), "la guerra no se calma con el tiempo");
        cleanup(level, center(h));
        cleanup(level, h.absolutePos(new BlockPos(6, 1, 6)));
        h.succeed();
    }

    // ------------------------------------------------------------------ refuerzos

    /** Una aldea de facción con menos soldados que su mínimo recibe refuerzos (de a uno). */
    @GameTest(template = TEMPLATE)
    public static void h3RefuerzosLlegan(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = faction("norte");
        VillageData.Village v = new VillageData.Village(center(h), f.id(), level.getGameTime());
        VillageData.get(level).add(v);
        check(VillageManager.garrison(level, center(h)).isEmpty(), "la aldea ya tenía soldados");

        AbstractRecruitEntity s = VillageEconomy.reinforce(level, v, true);
        check(s != null, "no llegó el refuerzo");
        check(VillageManager.isGarrison(s), "el refuerzo no es de la guarnición");
        List<AbstractRecruitEntity> now = VillageManager.garrison(level, center(h));
        StringBuilder who = new StringBuilder();
        for (AbstractRecruitEntity e : now) {
            who.append(e.getName().getString()).append(" en ").append(e.blockPosition().toShortString()).append("; ");
        }
        check(now.size() == 1, "llegó más de un refuerzo a la vez (" + now.size() + "): " + who);
        forget(level, v);
        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ utilidades

    private static FakePlayer player(GameTestHelper h, String name) {
        UUID id = UUID.nameUUIDFromBytes(("wb-h3-" + name + "@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        FakePlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(id, "wb_" + name));
        BlockPos c = center(h);
        p.moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return p;
    }

    private static BlockPos center(GameTestHelper h) {
        return h.absolutePos(CENTER);
    }

    /** Registra una aldea en CENTER y le pone {@code count} soldados. */
    private static List<AbstractRecruitEntity> village(GameTestHelper h, String factionName, int count) {
        return village(h, factionName, count, CENTER);
    }

    private static List<AbstractRecruitEntity> village(GameTestHelper h, String factionName, int count, BlockPos rel) {
        ServerLevel level = h.getLevel();
        Faction f = faction(factionName);
        BlockPos c = h.absolutePos(rel);
        VillageData.get(level).add(new VillageData.Village(c, f.id(), level.getGameTime()));
        RandomSource random = RandomSource.create(c.asLong());
        List<AbstractRecruitEntity> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            var troop = f.troops().stream().filter(t -> t.resolve() != null && !t.resolve().horse()).findFirst().orElseThrow();
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, c, f, troop, random, 1);
            check(s != null, "no apareció un soldado de " + f.name());
            out.add(s);
        }
        return out;
    }

    /** Empieza el ataque, mata a toda la guarnición y corre el chequeo de victoria. */
    private static void win(GameTestHelper h, FakePlayer player, List<AbstractRecruitEntity> garrison) {
        ServerLevel level = h.getLevel();
        garrison.get(0).hurt(level.damageSources().playerAttack(player), 1.0F);
        RaidManager.Raid raid = RaidManager.get(level, center(h));
        check(raid != null, "no empezó el ataque");
        for (AbstractRecruitEntity s : garrison) {
            s.setInvulnerable(false);
            s.hurt(level.damageSources().playerAttack(player), 10000F);
        }
        check(VillageManager.garrison(level, center(h)).isEmpty(), "quedaron soldados vivos");
        check(raid.killed == garrison.size(), "se contaron " + raid.killed + " caídos de " + garrison.size());
        check(!raid.fallenGear.isEmpty(), "no se juntó el equipo de los caídos");
        RaidManager.tick(level);
        check(raid.state == RaidManager.State.WON, "sin guarnición no se declaró la victoria (estado " + raid.state + ")");
    }

    private static CampBlockEntity camp(GameTestHelper h, UUID owner) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) throw new GameTestAssertException("no se creó el Campamento");
        camp.setOwner(owner);
        return camp;
    }

    private static boolean hasArmorOrWeapon(CampBlockEntity camp) {
        for (int i = 0; i < camp.getContainerSize(); i++) {
            ItemStack s = camp.getItem(i);
            if (s.getItem() instanceof ArmorItem || s.is(Items.IRON_SWORD) || s.is(Items.IRON_AXE)
                    || s.is(Items.STONE_SWORD) || s.is(Items.SHIELD)) return true;
        }
        return false;
    }

    private static void endRaid(GameTestHelper h) {
        RaidManager.discard(h.getLevel(), center(h));
        cleanup(h.getLevel(), center(h));
    }

    /** Saca la aldea de los datos del mundo (para no dejar aldeas conquistadas cobrando impuestos). */
    private static void forget(ServerLevel level, VillageData.Village v) {
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    private static void cleanup(ServerLevel level, BlockPos center) {
        for (AbstractRecruitEntity s : VillageManager.garrison(level, center)) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
        }
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
