package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.invasion.Breach;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * El general de las batallas (H9). Corre una vez por segundo por cada ataque en curso y maneja a los dos bandos
 * en tres capas, como Bannerlord:
 * <ul>
 *   <li><b>Ejército</b>: el plan según la relación de fuerzas (cargar, formar línea, replegarse / avanzar, aguantar).</li>
 *   <li><b>Formación</b>: infantería en línea de dos filas, arqueros detrás, caballería por el flanco, reserva.</li>
 *   <li><b>Soldado</b>: lo hace Recruits (pegar, cubrirse, disparar). El general solo da órdenes con lo de
 *       siempre: {@code setHoldPos} + estado 3 para formarse, estado 0 + {@code setTarget} para pelear.</li>
 * </ul>
 * Los defensores (la guarnición) siempre pelean así. Tus soldados solo si tienen la orden "Batalla"
 * ({@link #TAG_BATTLE}). Lo que decide el general no se guarda: se recalcula cada segundo.
 */
public final class BattleGeneral {
    /** En tu soldado: lo maneja el general cuando hay un ataque tuyo cerca. */
    public static final String TAG_BATTLE = "warbandcraft:battle";

    // Ejército
    /** Con más que esta relación de fuerzas, cargan todos. */
    public static double CHARGE_RATIO = 1.8;
    /** El defensor se repliega al centro con menos que esto. */
    public static double FALL_BACK_RATIO = 0.6;
    /** Tus soldados aguantan donde están con menos que esto. */
    public static double HOLD_RATIO = 0.4;
    /** El jugador vale como tantos soldados. */
    private static double PLAYER_WEIGHT = 2.0;

    // Formación
    /** A cuánto del centro de la aldea forma la línea del defensor (hacia el enemigo). */
    public static double LINE_DISTANCE = 12;
    /** Cuánto camina por segundo la línea de tus soldados al avanzar. */
    public static double ADVANCE_STEP = 6;
    /** Separación entre soldados de una fila, y entre filas. */
    public static double SPACING = 1.75;
    private static double RANK_GAP = 2.0;
    private static int RANKS = 2;
    /** Los arqueros forman tanto detrás de la infantería. */
    public static double RANGED_BEHIND = 6;
    /** Un infante con un enemigo a esta distancia sale a pelear... */
    public static double ENGAGE_DISTANCE = 8;
    /** ...y no se lo vuelve a formar mientras tenga enemigos a esta distancia. */
    public static double KEEP_FIGHTING = 16;
    /** Hasta dónde tiran los arqueros. */
    public static double RANGED_REACH = 48;
    /** La caballería rodea a esta distancia al costado del enemigo... */
    public static double FLANK_DISTANCE = 18;
    /** ...y carga cuando llega (a esta distancia del punto) o pasado este tiempo. */
    private static double FLANK_ARRIVED = 8;
    public static int FLANK_TIMEOUT_TICKS = 15 * 20;
    /** Qué parte de tu infantería queda de reserva (con al menos tantos infantes). */
    public static double RESERVE_FRACTION = 0.2;
    public static int RESERVE_MIN_INFANTRY = 5;
    /** El capitán pelea solo si alguien llega hasta acá. */
    private static double CAPTAIN_GUARD = 6;
    /** Puestos de arqueros en las murallas de un castillo: mínimo esto arriba del piso, y separados entre sí. */
    private static int WALL_MIN_HEIGHT = 4;
    private static double WALL_POST_SPACING = 3;
    private static int WALL_POSTS = 24;
    /** No re-mandar a formarse si el puesto nuevo está a menos que esto del anterior (cada orden recalcula el camino). */
    private static double MOVE_THRESHOLD = 1.0;

    private static final int WANDER = 0, FOLLOW_OWNER = 1, BACK_TO_POS = 3;
    /** Cuánto se queda quieto un defensor de castillo que no puede llegar a su enemigo (después prueba de nuevo). */
    public static int HOLD_TICKS = 100;

    public enum Role { INFANTRY, RANGED, CAVALRY, CAPTAIN }

    public enum Plan { CHARGE, LINE, FALL_BACK, ADVANCE, HOLD }

    /** Una orden que dio el general (para notar si después alguien la cambió). */
    private record Order(int follow, @Nullable Vec3 hold) {}

    /** Lo que el general recuerda de un ataque mientras dura (no se guarda). */
    private static final class Memory {
        final Map<UUID, Order> orders = new HashMap<>();
        @Nullable Plan defenderPlan, attackerPlan;
        long defenderFlankStart = -1, attackerFlankStart = -1;
        boolean defenderCavCharged, attackerCavCharged;
        int reserveSize = -1;
        boolean reserveReleased;
        @Nullable List<Vec3> wallPosts;
        /** Se trabaron yendo a su puesto (aldea, bosque): ya no forman, solo buscan enemigos. */
        final java.util.Set<UUID> free = new java.util.HashSet<>();
        /** Este segundo los está sacando el destrabador (yendo a una puerta): el general no los toca. */
        final java.util.Set<UUID> escaping = new java.util.HashSet<>();
        /** Defensores de castillo que no llegan a un enemigo de afuera: se quedan quietos hasta este tick. */
        final Map<UUID, Long> holdUntil = new HashMap<>();
        /** Asedio (castillo o aldea con muralla): tus carriles. Null hasta que hay soldados tuyos en Batalla. */
        @Nullable SiegeLanes.State siege;
    }

    private static final Map<String, Memory> MEMORY = new HashMap<>();

    private BattleGeneral() {}

    private static String key(ServerLevel level, BlockPos center) {
        return level.dimension().location() + "@" + center.asLong();
    }

    // ------------------------------------------------------------------ el tick

    /** Una vuelta del general para el ataque {@code raid} (solo con la aldea cargada). */
    public static void tick(ServerLevel level, RaidManager.Raid raid) {
        if (raid.state != RaidManager.State.FIGHTING || !level.isPositionEntityTicking(raid.center)) return;
        Memory m = MEMORY.computeIfAbsent(key(level, raid.center), k -> new Memory());

        List<AbstractRecruitEntity> defenders = VillageManager.garrison(level, raid.center);
        List<AbstractRecruitEntity> yours = attackerSoldiers(level, raid);
        Player player = RaidManager.attackerEntity(level, raid);
        List<LivingEntity> attackers = new ArrayList<>(yours);
        if (player != null && player.isAlive() && player.level() == level && !player.isSpectator()
                && VillageData.horizontalDistSq(player.blockPosition(), raid.center) <= RaidManager.REINFORCE_RANGE * RaidManager.REINFORCE_RANGE) {
            attackers.add(player);
        }
        if (defenders.isEmpty() || attackers.isEmpty()) return;

        VillageData.Village village = VillageData.get(level).at(raid.center);
        boolean castle = village != null && village.castle();

        // Pedido de Ale: que nunca se queden parados. Si nadie pierde vida un rato, la guarnición (y tus soldados en
        // Batalla) salen a buscar al enemigo más cercano por la malla.
        if (SiegePush.watch(key(level, raid.center), level.getGameTime(), defenders, attackers) != SiegePush.Stage.NORMAL) {
            List<AbstractRecruitEntity> battle = new ArrayList<>();
            for (AbstractRecruitEntity s : yours) if (inBattle(s)) battle.add(s);
            SiegePush.huntAll(level, defenders, attackers, raid.center, com.ale.warbandcraft.nav.NavCaps.SOLDIER);
            SiegePush.huntAll(level, battle, defenders, raid.center, com.ale.warbandcraft.nav.NavCaps.INVADER);
            return;
        }

        // Con muros: asedio al estilo Bannerlord (carriles, escaleras, todos de a pie). Si no hay nadie tuyo en
        // Batalla, la guarnición pelea como siempre.
        Breach.Walls walls = village == null ? null : Breach.walls(level, village);
        if (walls != null && walls.walled() && siegeTick(level, raid, m, defenders, yours, attackers, walls)) return;

        // Destrabador: el que no avanza deja la formación, pica, busca una puerta o sale teletransportado.
        // Los defensores de un castillo no salen: se quedan adentro defendiendo.
        m.escaping.clear();
        for (AbstractRecruitEntity s : defenders) escape(level, m, s, true, castle);
        for (AbstractRecruitEntity s : yours) escape(level, m, s, inBattle(s), false);

        double defPower = power(defenders), attPower = power(attackers);

        // Defensores
        Plan dp = defenderPlan(defPower / Math.max(0.1, attPower));
        if (dp != m.defenderPlan) {
            m.defenderPlan = dp;
            tell(level, raid, Component.translatable("msg.warbandcraft.battle.enemy_" + dp.name().toLowerCase()).withStyle(ChatFormatting.RED));
        }
        commandDefenders(level, raid, m, defenders, attackers, dp, castle);

        // Tus soldados con la orden Batalla
        List<AbstractRecruitEntity> battle = new ArrayList<>();
        int left = 0;
        for (AbstractRecruitEntity s : yours) {
            if (!s.getPersistentData().getBoolean(TAG_BATTLE)) continue;
            if (overridden(m, s)) {
                leaveBattle(s);
                left++;
            } else {
                battle.add(s);
            }
        }
        if (left > 0) tell(level, raid, Component.translatable("msg.warbandcraft.battle.left", left).withStyle(ChatFormatting.GRAY));
        if (!battle.isEmpty()) {
            Plan ap = attackerPlan(attPower / Math.max(0.1, defPower));
            if (ap != m.attackerPlan) {
                m.attackerPlan = ap;
                tell(level, raid, Component.translatable("msg.warbandcraft.battle.ours_" + ap.name().toLowerCase()).withStyle(ChatFormatting.AQUA));
            }
            commandAttackers(level, m, battle, new ArrayList<>(defenders), ap);
        }
    }

    private static void escape(ServerLevel level, Memory m, AbstractRecruitEntity s, boolean commanded, boolean stayInside) {
        BattleEscape.Decision d = BattleEscape.check(level, s, commanded, stayInside);
        switch (d.action()) {
            case FREE -> m.free.add(s.getUUID());
            case HOLD -> {
                s.setTarget(null);
                m.holdUntil.put(s.getUUID(), level.getGameTime() + HOLD_TICKS);
            }
            case NAV -> m.escaping.add(s.getUUID());   // lo lleva la malla: el general no lo toca
            default -> {}
        }
    }

    /** Separa a los que forman de los que no: los que salen por una puerta no se tocan; los libres, a pelear. */
    private static List<AbstractRecruitEntity> formable(Memory m, List<AbstractRecruitEntity> soldiers, List<LivingEntity> enemies) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (AbstractRecruitEntity s : soldiers) {
            if (m.escaping.contains(s.getUUID())) continue;
            Long hold = m.holdUntil.get(s.getUUID());
            if (hold != null && hold > s.level().getGameTime()) {
                // No llega al enemigo (está afuera del castillo): quieto en su lugar, los arqueros siguen tirando
                if (s.getTarget() != null && roleOf(s) != Role.RANGED) s.setTarget(null);
                moveTo(m, s, s.getHoldPos() != null && s.getFollowState() == BACK_TO_POS ? s.getHoldPos() : s.position());
                if (roleOf(s) == Role.RANGED) aim(s, enemies);
                continue;
            }
            if (m.free.contains(s.getUUID()) && roleOf(s) != Role.CAPTAIN) engage(m, s, enemies, false);
            else out.add(s);
        }
        return out;
    }

    /** Terminó el ataque (ganado o abandonado): tus soldados vuelven a seguirte; la guarnición, a patrullar. */
    public static void end(ServerLevel level, RaidManager.Raid raid) {
        Memory mem = MEMORY.remove(key(level, raid.center));
        SiegePush.forget(key(level, raid.center));
        SiegeLanes.end(level, siegeHost(level, raid), mem == null ? null : mem.siege);
        SiegeDefenders.end(siegeKey(level, raid), level.isPositionEntityTicking(raid.center) ? VillageManager.garrison(level, raid.center) : List.of());
        if (!level.isPositionEntityTicking(raid.center)) return;
        for (AbstractRecruitEntity s : attackerSoldiers(level, raid)) {
            if (s.getPersistentData().getBoolean(TAG_BATTLE)) {
                com.ale.warbandcraft.nav.Mover.stop(s);
                leaveBattle(s);
                s.setTarget(null);
                s.setFollowState(FOLLOW_OWNER);
            }
        }
        for (AbstractRecruitEntity s : VillageManager.garrison(level, raid.center)) {
            s.setShouldBlock(false);
            s.setTarget(null);
            s.setFollowState(WANDER);
        }
    }

    // ------------------------------------------------------------------ asedio

    /** Con tantos soldados en Batalla, el asedio va por dos carriles (separados tantos grados). */
    public static int SIEGE_TWO_LANES = 10;
    public static double SIEGE_SPREAD_DEG = 40;

    /**
     * Asedio a un castillo (o una aldea con muralla) con tus soldados en Batalla: atacan por carriles
     * ({@link SiegeLanes}: hueco abierto si hay; si no, escaleras o tus zapadores con TNT) y la guarnición defiende con
     * {@link SiegeDefenders} (arqueros arriba, tumbar escaleras, esperar adentro). El capitán se queda en el centro. Todos
     * de a pie. Devuelve false si no hay nadie tuyo en Batalla (entonces, el general de siempre).
     */
    private static boolean siegeTick(ServerLevel level, RaidManager.Raid raid, Memory m, List<AbstractRecruitEntity> defenders,
                                     List<AbstractRecruitEntity> yours, List<LivingEntity> attackers, Breach.Walls w) {
        List<AbstractRecruitEntity> battle = new ArrayList<>();
        for (AbstractRecruitEntity s : yours) if (inBattle(s)) battle.add(s);
        if (battle.isEmpty() && m.siege == null) return false;
        long now = level.getGameTime();
        BlockPos center = raid.center;
        for (AbstractRecruitEntity s : battle) SiegeLanes.dismount(s, true);
        if (m.siege == null) {
            m.siege = new SiegeLanes.State();
            double a = SiegeLanes.angleOf(center, centroid(battle));
            int lanes = battle.size() >= SIEGE_TWO_LANES ? 2 : 1;
            BlockPos first = SiegeLanes.stagePos(level, center, a, w, null);
            m.siege.add(SiegeLanes.angleOf(center, Vec3.atBottomCenterOf(first)), first);
            if (lanes == 2) {
                BlockPos second = SiegeLanes.stagePos(level, center, a + Math.toRadians(SIEGE_SPREAD_DEG), w, first);
                m.siege.add(SiegeLanes.angleOf(center, Vec3.atBottomCenterOf(second)), second);
            }
            tell(level, raid, Component.translatable("msg.warbandcraft.siege.ours_start", lanes).withStyle(ChatFormatting.AQUA));
        }
        // Tus soldados, repartidos en los carriles (siempre los mismos)
        battle.sort(Comparator.comparing(e -> e.getUUID().toString()));
        List<LivingEntity> enemies = new ArrayList<>(defenders);
        SiegeLanes.Host host = siegeHost(level, raid);
        int n = m.siege.lanes.size();
        for (SiegeLanes.Lane lane : m.siege.lanes) {
            List<AbstractRecruitEntity> members = new ArrayList<>();
            for (int i = 0; i < battle.size(); i++) if (i % n == lane.index) members.add(battle.get(i));
            SiegeLanes.tick(level, host, m.siege, lane, members, enemies, w, true, now);
        }
        // La guarnición: de a pie, por carril; el capitán en el centro
        List<AbstractRecruitEntity> def = new ArrayList<>();
        for (AbstractRecruitEntity s : defenders) {
            if (roleOf(s) == Role.CAPTAIN) {
                captain(m, s, Vec3.atBottomCenterOf(center), attackers);
                continue;
            }
            SiegeLanes.dismount(s, true);
            def.add(s);
        }
        SiegeDefenders.tick(level, siegeKey(level, raid), defenseHost(raid), def, attackers, SiegeLanes.info(m.siege), w,
                com.ale.warbandcraft.nav.NavCaps.SOLDIER);
        return true;
    }

    /** Los carriles de tu asedio a {@code center} (para las pruebas). Vacío si no hay asedio. */
    public static List<SiegeLanes.LaneInfo> siegeLanes(ServerLevel level, BlockPos center) {
        Memory m = MEMORY.get(key(level, center));
        return SiegeLanes.info(m == null ? null : m.siege);
    }

    private static String siegeKey(ServerLevel level, RaidManager.Raid raid) {
        return "raid:" + key(level, raid.center);
    }

    /** Lo que es de tu ataque para los carriles: las escaleras se guardan en el ataque y los avisos van a vos. */
    private static SiegeLanes.Host siegeHost(ServerLevel level, RaidManager.Raid raid) {
        return new SiegeLanes.Host() {
            @Override
            public BlockPos center() {
                return raid.center;
            }

            @Override
            public List<Long> ladders() {
                return raid.ladders;
            }

            @Override
            public void changed() {
                RaidManager.changed();
            }

            @Override
            public boolean cannons() {
                return true;   // tu zapador arma el cañón solo (pagando lo de siempre)
            }

            @Override
            public boolean payCannon(ServerLevel l, AbstractRecruitEntity sapper) {
                return com.ale.warbandcraft.sapper.SapperRules.payCannon(l, sapper, raid.attacker);
            }

            @Override
            public void announce(SiegeLanes.Event e) {
                tell(level, raid, Component.translatable("msg.warbandcraft.siege.ours_" + e.name().toLowerCase()).withStyle(ChatFormatting.AQUA));
            }

            @Override
            public String name() {
                return "tu ataque a " + raid.center.toShortString();
            }
        };
    }

    private static SiegeDefenders.Host defenseHost(RaidManager.Raid raid) {
        return new SiegeDefenders.Host() {
            @Override
            public BlockPos center() {
                return raid.center;
            }

            @Override
            public @Nullable UUID listener() {
                return raid.attacker;
            }

            @Override
            public void ladderPushed(BlockPos base) {
                raid.ladders.remove(base.asLong());
                RaidManager.changed();
            }
        };
    }

    /** Se olvida de todo (al cerrar el servidor). */
    public static void clear() {
        MEMORY.clear();
        BattleEscape.clear();
        SiegeDefenders.clear();
    }

    /** Tus soldados (vivos, de pie) cerca del ataque. */
    public static List<AbstractRecruitEntity> attackerSoldiers(ServerLevel level, RaidManager.Raid raid) {
        double r = RaidManager.REINFORCE_RANGE;
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(raid.center).inflate(r, 64, r),
                s -> s.isAlive() && s.getIsOwned() && Objects.equals(s.getOwnerUUID(), raid.attacker) && !Surrender.is(s)
                        && Soldiers.isSoldier(s));
    }

    // ------------------------------------------------------------------ la orden "Batalla"

    /** Le da (o le saca) la orden Batalla a tu soldado. */
    public static void setBattle(AbstractRecruitEntity s, boolean on) {
        if (on) {
            s.getPersistentData().putBoolean(TAG_BATTLE, true);
        } else if (inBattle(s)) {
            leaveBattle(s);
        }
    }

    public static boolean inBattle(AbstractRecruitEntity s) {
        return s.getPersistentData().getBoolean(TAG_BATTLE);
    }

    private static void leaveBattle(AbstractRecruitEntity s) {
        s.getPersistentData().remove(TAG_BATTLE);
        s.setShouldBlock(false);
        for (Memory m : MEMORY.values()) m.orders.remove(s.getUUID());
    }

    /** ¿Alguien le cambió la orden que le dio el general? (otra orden de Recruits o de la vista aérea) */
    private static boolean overridden(Memory m, AbstractRecruitEntity s) {
        Order o = m.orders.get(s.getUUID());
        if (o == null) return false;
        if (s.getFollowState() != o.follow()) return true;
        if (o.hold() == null) return false;
        Vec3 hold = s.getHoldPos();
        return hold == null || hold.distanceToSqr(o.hold()) > 1.0;
    }

    // ------------------------------------------------------------------ capa Ejército

    public static Plan defenderPlan(double ratio) {
        if (ratio > CHARGE_RATIO) return Plan.CHARGE;
        if (ratio < FALL_BACK_RATIO) return Plan.FALL_BACK;
        return Plan.LINE;
    }

    public static Plan attackerPlan(double ratio) {
        if (ratio > CHARGE_RATIO) return Plan.CHARGE;
        if (ratio < HOLD_RATIO) return Plan.HOLD;
        return Plan.ADVANCE;
    }

    /** Fuerza de un bando: vida × armadura × nivel, más a caballo; el jugador vale por {@link #PLAYER_WEIGHT}. */
    public static double power(List<? extends LivingEntity> side) {
        double total = 0;
        for (LivingEntity e : side) {
            double p = e.getHealth() * (1 + e.getArmorValue() / 10.0);
            if (e instanceof AbstractRecruitEntity r) {
                p *= 1 + 0.15 * r.getXpLevel();
                if (r.getVehicle() instanceof AbstractHorse) p *= 1.5;
            } else if (e instanceof Player) {
                p *= PLAYER_WEIGHT;
            }
            total += p;
        }
        return total;
    }

    public static Role roleOf(AbstractRecruitEntity s) {
        if (s.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)) return Role.CAPTAIN;
        if (s.getVehicle() instanceof AbstractHorse) return Role.CAVALRY;
        if (isRangedWeapon(s.getMainHandItem())) return Role.RANGED;
        return Role.INFANTRY;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        return stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem;
    }

    // ------------------------------------------------------------------ capa Formación: defensores

    private static void commandDefenders(ServerLevel level, RaidManager.Raid raid, Memory m, List<AbstractRecruitEntity> defenders,
                                         List<LivingEntity> enemies, Plan plan, boolean castle) {
        Vec3 center = Vec3.atBottomCenterOf(raid.center);
        Vec3 enemyCenter = centroid(enemies);
        Vec3 dir = horizontalDir(center, enemyCenter);
        double dist = horizontal(center, enemyCenter);
        Vec3 front = plan == Plan.FALL_BACK ? center.add(dir.scale(3)) : center.add(dir.scale(Math.min(LINE_DISTANCE, dist / 2)));

        List<AbstractRecruitEntity> infantry = new ArrayList<>(), ranged = new ArrayList<>(), cavalry = new ArrayList<>();
        for (AbstractRecruitEntity s : formable(m, defenders, enemies)) {
            switch (roleOf(s)) {
                case INFANTRY -> infantry.add(s);
                case RANGED -> ranged.add(s);
                case CAVALRY -> cavalry.add(s);
                case CAPTAIN -> captain(m, s, center, enemies);
            }
        }

        if (plan == Plan.CHARGE) {
            for (AbstractRecruitEntity s : infantry) engage(m, s, enemies, false);
        } else {
            formInfantry(level, m, infantry, front, dir, enemies);
        }

        if (castle && !ranged.isEmpty()) {
            if (m.wallPosts == null) m.wallPosts = wallPosts(level, raid.center);
            if (!m.wallPosts.isEmpty()) toWalls(m, ranged, m.wallPosts, enemies);
            else formRanged(level, m, ranged, front.subtract(dir.scale(RANGED_BEHIND)), dir, enemies);
        } else if (plan == Plan.CHARGE) {
            for (AbstractRecruitEntity s : ranged) aim(s, enemies);
        } else {
            formRanged(level, m, ranged, front.subtract(dir.scale(RANGED_BEHIND)), dir, enemies);
        }

        long now = level.getGameTime();
        if (plan == Plan.FALL_BACK && !m.defenderCavCharged) {
            // Replegados: la caballería espera al lado de la infantería y sale si alguien se acerca
            formInfantry(level, m, cavalry, center.subtract(dir.scale(4)), dir, enemies);
        } else {
            if (m.defenderFlankStart < 0) m.defenderFlankStart = now;
            m.defenderCavCharged = flank(m, cavalry, enemies, enemyCenter, dir, plan == Plan.CHARGE || m.defenderCavCharged,
                    now - m.defenderFlankStart > FLANK_TIMEOUT_TICKS);
        }
    }

    // ------------------------------------------------------------------ capa Formación: tus soldados

    private static void commandAttackers(ServerLevel level, Memory m, List<AbstractRecruitEntity> soldiers,
                                         List<LivingEntity> enemies, Plan plan) {
        List<AbstractRecruitEntity> infantry = new ArrayList<>(), ranged = new ArrayList<>(), cavalry = new ArrayList<>();
        soldiers = formable(m, soldiers, enemies);
        if (soldiers.isEmpty()) return;
        for (AbstractRecruitEntity s : soldiers) {
            switch (roleOf(s)) {
                case INFANTRY, CAPTAIN -> infantry.add(s);
                case RANGED -> ranged.add(s);
                case CAVALRY -> cavalry.add(s);
            }
        }
        Vec3 enemyCenter = centroid(enemies);
        Vec3 ownCenter = centroid(infantry.isEmpty() ? soldiers : infantry);
        Vec3 dir = horizontalDir(ownCenter, enemyCenter);
        double dist = horizontal(ownCenter, enemyCenter);
        Vec3 front = switch (plan) {
            case ADVANCE -> ownCenter.add(dir.scale(Math.max(0, Math.min(ADVANCE_STEP, dist - ENGAGE_DISTANCE / 2))));
            default -> ownCenter;
        };

        // Reserva: los infantes más atrasados esperan detrás de los arqueros hasta que la línea de adelante
        // quede en menos del doble que ellos, el plan sea cargar o el enemigo les llegue
        List<AbstractRecruitEntity> reserve = new ArrayList<>();
        if (!m.reserveReleased && infantry.size() >= RESERVE_MIN_INFANTRY) {
            int reserveSize = Math.max(1, (int) Math.round(infantry.size() * RESERVE_FRACTION));
            infantry.sort(Comparator.comparingDouble(s -> -horizontal(s.position(), enemyCenter)));
            reserve.addAll(infantry.subList(0, reserveSize));
            boolean threatened = false;
            for (AbstractRecruitEntity s : reserve) if (nearestDistance(s, enemies) <= ENGAGE_DISTANCE) threatened = true;
            if (m.reserveSize < 0) m.reserveSize = reserveSize;
            if (plan == Plan.CHARGE || threatened || infantry.size() - reserveSize < 2 * m.reserveSize) {
                m.reserveReleased = true;
                reserve.clear();
            } else {
                infantry.removeAll(reserve);
            }
        }

        if (plan == Plan.CHARGE) {
            for (AbstractRecruitEntity s : infantry) engage(m, s, enemies, false);
            for (AbstractRecruitEntity s : ranged) aim(s, enemies);
        } else {
            formInfantry(level, m, infantry, front, dir, enemies);
            formRanged(level, m, ranged, front.subtract(dir.scale(RANGED_BEHIND)), dir, enemies);
        }
        if (!reserve.isEmpty()) formInfantry(level, m, reserve, front.subtract(dir.scale(RANGED_BEHIND + 4)), dir, enemies);

        long now = level.getGameTime();
        if (plan == Plan.HOLD && !m.attackerCavCharged) {
            formInfantry(level, m, cavalry, front.subtract(dir.scale(RANGED_BEHIND)), dir, enemies);
        } else {
            if (m.attackerFlankStart < 0) m.attackerFlankStart = now;
            m.attackerCavCharged = flank(m, cavalry, enemies, enemyCenter, dir, plan == Plan.CHARGE || m.attackerCavCharged,
                    now - m.attackerFlankStart > FLANK_TIMEOUT_TICKS);
        }
    }

    // ------------------------------------------------------------------ piezas de la formación

    /** Infantería: línea en {@code front} mirando hacia {@code dir}; el que tiene un enemigo encima sale a pelear. */
    private static void formInfantry(ServerLevel level, Memory m, List<AbstractRecruitEntity> soldiers, Vec3 front, Vec3 dir,
                                     List<LivingEntity> enemies) {
        List<AbstractRecruitEntity> forming = new ArrayList<>();
        for (AbstractRecruitEntity s : soldiers) {
            if (isFighting(s) || nearestDistance(s, enemies) <= ENGAGE_DISTANCE) engage(m, s, enemies, false);
            else forming.add(s);
        }
        List<Vec3> slots = linePositions(level, front, dir, forming.size(), RANKS);
        assign(forming, slots, dir);
        for (int i = 0; i < forming.size(); i++) {
            AbstractRecruitEntity s = forming.get(i);
            s.setShouldBlock(true);
            moveTo(m, s, slots.get(i));
        }
    }

    /** Arqueros: línea quieta, tirándole al enemigo más cercano. */
    private static void formRanged(ServerLevel level, Memory m, List<AbstractRecruitEntity> soldiers, Vec3 line, Vec3 dir,
                                   List<LivingEntity> enemies) {
        List<Vec3> slots = linePositions(level, line, dir, soldiers.size(), 1);
        List<AbstractRecruitEntity> ordered = new ArrayList<>(soldiers);
        assign(ordered, slots, dir);
        for (int i = 0; i < ordered.size(); i++) {
            AbstractRecruitEntity s = ordered.get(i);
            moveTo(m, s, slots.get(i));
            aim(s, enemies);
        }
    }

    /** Castillo: los arqueros suben a los puestos de la muralla (el libre más cercano a cada uno). */
    private static void toWalls(Memory m, List<AbstractRecruitEntity> soldiers, List<Vec3> posts, List<LivingEntity> enemies) {
        List<Vec3> free = new ArrayList<>(posts);
        for (AbstractRecruitEntity s : soldiers) {
            if (free.isEmpty()) break;
            Vec3 best = free.get(0);
            for (Vec3 p : free) if (p.distanceToSqr(s.position()) < best.distanceToSqr(s.position())) best = p;
            free.remove(best);
            moveTo(m, s, best);
            aim(s, enemies);
        }
    }

    /**
     * Caballería: primero a un flanco del enemigo (el costado más cercano a ella), y de ahí carga contra sus
     * arqueros. Devuelve true si ya cargó (para no volver a rodear).
     */
    private static boolean flank(Memory m, List<AbstractRecruitEntity> cavalry, List<LivingEntity> enemies, Vec3 enemyCenter,
                                 Vec3 dir, boolean charge, boolean timedOut) {
        if (cavalry.isEmpty()) return charge;
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        Vec3 own = centroid(cavalry);
        double side = own.subtract(enemyCenter).dot(perp) >= 0 ? 1 : -1;
        Vec3 flankPoint = enemyCenter.add(perp.scale(side * FLANK_DISTANCE));
        boolean arrived = horizontal(own, flankPoint) <= FLANK_ARRIVED;
        if (charge || arrived || timedOut) {
            List<LivingEntity> priority = new ArrayList<>();
            for (LivingEntity e : enemies) if (e instanceof AbstractRecruitEntity r && roleOf(r) == Role.RANGED) priority.add(e);
            for (AbstractRecruitEntity s : cavalry) engage(m, s, priority.isEmpty() ? enemies : priority, true);
            return true;
        }
        for (int i = 0; i < cavalry.size(); i++) {
            AbstractRecruitEntity s = cavalry.get(i);
            if (isFighting(s) || nearestDistance(s, enemies) <= ENGAGE_DISTANCE) {
                engage(m, s, enemies, false);
                continue;
            }
            moveTo(m, s, flankPoint.add(dir.scale((i - (cavalry.size() - 1) / 2.0) * SPACING * 1.5)));
        }
        return false;
    }

    /** Puesto del flanco de la caballería (para las pruebas). */
    public static Vec3 flankPoint(Vec3 enemyCenter, Vec3 dir, Vec3 cavalryCenter) {
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        double side = cavalryCenter.subtract(enemyCenter).dot(perp) >= 0 ? 1 : -1;
        return enemyCenter.add(perp.scale(side * FLANK_DISTANCE));
    }

    /** El capitán se queda en el centro y pelea solo si alguien se le acerca. */
    private static void captain(Memory m, AbstractRecruitEntity s, Vec3 center, List<LivingEntity> enemies) {
        if (nearestDistance(s, enemies) <= CAPTAIN_GUARD) {
            engage(m, s, enemies, false);
        } else {
            moveTo(m, s, center);
        }
    }

    // ------------------------------------------------------------------ órdenes a un soldado

    /** A pelear: estado 0 (libre) contra el enemigo más cercano (o el que ya tenía, si sigue cerca). */
    private static void engage(Memory m, AbstractRecruitEntity s, List<LivingEntity> enemies, boolean forceTarget) {
        s.setShouldBlock(false);
        LivingEntity current = s.getTarget();
        boolean keep = !forceTarget && current != null && current.isAlive() && !Surrender.is(current)
                && s.distanceTo(current) <= KEEP_FIGHTING;
        if (!keep) {
            LivingEntity t = nearest(s, enemies);
            if (t != null && s.canAttack(t)) s.setTarget(t);
        }
        if (s.getFollowState() != WANDER) s.setFollowState(WANDER);
        m.orders.put(s.getUUID(), new Order(WANDER, null));
    }

    /** A formarse en {@code pos}: estado 3 de Recruits con ese puesto (no el 2: pisa el puesto con la posición actual). */
    private static void moveTo(Memory m, AbstractRecruitEntity s, Vec3 pos) {
        Vec3 hold = s.getHoldPos();
        if (hold == null || hold.distanceToSqr(pos) > MOVE_THRESHOLD * MOVE_THRESHOLD || s.getFollowState() != BACK_TO_POS) {
            if (roleOf(s) != Role.RANGED) s.setTarget(null);
            s.setHoldPos(pos);
            s.setFollowState(BACK_TO_POS);
            hold = pos;
        }
        m.orders.put(s.getUUID(), new Order(BACK_TO_POS, hold));
    }

    /** Arquero: si no tiene a quién tirarle, el enemigo más cercano a tiro. */
    private static void aim(AbstractRecruitEntity s, List<LivingEntity> enemies) {
        LivingEntity current = s.getTarget();
        if (current != null && current.isAlive() && !Surrender.is(current) && s.distanceTo(current) <= RANGED_REACH) return;
        LivingEntity t = nearest(s, enemies);
        if (t != null && s.distanceTo(t) <= RANGED_REACH && s.canAttack(t)) s.setTarget(t);
    }

    private static boolean isFighting(AbstractRecruitEntity s) {
        LivingEntity t = s.getTarget();
        return t != null && t.isAlive() && !Surrender.is(t) && s.distanceTo(t) <= KEEP_FIGHTING && roleOf(s) != Role.RANGED;
    }

    // ------------------------------------------------------------------ geometría

    /**
     * Puestos de una línea de {@code ranks} filas con centro en {@code front}, perpendicular a {@code dir}. La
     * primera fila es la de adelante. Cada puesto sobre el piso (si no se encuentra, a la altura de {@code front}).
     */
    public static List<Vec3> linePositions(ServerLevel level, Vec3 front, Vec3 dir, int count, int ranks) {
        List<Vec3> out = new ArrayList<>(count);
        if (count <= 0) return out;
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        int perRank = (int) Math.ceil(count / (double) Math.max(1, ranks));
        for (int i = 0; i < count; i++) {
            int rank = i / perRank, col = i % perRank;
            int inThisRank = Math.min(perRank, count - rank * perRank);
            double along = (col - (inThisRank - 1) / 2.0) * SPACING;
            Vec3 p = front.add(perp.scale(along)).subtract(dir.scale(rank * RANK_GAP));
            Integer y = Ground.surfaceY(level, (int) Math.floor(p.x), (int) Math.floor(p.z), (int) Math.floor(front.y), 4, 8);
            out.add(new Vec3(p.x, y != null ? y : front.y, p.z));
        }
        return out;
    }

    /**
     * Ordena a los soldados para que cada uno vaya al puesto que le queda más a mano: los más adelantados a la
     * primera fila, y en cada fila de izquierda a derecha. Así no se cruzan.
     */
    private static void assign(List<AbstractRecruitEntity> soldiers, List<Vec3> slots, Vec3 dir) {
        if (soldiers.size() < 2) return;
        Vec3 perp = new Vec3(-dir.z, 0, dir.x);
        soldiers.sort(Comparator.comparingDouble(s -> -s.position().dot(dir)));
        // Las filas, en el orden de los puestos (adelante primero)
        int i = 0;
        while (i < slots.size()) {
            int j = i;
            double rankDepth = slots.get(i).dot(dir);
            while (j < slots.size() && Math.abs(slots.get(j).dot(dir) - rankDepth) < 0.5) j++;
            List<AbstractRecruitEntity> rank = soldiers.subList(i, Math.min(j, soldiers.size()));
            rank.sort(Comparator.comparingDouble(s -> s.position().dot(perp)));
            i = j;
        }
    }

    /** Lo más alto del castillo donde pararse (murallas y torres), repartido. */
    public static List<Vec3> wallPosts(ServerLevel level, BlockPos anchor) {
        List<Vec3> candidates = new ArrayList<>();
        for (int dx = -21; dx <= 21; dx += 2) {
            for (int dz = -22; dz <= 22; dz += 2) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                Integer y = Ground.surfaceY(level, x, z, anchor.getY() + 26, 0, 26 - WALL_MIN_HEIGHT);
                if (y == null || y < anchor.getY() + WALL_MIN_HEIGHT) continue;
                candidates.add(new Vec3(x + 0.5, y, z + 0.5));
            }
        }
        candidates.sort(Comparator.comparingDouble(v -> -v.y));
        List<Vec3> posts = new ArrayList<>();
        for (Vec3 c : candidates) {
            if (posts.size() >= WALL_POSTS) break;
            boolean apart = true;
            for (Vec3 p : posts) if (p.distanceToSqr(c) < WALL_POST_SPACING * WALL_POST_SPACING) apart = false;
            if (apart) posts.add(c);
        }
        return posts;
    }

    public static Vec3 centroid(List<? extends LivingEntity> side) {
        double x = 0, y = 0, z = 0;
        for (LivingEntity e : side) {
            x += e.getX();
            y += e.getY();
            z += e.getZ();
        }
        int n = Math.max(1, side.size());
        return new Vec3(x / n, y / n, z / n);
    }

    /** Dirección horizontal de {@code from} a {@code to} (largo 1). */
    public static Vec3 horizontalDir(Vec3 from, Vec3 to) {
        Vec3 d = new Vec3(to.x - from.x, 0, to.z - from.z);
        return d.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : d.normalize();
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private static LivingEntity nearest(LivingEntity from, List<LivingEntity> candidates) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            if (!e.isAlive() || Surrender.is(e)) continue;
            double d = from.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static double nearestDistance(LivingEntity from, List<LivingEntity> candidates) {
        LivingEntity n = nearest(from, candidates);
        return n == null ? Double.MAX_VALUE : from.distanceTo(n);
    }

    private static void tell(ServerLevel level, RaidManager.Raid raid, Component msg) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(raid.attacker);
        if (p != null) p.displayClientMessage(msg, true);
    }
}
