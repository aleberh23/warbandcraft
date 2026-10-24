package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.network.OpenBarracksMessage;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cuartel de una aldea o castillo tuyo (pedido de Ale): soldados que patrullan solos adentro de la aldea. Se pasan
 * del barracón al cuartel y al revés desde el modo Gestión (pestaña Tropas). Con el dueño cerca andan sueltos
 * caminando de un punto al azar del territorio a otro; con el dueño lejos se guardan como fichas en
 * {@link BarracksData.Barracks#quarters} (comen del almacén como el barracón) y vuelven a salir cuando él vuelve.
 * Los arqueros/ballesteros van a los puestos de las torres (las de la mejora Torres; en un castillo, sus partes más altas)
 * y los demás patrullan. No los manejás vos: si les das otra orden, a los 2 s vuelven a lo suyo. Solo con una invasión
 * peleándose en la aldea quedan libres para tus órdenes; al terminar, vuelven solos a sus torres y patrullas.
 */
public final class Quarters {
    /** En el soldado: de qué aldea es su cuartel (centro como long). */
    public static final String TAG_QUARTERS = "warbandcraft:quarters";
    /** En el soldado: el punto al que va y desde cuándo (game time), y hasta cuándo se queda parado al llegar. */
    private static final String TAG_POINT = "warbandcraft:quarters_point", TAG_SINCE = "warbandcraft:quarters_since",
            TAG_WAIT = "warbandcraft:quarters_wait";
    /** En el soldado: está en un puesto de torre (no patrulla). */
    private static final String TAG_TOWER = "warbandcraft:quarters_tower";
    private static final String[] TAGS = {TAG_QUARTERS, TAG_POINT, TAG_SINCE, TAG_WAIT, TAG_TOWER};

    /** Lugar en el cuartel: aldea / castillo, el doble con la mejora Cuartel (H12). */
    public static int VILLAGE_CAPACITY = 10, CASTLE_CAPACITY = 15, BARRACKS_MULTIPLIER = 2;
    /** Salen a patrullar con el dueño a menos de esto del centro; se guardan con el dueño a más de {@link #STORE_DISTANCE}. */
    public static int DEPLOY_DISTANCE = 96, STORE_DISTANCE = 128;
    /** Los puntos de la patrulla: adentro del territorio y a menos de esto del centro (sin territorio: este radio). */
    public static int PATROL_RADIUS = 48;
    /** Al llegar a un punto se queda parado entre 5 y 15 s; si en 60 s no llegó, va a otro. */
    public static int WAIT_MIN = 100, WAIT_EXTRA = 200, GIVE_UP = 20 * 60;
    public static double ARRIVE = 2.5;
    /** Arquero de torre: a esta distancia (horizontal) de su puesto sube por la escalera (H17; sin camino, se lo pone arriba). */
    public static double CLIMB_DISTANCE = 8;
    /** Cuántos arqueros entran arriba de cada torre. */
    public static int PER_TOWER = 4;
    /** Cada cuánto se vuelven a buscar las torres de una aldea (ticks). */
    private static long POSTS_REFRESH = 20 * 60;

    /** Bichos que van a buscar los de a pie (desde donde estén); el arquero de torre tira hasta {@link #TOWER_RANGE}. */
    public static double MOB_RANGE = 16, TOWER_RANGE = 32;
    /** Un jugador que le pegó a uno de los tuyos (o a un aldeano) hace menos de esto (ticks) es enemigo. */
    private static int ATTACKER_MEMORY = 20 * 10;
    /** Persiguiendo: si en 15 s no se acercó, lo deja por 1 min. Bajo tierra = más de 4 bloques debajo del piso. */
    private static long CHASE_GIVE_UP = 20 * 15, IGNORE_TICKS = 20 * 60;
    private static int UNDERGROUND = 4, MAX_HEIGHT_DIFF = 12;

    /** Estados de órdenes de Recruits (seguir) y de agresión (0 = neutral: se defiende y ataca bichos). */
    private static final int WANDER = 0, HOLD = 2, BACK_TO_POS = 3, NEUTRAL = 0;

    private Quarters() {}

    /** Lugar en el cuartel de la aldea: 10 (castillo 15), el doble con el Cuartel. */
    public static int capacity(ServerLevel level, VillageData.Village v) {
        int base = v.castle() ? CASTLE_CAPACITY : VILLAGE_CAPACITY;
        TownData.Town t = TownData.get(level).at(v.center());
        return t != null && t.has(Upgrade.BARRACKS) ? base * BARRACKS_MULTIPLIER : base;
    }

    /** La aldea del cuartel del soldado (null = no es del cuartel). */
    @Nullable
    public static BlockPos villageOf(LivingEntity soldier) {
        CompoundTag pd = soldier.getPersistentData();
        return pd.contains(TAG_QUARTERS) ? BlockPos.of(pd.getLong(TAG_QUARTERS)) : null;
    }

    /** El soldado es del cuartel de la aldea cuyo Campamento es {@code camp}. */
    public static boolean belongsTo(ServerLevel level, AbstractRecruitEntity soldier, BlockPos camp) {
        BlockPos center = villageOf(soldier);
        if (center == null) return false;
        VillageData.Village v = VillageData.get(level).at(center);
        return v != null && camp.equals(VillageCamps.campOf(level, v));
    }

    /** El dueño está lejos de la aldea (o desconectado / en otro mundo: {@code ownerPos} null). */
    public static boolean ownerAway(@Nullable Vec3 ownerPos, BlockPos center) {
        return ownerPos == null || horizontalDistSq(ownerPos, center) > (double) STORE_DISTANCE * STORE_DISTANCE;
    }

    /** Los del cuartel que andan sueltos (cargados). */
    public static List<AbstractRecruitEntity> loaded(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (AbstractRecruitEntity s : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getPersistentData().contains(TAG_QUARTERS) && r.getPersistentData().getLong(TAG_QUARTERS) == key)) {
            out.add(s);
        }
        return out;
    }

    /** Los del cuartel que están en un puesto de torre (cargados). */
    public static int onTowers(ServerLevel level, BlockPos center) {
        int n = 0;
        for (AbstractRecruitEntity s : loaded(level, center)) if (s.getPersistentData().getBoolean(TAG_TOWER)) n++;
        return n;
    }

    /** Cuántos hay en el cuartel: guardados + sueltos. */
    public static int count(ServerLevel level, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        return (b == null ? 0 : b.quarters.size()) + loaded(level, v.center()).size();
    }

    // ------------------------------------------------------------------ pasar tropas

    /**
     * Pasa hasta {@code amount} soldados de la fila {@code rowKey} (null = cualquiera) del barracón al cuartel.
     * Los guardias pierden su puesto. Si el dueño está cerca, salen enseguida a patrullar.
     */
    public static Component toQuarters(ServerLevel level, VillageData.Village v, @Nullable String rowKey, int amount) {
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = camp == null ? null : data.at(camp);
        if (b == null) return Component.translatable("msg.warbandcraft.quarters.none").withStyle(ChatFormatting.RED);
        int room = capacity(level, v) - count(level, v);
        if (room <= 0) return Component.translatable("msg.warbandcraft.quarters.full", capacity(level, v)).withStyle(ChatFormatting.RED);
        int moved = 0;
        for (BarracksData.Soldier s : new ArrayList<>(b.soldiers)) {
            if (moved >= Math.min(amount, room)) break;
            if (rowKey != null && !rowKey.equals(s.rowKey())) continue;
            b.soldiers.remove(s);
            b.quarters.add(new BarracksData.Soldier(s.entity, s.horse, false, null, s.yRot, BACK_TO_POS, s.name, s.kit, s.tier, s.xpLevel));
            moved++;
        }
        if (moved == 0) return Component.translatable("msg.warbandcraft.quarters.no_one").withStyle(ChatFormatting.RED);
        data.changed();
        deployIfNear(level, v);
        return Component.translatable("msg.warbandcraft.quarters.moved_in", moved).withStyle(ChatFormatting.GREEN);
    }

    /**
     * Pasa hasta {@code amount} soldados de la fila {@code rowKey} (null = cualquiera) del cuartel al barracón: primero
     * los guardados, después los que andan sueltos (salvo que estén peleando).
     */
    public static Component toBarracks(ServerLevel level, VillageData.Village v, @Nullable String rowKey, int amount) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null) return Component.translatable("msg.warbandcraft.quarters.none").withStyle(ChatFormatting.RED);
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.getOrCreate(camp, v.owner());
        int capacity = Barracks.capacity(level, camp);
        int moved = 0;
        boolean full = false, fighting = false;
        for (BarracksData.Soldier s : new ArrayList<>(b.quarters)) {
            if (moved >= amount) break;
            if (rowKey != null && !rowKey.equals(s.rowKey())) continue;
            if (b.soldiers.size() >= capacity) {
                full = true;
                break;
            }
            b.quarters.remove(s);
            CompoundTag forge = s.entity.getCompound("ForgeData");
            for (String tag : TAGS) forge.remove(tag);
            b.soldiers.add(new BarracksData.Soldier(s.entity, s.horse, false, null, s.yRot, 1, s.name, s.kit, s.tier, s.xpLevel));
            moved++;
        }
        for (AbstractRecruitEntity s : loaded(level, v.center())) {
            if (moved >= amount || full) break;
            if (rowKey != null && !rowKey.equals(rowKey(s))) continue;
            CompoundTag pd = s.getPersistentData();
            long home = pd.getLong(TAG_QUARTERS);
            leave(s);
            Barracks.StoreResult r = Barracks.store(level, s, camp, true);
            if (r == Barracks.StoreResult.OK) {
                moved++;
                continue;
            }
            pd.putLong(TAG_QUARTERS, home);   // no se pudo: sigue en el cuartel
            if (r == Barracks.StoreResult.FULL) full = true;
            if (r == Barracks.StoreResult.FIGHTING) fighting = true;
        }
        data.changed();
        if (moved == 0) {
            String why = full ? "msg.warbandcraft.quarters.barracks_full" : fighting ? "msg.warbandcraft.quarters.fighting" : "msg.warbandcraft.quarters.no_one";
            return Component.translatable(why).withStyle(ChatFormatting.RED);
        }
        return Component.translatable("msg.warbandcraft.quarters.moved_out", moved).withStyle(ChatFormatting.GREEN);
    }

    /** Fila del Barracón a la que pertenece un soldado suelto (misma clave que {@link BarracksData.Soldier#rowKey}). */
    public static String rowKey(AbstractRecruitEntity s) {
        CompoundTag pd = s.getPersistentData();
        ResourceLocation kit = pd.contains(ClassAssigner.TAG_CLASS) ? ResourceLocation.tryParse(pd.getString(ClassAssigner.TAG_CLASS)) : null;
        return kit == null ? "" : kit + "#" + pd.getInt(ClassAssigner.TAG_TIER);
    }

    /** Una fila de la pestaña Tropas: clase y nivel, cuántos en el barracón y cuántos en el cuartel. */
    public record TroopRow(String key, Component label, int barracks, int quarters) {}

    public static List<TroopRow> rows(ServerLevel level, UUID owner, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        List<BarracksData.Soldier> barracks = b == null ? List.of() : b.soldiers;
        List<BarracksData.Soldier> quarters = new ArrayList<>(b == null ? List.of() : b.quarters);
        for (AbstractRecruitEntity s : loaded(level, v.center())) {   // los sueltos, como fichas solo para contarlos
            CompoundTag pd = s.getPersistentData();
            ResourceLocation kit = pd.contains(ClassAssigner.TAG_CLASS) ? ResourceLocation.tryParse(pd.getString(ClassAssigner.TAG_CLASS)) : null;
            quarters.add(new BarracksData.Soldier(new CompoundTag(), null, false, null, 0F, BACK_TO_POS, s.getName().getString(), kit,
                    pd.getInt(ClassAssigner.TAG_TIER), s.getXpLevel()));
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (BarracksData.Soldier s : barracks) counts.computeIfAbsent(s.rowKey(), k -> new int[2])[0]++;
        for (BarracksData.Soldier s : quarters) counts.computeIfAbsent(s.rowKey(), k -> new int[2])[1]++;
        List<BarracksData.Soldier> all = new ArrayList<>(barracks);
        all.addAll(quarters);
        List<TroopRow> out = new ArrayList<>();
        for (OpenBarracksMessage.Row r : OpenBarracksMessage.rows(level.getServer(), owner, all)) {
            int[] c = counts.get(r.key());
            out.add(new TroopRow(r.key(), r.label(), c[0], c[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------ patrulla

    /**
     * Cada 2 s: los cuarteles salen con el dueño cerca; los sueltos van a sus torres (arqueros) o patrullan. Fuera de una
     * invasión, una orden tuya se deshace; durante una invasión peleándose ahí, no se los toca.
     */
    public static void tick(ServerLevel level) {
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (v.isConquered()) deployIfNear(level, v);
        }
        long now = level.getGameTime();
        Map<Long, List<AbstractRecruitEntity>> byVillage = new LinkedHashMap<>();
        for (AbstractRecruitEntity s : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getPersistentData().contains(TAG_QUARTERS))) {
            byVillage.computeIfAbsent(s.getPersistentData().getLong(TAG_QUARTERS), k -> new ArrayList<>()).add(s);
        }
        java.util.Set<UUID> alive = new java.util.HashSet<>();   // olvidar persecuciones de los que ya no están
        byVillage.values().forEach(l -> l.forEach(x -> alive.add(x.getUUID())));
        CHASES.keySet().retainAll(alive);
        IGNORED.keySet().retainAll(alive);
        for (Map.Entry<Long, List<AbstractRecruitEntity>> e : byVillage.entrySet()) {
            BlockPos center = BlockPos.of(e.getKey());
            VillageData.Village v = VillageData.get(level).at(center);
            List<AbstractRecruitEntity> mine = new ArrayList<>();
            for (AbstractRecruitEntity s : e.getValue()) {
                if (v == null || !com.ale.warbandcraft.faction.Allies.same(v.owner(), s.getOwnerUUID()) || VillageCamps.campOf(level, v) == null) {
                    leave(s);   // la aldea ya no es tuya: queda quieto donde está, como un soldado común
                    s.setFollowState(HOLD);
                } else {
                    mine.add(s);
                }
            }
            if (mine.isEmpty() || underInvasion(level, center)) continue;   // invasión: los manejás vos
            assignTowers(level, mine, posts(level, v), now);
            List<LivingEntity> enemies = enemiesIn(level, v);
            for (AbstractRecruitEntity s : mine) {
                s.getPersistentData().remove(com.ale.warbandcraft.battle.BattleGeneral.TAG_BATTLE);   // no van a tus batallas
                boolean tower = s.getPersistentData().getBoolean(TAG_TOWER);
                // Prioridades: 1) enemigos adentro de la aldea, 2) bichos cerca, 3) patrullar / su torre. Tu orden, nunca.
                if (fight(level, s, enemies, tower, now)) {
                    if (tower) guardTower(level, s, now);   // tira desde arriba, sin bajarse
                    continue;
                }
                if (tower) guardTower(level, s, now);
                else patrol(level, s, center, now);
            }
        }
    }

    // ------------------------------------------------------------------ pelear

    /**
     * Elige a quién pegarle: un enemigo adentro de la aldea primero (el más cercano; el arquero de torre, solo a tiro), y
     * si no hay, un bicho cerca. Devuelve true si está peleando. Los de a pie salen a buscarlo (estado 0, como la
     * Batalla); el arquero de torre se queda arriba.
     */
    private static boolean fight(ServerLevel level, AbstractRecruitEntity s, List<LivingEntity> enemies, boolean tower, long now) {
        if (s.getState() != NEUTRAL) s.setAggroState(NEUTRAL);   // pasivo o agresivo por orden tuya: no
        LivingEntity current = s.getTarget();
        boolean alive = current != null && current.isAlive();
        // Lo que no se ataca: creepers, bichos bajo tierra o fuera de alcance, y lo que ya persiguió sin poder llegar
        java.util.function.Predicate<LivingEntity> ok = e -> worthFighting(level, s, e, now);
        LivingEntity target = null;
        if (alive && enemies.contains(current) && ok.test(current)) {
            target = current;
        } else {
            double range = tower ? TOWER_RANGE : Double.MAX_VALUE;
            target = nearest(s, enemies.stream().filter(ok).toList(), range);
            if (target == null && alive && current instanceof net.minecraft.world.entity.monster.Enemy && ok.test(current)
                    && current.distanceToSqr(s) <= (tower ? TOWER_RANGE * TOWER_RANGE : MOB_RANGE * MOB_RANGE * 4)) target = current;
            if (target == null) {
                double r = tower ? TOWER_RANGE : MOB_RANGE;
                List<LivingEntity> mobs = new ArrayList<>(level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, s.getBoundingBox().inflate(r, 6, r),
                        m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && ok.test(m)));
                target = nearest(s, mobs, r);
            }
        }
        if (target == null) {
            CHASES.remove(s.getUUID());
            if (alive) s.setTarget(null);   // un creeper, uno inalcanzable u otra cosa que le marcaste vos: no
            return false;
        }
        if (!tower) track(s, target, now);
        if (s.getTarget() != target) s.setTarget(target);
        if (!tower && s.getFollowState() != WANDER) s.setFollowState(WANDER);
        return true;
    }

    /** Persecución en curso de cada soldado: a quién, la menor distancia lograda y desde cuándo no la mejora. */
    private record Chase(UUID target, double best, long since) {}

    private static final Map<UUID, Chase> CHASES = new java.util.HashMap<>();
    /** Soldado → (objetivo → hasta cuándo no lo persigue). */
    private static final Map<UUID, Map<UUID, Long>> IGNORED = new java.util.HashMap<>();

    /** Si en {@link #CHASE_GIVE_UP} no se acercó ni un bloque, lo deja (está en una cueva, en el agua, tras un muro...). */
    private static void track(AbstractRecruitEntity s, LivingEntity target, long now) {
        double d = Math.sqrt(s.distanceToSqr(target));
        Chase c = CHASES.get(s.getUUID());
        if (c == null || !c.target().equals(target.getUUID()) || d < c.best() - 1) {
            CHASES.put(s.getUUID(), new Chase(target.getUUID(), d, now));
            return;
        }
        if (now - c.since() > CHASE_GIVE_UP && d > 3) {
            IGNORED.computeIfAbsent(s.getUUID(), k -> new java.util.HashMap<>()).put(target.getUUID(), now + IGNORE_TICKS);
            CHASES.remove(s.getUUID());
            s.setTarget(null);
        }
    }

    /** ¿Vale la pena ir a pegarle? No a un creeper, ni a algo bajo tierra o muy arriba/abajo, ni a lo que ya dejó. */
    private static boolean worthFighting(ServerLevel level, AbstractRecruitEntity s, LivingEntity e, long now) {
        if (e instanceof net.minecraft.world.entity.monster.Creeper) return false;
        Map<UUID, Long> ignored = IGNORED.get(s.getUUID());
        if (ignored != null) {
            ignored.values().removeIf(until -> until <= now);
            if (ignored.containsKey(e.getUUID())) return false;
        }
        if (e instanceof net.minecraft.world.entity.player.Player) return true;
        BlockPos p = e.blockPosition();
        if (!level.isLoaded(p)) return false;
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() >= surface - UNDERGROUND && Math.abs(e.getY() - s.getY()) <= MAX_HEIGHT_DIFF;
    }

    @Nullable
    private static LivingEntity nearest(AbstractRecruitEntity s, List<? extends LivingEntity> list, double range) {
        LivingEntity best = null;
        double bestD = range == Double.MAX_VALUE ? Double.MAX_VALUE : range * range;
        for (LivingEntity e : list) {
            double d = e.distanceToSqr(s);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /** Los enemigos adentro de la aldea (territorio, o a ≤ {@link #PATROL_RADIUS}+8 del centro). */
    public static List<LivingEntity> enemiesIn(ServerLevel level, VillageData.Village v) {
        int r = PATROL_RADIUS + 32;
        return level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(v.center()).inflate(r, 32, r),
                e -> e.isAlive() && inVillage(level, v.center(), e.blockPosition()) && isEnemy(level, v.owner(), e));
    }

    private static boolean inVillage(ServerLevel level, BlockPos center, BlockPos pos) {
        if (Territory.get(level).contains(center, pos)) return true;
        double dx = pos.getX() - center.getX(), dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (PATROL_RADIUS + 8.0) * (PATROL_RADIUS + 8.0);
    }

    /**
     * Enemigo de la aldea: illagers y bichos hostiles; invasores y atacantes de otras facciones; soldados de una facción
     * en guerra con vos; y cualquiera (jugador, soldado, animal) que esté atacando a vos, a tus soldados o a los aldeanos.
     */
    public static boolean isEnemy(ServerLevel level, UUID owner, LivingEntity e) {
        if (e instanceof net.minecraft.world.entity.monster.Creeper) return false;   // a los creepers no se los toca
        if (com.ale.warbandcraft.dungeon.Compat.isBoss(e)) {   // H25: a un jefe no se lo busca; si ataca a lo tuyo, sí
            return e instanceof net.minecraft.world.entity.Mob m && m.getTarget() != null && isOurs(owner, m.getTarget());
        }
        if (e instanceof net.minecraft.world.entity.raid.Raider || e instanceof net.minecraft.world.entity.monster.Enemy) return true;
        if (e instanceof net.minecraft.world.entity.player.Player p) {
            if (p.isCreative() || p.isSpectator() || p.getUUID().equals(owner)) return false;
            return p.getLastHurtMob() != null && isOurs(owner, p.getLastHurtMob())
                    && p.tickCount - p.getLastHurtMobTimestamp() < ATTACKER_MEMORY;
        }
        if (e instanceof AbstractRecruitEntity r) {
            if (com.ale.warbandcraft.faction.Allies.same(r.getOwnerUUID(), owner)) return false;
            CompoundTag pd = r.getPersistentData();
            if (pd.contains(com.ale.warbandcraft.invasion.Invasions.TAG_INVADER) || pd.contains(com.ale.warbandcraft.invasion.AiWars.TAG_AI_ATTACK)) return true;
            ResourceLocation f = ResourceLocation.tryParse(pd.getString(VillageManager.TAG_FACTION));
            if (r.getOwnerUUID() == null && f != null && com.ale.warbandcraft.faction.Relations.get(level.getServer()).atWar(owner, f)) return true;
        }
        return e instanceof net.minecraft.world.entity.Mob m && m.getTarget() != null && isOurs(owner, m.getTarget());
    }

    /** Vos, tus soldados o trabajadores, o un aldeano. */
    private static boolean isOurs(UUID owner, LivingEntity t) {
        if (t instanceof net.minecraft.world.entity.player.Player p) return p.getUUID().equals(owner);
        if (t instanceof net.minecraft.world.entity.npc.AbstractVillager) return true;
        return t instanceof AbstractRecruitEntity r && com.ale.warbandcraft.faction.Allies.same(r.getOwnerUUID(), owner);
    }

    /** Hay una invasión peleándose en la aldea: el cuartel queda a tus órdenes. */
    public static boolean underInvasion(ServerLevel level, BlockPos center) {
        com.ale.warbandcraft.invasion.InvasionData.Invasion inv = com.ale.warbandcraft.invasion.InvasionData.get(level).at(center);
        return inv != null && inv.state == com.ale.warbandcraft.invasion.InvasionData.State.FIGHTING;
    }

    /** Los arqueros (arco o ballesta, a pie) toman los puestos libres de las torres; el que se queda sin puesto, patrulla. */
    private static void assignTowers(ServerLevel level, List<AbstractRecruitEntity> soldiers, List<BlockPos> posts, long now) {
        java.util.Set<Long> free = new java.util.LinkedHashSet<>();
        for (BlockPos p : posts) free.add(p.asLong());
        List<AbstractRecruitEntity> waiting = new ArrayList<>();
        for (AbstractRecruitEntity s : soldiers) {
            CompoundTag pd = s.getPersistentData();
            boolean archer = com.ale.warbandcraft.battle.BattleGeneral.roleOf(s) == com.ale.warbandcraft.battle.BattleGeneral.Role.RANGED;
            if (archer && pd.getBoolean(TAG_TOWER) && free.remove(pd.getLong(TAG_POINT))) continue;   // sigue en su puesto
            if (pd.getBoolean(TAG_TOWER)) {   // su puesto ya no existe (o dejó de ser arquero): a patrullar
                pd.remove(TAG_TOWER);
                pd.remove(TAG_POINT);
            }
            if (archer) waiting.add(s);
        }
        for (AbstractRecruitEntity s : waiting) {
            if (free.isEmpty()) break;
            long post = free.iterator().next();
            free.remove(post);
            s.getPersistentData().putBoolean(TAG_TOWER, true);
            goTo(level, s, Vec3.atBottomCenterOf(BlockPos.of(post)), now);
        }
    }

    /** Arquero de torre: va a su puesto y, al llegar al pie de la torre, sube. Si le diste otra orden, vuelve. */
    private static void guardTower(ServerLevel level, AbstractRecruitEntity s, long now) {
        BlockPos post = BlockPos.of(s.getPersistentData().getLong(TAG_POINT));
        Vec3 at = Vec3.atBottomCenterOf(post);
        if (com.ale.warbandcraft.nav.Mover.moving(s)) return;   // subiendo por la escalera de la torre
        if (horizontalDistSq(s.position(), post) <= CLIMB_DISTANCE * CLIMB_DISTANCE && Math.abs(s.getY() - at.y) > 1.5) {
            if (s.isPassenger()) s.stopRiding();
            // H17: sube caminando por la malla (escalera de la torre); si no hay camino, aparece arriba como antes
            if (!com.ale.warbandcraft.nav.Mover.go(s, at, 0.6, com.ale.warbandcraft.nav.NavCaps.SOLDIER,
                    com.ale.warbandcraft.nav.NavPath.Bounds.around(post, 24), "quarters")
                    || !reachesPost(com.ale.warbandcraft.nav.Mover.run(s), at)) {
                com.ale.warbandcraft.nav.Mover.stop(s);
                s.teleportTo(at.x, at.y, at.z);
                s.getNavigation().stop();
            } else {
                return;
            }
        }
        LivingEntity target = s.getTarget();
        if (target != null && target.isAlive()) return;   // tirando
        Vec3 hold = s.getHoldPos();
        if (s.getFollowState() != BACK_TO_POS || hold == null || hold.distanceToSqr(at) > 2.25) goTo(level, s, at, now);
    }

    /** El camino termina arriba, en el puesto (no al pie de la torre). */
    private static boolean reachesPost(@Nullable com.ale.warbandcraft.nav.Mover.Run r, Vec3 at) {
        if (r == null) return false;
        Vec3 end = r.path.get(r.path.size() - 1).center();
        return Math.abs(end.y - at.y) <= 1 && end.distanceToSqr(at.x, end.y, at.z) <= 2.25;
    }

    /** Si el dueño está cerca y la aldea cargada, salen los del cuartel que estaban guardados. */
    public static int deployIfNear(ServerLevel level, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null || !level.isPositionEntityTicking(camp)) return 0;
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        if (b == null || b.quarters.isEmpty()) return 0;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(v.owner());
        if (owner == null || owner.level() != level
                || horizontalDistSq(owner.position(), v.center()) > (double) DEPLOY_DISTANCE * DEPLOY_DISTANCE) return 0;
        int out = 0;
        for (BarracksData.Soldier sheet : new ArrayList<>(b.quarters)) {
            b.quarters.remove(sheet);
            BlockPos point = randomPoint(level, v.center(), level.getRandom());
            Vec3 at = Barracks.standable(level, point != null ? point : camp, null, camp);
            AbstractRecruitEntity s = Barracks.create(level, sheet, at, camp, BACK_TO_POS, b.hungryDays);
            if (s == null) continue;
            s.getPersistentData().putLong(TAG_QUARTERS, v.center().asLong());
            goTo(level, s, at, level.getGameTime());
            out++;
        }
        BarracksData.get(level).changed();
        return out;
    }

    /** Un paso de la patrulla de {@code s}. */
    private static void patrol(ServerLevel level, AbstractRecruitEntity s, BlockPos center, long now) {
        LivingEntity target = s.getTarget();
        if (target != null && target.isAlive()) return;   // peleando: sigue su pelea
        CompoundTag pd = s.getPersistentData();
        Vec3 point = pd.contains(TAG_POINT) ? Vec3.atBottomCenterOf(BlockPos.of(pd.getLong(TAG_POINT))) : null;
        // ¿Le diste otra orden (o terminó una invasión)? Vuelve a lo suyo
        Vec3 hold = s.getHoldPos();
        if (point != null && (s.getFollowState() != BACK_TO_POS || hold == null || hold.distanceToSqr(point) > 2.25)) {
            goTo(level, s, point, now);
            return;
        }
        boolean arrived = point != null && horizontalDistSq(s.position(), BlockPos.containing(point)) <= ARRIVE * ARRIVE;
        if (arrived) {
            if (!pd.contains(TAG_WAIT)) {
                pd.putLong(TAG_WAIT, now + WAIT_MIN + level.getRandom().nextInt(WAIT_EXTRA));
                return;
            }
            if (now < pd.getLong(TAG_WAIT)) return;
        } else if (point != null && now - pd.getLong(TAG_SINCE) < GIVE_UP) {
            return;
        }
        BlockPos next = randomPoint(level, center, level.getRandom());
        if (next != null) goTo(level, s, Vec3.atBottomCenterOf(next), now);
    }

    private static void goTo(ServerLevel level, AbstractRecruitEntity s, Vec3 at, long now) {
        BlockPos p = BlockPos.containing(at);
        CompoundTag pd = s.getPersistentData();
        pd.putLong(TAG_POINT, p.asLong());
        pd.putLong(TAG_SINCE, now);
        pd.remove(TAG_WAIT);
        s.setTarget(null);
        s.setFollowState(BACK_TO_POS);
        s.setHoldPos(Vec3.atBottomCenterOf(p));
    }

    /** Deja el cuartel: le saca las marcas (sigue con la orden que tenga). */
    private static void leave(AbstractRecruitEntity s) {
        CompoundTag pd = s.getPersistentData();
        for (String tag : TAGS) pd.remove(tag);
    }

    private record CachedPosts(long at, List<BlockPos> posts) {}

    private static final Map<String, CachedPosts> POSTS = new java.util.HashMap<>();

    /**
     * Los puestos de arqueros de la aldea: arriba de cada torre de la mejora Torres ({@link #PER_TOWER} por torre); en un
     * castillo, sus partes más altas (los mismos puestos que usa la Batalla). Se recalcula cada minuto.
     */
    public static List<BlockPos> posts(ServerLevel level, VillageData.Village v) {
        String key = level.dimension().location() + "@" + v.center().asLong();
        CachedPosts c = POSTS.get(key);
        long now = level.getGameTime();
        if (c != null && now >= c.at() && now - c.at() < POSTS_REFRESH) return c.posts();
        List<BlockPos> out = new ArrayList<>();
        if (v.castle()) {
            for (Vec3 p : com.ale.warbandcraft.battle.BattleGeneral.wallPosts(level, v.center())) out.add(BlockPos.containing(p));
        } else {
            TownData.Town t = TownData.get(level).at(v.center());
            if (t != null && !t.towers.isEmpty()) out = drawnTowerPosts(level, t);   // H22c: torres sueltas
            else if (t != null && t.has(Upgrade.TOWERS) && t.ringRadius > 0) out = towerPosts(level, v.center(), t.ringRadius);
        }
        POSTS.put(key, new CachedPosts(now, out));
        return out;
    }

    /** H22c: arriba de cada torre suelta (centro con y = piso; el techo está 8 más arriba): {@link #PER_TOWER} puestos. */
    public static List<BlockPos> drawnTowerPosts(ServerLevel level, TownData.Town t) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : t.towers) {
            if (!level.isLoaded(c)) continue;
            BlockPos roof = c.above(9);
            int n = 0;
            for (int ox = -1; ox <= 1 && n < PER_TOWER; ox++) for (int oz = -1; oz <= 1 && n < PER_TOWER; oz++) {
                BlockPos q = roof.offset(ox, 0, oz);
                if (Ground.canStand(level, q)) {
                    out.add(q);
                    n++;
                }
            }
        }
        return out;
    }

    /**
     * Busca las torres de {@link com.ale.warbandcraft.upgrade.Blueprints#towers} sobre el anillo: cada una tiene una
     * escalera contra la pared que sube hasta el techo, y el centro del techo está un bloque hacia donde mira la escalera.
     */
    public static List<BlockPos> towerPosts(ServerLevel level, BlockPos center, int ring) {
        Map<Long, BlockPos> ladderTops = new LinkedHashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int rMin = Math.max(0, ring - 4), rMax = ring + 4;
        for (int dx = -rMax; dx <= rMax; dx++) {
            for (int dz = -rMax; dz <= rMax; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 < rMin * rMin || d2 > rMax * rMax) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = center.getY() + 32; y >= center.getY() - 12; y--) {
                    p.set(x, y, z);
                    if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.LADDER)) {
                        ladderTops.put(ChunkPos.asLong(x, z), p.immutable());   // la más alta de la columna
                        break;
                    }
                }
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos ladder : ladderTops.values()) {
            net.minecraft.core.Direction facing = level.getBlockState(ladder).getValue(net.minecraft.world.level.block.LadderBlock.FACING);
            BlockPos roof = ladder.relative(facing).above();
            if (!level.getBlockState(roof.below()).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) continue;   // no es una torre nuestra
            List<BlockPos> slots = new ArrayList<>();
            slots.add(roof);
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                BlockPos q = roof.offset(ox, 0, oz);
                if ((ox != 0 || oz != 0) && (q.getX() != ladder.getX() || q.getZ() != ladder.getZ())) slots.add(q);
            }
            int n = 0;
            for (BlockPos q : slots) {
                if (n >= PER_TOWER) break;
                if (Ground.canStand(level, q)) {
                    out.add(q);
                    n++;
                }
            }
        }
        return out;
    }

    /** Un lugar donde pararse al azar: en un chunk del territorio a ≤ {@link #PATROL_RADIUS} del centro, cargado. */
    @Nullable
    public static BlockPos randomPoint(ServerLevel level, BlockPos center, RandomSource r) {
        List<ChunkPos> chunks = new ArrayList<>();
        double max = (PATROL_RADIUS + 8.0) * (PATROL_RADIUS + 8.0);
        for (ChunkPos c : Territory.get(level).of(center)) {
            double dx = c.getMiddleBlockX() - center.getX(), dz = c.getMiddleBlockZ() - center.getZ();
            if (dx * dx + dz * dz <= max) chunks.add(c);
        }
        for (int i = 0; i < 12; i++) {
            int x, z;
            if (chunks.isEmpty()) {
                x = center.getX() + r.nextInt(2 * PATROL_RADIUS + 1) - PATROL_RADIUS;
                z = center.getZ() + r.nextInt(2 * PATROL_RADIUS + 1) - PATROL_RADIUS;
            } else {
                ChunkPos c = chunks.get(r.nextInt(chunks.size()));
                x = c.getMinBlockX() + r.nextInt(16);
                z = c.getMinBlockZ() + r.nextInt(16);
            }
            if ((double) (x - center.getX()) * (x - center.getX()) + (double) (z - center.getZ()) * (z - center.getZ())
                    > (double) PATROL_RADIUS * PATROL_RADIUS) continue;
            Integer y = Ground.surfaceY(level, x, z, center.getY(), 24, 24);
            if (y == null) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (Ground.canStand(level, p)) return p;
        }
        return null;
    }

    private static double horizontalDistSq(Vec3 a, BlockPos b) {
        double dx = a.x - (b.getX() + 0.5), dz = a.z - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}
