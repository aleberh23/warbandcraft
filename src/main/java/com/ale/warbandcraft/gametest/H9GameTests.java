package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleEscape;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Prisoners;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.raid.RaidData;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.mojang.authlib.GameProfile;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas automáticas del Hito 9 (batallas): rendirse, prisioneros al ganar, capturados al perder, reclutar y
 * rescatar prisioneros, el general (línea del defensor, flanco de la caballería), la orden Batalla y el guardado.
 *
 * Fuera de las pruebas cualquiera puede rendirse; acá solo el soldado con {@link Surrender#TAG_TEST_CHANCE}
 * (las de H3 necesitan que mueran todos).
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H9GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(2, 1, 2);
    private static final BlockPos CAMP_POS = new BlockPos(0, 1, 0);

    // ------------------------------------------------------------------ rendirse

    /** Un golpe mortal en medio de un ataque: se rinde en vez de morir y deja de contar como defensor. */
    @GameTest(template = TEMPLATE)
    public static void h9GolpeMortalRinde(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "rinde");
        List<AbstractRecruitEntity> garrison = village(h, "norte", 2);
        garrison.get(0).hurt(level.damageSources().playerAttack(player), 1.0F);
        RaidManager.Raid raid = RaidManager.get(level, center(h));
        check(raid != null, "no empezó el ataque");

        AbstractRecruitEntity s = garrison.get(1);
        willSurrender(s);
        s.hurt(level.damageSources().playerAttack(player), 10000F);
        check(s.isAlive(), "se murió en vez de rendirse");
        check(Surrender.is(s), "no quedó marcado como rendido");
        check(s.isInvulnerable(), "un rendido tiene que ser invulnerable");
        check(raid.surrendered.contains(s.getUUID()), "el ataque no anotó al rendido");
        check(!VillageManager.garrison(level, center(h)).contains(s), "un rendido sigue contando como defensor");

        RaidManager.discard(level, center(h));
        check(!Surrender.is(s) && !s.isInvulnerable(), "al terminar el ataque sin resultado no se levantó");
        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ ganar: prisioneros

    /** Ganás: el enemigo rendido va preso a tu barracón; tu soldado rendido se levanta. */
    @GameTest(template = TEMPLATE)
    public static void h9GanarTomaPrisioneros(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "presos");
        camp(h, player.getUUID());
        List<AbstractRecruitEntity> garrison = village(h, "valdren", 3);
        AbstractRecruitEntity mine = ownSoldier(h, player.getUUID(), new BlockPos(4, 1, 4));

        garrison.get(0).hurt(level.damageSources().playerAttack(player), 1.0F);
        RaidManager.Raid raid = RaidManager.get(level, center(h));
        check(raid != null, "no empezó el ataque");
        AbstractRecruitEntity captive = garrison.get(1);
        willSurrender(captive);
        captive.hurt(level.damageSources().playerAttack(player), 10000F);
        willSurrender(mine);
        mine.hurt(level.damageSources().mobAttack(garrison.get(0)), 10000F);
        check(Surrender.is(captive) && Surrender.is(mine), "no se rindieron los dos");

        for (AbstractRecruitEntity s : List.of(garrison.get(0), garrison.get(2))) {
            s.setInvulnerable(false);
            s.hurt(level.damageSources().playerAttack(player), 10000F);
        }
        RaidManager.tick(level);
        check(raid.state == RaidManager.State.WON, "con los demás caídos y uno rendido no se ganó (estado " + raid.state + ")");
        BarracksData.Barracks b = BarracksData.get(level).at(h.absolutePos(CAMP_POS));
        check(b != null && b.prisoners.size() == 1, "el prisionero no llegó al barracón: " + (b == null ? 0 : b.prisoners.size()));
        check(captive.isRemoved(), "el prisionero sigue en el mundo");
        BarracksData.Prisoner p = b.prisoners.get(0);
        check(faction("valdren").id().equals(p.faction), "el prisionero no recuerda su facción");
        check(mine.isAlive() && !Surrender.is(mine) && !mine.isInvulnerable(), "tu soldado rendido no se levantó");
        check(mine.getIsOwned() && player.getUUID().equals(mine.getOwnerUUID()), "tu soldado dejó de ser tuyo al ganar");

        RaidManager.discard(level, center(h));
        BarracksData.get(level).remove(h.absolutePos(CAMP_POS));
        mine.discard();
        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ perder: capturados

    /** Abandonás el ataque: tu soldado rendido pasa a la guarnición enemiga, sin dueño y con su nivel. */
    @GameTest(template = TEMPLATE)
    public static void h9PerderEntregaTusRendidos(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "capturados");
        List<AbstractRecruitEntity> garrison = village(h, "sultanato", 2);
        AbstractRecruitEntity mine = ownSoldier(h, player.getUUID(), new BlockPos(4, 1, 4));
        mine.setXpLevel(3);

        garrison.get(0).hurt(level.damageSources().playerAttack(player), 1.0F);
        check(RaidManager.get(level, center(h)) != null, "no empezó el ataque");
        willSurrender(mine);
        mine.hurt(level.damageSources().mobAttack(garrison.get(0)), 10000F);
        check(Surrender.is(mine), "tu soldado no se rindió");

        RaidManager.abandon(level, center(h));
        check(RaidManager.get(level, center(h)) == null, "el ataque sigue abierto después de abandonarlo");
        check(mine.isAlive() && !Surrender.is(mine), "tu soldado capturado no se levantó");
        check(!mine.getIsOwned(), "tu soldado capturado sigue teniendo dueño");
        check(VillageManager.isGarrison(mine), "tu soldado capturado no pasó a la guarnición");
        check(mine.getPersistentData().getLong(VillageManager.TAG_VILLAGE) == center(h).asLong(), "pasó a la guarnición de otra aldea");
        check(faction("sultanato").id().toString().equals(mine.getPersistentData().getString(VillageManager.TAG_FACTION)),
                "pasó a otra facción");
        check(mine.getXpLevel() == 3, "perdió su nivel al cambiar de bando: " + mine.getXpLevel());
        check(VillageManager.garrison(level, center(h)).contains(mine), "no cuenta como defensor de la aldea");

        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ reclutar y rescate

    /** Un prisionero se recluta (soldado tuyo, con su clase) y otro se devuelve por esmeraldas. */
    @GameTest(template = TEMPLATE)
    public static void h9ReclutarYRescatar(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "reclutar");
        CampBlockEntity camp = camp(h, player.getUUID());
        BlockPos campPos = h.absolutePos(CAMP_POS);
        List<AbstractRecruitEntity> garrison = village(h, "norte", 2);
        for (AbstractRecruitEntity s : garrison) {
            check(s.getPersistentData().contains(VillageManager.TAG_TROOP_KIT), "la guarnición no anotó la clase de su tropa");
            check(Prisoners.imprison(level, s, player.getUUID()), "no se pudo llevar preso");
        }
        BarracksData.Barracks b = BarracksData.get(level).at(campPos);
        check(b != null && b.prisoners.size() == 2, "no llegaron los 2 prisioneros");

        String row = b.prisoners.get(0).rowKey();
        check(Prisoners.recruit(level, campPos, row, player.getUUID()) == Prisoners.Result.OK, "no se pudo reclutar");
        check(b.prisoners.size() == 1 && b.soldiers.size() == 1, "reclutar no movió al prisionero a los soldados");
        BarracksData.Soldier sheet = b.soldiers.get(0);
        check(sheet.kit != null, "el prisionero reclutado perdió su clase");
        CompoundTag nbt = sheet.entity;
        check(nbt.hasUUID("OwnerUUID") && player.getUUID().equals(nbt.getUUID("OwnerUUID")) && nbt.getBoolean("isOwned"),
                "el reclutado no quedó a tu nombre");
        check(!nbt.getCompound("ForgeData").contains(VillageManager.TAG_FACTION), "el reclutado sigue marcado como guarnición");

        int before = camp.count(Items.EMERALD);
        String other = b.prisoners.get(0).rowKey();
        int paid = Prisoners.ransom(level, campPos, other);
        check(paid >= Prisoners.RANSOM_BASE, "el rescate pagó " + paid);
        check(camp.count(Items.EMERALD) - before == paid, "las esmeraldas del rescate no llegaron al Campamento");
        check(b.prisoners.isEmpty(), "el prisionero rescatado sigue en el barracón");
        int opinion = Relations.get(level.getServer()).opinion(player.getUUID(), faction("norte").id());
        check(opinion == Prisoners.RANSOM_OPINION, "el rescate no mejoró la opinión: " + opinion);

        BarracksData.get(level).remove(campPos);
        h.succeed();
    }

    // ------------------------------------------------------------------ el general

    /** Defensores en Línea: la infantería forma entre el centro y el enemigo; los arqueros, detrás de ella. */
    @GameTest(template = TEMPLATE)
    public static void h9DefensoresFormanLinea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "linea");
        List<AbstractRecruitEntity> garrison = village(h, "norte", 5);
        List<AbstractRecruitEntity> archers = garrison.subList(3, 5);
        for (AbstractRecruitEntity s : archers) s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        BlockPos enemyAt = new BlockPos(CENTER.getX() + 20, 1, CENTER.getZ());
        player.moveTo(h.absolutePos(enemyAt).getX() + 0.5, h.absolutePos(enemyAt).getY(), h.absolutePos(enemyAt).getZ() + 0.5);
        List<AbstractRecruitEntity> mine = new ArrayList<>();
        // 8 soldados + vos: parejos o más débiles los defensores (Línea o Replegarse), nunca Cargar
        for (int i = 0; i < 8; i++) mine.add(ownSoldier(h, player.getUUID(), enemyAt.offset(i / 4, 0, i % 4 - 2)));

        VillageData.Village v = VillageData.get(level).at(center(h));
        RaidManager.Raid raid = RaidManager.start(level, v, player.getUUID(), player);
        check(raid != null, "no empezó el ataque");
        BattleGeneral.tick(level, raid);

        double c = center(h).getX() + 0.5;
        double frontMin = Double.MAX_VALUE;
        for (AbstractRecruitEntity s : garrison.subList(0, 3)) {
            Vec3 hold = s.getHoldPos();
            check(hold != null && s.getFollowState() == 3, "un infante no recibió puesto en la línea");
            check(hold.x > c, "un infante no forma hacia el enemigo: x=" + hold.x + " centro=" + c);
            frontMin = Math.min(frontMin, hold.x);
        }
        for (AbstractRecruitEntity s : archers) {
            Vec3 hold = s.getHoldPos();
            check(hold != null && s.getFollowState() == 3, "un arquero no recibió puesto");
            check(hold.x < frontMin, "un arquero quedó delante de la infantería: " + hold.x + " vs " + frontMin);
        }

        RaidManager.discard(level, center(h));
        for (AbstractRecruitEntity s : mine) s.discard();
        cleanup(level, center(h));
        h.succeed();
    }

    /** El plan según las fuerzas, y la caballería que rodea por el costado (no de frente). */
    @GameTest(template = TEMPLATE)
    public static void h9PlanYFlanco(GameTestHelper h) {
        check(BattleGeneral.defenderPlan(2.0) == BattleGeneral.Plan.CHARGE, "con el doble de fuerza el defensor debería cargar");
        check(BattleGeneral.defenderPlan(1.0) == BattleGeneral.Plan.LINE, "parejos, el defensor debería formar línea");
        check(BattleGeneral.defenderPlan(0.5) == BattleGeneral.Plan.FALL_BACK, "con la mitad, el defensor debería replegarse");
        check(BattleGeneral.attackerPlan(1.0) == BattleGeneral.Plan.ADVANCE, "parejos, tus tropas deberían avanzar");
        check(BattleGeneral.attackerPlan(0.3) == BattleGeneral.Plan.HOLD, "muy en contra, tus tropas deberían aguantar");

        Vec3 enemy = new Vec3(100, 64, 100);
        Vec3 dir = new Vec3(1, 0, 0);                 // tus tropas avanzan hacia +x
        Vec3 flank = BattleGeneral.flankPoint(enemy, dir, new Vec3(90, 64, 106));
        check(Math.abs(flank.x - enemy.x) < 0.01, "el flanco no queda al costado del enemigo: x=" + flank.x);
        check(Math.abs(Math.abs(flank.z - enemy.z) - BattleGeneral.FLANK_DISTANCE) < 0.01, "el flanco no está a la distancia pedida");
        check(flank.z > enemy.z, "la caballería debería rodear por su propio costado (z+)");
        h.succeed();
    }

    /** Orden Batalla: si después le das otra orden, el soldado sale de la Batalla. */
    @GameTest(template = TEMPLATE)
    public static void h9OtraOrdenSacaDeBatalla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "orden");
        village(h, "valdren", 2);
        BlockPos at = new BlockPos(CENTER.getX() + 20, 1, CENTER.getZ());
        AbstractRecruitEntity mine = ownSoldier(h, player.getUUID(), at);
        BattleGeneral.setBattle(mine, true);

        VillageData.Village v = VillageData.get(level).at(center(h));
        RaidManager.Raid raid = RaidManager.start(level, v, player.getUUID(), player);
        check(raid != null, "no empezó el ataque");
        BattleGeneral.tick(level, raid);
        check(BattleGeneral.inBattle(mine), "perdió la orden Batalla sin que nadie lo tocara");
        check(mine.getFollowState() == 3 || mine.getFollowState() == 0, "el general no le dio ninguna orden: " + mine.getFollowState());

        mine.setFollowState(1);                        // "seguime", desde Recruits
        BattleGeneral.tick(level, raid);
        check(!BattleGeneral.inBattle(mine), "siguió en Batalla después de otra orden");
        check(mine.getFollowState() == 1, "el general le pisó la orden nueva");

        RaidManager.discard(level, center(h));
        mine.discard();
        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ destrabador de batalla

    /** Encerrado sin salida (no es zapador): ya no se teletransporta (H17); espera y vuelve a probar. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void h9EncerradoNoSeTeletransporta(GameTestHelper h) {
        UUID owner = UUID.nameUUIDFromBytes(("wb-h9-encerrado@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        box(h, Blocks.STONE);
        AbstractRecruitEntity mine = ownSoldier(h, owner, new BlockPos(2, 2, 2));
        AbstractRecruitEntity enemy = ownSoldier(h, UUID.randomUUID(), new BlockPos(7, 2, 2));
        enemy.setNoAi(true);
        mine.setTarget(enemy);
        check(BattleEscape.check(h.getLevel(), mine, true).action() == BattleEscape.Action.NONE, "trabado antes de tiempo");
        h.runAfterDelay(BattleEscape.STUCK_TICKS + 10, () -> {
            mine.setTarget(enemy);
            BattleEscape.Action a = BattleEscape.check(h.getLevel(), mine, true).action();
            check(a == BattleEscape.Action.HOLD, "encerrado sin salida debería esperar y hizo " + a);
            BlockPos rel = mine.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
            check(rel.getX() <= 3, "salió de la caja (¿teletransporte?): " + rel.toShortString());
            box(h, Blocks.AIR);
            mine.discard();
            enemy.discard();
            h.succeed();
        });
    }

    /** Encerrado con una puerta: sale por la puerta (por la malla de navegación o por su cuenta), sin teletransporte. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void h9EncerradoSalePorLaPuerta(GameTestHelper h) {
        UUID owner = UUID.nameUUIDFromBytes(("wb-h9-puerta@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        box(h, Blocks.STONE);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
        h.setBlock(new BlockPos(3, 2, 2), door);
        h.setBlock(new BlockPos(3, 3, 2), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        AbstractRecruitEntity mine = ownSoldier(h, owner, new BlockPos(2, 2, 2));
        AbstractRecruitEntity enemy = ownSoldier(h, UUID.randomUUID(), new BlockPos(7, 2, 2));
        enemy.setNoAi(true);
        mine.setTarget(enemy);
        h.onEachTick(() -> {
            if (h.getTick() % 20 == 0) {
                mine.setTarget(enemy);
                BattleEscape.check(h.getLevel(), mine, true);
            }
        });
        h.succeedWhen(() -> {
            BlockPos rel = mine.blockPosition().subtract(h.absolutePos(BlockPos.ZERO));
            if (rel.getX() < 4) throw new GameTestAssertException("todavía adentro: " + rel.toShortString());
            box(h, Blocks.AIR);
            mine.discard();
            enemy.discard();
        });
    }

    /** Asedio (H17): aldea con muralla y tus soldados en Batalla: atacan por un carril, se bajan del caballo; la guarnición, también. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void h9AsedioConMuralla(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "asedio");
        List<AbstractRecruitEntity> garrison = village(h, "norte", 2);
        com.ale.warbandcraft.upgrade.TownData.Town t = com.ale.warbandcraft.upgrade.TownData.get(level).getOrCreate(center(h));
        t.built.add(com.ale.warbandcraft.upgrade.Upgrade.PALISADE);
        t.ringRadius = 3;
        t.ringWalls = true;   // H22: el anillo automático de H12
        try {
            BlockPos at = new BlockPos(CENTER.getX() + 5, 1, CENTER.getZ());
            List<AbstractRecruitEntity> mine = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                AbstractRecruitEntity s = ownSoldier(h, player.getUUID(), at.offset(0, 0, i));
                BattleGeneral.setBattle(s, true);
                mine.add(s);
            }
            // Uno a caballo: en un asedio se baja
            net.minecraft.world.entity.animal.horse.Horse horse = h.spawn(EntityType.HORSE, at.offset(1, 0, 1));
            mine.get(0).startRiding(horse, true);
            VillageData.Village v = VillageData.get(level).at(center(h));
            RaidManager.Raid raid = RaidManager.start(level, v, player.getUUID(), player);
            check(raid != null, "no empezó el ataque");
            BattleGeneral.tick(level, raid);
            check(!BattleGeneral.siegeLanes(level, center(h)).isEmpty(), "con muralla no armó carriles de asedio");
            check(!mine.get(0).isPassenger(), "el de a caballo no se bajó en el asedio");
            BattleGeneral.tick(level, raid);   // otra vuelta: no se rompe nada
            RaidManager.discard(level, center(h));
            check(BattleGeneral.siegeLanes(level, center(h)).isEmpty(), "quedó el asedio en memoria al terminar");
            for (AbstractRecruitEntity s : mine) s.discard();
            horse.discard();
        } finally {
            com.ale.warbandcraft.upgrade.TownData.get(level).remove(center(h));
            cleanup(level, center(h));
        }
        h.succeed();
    }

    /** Defensor de castillo con el enemigo afuera, sin poder llegar: no sale, se queda adentro defendiendo. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void h9CastilloSeQuedaAdentro(GameTestHelper h) {
        UUID owner = UUID.nameUUIDFromBytes(("wb-h9-castillo@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        box(h, Blocks.STONE);
        AbstractRecruitEntity defender = ownSoldier(h, owner, new BlockPos(2, 2, 2));
        AbstractRecruitEntity enemy = ownSoldier(h, UUID.randomUUID(), new BlockPos(7, 2, 2));
        enemy.setNoAi(true);
        defender.setTarget(enemy);
        BlockPos start = defender.blockPosition();
        BattleEscape.check(h.getLevel(), defender, true, true);
        h.runAfterDelay(BattleEscape.STUCK_TICKS + 10, () -> {
            defender.setTarget(enemy);
            BattleEscape.Action a = BattleEscape.check(h.getLevel(), defender, true, true).action();
            check(a == BattleEscape.Action.HOLD, "el defensor del castillo debería quedarse adentro y hizo " + a);
            check(defender.blockPosition().equals(start), "el defensor del castillo se movió de adentro");
            box(h, Blocks.AIR);
            defender.discard();
            enemy.discard();
            h.succeed();
        });
    }

    /** Un zapador encerrado pica la pared hacia su enemigo. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void h9ZapadorEncerradoPica(GameTestHelper h) {
        UUID owner = UUID.nameUUIDFromBytes(("wb-h9-zapador@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        box(h, Blocks.STONE);
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("recruits:siege_engineer"));
        check(type != null, "Recruits no tiene zapador");
        Entity e = h.spawn(type, new BlockPos(2, 2, 2));
        check(e instanceof AbstractRecruitEntity, "el zapador no es un soldado de Recruits");
        AbstractRecruitEntity sapper = (AbstractRecruitEntity) e;
        sapper.setOwnerUUID(Optional.of(owner));
        sapper.setIsOwned(true);
        AbstractRecruitEntity enemy = ownSoldier(h, UUID.randomUUID(), new BlockPos(7, 2, 2));
        enemy.setNoAi(true);
        sapper.setTarget(enemy);
        BattleEscape.check(h.getLevel(), sapper, true);
        h.runAfterDelay(BattleEscape.STUCK_TICKS + 10, () -> {
            sapper.setTarget(enemy);
            BattleEscape.Action a = BattleEscape.check(h.getLevel(), sapper, true).action();
            check(a == BattleEscape.Action.DIG, "el zapador encerrado debería picar y hizo " + a);
            check(h.getBlockState(new BlockPos(3, 2, 2)).isAir() || h.getBlockState(new BlockPos(3, 3, 2)).isAir(),
                    "no rompió la pared hacia el enemigo");
            box(h, Blocks.AIR);
            sapper.discard();
            enemy.discard();
            h.succeed();
        });
    }

    /** Trabado yendo a su puesto de la formación: la deja y queda libre para buscar enemigos. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void h9TrabadoDejaLaFormacion(GameTestHelper h) {
        UUID owner = UUID.nameUUIDFromBytes(("wb-h9-formacion@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        box(h, Blocks.STONE);
        AbstractRecruitEntity mine = ownSoldier(h, owner, new BlockPos(2, 2, 2));
        Vec3 post = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(7, 2, 2)));
        mine.setHoldPos(post);
        mine.setFollowState(3);
        BattleEscape.check(h.getLevel(), mine, true);
        h.runAfterDelay(BattleEscape.STUCK_TICKS + 10, () -> {
            mine.setTarget(null);
            mine.setHoldPos(post);
            mine.setFollowState(3);
            BattleEscape.Action a = BattleEscape.check(h.getLevel(), mine, true).action();
            check(a == BattleEscape.Action.FREE, "trabado yendo a su puesto debería dejar la formación y hizo " + a);
            box(h, Blocks.AIR);
            mine.discard();
            h.succeed();
        });
    }

    /** Caja de 3x3x3 (hueco de 1x2 adentro, en (2,2,2)) de {@code block}; AIR la borra. */
    private static void box(GameTestHelper h, net.minecraft.world.level.block.Block block) {
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                for (int y = 2; y <= 4; y++) {
                    boolean inside = x == 2 && z == 2 && y <= 3;
                    if (!inside) h.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    // ------------------------------------------------------------------ guardado

    /** Un ataque se guarda y se vuelve a leer igual (caídos, equipo, rendidos, estado). */
    @GameTest(template = TEMPLATE)
    public static void h9AtaqueSeGuarda(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer player = player(h, "guardado");
        village(h, "norte", 1);
        VillageData.Village v = VillageData.get(level).at(center(h));
        RaidManager.Raid raid = RaidManager.start(level, v, player.getUUID(), player);
        check(raid != null, "no empezó el ataque");
        raid.killed = 4;
        raid.fallenGear.add(new ItemStack(Items.IRON_SWORD));
        UUID ghost = UUID.nameUUIDFromBytes("rendido".getBytes());
        raid.surrendered.add(ghost);

        CompoundTag saved = new RaidData().save(new CompoundTag());
        RaidManager.Raid back = null;
        for (RaidManager.Raid r : RaidData.load(saved).loadedRaids()) {
            if (r.center.equals(raid.center) && r.dimension == raid.dimension) back = r;
        }
        check(back != null, "el ataque no se leyó del guardado");
        check(back.attacker.equals(player.getUUID()), "cambió el atacante");
        check(back.faction.equals(raid.faction), "cambió la facción");
        check(back.killed == 4, "cambiaron los caídos: " + back.killed);
        check(back.fallenGear.size() == 1 && back.fallenGear.get(0).is(Items.IRON_SWORD), "se perdió el equipo de los caídos");
        check(back.surrendered.contains(ghost), "se perdieron los rendidos");
        check(back.state == RaidManager.State.FIGHTING, "cambió el estado");

        RaidManager.discard(level, center(h));
        cleanup(level, center(h));
        h.succeed();
    }

    // ------------------------------------------------------------------ utilidades

    private static void willSurrender(AbstractRecruitEntity s) {
        s.setInvulnerable(false);
        s.getPersistentData().putDouble(Surrender.TAG_TEST_CHANCE, 1.0);
    }

    private static FakePlayer player(GameTestHelper h, String name) {
        UUID id = UUID.nameUUIDFromBytes(("wb-h9-" + name + "@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        FakePlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(id, "wb_" + name));
        BlockPos c = center(h);
        p.moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return p;
    }

    private static BlockPos center(GameTestHelper h) {
        return h.absolutePos(CENTER);
    }

    /** Registra una aldea en CENTER y le pone {@code count} soldados de a pie. */
    private static List<AbstractRecruitEntity> village(GameTestHelper h, String factionName, int count) {
        ServerLevel level = h.getLevel();
        Faction f = faction(factionName);
        BlockPos c = center(h);
        VillageData.get(level).add(new VillageData.Village(c, f.id(), level.getGameTime()));
        RandomSource random = RandomSource.create(c.asLong());
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var troop = f.troops().stream().filter(t -> t.kit() != null && t.resolve() != null && !t.resolve().horse())
                    .findFirst().orElseThrow();
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, c, f, troop, random, 1);
            check(s != null, "no apareció un soldado de " + f.name());
            out.add(s);
        }
        return out;
    }

    private static AbstractRecruitEntity ownSoldier(GameTestHelper h, UUID owner, BlockPos rel) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse("recruits:recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, rel);
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity r = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
        r.getInventory().clearContent();
        r.setOwnerUUID(Optional.of(owner));
        r.setIsOwned(true);
        return r;
    }

    private static CampBlockEntity camp(GameTestHelper h, UUID owner) {
        h.setBlock(CAMP_POS, ModBlocks.CAMP.get());
        if (!(h.getBlockEntity(CAMP_POS) instanceof CampBlockEntity camp)) throw new GameTestAssertException("no se creó el Campamento");
        camp.setOwner(owner);
        return camp;
    }

    /** Saca a la guarnición (y a los rendidos) y deja la aldea como saqueada para siempre, para no molestar a otras pruebas. */
    private static void cleanup(ServerLevel level, BlockPos center) {
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class,
                new net.minecraft.world.phys.AABB(center).inflate(64),
                e -> e.getPersistentData().contains(VillageManager.TAG_VILLAGE)
                        && e.getPersistentData().getLong(VillageManager.TAG_VILLAGE) == center.asLong())) {
            if (s.getVehicle() != null) s.getVehicle().discard();
            s.discard();
        }
        VillageData.Village v = VillageData.get(level).at(center);
        if (v != null) VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    private static Faction faction(String name) {
        Faction f = FactionManager.INSTANCE.get(ResourceLocation.tryParse(WarbandCraft.MODID + ":" + name));
        check(f != null, "no se cargó la facción " + name);
        return f;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
