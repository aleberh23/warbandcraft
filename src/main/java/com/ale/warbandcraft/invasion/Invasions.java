package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.ale.warbandcraft.village.VillageStock;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * IA viva (H15a): las facciones juntan tesoro y leva cada día, te roban si las saqueás (al revés: les robás) y, si
 * están en guerra con vos, mandan ejércitos contra tus aldeas. La invasión se pelea de verdad si estás cerca al
 * llegar; si no, se autoresuelve. Los números están acá arriba.
 */
public final class Invasions {
    /** Leva: soldados por día por cada aldea/castillo de la facción, y tope. */
    public static int LEVY_PER_SETTLEMENT = 2, LEVY_CAP = 40;
    /** Incursión: mínimo para salir, base + por asentamiento, y máximo. */
    public static int MIN_ARMY = 6, ARMY_BASE = 4, ARMY_PER_SETTLEMENT = 2, MAX_ARMY = 16;
    /**
     * Ejército con general ({@link Assault}): leva que hace falta, chance (si la hay) de mandar uno en vez de una
     * incursión, mínimo, por asentamiento y máximo. Las reconquistas siempre van con ejército si alcanza la leva.
     */
    public static int GENERAL_ARMY_LEVY = 14, MIN_GENERAL_ARMY = 10, GENERAL_PER_SETTLEMENT = 3, MAX_GENERAL_ARMY = 24;
    public static double GENERAL_ARMY_CHANCE = 0.5;
    /** Autoresolución de un ejército con general: su fuerza ×, y cuánto le queda del bonus de los muros (trae zapadores). */
    public static double GENERAL_POWER = 1.3, SAPPER_WALL_FACTOR = 0.5;
    /** Chance por día de que una facción en guerra mande un ejército. */
    public static double DAILY_CHANCE = 0.35;
    /** Cuenta regresiva hasta que llega. */
    public static int COUNTDOWN_TICKS = 20 * 60 * 5;
    /** Pelea real: vos a esta distancia al llegar; defensores cuentan a tanto. */
    public static int REAL_DISTANCE = 100, DEFENDER_RANGE = 64;
    /** Pelea real: sin defensores durante esto, la aldea se pierde. */
    /** Sin defensores, un invasor a tanto del almacén (horizontal, y 3 de alto) = la aldea se pierde. */
    public static double STOREHOUSE_REACH = 3;
    /** Autoresolución: fuerza por invasor, milicia de la aldea/castillo, guardia (base + por nivel). */
    public static double INVADER_POWER = 1.2, VILLAGE_MILITIA = 2, CASTLE_MILITIA = 4, GUARD_POWER = 1, GUARD_PER_LEVEL = 0.15;
    /** Defensas (H12): castillo, empalizada, muralla, torres. */
    public static double CASTLE_DEFENSE = 1.5, PALISADE_DEFENSE = 1.25, WALL_DEFENSE = 1.5, TOWERS_DEFENSE = 0.25;
    /** Guardias que cuentan para una aldea: puesto a esta distancia del centro. */
    public static int GUARD_RADIUS = 48;
    /**
     * Reconquista: chance por día de que una facción en paz con vos quiera recuperar una aldea/castillo que le
     * conquistaste. Te declara la guerra (opinión a {@link #RECONQUER_OPINION} si estaba más arriba) y manda el ejército.
     */
    public static double RECONQUER_CHANCE = 0.15;
    public static int RECONQUER_OPINION = -50;
    /** Marca de los invasores (centro de la aldea que atacan). */
    public static final String TAG_INVADER = "warbandcraft:invader";

    private Invasions() {}

    // ------------------------------------------------------------------ cada día

    /** Día nuevo: las aldeas de facción juntan su almacén, crece la leva y las facciones en guerra mandan ejércitos. */
    public static void onNewDay(ServerLevel level, boolean launch) {
        VillageData villages = VillageData.get(level);
        for (VillageData.Village v : new ArrayList<>(villages.all())) {
            if (v.isConquered() || villages.isTest(v.center()) || v.isLooted(level.getGameTime())) continue;
            villages.setStock(v.center(), villages.stock(v.center()).plus(VillageStock.daily(level, v)).min(VillageStock.cap(level, v)));
        }
        growLevy(level);
        if (launch) {
            launchAll(level);
            AiWars.onNewDay(level);   // H15b: guerras entre facciones
        }
        Hosts.onNewDay(level, launch);   // H23: huestes
    }

    /** Cada facción suma {@link #LEVY_PER_SETTLEMENT} por aldea/castillo suyo, hasta {@link #LEVY_CAP}. */
    public static void growLevy(ServerLevel level) {
        InvasionData data = InvasionData.get(level);
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            int n = settlements(level, f.id()).size();
            if (n > 0) data.setLevy(f.id(), Math.min(LEVY_CAP, data.levy(f.id()) + LEVY_PER_SETTLEMENT * n));
        }
    }

    /** Las aldeas y castillos de la facción (sin dueño, no de prueba). */
    public static List<VillageData.Village> settlements(ServerLevel level, ResourceLocation faction) {
        VillageData data = VillageData.get(level);
        List<VillageData.Village> out = new ArrayList<>();
        for (VillageData.Village v : data.all()) {
            if (!v.isConquered() && v.faction().equals(faction) && !data.isTest(v.center())) out.add(v);
        }
        return out;
    }

    private static void launchAll(ServerLevel level) {
        Relations rel = Relations.get(level.getServer());
        RandomSource random = level.getRandom();
        InvasionData data = InvasionData.get(level);
        List<UUID> owners = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).all()) if (v.isConquered() && !owners.contains(v.owner())) owners.add(v.owner());
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            for (UUID owner : owners) {
                if (!rel.atWar(owner, f.id())) {   // en paz: quizás quiera recuperar lo suyo
                    if (data.levy(f.id()) >= MIN_ARMY && random.nextDouble() < RECONQUER_CHANCE) reconquer(level, owner, f.id(), -1, COUNTDOWN_TICKS);
                    continue;
                }
                if (!rel.atWar(owner, f.id()) || data.levy(f.id()) < MIN_ARMY) continue;
                if (random.nextDouble() >= DAILY_CHANCE) continue;
                boolean army = data.levy(f.id()) >= GENERAL_ARMY_LEVY && random.nextDouble() < GENERAL_ARMY_CHANCE;
                launch(level, owner, f.id(), -1, COUNTDOWN_TICKS, null, army ? InvasionData.Kind.ARMY : InvasionData.Kind.RAID);
            }
        }
    }

    /** Las aldeas/castillos de {@code owner} que antes eran de {@code faction}. */
    public static List<VillageData.Village> takenFrom(ServerLevel level, UUID owner, ResourceLocation faction) {
        List<VillageData.Village> out = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (owner.equals(v.owner()) && v.faction().equals(faction) && InvasionData.get(level).at(v.center()) == null
                    && !VillageData.get(level).isFounded(v.center())) out.add(v);   // la que fundaste nunca fue suya
        }
        return out;
    }

    /**
     * Reconquista: {@code faction} te declara la guerra y manda un ejército contra una aldea/castillo que era suyo.
     * Devuelve la invasión, o null si no tiene nada que recuperar o con qué.
     */
    @Nullable
    public static InvasionData.Invasion reconquer(ServerLevel level, UUID owner, ResourceLocation faction, int size, int countdown) {
        List<VillageData.Village> lost = takenFrom(level, owner, faction);
        if (lost.isEmpty()) return null;
        VillageData.Village target = lost.get(level.getRandom().nextInt(lost.size()));
        boolean army = size < 0 ? InvasionData.get(level).levy(faction) >= GENERAL_ARMY_LEVY : size >= MIN_GENERAL_ARMY;
        InvasionData.Invasion inv = launch(level, owner, faction, size, countdown, target.center(),
                army ? InvasionData.Kind.ARMY : InvasionData.Kind.RAID);
        if (inv == null) return null;
        Relations rel = Relations.get(level.getServer());
        int now = rel.opinion(owner, faction);
        if (now > RECONQUER_OPINION) rel.add(owner, faction, RECONQUER_OPINION - now);
        tell(level, owner, Component.translatable("msg.warbandcraft.invasion.reconquer", factionName(faction), placeName(level, inv))
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        return inv;
    }

    /**
     * Manda un ejército de {@code faction} contra tu aldea/castillo más cercano a sus tierras. {@code size} -1 = lo que
     * dé la leva. Devuelve la invasión, o null si no hay a quién atacar o con qué.
     */
    @Nullable
    public static InvasionData.Invasion launch(ServerLevel level, UUID owner, ResourceLocation faction, int size, int countdown) {
        return launch(level, owner, faction, size, countdown, null, InvasionData.Kind.RAID);
    }

    /** Igual, pero solo contra {@code only} (si no es null) y de la clase {@code kind} (incursión o ejército con general). */
    @Nullable
    public static InvasionData.Invasion launch(ServerLevel level, UUID owner, ResourceLocation faction, int size, int countdown,
                                               @Nullable BlockPos only, InvasionData.Kind kind) {
        InvasionData data = InvasionData.get(level);
        List<VillageData.Village> home = settlements(level, faction);
        VillageData.Village target = null;
        BlockPos from = null;
        double best = Double.MAX_VALUE;
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (!owner.equals(v.owner()) || data.at(v.center()) != null) continue;
            if (only == null && Hosts.threatens(level, v.center())) continue;   // H23: ya la amenaza una hueste
            if (only != null && !only.equals(v.center())) continue;
            if (home.isEmpty()) {
                if (target == null) target = v;
                continue;
            }
            for (VillageData.Village h : home) {
                double d = VillageData.horizontalDistSq(h.center(), v.center());
                if (d < best) {
                    best = d;
                    target = v;
                    from = h.center();
                }
            }
        }
        if (target == null) return null;
        if (size < 0) {
            if (kind == InvasionData.Kind.ARMY) {
                int max = Math.min(MAX_GENERAL_ARMY, ARMY_BASE + GENERAL_PER_SETTLEMENT * home.size());
                size = Math.min(max, data.levy(faction));
                if (size < MIN_GENERAL_ARMY) kind = InvasionData.Kind.RAID;   // no alcanza: incursión
            }
            if (kind == InvasionData.Kind.RAID) {
                int max = Math.min(MAX_ARMY, ARMY_BASE + ARMY_PER_SETTLEMENT * home.size());
                size = Math.min(max, data.levy(faction));
            }
            if (size < MIN_ARMY) return null;
            data.setLevy(faction, data.levy(faction) - size);
        }
        if (from == null) {
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            from = target.center().offset((int) (Math.cos(a) * 200), 0, (int) (Math.sin(a) * 200));
        }
        InvasionData.Invasion inv = new InvasionData.Invasion(target.center(), faction, owner, size, level.getGameTime() + countdown, from);
        inv.startTick = level.getGameTime();
        inv.kind = kind;
        data.put(inv);
        warn(level, inv);
        return inv;
    }

    /** Aviso de invasión al dueño (si está conectado). */
    public static void warn(ServerLevel level, InvasionData.Invasion inv) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p == null) return;
        long left = Math.max(0, inv.arriveTick - level.getGameTime());
        String key = inv.kind == InvasionData.Kind.ARMY ? "msg.warbandcraft.invasion.warning_army" : "msg.warbandcraft.invasion.warning";
        p.sendSystemMessage(Component.translatable(key, factionName(inv.faction), inv.size,
                placeName(level, inv), time(left), REAL_DISTANCE).withStyle(ChatFormatting.RED));
        p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.6F, 1.0F);
    }

    // ------------------------------------------------------------------ cada segundo

    public static void tick(ServerLevel level) {
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        for (InvasionData.Invasion inv : new ArrayList<>(data.all())) {
            VillageData.Village v = VillageData.get(level).at(inv.target);
            if (v == null || !inv.owner.equals(v.owner())) {   // ya no es tuya: no hay a quién invadir
                data.remove(inv.target);
                continue;
            }
            if (inv.state == InvasionData.State.MARCHING) {
                if (now < inv.arriveTick) continue;
                if (ownerNear(level, inv) && level.isPositionEntityTicking(inv.target)) startFight(level, inv);
                else autoresolve(level, inv, v);
            } else {
                fightTick(level, inv, v, now);
            }
        }
    }

    private static boolean ownerNear(ServerLevel level, InvasionData.Invasion inv) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        return p != null && p.level() == level && p.isAlive()
                && VillageData.horizontalDistSq(p.blockPosition(), inv.target) <= (double) REAL_DISTANCE * REAL_DISTANCE;
    }

    // ------------------------------------------------------------------ pelea real

    /**
     * El ejército aparece afuera de la aldea (afuera de los muros, si los hay), del lado de sus tierras, y la ataca:
     * ver {@link Assault}.
     */
    public static List<AbstractRecruitEntity> startFight(ServerLevel level, InvasionData.Invasion inv) {
        Faction f = FactionManager.INSTANCE.get(inv.faction);
        VillageData.Village v = VillageData.get(level).at(inv.target);
        if (f == null || v == null) {
            InvasionData.get(level).remove(inv.target);
            return new ArrayList<>();
        }
        List<AbstractRecruitEntity> out = Assault.spawn(level, inv, v, f);
        inv.state = InvasionData.State.FIGHTING;
        inv.size = out.size() + inv.reserve;
        InvasionData.get(level).changed();
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null) {
            String key = inv.kind == InvasionData.Kind.ARMY ? "msg.warbandcraft.invasion.arrived_army" : "msg.warbandcraft.invasion.arrived";
            p.sendSystemMessage(Component.translatable(key, factionName(inv.faction), placeName(level, inv))
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        return out;
    }

    /** Los invasores que siguen en pie (cargados). */
    public static List<AbstractRecruitEntity> invaders(ServerLevel level, BlockPos target) {
        long key = target.asLong();
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(target).inflate(96, 64, 96),
                e -> e.isAlive() && e.getPersistentData().contains(TAG_INVADER) && e.getPersistentData().getLong(TAG_INVADER) == key
                        && !Surrender.is(e));
    }

    /** Los tuyos que defienden: vos y tus soldados a {@link #DEFENDER_RANGE} bloques. */
    public static int defenders(ServerLevel level, InvasionData.Invasion inv) {
        int n = 0;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null && p.level() == level && p.isAlive() && !p.isSpectator()
                && VillageData.horizontalDistSq(p.blockPosition(), inv.target) <= (double) DEFENDER_RANGE * DEFENDER_RANGE) n++;
        n += level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(inv.target).inflate(DEFENDER_RANGE, 48, DEFENDER_RANGE),
                e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), inv.owner) && Soldiers.isSoldier(e) && !Surrender.is(e)).size();
        return n;
    }

    public static void fightTick(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, long now) {
        InvasionData data = InvasionData.get(level);
        if (!level.isPositionEntityTicking(inv.target)) {   // te fuiste: lo que queda se autoresuelve
            Assault.end(level, inv);
            autoresolve(level, inv, v);
            return;
        }
        List<AbstractRecruitEntity> left = invaders(level, inv.target);
        if (inv.reserve > 0) left.addAll(Assault.reinforce(level, inv, v, left.size()));   // H23: oleadas
        if (left.size() + inv.reserve != inv.size) {
            inv.size = left.size() + inv.reserve;
            data.changed();
        }
        if (Assault.tick(level, inv, v, left)) return;   // se retiraron
        SiegeDefense.tick(level, inv, v);
        bar(level, inv, left.size() + inv.reserve);
        int defs = defenders(level, inv);
        if (defs > inv.peakDefenders) {
            inv.peakDefenders = defs;
            data.changed();
        }
        if (left.isEmpty() && inv.reserve <= 0) {
            WarReports.record(level, inv, WarReports.Outcome.REPELLED, true, inv.attackers, inv.peakDefenders,
                    Math.max(0, inv.peakDefenders - defs));
            Assault.end(level, inv);
            data.remove(inv.target);
            com.ale.warbandcraft.village.Popularity.attacked(level, inv.target);   // H21: la gente se asustó
            tell(level, inv.owner, Component.translatable("msg.warbandcraft.invasion.repelled", placeName(level, inv), factionName(inv.faction))
                    .withStyle(ChatFormatting.GREEN));
            return;
        }
        if (defs > 0) {
            if (inv.noDefendersSince != Long.MIN_VALUE) {
                inv.noDefendersSince = Long.MIN_VALUE;
                data.changed();
            }
            return;
        }
        if (inv.noDefendersSince == Long.MIN_VALUE) {
            inv.noDefendersSince = now;
            data.changed();
        }
        // Sin resistencia van al almacén (ver Assault: objective); el primero que llega se queda con la aldea
        if (reachedStorehouse(level, v, left)) {
            int guards = guardsAt(level, inv.owner, inv.target).size();
            WarReports.record(level, inv, WarReports.Outcome.LOST, true, inv.attackers, Math.max(inv.peakDefenders, guards),
                    inv.peakDefenders + guards);
            Assault.end(level, inv);
            lose(level, inv, v, guardsAt(level, inv.owner, inv.target).size());
            for (AbstractRecruitEntity s : left) s.getPersistentData().remove(TAG_INVADER);   // quedan de guarnición
        }
    }

    /** El almacén de la aldea (su Campamento), o el centro si no tiene. */
    public static BlockPos storehouse(ServerLevel level, VillageData.Village v) {
        BlockPos camp = com.ale.warbandcraft.village.VillageCamps.campOf(level, v);
        return camp == null ? v.center() : camp;
    }

    public static boolean reachedStorehouse(ServerLevel level, VillageData.Village v, List<AbstractRecruitEntity> invaders) {
        BlockPos store = storehouse(level, v);
        for (AbstractRecruitEntity s : invaders) {
            if (VillageData.horizontalDistSq(s.blockPosition(), store) <= STOREHOUSE_REACH * STOREHOUSE_REACH
                    && Math.abs(s.getY() - store.getY()) <= 3) return true;
        }
        return false;
    }

    /** H17: la barra de quién va ganando (tus defensores contra el ejército), para el dueño si está cerca. */
    private static void bar(ServerLevel level, InvasionData.Invasion inv, int invaders) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        boolean near = p != null && p.level() == level
                && VillageData.horizontalDistSq(p.blockPosition(), inv.target) <= (double) (REAL_DISTANCE + 32) * (REAL_DISTANCE + 32);
        com.ale.warbandcraft.battle.BattleBar.update(barKey(level, inv),
                new com.ale.warbandcraft.battle.BattleBar.Side(Component.translatable("msg.warbandcraft.bar.defenders"), defenders(level, inv)),
                new com.ale.warbandcraft.battle.BattleBar.Side(factionName(inv.faction), invaders), true, near ? List.of(p) : List.of());
    }

    public static String barKey(ServerLevel level, InvasionData.Invasion inv) {
        return "invasion:" + level.dimension().location() + "@" + inv.target.asLong();
    }

    // ------------------------------------------------------------------ autoresolución

    /** Resultado de una autoresolución (para las pruebas). */
    public record Result(boolean defended, double attack, double defense, int guardsLost) {}

    public static Result autoresolve(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v) {
        RandomSource r = level.getRandom();
        List<BarracksData.Soldier> guards = guardsAt(level, inv.owner, inv.target);
        boolean army = inv.kind == InvasionData.Kind.ARMY;
        double attack = inv.size * INVADER_POWER * (army ? GENERAL_POWER : 1) * (0.8 + 0.4 * r.nextDouble());
        double defense = defense(level, v, guards, army) * (0.8 + 0.4 * r.nextDouble());
        // Los invasores que habían aparecido (pelea real que quedó a medias) se van o se quedan según el resultado
        if (defense >= attack) {
            int lost = (int) Math.round(guards.size() * Math.min(1.0, attack / Math.max(defense, 1e-6)) * 0.5);
            removeGuards(level, inv.owner, guards, lost);
            WarReports.record(level, inv, WarReports.Outcome.WON, inv.state == InvasionData.State.FIGHTING,
                    Math.max(inv.attackers, inv.size), Math.max(inv.peakDefenders, guards.size()), lost);
            InvasionData.get(level).remove(inv.target);
            com.ale.warbandcraft.village.Popularity.attacked(level, inv.target);   // H21
            tell(level, inv.owner, Component.translatable("msg.warbandcraft.invasion.auto_won", placeName(level, inv),
                    factionName(inv.faction), inv.size, lost).withStyle(ChatFormatting.GREEN));
            return new Result(true, attack, defense, lost);
        }
        int lost = guards.size();
        WarReports.record(level, inv, WarReports.Outcome.LOST, inv.state == InvasionData.State.FIGHTING,
                Math.max(inv.attackers, inv.size), Math.max(inv.peakDefenders, lost), lost);
        lose(level, inv, v, lost);
        // H15b: los que quedaron del ejército entran de guarnición (ya o cuando se cargue)
        InvasionData.get(level).setPendingGarrison(inv.target, Math.max(1, (int) Math.round(inv.size * (1 - defense / attack))));
        AiWars.spawnPending(level, inv.target);
        return new Result(false, attack, defense, lost);
    }

    /** Defensa de la aldea: milicia + guardias, por sus defensas (H12). */
    public static double defense(ServerLevel level, VillageData.Village v, List<BarracksData.Soldier> guards) {
        return defense(level, v, guards, false);
    }

    /** Igual; {@code sappers} = el atacante trae zapadores y escaleras: los muros valen menos. */
    public static double defense(ServerLevel level, VillageData.Village v, List<BarracksData.Soldier> guards, boolean sappers) {
        double d = v.castle() ? CASTLE_MILITIA : VILLAGE_MILITIA;
        for (BarracksData.Soldier s : guards) d += GUARD_POWER + GUARD_PER_LEVEL * s.xpLevel;
        double mult = v.castle() ? CASTLE_DEFENSE : 1.0;
        TownData.Town t = TownData.get(level).at(v.center());
        if (t != null) {
            if (t.has(Upgrade.WALL)) mult = Math.max(mult, WALL_DEFENSE);
            else if (t.has(Upgrade.PALISADE)) mult = Math.max(mult, PALISADE_DEFENSE);
            if (t.has(Upgrade.TOWERS)) mult += TOWERS_DEFENSE;
        }
        if (sappers) mult = 1 + (mult - 1) * SAPPER_WALL_FACTOR;
        return d * mult;
    }

    /** La aldea pasa a la facción: sin dueño, su almacén se vacía, tus guardias de ahí caen. */
    private static void lose(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, int guardsLost) {
        removeGuards(level, inv.owner, guardsAt(level, inv.owner, inv.target), Integer.MAX_VALUE);
        com.ale.warbandcraft.village.VillageStorehouse.forget(level, v);   // H16: el barracón de la aldea se pierde
        VillageData.get(level).add(new VillageData.Village(v.center(), inv.faction, v.foundedTick(), null, 0L, v.castle()));
        InvasionData.get(level).remove(inv.target);
        tell(level, inv.owner, Component.translatable("msg.warbandcraft.invasion.lost", placeName(level, inv), factionName(inv.faction), guardsLost)
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
    }

    /** Tus guardias de esa aldea que están guardados (fichas con puesto cerca del centro), los de menor nivel primero. */
    public static List<BarracksData.Soldier> guardsAt(ServerLevel level, UUID owner, BlockPos center) {
        List<BarracksData.Soldier> out = new ArrayList<>();
        BarracksData data = BarracksData.get(level);
        double r2 = (double) GUARD_RADIUS * GUARD_RADIUS;
        BlockPos own = com.ale.warbandcraft.village.VillageCamps.campFor(level, center);   // H16: su barracón cuenta entero
        for (BlockPos camp : CampRegistry.get(level).campsOf(owner)) {
            BarracksData.Barracks b = data.at(camp);
            if (b == null) continue;
            if (camp.equals(own) && !own.equals(center)) out.addAll(b.quarters);   // el cuartel guardado también defiende
            for (BarracksData.Soldier s : b.soldiers) {
                if (camp.equals(own) && !own.equals(center)) {
                    out.add(s);
                    continue;
                }
                if (!s.guard || s.post == null) continue;
                double dx = s.post.x - (center.getX() + 0.5), dz = s.post.z - (center.getZ() + 0.5);
                if (dx * dx + dz * dz <= r2) out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(s -> s.xpLevel));
        return out;
    }

    private static void removeGuards(ServerLevel level, UUID owner, List<BarracksData.Soldier> guards, int n) {
        if (n <= 0 || guards.isEmpty()) return;
        BarracksData data = BarracksData.get(level);
        int removed = 0;
        for (BarracksData.Soldier s : guards) {
            if (removed >= n) break;
            for (BlockPos camp : CampRegistry.get(level).campsOf(owner)) {
                BarracksData.Barracks b = data.at(camp);
                if (b != null && (b.soldiers.remove(s) || b.quarters.remove(s))) {
                    removed++;
                    break;
                }
            }
        }
        if (removed > 0) data.changed();
    }

    // ------------------------------------------------------------------ robo de recursos

    /** Se lleva el almacén de una aldea/castillo de facción (al saquearla o conquistarla). Devuelve los ítems. */
    public static List<ItemStack> stealStock(ServerLevel level, VillageData.Village v) {
        VillageData data = VillageData.get(level);
        VillageStock.Stock s = data.stock(v.center());
        if (s.isEmpty()) return List.of();
        data.setStock(v.center(), VillageStock.Stock.EMPTY);
        return s.items();
    }

    // ------------------------------------------------------------------ invasores sueltos

    /**
     * Un invasor que se carga del disco: si su invasión sigue peleando, se queda; si la aldea ya es de su facción,
     * queda de guarnición; si no (la invasión terminó sin él), sobra.
     */
    public static boolean keepInvader(ServerLevel level, Entity e) {
        if (!e.getPersistentData().contains(TAG_INVADER)) return true;
        BlockPos target = BlockPos.of(e.getPersistentData().getLong(TAG_INVADER));
        InvasionData.Invasion inv = InvasionData.get(level).at(target);
        if (inv != null && inv.state == InvasionData.State.FIGHTING) return true;
        VillageData.Village v = VillageData.get(level).at(target);
        String faction = e.getPersistentData().getString(VillageManager.TAG_FACTION);
        if (v != null && !v.isConquered() && v.faction().toString().equals(faction)) {
            e.getPersistentData().remove(TAG_INVADER);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ textos

    public static Component factionName(ResourceLocation id) {
        Faction f = FactionManager.INSTANCE.get(id);
        return f == null ? Component.literal(id.getPath()) : Component.literal(f.name()).withStyle(f.color());
    }

    public static Component placeName(ServerLevel level, InvasionData.Invasion inv) {
        VillageData.Village v = VillageData.get(level).at(inv.target);
        String key = v != null && v.castle() ? "msg.warbandcraft.invasion.castle" : "msg.warbandcraft.invasion.village";
        return Component.translatable(key, inv.target.getX(), inv.target.getZ());
    }

    public static String time(long ticks) {
        long s = Math.max(0, ticks / 20);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static void tell(ServerLevel level, UUID owner, Component msg) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) p.sendSystemMessage(msg);
    }
}
