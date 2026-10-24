package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Patrullas (H15b): grupos chicos de una facción que salen de su aldea o castillo, recorren cuatro puntos alrededor y
 * vuelven. Solo aparecen con un jugador cerca y se van si se queda sola. Si su facción está en guerra con vos, te
 * atacan (como la guarnición). Pegarle a una patrulla no empieza un ataque a la aldea, pero la facción te quiere menos.
 * <p>
 * Patrullas errantes (pedido de Ale): grupos más grandes de cualquier facción (o bandidos) que aparecen lejos de un
 * jugador y cruzan el mapa hacia un asentamiento suyo o de largo, en tramos cortos y en formación. Si se cruzan con
 * enemigos (facción en guerra, bandidos, guarnición enemiga) pelean ({@link AiWars#enemies}); después siguen.
 */
public final class Patrols {
    /** Pedido de Ale: un soldado de patrulla que matás vos (o tus soldados) suelta esmeraldas ahí mismo, para reclutar al comienzo. */
    public static void dropEmeralds(ServerLevel level, AbstractRecruitEntity dead, @Nullable Entity killer) {
        if (!dead.getPersistentData().contains(TAG_PATROL)) return;
        if (com.ale.warbandcraft.raid.RaidManager.responsiblePlayer(killer) == null) return;
        int n = DROP_EMERALDS_MIN + level.random.nextInt(Math.max(1, DROP_EMERALDS_MAX - DROP_EMERALDS_MIN + 1));
        String f = dead.getPersistentData().getString(VillageManager.TAG_FACTION);
        if (f.endsWith(":bandidos")) n += DROP_EMERALDS_BANDIT_BONUS;
        if (n > 0) dead.spawnAtLocation(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, n));
    }

    /** Se revisa cada tanto: jugador a esta distancia de un asentamiento, chance de que salga una patrulla. */
    public static int PLAYER_RANGE = 160;
    public static double SPAWN_CHANCE = 0.3;
    /** Tamaño, radio del recorrido y a qué distancia de un punto se pasa al siguiente. */
    public static int MIN_SIZE = 3, MAX_SIZE = 4, ROUTE_RADIUS = 56, ARRIVE = 5;
    /** Recorrido: cuatro puntos alrededor y vuelta a casa. */
    public static int STEPS = 5;
    /** Opinión que se pierde por pegarle a una patrulla (una vez por soldado). */
    public static int HIT_OPINION = -5;
    /** Patrullas errantes: cada minuto, por jugador, chance de que aparezca una; cuántas puede haber cerca de un jugador. */
    public static double ROAM_CHANCE = 0.35;
    public static int ROAM_MAX_NEAR = 2;
    /** Tamaño de una patrulla errante. */
    public static int ROAM_MIN_SIZE = 4, ROAM_MAX_SIZE = 8;
    /** A qué distancia del jugador aparece una patrulla errante (lejos, para que la veas venir). */
    public static int ROAM_SPAWN_MIN = 72, ROAM_SPAWN_MAX = 120;
    /** Largo del viaje de una patrulla errante que no va a un asentamiento suyo. */
    public static int ROAM_TRIP_MIN = 220, ROAM_TRIP_MAX = 400;
    /** Si hay un asentamiento de su facción entre estas distancias de donde aparece, la patrulla errante va ahí. */
    public static int ROAM_HOME_MIN = 120, ROAM_HOME_RANGE = 500;
    /** Chance de que una patrulla errante sea de bandidos; si no, de que sea la facción del bioma (si no, cualquiera). */
    public static double ROAM_BANDIT_CHANCE = 0.2, ROAM_BIOME_CHANCE = 0.6;
    /** Esmeraldas que suelta un soldado de patrulla al caer a manos tuyas o de tus soldados (entre MIN y MAX; un recluta cuesta 4). */
    public static int DROP_EMERALDS_MIN = 1, DROP_EMERALDS_MAX = 2;
    /** De más para los bandidos (llevan lo robado). */
    public static int DROP_EMERALDS_BANDIT_BONUS = 1;
    /** Patrulla errante: largo de cada tramo, ticks para cambiar un tramo al que no llega, distancia al destino para terminar. */
    public static int ROAM_LEG = 20, ROAM_LEG_TICKS = 1200, ROAM_ARRIVE = 10;
    /** Estado de Recruits: ir a su puesto ({@code setHoldPos}). */
    private static final int BACK_TO_POS = 3;

    public static final String TAG_PATROL = "warbandcraft:patrol";
    private static final String TAG_STEP = "warbandcraft:patrol_step";
    private static final String TAG_ANGLE = "warbandcraft:patrol_angle";
    private static final String TAG_HIT = "warbandcraft:patrol_hit";
    /** Patrulla errante: adónde va (long). Su {@link #TAG_PATROL} es donde apareció (identifica al grupo). */
    private static final String TAG_ROAM = "warbandcraft:patrol_roam";
    private static final String TAG_SLOT = "warbandcraft:patrol_slot";
    /** Patrullas errantes: el tramo que está haciendo cada grupo (por dimensión y {@link #TAG_PATROL}), en memoria. */
    private static final Map<ResourceKey<Level>, Map<Long, Leg>> LEGS = new HashMap<>();

    private record Leg(Vec3 pos, long since) {}

    private Patrols() {}

    /** Cada minuto: con un jugador cerca de un asentamiento sin patrulla, puede salir una; y quizás una errante. */
    public static void spawnNearPlayers(ServerLevel level) {
        RandomSource r = level.getRandom();
        VillageData data = VillageData.get(level);
        double range2 = (double) PLAYER_RANGE * PLAYER_RANGE;
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            if (v.isConquered() || data.isTest(v.center()) || v.isLooted(level.getGameTime())) continue;
            if (!level.isPositionEntityTicking(v.center())) continue;
            boolean near = false;
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && VillageData.horizontalDistSq(p.blockPosition(), v.center()) <= range2) near = true;
            }
            if (!near || !members(level, v.center()).isEmpty() || r.nextDouble() >= SPAWN_CHANCE) continue;
            spawn(level, v);
        }
        for (ServerPlayer p : level.players()) {   // patrullas errantes
            if (p.isSpectator() || r.nextDouble() >= ROAM_CHANCE || roamingNear(level, p.blockPosition()) >= ROAM_MAX_NEAR) continue;
            spawnRoaming(level, p, null, ROAM_SPAWN_MIN, ROAM_SPAWN_MAX);
        }
    }

    /** Sale una patrulla de la aldea/castillo {@code v}. */
    public static List<AbstractRecruitEntity> spawn(ServerLevel level, VillageData.Village v) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        Faction f = FactionManager.INSTANCE.get(v.faction());
        if (f == null) return out;
        RandomSource r = level.getRandom();
        float angle = r.nextFloat() * 360F;
        int n = MIN_SIZE + r.nextInt(MAX_SIZE - MIN_SIZE + 1);
        for (int i = 0; i < n; i++) {
            Troop troop = f.pickTroop(r);
            if (troop == null) break;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, v.center(), f, troop, r, 4, ROUTE_RADIUS + 16);
            if (s == null) continue;
            s.getPersistentData().putLong(TAG_PATROL, v.center().asLong());
            s.getPersistentData().putInt(TAG_STEP, 0);
            s.getPersistentData().putFloat(TAG_ANGLE, angle);
            goTo(level, s, waypoint(level, v.center(), angle, 0));
            out.add(s);
        }
        return out;
    }

    /** Los de la patrulla de esa aldea que están cargados. */
    public static List<AbstractRecruitEntity> members(ServerLevel level, BlockPos home) {
        long key = home.asLong();
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(home).inflate(ROUTE_RADIUS + 48, 64, ROUTE_RADIUS + 48),
                e -> e.isAlive() && e.getPersistentData().contains(TAG_PATROL) && e.getPersistentData().getLong(TAG_PATROL) == key);
    }

    /** El punto {@code step} del recorrido (el último es casa). */
    public static Vec3 waypoint(ServerLevel level, BlockPos home, float angle, int step) {
        if (step >= STEPS - 1) return Vec3.atBottomCenterOf(home);
        double a = Math.toRadians(angle + 90 * step);
        int x = home.getX() + (int) Math.round(Math.cos(a) * ROUTE_RADIUS), z = home.getZ() + (int) Math.round(Math.sin(a) * ROUTE_RADIUS);
        int y = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : home.getY();
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private static void goTo(ServerLevel level, AbstractRecruitEntity s, Vec3 target) {
        s.setHoldPos(target);
        s.setFollowState(BACK_TO_POS);
    }

    /** Cada 2 s: el que llegó a su punto pasa al siguiente; al volver a casa, la patrulla termina. Sin jugadores cerca, se va. */
    public static void tick(ServerLevel level) {
        List<Entity> gone = new ArrayList<>();
        Map<Long, List<AbstractRecruitEntity>> roaming = new LinkedHashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof AbstractRecruitEntity s) || !s.isAlive() || !s.getPersistentData().contains(TAG_PATROL)) continue;
            BlockPos home = BlockPos.of(s.getPersistentData().getLong(TAG_PATROL));
            if (level.getNearestPlayer(s, PLAYER_RANGE) == null) {
                gone.add(s);
                continue;
            }
            if (isRoaming(s)) {
                roaming.computeIfAbsent(home.asLong(), k -> new ArrayList<>()).add(s);
                continue;
            }
            if (s.getTarget() != null && s.getTarget().isAlive()) continue;   // peleando
            if (step(level, s, home)) gone.add(s);
        }
        Map<Long, Leg> legs = LEGS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        legs.keySet().retainAll(roaming.keySet());
        for (Map.Entry<Long, List<AbstractRecruitEntity>> g : roaming.entrySet()) {
            if (roamStep(level, g.getKey(), g.getValue(), legs)) gone.addAll(g.getValue());
        }
        for (Entity e : gone) {
            if (e.getVehicle() != null) e.getVehicle().discard();
            e.discard();
        }
    }

    /** Si llegó a su punto, pasa al siguiente. Devuelve true si terminó el recorrido (volvió a casa). */
    public static boolean step(ServerLevel level, AbstractRecruitEntity s, BlockPos home) {
        int step = s.getPersistentData().getInt(TAG_STEP);
        float angle = s.getPersistentData().getFloat(TAG_ANGLE);
        Vec3 target = waypoint(level, home, angle, step);
        double dx = s.getX() - target.x, dz = s.getZ() - target.z;
        if (dx * dx + dz * dz > ARRIVE * ARRIVE) {
            if (s.getHoldPos() == null || s.getHoldPos().distanceToSqr(target) > 4) goTo(level, s, target);
            return false;
        }
        if (step >= STEPS - 1) return true;
        s.getPersistentData().putInt(TAG_STEP, step + 1);
        goTo(level, s, waypoint(level, home, angle, step + 1));
        return false;
    }

    public static boolean isPatrol(Entity e) {
        return e.getPersistentData().contains(TAG_PATROL);
    }

    /** Un jugador (o su tropa) le pegó a un soldado de patrulla: la facción te quiere menos (una vez por soldado). */
    public static void onHit(ServerLevel level, AbstractRecruitEntity s, UUID player) {
        if (s.getPersistentData().getBoolean(TAG_HIT)) return;
        s.getPersistentData().putBoolean(TAG_HIT, true);
        ResourceLocation f = ResourceLocation.tryParse(s.getPersistentData().getString(VillageManager.TAG_FACTION));
        if (f != null) Relations.get(level.getServer()).add(player, f, HIT_OPINION);
    }

    // ------------------------------------------------------------------ patrullas errantes

    public static boolean isRoaming(Entity e) {
        return e.getPersistentData().contains(TAG_ROAM);
    }

    /** Cuántas patrullas errantes distintas hay cargadas cerca de {@code pos}. */
    public static int roamingNear(ServerLevel level, BlockPos pos) {
        Set<Long> groups = new HashSet<>();
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class,
                new AABB(pos).inflate(PLAYER_RANGE, 96, PLAYER_RANGE), e -> e.isAlive() && isRoaming(e))) {
            groups.add(s.getPersistentData().getLong(TAG_PATROL));
        }
        return groups.size();
    }

    /**
     * Aparece una patrulla errante a {@code minDist}-{@code maxDist} bloques del jugador, en un lugar cargado, en tierra y
     * lejos de aldeas. {@code forced} = esa facción (null = al azar: bandidos, la del bioma o cualquiera).
     */
    public static List<AbstractRecruitEntity> spawnRoaming(ServerLevel level, ServerPlayer player, @Nullable Faction forced,
                                                           int minDist, int maxDist) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        RandomSource r = level.getRandom();
        VillageData data = VillageData.get(level);
        BlockPos at = null;
        for (int i = 0; i < 12 && at == null; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int d = minDist + r.nextInt(Math.max(1, maxDist - minDist + 1));
            int x = player.getBlockX() + (int) Math.round(Math.cos(a) * d), z = player.getBlockZ() + (int) Math.round(Math.sin(a) * d);
            if (!level.isPositionEntityTicking(new BlockPos(x, player.getBlockY(), z))) continue;
            BlockPos ground = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.getFluidState(ground.below()).isEmpty() || !level.getFluidState(ground).isEmpty()) continue;
            if (data.nearest(ground, 64) != null) continue;
            at = ground;
        }
        if (at == null) return out;

        Faction f = forced;
        if (f == null && r.nextDouble() < ROAM_BANDIT_CHANCE) f = FactionManager.INSTANCE.get(com.ale.warbandcraft.dungeon.Dungeons.BANDITS);
        if (f == null && r.nextDouble() < ROAM_BIOME_CHANCE) f = FactionManager.INSTANCE.forBiome(level.getBiome(at));
        List<Faction> all = FactionManager.INSTANCE.ordered();
        if (f == null && !all.isEmpty()) f = all.get(r.nextInt(all.size()));
        if (f == null) return out;

        BlockPos dest = null;
        if (!f.outlaw()) {   // a un asentamiento suyo, si hay uno a una distancia razonable
            for (VillageData.Village v : data.byDistance(at)) {
                double dist = Math.sqrt(VillageData.horizontalDistSq(at, v.center()));
                if (dist > ROAM_HOME_RANGE) break;
                if (dist >= ROAM_HOME_MIN && !v.isConquered() && !data.isTest(v.center()) && v.faction().equals(f.id())) {
                    dest = v.center();
                    break;
                }
            }
        }
        if (dest == null) {   // si no, pasa por cerca del jugador y sigue de largo
            double toward = Math.atan2(player.getZ() - at.getZ(), player.getX() - at.getX()) + (r.nextDouble() - 0.5) * Math.toRadians(80);
            int d = ROAM_TRIP_MIN + r.nextInt(Math.max(1, ROAM_TRIP_MAX - ROAM_TRIP_MIN + 1));
            dest = new BlockPos(at.getX() + (int) Math.round(Math.cos(toward) * d), at.getY(), at.getZ() + (int) Math.round(Math.sin(toward) * d));
        }

        int n = ROAM_MIN_SIZE + r.nextInt(Math.max(1, ROAM_MAX_SIZE - ROAM_MIN_SIZE + 1));
        for (int i = 0; i < n; i++) {
            Troop troop = f.pickTroop(r);
            if (troop == null) break;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, at, f, troop, r, 4, ROUTE_RADIUS);
            if (s == null) continue;
            s.clearRestriction();   // no tiene casa: anda por el mapa
            if (s.getVehicle() instanceof PathfinderMob horse) horse.clearRestriction();
            s.getPersistentData().putLong(TAG_PATROL, at.asLong());
            s.getPersistentData().putLong(TAG_ROAM, dest.asLong());
            s.getPersistentData().putInt(TAG_SLOT, out.size());
            out.add(s);
        }
        if (!out.isEmpty()) {
            boolean hostile = Relations.get(level.getServer()).atWar(player.getUUID(), f.id());
            player.displayClientMessage(Component.translatable(hostile ? "msg.warbandcraft.patrol.seen_hostile" : "msg.warbandcraft.patrol.seen",
                    Component.literal(f.name()).withStyle(f.color()), out.size()), true);
            WarbandCraft.LOGGER.info("Patrulla errante de {} ({}) en {} hacia {}", f.id(), out.size(), at, dest);
        }
        return out;
    }

    /**
     * Un paso de una patrulla errante: todos van al mismo tramo en formación (filas de 3); cuando el grupo llega, el
     * próximo tramo hacia el destino. Si alguno pelea, el resto espera. Devuelve true si llegó (se va).
     */
    private static boolean roamStep(ServerLevel level, long key, List<AbstractRecruitEntity> members, Map<Long, Leg> legs) {
        BlockPos dest = BlockPos.of(members.get(0).getPersistentData().getLong(TAG_ROAM));
        double cx = 0, cz = 0;
        for (AbstractRecruitEntity s : members) {
            if (s.getTarget() != null && s.getTarget().isAlive()) return false;   // peleando: el resto espera
            cx += s.getX();
            cz += s.getZ();
        }
        cx /= members.size();
        cz /= members.size();
        double dx = dest.getX() + 0.5 - cx, dz = dest.getZ() + 0.5 - cz, left = Math.sqrt(dx * dx + dz * dz);
        if (left <= ROAM_ARRIVE) return true;

        long now = level.getGameTime();
        Leg leg = legs.get(key);
        boolean arrived = leg != null && sq(leg.pos().x - cx) + sq(leg.pos().z - cz) <= sq(ARRIVE + 2);
        if (leg == null || arrived || now - leg.since() > ROAM_LEG_TICKS) {
            double base = Math.atan2(dz, dx);
            // al que no llega a un tramo (un río, un barranco) se lo manda para otro lado
            double tilt = leg != null && !arrived
                    ? (level.getRandom().nextBoolean() ? 1 : -1) * Math.toRadians(45 + level.getRandom().nextInt(46)) : 0;
            Vec3 next = null;
            for (double extra : new double[]{0, 0.5, -0.5, 1, -1}) {
                double a = base + tilt + extra;
                double len = Math.min(ROAM_LEG, left);
                int x = (int) Math.round(cx + Math.cos(a) * len), z = (int) Math.round(cz + Math.sin(a) * len);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;   // sin meterse al agua
                next = new Vec3(x + 0.5, y, z + 0.5);
                break;
            }
            if (next == null) return false;
            leg = new Leg(next, now);
            legs.put(key, leg);
        }

        // Formación: filas de 3 a lo ancho del camino, 2,5 bloques entre cada uno
        double a = Math.atan2(leg.pos().z - cz, leg.pos().x - cx);
        double fx = Math.cos(a), fz = Math.sin(a), sx = -fz, sz = fx;
        for (AbstractRecruitEntity s : members) {
            int slot = s.getPersistentData().getInt(TAG_SLOT);
            double side = (slot % 3 - 1) * 2.5, back = (slot / 3) * 2.5;
            int x = (int) Math.floor(leg.pos().x + sx * side - fx * back), z = (int) Math.floor(leg.pos().z + sz * side - fz * back);
            int y = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : (int) leg.pos().y;
            Vec3 target = new Vec3(x + 0.5, y, z + 0.5);
            if (s.getHoldPos() == null || s.getHoldPos().distanceToSqr(target) > 4 || s.getFollowState() != BACK_TO_POS) goTo(level, s, target);
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** ¿Las facciones de estos dos soldados pelean si se cruzan? Bandidos con todos; si no, si están en guerra (H15b). */
    public static boolean factionsAtWar(ServerLevel level, String a, String b) {
        if (a.equals(b)) return false;
        ResourceLocation fa = ResourceLocation.tryParse(a), fb = ResourceLocation.tryParse(b);
        if (fa == null || fb == null) return false;
        Faction x = FactionManager.INSTANCE.get(fa), y = FactionManager.INSTANCE.get(fb);
        if ((x != null && x.outlaw()) || (y != null && y.outlaw())) return true;
        return InvasionData.get(level).warUntil(fa, fb) >= level.getDayTime() / 24000L;
    }
}
