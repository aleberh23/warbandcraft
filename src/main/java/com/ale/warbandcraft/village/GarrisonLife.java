package com.ale.warbandcraft.village;

import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.SiegeLanes;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.upgrade.Blueprints;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La vida de las guarniciones de facción en paz (pedido de Ale: "quedan dispersos"). Cada soldado tiene un puesto:
 * los arqueros arriba de las torres (o de los muros del castillo), dos guardias en cada portón de la empalizada, dos que
 * rondan el mercado (pedido de Ale), unos pocos en la plaza (la campana) y el resto patrulla por la aldea con paradas. Si alguno tiene a quién pegarle, pelea
 * y después vuelve a lo suyo. Durante un ataque, una batalla o una invasión no se los toca (los maneja esa pelea).
 */
public final class GarrisonLife {
    /** Guardias por portón; parte de los que quedan que hace guardia en la plaza. */
    public static int GATE_GUARDS = 2;
    public static double PLAZA_SHARE = 0.25;
    /** Guardias que rondan el mercado (después de los portones) y a cuánto del puesto del Mercader caminan. */
    public static int MARKET_GUARDS = 2, MARKET_RADIUS = 7;
    /** Patrulla: cuánto espera en cada punto (ticks, mínimo + al azar) y cuánto le da para llegar. */
    public static int WAIT_MIN = 20 * 6, WAIT_EXTRA = 20 * 12, GIVE_UP = 20 * 45;
    /** Arquero a tanto del pie de su torre: aparece arriba (no trepan escaleras solos). */
    public static double CLIMB_DISTANCE = 6;
    /** Una pelea más lejos que esto del centro (más el radio de patrulla) se deja: vuelven a la aldea. */
    public static int CHASE_MARGIN = 16;

    private enum Kind { TOWER, GATE, MARKET, PLAZA, PATROL, CAPTAIN }

    private static final class Duty {
        Kind kind;
        Vec3 at;
        BlockPos around;   // MARKET: el medio de la ronda
        long since, waitUntil;
    }

    private static final Map<UUID, Duty> DUTIES = new HashMap<>();
    private static final Map<Long, CachedGates> GATES = new HashMap<>();

    private record CachedGates(long at, List<BlockPos> gates) {}

    private GarrisonLife() {}

    public static void clear() {
        DUTIES.clear();
        GATES.clear();
    }

    /** Cada 2 s: las guarniciones de las aldeas/castillos de facción cargados y en paz. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        VillageData data = VillageData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            BlockPos c = v.center();
            if (v.isConquered() || data.isTest(c) || !level.isPositionEntityTicking(c)) continue;
            if (RaidManager.get(level, c) != null || InvasionData.get(level).battleAt(c) != null) continue;   // lo maneja la pelea
            List<AbstractRecruitEntity> garrison = AiWars.defenders(level, c, v.faction());
            if (garrison.isEmpty()) continue;
            garrison.sort(Comparator.comparing(e -> e.getUUID().toString()));   // siempre el mismo orden: mismos puestos
            assign(level, v, garrison, now);
            for (AbstractRecruitEntity s : garrison) {
                seen.add(s.getUUID());
                act(level, v, s, now);
            }
        }
        DUTIES.keySet().retainAll(seen);
    }

    /** Reparte los puestos: torres a los arqueros, portones, plaza y patrulla. Un puesto que sigue igual no se toca. */
    private static void assign(ServerLevel level, VillageData.Village v, List<AbstractRecruitEntity> garrison, long now) {
        List<BlockPos> towers = new ArrayList<>(Quarters.posts(level, v));
        List<BlockPos> gates = v.castle() ? List.of() : gates(level, v, now);
        List<AbstractRecruitEntity> rest = new ArrayList<>();
        for (AbstractRecruitEntity s : garrison) {
            if (s.getPersistentData().getBoolean(CastleManager.TAG_CAPTAIN)) {
                set(s, Kind.CAPTAIN, Vec3.atBottomCenterOf(v.center()), now);
                continue;
            }
            boolean archer = BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED;
            if (archer && !towers.isEmpty()) {
                set(s, Kind.TOWER, Vec3.atBottomCenterOf(towers.remove(0)), now);
                continue;
            }
            rest.add(s);
        }
        int gateSlots = gates.size() * GATE_GUARDS;
        BlockPos market = v.castle() ? null : com.ale.warbandcraft.market.Market.workplace(level, v);
        int marketSlots = market == null ? 0 : Math.min(MARKET_GUARDS, Math.max(0, rest.size() - gateSlots));
        int plaza = (int) Math.ceil(Math.max(0, rest.size() - gateSlots - marketSlots) * PLAZA_SHARE);
        for (int i = 0; i < rest.size(); i++) {
            AbstractRecruitEntity s = rest.get(i);
            if (i < gateSlots) {
                BlockPos g = gates.get(i / GATE_GUARDS);
                int side = i % GATE_GUARDS == 0 ? 1 : -1;
                Vec3 inward = SiegeLanes.horizontal(Vec3.atBottomCenterOf(g), Vec3.atBottomCenterOf(v.center())) < 1 ? Vec3.ZERO
                        : Vec3.atBottomCenterOf(v.center()).subtract(Vec3.atBottomCenterOf(g)).multiply(1, 0, 1).normalize();
                Vec3 across = new Vec3(-inward.z, 0, inward.x).scale(2.0 * side);
                set(s, Kind.GATE, Vec3.atBottomCenterOf(g).add(inward.scale(2)).add(across), now);
            } else if (i < gateSlots + marketSlots) {
                Duty d = DUTIES.get(s.getUUID());
                if (d == null || d.kind != Kind.MARKET || !market.equals(d.around)) {
                    d = new Duty();
                    d.kind = Kind.MARKET;
                    d.around = market;
                    d.since = now - GIVE_UP;   // busca un punto ya
                    DUTIES.put(s.getUUID(), d);
                }
            } else if (i < gateSlots + marketSlots + plaza) {
                int k = i - gateSlots - marketSlots;
                double a = k * 2.4;   // alrededor de la campana
                set(s, Kind.PLAZA, Vec3.atBottomCenterOf(v.center()).add(Math.cos(a) * 4, 0, Math.sin(a) * 4), now);
            } else {
                Duty d = DUTIES.get(s.getUUID());
                if (d == null || d.kind != Kind.PATROL) {
                    d = new Duty();
                    d.kind = Kind.PATROL;
                    d.since = now - GIVE_UP;   // busca un punto ya
                    DUTIES.put(s.getUUID(), d);
                }
            }
        }
    }

    private static void set(AbstractRecruitEntity s, Kind kind, Vec3 at, long now) {
        Duty d = DUTIES.get(s.getUUID());
        if (d != null && d.kind == kind && d.at != null && d.at.distanceToSqr(at) < 1) return;
        d = new Duty();
        d.kind = kind;
        d.at = at;
        d.since = now;
        DUTIES.put(s.getUUID(), d);
    }

    /** Un soldado: si tiene a quién pegarle (cerca de la aldea), pelea; si no, a su puesto o a patrullar. */
    private static void act(ServerLevel level, VillageData.Village v, AbstractRecruitEntity s, long now) {
        Duty d = DUTIES.get(s.getUUID());
        if (d == null) return;
        int radius = v.castle() ? CastleManager.PATROL_RADIUS : VillageManager.PATROL_RADIUS;
        LivingEntity t = s.getTarget();
        if (t != null && t.isAlive()) {
            boolean far = SiegeLanes.dist(t, v.center()) > radius + CHASE_MARGIN;
            if (t instanceof Creeper || far) {
                s.setTarget(null);
            } else {
                if (d.kind != Kind.TOWER && s.getFollowState() != 0) s.setFollowState(0);   // de a pie: a pelear
                return;   // el de la torre tira desde arriba
            }
        }
        switch (d.kind) {
            case TOWER -> {
                double dx = s.getX() - d.at.x, dz = s.getZ() - d.at.z;
                if (dx * dx + dz * dz <= CLIMB_DISTANCE * CLIMB_DISTANCE && Math.abs(s.getY() - d.at.y) > 1.5) {
                    if (s.isPassenger()) s.stopRiding();
                    s.teleportTo(d.at.x, d.at.y, d.at.z);
                    s.getNavigation().stop();
                }
                hold(s, d.at);
            }
            case GATE, PLAZA, CAPTAIN -> hold(s, d.at);
            case PATROL -> patrol(level, v.center(), s, d, radius - 4, now);
            case MARKET -> patrol(level, d.around, s, d, MARKET_RADIUS, now);
        }
    }

    private static void patrol(ServerLevel level, BlockPos around, AbstractRecruitEntity s, Duty d, int radius, long now) {
        boolean arrived = d.at != null && SiegeLanes.horizontal(s.position(), d.at) <= 2.5;
        if (arrived) {
            if (d.waitUntil == 0) d.waitUntil = now + WAIT_MIN + level.getRandom().nextInt(WAIT_EXTRA);
            if (now < d.waitUntil) {
                hold(s, d.at);
                return;
            }
        } else if (d.at != null && now - d.since < GIVE_UP) {
            hold(s, d.at);
            return;
        }
        BlockPos next = randomPoint(level, around, radius, level.getRandom());
        if (next == null) return;
        d.at = Vec3.atBottomCenterOf(next);
        d.since = now;
        d.waitUntil = 0;
        hold(s, d.at);
    }

    private static void hold(AbstractRecruitEntity s, Vec3 at) {
        Vec3 h = s.getHoldPos();
        if (s.getFollowState() != 3 || h == null || h.distanceToSqr(at) > 1) {
            s.setHoldPos(at);
            s.setFollowState(3);
        }
    }

    @Nullable
    private static BlockPos randomPoint(ServerLevel level, BlockPos center, int radius, RandomSource r) {
        for (int i = 0; i < 10; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.min(4, radius / 2.0) + r.nextDouble() * Math.max(1, radius - Math.min(4, radius / 2.0));
            int x = center.getX() + (int) Math.round(Math.cos(a) * d), z = center.getZ() + (int) Math.round(Math.sin(a) * d);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            Integer y = Ground.surfaceY(level, x, z, center.getY(), 16, 16);
            if (y == null) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (Ground.canStand(level, p) && level.getFluidState(p.below()).isEmpty()) return p;
        }
        return null;
    }

    /** Los portones de la empalizada/muralla de anillo: los huecos que dejó sobre los caminos (medio de cada uno). */
    public static List<BlockPos> gates(ServerLevel level, VillageData.Village v, long now) {
        CachedGates c = GATES.get(v.center().asLong());
        if (c != null && now - c.at() < 20 * 60) return c.gates();
        List<BlockPos> out = new ArrayList<>();
        TownData.Town t = TownData.get(level).at(v.center());
        if (t != null && t.ringWalls && t.ringRadius > 0) {
            List<int[]> cols = Blueprints.ringColumns(v.center(), t.ringRadius);
            int n = cols.size();
            boolean[] path = new boolean[n];
            Integer[] ys = new Integer[n];
            for (int i = 0; i < n; i++) {
                int x = cols.get(i)[0], z = cols.get(i)[1];
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                ys[i] = Blueprints.groundY(level, x, z, v.center().getY());
                path[i] = ys[i] != null && level.getBlockState(new BlockPos(x, ys[i], z)).is(Blocks.DIRT_PATH);
            }
            int start = -1;
            for (int i = 0; i < n; i++) if (!path[i]) start = i;   // empezar después de un tramo sin camino
            if (start >= 0) {
                for (int k = 1; k <= n; k++) {
                    int i = (start + k) % n;
                    if (!path[i]) continue;
                    int len = 0;
                    while (len < n && path[(i + len) % n]) len++;
                    int mid = (i + len / 2) % n;
                    out.add(new BlockPos(cols.get(mid)[0], ys[mid] + 1, cols.get(mid)[1]));
                    k += len;
                }
            }
        }
        GATES.put(v.center().asLong(), new CachedGates(now, out));
        return out;
    }
}
