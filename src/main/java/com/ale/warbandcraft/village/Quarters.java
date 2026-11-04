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
 * y los demás patrullan, siempre adentro de las murallas (pedido de Ale: esperan al enemigo, no salen a buscarlo; nadie
 * camina por la empalizada). Los arqueros van caminando de verdad a su torre (sin teletransportes): si no llegan, prueban
 * otra. Una orden tuya (seguir, mover, Batalla...) se respeta: ese soldado queda a tus órdenes hasta que se guarde (te
 * alejás de la aldea) y vuelve a salir al cuartel. En una invasión los mueve la defensa; al terminar, vuelven a lo suyo.
 */
public final class Quarters {
    /** En el soldado: de qué aldea es su cuartel (centro como long). */
    public static final String TAG_QUARTERS = "warbandcraft:quarters";
    /** En el soldado: el punto al que va y desde cuándo (game time), y hasta cuándo se queda parado al llegar. */
    private static final String TAG_POINT = "warbandcraft:quarters_point", TAG_SINCE = "warbandcraft:quarters_since",
            TAG_WAIT = "warbandcraft:quarters_wait";
    /** En el soldado: está en un puesto de torre (no patrulla). */
    private static final String TAG_TOWER = "warbandcraft:quarters_tower";
    /** En el soldado: le diste una orden (queda a tus órdenes); está peleando por el cuartel; salió de una invasión. */
    public static final String TAG_ORDERED = "warbandcraft:quarters_ordered";
    private static final String TAG_FIGHT = "warbandcraft:quarters_fight", TAG_RESET = "warbandcraft:quarters_reset";
    private static final String[] TAGS = {TAG_QUARTERS, TAG_POINT, TAG_SINCE, TAG_WAIT, TAG_TOWER, TAG_ORDERED, TAG_FIGHT, TAG_RESET};
    /** Las marcas de trabajo (no la de la aldea): se borran al volver a salir del cuartel guardado. */
    private static final String[] DUTY_TAGS = {TAG_POINT, TAG_SINCE, TAG_WAIT, TAG_TOWER, TAG_ORDERED, TAG_FIGHT, TAG_RESET};

    /** Lugar en el cuartel: aldea / castillo, el doble con la mejora Cuartel (H12). */
    public static int VILLAGE_CAPACITY = 10, CASTLE_CAPACITY = 15, BARRACKS_MULTIPLIER = 2;
    /** Salen a patrullar con el dueño a menos de esto del centro; se guardan con el dueño a más de {@link #STORE_DISTANCE}. */
    public static int DEPLOY_DISTANCE = 96, STORE_DISTANCE = 128;
    /** Los puntos de la patrulla: adentro del territorio y a menos de esto del centro (sin territorio: este radio). */
    public static int PATROL_RADIUS = 48;
    /** Al llegar a un punto se queda parado entre 5 y 15 s; si en 60 s no llegó, va a otro. */
    public static int WAIT_MIN = 100, WAIT_EXTRA = 200, GIVE_UP = 20 * 60;
    public static double ARRIVE = 2.5;
    /**
     * Arquero de torre: camina hasta el pie de la torre y, a esta distancia (horizontal) de su puesto, aparece arriba
     * (pedido de Ale: no saben trepar escaleras; desde adentro de la torre está bien que suban así).
     */
    public static double FOOT_DISTANCE = 8;
    /** Arquero que no llega al pie de su torre en tanto (ticks): prueba otra y esa no la vuelve a probar por un rato. */
    public static long TOWER_GIVE_UP = 20 * 90, UNREACHABLE_TICKS = 20 * 60 * 5;
    /** Arquero a menos de esto (horizontal) de su puesto y a su altura: queda clavado ahí (no lo corre Recruits al tirar). */
    public static double PIN_DISTANCE = 1.2;
    /** Cuántos arqueros entran arriba de cada torre. */
    public static int PER_TOWER = 4;
    /** Puntos altos arriba de la muralla para arqueros: uno cada tanto (bloques), hasta este máximo. */
    public static int WALL_POST_SPACING = 8, WALL_POSTS_MAX = 24;
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
    private static final int WANDER = 0, FOLLOW = 1, HOLD = 2, BACK_TO_POS = 3, PROTECT_OWNER = 4, PROTECT = 5, NEUTRAL = 0;
    private static final String BY = "quarters";

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

    /**
     * El cuartel se guarda solo si no queda nadie de la facción del dueño cerca de la aldea (antes miraba solo al dueño
     * del soldado: con soldados de un compañero que no estaba, salían con vos cerca y a los 10 s se guardaban, sin parar).
     */
    public static boolean factionAway(ServerLevel level, @Nullable UUID owner, BlockPos center) {
        return !factionNear(level, owner, center, STORE_DISTANCE);
    }

    /** Hay un jugador del dueño (o de su facción) a menos de {@code dist} (horizontal) de {@code center}. */
    public static boolean factionNear(ServerLevel level, @Nullable UUID owner, BlockPos center, double dist) {
        if (owner == null) return false;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !com.ale.warbandcraft.faction.Allies.same(p.getUUID(), owner)) continue;
            if (horizontalDistSq(p.position(), center) <= dist * dist) return true;
        }
        return false;
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
            if (mine.isEmpty()) continue;
            if (underInvasion(level, center)) {   // invasión: los mueve la defensa (SiegeDefenders), sueltos
                for (AbstractRecruitEntity s : mine) {
                    pin(s, false);
                    s.getPersistentData().putBoolean(TAG_RESET, true);   // al terminar, cada uno vuelve a lo suyo
                }
                continue;
            }
            List<AbstractRecruitEntity> managed = new ArrayList<>();
            for (AbstractRecruitEntity s : mine) {
                CompoundTag pd = s.getPersistentData();
                if (pd.getBoolean(TAG_RESET)) {   // terminó una invasión: lo que hizo la defensa no es una orden tuya
                    pd.remove(TAG_RESET);
                    pd.remove(TAG_ORDERED);
                    pd.remove(TAG_FIGHT);
                    pd.remove(TAG_WAIT);
                    if (pd.getBoolean(TAG_TOWER) && pd.contains(TAG_POINT)) goTo(level, s, Vec3.atBottomCenterOf(BlockPos.of(pd.getLong(TAG_POINT))), now);
                    else pd.remove(TAG_POINT);
                }
                if (!pd.getBoolean(TAG_ORDERED) && playerOrdered(s)) {
                    com.ale.warbandcraft.WarbandCraft.LOGGER.info("Cuartel: {} queda a tus órdenes (estado {}, puesto {}, punto {})", s.getName().getString(),
                            s.getFollowState(), s.getHoldPos(), pd.contains(TAG_POINT) ? BlockPos.of(pd.getLong(TAG_POINT)).toShortString() : "-");
                    pd.putBoolean(TAG_ORDERED, true);   // pedido de Ale: le diste una orden, se respeta
                    pd.remove(TAG_FIGHT);
                    com.ale.warbandcraft.nav.Mover.Run run = com.ale.warbandcraft.nav.Mover.run(s);
                    if (run != null && BY.equals(run.by)) com.ale.warbandcraft.nav.Mover.stop(s);
                }
                if (pd.getBoolean(TAG_ORDERED)) {
                    pin(s, false);
                    continue;
                }
                managed.add(s);
            }
            assignTowers(level, managed, posts(level, v), now);
            List<LivingEntity> enemies = enemiesIn(level, v);
            for (AbstractRecruitEntity s : managed) {
                boolean tower = s.getPersistentData().getBoolean(TAG_TOWER);
                // Prioridades: 1) enemigos adentro de la muralla, 2) bichos cerca (adentro), 3) patrullar / su torre.
                // Los arqueros tiran desde donde están (no salen a pelear de cerca).
                if (fight(level, v, s, enemies, tower, now)) {
                    if (tower) guardTower(level, s, now);   // tira desde arriba, sin bajarse
                    continue;
                }
                if (tower) guardTower(level, s, now);
                else patrol(level, v, s, now);
            }
        }
    }

    // ------------------------------------------------------------------ pelear

    /**
     * Elige a quién pegarle y devuelve true si está peleando. Los de a pie: solo enemigos y bichos adentro de las murallas
     * (salen a buscarlo, estado 0; si se va afuera lo dejan y vuelven a lo suyo). Los arqueros (de torre o no): lo que
     * tengan a tiro y a la vista, adentro o afuera, desde donde están, sin moverse (pedido de Ale: los ballesteros salían
     * a pelear de cerca y los mataban).
     */
    private static boolean fight(ServerLevel level, VillageData.Village v, AbstractRecruitEntity s, List<LivingEntity> enemies, boolean tower, long now) {
        if (s.getState() != NEUTRAL) s.setAggroState(NEUTRAL);   // pasivo o agresivo: no
        CompoundTag pd = s.getPersistentData();
        boolean archer = tower || isArcher(s);
        LivingEntity current = s.getTarget();
        boolean alive = current != null && current.isAlive();
        // Lo que no se ataca: creepers, bichos bajo tierra o fuera de alcance, lo que ya persiguió sin poder llegar, y
        // (de a pie) lo que está afuera de la muralla
        java.util.function.Predicate<LivingEntity> ok = archer
                ? e -> !(e instanceof net.minecraft.world.entity.monster.Creeper) && !ignored(s, e, now)   // el arquero: si lo ve, le tira
                        && e.distanceToSqr(s) <= TOWER_RANGE * TOWER_RANGE && s.hasLineOfSight(e)
                : e -> worthFighting(level, s, e, now) && InsideWalls.inside(level, v, e.blockPosition());
        LivingEntity target = null;
        if (alive && ok.test(current) && (enemies.contains(current) || isEnemy(level, v.owner(), current))) {
            target = current;
        } else {
            target = nearest(s, enemies.stream().filter(ok).toList(), archer ? TOWER_RANGE : Double.MAX_VALUE);
            if (target == null) {
                double r = archer ? TOWER_RANGE : MOB_RANGE;
                List<LivingEntity> near = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, s.getBoundingBox().inflate(r, 8, r),
                        m -> m.isAlive() && m != s && (archer ? isEnemy(level, v.owner(), m) : m instanceof net.minecraft.world.entity.monster.Enemy
                                && isEnemy(level, v.owner(), m)) && ok.test(m)));
                target = nearest(s, near, r);
            }
        }
        if (target == null) {
            CHASES.remove(s.getUUID());
            pd.remove(TAG_FIGHT);
            if (alive) s.setTarget(null);   // afuera de la muralla, un creeper, uno inalcanzable: no
            return false;
        }
        if (archer) {
            // El arquero sin torre que patrullaba se para donde está y tira desde ahí (no cambia de orden)
            if (!tower && !pd.getBoolean(TAG_FIGHT)) goTo(level, s, s.position(), now);
            pd.putBoolean(TAG_FIGHT, true);
            if (s.getTarget() != target) s.setTarget(target);
            return true;
        }
        track(s, target, now);
        pd.putBoolean(TAG_FIGHT, true);
        if (s.getTarget() != target) s.setTarget(target);
        if (s.getFollowState() != WANDER) s.setFollowState(WANDER);
        return true;
    }

    /** Arquero o ballestero a pie. */
    private static boolean isArcher(AbstractRecruitEntity s) {
        return !s.isPassenger() && com.ale.warbandcraft.battle.BattleGeneral.roleOf(s) == com.ale.warbandcraft.battle.BattleGeneral.Role.RANGED;
    }

    /**
     * ¿Le diste otra orden? (seguir, quedarse, mover a otro lado, Batalla, al barracón). Lo que hace el cuartel es estado 3
     * con el puesto en su punto, o estado 0 mientras pelea por el cuartel.
     */
    static boolean playerOrdered(AbstractRecruitEntity s) {
        CompoundTag pd = s.getPersistentData();
        if (pd.getBoolean(com.ale.warbandcraft.battle.BattleGeneral.TAG_BATTLE) || pd.getBoolean(Barracks.TAG_TO_BARRACKS)) return true;
        int state = s.getFollowState();
        if (state == FOLLOW || state == PROTECT_OWNER || state == PROTECT) return true;   // seguirte / protegerte
        // Estado 0 o "quieto acá" (2 con el puesto donde está) lo pone Recruits solo: al terminar una pelea, al descansar
        // herido (RestGoal: 0 y después 2) o al elegir un objetivo. Eso no es una orden tuya (antes los dejaba sin patrullar).
        if (state != HOLD && state != BACK_TO_POS) return false;
        // Sin punto del cuartel (dejó una torre a la que no llegaba, terminó una invasión): el puesto viejo no es orden tuya
        if (!pd.contains(TAG_POINT)) return false;
        Vec3 hold = s.getHoldPos();
        if (hold == null) return false;
        if (pd.contains(TAG_POINT) && hold.distanceToSqr(Vec3.atBottomCenterOf(BlockPos.of(pd.getLong(TAG_POINT)))) <= 2.25) return false;
        return hold.distanceToSqr(s.position()) > ORDER_MOVE * ORDER_MOVE;   // lo mandaste a otro lado
    }

    /** Un puesto a más de esto del soldado (y que no es el del cuartel) es una orden tuya de ir a otro lado. */
    public static double ORDER_MOVE = 4;

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

    /** Ya lo persiguió sin poder llegar: lo deja un rato. */
    private static boolean ignored(AbstractRecruitEntity s, LivingEntity e, long now) {
        Map<UUID, Long> ignored = IGNORED.get(s.getUUID());
        if (ignored == null) return false;
        ignored.values().removeIf(until -> until <= now);
        return ignored.containsKey(e.getUUID());
    }

    /** ¿Vale la pena ir a pegarle? No a un creeper, ni a algo bajo tierra o muy arriba/abajo, ni a lo que ya dejó. */
    private static boolean worthFighting(ServerLevel level, AbstractRecruitEntity s, LivingEntity e, long now) {
        if (e instanceof net.minecraft.world.entity.monster.Creeper || ignored(s, e, now)) return false;
        if (e instanceof net.minecraft.world.entity.player.Player) return true;
        BlockPos p = e.blockPosition();
        if (!level.isLoaded(p)) return false;
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        boolean cave = p.getY() < surface - UNDERGROUND && e.getY() < s.getY() - UNDERGROUND;   // en una cueva debajo de él
        return !cave && Math.abs(e.getY() - s.getY()) <= MAX_HEIGHT_DIFF;
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
        com.ale.warbandcraft.raid.RaidManager.Raid raid = com.ale.warbandcraft.raid.RaidManager.get(level, center);
        if (raid != null && raid.pvp() && raid.state == com.ale.warbandcraft.raid.RaidManager.State.FIGHTING) return true;   // asedio de otro jugador
        com.ale.warbandcraft.invasion.InvasionData.Invasion inv = com.ale.warbandcraft.invasion.InvasionData.get(level).at(center);
        return inv != null && inv.state == com.ale.warbandcraft.invasion.InvasionData.State.FIGHTING;
    }

    /**
     * Los arqueros (arco o ballesta, a pie) toman los puestos de las torres y puntos altos, repartidos parejo (pedido de
     * Ale): con N arqueros se usan los N primeros de {@link #posts}, que van intercalados de una torre/punto a otro. El
     * que está en un puesto que sobra cambia a uno que falta; el que se queda sin puesto, patrulla.
     * Pedido de Ale (se teletransportaban y salían a patrullar): el puesto es fijo mientras exista. Un arquero de torre
     * sigue siéndolo aunque Recruits le ponga la espada en la mano un rato, y para emparejar se cambia de a uno, solo con
     * una diferencia de 2 o más entre la torre más llena y una con lugar.
     */
    public static void assignTowers(ServerLevel level, List<AbstractRecruitEntity> soldiers, List<BlockPos> posts, long now) {
        List<List<BlockPos>> groups = groups(posts);
        Map<Long, Integer> groupOf = new java.util.HashMap<>();
        for (int g = 0; g < groups.size(); g++) for (BlockPos p : groups.get(g)) groupOf.put(p.asLong(), g);
        int[] used = new int[groups.size()];
        java.util.Set<Long> taken = new java.util.HashSet<>();
        Map<Long, AbstractRecruitEntity> on = new java.util.HashMap<>();
        List<AbstractRecruitEntity> waiting = new ArrayList<>();
        for (AbstractRecruitEntity s : soldiers) {
            CompoundTag pd = s.getPersistentData();
            boolean tower = pd.getBoolean(TAG_TOWER);
            boolean archer = !s.isPassenger() && (tower || com.ale.warbandcraft.battle.BattleGeneral.roleOf(s) == com.ale.warbandcraft.battle.BattleGeneral.Role.RANGED);
            long post = pd.getLong(TAG_POINT);
            if (archer && tower && groupOf.containsKey(post) && !unreachable(s, BlockPos.of(post), now) && taken.add(post)) {   // sigue en su puesto
                used[groupOf.get(post)]++;
                on.put(post, s);
                continue;
            }
            if (tower) {   // su puesto ya no existe, no llega (o se subió a un caballo): a patrullar
                pd.remove(TAG_TOWER);
                pd.remove(TAG_POINT);
                pin(s, false);
                if (com.ale.warbandcraft.nav.Mover.run(s) != null && BY.equals(com.ale.warbandcraft.nav.Mover.run(s).by)) com.ale.warbandcraft.nav.Mover.stop(s);
            }
            if (archer) waiting.add(s);
        }
        for (AbstractRecruitEntity s : waiting) {
            BlockPos post = emptiest(groups, used, taken, p -> !unreachable(s, p, now));
            if (post == null) continue;
            take(level, s, post, groupOf, used, taken, now);
        }
        // Emparejar: de a uno por vuelta, del lugar más lleno al más vacío con lugar (y que no esté peleando)
        int full = -1;
        for (int g = 0; g < used.length; g++) if (full < 0 || used[g] > used[full]) full = g;
        if (full < 0) return;
        for (BlockPos p : groups.get(full)) {
            AbstractRecruitEntity s = on.get(p.asLong());
            if (s == null || s.getTarget() != null && s.getTarget().isAlive()) continue;
            BlockPos to = emptiest(groups, used, taken, q -> !unreachable(s, q, now));
            if (to == null || used[full] - used[groupOf.get(to.asLong())] < 2) return;
            used[full]--;
            taken.remove(p.asLong());
            pin(s, false);
            take(level, s, to, groupOf, used, taken, now);
            return;
        }
    }

    /** El primer puesto libre (y al que llega) del grupo (torre o punto) con menos arqueros; a igualdad, el que va antes. */
    @Nullable
    private static BlockPos emptiest(List<List<BlockPos>> groups, int[] used, java.util.Set<Long> taken, java.util.function.Predicate<BlockPos> allowed) {
        BlockPos best = null;
        int bestUsed = Integer.MAX_VALUE;
        for (int g = 0; g < groups.size(); g++) {
            if (used[g] >= bestUsed) continue;
            for (BlockPos p : groups.get(g)) {
                if (taken.contains(p.asLong()) || !allowed.test(p)) continue;
                best = p;
                bestUsed = used[g];
                break;
            }
        }
        return best;
    }

    private static void take(ServerLevel level, AbstractRecruitEntity s, BlockPos post, Map<Long, Integer> groupOf, int[] used,
                             java.util.Set<Long> taken, long now) {
        taken.add(post.asLong());
        used[groupOf.get(post.asLong())]++;
        s.getPersistentData().putBoolean(TAG_TOWER, true);
        goTo(level, s, Vec3.atBottomCenterOf(post), now);
    }

    /** Soldado → (puesto → hasta cuándo no lo vuelve a probar): puestos a los que no encontró cómo llegar caminando. */
    private static final Map<UUID, Map<Long, Long>> UNREACHABLE = new java.util.HashMap<>();

    /** No encontró cómo llegar caminando a ese puesto (o a uno pegado: la misma torre) hace poco. */
    public static boolean unreachable(LivingEntity s, BlockPos post, long now) {
        Map<Long, Long> m = UNREACHABLE.get(s.getUUID());
        if (m == null) return false;
        m.values().removeIf(until -> until <= now);
        for (long l : m.keySet()) {
            BlockPos q = BlockPos.of(l);
            if (Math.abs(q.getX() - post.getX()) <= 3 && Math.abs(q.getZ() - post.getZ()) <= 3 && Math.abs(q.getY() - post.getY()) <= 2) return true;
        }
        return false;
    }

    public static void markUnreachable(LivingEntity s, BlockPos post, long now) {
        UNREACHABLE.computeIfAbsent(s.getUUID(), k -> new java.util.HashMap<>()).put(post.asLong(), now + UNREACHABLE_TICKS);
    }

    private static final UUID PIN_ID = UUID.fromString("5b0f3c1e-7a42-4d6e-9c8b-2f1a6d4e8b31");

    /**
     * Arquero en su puesto: velocidad 0 (modificador transitorio, como el Tabernero) para que Recruits no lo corra al
     * tirar y se caiga de la torre o la muralla. Sigue tirando.
     */
    static void pin(AbstractRecruitEntity s, boolean on) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance speed = s.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null || (speed.getModifier(PIN_ID) != null) == on) return;
        if (on) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(PIN_ID, "warbandcraft tower post",
                    -1.0, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else {
            speed.removeModifier(PIN_ID);
        }
    }

    /**
     * Arquero de torre: va caminando hasta el pie de su torre (lo lleva Recruits) y, ya ahí ({@link #FOOT_DISTANCE}),
     * aparece arriba. Si no llega al pie en {@link #TOWER_GIVE_UP}, deja esa torre (prueba otra, o patrulla).
     */
    private static void guardTower(ServerLevel level, AbstractRecruitEntity s, long now) {
        CompoundTag pd = s.getPersistentData();
        BlockPos post = BlockPos.of(pd.getLong(TAG_POINT));
        Vec3 at = Vec3.atBottomCenterOf(post);
        boolean onPost = horizontalDistSq(s.position(), post) <= PIN_DISTANCE * PIN_DISTANCE && Math.abs(s.getY() - at.y) < 1;
        pin(s, onPost);
        if (onPost) pd.putLong(TAG_SINCE, now);   // llegó: el plazo corre desde que se va
        if (com.ale.warbandcraft.nav.Mover.moving(s)) return;   // subiendo por la escalera de la torre
        if (!onPost && now - pd.getLong(TAG_SINCE) > TOWER_GIVE_UP) {
            giveUpPost(s, post, now);
            return;
        }
        if (!onPost && horizontalDistSq(s.position(), post) <= FOOT_DISTANCE * FOOT_DISTANCE && Math.abs(s.getY() - at.y) > 1.5) {
            // Al pie de la torre: sube (aparece arriba; las escaleras no las saben trepar)
            if (s.isPassenger()) s.stopRiding();
            com.ale.warbandcraft.nav.Mover.stop(s);
            s.teleportTo(at.x, at.y, at.z);
            s.getNavigation().stop();
            pd.putLong(TAG_SINCE, now);
        }
        LivingEntity target = s.getTarget();
        if (target != null && target.isAlive()) return;   // tirando
        Vec3 hold = s.getHoldPos();
        if (s.getFollowState() != BACK_TO_POS || hold == null || hold.distanceToSqr(at) > 2.25) {
            long since = pd.getLong(TAG_SINCE);
            goTo(level, s, at, now);
            pd.putLong(TAG_SINCE, since);   // volver a mandarlo no corre el plazo
        }
    }

    /** No llega a ese puesto: lo anota y queda libre para otro (o patrulla). */
    private static void giveUpPost(AbstractRecruitEntity s, BlockPos post, long now) {
        markUnreachable(s, post, now);
        CompoundTag pd = s.getPersistentData();
        pd.remove(TAG_TOWER);
        pd.remove(TAG_POINT);
        pin(s, false);
    }


    /**
     * Si el dueño está cerca y la aldea cargada, salen los del cuartel que estaban guardados: aparecen en el Campamento de
     * la aldea (el cuartel) y caminan a su lugar (pedido de Ale: no aparecen en cualquier lado).
     */
    public static int deployIfNear(ServerLevel level, VillageData.Village v) {
        return deploy(level, v, false);
    }

    /** {@code force}: salen aunque no haya nadie de la facción cerca (un asedio de otro jugador). */
    public static int deploy(ServerLevel level, VillageData.Village v, boolean force) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null || !level.isPositionEntityTicking(camp)) return 0;
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        if (b == null || b.quarters.isEmpty()) return 0;
        if (!force && !factionNear(level, v.owner(), v.center(), DEPLOY_DISTANCE)) return 0;   // vos o alguien de tu facción cerca
        int out = 0;
        for (BarracksData.Soldier sheet : new ArrayList<>(b.quarters)) {
            b.quarters.remove(sheet);
            Vec3 at = Barracks.standable(level, camp, null, camp);
            AbstractRecruitEntity s = Barracks.create(level, sheet, at, camp, BACK_TO_POS, b.hungryDays);
            if (s == null) continue;
            CompoundTag pd = s.getPersistentData();
            for (String tag : DUTY_TAGS) pd.remove(tag);   // vuelve a empezar: sin órdenes viejas
            pd.remove(com.ale.warbandcraft.battle.BattleGeneral.TAG_BATTLE);
            pd.putLong(TAG_QUARTERS, v.center().asLong());
            BlockPos point = randomPoint(level, v.center(), level.getRandom());
            goTo(level, s, point != null ? Vec3.atBottomCenterOf(point) : at, level.getGameTime());
            out++;
        }
        BarracksData.get(level).changed();
        if (out > 0) com.ale.warbandcraft.WarbandCraft.LOGGER.info("Cuartel de {}: salen {} del Campamento {}", v.center().toShortString(), out, camp.toShortString());
        return out;
    }

    /** Diagnóstico: las marcas del cuartel que tiene. */
    public static String debugTags(AbstractRecruitEntity s) {
        StringBuilder b = new StringBuilder();
        for (String t : TAGS) if (s.getPersistentData().contains(t)) b.append(t.replace("warbandcraft:", "")).append(' ');
        return b.toString().trim();
    }

    /** Un paso de la patrulla de {@code s}: de un punto adentro de las murallas a otro. */
    private static void patrol(ServerLevel level, VillageData.Village v, AbstractRecruitEntity s, long now) {
        LivingEntity target = s.getTarget();
        if (target != null && target.isAlive()) return;   // peleando: sigue su pelea
        CompoundTag pd = s.getPersistentData();
        Vec3 point = pd.contains(TAG_POINT) ? Vec3.atBottomCenterOf(BlockPos.of(pd.getLong(TAG_POINT))) : null;
        // Terminó de pelear (o el punto quedó afuera de la muralla / arriba de la empalizada): vuelve a lo suyo
        if (point != null && (!InsideWalls.inside(level, v, point) || InsideWalls.onPalisade(level, BlockPos.containing(point)))) point = null;
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
        BlockPos next = randomPoint(level, v.center(), level.getRandom());
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
        pin(s, false);
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
            // Pedido de Ale: arriba de la muralla no va nadie, solo a las torres
        }
        out = spread(out);
        POSTS.put(key, new CachedPosts(now, out));
        return out;
    }

    /**
     * Puntos altos arriba de la muralla (dibujada, o el anillo de H12), uno cada {@link #WALL_POST_SPACING}, sin pisar las
     * torres: también son puestos de arqueros.
     */
    public static List<BlockPos> wallTopPosts(ServerLevel level, BlockPos center, TownData.Town t, List<BlockPos> towers) {
        Map<Long, Integer> tops = new LinkedHashMap<>();   // columna → y del bloque más alto
        if (!t.defense.isEmpty()) {
            for (long l : t.defense.keySet()) {
                BlockPos p = BlockPos.of(l);
                tops.merge(ChunkPos.asLong(p.getX(), p.getZ()), p.getY(), Math::max);
            }
        } else if (t.ringWalls && t.ringRadius > 0) {
            for (int[] c : com.ale.warbandcraft.upgrade.Blueprints.ringColumns(center, t.ringRadius)) {
                if (!level.hasChunk(c[0] >> 4, c[1] >> 4)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]) - 1;
                net.minecraft.world.level.block.state.BlockState s = level.getBlockState(new BlockPos(c[0], y, c[1]));
                if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)
                        || s.is(net.minecraft.tags.BlockTags.WALLS)) tops.put(ChunkPos.asLong(c[0], c[1]), y);
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : tops.entrySet()) {
            if (out.size() >= WALL_POSTS_MAX) break;
            BlockPos p = new BlockPos(ChunkPos.getX(e.getKey()), e.getValue() + 1, ChunkPos.getZ(e.getKey()));
            if (!Ground.canStand(level, p) || !behindBattlement(level, p) || near(p, towers, 4) || near(p, out, WALL_POST_SPACING)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Pedido de Ale: solo muralla de piedra con almenas (de la empalizada se caen y los matan). Parado sobre piedra (no
     * troncos ni encima de una almena) y con una almena pegada al lado.
     */
    public static boolean behindBattlement(ServerLevel level, BlockPos p) {
        net.minecraft.world.level.block.state.BlockState floor = level.getBlockState(p.below());
        if (floor.is(net.minecraft.tags.BlockTags.LOGS) || floor.is(net.minecraft.tags.BlockTags.WALLS)
                || floor.is(net.minecraft.tags.BlockTags.PLANKS) || floor.is(net.minecraft.tags.BlockTags.FENCES)) return false;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(p.relative(d)).is(net.minecraft.tags.BlockTags.WALLS)) return true;
        }
        return false;
    }

    private static boolean near(BlockPos p, List<BlockPos> list, double d) {
        for (BlockPos q : list) if (horizontalDistSq(Vec3.atBottomCenterOf(q), p) < d * d) return true;
        return false;
    }

    /**
     * Ordena los puestos para repartir parejo: los junta por torre/punto (pegados = el mismo), elige los grupos lo más
     * separados posible (el primero, después el más lejos de los elegidos...) y va de a uno por grupo en ronda. Así los
     * N primeros quedan repartidos entre todas las torres y puntos altos.
     */
    public static List<BlockPos> spread(List<BlockPos> posts) {
        List<List<BlockPos>> ordered = groups(posts);
        List<BlockPos> out = new ArrayList<>();
        for (int round = 0; out.size() < posts.size(); round++) {
            for (List<BlockPos> g : ordered) if (round < g.size()) out.add(g.get(round));
        }
        return out;
    }

    /** Los puestos juntados por torre/punto (pegados = el mismo), los grupos ordenados lo más separados posible. */
    static List<List<BlockPos>> groups(List<BlockPos> posts) {
        List<List<BlockPos>> groups = new ArrayList<>();
        boolean[] used = new boolean[posts.size()];
        for (int i = 0; i < posts.size(); i++) {
            if (used[i]) continue;
            List<BlockPos> g = new ArrayList<>();
            java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
            open.add(i);
            used[i] = true;
            while (!open.isEmpty()) {
                BlockPos p = posts.get(open.poll());
                g.add(p);
                for (int j = 0; j < posts.size(); j++) {
                    BlockPos q = posts.get(j);
                    if (!used[j] && Math.abs(p.getX() - q.getX()) <= 2 && Math.abs(p.getZ() - q.getZ()) <= 2 && Math.abs(p.getY() - q.getY()) <= 2) {
                        used[j] = true;
                        open.add(j);
                    }
                }
            }
            groups.add(g);
        }
        List<List<BlockPos>> ordered = new ArrayList<>();
        while (!groups.isEmpty()) {
            int best = 0;
            double bestD = -1;
            for (int i = 0; i < groups.size() && !ordered.isEmpty(); i++) {
                double d = Double.MAX_VALUE;
                for (List<BlockPos> o : ordered) d = Math.min(d, groups.get(i).get(0).distSqr(o.get(0)));
                if (d > bestD) {
                    bestD = d;
                    best = i;
                }
            }
            ordered.add(groups.remove(best));
        }
        return ordered;
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

    /**
     * Un lugar donde pararse al azar adentro de la aldea: con muralla, adentro de ella (pedido de Ale); sin muralla, en un
     * chunk del territorio a ≤ {@link #PATROL_RADIUS} del centro. Nunca arriba de la empalizada ni en el agua.
     */
    @Nullable
    public static BlockPos randomPoint(ServerLevel level, BlockPos center, RandomSource r) {
        VillageData.Village v = VillageData.get(level).at(center);
        boolean walled = v != null && InsideWalls.walled(level, v);
        List<ChunkPos> chunks = new ArrayList<>();
        if (!walled) {
            double max = (PATROL_RADIUS + 8.0) * (PATROL_RADIUS + 8.0);
            for (ChunkPos c : Territory.get(level).of(center)) {
                double dx = c.getMiddleBlockX() - center.getX(), dz = c.getMiddleBlockZ() - center.getZ();
                if (dx * dx + dz * dz <= max) chunks.add(c);
            }
        }
        for (int i = 0; i < 16; i++) {
            int x, z;
            if (walled) {
                double a = r.nextDouble() * Math.PI * 2;
                Vec3 dir = new Vec3(center.getX() + 0.5 + Math.cos(a) * 8, center.getY(), center.getZ() + 0.5 + Math.sin(a) * 8);
                double max = Math.max(2, Math.min(PATROL_RADIUS, InsideWalls.radiusToward(level, v, dir) - 2));
                double d = Math.sqrt(r.nextDouble()) * max;
                x = (int) Math.floor(center.getX() + 0.5 + Math.cos(a) * d);
                z = (int) Math.floor(center.getZ() + 0.5 + Math.sin(a) * d);
            } else if (chunks.isEmpty()) {
                x = center.getX() + r.nextInt(2 * PATROL_RADIUS + 1) - PATROL_RADIUS;
                z = center.getZ() + r.nextInt(2 * PATROL_RADIUS + 1) - PATROL_RADIUS;
            } else {
                ChunkPos c = chunks.get(r.nextInt(chunks.size()));
                x = c.getMinBlockX() + r.nextInt(16);
                z = c.getMinBlockZ() + r.nextInt(16);
            }
            if ((double) (x - center.getX()) * (x - center.getX()) + (double) (z - center.getZ()) * (z - center.getZ())
                    > (double) PATROL_RADIUS * PATROL_RADIUS) continue;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            Integer y = Ground.surfaceY(level, x, z, center.getY(), 24, 24);
            if (y == null) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (!Ground.canStand(level, p) || InsideWalls.onPalisade(level, p) || !level.getFluidState(p.below()).isEmpty()) continue;
            if (v != null && !InsideWalls.inside(level, v, p)) continue;
            return p;
        }
        return null;
    }
    private static double horizontalDistSq(Vec3 a, BlockPos b) {
        double dx = a.x - (b.getX() + 0.5), dz = a.z - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }
}
