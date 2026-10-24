package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
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
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Pruebas automáticas del Hito 4 (castillos): aparecen como una mazmorra más — sin depender de ninguna
 * aldea ni limitados a uno por facción — con capitán fijo y guarnición grande. Si cae el capitán, el
 * asedio termina y los defensores que quedan se liberan como prisioneros.
 * Corren con {@code gradlew runGameTestServer} (o probar.bat).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H4GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);

    // ------------------------------------------------------------------ aparece como una mazmorra, sin aldea

    /** Un castillo plantado directo (sin ninguna aldea) tiene capitán y guarnición grande; no se duplica cerca. */
    @GameTest(template = TEMPLATE)
    public static void h4CastilloSueltoConCapitanYGuarnicion(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Faction f = faction("norte");
        BlockPos c = center(h);

        CastleManager.place(level, c, f);

        VillageData.Village castle = findCastle(level, f.id());
        check(castle != null, "no se registró el castillo de " + f.name());

        List<AbstractRecruitEntity> garrison = VillageManager.garrison(level, castle.center());
        check(garrison.size() >= CastleManager.GARRISON_MIN && garrison.size() <= CastleManager.GARRISON_MAX + 1,
                "guarnición de castillo con tamaño raro: " + garrison.size());
        long captains = garrison.stream().filter(e -> e.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)).count();
        check(captains == 1, "debería haber un solo capitán, hay " + captains);
        check(CastleManager.captainAlive(garrison), "el capitán no figura vivo en su propia guarnición");

        // Cerca de un castillo ya plantado no se planta otro; lejos, sí se podría.
        check(CastleManager.hasCastleNear(level, castle.center().offset(50, 0, 50), CastleManager.CASTLE_SPACING),
                "un punto cercano debería contar como \"ya hay un castillo ahí\"");
        check(!CastleManager.hasCastleNear(level, castle.center().offset(0, 0, 5000), CastleManager.CASTLE_SPACING),
                "un punto lejano no debería verse afectado por este castillo");

        cleanup(level, castle.center());
        forget(level, castle);
        h.succeed();
    }

    // ------------------------------------------------------------------ cae el capitán

    /**
     * Si cae el capitán, el asedio termina aunque sigan vivos otros defensores: quedan libres como prisioneros.
     *
     * No usa {@link CastleManager#place}: ese castillo buscaría un lugar plano lejos de {@code CENTER},
     * fuera de la estructura de la prueba, y ahí {@code isPositionEntityTicking} no da true (nadie "real" está
     * parado cerca). Se arma el mismo escenario (capitán + guarnición) a mano, en {@code CENTER}, donde la
     * prueba sí tiene el chunk cargado — la placement real ya la cubre {@code h4CastilloSueltoConCapitanYGuarnicion}.
     */
    @GameTest(template = TEMPLATE)
    public static void h4CaeElCapitanLiberaPrisioneros(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "capitan");
        Faction f = faction("pantanos");
        BlockPos c = center(h);
        VillageData.get(level).add(new VillageData.Village(c, f.id(), level.getGameTime()).asCastle());

        RandomSource random = RandomSource.create(c.asLong());
        CastleManager.spawnCaptain(level, c, f, random);
        List<AbstractRecruitEntity> extra = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Troop troop = f.troops().stream().filter(t -> t.resolve() != null && !t.resolve().horse()).findFirst().orElseThrow();
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, c, f, troop, random, 1, CastleManager.PATROL_RADIUS);
            check(s != null, "no apareció un soldado extra de " + f.name());
            s.getPersistentData().putBoolean(CastleManager.TAG_CASTLE, true);
            extra.add(s);
        }

        List<AbstractRecruitEntity> garrison = VillageManager.garrison(level, c);
        check(garrison.size() == 3, "guarnición de prueba con tamaño raro: " + garrison.size());
        AbstractRecruitEntity captain = garrison.stream()
                .filter(e -> e.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)).findFirst().orElse(null);
        check(captain != null, "no apareció el capitán");

        captain.hurt(level.damageSources().playerAttack(player), 1.0F); // empieza el asedio
        RaidManager.Raid raid = RaidManager.get(level, c);
        check(raid != null, "pegarle al capitán no empezó el asedio");

        captain.setInvulnerable(false);
        captain.hurt(level.damageSources().playerAttack(player), 10000F);
        check(!captain.isAlive(), "el capitán no murió");

        List<AbstractRecruitEntity> survivors = VillageManager.garrison(level, c);
        check(survivors.size() == 2, "en esta prueba deberían quedar los 2 sobrevivientes: " + survivors.size());

        RaidManager.tick(level);
        check(raid.state == RaidManager.State.WON, "sin capitán el asedio debería terminar igual (estado " + raid.state + ")");
        for (AbstractRecruitEntity s : survivors) {
            check(!VillageManager.isGarrison(s), "un sobreviviente sigue marcado como guarnición (prisionero no liberado)");
            check(!s.hasRestriction(), "un prisionero sigue restringido a la patrulla del castillo");
        }

        for (AbstractRecruitEntity s : survivors) s.discard(); // ya no son guarnición: cleanup() no los agarra
        forget(level, VillageData.get(level).at(c));
        RaidManager.discard(level, c);
        h.succeed();
    }

    // ------------------------------------------------------------------ utilidades

    private static FakePlayer player(GameTestHelper h, String name) {
        UUID id = UUID.nameUUIDFromBytes(("wb-h4-" + name + "@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        FakePlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(id, "wb_" + name));
        BlockPos c = center(h);
        p.moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return p;
    }

    private static BlockPos center(GameTestHelper h) {
        return h.absolutePos(CENTER);
    }

    private static VillageData.Village findCastle(ServerLevel level, ResourceLocation faction) {
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (v.castle() && v.faction().equals(faction)) return v;
        }
        return null;
    }

    private static void cleanup(ServerLevel level, BlockPos center) {
        for (AbstractRecruitEntity s : VillageManager.garrison(level, center)) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
        }
    }

    /** Saca el castillo de los datos del mundo (para no dejarlo cobrando impuestos ni pidiendo refuerzos). */
    private static void forget(ServerLevel level, VillageData.Village v) {
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
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
