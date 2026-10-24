package com.ale.warbandcraft.army;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.BattleSize;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.invasion.Ambush;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ejércitos de campaña (H20): armarlos con soldados de distintos barracones, invocarlos en un campo de batalla (llegan
 * formados después de una cuenta regresiva), mandarlos (Mantener, Avanzar, Cargar, Retirada) y licenciarlos.
 * Quietos mantienen el terreno: cada uno vuelve a su puesto y solo pelea con lo que se le acerca.
 */
public final class Armies {
    /** En el soldado: el ejército al que pertenece (UUID como texto). */
    public static final String TAG_ARMY = "warbandcraft:army";
    /** Biomas donde se puede invocar un ejército (editable con un datapack). */
    public static final TagKey<Biome> BATTLEFIELD = TagKey.create(Registries.BIOME, new ResourceLocation(WarbandCraft.MODID, "battlefield"));

    /** Máximo de soldados por ejército. */
    public static int MAX_SOLDIERS = 100;
    /** Máximo de ejércitos por jugador. */
    public static int MAX_ARMIES = 3;
    /** Velocidad de marcha (bloques por segundo) para la cuenta regresiva, antes de dividir. */
    public static double MARCH_SPEED = 4.3;
    /** El viaje del Campamento más lejano se divide por esto (llegan más rápido que caminando). */
    public static double TRAVEL_DIVISOR = 3.0;
    /** Cuenta regresiva mínima y máxima, en segundos. */
    public static int MIN_WAIT_SECONDS = 10, MAX_WAIT_SECONDS = 180;
    /** Si te alejás más que esto del lugar mientras llegan, se cancela. */
    public static int CANCEL_DISTANCE = 64;
    /** Campo de batalla: medio ancho (de costado) y medio largo (hacia adelante y atrás) que se miden. */
    public static int FIELD_HALF_WIDTH = 30, FIELD_HALF_DEPTH = 20;
    /** Desnivel aceptado respecto del centro y qué parte del campo tiene que cumplirlo. */
    public static int FIELD_MAX_UNEVEN = 4;
    public static double FIELD_FLAT_SHARE = 0.7;
    /** Como mucho esta parte del campo con árboles (no importa el bioma: importa el terreno). */
    public static double FIELD_MAX_TREES = 0.15;
    /** No se invoca a menos de esto de una aldea o castillo (tuyo o ajeno). */
    public static int VILLAGE_DISTANCE = 64;
    /** Formación: soldados por fila de un bloque, filas por bloque, separación y hueco entre bloques. */
    public static int BLOCK_WIDTH = 5, BLOCK_RANKS = 2;
    public static double SPACING = 1.75, BLOCK_GAP = 2.0, RANK_GAP = 1.75;
    /** Los arqueros forman tantos bloques detrás de la infantería. */
    public static double RANGED_BEHIND = 6.0;
    /** Manteniendo: pelean con lo que esté a esto de su puesto, y los arqueros tiran hasta esto. */
    public static double HOLD_ENGAGE = 16.0, RANGED_REACH = 32.0;
    /** Cargando: buscan enemigos hasta esto del centro del ejército; sin enemigos por tantos ticks vuelven a formar. */
    public static double CHARGE_REACH = 64.0;
    public static int CHARGE_CALM_TICKS = 100;
    /** Retirada: la formación se corre tantos bloques para atrás. */
    public static double RETREAT_DISTANCE = 24.0;
    /** Con el dueño más lejos que esto (o desconectado), el ejército vuelve solo a sus barracones. */
    public static int KEEP_DISTANCE = 256;
    /** Emboscada: la primera carga desde un lugar con árboles pega esto más, durante tantos ticks. */
    public static double AMBUSH_BONUS = 0.3;
    public static int AMBUSH_TICKS = 200;
    /** Qué parte de las columnas cerca del ejército tienen que tener hojas para contar como escondido. */
    public static double HIDDEN_SHARE = 0.25;
    /** Experiencia de Recruits para cada sobreviviente al ganar una batalla de campo. */
    public static int VICTORY_XP = 40;
    /** H23: las oleadas de refuerzo aparecen tanto más atrás de los arqueros. */
    public static double REINFORCE_BEHIND = 10.0;

    private static final Map<UUID, ServerBossEvent> BARS = new HashMap<>();

    private Armies() {}

    // ------------------------------------------------------------------ armar

    public static Component create(ServerLevel level, UUID owner, String name) {
        ArmyData data = ArmyData.get(level);
        if (data.of(owner).size() >= MAX_ARMIES) return Component.translatable("msg.warbandcraft.army.too_many", MAX_ARMIES).withStyle(ChatFormatting.RED);
        String n = name == null || name.isBlank() ? Component.translatable("gui.warbandcraft.army.default_name", data.of(owner).size() + 1).getString()
                : name.strip();
        if (n.length() > 32) n = n.substring(0, 32);
        data.put(new ArmyData.Army(UUID.randomUUID(), owner, n));
        return Component.translatable("msg.warbandcraft.army.created", n).withStyle(ChatFormatting.GREEN);
    }

    @Nullable
    public static ArmyData.Army mine(ServerLevel level, UUID owner, @Nullable UUID id) {
        if (id == null) return null;
        ArmyData.Army a = ArmyData.get(level).get(id);
        return a != null && a.owner.equals(owner) ? a : null;
    }

    public static Component rename(ServerLevel level, ArmyData.Army a, String name) {
        String n = name.strip();
        if (n.isEmpty()) return null;
        a.name = n.length() > 32 ? n.substring(0, 32) : n;
        ArmyData.get(level).changed();
        return null;
    }

    /** Borra el ejército; si estaba en el campo, primero vuelven todos a sus barracones. */
    public static Component delete(ServerLevel level, ArmyData.Army a) {
        if (a.state != ArmyData.State.IDLE) dismiss(level, a);
        ArmyData.get(level).remove(a.id);
        return Component.translatable("msg.warbandcraft.army.deleted", a.name);
    }

    /** Cuántos soldados de la fila {@code row} hay en el barracón de {@code camp}. */
    public static int available(ServerLevel level, BlockPos camp, String row) {
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        if (b == null) return 0;
        int n = 0;
        for (BarracksData.Soldier s : b.soldiers) if (s.rowKey().equals(row)) n++;
        return n;
    }

    /**
     * Suma (o resta) {@code delta} al pedido de esa fila; {@code Integer.MAX_VALUE} = todos los que haya. Se recorta a
     * lo que hay en el barracón y al máximo del ejército. Solo con el ejército en casa.
     */
    public static Component assign(ServerLevel level, ArmyData.Army a, BlockPos camp, String row, int delta) {
        if (a.state != ArmyData.State.IDLE) return Component.translatable("msg.warbandcraft.army.busy").withStyle(ChatFormatting.RED);
        if (!CampRegistry.get(level).isCampOf(a.owner, camp)) return null;
        ArmyData.Pick p = a.pick(camp, row);
        int current = p == null ? 0 : p.count;
        int others = a.total() - current;
        int avail = available(level, camp, row);
        long wanted = delta == Integer.MAX_VALUE ? avail : (long) current + delta;
        int next = (int) Math.max(0, Math.min(Math.min(wanted, avail), MAX_SOLDIERS - others));
        if (p == null && next > 0) a.picks.add(new ArmyData.Pick(camp, row, next));
        else if (p != null && next == 0) a.picks.remove(p);
        else if (p != null) p.count = next;
        ArmyData.get(level).changed();
        if (wanted > next && others + next >= MAX_SOLDIERS) {
            return Component.translatable("msg.warbandcraft.army.full", MAX_SOLDIERS).withStyle(ChatFormatting.GOLD);
        }
        return null;
    }

    /** Lo que de verdad va a salir: lo pedido recortado a lo que hay hoy en cada barracón. */
    public static int ready(ServerLevel level, ArmyData.Army a) {
        int n = 0;
        for (ArmyData.Pick p : a.picks) n += Math.min(p.count, available(level, p.camp, p.row));
        return n;
    }

    // ------------------------------------------------------------------ invocar

    /** Por qué no se puede invocar un ejército en {@code pos} mirando hacia {@code facing} (null = se puede). */
    @Nullable
    public static Component fieldProblem(ServerLevel level, BlockPos pos, Vec3 facing) {
        VillageData.Village v = VillageData.get(level).nearest(pos, VILLAGE_DISTANCE);
        if (v != null) return Component.translatable("msg.warbandcraft.army.in_village");
        // H25: ni encima de una mazmorra de otro mod ni de una guarida/túmulo
        if (com.ale.warbandcraft.dungeon.Compat.surfaceStructureIn(level, pos.getX() - FIELD_HALF_WIDTH, pos.getZ() - FIELD_HALF_WIDTH,
                pos.getX() + FIELD_HALF_WIDTH, pos.getZ() + FIELD_HALF_WIDTH)
                || com.ale.warbandcraft.dungeon.DungeonData.get(level).near(pos, FIELD_HALF_WIDTH + com.ale.warbandcraft.dungeon.Dungeons.FOOTPRINT) != null) {
            return Component.translatable("msg.warbandcraft.army.structure");
        }
        Vec3 f = flat(facing);
        Vec3 side = new Vec3(-f.z, 0, f.x);
        int baseY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int total = 0, flat = 0, water = 0, trees = 0;
        for (int a = -FIELD_HALF_WIDTH; a <= FIELD_HALF_WIDTH; a += 4) {
            for (int d = -FIELD_HALF_DEPTH; d <= FIELD_HALF_DEPTH; d += 4) {
                Vec3 p = Vec3.atCenterOf(pos).add(side.scale(a)).add(f.scale(d));
                BlockPos col = BlockPos.containing(p.x, baseY, p.z);
                if (!level.isLoaded(col)) continue;
                total++;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
                boolean wet = !level.getFluidState(new BlockPos(col.getX(), y - 1, col.getZ())).isEmpty();
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ()) > y) trees++;
                if (wet && Math.abs(a) <= FIELD_HALF_WIDTH / 2 && Math.abs(d) <= FIELD_HALF_DEPTH / 2) water++;
                if (!wet && Math.abs(y - baseY) <= FIELD_MAX_UNEVEN) flat++;
            }
        }
        if (water > 0) return Component.translatable("msg.warbandcraft.army.water");
        if (trees > total * FIELD_MAX_TREES) return Component.translatable("msg.warbandcraft.army.trees");
        if (total == 0 || flat < total * FIELD_FLAT_SHARE) return Component.translatable("msg.warbandcraft.army.uneven");
        return null;
    }

    public static boolean biomeOk(Holder<Biome> biome) {
        return biome.is(BATTLEFIELD);
    }

    /** Segundos que tarda en llegar el ejército a {@code pos}: lo que tarda el Campamento más lejano, más rápido. */
    public static int waitSeconds(ArmyData.Army a, BlockPos pos) {
        double far = 0;
        for (ArmyData.Pick p : a.picks) {
            double dx = p.camp.getX() - pos.getX(), dz = p.camp.getZ() - pos.getZ();
            far = Math.max(far, Math.sqrt(dx * dx + dz * dz));
        }
        return Mth.clamp((int) Math.round(far / MARCH_SPEED / TRAVEL_DIVISOR), MIN_WAIT_SECONDS, MAX_WAIT_SECONDS);
    }

    /** "Invocar aquí": empieza la cuenta regresiva en el lugar del jugador, mirando hacia donde mira. */
    public static Component summon(ServerPlayer player, ArmyData.Army a) {
        ServerLevel level = player.serverLevel();
        if (a.state != ArmyData.State.IDLE) return Component.translatable("msg.warbandcraft.army.busy").withStyle(ChatFormatting.RED);
        if (ready(level, a) == 0) return Component.translatable("msg.warbandcraft.army.empty").withStyle(ChatFormatting.RED);
        Vec3 facing = flat(player.getLookAngle());
        Component problem = fieldProblem(level, player.blockPosition(), facing);
        if (problem != null) return problem.copy().withStyle(ChatFormatting.RED);
        int seconds = waitSeconds(a, player.blockPosition());
        a.state = ArmyData.State.COMING;
        a.anchor = player.position();
        a.facing = facing;
        a.summonTick = level.getGameTime();
        a.arriveTick = a.summonTick + seconds * 20L;
        ArmyData.get(level).changed();
        updateBar(level, a);
        return Component.translatable("msg.warbandcraft.army.coming", a.name, seconds).withStyle(ChatFormatting.GREEN);
    }

    public static Component cancel(ServerLevel level, ArmyData.Army a) {
        if (a.state != ArmyData.State.COMING) return null;
        a.state = ArmyData.State.IDLE;
        ArmyData.get(level).changed();
        removeBar(a);
        return Component.translatable("msg.warbandcraft.army.cancelled", a.name).withStyle(ChatFormatting.GOLD);
    }

    /**
     * Llegan: salen de sus barracones y aparecen formados alrededor de {@code anchor} mirando hacia {@code facing}.
     * Devuelve los soldados que aparecieron (las pruebas lo llaman directo).
     */
    public static List<AbstractRecruitEntity> arrive(ServerLevel level, ArmyData.Army a, Vec3 anchor, Vec3 facing) {
        removeBar(a);
        a.anchor = anchor;
        a.facing = flat(facing);
        List<AbstractRecruitEntity> out = new ArrayList<>();
        a.waiting.clear();
        int room = BattleSize.MAX_PER_SIDE;   // H23: tamaño de batalla; el resto espera en su barracón y entra en oleadas
        for (ArmyData.Pick p : a.picks) {
            int now = Math.max(0, Math.min(p.count, room));
            List<AbstractRecruitEntity> came = now > 0 ? spawnPick(level, a, p.camp, p.row, now, anchor) : List.of();
            out.addAll(came);
            room -= came.size();
            if (p.count > came.size()) a.waiting.add(new ArmyData.Pick(p.camp, p.row, p.count - came.size()));
        }
        a.state = ArmyData.State.FIELD;
        a.mode = ArmyData.Mode.HOLD;
        a.arriveTick = level.getGameTime();
        a.charged = false;
        a.foes.clear();
        a.free.clear();
        form(level, a, out, true);
        ArmyData.get(level).changed();
        return out;
    }

    /** Saca del barracón de {@code camp} hasta {@code count} soldados de la fila {@code row} y los hace aparecer en {@code at}. */
    private static List<AbstractRecruitEntity> spawnPick(ServerLevel level, ArmyData.Army a, BlockPos camp, String row, int count, Vec3 at) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(camp);
        if (b == null) return out;
        List<BarracksData.Soldier> pick = new ArrayList<>();
        for (boolean guards : new boolean[]{false, true}) {
            for (BarracksData.Soldier s : b.soldiers) {
                if (pick.size() >= count) break;
                if (s.guard == guards && s.rowKey().equals(row)) pick.add(s);
            }
        }
        for (BarracksData.Soldier s : pick) {
            b.soldiers.remove(s);
            AbstractRecruitEntity soldier = Barracks.create(level, s, at, camp, Barracks.BACK_TO_POS, b.hungryDays);
            if (soldier == null) continue;
            soldier.getPersistentData().putString(TAG_ARMY, a.id.toString());
            a.members.put(soldier.getUUID(), camp.asLong());
            out.add(soldier);
        }
        data.changed();
        return out;
    }

    /**
     * H23: oleadas. Si hay lugar en el campo ({@link BattleSize}), los que esperan en sus barracones entran por la
     * retaguardia. Devuelve los que entraron.
     */
    public static List<AbstractRecruitEntity> reinforce(ServerLevel level, ArmyData.Army a, List<AbstractRecruitEntity> onField) {
        int waiting = 0;
        for (ArmyData.Pick p : a.waiting) waiting += p.count;
        int room = BattleSize.room(onField.size(), waiting);
        List<AbstractRecruitEntity> out = new ArrayList<>();
        if (room <= 0) return out;
        Vec3 center = onField.isEmpty() ? a.anchor : BattleGeneral.centroid(onField);
        Vec3 rear = ground(level, center.subtract(a.facing.scale(RANGED_BEHIND + REINFORCE_BEHIND)), center.y);
        for (Iterator<ArmyData.Pick> it = a.waiting.iterator(); it.hasNext() && room > 0; ) {
            ArmyData.Pick p = it.next();
            int n = Math.min(room, p.count);
            List<AbstractRecruitEntity> came = spawnPick(level, a, p.camp, p.row, n, rear);
            out.addAll(came);
            room -= n;
            p.count -= n;   // los que ya no estaban en el barracón tampoco van a venir
            if (p.count <= 0) it.remove();
        }
        ArmyData.get(level).changed();
        return out;
    }

    // ------------------------------------------------------------------ formación

    /** Los soldados del ejército que están cargados en el mundo. */
    public static List<AbstractRecruitEntity> loaded(ServerLevel level, ArmyData.Army a) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        for (UUID id : a.members.keySet()) {
            if (level.getEntity(id) instanceof AbstractRecruitEntity s && s.isAlive()) out.add(s);
        }
        return out;
    }

    /**
     * Reparte los puestos: infantería adelante en bloques de 10 (2 filas de 5), arqueros {@link #RANGED_BEHIND} atrás,
     * caballería en los flancos y zapadores/capitanes atrás en el centro. Con {@code teleport} aparecen ahí mismo.
     */
    public static void form(ServerLevel level, ArmyData.Army a, List<AbstractRecruitEntity> soldiers, boolean teleport) {
        a.slots.clear();
        a.free.clear();
        a.lastOrder.clear();
        for (Map.Entry<AbstractRecruitEntity, Vec3> e : layout(a.anchor, a.facing, soldiers).entrySet()) {
            AbstractRecruitEntity s = e.getKey();
            Vec3 slot = ground(level, e.getValue(), a.anchor.y);
            a.slots.put(s.getUUID(), slot);
            if (teleport) s.getRootVehicle().teleportTo(slot.x, slot.y, slot.z);
            hold(a, s, slot);
        }
        Vec3 f = a.facing;
        for (AbstractRecruitEntity s : soldiers) {
            s.setYRot((float) (Mth.atan2(f.z, f.x) * Mth.RAD_TO_DEG) - 90.0F);
            s.setYHeadRot(s.getYRot());
        }
    }

    /** El puesto en el piso (centro del bloque) de una posición de la formación. */
    public static Vec3 ground(ServerLevel level, Vec3 p, double hintY) {
        Integer y = Ground.surfaceY(level, Mth.floor(p.x), Mth.floor(p.z), Mth.floor(hintY), 6, 10);
        return new Vec3(Mth.floor(p.x) + 0.5, y != null ? y : hintY, Mth.floor(p.z) + 0.5);
    }

    /**
     * Dónde va cada uno (sin la altura): infantería adelante en bloques de 10 (2 filas de 5), arqueros
     * {@link #RANGED_BEHIND} atrás, caballería en los flancos y zapadores/capitanes atrás en el centro. Lo usa también la
     * Hueste (H23).
     */
    public static Map<AbstractRecruitEntity, Vec3> layout(Vec3 anchor, Vec3 facing, List<AbstractRecruitEntity> soldiers) {
        Vec3 f = flat(facing);
        Vec3 side = new Vec3(-f.z, 0, f.x);
        List<AbstractRecruitEntity> infantry = new ArrayList<>(), ranged = new ArrayList<>(), cavalry = new ArrayList<>(), back = new ArrayList<>();
        for (AbstractRecruitEntity s : soldiers) {
            if (s instanceof SiegeEngineerEntity) {
                back.add(s);
                continue;
            }
            switch (BattleGeneral.roleOf(s)) {
                case CAVALRY -> cavalry.add(s);
                case RANGED -> ranged.add(s);
                case CAPTAIN -> back.add(s);
                default -> infantry.add(s);
            }
        }
        Comparator<AbstractRecruitEntity> byLevel = Comparator.comparingInt(AbstractRecruitEntity::getXpLevel).reversed();
        infantry.sort(byLevel);
        ranged.sort(byLevel);
        cavalry.sort(byLevel);

        Vec3 front = anchor.add(f.scale(4));
        List<Vec3> inf = blocks(front, f, side, infantry.size());
        double infWidth = width(infantry.size());
        Vec3 rangedFront = front.subtract(f.scale(BLOCK_RANKS * RANK_GAP + RANGED_BEHIND));
        List<Vec3> rng = blocks(rangedFront, f, side, ranged.size());
        int leftCount = (cavalry.size() + 1) / 2;
        double flank = Math.max(infWidth, width(ranged.size())) / 2 + BLOCK_GAP + 4 + width(Math.min(leftCount, BLOCK_WIDTH * BLOCK_RANKS)) / 2;
        List<Vec3> cav = new ArrayList<>(blocks(front.subtract(side.scale(flank)), f, side, leftCount));
        cav.addAll(blocks(front.add(side.scale(flank)), f, side, cavalry.size() - leftCount));
        Vec3 backFront = rangedFront.subtract(f.scale(BLOCK_RANKS * RANK_GAP + 3));
        List<Vec3> bk = blocks(backFront, f, side, back.size());

        Map<AbstractRecruitEntity, Vec3> out = new java.util.LinkedHashMap<>();
        put(out, infantry, inf);
        put(out, ranged, rng);
        put(out, cavalry, cav);
        put(out, back, bk);
        return out;
    }

    private static void put(Map<AbstractRecruitEntity, Vec3> out, List<AbstractRecruitEntity> soldiers, List<Vec3> slots) {
        for (int i = 0; i < soldiers.size() && i < slots.size(); i++) out.put(soldiers.get(i), slots.get(i));
    }

    /** Ancho (de costado) que ocupan {@code n} soldados en bloques. */
    private static double width(int n) {
        if (n <= 0) return 0;
        int perBlock = BLOCK_WIDTH * BLOCK_RANKS;
        int blocks = (n + perBlock - 1) / perBlock;
        double blockWidth = (BLOCK_WIDTH - 1) * SPACING;
        return blocks * blockWidth + (blocks - 1) * (BLOCK_GAP + SPACING);
    }

    /** Puestos de {@code n} soldados en bloques de {@link #BLOCK_WIDTH}×{@link #BLOCK_RANKS}, centrados en {@code front}. */
    public static List<Vec3> blocks(Vec3 front, Vec3 f, Vec3 side, int n) {
        List<Vec3> out = new ArrayList<>(n);
        if (n <= 0) return out;
        int perBlock = BLOCK_WIDTH * BLOCK_RANKS;
        double blockWidth = (BLOCK_WIDTH - 1) * SPACING;
        double step = blockWidth + BLOCK_GAP + SPACING;
        double left = -width(n) / 2;
        for (int i = 0; i < n; i++) {
            int block = i / perBlock, inBlock = i % perBlock;
            int rank = inBlock / BLOCK_WIDTH, col = inBlock % BLOCK_WIDTH;
            double along = left + block * step + col * SPACING;
            out.add(front.add(side.scale(along)).subtract(f.scale(rank * RANK_GAP)));
        }
        return out;
    }

    /** A su puesto: estado 3 de Recruits (volver al lugar), sin objetivo. */
    private static void hold(ArmyData.Army a, AbstractRecruitEntity s, Vec3 slot) {
        if (BattleGeneral.roleOf(s) != BattleGeneral.Role.RANGED) s.setTarget(null);
        s.setFollowState(Barracks.BACK_TO_POS);   // el 2 pisa el puesto: primero el estado, después el puesto
        s.setHoldPos(slot);
        a.lastOrder.put(s.getUUID(), new int[]{Barracks.BACK_TO_POS, Mth.floor(slot.x), Mth.floor(slot.z)});
    }

    private static void fight(ArmyData.Army a, AbstractRecruitEntity s, LivingEntity target) {
        if (s.getTarget() != target && s.canAttack(target)) s.setTarget(target);
        if (s.getFollowState() != 0) s.setFollowState(0);
        a.lastOrder.put(s.getUUID(), new int[]{0, 0, 0});
    }

    /** ¿El jugador le dio otra orden (vista aérea, Recruits)? Entonces el ejército no lo toca. */
    private static boolean overridden(ArmyData.Army a, AbstractRecruitEntity s) {
        int[] o = a.lastOrder.get(s.getUUID());
        if (o == null) return false;
        if (s.getFollowState() != o[0]) return true;
        if (o[0] != Barracks.BACK_TO_POS) return false;
        Vec3 h = s.getHoldPos();
        return h == null || Math.abs(Mth.floor(h.x) - o[1]) > 1 || Math.abs(Mth.floor(h.z) - o[2]) > 1;
    }

    // ------------------------------------------------------------------ órdenes

    public static Component holdOrder(ServerLevel level, ArmyData.Army a) {
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        a.mode = ArmyData.Mode.HOLD;
        List<AbstractRecruitEntity> soldiers = loaded(level, a);
        if (!soldiers.isEmpty()) a.anchor = BattleGeneral.centroid(soldiers).subtract(a.facing.scale(1));
        form(level, a, soldiers, false);
        ArmyData.get(level).changed();
        return Component.translatable("msg.warbandcraft.army.hold", a.name);
    }

    /** La formación se mueve a {@code to}, mirando en la dirección de la marcha. */
    public static Component advance(ServerLevel level, ArmyData.Army a, Vec3 to) {
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        List<AbstractRecruitEntity> soldiers = loaded(level, a);
        Vec3 from = soldiers.isEmpty() ? a.anchor : BattleGeneral.centroid(soldiers);
        Vec3 dir = flat(to.subtract(from));
        if (dir.lengthSqr() > 1.0E-4 && to.distanceToSqr(from) > 4) a.facing = dir;
        a.anchor = to;
        a.mode = ArmyData.Mode.HOLD;
        form(level, a, soldiers, false);
        ArmyData.get(level).changed();
        return Component.translatable("msg.warbandcraft.army.advance", a.name);
    }

    public static Component retreat(ServerLevel level, ArmyData.Army a) {
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        List<AbstractRecruitEntity> soldiers = loaded(level, a);
        Vec3 from = soldiers.isEmpty() ? a.anchor : BattleGeneral.centroid(soldiers);
        a.anchor = from.subtract(a.facing.scale(RETREAT_DISTANCE));
        a.mode = ArmyData.Mode.HOLD;
        for (AbstractRecruitEntity s : soldiers) s.setTarget(null);
        form(level, a, soldiers, false);
        ArmyData.get(level).changed();
        return Component.translatable("msg.warbandcraft.army.retreat", a.name);
    }

    /**
     * ¡Carguen! Si hay un ejército enemigo en marcha cerca, se le tiran encima (batalla de campo). Desde un lugar con
     * árboles, la primera carga es una emboscada: pega más fuerte un rato.
     */
    public static Component charge(ServerLevel level, ArmyData.Army a, @Nullable ServerPlayer player) {
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        List<AbstractRecruitEntity> soldiers = loaded(level, a);
        if (soldiers.isEmpty()) return null;
        Vec3 center = BattleGeneral.centroid(soldiers);
        Component extra = Component.empty();
        if (a.foes.isEmpty()) {
            Ambush.Target t = Ambush.find(level, a.owner, center);
            if (t != null) {
                BlockPos at = BlockPos.containing(center.add(a.facing.scale(8)));
                for (AbstractRecruitEntity e : Ambush.spring(level, player, at, t)) a.foes.add(e.getUUID());
            }
        }
        if (!a.charged && hidden(level, BlockPos.containing(center))) {
            a.ambushUntil = level.getGameTime() + AMBUSH_TICKS;
            extra = Component.translatable("msg.warbandcraft.army.ambush_bonus", Math.round(AMBUSH_BONUS * 100));
        }
        a.charged = true;
        a.mode = ArmyData.Mode.CHARGE;
        a.free.clear();
        a.calmSince = -1;
        ArmyData.get(level).changed();
        chargeTick(level, a, soldiers);
        return Component.translatable("msg.warbandcraft.army.charge", a.name).append(extra);
    }

    /** Hay árboles alrededor: el ejército está escondido. */
    public static boolean hidden(ServerLevel level, BlockPos at) {
        int total = 0, leaves = 0;
        for (int dx = -12; dx <= 12; dx += 3) {
            for (int dz = -12; dz <= 12; dz += 3) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!level.isLoaded(new BlockPos(x, at.getY(), z))) continue;
                total++;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (level.getBlockState(new BlockPos(x, top - 1, z)).is(BlockTags.LEAVES)) leaves++;
            }
        }
        return total > 0 && leaves >= total * HIDDEN_SHARE;
    }

    /** Licenciar: cada uno vuelve a su barracón (si está lleno, al Campamento tuyo más cercano con lugar). */
    public static Component dismiss(ServerLevel level, ArmyData.Army a) {
        if (a.state == ArmyData.State.COMING) return cancel(level, a);
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        int back = 0;
        for (AbstractRecruitEntity s : loaded(level, a)) {
            if (storeHome(level, s, a.members.get(s.getUUID()))) back++;
        }
        a.members.clear();
        a.waiting.clear();
        a.slots.clear();
        a.lastOrder.clear();
        a.free.clear();
        a.foes.clear();
        a.state = ArmyData.State.IDLE;
        a.mode = ArmyData.Mode.HOLD;
        ArmyData.get(level).changed();
        return Component.translatable("msg.warbandcraft.army.dismissed", a.name, back);
    }

    /** Lo guarda en su Campamento (o el más cercano con lugar). Si no hay dónde, queda suelto siguiéndote. */
    private static boolean storeHome(ServerLevel level, AbstractRecruitEntity s, @Nullable Long camp) {
        s.getPersistentData().remove(TAG_ARMY);
        s.setTarget(null);
        s.setLastHurtByMob(null);
        if (Surrender.is(s)) return false;
        CampRegistry reg = CampRegistry.get(level);
        UUID owner = s.getOwnerUUID();
        List<BlockPos> tries = new ArrayList<>();
        if (camp != null) tries.add(BlockPos.of(camp));
        if (owner != null) {
            List<BlockPos> others = new ArrayList<>(reg.campsOf(owner));
            others.sort(Comparator.comparingDouble(p -> p.distSqr(s.blockPosition())));
            tries.addAll(others);
        }
        for (BlockPos c : tries) {
            if (owner == null || !reg.isCampOf(owner, c)) continue;
            if (Barracks.store(level, s, c, true) == Barracks.StoreResult.OK) return true;
        }
        s.setFollowState(Barracks.FOLLOW_OWNER);
        return false;
    }

    // ------------------------------------------------------------------ cada segundo

    public static void tick(ServerLevel level) {
        ArmyData data = ArmyData.get(level);
        long now = level.getGameTime();
        for (ArmyData.Army a : new ArrayList<>(data.all())) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(a.owner);
            boolean here = owner != null && owner.level() == level;
            if (a.state == ArmyData.State.COMING) {
                if (!here || owner.position().distanceToSqr(a.anchor) > (double) CANCEL_DISTANCE * CANCEL_DISTANCE) {
                    Component msg = cancel(level, a);
                    if (owner != null && msg != null) owner.sendSystemMessage(msg);
                    continue;
                }
                if (now >= a.arriveTick) {
                    List<AbstractRecruitEntity> out = arrive(level, a, a.anchor, a.facing);
                    owner.sendSystemMessage(Component.translatable("msg.warbandcraft.army.arrived", a.name, out.size()).withStyle(ChatFormatting.GREEN));
                    com.ale.warbandcraft.network.ArmyStatusMessage.send(owner);
                } else {
                    updateBar(level, a);
                }
                continue;
            }
            if (a.state != ArmyData.State.FIELD) continue;
            List<AbstractRecruitEntity> soldiers = loaded(level, a);
            Vec3 center = soldiers.isEmpty() ? a.anchor : BattleGeneral.centroid(soldiers);
            if (!here || owner.position().distanceToSqr(center) > (double) KEEP_DISTANCE * KEEP_DISTANCE) {
                dismiss(level, a);
                if (owner != null) {
                    owner.sendSystemMessage(Component.translatable("msg.warbandcraft.army.went_home", a.name).withStyle(ChatFormatting.GOLD));
                    com.ale.warbandcraft.network.ArmyStatusMessage.send(owner);
                }
                continue;
            }
            if (!a.waiting.isEmpty()) {   // H23: entran los que esperaban, si hay lugar
                List<AbstractRecruitEntity> came = reinforce(level, a, soldiers);
                if (!came.isEmpty()) {
                    soldiers.addAll(came);
                    if (a.mode == ArmyData.Mode.HOLD) form(level, a, soldiers, false);
                }
            }
            if (a.members.isEmpty()) {
                a.waiting.clear();
                a.state = ArmyData.State.IDLE;
                data.changed();
                owner.sendSystemMessage(Component.translatable("msg.warbandcraft.army.wiped", a.name).withStyle(ChatFormatting.RED));
                com.ale.warbandcraft.network.ArmyStatusMessage.send(owner);
                continue;
            }
            if (a.slots.isEmpty() && !soldiers.isEmpty()) form(level, a, soldiers, false);   // recién cargado del disco
            if (a.mode == ArmyData.Mode.CHARGE) chargeTick(level, a, soldiers);
            else if (a.mode == ArmyData.Mode.DELEGATE) delegateTick(level, a, soldiers);
            else holdTick(level, a, soldiers);
            checkVictory(level, a, owner);
        }
    }

    /** Manteniendo: cada uno en su puesto; pelea con lo que se le acerque y vuelve. */
    private static void holdTick(ServerLevel level, ArmyData.Army a, List<AbstractRecruitEntity> soldiers) {
        List<LivingEntity> enemies = enemies(level, a, a.anchor, CHARGE_REACH);
        for (AbstractRecruitEntity s : soldiers) {
            if (a.free.contains(s.getUUID())) continue;
            if (overridden(a, s)) {
                a.free.add(s.getUUID());
                continue;
            }
            Vec3 slot = a.slots.get(s.getUUID());
            if (slot == null) continue;
            boolean ranged = BattleGeneral.roleOf(s) == BattleGeneral.Role.RANGED;
            LivingEntity near = nearest(ranged ? s.position() : slot, enemies, ranged ? RANGED_REACH : HOLD_ENGAGE);
            if (near != null && !ranged) {
                fight(a, s, near);
                continue;
            }
            if (near != null && (s.getTarget() == null || !s.getTarget().isAlive())) s.setTarget(near);   // arquero: tira desde su puesto
            int[] o = a.lastOrder.get(s.getUUID());
            if (o == null || o[0] != Barracks.BACK_TO_POS) hold(a, s, slot);
        }
    }

    private static void chargeTick(ServerLevel level, ArmyData.Army a, List<AbstractRecruitEntity> soldiers) {
        if (soldiers.isEmpty()) return;
        Vec3 center = BattleGeneral.centroid(soldiers);
        List<LivingEntity> enemies = enemies(level, a, center, CHARGE_REACH);
        long now = level.getGameTime();
        if (enemies.isEmpty()) {
            if (a.calmSince < 0) a.calmSince = now;
            if (now - a.calmSince >= CHARGE_CALM_TICKS) {   // no queda nadie: vuelven a formar donde están
                a.mode = ArmyData.Mode.HOLD;
                a.anchor = center;
                form(level, a, soldiers, false);
                ArmyData.get(level).changed();
            }
            return;
        }
        a.calmSince = -1;
        for (AbstractRecruitEntity s : soldiers) {
            if (a.free.contains(s.getUUID())) continue;
            if (overridden(a, s)) {
                a.free.add(s.getUUID());
                continue;
            }
            LivingEntity current = s.getTarget();
            if (current != null && current.isAlive() && !Surrender.is(current) && enemies.contains(current)) {
                a.lastOrder.put(s.getUUID(), new int[]{s.getFollowState(), 0, 0});
                if (s.getFollowState() != 0) fight(a, s, current);
                continue;
            }
            LivingEntity t = nearest(s.position(), enemies, CHARGE_REACH * 2);
            if (t != null) fight(a, s, t);
        }
    }

    /** H24: con el mando delegado, el general de la IA maneja al ejército (táctica, formaciones, moral). */
    private static void delegateTick(ServerLevel level, ArmyData.Army a, List<AbstractRecruitEntity> soldiers) {
        if (soldiers.isEmpty()) return;
        Vec3 center = BattleGeneral.centroid(soldiers);
        List<LivingEntity> enemies = enemies(level, a, center, CHARGE_REACH * 2);
        if (enemies.isEmpty()) return;
        com.ale.warbandcraft.battle.tactics.TeamAI.tick(level, aiKey(a), soldiers, enemies,
                new com.ale.warbandcraft.battle.tactics.TeamAI.Context(a.anchor.subtract(a.facing.scale(40)), null, true));
    }

    public static String aiKey(ArmyData.Army a) {
        return "army:" + a.id;
    }

    /** H24: "Delegar el mando" (como en Bannerlord): la IA elige la táctica y maneja las formaciones hasta tu próxima orden. */
    public static Component delegate(ServerLevel level, ArmyData.Army a) {
        if (a.state != ArmyData.State.FIELD) return null;
        com.ale.warbandcraft.battle.tactics.TeamAI.forget(aiKey(a));
        a.mode = ArmyData.Mode.DELEGATE;
        a.free.clear();
        a.lastOrder.clear();
        ArmyData.get(level).changed();
        return Component.translatable("msg.warbandcraft.army.delegate", a.name);
    }

    /** Ganaron la batalla de campo: todos los enemigos de la marcha cayeron (o se fueron). */
    private static void checkVictory(ServerLevel level, ArmyData.Army a, ServerPlayer owner) {
        if (a.foes.isEmpty()) return;
        for (Iterator<UUID> it = a.foes.iterator(); it.hasNext(); ) {
            Entity e = level.getEntity(it.next());
            if (e == null || !e.isAlive() || Surrender.is(e)) it.remove();
        }
        if (!a.foes.isEmpty()) return;
        int alive = 0;
        for (AbstractRecruitEntity s : loaded(level, a)) {
            s.addXp(VICTORY_XP);
            s.checkLevel();
            alive++;
        }
        ArmyData.get(level).changed();
        owner.sendSystemMessage(Component.translatable("msg.warbandcraft.army.victory", a.name, alive).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
    }

    /** Enemigos del ejército cerca de {@code at}: los de siempre del cuartel (bichos, facciones en guerra...) y los de la marcha. */
    public static List<LivingEntity> enemies(ServerLevel level, ArmyData.Army a, Vec3 at, double reach) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(reach, 24, reach),
                e -> e.isAlive() && !Surrender.is(e) && !(e instanceof net.minecraft.world.entity.monster.Creeper)
                        && (a.foes.contains(e.getUUID()) || e.getPersistentData().getBoolean(Ambush.TAG_FIELD)
                        && !(e instanceof AbstractRecruitEntity r && Objects.equals(r.getOwnerUUID(), a.owner))
                        || Quarters.isEnemy(level, a.owner, e) || com.ale.warbandcraft.invasion.AiWars.isFoe(level, a.owner, e)));
    }

    @Nullable
    private static LivingEntity nearest(Vec3 from, List<LivingEntity> enemies, double reach) {
        LivingEntity best = null;
        double bestD = reach * reach;
        for (LivingEntity e : enemies) {
            double d = e.position().distanceToSqr(from);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ eventos

    /** Un soldado del ejército murió: sale de la lista. */
    public static void onDeath(ServerLevel level, AbstractRecruitEntity s) {
        if (!s.getPersistentData().contains(TAG_ARMY)) return;
        ArmyData data = ArmyData.get(level);
        ArmyData.Army a = data.armyOf(s.getUUID());
        if (a == null) return;
        a.members.remove(s.getUUID());
        a.slots.remove(s.getUUID());
        data.changed();
    }

    /** Emboscada: los golpes del ejército valen más mientras dura. */
    public static float damage(ServerLevel level, @Nullable Entity attacker, float amount) {
        if (!(attacker instanceof AbstractRecruitEntity r) || !r.getPersistentData().contains(TAG_ARMY)) return amount;
        ArmyData.Army a = ArmyData.get(level).armyOf(r.getUUID());
        if (a == null || level.getGameTime() > a.ambushUntil) return amount;
        return (float) (amount * (1 + AMBUSH_BONUS));
    }

    /** Soldados de ejércitos que quedaron sueltos (se cargaron después de licenciar): vuelven a su barracón (cada 10 s). */
    public static void sweepOrphans(ServerLevel level) {
        ArmyData data = ArmyData.get(level);
        List<AbstractRecruitEntity> orphans = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof AbstractRecruitEntity s) || !s.isAlive() || !s.getPersistentData().contains(TAG_ARMY)) continue;
            ArmyData.Army a = data.armyOf(s.getUUID());
            if (a == null || a.state != ArmyData.State.FIELD) orphans.add(s);
        }
        for (AbstractRecruitEntity s : orphans) {
            Long camp = s.getPersistentData().contains(Barracks.TAG_CAMP) ? s.getPersistentData().getLong(Barracks.TAG_CAMP) : null;
            storeHome(level, s, camp);
        }
    }

    /** Cuántos soldados de {@code camp} están afuera en un ejército (comen de su Campamento). */
    public static int outFrom(ServerLevel level, BlockPos camp) {
        int n = 0;
        long c = camp.asLong();
        for (ArmyData.Army a : ArmyData.get(level).all()) {
            for (Long m : a.members.values()) if (m == c) n++;
        }
        return n;
    }

    /** ¿Es de un ejército en el campo? (el guardado automático no lo toca). */
    public static boolean inArmy(AbstractRecruitEntity s) {
        return s.getPersistentData().contains(TAG_ARMY);
    }

    // ------------------------------------------------------------------ barra de la cuenta regresiva

    private static void updateBar(ServerLevel level, ArmyData.Army a) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(a.owner);
        ServerBossEvent bar = BARS.computeIfAbsent(a.id, k -> new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.NOTCHED_10));
        long left = Math.max(0, a.arriveTick - level.getGameTime());
        long total = Math.max(1, a.arriveTick - a.summonTick);
        bar.setProgress(Mth.clamp(1.0F - left / (float) total, 0.0F, 1.0F));
        long s = left / 20;
        bar.setName(Component.translatable("gui.warbandcraft.army.bar", a.name, s / 60 + ":" + String.format("%02d", s % 60)));
        if (owner != null && !bar.getPlayers().contains(owner)) bar.addPlayer(owner);
    }

    private static void removeBar(ArmyData.Army a) {
        ServerBossEvent bar = BARS.remove(a.id);
        if (bar != null) bar.removeAllPlayers();
    }

    public static void clear() {
        for (ServerBossEvent bar : BARS.values()) bar.removeAllPlayers();
        BARS.clear();
    }

    static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0, v.z);
        return f.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : f.normalize();
    }
}
