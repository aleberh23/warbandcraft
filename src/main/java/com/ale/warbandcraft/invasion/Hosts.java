package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.army.Armies;
import com.ale.warbandcraft.army.ArmyData;
import com.ale.warbandcraft.battle.BattleBar;
import com.ale.warbandcraft.battle.BattleSize;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
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
 * La Hueste (H23, "llamado a la batalla"): una facción en guerra con vos junta el grueso de su ejército (30 a 100
 * hombres, según tu fuerza) y acampa en un campo de batalla cerca de una aldea tuya. Tenés {@link #DEADLINE_DAYS} días
 * para presentarle batalla (invocar un ejército ahí, o acercarte): batalla a campo abierto, con oleadas según el tamaño
 * de batalla ({@link BattleSize}). Si no vas, o si te vas en medio, marcha contra la aldea con lo que le quede.
 * <p>
 * Cuántas y cada cuánto (estilo Total War / Bannerlord): cada facción junta hombres para la hueste por cada asentamiento
 * suyo; cada día, con guerra, tira una chance que crece con el odio que te tiene; el tamaño sale de tu fuerza (la IA arma
 * lo que cree que te gana), entre {@link #MIN_SIZE} y {@link #MAX_SIZE}. Después de una hueste, unos días de calma.
 */
public final class Hosts {
    /** Hombres por día que cada aldea/castillo de la facción suma para la hueste, y tope. */
    public static int POOL_PER_SETTLEMENT = 6, POOL_CAP = 150;
    /** Tamaño: tu fuerza por esto (±{@link #SIZE_JITTER}), entre el mínimo y el máximo. */
    public static double STRENGTH_RATIO = 1.3, SIZE_JITTER = 0.15;
    public static int MIN_SIZE = 30, MAX_SIZE = 100;
    /** Chance por día: base + odio × esto (odio 0 en la opinión de guerra, 1 en el mínimo). */
    public static double CHANCE_BASE = 0.10, CHANCE_HATE = 0.30;
    /** Días para presentarle batalla. */
    public static int DEADLINE_DAYS = 3;
    /** Días sin otra hueste de esa facción contra vos: después de que marchó contra la aldea / después de que la venciste. */
    public static int COOLDOWN_DAYS = 4, DEFEAT_COOLDOWN_DAYS = 7;
    /**
     * Campo de batalla (donde invocás tu ejército): a esta distancia de la aldea (de a pasos) y cuántos lugares se prueban
     * de verdad (carga chunks). La hueste acampa {@link #GAP} bloques más allá, del lado contrario a la aldea.
     */
    public static int FIELD_MIN = 150, FIELD_MAX = 360, FIELD_STEP = 30, FIELD_TRIES = 6;
    /** La distancia preferida a la aldea (entre dos lugares igual de parejos). */
    public static int FIELD_IDEAL = 220;
    /** Reconocimiento: cada cuántos grados alrededor de la aldea, el rectángulo (medio ancho, detrás del punto de reunión, más allá de las carpas) y cada cuántos bloques se mide. */
    public static int FIELD_ANGLE_STEP = 10, FIELD_HALF_WIDTH = 30, FIELD_BEHIND = 20, FIELD_BEYOND = 20, FIELD_SAMPLE = 4;
    /** El rectángulo: máximo de agua y de árboles; mínimo cerca de la altura del medio (± desnivel); escalones entre vecinos (más de tanto) como máximo. */
    public static double FIELD_MAX_WATER = 0.02, FIELD_MAX_TREES = 0.04, FIELD_FLAT_SHARE = 0.85, FIELD_MAX_CLIFFS = 0.05;
    public static int FIELD_MAX_UNEVEN = 6, FIELD_MAX_STEP = 2;
    /** Empieza la batalla cuando un ejército tuyo llega (invocado) a esta distancia del punto de reunión. Vos solo, no. */
    public static int ARMY_RANGE = 48;
    /** Te recuerda cómo empezar si estás a esta distancia del punto de reunión. */
    public static int REMIND_RANGE = 160;
    /** La hueste forma a esta distancia de donde empezó la batalla, mirándote. */
    public static double GAP = 80;
    /** Formada: espera esto (o hasta que la tocan / te acercás a {@link #ENGAGE}) y carga. */
    public static int FORM_TICKS = 20 * 6;
    /** Retirada ordenada (táctica del general): cuando ya no tiene enemigos a esto, dejó el campo. */
    public static double RETREAT_SAFE = 48;
    public static double ENGAGE = 20;
    /** Tus soldados sueltos (te siguen) pelean con los de la hueste que tengan a esta distancia. */
    public static double FOLLOWER_REACH = 40;
    /** Se desbanda cuando le queda esta parte; los que huyen corren tanto y se van a los tantos ticks. */
    public static double ROUT_SHARE = 0.2;
    public static double FLEE_DISTANCE = 40;
    public static int FLEE_TICKS = 20 * 15;
    /** Te fuiste: a más de esto del campo durante tanto, lo que queda de la hueste marcha contra la aldea. */
    public static int LEAVE_DISTANCE = 160, LEAVE_TICKS = 20 * 30;
    /** Cuenta regresiva de la marcha contra la aldea. */
    public static int MARCH_TICKS = 20 * 120;
    /** Vencerla: esmeraldas por hombre de la hueste (a tu Campamento más cercano) y opinión (cansancio de la guerra). */
    public static int LOOT_PER_SOLDIER = 1, WEARY_OPINION = 20;
    /** Los soldados de la hueste: radio de su restricción de movimiento. */
    public static int RESTRICT = 96;
    /** Marca de los soldados de una hueste (UUID de la hueste). */
    public static final String TAG_HOST = "warbandcraft:host";

    private static final int PASSIVE = 3;

    public enum Phase { FORM, ADVANCE, CHARGE, ROUT }

    /** Lo que se recuerda de una batalla en curso (no se guarda: al volver, carga). */
    private static final class Memory {
        Phase phase = Phase.CHARGE;
        long since, lastNear, routSince;
        int fled;
        /** Formada / avanzando: el centro de la formación. */
        @Nullable Vec3 anchor;
    }

    private static final Map<UUID, Memory> MEMORY = new HashMap<>();

    private Hosts() {}

    public static void clear() {
        MEMORY.clear();
    }

    // ------------------------------------------------------------------ cada día

    /** Día nuevo: las facciones juntan hombres, se recuerdan las huestes acampadas y quizás sale una nueva. */
    public static void onNewDay(ServerLevel level, boolean launch) {
        growPool(level);
        long day = level.getDayTime() / 24000L;
        for (InvasionData.Host h : InvasionData.get(level).hosts()) {
            if (h.state == InvasionData.Host.State.CAMPED && h.lastWarnDay != day) {
                h.lastWarnDay = day;
                warn(level, h);
            }
        }
        if (launch) launchAll(level);
    }

    public static void growPool(ServerLevel level) {
        InvasionData data = InvasionData.get(level);
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            int n = Invasions.settlements(level, f.id()).size();
            if (n > 0) data.setHostPool(f.id(), Math.min(POOL_CAP, data.hostPool(f.id()) + POOL_PER_SETTLEMENT * n));
        }
    }

    private static void launchAll(ServerLevel level) {
        Relations rel = Relations.get(level.getServer());
        InvasionData data = InvasionData.get(level);
        RandomSource random = level.getRandom();
        long day = level.getDayTime() / 24000L;
        List<UUID> owners = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).all()) if (v.isConquered() && !owners.contains(v.owner())) owners.add(v.owner());
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            for (UUID owner : owners) {
                if (!factionOnline(level, owner)) continue;   // pedido de Ale: solo con alguien de su facción conectado
                if (!rel.atWar(owner, f.id()) || hostOf(level, f.id(), owner) != null || data.hostCooldown(f.id(), owner) >= day) continue;
                if (data.hostPool(f.id()) < MIN_SIZE || random.nextDouble() >= chance(rel.opinion(owner, f.id()))) continue;
                create(level, f.id(), owner, -1);
            }
        }
    }

    /** Chance por día de que salga una hueste: crece con lo que te odian (de la opinión de guerra al mínimo). */
    public static double chance(int opinion) {
        double hate = (Relations.WAR_THRESHOLD - opinion) / (double) Math.max(1, Relations.WAR_THRESHOLD - Relations.MIN);
        return CHANCE_BASE + CHANCE_HATE * Mth.clamp(hate, 0, 1);
    }

    /** Tu fuerza: soldados en tus barracones y cuarteles más los que andan sueltos (cargados). */
    public static int strength(ServerLevel level, UUID owner) {
        int n = 0;
        BarracksData data = BarracksData.get(level);
        for (BlockPos camp : CampRegistry.get(level).campsOf(owner)) {
            BarracksData.Barracks b = data.at(camp);
            if (b != null) n += b.soldiers.size() + b.quarters.size();
        }
        for (Entity e : level.getAllEntities()) {
            if (e instanceof AbstractRecruitEntity s && s.isAlive() && com.ale.warbandcraft.faction.Allies.same(s.getOwnerUUID(), owner) && Soldiers.isSoldier(s)) n++;
        }
        return n;
    }

    /** Cuántos manda: tu fuerza × {@link #STRENGTH_RATIO} con algo de azar, entre el mínimo y el máximo, y lo que juntó. 0 = no alcanza. */
    public static int sizeFor(int strength, int pool, RandomSource random) {
        double jitter = 1 + SIZE_JITTER * (random.nextDouble() * 2 - 1);
        int want = Mth.clamp((int) Math.round(strength * STRENGTH_RATIO * jitter), MIN_SIZE, MAX_SIZE);
        int size = Math.min(want, pool);
        return size < MIN_SIZE ? 0 : size;
    }

    /**
     * Arma una hueste de {@code faction} contra la aldea/castillo de {@code owner} más cercano a sus tierras que tenga un
     * campo de batalla al lado. {@code size} -1 = lo que corresponda. Devuelve la hueste, o null si no hay dónde o con qué.
     */
    @Nullable
    public static InvasionData.Host create(ServerLevel level, ResourceLocation faction, UUID owner, int size) {
        InvasionData data = InvasionData.get(level);
        if (size < 0) {
            size = sizeFor(strength(level, owner), data.hostPool(faction), level.getRandom());
            if (size <= 0) return null;
        }
        List<VillageData.Village> home = Invasions.settlements(level, faction);
        List<VillageData.Village> mine = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (owner.equals(v.owner()) && !threatens(level, v.center())) mine.add(v);
        }
        mine.sort(Comparator.comparingDouble(v -> distanceToHome(v, home)));
        for (VillageData.Village v : mine) {
            BlockPos from = nearestHome(v, home);
            double angle = from == null ? level.getRandom().nextDouble() * Math.PI * 2
                    : Math.atan2(from.getZ() - v.center().getZ(), from.getX() - v.center().getX());
            Field field = findField(level, v.center(), angle);
            if (field == null) continue;
            if (data.hostPool(faction) >= size) data.setHostPool(faction, data.hostPool(faction) - size);
            else data.setHostPool(faction, 0);
            InvasionData.Host h = place(level, faction, owner, v.center(), field.muster(), size, DEADLINE_DAYS * 24000L);
            setAxis(level, h, field.axis());
            return h;
        }
        return null;
    }

    /** Planta la hueste en {@code camp} (sin controles; lo usan {@link #create}, las pruebas y el comando). */
    public static InvasionData.Host place(ServerLevel level, ResourceLocation faction, UUID owner, BlockPos target, BlockPos camp, int size,
                                         long deadlineTicks) {
        long now = level.getGameTime();
        InvasionData.Host h = new InvasionData.Host(UUID.randomUUID(), faction, owner, target, camp, size, now, now + deadlineTicks);
        h.lastWarnDay = level.getDayTime() / 24000L;
        InvasionData.get(level).putHost(h);
        warn(level, h);
        return h;
    }

    /** Hacia dónde quedan las carpas desde el punto de reunión (lo que dio el reconocimiento del terreno). */
    public static void setAxis(ServerLevel level, InvasionData.Host h, Vec3 axis) {
        h.axisX = axis.x;
        h.axisZ = axis.z;
        InvasionData.get(level).changed();
    }

    private static double distanceToHome(VillageData.Village v, List<VillageData.Village> home) {
        BlockPos h = nearestHome(v, home);
        return h == null ? 0 : VillageData.horizontalDistSq(h, v.center());
    }

    @Nullable
    private static BlockPos nearestHome(VillageData.Village v, List<VillageData.Village> home) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (VillageData.Village h : home) {
            double d = VillageData.horizontalDistSq(h.center(), v.center());
            if (d < bestD) {
                bestD = d;
                best = h.center();
            }
        }
        return best;
    }

    /** Un campo de batalla: el punto de reunión (donde invocás tu ejército) y hacia dónde quedan las carpas de la hueste. */
    public record Field(BlockPos muster, Vec3 axis) {}

    /**
     * El campo de batalla, por el terreno (no por el bioma): un rectángulo donde entran los dos ejércitos (desde
     * {@link #FIELD_BEHIND} detrás del punto de reunión hasta {@link #FIELD_BEYOND} más allá de las carpas, {@link #GAP} de
     * separación, {@link #FIELD_HALF_WIDTH} de medio ancho) sin desniveles grandes, sin árboles y sin agua, en cualquier
     * orientación, a {@link #FIELD_MIN}-{@link #FIELD_MAX} bloques de la aldea. Primero se mira el terreno que ya está
     * cargado (lo que ve el jugador); si ahí no hay nada, se cargan unos pocos lugares más. Gana el más parejo, a
     * igualdad el más cerca de {@link #FIELD_IDEAL}. Null = no hay.
     */
    @Nullable
    public static Field findField(ServerLevel level, BlockPos target, double angle) {
        Field best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<BlockPos> unloaded = new ArrayList<>();
        for (int deg = 0; deg < 360; deg += FIELD_ANGLE_STEP) {
            double a = angle + Math.toRadians(deg);
            for (int dist = FIELD_MIN; dist <= FIELD_MAX; dist += FIELD_STEP) {
                BlockPos at = new BlockPos(target.getX() + (int) Math.round(Math.cos(a) * dist), target.getY(),
                        target.getZ() + (int) Math.round(Math.sin(a) * dist));
                boolean anyUnloaded = false;
                for (int o = 0; o < 8; o++) {
                    Vec3 dir = new Vec3(Math.cos(a + o * Math.PI / 4), 0, Math.sin(a + o * Math.PI / 4));
                    double score = measure(level, target, at, dir);
                    if (score == UNLOADED) {
                        anyUnloaded = true;
                        continue;
                    }
                    score -= Math.abs(dist - FIELD_IDEAL) / 1000.0;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Field(ground(level, at), dir);
                    }
                }
                if (anyUnloaded) unloaded.add(at);
            }
        }
        if (best != null) return best;
        // Nada en lo cargado: se cargan los lugares más cercanos al ideal y se vuelven a medir
        unloaded.sort(Comparator.comparingDouble(p -> Math.abs(Math.sqrt(VillageData.horizontalDistSq(p, target)) - FIELD_IDEAL)));
        for (int i = 0; i < unloaded.size() && i < FIELD_TRIES; i++) {
            BlockPos at = unloaded.get(i);
            loadArea(level, at);
            for (int o = 0; o < 8; o++) {
                Vec3 dir = new Vec3(Math.cos(o * Math.PI / 4), 0, Math.sin(o * Math.PI / 4));
                double score = measure(level, target, at, dir);
                if (score > bestScore && score != UNLOADED) {
                    bestScore = score;
                    best = new Field(ground(level, at), dir);
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static final double UNLOADED = -1000, INVALID = -999;

    private static BlockPos ground(ServerLevel level, BlockPos at) {
        return new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
    }

    /**
     * Mide el rectángulo de la batalla con el punto de reunión en {@code at} y las carpas hacia {@code dir}. Devuelve qué tan
     * parejo es (más = mejor), {@link #INVALID} si no sirve (árboles, agua, desniveles, una aldea cerca) o {@link #UNLOADED}
     * si una parte no está cargada.
     */
    private static double measure(ServerLevel level, BlockPos village, BlockPos at, Vec3 dir) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        Vec3 base = Vec3.atBottomCenterOf(at);
        int rows = (int) ((FIELD_BEHIND + GAP + FIELD_BEYOND) / FIELD_SAMPLE) + 1, cols = (int) (2 * FIELD_HALF_WIDTH / FIELD_SAMPLE) + 1;
        int[][] h = new int[rows][cols];
        int total = 0, trees = 0, water = 0;
        double villageR = Armies.VILLAGE_DISTANCE + 16;
        if (com.ale.warbandcraft.dungeon.DungeonData.get(level).near(at, (int) (FIELD_BEHIND + GAP + FIELD_BEYOND)) != null) return INVALID;   // H25
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Vec3 p = base.add(dir.scale(-FIELD_BEHIND + r * FIELD_SAMPLE)).add(side.scale(-FIELD_HALF_WIDTH + c * FIELD_SAMPLE));
                int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
                if (!level.hasChunk(x >> 4, z >> 4)) return UNLOADED;
                if ((r == 0 || r == rows - 1) && c == cols / 2 && VillageData.horizontalDistSq(new BlockPos(x, 0, z), village) < villageR * villageR) {
                    return INVALID;
                }
                total++;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                h[r][c] = ground;
                // H25: una estructura de otro mod (o una guarida/túmulo) en el campo lo invalida
                if (r % 2 == 0 && c % 2 == 0 && com.ale.warbandcraft.dungeon.Compat.foreignStructureAt(level, new BlockPos(x, ground, z))) return INVALID;
                if (top > ground || level.getBlockState(new BlockPos(x, ground - 1, z)).is(BlockTags.LOGS)) trees++;
                if (!level.getFluidState(new BlockPos(x, ground - 1, z)).isEmpty()) water++;
            }
        }
        if (water > total * FIELD_MAX_WATER || trees > total * FIELD_MAX_TREES) return INVALID;
        // Sin desniveles grandes: casi todo cerca de la altura del medio, y sin escalones (barrancos) entre vecinos
        int[] all = new int[total];
        int i = 0;
        for (int[] row : h) for (int v : row) all[i++] = v;
        java.util.Arrays.sort(all);
        int median = all[total / 2];
        int near = 0, cliffs = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (Math.abs(h[r][c] - median) <= FIELD_MAX_UNEVEN) near++;
                if (r > 0 && Math.abs(h[r][c] - h[r - 1][c]) > FIELD_MAX_STEP) cliffs++;
                if (c > 0 && Math.abs(h[r][c] - h[r][c - 1]) > FIELD_MAX_STEP) cliffs++;
            }
        }
        if (near < total * FIELD_FLAT_SHARE || cliffs > total * FIELD_MAX_CLIFFS) return INVALID;
        return near / (double) total - 2.0 * trees / total - cliffs / (double) total;
    }

    /** Carga (y genera si hace falta) los chunks alrededor de un lugar, para poder medirlo en cualquier orientación. */
    private static void loadArea(ServerLevel level, BlockPos at) {
        int r = (int) (FIELD_BEHIND + GAP + FIELD_BEYOND) + 4;
        for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++) {
            for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) level.getChunk(cx, cz);
        }
    }

    /** Dónde están las carpas de la hueste: {@link #GAP} bloques más allá del punto de reunión, lejos de la aldea. */
    public static BlockPos tents(InvasionData.Host h) {
        Vec3 away = h.axisX != 0 || h.axisZ != 0 ? flat(new Vec3(h.axisX, 0, h.axisZ))
                : flat(Vec3.atBottomCenterOf(h.camp).subtract(Vec3.atBottomCenterOf(h.target)));
        return BlockPos.containing(Vec3.atBottomCenterOf(h.camp).add(away.scale(GAP)));
    }

    /** ¿Hay una hueste contra esta aldea/castillo? */
    public static boolean threatens(ServerLevel level, BlockPos target) {
        return hostAt(level, target) != null;
    }

    @Nullable
    public static InvasionData.Host hostAt(ServerLevel level, BlockPos target) {
        for (InvasionData.Host h : InvasionData.get(level).hosts()) if (h.target.equals(target)) return h;
        return null;
    }

    @Nullable
    public static InvasionData.Host hostOf(ServerLevel level, ResourceLocation faction, UUID owner) {
        for (InvasionData.Host h : InvasionData.get(level).hosts()) if (h.faction.equals(faction) && h.owner.equals(owner)) return h;
        return null;
    }

    /** El aviso al dueño (si está conectado): quién, cuántos, dónde y cuánto falta. */
    public static void warn(ServerLevel level, InvasionData.Host h) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        if (p == null || h.state != InvasionData.Host.State.CAMPED) return;
        long left = Math.max(0, h.deadline - level.getGameTime());
        p.sendSystemMessage(Component.translatable("msg.warbandcraft.host.camped", Invasions.factionName(h.faction), h.size,
                placeName(level, h), days(left), h.camp.getX(), h.camp.getZ(), ARMY_RANGE)
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.7F, 0.8F);
    }

    /** "2 días y 5 h" (un día de juego = 20 min). */
    public static String days(long ticks) {
        long d = ticks / 24000L, hours = (ticks % 24000L) / 1000L;
        return d > 0 ? d + " d " + hours + " h" : hours > 0 ? hours + " h" : Invasions.time(ticks);
    }

    static Component placeName(ServerLevel level, InvasionData.Host h) {
        VillageData.Village v = VillageData.get(level).at(h.target);
        if (v == null) return Component.literal(h.target.getX() + ", " + h.target.getZ());
        return VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
    }

    // ------------------------------------------------------------------ cada segundo

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (InvasionData.Host h : new ArrayList<>(InvasionData.get(level).hosts())) {
            VillageData.Village v = VillageData.get(level).at(h.target);
            if (h.state == InvasionData.Host.State.CAMPED) {
                if (v == null || !h.owner.equals(v.owner())) {   // la aldea ya no es tuya: se vuelven
                    disband(level, h);
                    continue;
                }
                campedTick(level, h, now);
            } else {
                battleTick(level, h, now);
            }
        }
    }

    /** Pedido de Ale: la Hueste solo sale (y su plazo solo corre) con el dueño o alguien de su facción conectado. */
    public static boolean factionOnline(ServerLevel level, UUID owner) {
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return true;   // pruebas: FakePlayer
        return com.ale.warbandcraft.faction.Allies.anyOnline(level.getServer(), owner);
    }

    private static void campedTick(ServerLevel level, InvasionData.Host h, long now) {
        if (!factionOnline(level, h.owner)) {   // sin nadie conectado el plazo queda quieto (tick cada 20)
            h.deadline += 20;
            InvasionData.get(level).setDirty();
            return;
        }
        if (now >= h.deadline) {
            marchOn(level, h, "msg.warbandcraft.host.marches");
            return;
        }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        if (p == null || p.level() != level || !p.isAlive()) return;
        Vec3 trigger = null;   // solo con un ejército tuyo invocado ahí (vos solo o con los que te siguen, no)
        for (ArmyData.Army a : ArmyData.get(level).of(h.owner)) {
            if (a.state == ArmyData.State.FIELD && horizontalDist(a.anchor, Vec3.atBottomCenterOf(h.camp)) <= ARMY_RANGE) trigger = a.anchor;
        }
        double toCamp = horizontalDist(p.position(), Vec3.atBottomCenterOf(h.camp));
        if (trigger != null && level.isPositionEntityTicking(BlockPos.containing(trigger))) {
            startBattle(level, h, trigger);
            return;
        }
        if (toCamp <= REMIND_RANGE && now % 100 < 20) {
            p.displayClientMessage(Component.translatable("msg.warbandcraft.host.remind", Invasions.factionName(h.faction), h.size,
                    h.camp.getX(), h.camp.getZ(), (int) toCamp).withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** La aldea ya no es tuya: la hueste se vuelve (los hombres vuelven a juntarse). */
    private static void disband(ServerLevel level, InvasionData.Host h) {
        InvasionData data = InvasionData.get(level);
        data.setHostPool(h.faction, Math.min(POOL_CAP, data.hostPool(h.faction) + h.size));
        end(level, h);
    }

    private static void end(ServerLevel level, InvasionData.Host h) {
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(h));
        InvasionData.get(level).removeHost(h.id);
        MEMORY.remove(h.id);
        BattleBar.close(barKey(h));
    }

    /**
     * La hueste marcha contra la aldea con lo que le queda: una invasión con general (si ya había una en camino, se le
     * suma). Se pelea de verdad si estás ahí (con oleadas) y si no, se autoresuelve con esos números.
     */
    @Nullable
    public static InvasionData.Invasion marchOn(ServerLevel level, InvasionData.Host h, String messageKey) {
        end(level, h);
        InvasionData data = InvasionData.get(level);
        data.setHostCooldown(h.faction, h.owner, level.getDayTime() / 24000L + COOLDOWN_DAYS);
        VillageData.Village v = VillageData.get(level).at(h.target);
        if (h.size <= 0) return null;
        if (v == null || !h.owner.equals(v.owner())) {
            data.setHostPool(h.faction, Math.min(POOL_CAP, data.hostPool(h.faction) + h.size));
            return null;
        }
        InvasionData.Invasion inv = data.at(h.target);
        if (inv != null) {   // ya había una: se suman
            inv.size += h.size;
            inv.attackers += h.size;
            if (inv.state == InvasionData.State.FIGHTING) inv.reserve += h.size;
            else inv.kind = InvasionData.Kind.ARMY;
            data.changed();
        } else {
            long now = level.getGameTime();
            inv = new InvasionData.Invasion(h.target, h.faction, h.owner, h.size, now + MARCH_TICKS, h.camp);
            inv.startTick = now;
            inv.kind = InvasionData.Kind.ARMY;
            data.put(inv);
        }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        if (p != null) {
            p.sendSystemMessage(Component.translatable(messageKey, Invasions.factionName(h.faction), h.size, placeName(level, h),
                    Invasions.time(Math.max(0, inv.arriveTick - level.getGameTime())), Invasions.REAL_DISTANCE)
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.8F, 0.7F);
        }
        return inv;
    }

    // ------------------------------------------------------------------ la batalla

    /**
     * ¡Batalla! La hueste aparece formada a {@link #GAP} bloques de {@code trigger} (tu ejército, o vos), mirándote:
     * hasta {@link BattleSize#MAX_PER_SIDE}; el resto espera. Devuelve los que aparecieron.
     */
    public static List<AbstractRecruitEntity> startBattle(ServerLevel level, InvasionData.Host h, Vec3 trigger) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        Faction f = FactionManager.INSTANCE.get(h.faction);
        if (f == null) {
            end(level, h);
            return out;
        }
        Vec3 camp = Vec3.atBottomCenterOf(tents(h));
        Vec3 dir = flat(camp.subtract(trigger));
        if (horizontalDist(camp, trigger) < 1) dir = flat(camp.subtract(Vec3.atBottomCenterOf(h.target)));
        Vec3 center = trigger.add(dir.scale(GAP));
        for (double d = GAP; d > 30 && !level.isPositionEntityTicking(BlockPos.containing(center)); d -= 10) {
            center = trigger.add(dir.scale(d - 10));   // lo más lejos que esté cargado, para que se la vea venir
        }
        center = Armies.ground(level, center, trigger.y);
        Vec3 facing = dir.scale(-1);
        h.facingX = facing.x;
        h.facingZ = facing.z;
        h.front = BlockPos.containing(center);
        h.startSize = h.size;
        h.reserve = h.size;
        h.state = InvasionData.Host.State.FIGHTING;

        int n = Math.min(h.size, BattleSize.MAX_PER_SIDE);
        for (int i = 0; i < n; i++) {
            h.reserve--;
            AbstractRecruitEntity s = spawnOne(level, h, f, center);
            if (s != null) out.add(s);
        }
        for (Map.Entry<AbstractRecruitEntity, Vec3> e : Armies.layout(center, facing, out).entrySet()) {
            Vec3 slot = Armies.ground(level, e.getValue(), center.y);
            AbstractRecruitEntity s = e.getKey();
            s.getRootVehicle().teleportTo(slot.x, slot.y, slot.z);
            s.setYRot((float) (Mth.atan2(facing.z, facing.x) * Mth.RAD_TO_DEG) - 90.0F);
            s.setYHeadRot(s.getYRot());
            s.setFollowState(3);   // a su puesto (primero el estado: el 2 pisa el puesto)
            s.setHoldPos(slot);
        }
        h.size = out.size() + h.reserve;
        InvasionData.get(level).changed();
        Memory m = new Memory();
        m.phase = Phase.FORM;
        m.since = m.lastNear = level.getGameTime();
        m.anchor = center;
        MEMORY.put(h.id, m);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        if (p != null) {
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.host.battle", Invasions.factionName(h.faction), out.size(), h.reserve)
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        return out;
    }

    @Nullable
    private static AbstractRecruitEntity spawnOne(ServerLevel level, InvasionData.Host h, Faction f, Vec3 at) {
        Troop troop = f.pickTroop(level.getRandom());
        if (troop == null) return null;
        AbstractRecruitEntity s = VillageManager.spawnTroop(level, BlockPos.containing(at), f, troop, level.getRandom(), 4, RESTRICT);
        if (s == null) return null;
        var pd = s.getPersistentData();
        pd.putLong(VillageManager.TAG_VILLAGE, h.camp.asLong());
        pd.putBoolean(Ambush.TAG_FIELD, true);
        pd.putString(TAG_HOST, h.id.toString());
        return s;
    }

    /** Los de la hueste que siguen en pie (cargados). */
    public static List<AbstractRecruitEntity> soldiers(ServerLevel level, InvasionData.Host h) {
        String id = h.id.toString();
        double r = LEAVE_DISTANCE;
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(h.front).inflate(r, 64, r),
                e -> e.isAlive() && id.equals(e.getPersistentData().getString(TAG_HOST)) && !Surrender.is(e));
    }

    /** Contra quiénes pelea: vos (si estás cerca) y tus soldados. */
    private static List<LivingEntity> enemies(ServerLevel level, InvasionData.Host h, @Nullable ServerPlayer p) {
        double r = LEAVE_DISTANCE;
        List<LivingEntity> out = new ArrayList<>(level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(h.front).inflate(r, 48, r),
                e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), h.owner) && Soldiers.isSoldier(e) && !Surrender.is(e)));
        if (p != null && p.level() == level && p.isAlive() && !p.isSpectator() && !p.isCreative()
                && horizontalDist(p.position(), Vec3.atBottomCenterOf(h.front)) <= r) out.add(p);
        return out;
    }

    /** Una vuelta de la batalla: refuerzos, el general (formar → cargar), desbandarse, ganar o que te vayas. */
    public static void battleTick(ServerLevel level, InvasionData.Host h, long now) {
        Memory m = MEMORY.computeIfAbsent(h.id, k -> {
            Memory x = new Memory();
            x.since = x.lastNear = now;
            return x;
        });
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        boolean near = p != null && p.level() == level && p.isAlive()
                && horizontalDist(p.position(), Vec3.atBottomCenterOf(h.front)) <= LEAVE_DISTANCE;
        if (near) m.lastNear = now;
        List<AbstractRecruitEntity> mine = soldiers(level, h);
        if (!level.isPositionEntityTicking(h.front) || now - m.lastNear > LEAVE_TICKS) {   // te fuiste: siguen hacia la aldea
            if (m.phase == Phase.ROUT) {
                victory(level, h, m);
                return;
            }
            for (AbstractRecruitEntity s : mine) leave(s);
            h.size = mine.size() + h.reserve;
            h.reserve = 0;
            marchOn(level, h, "msg.warbandcraft.host.you_left");
            return;
        }
        Faction f = FactionManager.INSTANCE.get(h.faction);
        Vec3 facing = new Vec3(h.facingX, 0, h.facingZ);
        // Oleadas: entran por la retaguardia
        if (m.phase != Phase.ROUT && f != null && h.reserve > 0) {
            int room = BattleSize.room(mine.size(), h.reserve);
            Vec3 front = Vec3.atBottomCenterOf(h.front);
            Vec3 rear = front;   // lo más atrás que esté cargado (si no, no aparecen y se perderían)
            for (double back = Armies.RANGED_BEHIND + Armies.REINFORCE_BEHIND; back > 0; back -= 4) {
                Vec3 at = front.subtract(facing.scale(back));
                if (level.isPositionEntityTicking(BlockPos.containing(at))) {
                    rear = at;
                    break;
                }
            }
            rear = Armies.ground(level, rear, h.front.getY());
            for (int i = 0; i < room; i++) {
                AbstractRecruitEntity s = spawnOne(level, h, f, rear);
                if (s == null) s = spawnOne(level, h, f, front);   // otro intento, en el frente
                h.reserve--;   // si ni así aparece, se pierde (no queda trabada la batalla esperándolo)
                if (s != null) mine.add(s);
            }
            InvasionData.get(level).changed();
        }
        int left = mine.size() + h.reserve;
        if (left != h.size) {
            h.size = left;
            InvasionData.get(level).changed();
        }
        List<LivingEntity> enemies = enemies(level, h, near ? p : null);
        BattleBar.update(barKey(h), new BattleBar.Side(Component.translatable("msg.warbandcraft.bar.yours"), enemies.size()),
                new BattleBar.Side(Invasions.factionName(h.faction), left), true, near ? List.of(p) : List.of());

        if (m.phase == Phase.ROUT) {
            for (AbstractRecruitEntity s : mine) {
                if (horizontalDist(s.position(), Vec3.atBottomCenterOf(h.front)) >= FLEE_DISTANCE - 8 || now - m.routSince >= FLEE_TICKS) {
                    leave(s);
                    m.fled++;
                }
            }
            if (soldiers(level, h).isEmpty()) victory(level, h, m);
            return;
        }
        if (left == 0) {
            victory(level, h, m);
            return;
        }
        if (h.startSize > 0 && left <= h.startSize * ROUT_SHARE) {   // ¡se desbandan!
            rout(level, h, m, mine, facing, p);
            return;
        }
        if (m.phase == Phase.FORM) {   // recién formada: espera un momento (salvo que la toquen) y el general toma el mando
            boolean contact = now - m.since >= FORM_TICKS;
            for (AbstractRecruitEntity s : mine) {
                if (contact) break;
                if (s.getLastHurtByMob() != null || nearest(s.position(), enemies, ENGAGE) != null) contact = true;
            }
            if (!contact) return;
            m.phase = Phase.ADVANCE;
        }
        // H24: el general de la hueste (tácticas como en Bannerlord: avanzar en línea, rodear, hostigar, retirarse...)
        m.fled += com.ale.warbandcraft.battle.tactics.TeamAI.tick(level, aiKey(h), mine, enemies,
                new com.ale.warbandcraft.battle.tactics.TeamAI.Context(Vec3.atBottomCenterOf(tents(h)), null, true));
        com.ale.warbandcraft.battle.tactics.TeamAI ai = com.ale.warbandcraft.battle.tactics.TeamAI.get(aiKey(h));
        if (ai != null && ai.tactic() == com.ale.warbandcraft.battle.tactics.Tactics.Tactic.RETREAT && !mine.isEmpty()
                && nearest(com.ale.warbandcraft.battle.BattleGeneral.centroid(mine), enemies, RETREAT_SAFE) == null) {
            rout(level, h, m, mine, facing, p);   // se retiraron del campo: ganaste
            return;
        }
        // Tus soldados sueltos que te siguen (no los de un ejército ni con la orden Batalla) pelean con lo que tengan cerca
        for (LivingEntity e : enemies) {
            if (!(e instanceof AbstractRecruitEntity s) || Armies.inArmy(s) || com.ale.warbandcraft.battle.BattleGeneral.inBattle(s)) continue;
            int st = s.getFollowState();
            if (st != 0 && st != 1 && st != 5) continue;
            LivingEntity t = s.getTarget();
            if (t != null && t.isAlive()) continue;
            LivingEntity best = nearest(s.position(), mine, FOLLOWER_REACH);
            if (best != null) s.setTarget(best);
        }
    }

    /** Se desbandan: los que esperaban ni entran, los que están corren para atrás y se van. */
    private static void rout(ServerLevel level, InvasionData.Host h, Memory m, List<AbstractRecruitEntity> mine, Vec3 facing,
                             @Nullable ServerPlayer p) {
        m.phase = Phase.ROUT;
        m.routSince = level.getGameTime();
        m.fled += h.reserve;
        h.reserve = 0;
        for (AbstractRecruitEntity s : mine) {
            s.setTarget(null);
            s.setAggroState(PASSIVE);
            Vec3 away = Armies.ground(level, s.position().add(facing.scale(-FLEE_DISTANCE)), s.getY());
            s.setFollowState(3);
            s.setHoldPos(away);
        }
        InvasionData.get(level).changed();
        if (p != null) {
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.host.rout", Invasions.factionName(h.faction))
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        }
    }

    /**
     * ¡Ganaste! La hueste no existe más: botín al Campamento más cercano, experiencia a tus soldados de cerca, la facción
     * se cansa de la guerra y por unos días no manda otra. Los que huyeron vuelven a juntarse.
     */
    private static void victory(ServerLevel level, InvasionData.Host h, Memory m) {
        end(level, h);
        InvasionData data = InvasionData.get(level);
        data.setHostCooldown(h.faction, h.owner, level.getDayTime() / 24000L + DEFEAT_COOLDOWN_DAYS);
        data.setHostPool(h.faction, Math.min(POOL_CAP, data.hostPool(h.faction) + m.fled));
        Relations.get(level.getServer()).add(h.owner, h.faction, WEARY_OPINION);
        int loot = LOOT_PER_SOLDIER * h.startSize;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(h.owner);
        int veterans = 0;
        double r = LEAVE_DISTANCE;
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(h.front).inflate(r, 48, r),
                e -> e.isAlive() && com.ale.warbandcraft.faction.Allies.same(e.getOwnerUUID(), h.owner) && Soldiers.isSoldier(e))) {
            s.addXp(Armies.VICTORY_XP);
            s.checkLevel();
            veterans++;
        }
        if (p != null) {
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.host.victory", Invasions.factionName(h.faction), loot, veterans,
                    WEARY_OPINION, DEFEAT_COOLDOWN_DAYS).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8F, 1.0F);
        }
        // El botín (equipo de los caídos + armamento + estas esmeraldas) al Campamento, aldea o castillo más cercano
        com.ale.warbandcraft.battle.BattleLoot.bonus(level, h.owner, loot, h.front);
        com.ale.warbandcraft.battle.BattleLoot.flush(level, h.owner);
    }

    private static void leave(AbstractRecruitEntity s) {
        com.ale.warbandcraft.nav.Mover.stop(s);
        if (s.getVehicle() instanceof AbstractHorse horse) horse.discard();
        s.discard();
    }

    /** Un soldado de una hueste que se carga del disco: solo sigue si su batalla sigue. */
    public static boolean keepSoldier(ServerLevel level, Entity e) {
        String id = e.getPersistentData().getString(TAG_HOST);
        if (id.isEmpty()) return true;
        for (InvasionData.Host h : InvasionData.get(level).hosts()) {
            if (h.state == InvasionData.Host.State.FIGHTING && h.id.toString().equals(id)) return true;
        }
        return false;
    }

    public static boolean isHostSoldier(Entity e) {
        return e.getPersistentData().contains(TAG_HOST);
    }

    /** En qué fase está la batalla de una hueste (para las pruebas). */
    @Nullable
    public static Phase phase(InvasionData.Host h) {
        Memory m = MEMORY.get(h.id);
        return m == null ? null : m.phase;
    }

    /** La clave del general de la hueste ({@link com.ale.warbandcraft.battle.tactics.TeamAI}). */
    public static String aiKey(InvasionData.Host h) {
        return "host:" + h.id;
    }

    private static String barKey(InvasionData.Host h) {
        return "host:" + h.id;
    }

    @Nullable
    private static LivingEntity nearest(Vec3 from, List<? extends LivingEntity> list, double reach) {
        LivingEntity best = null;
        double bestD = reach * reach;
        for (LivingEntity e : list) {
            double d = e.position().distanceToSqr(from);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        return f.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : f.normalize();
    }
}
