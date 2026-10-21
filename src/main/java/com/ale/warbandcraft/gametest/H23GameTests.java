package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.BattleSize;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.invasion.Ambush;
import com.ale.warbandcraft.invasion.Assault;
import com.ale.warbandcraft.invasion.Hosts;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.GarrisonDefense;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas del Hito 23: el tamaño y la chance de la hueste, la hueste que marcha si no vas, la batalla con oleadas y la
 * victoria, las oleadas de una invasión grande, meterse en una batalla entre facciones con el botón (soldados que se
 * suman, opinión, botín) y asediar con el botón. Cada una con hueste o jugador va en su propia tanda: {@code Hosts.tick}
 * recorre todas las huestes y los jugadores de prueba se ven desde las otras pruebas.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H23GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 1, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");
    private static final ResourceLocation OTHER = new ResourceLocation(WarbandCraft.MODID, "sultanato");

    @GameTest(template = TEMPLATE)
    public static void hostSizeAndChance(GameTestHelper h) {
        RandomSource r = RandomSource.create(7);
        check(Hosts.sizeFor(5, 150, r) == Hosts.MIN_SIZE, "con poca fuerza tendría que mandar el mínimo");
        check(Hosts.sizeFor(500, 150, r) == Hosts.MAX_SIZE, "con mucha fuerza tendría que mandar el máximo");
        for (int i = 0; i < 20; i++) {
            int s = Hosts.sizeFor(50, 150, r);
            check(s >= 55 && s <= 75, "contra 50 soldados mandó " + s + " (esperaba 55-75)");
        }
        check(Hosts.sizeFor(50, 20, r) == 0, "sin hombres juntados no tendría que salir");
        check(Math.abs(Hosts.chance(Relations.WAR_THRESHOLD) - Hosts.CHANCE_BASE) < 1e-9, "en el umbral de guerra la chance es la base");
        check(Math.abs(Hosts.chance(Relations.MIN) - (Hosts.CHANCE_BASE + Hosts.CHANCE_HATE)) < 1e-9, "con odio total la chance es la máxima");
        check(Hosts.chance(-70) > Hosts.chance(-40) && Hosts.chance(-70) < Hosts.chance(-100), "la chance no crece con el odio");
        h.succeed();
    }

    /** Pasaron los 3 días y no fuiste: la hueste entera marcha contra la aldea (invasión con general). */
    @GameTest(template = TEMPLATE, batch = "h23_host_march")
    public static void hostMarchesWhenNobodyComes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        InvasionData.Host host = Hosts.place(level, OTHER, owner, center, center.east(30), 40, 0);
        try {
            check(Hosts.threatens(level, center), "la aldea no figura amenazada");
            Hosts.tick(level);
            check(data.host(host.id) == null, "la hueste sigue acampada después del plazo");
            InvasionData.Invasion inv = data.at(center);
            check(inv != null, "no salió la invasión contra la aldea");
            check(inv.size == 40 && inv.kind == InvasionData.Kind.ARMY && inv.faction.equals(OTHER),
                    "la invasión no es la hueste entera: " + inv.size + " " + inv.kind);
            check(data.hostCooldown(OTHER, owner) >= level.getDayTime() / 24000L, "no quedó el descanso entre huestes");
        } finally {
            data.remove(center);
            data.removeHost(host.id);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** La batalla: aparecen hasta el tamaño de batalla, el resto entra en oleadas; sin nadie en pie, ganaste. */
    @GameTest(template = TEMPLATE, batch = "h23_host_battle")
    public static void hostFightsInWaves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        int max = BattleSize.MAX_PER_SIDE;
        double gap = Hosts.GAP;
        BattleSize.MAX_PER_SIDE = 4;
        Hosts.GAP = 3;
        InvasionData.Host host = Hosts.place(level, FACTION, owner, center, h.absolutePos(new BlockPos(6, 2, 4)), 7, 24000);
        try {
            Vec3 trigger = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(2, 2, 4)));
            List<AbstractRecruitEntity> first = Hosts.startBattle(level, host, trigger);
            check(first.size() == 4, "aparecieron " + first.size() + " en vez de 4 (tamaño de batalla)");
            check(host.reserve == 3 && host.size == 7 && host.state == InvasionData.Host.State.FIGHTING,
                    "la hueste no quedó peleando con 3 esperando: reserva " + host.reserve + ", tamaño " + host.size);
            for (AbstractRecruitEntity s : first) {
                check(s.getPersistentData().getString(Hosts.TAG_HOST).equals(host.id.toString()), "un soldado no tiene la marca de la hueste");
                check(s.getPersistentData().getBoolean(Ambush.TAG_FIELD), "un soldado no cuenta como ejército de campo");
            }
            check(Hosts.phase(host) == Hosts.Phase.FORM, "la hueste no empezó formada");

            first.get(0).discard();
            first.get(1).discard();
            Hosts.battleTick(level, host, level.getGameTime());
            check(Hosts.soldiers(level, host).size() == 4 && host.reserve == 1,
                    "la oleada no llenó los lugares: en el campo " + Hosts.soldiers(level, host).size() + ", reserva " + host.reserve);
            check(host.size == 5, "la hueste tendría que tener 5, tiene " + host.size);

            // Cae uno con armadura: su pechera va al botín (no queda tirada), con armamento por el caído
            AbstractRecruitEntity fallen = Hosts.soldiers(level, host).get(0);
            fallen.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            fallen.kill();
            check(com.ale.warbandcraft.battle.BattleLoot.count(owner, Items.IRON_CHESTPLATE) >= 1, "la pechera del caído no fue al botín");
            check(com.ale.warbandcraft.battle.BattleLoot.count(owner, com.ale.warbandcraft.init.ModItems.ARMAMENT.get()) >= 1,
                    "el caído no sumó armamento al botín");
            check(fallen.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "la pechera quedó puesta (se caería al piso)");

            for (AbstractRecruitEntity s : Hosts.soldiers(level, host)) s.discard();
            host.reserve = 0;
            Hosts.battleTick(level, host, level.getGameTime());
            check(data.host(host.id) == null, "sin nadie en pie la hueste sigue");
            check(com.ale.warbandcraft.battle.BattleLoot.count(owner, Items.IRON_CHESTPLATE) == 0, "al ganar el botín no se mandó");
            boolean chest = false;
            for (ItemStack s : com.ale.warbandcraft.camp.CampRegistry.get(level).pendingFor(owner)) chest |= s.is(Items.IRON_CHESTPLATE);
            check(chest, "la pechera no está entre lo que se manda al Campamento");
            check(data.hostCooldown(FACTION, owner) >= level.getDayTime() / 24000L + Hosts.DEFEAT_COOLDOWN_DAYS - 1,
                    "después de vencerla no quedó el descanso largo");
        } finally {
            BattleSize.MAX_PER_SIDE = max;
            Hosts.GAP = gap;
            for (Entity e : level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(center).inflate(32),
                    e -> e.getPersistentData().contains(Hosts.TAG_HOST))) e.discard();
            data.removeHost(host.id);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Una invasión más grande que el tamaño de batalla: el resto espera y entra cuando caen los de adelante. */
    @GameTest(template = TEMPLATE, batch = "h23_assault")
    public static void bigInvasionComesInWaves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, FACTION, 0).withOwner(owner);
        VillageData.get(level).add(v);
        int max = BattleSize.MAX_PER_SIDE;
        BattleSize.MAX_PER_SIDE = 3;
        InvasionData.Invasion inv = new InvasionData.Invasion(center, FACTION, owner, 5, level.getGameTime(), center);
        InvasionData.get(level).put(inv);
        try {
            List<AbstractRecruitEntity> spawned = Invasions.startFight(level, inv);
            check(spawned.size() == 3, "aparecieron " + spawned.size() + " invasores en vez de 3");
            check(inv.reserve == 2 && inv.size == 5, "no quedaron 2 esperando: reserva " + inv.reserve + ", tamaño " + inv.size);
            spawned.get(0).discard();
            Invasions.fightTick(level, inv, v, level.getGameTime());
            check(Invasions.invaders(level, center).size() == 3 && inv.reserve == 1 && inv.size == 4,
                    "la oleada no entró: en el campo " + Invasions.invaders(level, center).size() + ", reserva " + inv.reserve + ", tamaño " + inv.size);
        } finally {
            BattleSize.MAX_PER_SIDE = max;
            for (AbstractRecruitEntity s : Invasions.invaders(level, center)) s.discard();
            Assault.end(level, inv);
            InvasionData.get(level).remove(center);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Batalla entre facciones: el botón Ayudar suma a tus soldados de cerca, el otro bando los ve como enemigos y hay botín. */
    @GameTest(template = TEMPLATE, batch = "h23_join")
    public static void joinAiBattleWithButton(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, OTHER, 0);
        VillageData.get(level).add(v);
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        InvasionData.March m = new InvasionData.March(UUID.randomUUID(), FACTION, center, center.east(300), 2, now - 10, now);
        data.putMarch(m);
        ServerPlayer p = player(level, h, center);
        List<AbstractRecruitEntity> defenders = new ArrayList<>(), attackers = new ArrayList<>();
        AbstractRecruitEntity mine = null;
        try {
            Faction other = FactionManager.INSTANCE.get(OTHER);
            check(other != null, "no está la facción " + OTHER);
            for (int i = 0; i < 2; i++) {
                AbstractRecruitEntity s = VillageManager.spawnTroop(level, center, other, other.pickTroop(level.getRandom()), level.getRandom(), 3);
                if (s != null) defenders.add(s);
            }
            check(defenders.size() == 2, "no aparecieron los defensores");
            attackers.addAll(AiWars.startBattle(level, m));
            check(m.fighting && m.startDefenders == 2, "la batalla no empezó con 2 defensores");
            mine = soldier(h, p.getUUID());

            Relations rel = Relations.get(level.getServer());
            int before = rel.opinion(p.getUUID(), OTHER);
            int n = AiWars.join(level, p, m, true);
            check(n == 1, "se tendría que haber metido tu soldado, se metieron " + n);
            check(m.helpedAttack.contains(p.getUUID()), "no quedó anotado que ayudás al atacante");
            check(rel.opinion(p.getUUID(), OTHER) == before + AiWars.JOIN_OPINION, "meterte no bajó la opinión del otro bando");
            check(AiWars.isFoe(level, p.getUUID(), defenders.get(0)), "los defensores no son tus enemigos");
            check(!AiWars.isFoe(level, p.getUUID(), attackers.get(0)), "los atacantes que ayudás son tus enemigos");
            check(GarrisonDefense.isHostile(defenders.get(0), mine), "los defensores no van contra tu soldado");

            AiWars.battleTick(level, m);
            check(mine.getTarget() != null && defenders.contains(mine.getTarget()), "tu soldado no fue contra un defensor");

            int beforeAlly = rel.opinion(p.getUUID(), FACTION);
            for (AbstractRecruitEntity s : defenders) s.discard();
            AiWars.battleTick(level, m);
            check(data.battleAt(center) == null, "la batalla no terminó");
            check(rel.opinion(p.getUUID(), FACTION) == Math.min(Relations.MAX, beforeAlly + AiWars.HELP_OPINION), "el ganador no te agradeció");
            int got = 0;   // sin Campamentos, el botín queda esperando para el primero que se cargue
            for (ItemStack s : com.ale.warbandcraft.camp.CampRegistry.get(level).pendingFor(p.getUUID())) if (s.is(Items.EMERALD)) got += s.getCount();
            check(got == AiWars.LOOT_BASE, "el premio fue " + got + " esmeraldas");
            check(!mine.getPersistentData().contains(AiWars.TAG_JOIN), "tu soldado sigue metido en una batalla que terminó");
        } finally {
            for (AbstractRecruitEntity s : defenders) s.discard();
            for (AbstractRecruitEntity s : attackers) s.discard();
            if (mine != null) mine.discard();
            data.removeMarch(m.id);
            VillageData.get(level).add(new VillageData.Village(center, OTHER, 0).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    /** Asediar con el botón: empieza el ataque y tus soldados de cerca entran en modo Batalla, sin pegarle a nadie. */
    @GameTest(template = TEMPLATE, batch = "h23_siege")
    public static void siegeWithButton(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(CENTER);
        VillageData.Village v = new VillageData.Village(center, OTHER, 0);
        VillageData.get(level).add(v);
        ServerPlayer p = player(level, h, center);
        AbstractRecruitEntity guard = null, mine = null;
        try {
            Faction other = FactionManager.INSTANCE.get(OTHER);
            check(other != null, "no está la facción " + OTHER);
            guard = VillageManager.spawnTroop(level, center, other, other.pickTroop(level.getRandom()), level.getRandom(), 3);
            check(guard != null, "no apareció la guarnición");
            mine = soldier(h, p.getUUID());
            BattleChoices.siege(p, center);
            RaidManager.Raid raid = RaidManager.get(level, center);
            check(raid != null && raid.attacker.equals(p.getUUID()), "no empezó el ataque");
            check(BattleGeneral.inBattle(mine), "tu soldado no entró en modo Batalla");
        } finally {
            RaidManager.discard(level, center);
            if (guard != null) guard.discard();
            if (mine != null) mine.discard();
            VillageData.get(level).add(new VillageData.Village(center, OTHER, 0).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ayudas

    /** Un jugador de prueba (FakePlayer de Forge: el de vanilla se rompe con los paquetes de los mods) parado en {@code at}. */
    private static ServerPlayer player(ServerLevel level, GameTestHelper h, BlockPos at) {
        UUID id = UUID.nameUUIDFromBytes(("warbandcraft-h23-player@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
        ServerPlayer p = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(id, "h23"));
        p.setPos(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        return p;
    }

    /** Un soldado tuyo (de {@code owner}) con espada, al lado del centro. */
    private static AbstractRecruitEntity soldier(GameTestHelper h, UUID owner) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "Recruits no está cargado");
        Entity e = h.spawn(type, new BlockPos(3, 2, 4));
        check(e instanceof AbstractRecruitEntity, "recruits:recruit no es un AbstractRecruitEntity");
        AbstractRecruitEntity r = (AbstractRecruitEntity) e;
        for (EquipmentSlot slot : EquipmentSlot.values()) r.setItemSlot(slot, ItemStack.EMPTY);
        r.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        r.setOwnerUUID(Optional.of(owner));
        r.setIsOwned(true);
        r.setFollowState(1);
        return r;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h23@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
