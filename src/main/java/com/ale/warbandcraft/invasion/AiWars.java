package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.battle.SiegeDefenders;
import com.ale.warbandcraft.battle.SiegeLanes;
import com.ale.warbandcraft.battle.SiegePush;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guerras entre facciones de la IA (H15b): se declaran y se terminan solas, mandan ejércitos (que se ven en el mapa)
 * y se toman aldeas entre ellas. Al llegar se autoresuelve con números, salvo que haya un jugador cerca: ahí
 * la batalla se pelea de verdad y el jugador se puede meter (pegarle a un bando baja la opinión de ese bando; si gana
 * el bando que ayudaste, sube la de ese). Los números están acá arriba.
 */
public final class AiWars {
    /** Chance por día de que dos facciones vecinas se declaren la guerra, y cuánto dura (días). */
    public static double WAR_CHANCE = 0.08;
    public static int WAR_MIN_DAYS = 4, WAR_MAX_DAYS = 8;
    /** Vecinas: sus asentamientos más cercanos a esta distancia. */
    public static int NEIGHBOR_DISTANCE = 800;
    /** Chance por día de que una facción en guerra mande un ejército. */
    public static double MARCH_CHANCE = 0.4;
    /** Velocidad de marcha (bloques por segundo) y duración mínima / máxima. */
    public static double MARCH_SPEED = 2.0;
    public static int MIN_MARCH_TICKS = 20 * 120, MAX_MARCH_TICKS = 20 * 600;
    /** Defensa de una aldea de facción: su guarnición promedio (castillo: la del castillo) por sus defensas. */
    public static double VILLAGE_GARRISON_POWER = 1.0;
    /** Batalla real: un jugador a esta distancia de la aldea cuando llega el ejército. */
    public static int BATTLE_DISTANCE = 100;
    /** A quiénes se avisa que empezó una batalla cerca. */
    public static int BATTLE_NEWS_RANGE = 160;
    /**
     * Meterse (H23): opinión que se pierde con el bando contra el que peleás (una vez por batalla) y la que se gana con
     * el tuyo si gana.
     */
    public static int JOIN_OPINION = -15, HELP_OPINION = 10;
    /** Aviso con tiempo: cuánto antes de llegar se avisa, y a los jugadores a qué distancia de la aldea. */
    public static int WARN_TICKS = 20 * 120, WARN_RANGE = 400;
    /** Al meterte, tus soldados a esta distancia se meten con vos; buscan enemigos hasta esta otra. */
    public static int JOIN_RADIUS = 64;
    public static double HELPER_REACH = 96;
    /**
     * Botín si gana tu bando, además del equipo de los caídos ({@link com.ale.warbandcraft.battle.BattleLoot}): esmeraldas
     * de premio; si toman la aldea, esta parte de su almacén.
     */
    public static int LOOT_BASE = 4;
    public static double LOOT_STOCK_SHARE = 0.5;
    /** Marca de los atacantes de una batalla entre facciones (centro de la aldea que atacan). */
    public static final String TAG_AI_ATTACK = "warbandcraft:ai_attack";
    /** H23: en tus soldados que se metieron: la marcha (UUID) y la orden que tenían antes (para devolvérsela). */
    public static final String TAG_JOIN = "warbandcraft:ai_join", TAG_JOIN_STATE = "warbandcraft:ai_join_state";
    /** A quiénes ya se les ofreció meterse en cada batalla (no se guarda: al volver a entrar, se ofrece de nuevo). */
    private static final java.util.Map<UUID, java.util.Set<UUID>> ASKED = new java.util.HashMap<>();

    private AiWars() {}

    // ------------------------------------------------------------------ cada día

    public static void onNewDay(ServerLevel level) {
        InvasionData data = InvasionData.get(level);
        RandomSource r = level.getRandom();
        long day = level.getDayTime() / 24000L;
        List<Faction> factions = new ArrayList<>();
        for (Faction f : FactionManager.INSTANCE.ordered()) if (!Invasions.settlements(level, f.id()).isEmpty()) factions.add(f);
        for (int i = 0; i < factions.size(); i++) {
            for (int j = i + 1; j < factions.size(); j++) {
                Faction a = factions.get(i), b = factions.get(j);
                long until = data.warUntil(a.id(), b.id());
                if (until >= 0 && day >= until) {
                    data.setWar(a.id(), b.id(), -1);
                    announce(level, Component.translatable("msg.warbandcraft.aiwar.peace", name(a), name(b)).withStyle(ChatFormatting.GRAY));
                } else if (until < 0 && neighbors(level, a.id(), b.id()) && r.nextDouble() < WAR_CHANCE) {
                    data.setWar(a.id(), b.id(), day + WAR_MIN_DAYS + r.nextInt(WAR_MAX_DAYS - WAR_MIN_DAYS + 1));
                    announce(level, Component.translatable("msg.warbandcraft.aiwar.declared", name(a), name(b)).withStyle(ChatFormatting.GOLD));
                }
                if (data.warUntil(a.id(), b.id()) >= 0) {
                    boolean aFirst = r.nextBoolean();
                    Faction att = aFirst ? a : b, def = aFirst ? b : a;
                    if (r.nextDouble() < MARCH_CHANCE) launch(level, att.id(), def.id(), -1);
                }
            }
        }
    }

    public static boolean atWar(ServerLevel level, ResourceLocation a, ResourceLocation b) {
        return InvasionData.get(level).warUntil(a, b) >= 0;
    }

    private static boolean neighbors(ServerLevel level, ResourceLocation a, ResourceLocation b) {
        double max = (double) NEIGHBOR_DISTANCE * NEIGHBOR_DISTANCE;
        for (VillageData.Village x : Invasions.settlements(level, a)) {
            for (VillageData.Village y : Invasions.settlements(level, b)) {
                if (VillageData.horizontalDistSq(x.center(), y.center()) <= max) return true;
            }
        }
        return false;
    }

    /**
     * Manda un ejército de {@code attacker} contra el asentamiento de {@code defender} más cercano a los suyos.
     * {@code size} -1 = lo que dé la leva. Devuelve la marcha, o null si no hay con qué.
     */
    @Nullable
    public static InvasionData.March launch(ServerLevel level, ResourceLocation attacker, ResourceLocation defender, int size) {
        InvasionData data = InvasionData.get(level);
        List<VillageData.Village> home = Invasions.settlements(level, attacker);
        List<VillageData.Village> enemy = Invasions.settlements(level, defender);
        if (home.isEmpty() || enemy.isEmpty()) return null;
        VillageData.Village from = null, target = null;
        double best = Double.MAX_VALUE;
        for (VillageData.Village h : home) {
            for (VillageData.Village e : enemy) {
                double d = VillageData.horizontalDistSq(h.center(), e.center());
                if (d < best && !marching(data, e.center())) {
                    best = d;
                    from = h;
                    target = e;
                }
            }
        }
        if (target == null) return null;
        if (size < 0) {
            size = Math.min(Invasions.MAX_ARMY, data.levy(attacker));
            if (size < Invasions.MIN_ARMY) return null;
            data.setLevy(attacker, data.levy(attacker) - size);
        }
        long now = level.getGameTime();
        int ticks = (int) Math.max(MIN_MARCH_TICKS, Math.min(MAX_MARCH_TICKS, Math.sqrt(best) / MARCH_SPEED * 20));
        InvasionData.March m = new InvasionData.March(UUID.randomUUID(), attacker, target.center(), from.center(), size, now, now + ticks);
        data.putMarch(m);
        Faction a = FactionManager.INSTANCE.get(attacker), d = FactionManager.INSTANCE.get(defender);
        announce(level, Component.translatable("msg.warbandcraft.aiwar.march", name(a), size, name(d),
                target.center().getX(), target.center().getZ()).withStyle(ChatFormatting.GOLD));
        return m;
    }

    private static boolean marching(InvasionData data, BlockPos target) {
        for (InvasionData.March m : data.marches()) if (m.target.equals(target)) return true;
        return false;
    }

    // ------------------------------------------------------------------ cada segundo

    public static void tick(ServerLevel level) {
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        for (InvasionData.March m : new ArrayList<>(data.marches())) {
            if (m.fighting) {
                battleTick(level, m);
                continue;
            }
            if (!m.warned && m.arriveTick - now <= WARN_TICKS) warnSoon(level, m, now);
            if (now < m.arriveTick) continue;
            if (RaidManager.get(level, m.target) != null) continue;   // un jugador la está atacando: esperan
            if (playerNear(level, m.target) && level.isPositionEntityTicking(m.target)) startBattle(level, m);
            else resolve(level, m);
        }
    }

    /** Resultado de una batalla entre facciones (para las pruebas). */
    public record Result(boolean taken, double attack, double defense) {}

    /**
     * El ejército llega: se autoresuelve contra la guarnición (en números) y sus defensas. Si era una batalla real que
     * quedó a medias (la aldea se descargó), cuenta lo que quedaba de cada lado.
     */
    public static Result resolve(ServerLevel level, InvasionData.March m) {
        InvasionData data = InvasionData.get(level);
        if (m.fighting) endSiege(level, m);
        data.removeMarch(m.id);
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (v == null || v.isConquered() || v.faction().equals(m.attacker)) {   // ya no hay a quién atacar: vuelven
            data.setLevy(m.attacker, data.levy(m.attacker) + m.size);
            releaseHelpers(level, m);
            return new Result(false, 0, 0);
        }
        RandomSource r = level.getRandom();
        double attack = m.size * Invasions.INVADER_POWER * com.ale.warbandcraft.battle.AutoPower.faction(m.attacker, false)
                * (0.8 + 0.4 * r.nextDouble());
        double defense = (m.fighting ? defense(level, v, m.defenders) : defense(level, v)) * (0.8 + 0.4 * r.nextDouble());
        Faction att = FactionManager.INSTANCE.get(m.attacker), def = FactionManager.INSTANCE.get(v.faction());
        if (attack > defense) {
            finish(level, m, v, true);
            take(level, v, m.attacker, Math.max(1, (int) Math.round(m.size * (1 - defense / attack))));
            announce(level, Component.translatable("msg.warbandcraft.aiwar.taken", name(att), name(def),
                    m.target.getX(), m.target.getZ()).withStyle(ChatFormatting.GOLD));
            return new Result(true, attack, defense);
        }
        announce(level, Component.translatable("msg.warbandcraft.aiwar.repelled", name(def), name(att),
                m.target.getX(), m.target.getZ()).withStyle(ChatFormatting.GRAY));
        finish(level, m, v, false);
        return new Result(false, attack, defense);
    }

    /** Defensa de una aldea/castillo de facción: su guarnición promedio por sus defensas (H12). */
    public static double defense(ServerLevel level, VillageData.Village v) {
        Faction f = FactionManager.INSTANCE.get(v.faction());
        return defense(level, v, v.castle() ? (CastleManager.GARRISON_MIN + CastleManager.GARRISON_MAX) / 2.0
                : f == null ? 5 : (f.garrisonMin() + f.garrisonMax()) / 2.0);
    }

    /** Defensa con {@code garrison} soldados. */
    public static double defense(ServerLevel level, VillageData.Village v, double garrison) {
        garrison += com.ale.warbandcraft.village.GarrisonReserve.remaining(level, v);   // la reserva de los pueblos grandes
        double mult = v.castle() ? Invasions.CASTLE_DEFENSE : 1.0;
        TownData.Town t = TownData.get(level).at(v.center());
        if (t != null) {
            if (t.has(Upgrade.WALL)) mult = Math.max(mult, Invasions.WALL_DEFENSE);
            else if (t.has(Upgrade.PALISADE)) mult = Math.max(mult, Invasions.PALISADE_DEFENSE);
            if (t.has(Upgrade.TOWERS)) mult += Invasions.TOWERS_DEFENSE;
        }
        return garrison * VILLAGE_GARRISON_POWER * com.ale.warbandcraft.battle.AutoPower.faction(v.faction(), true) * mult;
    }

    /**
     * La aldea pasa a {@code faction} (sin dueño). La guarnición vieja que esté cargada se va; los que quedaron del
     * ejército entran de guarnición (ya, si está cargada; si no, cuando se cargue: {@link #spawnPending}).
     */
    public static void take(ServerLevel level, VillageData.Village v, ResourceLocation faction, int survivors) {
        VillageData.get(level).add(new VillageData.Village(v.center(), faction, v.foundedTick(), null, 0L, v.castle()));
        if (level.isPositionEntityTicking(v.center())) {
            for (AbstractRecruitEntity s : VillageManager.garrison(level, v.center())) s.discard();
        }
        InvasionData.get(level).setPendingGarrison(v.center(), survivors);
        spawnPending(level, v.center());
    }

    /** Hace aparecer la guarnición que espera en una aldea recién tomada, si está cargada. */
    public static void spawnPending(ServerLevel level, BlockPos center) {
        InvasionData data = InvasionData.get(level);
        int n = data.pendingGarrison(center);
        if (n <= 0 || !level.isPositionEntityTicking(center)) return;
        VillageData.Village v = VillageData.get(level).at(center);
        Faction f = v == null ? null : FactionManager.INSTANCE.get(v.faction());
        if (f != null && !v.isConquered()) {
            int radius = v.castle() ? CastleManager.PATROL_RADIUS : VillageManager.PATROL_RADIUS;
            for (int i = 0; i < n; i++) {
                var troop = f.pickTroop(level.getRandom());
                if (troop != null) VillageManager.spawnTroop(level, center, f, troop, level.getRandom(), 10, radius);
            }
        }
        data.setPendingGarrison(center, 0);
    }

    // ------------------------------------------------------------------ batalla real

    private static boolean playerNear(ServerLevel level, BlockPos target) {
        double max = (double) BATTLE_DISTANCE * BATTLE_DISTANCE;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.isAlive() && VillageData.horizontalDistSq(p.blockPosition(), target) <= max) return true;
        }
        return false;
    }

    /** El ejército aparece afuera (de la aldea o de sus muros), del lado de sus tierras, y la ataca. */
    public static List<AbstractRecruitEntity> startBattle(ServerLevel level, InvasionData.March m) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        Faction f = FactionManager.INSTANCE.get(m.attacker);
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (f == null || v == null || v.isConquered() || v.faction().equals(m.attacker)) {
            resolve(level, m);
            return out;
        }
        // H17: aparecen afuera (de la aldea, o de los muros), en lados a los que se llega por tierra; con muros, de a pie.
        // Pedido de Ale: del lado más débil (hueco, pocos defensores), no siempre del mismo lado.
        Breach.Walls w = Breach.walls(level, v);
        double a = Math.atan2(m.from.getZ() - m.target.getZ(), m.from.getX() - m.target.getX());
        SiegeLanes.State st = new SiegeLanes.State();
        int lanes = m.size >= SIEGE_TWO_LANES ? 2 : 1;
        for (BlockPos stage : com.ale.warbandcraft.battle.SiegePush.stages(level, m.target, w, a, lanes,
                defenders(level, m.target, v.faction()), level.getRandom())) {
            st.add(SiegeLanes.angleOf(m.target, Vec3.atBottomCenterOf(stage)), stage);
        }
        SIEGES.put(m.id, st);
        RandomSource random = level.getRandom();
        for (int i = 0; i < m.size; i++) {
            var troop = f.pickTroop(random);
            if (troop == null) break;
            SiegeLanes.Lane lane = st.lane(i % lanes);
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, lane.stage, f, troop, random, 4);
            if (s == null) continue;
            s.getPersistentData().putLong(VillageManager.TAG_VILLAGE, m.target.asLong());
            s.getPersistentData().putLong(TAG_AI_ATTACK, m.target.asLong());
            s.getPersistentData().putInt(Assault.TAG_GROUP, lane.index);
            s.restrictTo(m.target, w.radius() + SiegeLanes.AREA_MARGIN);
            SiegeLanes.dismount(s, false);
            out.add(s);
        }
        m.fighting = true;
        m.size = out.size();
        m.defenders = defenders(level, m.target, v.faction()).size();
        m.startAttackers = m.size;
        m.startDefenders = m.defenders;
        InvasionData.get(level).changed();
        Component msg = Component.translatable("msg.warbandcraft.aiwar.battle", name(f), name(FactionManager.INSTANCE.get(v.faction())))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        double news = (double) BATTLE_NEWS_RANGE * BATTLE_NEWS_RANGE;
        for (ServerPlayer p : level.players()) {
            if (VillageData.horizontalDistSq(p.blockPosition(), m.target) > news) continue;
            p.sendSystemMessage(msg);
            offer(level, m, p, false);   // H23: la pantalla para meterse
        }
        return out;
    }

    /** Los atacantes de la batalla contra {@code target} que siguen en pie (cargados). */
    public static List<AbstractRecruitEntity> attackers(ServerLevel level, BlockPos target) {
        long key = target.asLong();
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(target).inflate(96, 64, 96),
                e -> e.isAlive() && e.getPersistentData().contains(TAG_AI_ATTACK) && e.getPersistentData().getLong(TAG_AI_ATTACK) == key);
    }

    /** Los defensores: la guarnición de la aldea que es de su facción (sin atacantes, invasores ni patrullas). */
    public static List<AbstractRecruitEntity> defenders(ServerLevel level, BlockPos target, ResourceLocation faction) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        String f = faction.toString();
        for (AbstractRecruitEntity s : VillageManager.garrison(level, target)) {
            var pd = s.getPersistentData();
            if (pd.contains(TAG_AI_ATTACK) || pd.contains(Invasions.TAG_INVADER) || Patrols.isPatrol(s)) continue;
            if (VillageManager.isGarrison(s) && f.equals(pd.getString(VillageManager.TAG_FACTION))) out.add(s);
        }
        return out;
    }

    /** Cada segundo, mientras se pelea: sin atacantes, la aldea aguantó; sin defensores, cae. Descargada: números. */
    public static void battleTick(ServerLevel level, InvasionData.March m) {
        InvasionData data = InvasionData.get(level);
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (v == null || v.isConquered() || v.faction().equals(m.attacker)) {   // ya no hay a quién atacar
            endSiege(level, m);
            for (AbstractRecruitEntity s : attackers(level, m.target)) s.discard();
            data.removeMarch(m.id);
            releaseHelpers(level, m);
            return;
        }
        if (!level.isPositionEntityTicking(m.target)) {
            resolve(level, m);
            return;
        }
        List<AbstractRecruitEntity> att = attackers(level, m.target);
        List<AbstractRecruitEntity> def = defenders(level, m.target, v.faction());
        if (att.size() != m.size || def.size() != m.defenders) {
            m.size = att.size();
            m.defenders = def.size();
            data.changed();
        }
        if (!att.isEmpty() && !def.isEmpty()) {
            siegeTick(level, m, v, att, def);
            helpersTick(level, m, att, def);   // H23: los tuyos que se metieron
        }
        // H17: la barra de quién va ganando, para los que están cerca
        List<ServerPlayer> near = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (VillageData.horizontalDistSq(p.blockPosition(), m.target) > (double) BATTLE_NEWS_RANGE * BATTLE_NEWS_RANGE) continue;
            near.add(p);
            if (!ASKED.getOrDefault(m.id, java.util.Set.of()).contains(p.getUUID())) offer(level, m, p, false);   // H23: llegó tarde
        }
        com.ale.warbandcraft.battle.BattleBar.update("march:" + m.id,
                new com.ale.warbandcraft.battle.BattleBar.Side(name(FactionManager.INSTANCE.get(m.attacker)), att.size()),
                new com.ale.warbandcraft.battle.BattleBar.Side(name(FactionManager.INSTANCE.get(v.faction())),
                        def.size() + com.ale.warbandcraft.village.GarrisonReserve.remaining(level, v)), false, near);
        Faction a = FactionManager.INSTANCE.get(m.attacker), d = FactionManager.INSTANCE.get(v.faction());
        if (att.isEmpty()) {
            endSiege(level, m);
            data.removeMarch(m.id);
            announce(level, Component.translatable("msg.warbandcraft.aiwar.repelled", name(d), name(a),
                    m.target.getX(), m.target.getZ()).withStyle(ChatFormatting.GRAY));
            finish(level, m, v, false);
        } else if (def.isEmpty() && com.ale.warbandcraft.village.GarrisonReserve.remaining(level, v) == 0) {
            endSiege(level, m);
            data.removeMarch(m.id);
            finish(level, m, v, true);
            VillageData.get(level).add(new VillageData.Village(v.center(), m.attacker, v.foundedTick(), null, 0L, v.castle()));
            int radius = v.castle() ? CastleManager.PATROL_RADIUS : VillageManager.PATROL_RADIUS;
            for (AbstractRecruitEntity s : att) {   // los que quedaron, de guarnición
                s.getPersistentData().remove(TAG_AI_ATTACK);
                if (v.castle()) s.getPersistentData().putBoolean(CastleManager.TAG_CASTLE, true);
                s.restrictTo(v.center(), radius);
            }
            announce(level, Component.translatable("msg.warbandcraft.aiwar.taken", name(a), name(d),
                    m.target.getX(), m.target.getZ()).withStyle(ChatFormatting.GOLD));
        }
    }

    // ------------------------------------------------------------------ asedio (H17)

    /** Con tantos soldados, el asedio va por dos carriles (separados tantos grados). */
    public static int SIEGE_TWO_LANES = 10;
    public static double SIEGE_SPREAD_DEG = 50;

    /** Los carriles de cada batalla en curso (por marcha; no se guarda: se rearma con las marcas). */
    private static final java.util.Map<UUID, SiegeLanes.State> SIEGES = new java.util.HashMap<>();

    /**
     * Batalla contra un castillo (o una aldea con muralla): los atacantes asedian por carriles (escaleras, zapadores,
     * cañones) y la guarnición defiende por carril (arqueros arriba, tumbar escaleras); el capitán, en el centro. Sin
     * muros, pelean sueltos como siempre.
     */
    private static void siegeTick(ServerLevel level, InvasionData.March m, VillageData.Village v, List<AbstractRecruitEntity> att,
                                  List<AbstractRecruitEntity> def) {
        Breach.Walls w = Breach.walls(level, v);
        SiegeLanes.State st = SIEGES.computeIfAbsent(m.id, k -> rebuild(level, m, att, w));
        long now = level.getGameTime();
        List<LivingEntity> defenders = new ArrayList<>(def), attackers = new ArrayList<>(att);
        for (AbstractRecruitEntity s : helpers(level, m)) {   // H23: los tuyos que se metieron
            if (m.helpedAttack.contains(s.getOwnerUUID())) attackers.add(s);
            else if (m.helpedDefense.contains(s.getOwnerUUID())) defenders.add(s);
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || VillageData.horizontalDistSq(p.blockPosition(), m.target) > 128 * 128) continue;
            if (m.helpedAttack.contains(p.getUUID())) attackers.add(p);
            else if (m.helpedDefense.contains(p.getUUID())) defenders.add(p);
        }
        // Pedido de Ale: que nunca se queden parados. Sin daño un rato, todos a buscarse; mucho más, se decide con lo que hay.
        SiegePush.Stage stage = SiegePush.watch("march:" + m.id, now, att, def);
        if (stage == SiegePush.Stage.GIVE_UP) {
            boolean attackersWin = SiegePush.aWins(att, def);
            com.ale.warbandcraft.WarbandCraft.LOGGER.info("Batalla entre facciones en {}: trabada, se decide con lo que queda ({} contra {}): gana {}",
                    m.target.toShortString(), att.size(), def.size(), attackersWin ? "el que ataca" : "el que defiende");
            for (AbstractRecruitEntity s : attackersWin ? def : att) s.discard();   // se rinden y se van: el próximo segundo termina
            return;
        }
        if (stage == SiegePush.Stage.PUSH) {
            for (AbstractRecruitEntity s : att) SiegeLanes.dismount(s, false);
            SiegePush.huntAll(level, att, defenders, m.target, com.ale.warbandcraft.nav.NavCaps.INVADER);
            SiegePush.huntAll(level, def, attackers, m.target, com.ale.warbandcraft.nav.NavCaps.SOLDIER);
            return;
        }
        SiegeLanes.Host host = host(level, m);
        for (SiegeLanes.Lane lane : st.lanes) {
            List<AbstractRecruitEntity> members = new ArrayList<>();
            for (AbstractRecruitEntity s : att) {
                SiegeLanes.dismount(s, false);
                if (st.lane(s.getPersistentData().getInt(Assault.TAG_GROUP)) == lane) members.add(s);
            }
            SiegeLanes.tick(level, host, st, lane, members, defenders, w, true, now);
        }
        List<AbstractRecruitEntity> foot = new ArrayList<>();
        for (AbstractRecruitEntity s : def) {
            if (s.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)) continue;   // el capitán, en lo suyo
            SiegeLanes.dismount(s, true);
            foot.add(s);
        }
        if (!w.walled()) {   // sin muros: defienden la aldea (los que entraron primero, después los de cerca)
            defendOpen(level, foot, attackers, m.target, w);
            return;
        }
        SiegeDefenders.tick(level, "march:" + m.id, new SiegeDefenders.Host() {
            @Override
            public BlockPos center() {
                return m.target;
            }

            @Override
            public @Nullable UUID listener() {
                return null;
            }

            @Override
            public void ladderPushed(BlockPos base) {
                m.ladders.remove(base.asLong());
                InvasionData.get(level).changed();
            }
        }, foot, attackers, SiegeLanes.info(st), w, com.ale.warbandcraft.nav.NavCaps.SOLDIER);
    }

    /** Defensores: un atacante a tanto del borde de la aldea, salen a pelear; los arqueros tiran a tanto. */
    public static double OPEN_SALLY = 10, OPEN_ARCHER_REACH = 40;

    /**
     * Aldea sin muros: cada defensor pelea con el atacante que entró (o que tiene cerca); si no hay, espera en medio anillo
     * alrededor del centro, del lado de donde vienen (los arqueros más adentro, tirando desde ahí).
     */
    public static void defendOpen(ServerLevel level, List<AbstractRecruitEntity> def, List<LivingEntity> attackers, BlockPos center,
                                  Breach.Walls w) {
        if (attackers.isEmpty()) return;
        Vec3 c = Vec3.atBottomCenterOf(center);
        Vec3 enemyCenter = com.ale.warbandcraft.battle.BattleGeneral.centroid(attackers);
        Vec3 dir = com.ale.warbandcraft.battle.BattleGeneral.horizontalDir(c, enemyCenter);
        double ring = Math.max(4, w.radius() * 0.45);
        for (AbstractRecruitEntity s : def) {
            if (com.ale.warbandcraft.nav.Mover.moving(s)) com.ale.warbandcraft.nav.Mover.stop(s);
            s.setShouldBlock(false);
            boolean ranged = com.ale.warbandcraft.battle.BattleGeneral.roleOf(s) == com.ale.warbandcraft.battle.BattleGeneral.Role.RANGED;
            LivingEntity near = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity a : attackers) {
                if (!a.isAlive()) continue;
                double d = s.distanceTo(a);
                boolean in = SiegeLanes.dist(a, center) <= w.radius() + OPEN_SALLY;
                if ((in || d <= SiegePush.NEAR_ENEMY) && d < best) {
                    best = d;
                    near = a;
                }
            }
            if (near != null && (!ranged || best <= 6)) {
                if (s.getTarget() != near) s.setTarget(near);
                if (s.getFollowState() != 0) s.setFollowState(0);
                continue;
            }
            int h = Math.floorMod(s.getUUID().hashCode(), 1000);
            double spread = (h / 1000.0 - 0.5) * Math.PI * 0.9;
            double cos = Math.cos(spread), sin = Math.sin(spread);
            Vec3 d = new Vec3(dir.x * cos - dir.z * sin, 0, dir.x * sin + dir.z * cos);
            Vec3 post = Breach.dryNear(level, Breach.ground(level, c.add(d.scale(ranged ? ring * 0.6 : ring)), center.getY()));
            SiegeLanes.moveTo(s, post);
            if (ranged) {
                LivingEntity t = near != null ? near : SiegeLanes.nearest(s, attackers);
                if (t != null && s.distanceTo(t) <= OPEN_ARCHER_REACH && s.canAttack(t)) s.setTarget(t);
            }
        }
    }

    private static String aiKey(InvasionData.March m, boolean attackers) {
        return "march:" + m.id + (attackers ? ":a" : ":d");
    }

    private static SiegeLanes.State rebuild(ServerLevel level, InvasionData.March m, List<AbstractRecruitEntity> att, Breach.Walls w) {
        SiegeLanes.State st = new SiegeLanes.State();
        int max = 0;
        for (AbstractRecruitEntity s : att) max = Math.max(max, s.getPersistentData().getInt(Assault.TAG_GROUP));
        for (int g = 0; g <= Math.min(1, max); g++) {
            List<AbstractRecruitEntity> list = new ArrayList<>();
            for (AbstractRecruitEntity s : att) if (s.getPersistentData().getInt(Assault.TAG_GROUP) == g) list.add(s);
            double a = list.isEmpty() ? Math.atan2(m.from.getZ() - m.target.getZ(), m.from.getX() - m.target.getX())
                    : SiegeLanes.angleOf(m.target, com.ale.warbandcraft.battle.BattleGeneral.centroid(list));
            BlockPos stage = BlockPos.containing(Breach.dryNear(level, Breach.ground(level, Breach.at(m.target, a, w.radius() + SiegeLanes.STAGE_MARGIN),
                    m.target.getY())));
            st.add(a, stage);
        }
        return st;
    }

    private static SiegeLanes.Host host(ServerLevel level, InvasionData.March m) {
        return new SiegeLanes.Host() {
            @Override
            public BlockPos center() {
                return m.target;
            }

            @Override
            public List<Long> ladders() {
                return m.ladders;
            }

            @Override
            public void changed() {
                InvasionData.get(level).changed();
            }

            @Override
            public boolean cannons() {
                return true;
            }

            @Override
            public void announce(SiegeLanes.Event e) {}

            @Override
            public String name() {
                return "batalla entre facciones en " + m.target.toShortString();
            }

            @Override
            public boolean villagers() {
                return true;
            }
        };
    }

    /** Terminó la batalla: escaleras y cañones afuera, defensores sueltos. */
    private static void endSiege(ServerLevel level, InvasionData.March m) {
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(m, true));
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(m, false));
        com.ale.warbandcraft.battle.BattleBar.close("march:" + m.id);
        SiegePush.forget("march:" + m.id);
        SiegeLanes.State st = SIEGES.remove(m.id);
        SiegeLanes.end(level, host(level, m), st);
        com.ale.warbandcraft.battle.SiegeDefenders.end("march:" + m.id, List.of());
    }

    public static void clearSieges() {
        SIEGES.clear();
        ASKED.clear();
    }

    /**
     * Un jugador (o su tropa) le pegó a un soldado de una batalla entre facciones. Si todavía no había elegido bando, es
     * como elegir el otro ({@link #join}). Devuelve false si no había batalla (entonces es un ataque común a la aldea).
     */
    public static boolean onHit(ServerLevel level, AbstractRecruitEntity s, UUID player, LivingEntity source) {
        var pd = s.getPersistentData();
        if (!pd.contains(VillageManager.TAG_VILLAGE)) return false;
        InvasionData data = InvasionData.get(level);
        InvasionData.March m = data.battleAt(BlockPos.of(pd.getLong(VillageManager.TAG_VILLAGE)));
        if (m == null) return false;
        boolean attacker = pd.contains(TAG_AI_ATTACK);
        if (!m.helpedAttack.contains(player) && !m.helpedDefense.contains(player)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
            if (p != null) join(level, p, m, !attacker);
            else if ((attacker ? m.helpedDefense : m.helpedAttack).add(player)) {
                ResourceLocation f = ResourceLocation.tryParse(pd.getString(VillageManager.TAG_FACTION));
                if (f != null) Relations.get(level.getServer()).add(player, f, JOIN_OPINION);
                data.changed();
            }
        }
        if (s.getTarget() == null || !s.getTarget().isAlive()) s.setTarget(source);
        return true;
    }

    // ------------------------------------------------------------------ meterse (H23)

    /** Bando de un soldado en una batalla entre facciones: la marcha y si es de los que atacan. */
    private record Side(InvasionData.March march, boolean attacker) {}

    @Nullable
    private static Side sideOf(ServerLevel level, net.minecraft.world.entity.Entity e) {
        if (!(e instanceof AbstractRecruitEntity r) || !VillageManager.isGarrison(r)) return null;
        var pd = r.getPersistentData();
        InvasionData data = InvasionData.get(level);
        if (pd.contains(TAG_AI_ATTACK)) {
            InvasionData.March m = data.battleAt(BlockPos.of(pd.getLong(TAG_AI_ATTACK)));
            return m == null ? null : new Side(m, true);
        }
        if (!pd.contains(VillageManager.TAG_VILLAGE)) return null;
        InvasionData.March m = data.battleAt(BlockPos.of(pd.getLong(VillageManager.TAG_VILLAGE)));
        if (m == null || pd.getString(VillageManager.TAG_FACTION).equals(m.attacker.toString())) return null;
        return new Side(m, false);
    }

    /** ¿{@code e} pelea en una batalla entre facciones del bando contra el que se metió {@code player}? */
    public static boolean isFoe(ServerLevel level, UUID player, net.minecraft.world.entity.Entity e) {
        Side s = sideOf(level, e);
        if (s == null) return false;
        return s.attacker() ? s.march().helpedDefense.contains(player) : s.march().helpedAttack.contains(player);
    }

    /** Aviso con tiempo: a los jugadores cerca de la aldea, cuánto falta y que para meterse hay que estar ahí. */
    private static void warnSoon(ServerLevel level, InvasionData.March m, long now) {
        m.warned = true;
        InvasionData.get(level).changed();
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (v == null || v.isConquered()) return;
        Component msg = Component.translatable("msg.warbandcraft.aiwar.soon", name(FactionManager.INSTANCE.get(m.attacker)),
                name(FactionManager.INSTANCE.get(v.faction())), m.target.getX(), m.target.getZ(),
                Invasions.time(Math.max(0, m.arriveTick - now)), BATTLE_DISTANCE).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) {
            if (VillageData.horizontalDistSq(p.blockPosition(), m.target) <= (double) WARN_RANGE * WARN_RANGE) {
                p.sendSystemMessage(msg);
                p.playNotifySound(net.minecraft.sounds.SoundEvents.RAID_HORN.value(), net.minecraft.sounds.SoundSource.HOSTILE, 0.4F, 1.3F);
            }
        }
    }

    /** La pantalla: Ayudar a los que atacan · Ayudar a los que defienden · No involucrarse. */
    public static void offer(ServerLevel level, InvasionData.March m, ServerPlayer p, boolean asked) {
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (v == null || v.isConquered()) return;
        ASKED.computeIfAbsent(m.id, k -> new java.util.HashSet<>()).add(p.getUUID());
        Component att = name(FactionManager.INSTANCE.get(m.attacker)), def = name(FactionManager.INSTANCE.get(v.faction()));
        Component place = com.ale.warbandcraft.village.VillageCamps.displayName(level, v,
                Component.literal(com.ale.warbandcraft.village.VillageCamps.nameFor(v)));
        List<Component> lines = List.of(
                Component.translatable("gui.warbandcraft.join.sides", att, m.size, def, m.defenders),
                Component.translatable("gui.warbandcraft.join.cost", -JOIN_OPINION, HELP_OPINION),
                Component.translatable("gui.warbandcraft.join.soldiers", JOIN_RADIUS));
        com.ale.warbandcraft.battle.BattleChoices.send(p, com.ale.warbandcraft.battle.BattleChoices.KIND_JOIN, m.id.toString(),
                Component.translatable("gui.warbandcraft.join.title", att, place),
                lines, List.of(Component.translatable("gui.warbandcraft.join.help", att), Component.translatable("gui.warbandcraft.join.help", def),
                        Component.translatable("gui.warbandcraft.join.none")), asked);
    }

    /**
     * Tecla B: si hay una batalla entre facciones cerca, ofrece meterse (o, si ya estás metido, suma a tus soldados de
     * cerca). Devuelve true si había una.
     */
    public static boolean offerNear(ServerLevel level, ServerPlayer p) {
        for (InvasionData.March m : InvasionData.get(level).marches()) {
            if (!m.fighting || VillageData.horizontalDistSq(p.blockPosition(), m.target) > (double) BATTLE_NEWS_RANGE * BATTLE_NEWS_RANGE) continue;
            if (m.helpedAttack.contains(p.getUUID()) || m.helpedDefense.contains(p.getUUID())) {
                int n = joinSoldiers(level, p, m);
                p.displayClientMessage(Component.translatable("msg.warbandcraft.join.more", n).withStyle(ChatFormatting.AQUA), true);
            } else {
                offer(level, m, p, true);
            }
            return true;
        }
        return false;
    }

    /** Contestó la pantalla: 0 = ayudar a los que atacan, 1 = a los que defienden, otra cosa = no se mete. */
    public static void answer(ServerPlayer p, String id, int option) {
        ServerLevel level = p.serverLevel();
        InvasionData.March m;
        try {
            m = InvasionData.get(level).marches().stream().filter(x -> x.id.equals(UUID.fromString(id))).findFirst().orElse(null);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (m == null || !m.fighting) {
            p.displayClientMessage(Component.translatable("msg.warbandcraft.join.over").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (option == 0 || option == 1) {
            join(level, p, m, option == 0);
        } else if (option == 2) {
            p.displayClientMessage(Component.translatable("msg.warbandcraft.join.declined").withStyle(ChatFormatting.GRAY), true);
        }
    }

    /**
     * Se mete del lado de los que atacan ({@code attackSide}) o de los que defienden: el otro bando lo quiere menos y lo
     * ataca a él y a sus soldados; sus soldados cerca se meten solos. Devuelve cuántos soldados se metieron.
     */
    public static int join(ServerLevel level, ServerPlayer p, InvasionData.March m, boolean attackSide) {
        VillageData.Village v = VillageData.get(level).at(m.target);
        if (v == null) return 0;
        UUID id = p.getUUID();
        ASKED.computeIfAbsent(m.id, k -> new java.util.HashSet<>()).add(id);
        java.util.Set<UUID> mine = attackSide ? m.helpedAttack : m.helpedDefense, other = attackSide ? m.helpedDefense : m.helpedAttack;
        other.remove(id);
        ResourceLocation ally = attackSide ? m.attacker : v.faction(), enemy = attackSide ? v.faction() : m.attacker;
        if (mine.add(id)) Relations.get(level.getServer()).add(id, enemy, JOIN_OPINION);
        InvasionData.get(level).changed();
        int n = joinSoldiers(level, p, m);
        p.sendSystemMessage(Component.translatable("msg.warbandcraft.join.joined", name(FactionManager.INSTANCE.get(ally)), n,
                name(FactionManager.INSTANCE.get(enemy)), JOIN_OPINION).withStyle(ChatFormatting.AQUA));
        return n;
    }

    /** Tus soldados cerca (no los de un ejército ni los del cuartel) se meten en la batalla. */
    private static int joinSoldiers(ServerLevel level, ServerPlayer p, InvasionData.March m) {
        int n = 0;
        String id = m.id.toString();
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class, p.getBoundingBox().inflate(JOIN_RADIUS),
                s -> s.isAlive() && com.ale.warbandcraft.faction.Allies.same(s.getOwnerUUID(), p.getUUID()) && com.ale.warbandcraft.ai.Soldiers.isSoldier(s)
                        && !com.ale.warbandcraft.battle.Surrender.is(s) && !com.ale.warbandcraft.army.Armies.inArmy(s)
                        && !s.getPersistentData().contains(com.ale.warbandcraft.village.Quarters.TAG_QUARTERS))) {
            var pd = s.getPersistentData();
            if (id.equals(pd.getString(TAG_JOIN))) continue;
            if (!pd.contains(TAG_JOIN_STATE)) pd.putInt(TAG_JOIN_STATE, s.getFollowState());
            pd.putString(TAG_JOIN, id);
            n++;
        }
        return n;
    }

    /** Tus soldados que se metieron: cada uno contra el enemigo más cercano del otro bando. */
    private static void helpersTick(ServerLevel level, InvasionData.March m, List<AbstractRecruitEntity> att, List<AbstractRecruitEntity> def) {
        if (m.helpedAttack.isEmpty() && m.helpedDefense.isEmpty()) return;
        String id = m.id.toString();
        for (AbstractRecruitEntity s : helpers(level, m)) {
            UUID owner = s.getOwnerUUID();
            if (owner == null || com.ale.warbandcraft.battle.Surrender.is(s)) continue;
            List<AbstractRecruitEntity> foes = m.helpedAttack.contains(owner) ? def : m.helpedDefense.contains(owner) ? att : List.of();
            if (foes.isEmpty() || !id.equals(s.getPersistentData().getString(TAG_JOIN))) continue;
            LivingEntity t = s.getTarget();
            if (t != null && t.isAlive() && foes.contains(t)) continue;
            AbstractRecruitEntity best = null;
            double bestD = HELPER_REACH * HELPER_REACH;
            for (AbstractRecruitEntity f : foes) {
                double d = f.distanceToSqr(s);
                if (d < bestD) {
                    bestD = d;
                    best = f;
                }
            }
            if (best == null) continue;
            s.setTarget(best);
            if (s.getFollowState() != 0) s.setFollowState(0);
        }
    }

    private static List<AbstractRecruitEntity> helpers(ServerLevel level, InvasionData.March m) {
        String id = m.id.toString();
        double r = HELPER_REACH + 64;
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(m.target).inflate(r, 64, r),
                s -> s.isAlive() && id.equals(s.getPersistentData().getString(TAG_JOIN)));
    }

    /** Terminó la batalla: tus soldados vuelven a la orden que tenían. */
    private static void releaseHelpers(ServerLevel level, InvasionData.March m) {
        ASKED.remove(m.id);
        if (!level.isPositionEntityTicking(m.target)) return;
        for (AbstractRecruitEntity s : helpers(level, m)) release(s);
    }

    /** Le saca la marca de meterse y le devuelve su orden de antes. */
    public static void release(AbstractRecruitEntity s) {
        var pd = s.getPersistentData();
        if (!pd.contains(TAG_JOIN)) return;
        int before = pd.contains(TAG_JOIN_STATE) ? pd.getInt(TAG_JOIN_STATE) : 1;
        pd.remove(TAG_JOIN);
        pd.remove(TAG_JOIN_STATE);
        s.setTarget(null);
        s.setFollowState(before);
    }

    /** Un soldado tuyo que se carga del disco marcado para una batalla que ya no está: vuelve a lo suyo. */
    public static void releaseStale(ServerLevel level, AbstractRecruitEntity s) {
        String id = s.getPersistentData().getString(TAG_JOIN);
        if (id.isEmpty()) return;
        for (InvasionData.March m : InvasionData.get(level).marches()) if (m.fighting && m.id.toString().equals(id)) return;
        release(s);
    }

    /**
     * Terminó una batalla en la que alguien se metió: el bando que ganó les agradece a los que lo ayudaron (opinión +
     * botín: esmeraldas por enemigo caído y, si tomaron la aldea, parte de su almacén), y los soldados vuelven.
     * Se llama antes de que la aldea cambie de dueño.
     */
    private static void finish(ServerLevel level, InvasionData.March m, VillageData.Village v, boolean attackerWon) {
        ResourceLocation winner = attackerWon ? m.attacker : v.faction();
        java.util.Set<UUID> players = attackerWon ? m.helpedAttack : m.helpedDefense;
        if (!players.isEmpty()) {
            List<net.minecraft.world.item.ItemStack> share = new ArrayList<>();
            if (attackerWon) {   // parte del almacén de la aldea tomada, repartida
                VillageData villages = VillageData.get(level);
                com.ale.warbandcraft.village.VillageStock.Stock st = villages.stock(v.center());
                int k = players.size();
                com.ale.warbandcraft.village.VillageStock.Stock part = new com.ale.warbandcraft.village.VillageStock.Stock(
                        (int) (st.emeralds() * LOOT_STOCK_SHARE / k), (int) (st.food() * LOOT_STOCK_SHARE / k), (int) (st.armament() * LOOT_STOCK_SHARE / k));
                share.addAll(part.items());
            }
            for (UUID u : players) {
                Relations.get(level.getServer()).add(u, winner, HELP_OPINION);
                // H23: el premio va a la bolsa de la batalla (con el equipo de los caídos) y todo al Campamento más cercano
                com.ale.warbandcraft.battle.BattleLoot.bonus(level, u, LOOT_BASE, m.target);
                if (!share.isEmpty()) com.ale.warbandcraft.battle.BattleLoot.give(level, u, share, m.target);
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(u);
                if (p != null) {
                    p.sendSystemMessage(Component.translatable("msg.warbandcraft.aiwar.thanks", name(FactionManager.INSTANCE.get(winner)), HELP_OPINION)
                            .withStyle(ChatFormatting.GREEN));
                }
                com.ale.warbandcraft.battle.BattleLoot.flush(level, u);
            }
        }
        releaseHelpers(level, m);
    }

    /**
     * El jugador que se metió del otro lado del de {@code dead} (para su botín): {@code preferred} si es uno de ellos (el
     * que lo mató), si no el primero. Null si nadie se metió contra su bando.
     */
    @Nullable
    public static UUID helperAgainst(ServerLevel level, AbstractRecruitEntity dead, @Nullable UUID preferred) {
        Side s = sideOf(level, dead);
        if (s == null) return null;
        java.util.Set<UUID> helpers = s.attacker() ? s.march().helpedDefense : s.march().helpedAttack;
        if (preferred != null && helpers.contains(preferred)) return preferred;
        return helpers.isEmpty() ? null : helpers.iterator().next();
    }

    /** ¿{@code a} y {@code b} pelean en la misma batalla entre facciones, de bandos distintos? */
    public static boolean enemies(AbstractRecruitEntity a, LivingEntity b) {
        if (!(b instanceof AbstractRecruitEntity r) || !VillageManager.isGarrison(a) || !VillageManager.isGarrison(r)) return false;
        var pa = a.getPersistentData();
        var pb = r.getPersistentData();
        if (pa.getString(VillageManager.TAG_FACTION).equals(pb.getString(VillageManager.TAG_FACTION))) return false;
        if (pa.contains(TAG_AI_ATTACK)) return pb.contains(VillageManager.TAG_VILLAGE) && pb.getLong(VillageManager.TAG_VILLAGE) == pa.getLong(TAG_AI_ATTACK);
        if (pb.contains(TAG_AI_ATTACK)) return pa.contains(VillageManager.TAG_VILLAGE) && pa.getLong(VillageManager.TAG_VILLAGE) == pb.getLong(TAG_AI_ATTACK);
        if ((Patrols.isRoaming(a) || Patrols.isRoaming(r)) && a.level() instanceof ServerLevel level) {   // patrullas errantes que se cruzan
            return Patrols.factionsAtWar(level, pa.getString(VillageManager.TAG_FACTION), pb.getString(VillageManager.TAG_FACTION));
        }
        return false;
    }

    /** Un atacante que se carga del disco: solo sigue si su batalla sigue (si no, lo que quedó ya se contó). */
    public static boolean keepAttacker(ServerLevel level, net.minecraft.world.entity.Entity e) {
        if (!e.getPersistentData().contains(TAG_AI_ATTACK)) return true;
        return InvasionData.get(level).battleAt(BlockPos.of(e.getPersistentData().getLong(TAG_AI_ATTACK))) != null;
    }

    // ------------------------------------------------------------------ en el mapa

    /** Dónde va un ejército ahora: entre su salida y su destino, según el tiempo. */
    public static Vec3 position(BlockPos from, BlockPos to, long start, long arrive, long now) {
        double f = arrive <= start ? 1 : Math.max(0, Math.min(1, (now - start) / (double) (arrive - start)));
        return Vec3.atCenterOf(from).lerp(Vec3.atCenterOf(to), f);
    }

    // ------------------------------------------------------------------ textos

    static Component name(@Nullable Faction f) {
        return f == null ? Component.literal("?") : Component.literal(f.name()).withStyle(f.color());
    }

    /** Noticia para todos los jugadores de esta dimensión. */
    static void announce(ServerLevel level, Component msg) {
        for (ServerPlayer p : level.players()) p.sendSystemMessage(msg);
    }
}
