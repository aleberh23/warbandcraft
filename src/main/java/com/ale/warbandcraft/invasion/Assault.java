package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.SiegeLanes;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.nav.Mover;
import com.ale.warbandcraft.nav.NavCaps;
import com.ale.warbandcraft.village.CastleManager;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * El asalto de una invasión de verdad (la pelea real de {@link Invasions}), al estilo de los asedios de Bannerlord. Los
 * carriles, las escaleras, los zapadores, el cañón y los grupos los maneja {@link SiegeLanes}; acá está lo que es de la
 * invasión: aparecer afuera (en lados a los que se llega por tierra), el general, formar antes del asalto, la moral de
 * cada soldado (el que se quiebra huye) y la retirada del ejército.
 * <ul>
 *   <li>Incursión ({@link InvasionData.Kind#RAID}): un carril, sin general, sin formar.</li>
 *   <li>Ejército ({@link InvasionData.Kind#ARMY}): general atrás con su estandarte, 2 zapadores (TNT y cañón), 2 o 3
 *       carriles. Si cae el general pelean sin orden y se desmoralizan.</li>
 * </ul>
 * En un asedio todos pelean de a pie. Lo que decide el general no se guarda (se recalcula); las escaleras puestas sí
 * ({@link InvasionData.Invasion#ladders}).
 */
public final class Assault {
    /** Marca del invasor: su carril (0, 1, 2). */
    public static final String TAG_GROUP = "warbandcraft:assault_group";
    /** Marca del general del ejército. */
    public static final String TAG_GENERAL = "warbandcraft:general";

    public static int STAGE_MARGIN = SiegeLanes.STAGE_MARGIN;
    /** Separación entre carriles (grados) y cuántos: 3 con un ejército de al menos tantos soldados. */
    public static double GROUP_SPREAD_DEG = 70;
    public static int THREE_GROUPS_FROM = 14;
    /** Ejército: zapadores (y TNT extra de cada uno), cuánto forma antes del asalto. */
    public static int ARMY_SAPPERS = 2, SAPPER_TNT = 10, FORM_TICKS = 15 * 20;
    /** Se retiran cuando quedan tantos (de los que aparecieron): incursión, ejército, ejército sin general. */
    public static double RETREAT_RAID = 0.3, RETREAT_ARMY = 0.4, RETREAT_NO_GENERAL = 0.6;
    /** Si en tanto tiempo no ganan, levantan el sitio. */
    public static int SIEGE_TIMEOUT = 20 * 60 * 6;
    /** Retirada: hasta dónde corren y cuánto esperan antes de irse. */
    public static int RETREAT_DISTANCE = 40, RETREAT_TICKS = 20 * 20;
    /**
     * Moral: al empezar, lo que baja por cada compañero que cae a {@link #MORALE_RADIUS}, por el general, por poca vida
     * (por segundo), lo que sube por segundo cerca del general, y por debajo de cuánto huye.
     */
    public static float MORALE_START = 100, MORALE_DEATH = 7, MORALE_GENERAL = 25, MORALE_WOUNDED = 1.5F,
            MORALE_GENERAL_AURA = 1, MORALE_BREAK = 20;
    public static double MORALE_RADIUS = 12, GENERAL_AURA = 24;

    private static final int PASSIVE = 3;
    private static final String BY = "assault";

    public enum Phase { FORM, ASSAULT, RETREAT }

    private static final class Memory {
        Phase phase = Phase.ASSAULT;
        long since, assaultSince;
        final SiegeLanes.State lanes = new SiegeLanes.State();
        boolean generalDead;
        final Map<UUID, Float> morale = new HashMap<>();
        final Map<UUID, Vec3> lastPos = new HashMap<>();
        final Set<UUID> fleeing = new HashSet<>();
    }

    private static final Map<String, Memory> MEMORY = new HashMap<>();

    private Assault() {}

    private static String key(ServerLevel level, BlockPos target) {
        return level.dimension().location() + "@" + target.asLong();
    }

    public static void clear() {
        MEMORY.clear();
        com.ale.warbandcraft.battle.SiegePush.clear();
    }

    /** ¿Los atacantes de la invasión a {@code center} están en el destrabe (todos a buscarse)? */
    public static boolean pushing(ServerLevel level, BlockPos center) {
        return com.ale.warbandcraft.battle.SiegePush.peek(key(level, center)) != com.ale.warbandcraft.battle.SiegePush.Stage.NORMAL;
    }

    // ------------------------------------------------------------------ aparecer

    /** Hace aparecer el ejército afuera de la aldea, repartido en carriles. Devuelve los soldados. */
    public static List<AbstractRecruitEntity> spawn(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, Faction f) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        RandomSource random = level.getRandom();
        Breach.Walls w = Breach.walls(level, v);
        BlockPos center = v.center();
        double base = Math.atan2(inv.from.getZ() - center.getZ(), inv.from.getX() - center.getX());
        if (inv.from.equals(center)) base = random.nextDouble() * Math.PI * 2;
        boolean army = inv.kind == InvasionData.Kind.ARMY;
        int groups = !army ? 1 : inv.size >= THREE_GROUPS_FROM ? 3 : 2;

        Memory m = new Memory();
        m.phase = army ? Phase.FORM : Phase.ASSAULT;
        m.since = m.assaultSince = level.getGameTime();
        // Pedido de Ale: siempre afuera del muro, del lado más débil (hueco, pocos defensores), no siempre del mismo lado
        BlockPos[] stage = com.ale.warbandcraft.battle.SiegePush.stages(level, center, w, base, groups, defenders(level, inv, w), random)
                .toArray(new BlockPos[0]);
        for (int g = 0; g < groups; g++) {
            // Si tuvo que correrse (agua, del otro lado de un río), ataca desde donde quedó
            m.lanes.add(SiegeLanes.angleOf(center, Vec3.atBottomCenterOf(stage[g])), stage[g]);
        }
        MEMORY.put(key(level, center), m);

        int restrict = w.radius() + STAGE_MARGIN + RETREAT_DISTANCE;
        int total = inv.size;
        int soldiers = Math.min(total, com.ale.warbandcraft.battle.BattleSize.MAX_PER_SIDE);   // H23: el resto entra en oleadas
        inv.reserve = total - soldiers;
        if (army) {
            AbstractRecruitEntity general = VillageManager.spawnFixed(level, behind(level, center, m.lanes.lane(0).angle, w), f,
                    new ResourceLocation("recruits", "captain"), CastleManager.captainEquipment(), List.of(), false,
                    "General " + f.soldierSuffix(), random, 2, restrict);
            if (general != null) {
                general.getPersistentData().putBoolean(TAG_GENERAL, true);
                general.setItemSlot(EquipmentSlot.OFFHAND, f.banner().copy());
                mark(general, center, 0, restrict);
                out.add(general);
                soldiers--;
            }
            Troop sapper = new Troop("Zapador", new ResourceLocation("warbandcraft", "zapador"), 0, 1, null, Map.of(),
                    Optional.empty(), Optional.empty(), true);
            for (int i = 0; i < ARMY_SAPPERS && soldiers > 1; i++) {
                int g = i % groups;
                AbstractRecruitEntity s = VillageManager.spawnTroop(level, stage[g], f, sapper, random, 3, restrict);
                if (s == null) continue;
                s.getInventory().addItem(new ItemStack(Items.TNT, SAPPER_TNT));
                mark(s, center, g, restrict);
                out.add(s);
                soldiers--;
            }
        }
        for (int i = 0; i < soldiers; i++) {
            Troop troop = f.pickTroop(random);
            if (troop == null) break;
            int g = i % groups;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, stage[g], f, troop, random, 4, restrict);
            if (s == null) continue;
            mark(s, center, g, restrict);
            out.add(s);
        }
        inv.startSize = out.size() + inv.reserve;
        for (AbstractRecruitEntity s : out) {
            m.morale.put(s.getUUID(), MORALE_START);
            SiegeLanes.dismount(s, false);   // en un asedio, todos de a pie
        }
        if (army) {
            tell(level, inv, Component.translatable("msg.warbandcraft.assault.forming", Invasions.factionName(inv.faction),
                    Invasions.placeName(level, inv), groups).withStyle(ChatFormatting.RED));
        }
        return out;
    }

    /**
     * H23: oleadas. Si hay lugar en el campo ({@link com.ale.warbandcraft.battle.BattleSize}), los que esperan aparecen
     * en el lugar de partida de un carril. Devuelve los que entraron.
     */
    public static List<AbstractRecruitEntity> reinforce(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, int onField) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        int n = com.ale.warbandcraft.battle.BattleSize.room(onField, inv.reserve);
        Faction f = com.ale.warbandcraft.faction.FactionManager.INSTANCE.get(inv.faction);
        if (n <= 0 || f == null) return out;
        Memory m = MEMORY.get(key(level, inv.target));
        if (m != null && m.phase == Phase.RETREAT) return out;
        Breach.Walls w = Breach.walls(level, v);
        BlockPos c = v.center();
        int restrict = w.radius() + STAGE_MARGIN + RETREAT_DISTANCE;
        RandomSource random = level.getRandom();
        int lanes = m == null ? 1 : Math.max(1, m.lanes.lanes.size());
        for (int i = 0; i < n; i++) {
            inv.reserve--;
            int g = random.nextInt(lanes);
            BlockPos stage;
            if (m != null) {
                stage = m.lanes.lane(g).stage;
            } else {
                double angle = Math.atan2(inv.from.getZ() - c.getZ(), inv.from.getX() - c.getX());
                stage = BlockPos.containing(Breach.dryNear(level, Breach.ground(level, Breach.at(c, angle, w.radius() + STAGE_MARGIN), c.getY())));
            }
            Troop troop = f.pickTroop(random);
            if (troop == null) continue;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, stage, f, troop, random, 4, restrict);
            if (s == null) continue;
            mark(s, c, g, restrict);
            SiegeLanes.dismount(s, false);
            if (m != null) m.morale.put(s.getUUID(), MORALE_START);
            out.add(s);
        }
        InvasionData.get(level).changed();
        return out;
    }

    private static void mark(AbstractRecruitEntity s, BlockPos center, int group, int restrict) {
        s.getPersistentData().putLong(VillageManager.TAG_VILLAGE, center.asLong());
        s.getPersistentData().putLong(Invasions.TAG_INVADER, center.asLong());
        s.getPersistentData().putInt(TAG_GROUP, group);
        s.restrictTo(center, restrict);
    }

    /** El general se queda detrás del carril principal. */
    private static BlockPos behind(ServerLevel level, BlockPos center, double angle, Breach.Walls w) {
        Vec3 p = Breach.dryNear(level, Breach.ground(level, Breach.at(center, angle, w.radius() + STAGE_MARGIN + 8), center.getY()));
        BlockPos b = BlockPos.containing(p);
        return level.isPositionEntityTicking(b) ? b : BlockPos.containing(Breach.ground(level, Breach.at(center, angle, w.radius() + STAGE_MARGIN),
                center.getY()));
    }

    // ------------------------------------------------------------------ cada segundo

    /**
     * Una vuelta del general del asalto. Devuelve true si la invasión terminó acá (se retiraron): ya avisó y la sacó.
     */
    public static boolean tick(ServerLevel level, InvasionData.Invasion inv, VillageData.Village v, List<AbstractRecruitEntity> invaders) {
        if (invaders.isEmpty()) return false;
        long now = level.getGameTime();
        Breach.Walls w = Breach.walls(level, v);
        Memory m = MEMORY.computeIfAbsent(key(level, inv.target), k -> rebuild(level, inv, invaders, w, now));
        BlockPos center = v.center();
        List<LivingEntity> enemies = defenders(level, inv, w);
        boolean army = inv.kind == InvasionData.Kind.ARMY;

        AbstractRecruitEntity general = null;
        for (AbstractRecruitEntity s : invaders) {
            if (m.phase != Phase.RETREAT) SiegeLanes.dismount(s, false);
            if (s.getPersistentData().getBoolean(TAG_GENERAL)) general = s;
        }
        if (army && general == null && !m.generalDead) {
            m.generalDead = true;
            for (Map.Entry<UUID, Float> e : m.morale.entrySet()) e.setValue(e.getValue() - MORALE_GENERAL);
            tell(level, inv, Component.translatable("msg.warbandcraft.assault.general_down", Invasions.factionName(inv.faction))
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }

        // ¿Se retira todo el ejército?
        if (m.phase != Phase.RETREAT) {
            double left = (invaders.size() + inv.reserve) / (double) Math.max(1, inv.startSize);
            double limit = !army ? RETREAT_RAID : m.generalDead ? RETREAT_NO_GENERAL : RETREAT_ARMY;
            if (inv.startSize > 0 && left <= limit || m.phase == Phase.ASSAULT && now - m.assaultSince > SIEGE_TIMEOUT) {
                m.phase = Phase.RETREAT;
                m.since = now;
                if (inv.reserve > 0) {   // H23: los que esperaban ni llegan a entrar: vuelven a la leva
                    InvasionData data = InvasionData.get(level);
                    data.setLevy(inv.faction, Math.min(Invasions.LEVY_CAP, data.levy(inv.faction) + inv.reserve));
                    inv.size = Math.max(0, inv.size - inv.reserve);
                    inv.reserve = 0;
                    data.changed();
                }
                for (AbstractRecruitEntity s : invaders) flee(level, m, s, center, w);
                SiegeLanes.end(level, host(level, inv), m.lanes);
                tell(level, inv, Component.translatable("msg.warbandcraft.assault.retreat", Invasions.factionName(inv.faction))
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            }
        }
        if (m.phase == Phase.RETREAT) return retreat(level, inv, m, w, invaders, now);

        morale(level, m, invaders, general, center, w);
        List<AbstractRecruitEntity> fighting = new ArrayList<>();
        for (AbstractRecruitEntity s : invaders) if (!m.fleeing.contains(s.getUUID())) fighting.add(s);
        runAway(level, inv, m, invaders, center, w);
        if (fighting.isEmpty()) return false;

        if (m.phase == Phase.FORM) {
            boolean contact = false;
            for (AbstractRecruitEntity s : fighting) if (SiegeLanes.nearestDistance(s, enemies) <= SiegeLanes.ENGAGE * 2) contact = true;
            if (now - m.since >= FORM_TICKS || contact) {
                m.phase = Phase.ASSAULT;
                m.assaultSince = now;
                tell(level, inv, Component.translatable("msg.warbandcraft.assault.charge", Invasions.factionName(inv.faction))
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
        }

        // Pedido de Ale: que no se queden parados. Sin daño un rato, todos a buscar al enemigo; mucho más, se retiran.
        if (m.phase == Phase.ASSAULT) {
            com.ale.warbandcraft.battle.SiegePush.Stage stage = com.ale.warbandcraft.battle.SiegePush.watch(key(level, center), now, fighting, enemies);
            if (stage == com.ale.warbandcraft.battle.SiegePush.Stage.GIVE_UP) {
                m.assaultSince = now - SIEGE_TIMEOUT - 1;   // el próximo segundo, retirada
                return false;
            }
            if (stage == com.ale.warbandcraft.battle.SiegePush.Stage.PUSH) {
                if (enemies.isEmpty()) {
                    for (AbstractRecruitEntity s : fighting) SiegeLanes.moveTo(s, Vec3.atBottomCenterOf(host(level, inv).objective()));
                } else {
                    com.ale.warbandcraft.battle.SiegePush.huntAll(level, fighting, enemies, center, com.ale.warbandcraft.nav.NavCaps.INVADER);
                }
                return false;
            }
        }

        boolean ordered = army && !m.generalDead;
        if (general != null) commandGeneral(level, m, general, center, w, enemies);
        SiegeLanes.Host host = host(level, inv);
        for (SiegeLanes.Lane lane : m.lanes.lanes) {
            List<AbstractRecruitEntity> members = new ArrayList<>();
            for (AbstractRecruitEntity s : fighting) if (s != general && laneOf(m, s) == lane) members.add(s);
            if (m.phase == Phase.FORM) SiegeLanes.form(level, lane, center, members, enemies);
            else SiegeLanes.tick(level, host, m.lanes, lane, members, enemies, w, ordered, now);
        }
        return false;
    }

    /** Lo que es de la invasión para los carriles: dónde se guardan las escaleras y los avisos al dueño. */
    private static SiegeLanes.Host host(ServerLevel level, InvasionData.Invasion inv) {
        return new SiegeLanes.Host() {
            @Override
            public BlockPos center() {
                return inv.target;
            }

            @Override
            public List<Long> ladders() {
                return inv.ladders;
            }

            @Override
            public void changed() {
                InvasionData.get(level).changed();
            }

            @Override
            public boolean cannons() {
                return inv.kind == InvasionData.Kind.ARMY;
            }

            @Override
            public void announce(SiegeLanes.Event e) {
                String key = switch (e) {
                    case LADDERS -> "msg.warbandcraft.assault.ladders";
                    case BLAST -> "msg.warbandcraft.assault.blast";
                    case BOOM -> "msg.warbandcraft.assault.boom";
                    case CANNON -> "msg.warbandcraft.assault.cannon";
                    case TUNNEL -> "msg.warbandcraft.assault.tunnel";
                    case TUNNEL_DONE -> "msg.warbandcraft.assault.tunnel_done";
                };
                tell(level, inv, Component.translatable(key).withStyle(e == SiegeLanes.Event.BOOM ? ChatFormatting.RED : ChatFormatting.GOLD));
            }

            @Override
            public String name() {
                return "tu aldea " + inv.target.toShortString();
            }

            @Override
            public boolean villagers() {
                return true;
            }

            @Override
            public BlockPos objective() {
                VillageData.Village v = VillageData.get(level).at(inv.target);
                BlockPos store = v == null ? inv.target : Invasions.storehouse(level, v);
                return store.equals(inv.target) ? store
                        : BlockPos.containing(com.ale.warbandcraft.camp.Barracks.standable(level, store, null, store));
            }
        };
    }

    /** La memoria de un asalto que ya estaba en marcha (se volvió a cargar el mundo): carriles por las marcas. */
    private static Memory rebuild(ServerLevel level, InvasionData.Invasion inv, List<AbstractRecruitEntity> invaders, Breach.Walls w, long now) {
        Memory m = new Memory();
        m.since = m.assaultSince = now;
        BlockPos c = inv.target;
        Map<Integer, List<AbstractRecruitEntity>> byGroup = new HashMap<>();
        for (AbstractRecruitEntity s : invaders) {
            byGroup.computeIfAbsent(s.getPersistentData().getInt(TAG_GROUP), k -> new ArrayList<>()).add(s);
            m.morale.put(s.getUUID(), MORALE_START);
        }
        int max = byGroup.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        double fallback = Math.atan2(inv.from.getZ() - c.getZ(), inv.from.getX() - c.getX());
        for (int g = 0; g <= Math.min(max, 2); g++) {
            List<AbstractRecruitEntity> list = byGroup.getOrDefault(g, List.of());
            Vec3 at = list.isEmpty() ? null : BattleGeneral.centroid(list);
            double angle = at == null || at.distanceToSqr(Vec3.atCenterOf(c)) < 9 ? fallback : SiegeLanes.angleOf(c, at);
            BlockPos stage = BlockPos.containing(Breach.dryNear(level, Breach.ground(level, Breach.at(c, angle, w.radius() + STAGE_MARGIN), c.getY())));
            m.lanes.add(angle, stage);
        }
        return m;
    }

    private static SiegeLanes.Lane laneOf(Memory m, AbstractRecruitEntity s) {
        return m.lanes.lane(s.getPersistentData().getInt(TAG_GROUP));
    }

    /** Los tuyos que defienden: el dueño (si está cerca) y sus soldados. */
    private static List<LivingEntity> defenders(ServerLevel level, InvasionData.Invasion inv, Breach.Walls w) {
        double r = w.radius() + 64;
        List<LivingEntity> out = new ArrayList<>(level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(inv.target).inflate(r, 48, r),
                e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), inv.owner) && Soldiers.isSoldier(e) && !Surrender.is(e)));
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null && p.level() == level && p.isAlive() && !p.isSpectator() && !p.isCreative()
                && VillageData.horizontalDistSq(p.blockPosition(), inv.target) <= r * r) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ moral

    /** La moral de cada uno: compañeros que caen cerca, el general, la vida que le queda. */
    private static void morale(ServerLevel level, Memory m, List<AbstractRecruitEntity> invaders, @Nullable AbstractRecruitEntity general,
                               BlockPos center, Breach.Walls w) {
        Set<UUID> alive = new HashSet<>();
        for (AbstractRecruitEntity s : invaders) alive.add(s.getUUID());
        for (Map.Entry<UUID, Vec3> e : new ArrayList<>(m.lastPos.entrySet())) {
            if (alive.contains(e.getKey())) continue;
            m.lastPos.remove(e.getKey());
            m.morale.remove(e.getKey());
            if (m.fleeing.remove(e.getKey())) continue;   // se fue huyendo: no es una baja
            for (AbstractRecruitEntity s : invaders) {
                if (s.position().distanceToSqr(e.getValue()) <= MORALE_RADIUS * MORALE_RADIUS) m.morale.merge(s.getUUID(), -MORALE_DEATH, Float::sum);
            }
        }
        for (AbstractRecruitEntity s : invaders) {
            m.lastPos.put(s.getUUID(), s.position());
            float mo = m.morale.getOrDefault(s.getUUID(), MORALE_START);
            if (s.getHealth() < s.getMaxHealth() * 0.35F) mo -= MORALE_WOUNDED;
            if (general != null && general != s && s.distanceToSqr(general) <= GENERAL_AURA * GENERAL_AURA) mo = Math.min(MORALE_START, mo + MORALE_GENERAL_AURA);
            m.morale.put(s.getUUID(), mo);
            if (mo <= MORALE_BREAK && !m.fleeing.contains(s.getUUID()) && !s.getPersistentData().getBoolean(TAG_GENERAL)) {
                flee(level, m, s, center, w);
            }
        }
    }

    /** Huye: corre hacia afuera, sin pelear. */
    private static void flee(ServerLevel level, Memory m, AbstractRecruitEntity s, BlockPos center, Breach.Walls w) {
        m.fleeing.add(s.getUUID());
        s.setTarget(null);
        s.setAggroState(PASSIVE);
        s.setShouldBlock(false);
        Vec3 away = Breach.dryNear(level, Breach.ground(level, Breach.at(center, laneOf(m, s).angle, w.radius() + RETREAT_DISTANCE), center.getY()));
        if (!Mover.go(s, away, 3, NavCaps.INVADER, SiegeLanes.bounds(center, w), BY)) SiegeLanes.moveTo(s, away);
    }

    /** Los que huyen y ya están lejos se van (vuelven a la leva). */
    private static void runAway(ServerLevel level, InvasionData.Invasion inv, Memory m, List<AbstractRecruitEntity> invaders, BlockPos center,
                                Breach.Walls w) {
        int gone = 0;
        for (AbstractRecruitEntity s : invaders) {
            if (!m.fleeing.contains(s.getUUID())) continue;
            if (SiegeLanes.dist(s, center) >= w.radius() + RETREAT_DISTANCE - 8
                    || !Mover.moving(s) && SiegeLanes.dist(s, center) > w.radius() + STAGE_MARGIN) {
                leave(s);
                gone++;
            }
        }
        if (gone > 0) {
            InvasionData data = InvasionData.get(level);
            data.setLevy(inv.faction, Math.min(Invasions.LEVY_CAP, data.levy(inv.faction) + gone));
            inv.size = Math.max(0, inv.size - gone);
            inv.startSize = Math.max(inv.size, inv.startSize - gone);   // los que huyeron no cuentan como bajas
            data.changed();
        }
    }

    private static void leave(AbstractRecruitEntity s) {
        Mover.stop(s);
        if (s.getVehicle() instanceof AbstractHorse h) h.discard();
        s.discard();
    }

    // ------------------------------------------------------------------ el general

    /** Se queda atrás con su estandarte; pelea solo si alguien llega hasta él. */
    private static void commandGeneral(ServerLevel level, Memory m, AbstractRecruitEntity g, BlockPos center, Breach.Walls w,
                                       List<LivingEntity> enemies) {
        if (SiegeLanes.nearestDistance(g, enemies) <= 6) {
            SiegeLanes.engage(g, enemies);
            return;
        }
        SiegeLanes.moveTo(g, Vec3.atBottomCenterOf(behind(level, center, m.lanes.lane(0).angle, w)));
    }

    // ------------------------------------------------------------------ retirada

    /** Corren hacia afuera; los que llegan lejos (o pasado el plazo) se van. Devuelve true si ya no queda nadie. */
    private static boolean retreat(ServerLevel level, InvasionData.Invasion inv, Memory m, Breach.Walls w, List<AbstractRecruitEntity> invaders,
                                   long now) {
        int gone = 0;
        for (AbstractRecruitEntity s : invaders) {
            boolean far = SiegeLanes.dist(s, inv.target) >= w.radius() + RETREAT_DISTANCE - 8;
            if (far || now - m.since >= RETREAT_TICKS) {
                leave(s);
                gone++;
            } else if (!Mover.moving(s)) {
                SiegeLanes.moveTo(s, Breach.ground(level, Breach.at(inv.target, laneOf(m, s).angle, w.radius() + RETREAT_DISTANCE), inv.target.getY()));
            }
        }
        if (gone > 0) {
            InvasionData data = InvasionData.get(level);
            data.setLevy(inv.faction, Math.min(Invasions.LEVY_CAP, data.levy(inv.faction) + gone));
            inv.size = Math.max(0, inv.size - gone);
            data.changed();
        }
        if (gone < invaders.size()) return false;
        end(level, inv);
        InvasionData.get(level).remove(inv.target);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null) {
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.invasion.retreated", Invasions.placeName(level, inv),
                    Invasions.factionName(inv.faction)).withStyle(ChatFormatting.GREEN));
        }
        return true;
    }

    // ------------------------------------------------------------------ fin

    /**
     * Terminó la pelea (ganaste, perdiste, se retiraron o se autoresuelve lo que queda): se sacan las escaleras y los
     * cañones, se olvida el plan y los que quedan en pie (si quedan de guarnición) vuelven a patrullar.
     */
    public static void end(ServerLevel level, InvasionData.Invasion inv) {
        Memory m = MEMORY.remove(key(level, inv.target));
        com.ale.warbandcraft.battle.SiegePush.forget(key(level, inv.target));
        SiegeLanes.end(level, host(level, inv), m == null ? null : m.lanes);
        com.ale.warbandcraft.battle.BattleBar.close(Invasions.barKey(level, inv));
        SiegeDefense.end(level, inv);
        if (!level.isPositionEntityTicking(inv.target)) return;
        for (AbstractRecruitEntity s : Invasions.invaders(level, inv.target)) {
            Mover.stop(s);
            s.getPersistentData().remove(TAG_GROUP);
            s.setShouldBlock(false);
            s.setTarget(null);
            s.setFollowState(0);
            s.restrictTo(inv.target, VillageManager.PATROL_RADIUS);
        }
        // Si te rompieron el muro, que sepas que se repara desde Gestión
        VillageData.Village v = VillageData.get(level).at(inv.target);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (v != null && inv.owner.equals(v.owner()) && p != null) {
            com.ale.warbandcraft.upgrade.WallRepair.warning(level, v).ifPresent(p::sendSystemMessage);
        }
    }

    /** Un cañón de asedio que se carga del disco: sobra (el carril arma otro si hace falta). */
    public static boolean keepCannon(ServerLevel level, Entity e) {
        return !SiegeLanes.isSiegeCannon(e);
    }

    /** Los carriles del asedio a {@code target} (para la defensa). Vacío si no hay asedio. */
    public static List<SiegeLanes.LaneInfo> lanes(ServerLevel level, BlockPos target) {
        Memory m = MEMORY.get(key(level, target));
        return SiegeLanes.info(m == null ? null : m.lanes);
    }

    private static void tell(ServerLevel level, InvasionData.Invasion inv, Component msg) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(inv.owner);
        if (p != null) p.displayClientMessage(msg, true);
    }

    /** El carril de un invasor (para las pruebas). */
    public static int groupIndex(Entity e) {
        return e.getPersistentData().getInt(TAG_GROUP);
    }

    /** En qué fase está el asalto, o null si no hay memoria (para las pruebas). */
    @Nullable
    public static Phase phase(ServerLevel level, BlockPos target) {
        Memory m = MEMORY.get(key(level, target));
        return m == null ? null : m.phase;
    }
}
