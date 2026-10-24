package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * H21: crecimiento de aldeas estilo Stronghold (pedido de Ale). Cada aldea tuya tiene popularidad (0 a 100) que se mueve
 * cada minuto según raciones, impuestos, camas libres, variedad de comida, seguridad, peligro, taberna y mercado. Con
 * {@link #THRESHOLD} o más llegan aldeanos (si hay cama y no llegó al tope); con menos se van. Los que llegan vienen sin
 * oficio y no lo toman solos (marca {@link #TAG_SETTLER}): esperan a que les des uno en Gestión (Villager Workers).
 * <p>
 * Solo corre con la aldea cargada (descargada queda quieta). Castillos no crecen.
 */
public final class Popularity {
    public static final String TAG_SETTLER = "warbandcraft:settler";

    /** Cada cuánto se mueve la popularidad y se mira si llega o se va alguien (1 minuto). */
    public static int EVERY_TICKS = 20 * 60;
    public static int START = 50, THRESHOLD = 50;
    /** Tope de población (aldeanos + trabajadores) por tamaño: aldea, pueblo, ciudad (H12). */
    public static int CAP_VILLAGE = 12, CAP_TOWN = 24, CAP_CITY = 48;
    /** Cada cuántos minutos llega uno según la popularidad (50-69, 70-89, 90+) y se va uno (25-49, menos de 25). */
    public static int ARRIVE_MIN_50 = 5, ARRIVE_MIN_70 = 3, ARRIVE_MIN_90 = 2, LEAVE_MIN_25 = 5, LEAVE_MIN_0 = 2;
    /** Raciones (Ninguna, Media, Normal, Abundante, Doble): puntos de comida por aldeano y día, y popularidad por minuto. */
    public static int RATION_0 = 0, RATION_1 = 2, RATION_2 = 4, RATION_3 = 6, RATION_4 = 8;
    public static int RATION_POP_0 = -4, RATION_POP_1 = -2, RATION_POP_2 = 0, RATION_POP_3 = 2, RATION_POP_4 = 4;
    /** Impuestos (Sin, Bajos, Normales, Altos, Abusivos): multiplicador de esmeraldas (×10) y popularidad por minuto. */
    public static int TAX_X10_0 = 0, TAX_X10_1 = 5, TAX_X10_2 = 10, TAX_X10_3 = 15, TAX_X10_4 = 20;
    public static int TAX_POP_0 = 2, TAX_POP_1 = 0, TAX_POP_2 = -2, TAX_POP_3 = -4, TAX_POP_4 = -6;
    /** Variedad: +1 por tipo de comida del almacén después del primero, hasta esto. */
    public static int VARIETY_MAX = 3;
    /** Camas libres: 0 / 1 a 3 / 4 o más. */
    public static int BEDS_NONE = -1, BEDS_SOME = 1, BEDS_MANY = 2, BEDS_MANY_FROM = 4;
    /** Seguridad: guardias cada 10 habitantes (0 → −2, 1 → 0, 2+ → +1) y empalizada o muralla +1. */
    public static int GUARDS_NONE = -2, GUARDS_GOOD = 1, WALLS = 1;
    /** Invasión en marcha o pelea en la aldea; sin comida para la ración elegida (ese día). */
    public static int DANGER = -3, HUNGRY = -4;
    /** Taberna con tabernero: + su nivel; mercado. */
    public static int MARKET = 1;
    /** H22: capilla. */
    public static int CHAPEL = 2;
    /** Ataque reciente (invasión rechazada o perdida a medias): popularidad por minuto y cuántos días dura. */
    public static int RECENT_ATTACK = -2, RECENT_ATTACK_DAYS = 3;
    /** Dónde aparece el que llega (lejos del centro, adentro del territorio). */
    public static int ARRIVE_DISTANCE = 40;

    private static final String[] RATION_KEYS = {"none", "half", "normal", "plenty", "double"};
    private static final String[] TAX_KEYS = {"none", "low", "normal", "high", "cruel"};

    private Popularity() {}

    public static int rationPoints(int r) {
        return switch (r) {
            case 0 -> RATION_0;
            case 1 -> RATION_1;
            case 3 -> RATION_3;
            case 4 -> RATION_4;
            default -> RATION_2;
        };
    }

    private static int rationPop(int r) {
        return switch (r) {
            case 0 -> RATION_POP_0;
            case 1 -> RATION_POP_1;
            case 3 -> RATION_POP_3;
            case 4 -> RATION_POP_4;
            default -> RATION_POP_2;
        };
    }

    /** Multiplicador de impuestos del nivel {@code t} (0..4). */
    public static double taxFactor(int t) {
        return switch (t) {
            case 0 -> TAX_X10_0;
            case 1 -> TAX_X10_1;
            case 3 -> TAX_X10_3;
            case 4 -> TAX_X10_4;
            default -> TAX_X10_2;
        } / 10.0;
    }

    private static int taxPop(int t) {
        return switch (t) {
            case 0 -> TAX_POP_0;
            case 1 -> TAX_POP_1;
            case 3 -> TAX_POP_3;
            case 4 -> TAX_POP_4;
            default -> TAX_POP_2;
        };
    }

    public static int cap(@Nullable TownData.Town t) {
        int lvl = t == null ? 1 : t.level();
        return lvl >= 3 ? CAP_CITY : lvl == 2 ? CAP_TOWN : CAP_VILLAGE;
    }

    // ------------------------------------------------------------------ factores

    /** Un factor del informe: clave de traducción, su argumento y cuánto suma por minuto. */
    public record Factor(String key, int arg, int value) {}

    /** El informe de la aldea: popularidad, cambio por minuto, factores, población, tope y cuánto falta para el próximo. */
    public record Info(int popularity, int delta, List<Factor> factors, int villagers, int workers, int free, int cap,
                       int rations, int taxes, int minutesToNext, boolean arriving, boolean autoAssign) {
        public static final Info NONE = new Info(-1, 0, List.of(), 0, 0, 0, 0, 2, 2, 0, false, false);

        public void write(FriendlyByteBuf b) {
            b.writeVarInt(popularity + 1);
            b.writeVarInt(delta + 100);
            b.writeCollection(factors, (bb, f) -> {
                bb.writeUtf(f.key());
                bb.writeVarInt(f.arg());
                bb.writeVarInt(f.value() + 100);
            });
            b.writeVarInt(villagers);
            b.writeVarInt(workers);
            b.writeVarInt(free);
            b.writeVarInt(cap);
            b.writeVarInt(rations);
            b.writeVarInt(taxes);
            b.writeVarInt(minutesToNext);
            b.writeBoolean(arriving);
            b.writeBoolean(autoAssign);
        }

        public static Info read(FriendlyByteBuf b) {
            int pop = b.readVarInt() - 1, delta = b.readVarInt() - 100;
            List<Factor> f = b.readList(bb -> new Factor(bb.readUtf(), bb.readVarInt(), bb.readVarInt() - 100));
            return new Info(pop, delta, f, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readBoolean());
        }
    }

    /** Los factores de hoy de la aldea (cargada). */
    public static List<Factor> factors(ServerLevel level, VillageData.Village v, TownData.Town t) {
        List<Factor> out = new ArrayList<>();
        out.add(new Factor(t.hungry ? "hungry" : "rations_" + RATION_KEYS[t.rations], 0, t.hungry ? HUNGRY : rationPop(t.rations)));
        int variety = foodTypes(level, v);
        if (variety > 1) out.add(new Factor("variety", variety, Math.min(VARIETY_MAX, variety - 1)));
        out.add(new Factor("taxes_" + TAX_KEYS[t.taxes], 0, taxPop(t.taxes)));
        int free = Housing.of(level, v.center()).freeBeds();
        out.add(new Factor("beds", free, free == 0 ? BEDS_NONE : free >= BEDS_MANY_FROM ? BEDS_MANY : BEDS_SOME));
        int pop = Math.max(1, population(level, v));
        int guards = guards(level, v);
        int per10 = guards * 10 / pop;
        out.add(new Factor("guards", guards, per10 == 0 ? GUARDS_NONE : per10 >= 2 ? GUARDS_GOOD : 0));
        if (t.has(Upgrade.PALISADE) || t.has(Upgrade.WALL)) out.add(new Factor("walls", 0, WALLS));
        if (InvasionData.get(level).at(v.center()) != null) out.add(new Factor("danger", 0, DANGER));
        if (t.tavernLevel() > 0 && t.innkeeper) out.add(new Factor("tavern", t.tavernLevel(), t.tavernLevel()));
        if (t.has(Upgrade.MARKET)) out.add(new Factor("market", 0, MARKET));
        if (t.has(Upgrade.CHAPEL)) out.add(new Factor("chapel", 0, CHAPEL));
        if (level.getGameTime() < t.troubledUntil) out.add(new Factor("recent_attack", 0, RECENT_ATTACK));
        return out;
    }

    private static int foodTypes(ServerLevel level, VillageData.Village v) {
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp == null) return 0;
        Set<Item> types = new HashSet<>();
        camp.forEachStack(s -> {
            FoodProperties f = Barracks.food(s);
            if (f != null) types.add(s.getItem());
        });
        return types.size();
    }

    private static int guards(ServerLevel level, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        int n = b == null ? 0 : b.soldiers.size() + b.quarters.size();
        return n + Quarters.loaded(level, v.center()).size();
    }

    /** Aldeanos + trabajadores tuyos cerca del centro. */
    public static int population(ServerLevel level, VillageData.Village v) {
        return VillageHud.population(level, v);
    }

    // ------------------------------------------------------------------ cada minuto

    /**
     * Cada {@link #EVERY_TICKS}: tus aldeas cargadas cambian su popularidad, y llega o se va alguien. Las descargadas
     * siguen "en papel" con lo último que se midió ({@link #paperMinute}).
     */
    public static void tick(ServerLevel level) {
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (!v.isConquered() || v.castle()) continue;
            if (level.isPositionEntityTicking(v.center())) minute(level, v, towns.getOrCreate(v.center()));
            else paperMinute(towns.getOrCreate(v.center()));
            towns.changed();
        }
    }

    /** Un ataque a la aldea: la gente se asusta por {@link #RECENT_ATTACK_DAYS} días. */
    public static void attacked(ServerLevel level, BlockPos center) {
        TownData towns = TownData.get(level);
        TownData.Town t = towns.getOrCreate(center);
        t.troubledUntil = level.getGameTime() + RECENT_ATTACK_DAYS * 24000L;
        towns.changed();
    }

    /**
     * Un minuto de la aldea descargada: la popularidad se mueve con el último cambio medido y la gente llega o se va
     * "en papel" (con las camas y habitantes de la última vez). Al cargarse aparecen o se van de verdad.
     */
    public static void paperMinute(TownData.Town t) {
        t.popularity = Math.max(0, Math.min(100, t.popularity + t.lastDelta));
        if (t.popularity >= THRESHOLD) {
            t.leaveMinutes = 0;
            if (t.paperPop >= cap(t) || t.paperBeds <= 0) {
                t.growthMinutes = 0;
                return;
            }
            if (++t.growthMinutes < arriveEvery(t.popularity)) return;
            t.growthMinutes = 0;
            t.paperIn++;
            t.paperPop++;
            t.paperBeds--;
            return;
        }
        t.growthMinutes = 0;
        if (++t.leaveMinutes < (t.popularity < 25 ? LEAVE_MIN_0 : LEAVE_MIN_25)) return;
        t.leaveMinutes = 0;
        if (t.paperPop <= 0) return;
        if (t.paperIn > 0) t.paperIn--;   // el último que llegó en papel es el primero que se va
        else t.paperOut++;
        t.paperPop--;
        t.paperBeds++;
    }

    /** Lo que pasó en papel mientras la aldea estaba descargada: llegan y se van de verdad. */
    private static void applyPaper(ServerLevel level, VillageData.Village v, TownData.Town t) {
        int in = 0, out = 0;
        for (; t.paperIn > 0; t.paperIn--) if (spawnSettler(level, v) != null) in++;
        for (; t.paperOut > 0; t.paperOut--) if (leave(level, v)) out++;
        if (in > 0 || out > 0) {
            tell(level, v, Component.translatable("msg.warbandcraft.growth.while_away", name(level, v), in, out)
                    .withStyle(in >= out ? ChatFormatting.GREEN : ChatFormatting.GOLD));
        }
    }

    /** Un minuto de la aldea. Devuelve +1 si llegó alguien, −1 si se fue, 0 si nada. */
    public static int minute(ServerLevel level, VillageData.Village v, TownData.Town t) {
        applyPaper(level, v, t);
        int delta = 0;
        for (Factor f : factors(level, v, t)) delta += f.value();
        t.popularity = Math.max(0, Math.min(100, t.popularity + delta));
        keepSettlersFree(level, v);
        int pop = population(level, v);
        // Para seguir en papel si se descarga
        t.lastDelta = delta;
        t.paperPop = pop;
        t.paperBeds = Housing.of(level, v.center()).freeBeds();
        if (t.autoAssign) {
            AbstractWorkerEntity w = WorkZones.autoAssign(level, v);
            if (w != null) {
                tell(level, v, Component.translatable("msg.warbandcraft.growth.auto_assigned", name(level, v), w.getType().getDescription())
                        .withStyle(ChatFormatting.GREEN));
            }
        }
        if (t.popularity >= THRESHOLD) {
            t.leaveMinutes = 0;
            if (pop >= cap(t) || Housing.of(level, v.center()).freeBeds() == 0) {
                t.growthMinutes = 0;
                return 0;
            }
            if (++t.growthMinutes < arriveEvery(t.popularity)) return 0;
            t.growthMinutes = 0;
            if (spawnSettler(level, v) == null) return 0;
            tell(level, v, Component.translatable("msg.warbandcraft.growth.arrived", name(level, v), pop + 1, cap(t)).withStyle(ChatFormatting.GREEN));
            return 1;
        }
        t.growthMinutes = 0;
        if (++t.leaveMinutes < (t.popularity < 25 ? LEAVE_MIN_0 : LEAVE_MIN_25)) return 0;
        t.leaveMinutes = 0;
        if (!leave(level, v)) return 0;
        tell(level, v, Component.translatable("msg.warbandcraft.growth.left", name(level, v), (int) t.popularity).withStyle(ChatFormatting.RED));
        return -1;
    }

    public static int arriveEvery(double popularity) {
        return popularity >= 90 ? ARRIVE_MIN_90 : popularity >= 70 ? ARRIVE_MIN_70 : ARRIVE_MIN_50;
    }

    /** Llega un aldeano sin oficio al borde del territorio y camina hasta la campana. */
    @Nullable
    public static Villager spawnSettler(ServerLevel level, VillageData.Village v) {
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) return null;
        BlockPos at = edgeSpot(level, v);
        villager.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360F, 0F);
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.MOB_SUMMONED, null, null);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
        villager.getPersistentData().putBoolean(TAG_SETTLER, true);
        villager.setPersistenceRequired();
        if (!level.addFreshEntity(villager)) return null;
        villager.getNavigation().moveTo(v.center().getX() + 0.5, v.center().getY(), v.center().getZ() + 0.5, 0.6);
        return villager;
    }

    /** Un lugar cargado a {@link #ARRIVE_DISTANCE} del centro, en el piso (si no hay, al lado de la campana). */
    private static BlockPos edgeSpot(ServerLevel level, VillageData.Village v) {
        BlockPos c = v.center();
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return c.above();   // las pruebas están pegadas
        for (int i = 0; i < 12; i++) {
            double a = level.random.nextDouble() * Math.PI * 2;
            int x = c.getX() + (int) Math.round(Math.cos(a) * ARRIVE_DISTANCE), z = c.getZ() + (int) Math.round(Math.sin(a) * ARRIVE_DISTANCE);
            if (!level.isLoaded(new BlockPos(x, c.getY(), z)) || !level.isPositionEntityTicking(new BlockPos(x, c.getY(), z))) continue;
            if (Territory.get(level).has(v.center()) && !Territory.get(level).of(v.center()).contains(new ChunkPos(new BlockPos(x, 0, z)))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (level.getFluidState(p.below()).isEmpty()) return p;
        }
        return c.above();
    }

    /** Se va uno: un recién llegado libre, otro aldeano libre, un trabajador sin zona (en ese orden). */
    private static boolean leave(ServerLevel level, VillageData.Village v) {
        AABB box = new AABB(v.center()).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box, x -> x.isAlive() && !x.isBaby());
        for (boolean settlersOnly : new boolean[]{true, false}) {
            for (Villager x : villagers) {
                if (!VillageManagement.isFree(x) || (settlersOnly && !x.getPersistentData().getBoolean(TAG_SETTLER))) continue;
                gone(level, x);
                return true;
            }
        }
        for (AbstractWorkerEntity w : level.getEntitiesOfClass(AbstractWorkerEntity.class, box,
                x -> x.isAlive() && com.ale.warbandcraft.faction.Allies.same(x.getOwnerUUID(), v.owner()) && !WorkZones.hasPlace(x))) {
            gone(level, w);
            return true;
        }
        return false;
    }

    private static void gone(ServerLevel level, net.minecraft.world.entity.LivingEntity e) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, e.getX(), e.getY() + 1, e.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
        if (e instanceof Villager vi) {
            for (var m : List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT)) {
                vi.releasePoi(m);
            }
        }
        e.discard();
    }

    /** Los recién llegados no toman oficio solos: si agarraron uno sin comerciar todavía, lo sueltan. */
    private static void keepSettlersFree(ServerLevel level, VillageData.Village v) {
        AABB box = new AABB(v.center()).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS);
        for (Villager x : level.getEntitiesOfClass(Villager.class, box, x -> x.getPersistentData().getBoolean(TAG_SETTLER))) {
            if (x.getVillagerData().getProfession() == VillagerProfession.NONE || x.getVillagerXp() > 0) continue;
            x.releasePoi(MemoryModuleType.JOB_SITE);
            x.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
            x.setVillagerData(x.getVillagerData().setProfession(VillagerProfession.NONE));
        }
    }

    // ------------------------------------------------------------------ cada día: raciones

    /** Día nuevo: cada aldea tuya cargada come su ración del almacén; si no alcanza, ese día cuenta como sin comida. */
    public static void onNewDay(ServerLevel level) {
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (!v.isConquered() || v.castle() || !level.isPositionEntityTicking(v.center())) continue;
            TownData.Town t = towns.getOrCreate(v.center());
            eat(level, v, t);
            towns.changed();
        }
    }

    /** La ración del día. Devuelve true si alcanzó. */
    public static boolean eat(ServerLevel level, VillageData.Village v, TownData.Town t) {
        int need = population(level, v) * rationPoints(t.rations);
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (need <= 0) {
            t.hungry = false;
            return true;
        }
        boolean ok = camp != null && Barracks.foodPoints(camp) >= need;
        if (ok) Barracks.takeFood(camp, need);
        t.hungry = !ok;
        if (!ok) tell(level, v, Component.translatable("msg.warbandcraft.growth.hungry", name(level, v)).withStyle(ChatFormatting.GOLD));
        return ok;
    }

    // ------------------------------------------------------------------ órdenes y pantalla

    public static Component setRations(ServerLevel level, VillageData.Village v, int r) {
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        t.rations = Math.max(0, Math.min(4, r));
        TownData.get(level).changed();
        return Component.translatable("msg.warbandcraft.growth.rations", Component.translatable("gui.warbandcraft.growth.rations_" + RATION_KEYS[t.rations]))
                .withStyle(ChatFormatting.GREEN);
    }

    public static Component setAutoAssign(ServerLevel level, VillageData.Village v, boolean on) {
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        t.autoAssign = on;
        TownData.get(level).changed();
        return Component.translatable(on ? "msg.warbandcraft.growth.auto_assign_on" : "msg.warbandcraft.growth.auto_assign_off")
                .withStyle(ChatFormatting.GREEN);
    }

    public static Component setTaxes(ServerLevel level, VillageData.Village v, int x) {
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        t.taxes = Math.max(0, Math.min(4, x));
        TownData.get(level).changed();
        return Component.translatable("msg.warbandcraft.growth.taxes", Component.translatable("gui.warbandcraft.growth.taxes_" + TAX_KEYS[t.taxes]))
                .withStyle(ChatFormatting.GREEN);
    }

    /** El informe para la pestaña Pueblo del modo Gestión. */
    public static Info info(ServerLevel level, VillageData.Village v) {
        if (v.castle()) return Info.NONE;
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        List<Factor> f = factors(level, v, t);
        int delta = 0;
        for (Factor x : f) delta += x.value();
        AABB box = new AABB(v.center()).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS);
        List<Villager> vs = level.getEntitiesOfClass(Villager.class, box, Villager::isAlive);
        int free = 0;
        for (Villager x : vs) if (VillageManagement.isFree(x)) free++;
        int workers = level.getEntitiesOfClass(AbstractWorkerEntity.class, box, AbstractWorkerEntity::isAlive).size();
        boolean arriving = t.popularity >= THRESHOLD;
        int every = arriving ? arriveEvery(t.popularity) : (t.popularity < 25 ? LEAVE_MIN_0 : LEAVE_MIN_25);
        int done = arriving ? t.growthMinutes : t.leaveMinutes;
        return new Info((int) t.popularity, delta, f, vs.size(), workers, free, cap(t), t.rations, t.taxes, Math.max(0, every - done), arriving,
                t.autoAssign);
    }

    private static void tell(ServerLevel level, VillageData.Village v, Component msg) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(v.owner());
        if (p != null) p.sendSystemMessage(msg);
    }

    private static Component name(ServerLevel level, VillageData.Village v) {
        return VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
    }
}
