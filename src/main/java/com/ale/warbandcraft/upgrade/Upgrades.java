package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.util.Ground;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mejoras visibles (H12): encargar una mejora, la obra que se levanta de a poco con su constructor y lo que da
 * cada mejora hecha. Los números de los efectos están acá arriba.
 */
public final class Upgrades {
    /** Ritmo de obra: bloques por segundo, y la duración entre 1 y 2 minutos. */
    public static int BLOCKS_PER_SECOND = 8;
    public static int MIN_TICKS = 20 * 60, MAX_TICKS = 20 * 120;
    /** Pueblo y ciudad: esmeraldas y comida ×1,5 y ×2. */
    public static double LEVEL_BONUS = 0.5;
    public static int MARKET_EMERALDS = 4, SMITHY_ARMAMENT = 3, GRANARY_FOOD = 2, GRANARY_CAP_FACTOR = 2;
    /** Cuartel: lugares extra en los barracones de tus Campamentos a esta distancia. */
    public static int BARRACKS_BONUS = 20, CASTLE_BARRACKS_BONUS = 40, BARRACKS_RANGE = 64;
    /** Marca del aldeano constructor ({@link BuilderNpc}). */
    public static final String TAG_BUILDER = BuilderNpc.TAG;

    private Upgrades() {}

    // ------------------------------------------------------------------ estado

    public enum State { DONE, BUILDING, AVAILABLE, LOCKED }

    /** Una fila de la pantalla: la mejora, en qué está, por qué no se puede (si no) y cuánto va la obra. */
    public record Status(Upgrade upgrade, State state, Component reason, int percent) {}

    @Nullable
    public static TownData.Town town(ServerLevel level, BlockPos center) {
        return TownData.get(level).at(center);
    }

    /** Todas las mejoras que le corresponden a esta aldea o castillo, con su estado. */
    public static List<Status> status(ServerLevel level, VillageData.Village v) {
        TownData.Town t = town(level, v.center());
        List<Status> out = new ArrayList<>();
        for (Upgrade u : Upgrade.values()) {
            if (!u.appliesTo(v.castle()) || !u.listed()) continue;
            if (!v.castle() && (u == Upgrade.PALISADE || u == Upgrade.WALL || u == Upgrade.TOWERS)) continue;   // H22c: se dibujan en Defensa
            if (t != null && t.has(u)) {
                out.add(new Status(u, State.DONE, Component.empty(), 100));
            } else if (t != null && t.job != null && t.job.upgrade == u) {
                out.add(new Status(u, State.BUILDING, Component.empty(), percent(t.job)));
            } else {
                Component why = blocker(t, u);
                out.add(new Status(u, why == null ? State.AVAILABLE : State.LOCKED, why == null ? Component.empty() : why, 0));
            }
        }
        return out;
    }

    public static int percent(TownData.Job job) {
        return job.size() == 0 ? 100 : Math.min(100, job.placed * 100 / job.size());
    }

    /** Por qué no se puede encargar {@code u} (sin contar el costo), o null si se puede. */
    @Nullable
    public static Component blocker(@Nullable TownData.Town t, Upgrade u) {
        int builder = t == null ? 0 : t.builderLevel();
        if (u.requires != null && (t == null || !t.has(u.requires))) {
            return Component.translatable("upgrade.warbandcraft.needs", u.requires.name(false));
        }
        if (builder < u.builderLevel) return Component.translatable("upgrade.warbandcraft.needs_builder", u.builderLevel);
        if (t != null && t.job != null) return Component.translatable("upgrade.warbandcraft.busy", t.job.upgrade.name(false));
        return null;
    }

    // ------------------------------------------------------------------ encargar

    /** Encarga {@code u} donde ya tiene terreno (o donde lo busque solo, en castillos y en las murallas). */
    public static Component order(ServerPlayer player, BlockPos center, Upgrade u) {
        return order(player, center, u, null, 0);
    }

    /**
     * Encarga {@code u} en tu aldea/castillo de {@code center}: valida, cobra (materiales de {@link BuildCost} +
     * esmeraldas) y arranca la obra. H22: los edificios de las aldeas se plantan a mano: {@code corner} = la esquina
     * del terreno elegido en el modo Gestión y {@code rot} su giro (los niveles siguientes van donde está el primero).
     */
    public static Component order(ServerPlayer player, BlockPos center, Upgrade u, @Nullable BlockPos corner, int rot) {
        return commit(player, prepare(player, center, u, corner, rot));
    }

    /** H22b/c: encarga una obra de {@link Works} (muralla dibujada, portón, torre, terreno, demoler) descripta en {@code text}. */
    public static Component orderWork(ServerPlayer player, BlockPos center, String text) {
        return commit(player, prepareWork(player, center, text));
    }

    /** Cobra lo preparado y arranca la obra. */
    private static Component commit(ServerPlayer player, Prepared p) {
        if (p.error != null) return p.error;
        ServerLevel level = player.serverLevel();
        CampBlockEntity camp = VillageStorehouse.camp(level, p.v);
        BuildCost.pay(p.bill, camp, player);
        int cost = p.bill.emeralds();
        int fromStock = cost <= 0 ? 0 : VillageStock.takeEmeralds(level, p.v, cost);
        if (cost - fromStock > 0) takeFromInventory(player, cost - fromStock);
        if (p.site != null && !p.key.isEmpty()) p.town.putSite(p.key, p.site, p.upgrade.siteSize(), p.rot);
        start(level, p.town, p.upgrade, p.plan);
        p.town.job.paid = p.bill;
        p.town.job.siteKey = p.key;
        TownData.get(level).changed();
        return Component.translatable("upgrade.warbandcraft.started", p.upgrade.name(p.v.castle()), cost).withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" Materiales: " + p.bill.text() + ".").withStyle(ChatFormatting.GRAY));
    }

    /** H22: la vista previa de plantar {@code u} ahí: si se puede, el porqué si no, la cuenta y la altura del piso. */
    public record Preview(boolean ok, String text, int floorY) {}

    public static Preview preview(ServerPlayer player, BlockPos center, Upgrade u, BlockPos corner, int rot) {
        return previewOf(prepare(player, center, u, corner, rot), center);
    }

    public static Preview previewWork(ServerPlayer player, BlockPos center, String text) {
        return previewOf(prepareWork(player, center, text), center);
    }

    private static Preview previewOf(Prepared p, BlockPos center) {
        int y = p.site != null ? p.site.getY() : center.getY();
        if (p.bill == null) return new Preview(false, p.error == null ? "No se puede" : p.error.getString(), y);
        String text = p.bill.text() + (p.bill.removed() > 0 ? " · saca " + p.bill.removed() + " bloques (al almacén)" : "")
                + (p.note.isEmpty() ? "" : " · " + p.note);
        if (p.error != null) return new Preview(false, p.error.getString() + " · " + text, y);
        return new Preview(true, text, y);
    }

    /** Todo lo que se revisa antes de encargar. Con {@code error} != null no se puede (si {@code bill} != null, es por plata o materiales). */
    private record Prepared(@Nullable Component error, @Nullable Upgrade upgrade, @Nullable VillageData.Village v, @Nullable TownData.Town town,
                            @Nullable Blueprints.Plan plan, @Nullable BuildCost.Bill bill, String key, @Nullable BlockPos site, int rot,
                            String note) {
        static Prepared no(Component why) {
            return new Prepared(why, null, null, null, null, null, "", null, 0, "");
        }
    }

    /** Lo que se revisa para cualquier obra: que la aldea sea tuya, se pueda y esté cargada. Null = se puede. */
    @Nullable
    private static Component check(ServerLevel level, ServerPlayer player, @Nullable VillageData.Village v, Upgrade u) {
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return error("upgrade.warbandcraft.not_yours");
        if (!u.appliesTo(v.castle())) return error("upgrade.warbandcraft.not_here");
        TownData.Town existing = TownData.get(level).at(v.center());
        if (existing != null && u.listed() && existing.has(u)) return error("upgrade.warbandcraft.done");
        Component why = blocker(existing, u);
        if (why != null) return why.copy().withStyle(ChatFormatting.RED);
        if (!level.isPositionEntityTicking(v.center())) return error("upgrade.warbandcraft.not_loaded");
        return null;
    }

    /** La cuenta del plano y si alcanza la plata y los materiales. */
    private static Prepared billed(ServerLevel level, ServerPlayer player, Upgrade u, VillageData.Village v, TownData.Town town,
                                   Blueprints.Plan plan, int emeralds, String key, @Nullable BlockPos site, int rot, String note) {
        BuildCost.Bill bill = BuildCost.of(level, plan, emeralds, u == Upgrade.EARTH_FILL);   // la tierra, solo al rellenar
        Component problem = null;
        int inStock = VillageStock.total(level, v).emeralds();
        int inHand = player.getInventory().countItem(Items.EMERALD);
        if (inStock + inHand < emeralds) {
            problem = Component.translatable("upgrade.warbandcraft.no_money", emeralds, inStock, inHand).withStyle(ChatFormatting.RED);
        } else {
            String lack = BuildCost.missing(bill, VillageStorehouse.camp(level, v), player);
            if (lack != null) problem = Component.literal("Faltan materiales (almacén + inventario): " + lack).withStyle(ChatFormatting.RED);
        }
        return new Prepared(problem, u, v, town, plan, bill, key, site, rot, note);
    }

    private static Prepared prepare(ServerPlayer player, BlockPos center, Upgrade u, @Nullable BlockPos corner, int rot) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        Component why = check(level, player, v, u);
        if (why != null) return Prepared.no(why);

        TownData.Town town = TownData.get(level).getOrCreate(center);
        Blueprints.Plan plan;
        String key = "";
        BlockPos site = null;
        int r = 0;
        Style style = Style.of(level, v, town);
        if (u.handPlaced() && !v.castle()) {   // H22: elegido a mano (los castillos, por ahora, como antes)
            key = u == Upgrade.HOUSE ? nextHouseKey(town) : u.siteKey();
            BlockPos known = u == Upgrade.HOUSE ? null : town.sites.get(key);
            if (known != null) {
                site = grownSite(level, v, town, key, u);
                if (site == null) {
                    return Prepared.no(Component.literal("No hay lugar para agrandarlo: el nivel nuevo pide " + u.siteSize() + "×" + u.siteSize()
                            + " alrededor del edificio de antes (Demoler lo que estorba).").withStyle(ChatFormatting.RED));
                }
                r = town.rotations.getOrDefault(key, 0);
            } else {
                if (corner == null) {
                    return Prepared.no(Component.literal("Elegí dónde: vista aérea (V) → Gestión → Construir.").withStyle(ChatFormatting.RED));
                }
                Placement.Spot spot = Placement.check(level, v, town, corner, key, u.siteSize());
                if (spot.problem() != null) return Prepared.no(Component.literal(spot.problem()).withStyle(ChatFormatting.RED));
                site = spot.site();
                r = Math.floorMod(rot, 4);
            }
            plan = Blueprints.building(level, u, site, r, style);
        } else {
            plan = plan(level, v, town, u);
        }
        if (plan == null || plan.size() == 0) return Prepared.no(error("upgrade.warbandcraft.no_room"));
        String note = u.handPlaced() ? "estilo " + style.label + " · " + u.siteSize() + "×" + u.siteSize() : "";
        return billed(level, player, u, v, town, plan, u.cost, key, site, r, note);
    }

    private static Prepared prepareWork(ServerPlayer player, BlockPos center, String text) {
        ServerLevel level = player.serverLevel();
        Upgrade u = Works.upgradeOf(text);
        if (u == null) return Prepared.no(Component.literal("No entiendo esa obra.").withStyle(ChatFormatting.RED));
        VillageData.Village v = VillageData.get(level).at(center);
        Component why = check(level, player, v, u);
        if (why != null) return Prepared.no(why);
        if (v.castle()) return Prepared.no(Component.literal("Por ahora en los castillos no se construye a mano.").withStyle(ChatFormatting.RED));
        TownData.Town town = TownData.get(level).getOrCreate(center);
        Works.Spec s = Works.spec(level, v, town, text);
        if (s == null) return Prepared.no(Component.literal("No entiendo esa obra.").withStyle(ChatFormatting.RED));
        if (s.problem() != null) return Prepared.no(Component.literal(s.problem()).withStyle(ChatFormatting.RED));
        return billed(level, player, u, v, town, s.plan(), s.emeralds(), s.key(), null, 0, s.note());
    }

    /**
     * El terreno de {@code key} para construir {@code u}: el de siempre, o (si es de antes de los estilos y más chico) el
     * agrandado alrededor del mismo medio. Null si no entra; no lo anota (eso, al encargar).
     */
    @Nullable
    static BlockPos grownSite(ServerLevel level, VillageData.Village v, TownData.Town town, String key, Upgrade u) {
        BlockPos known = town.sites.get(key);
        if (known == null || !town.legacySite(key) || town.siteSize(key) >= u.siteSize()) return known;
        BlockPos grown = Blueprints.enlarge(level, known, town.siteSize(key), u.siteSize(), town.lots(key), v.center().getY());
        if (grown == null || v.owner() == null) return grown;
        int m = u.siteSize() - 1;
        for (int[] c : new int[][]{{0, 0}, {m, 0}, {0, m}, {m, m}}) {
            if (!Placement.inside(level, v, grown.getX() + c[0], grown.getZ() + c[1])) return null;
        }
        return grown;
    }

    /** La clave de terreno de la próxima casa ("house_N" libre). */
    private static String nextHouseKey(TownData.Town town) {
        int k = 0;
        while (town.sites.containsKey("house_" + k)) k++;
        return "house_" + k;
    }

    /**
     * H22: cancela la obra de tu aldea: devuelve al almacén la parte de lo pagado que todavía no se puso. Lo que ya está
     * puesto queda; el terreno de un edificio nuevo se libera.
     */
    public static Component cancel(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        if (v == null || !com.ale.warbandcraft.faction.Allies.same(player.getUUID(), v.owner())) return error("upgrade.warbandcraft.not_yours");
        TownData data = TownData.get(level);
        TownData.Town t = data.at(center);
        if (t == null || t.job == null) return Component.literal("No hay ninguna obra.").withStyle(ChatFormatting.RED);
        TownData.Job job = t.job;
        double left = job.size() == 0 ? 0 : (job.size() - job.placed) / (double) job.size();
        BuildCost.Bill back = job.paid.times(left);
        BuildCost.refund(back, VillageStorehouse.camp(level, v), player);
        removeBuilder(level, job);
        if (!job.siteKey.isEmpty() && t.built.stream().noneMatch(b -> b.siteKey().equals(job.siteKey))) t.removeSite(job.siteKey);
        t.job = null;
        data.changed();
        return Component.literal("Obra cancelada: " + job.upgrade.name(v.castle()).getString() + ". Volvió al almacén: " + back.text() + ".")
                .withStyle(ChatFormatting.YELLOW);
    }

    private static Component error(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.RED);
    }

    public static void takeFromInventory(ServerPlayer player, int n) {
        for (int i = 0; i < player.getInventory().getContainerSize() && n > 0; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(n, s.getCount());
            s.shrink(take);
            n -= take;
        }
        player.getInventory().setChanged();
    }

    /** Los bloques de la obra (busca y anota el terreno si es un edificio). Null si no hay lugar. */
    @Nullable
    public static Blueprints.Plan plan(ServerLevel level, VillageData.Village v, TownData.Town town, Upgrade u) {
        BlockPos c = v.center();
        if (town.ringRadius == 0) town.ringRadius = v.castle() ? 0 : Blueprints.ringRadius(level, c);
        boolean npc = v.owner() == null && !v.castle();
        boolean ringUp = town.has(Upgrade.PALISADE) || town.has(Upgrade.WALL) || town.ringWalls;
        if (npc && !ringUp && (u == Upgrade.PALISADE || u == Upgrade.WALL)) town.ringRadius = ringAround(c, town);
        return switch (u) {
            case PALISADE -> Blueprints.ring(level, c, town.ringRadius, false);
            case WALL -> Blueprints.ring(level, c, town.ringRadius, true);
            case TOWERS -> Blueprints.towers(level, c, town.ringRadius);
            case TOWN -> Blueprints.plaza(level, c, false);
            case CITY -> Blueprints.plaza(level, c, true);
            case HOUSE -> {   // cada casa en su terreno: "house_N", así las próximas no la pisan
                BlockPos site = com.ale.warbandcraft.village.Housing.findSite(level, c, town.lots(null));
                if (site == null) yield null;
                town.putSite("house_" + town.houses, site, u.siteSize(), 0);
                yield Blueprints.building(level, u, site, 0, Style.of(level, v, town));
            }
            default -> {
                String key = u.siteKey();
                int size = u.siteSize();
                BlockPos site = town.sites.get(key);
                if (site != null) {
                    site = grownSite(level, v, town, key, u);
                    if (site == null) yield null;   // un terreno viejo que no se puede agrandar: no hay lugar
                } else {
                    List<Blueprints.Lot> taken = town.lots(null);
                    if (v.castle()) {
                        site = Blueprints.findSite(level, c, 27, 52, size, taken);
                    } else {
                        site = Blueprints.findSite(level, c, 8, town.ringRadius - size, size, taken);
                        if (site == null && npc) site = Blueprints.findSiteRelaxed(level, c, 8, town.ringRadius - size, size, taken);
                        if (site == null && npc && !ringUp) {   // la empalizada todavía no está: crece hasta abrazar al edificio
                            int far = Blueprints.RING_MAX - size - Blueprints.RING_PAD;
                            site = Blueprints.findSite(level, c, town.ringRadius - size, far, size, taken);
                            if (site == null) site = Blueprints.findSiteRelaxed(level, c, town.ringRadius - size, far, size, taken);
                        }
                        if (site == null) site = Blueprints.findSite(level, c, town.ringRadius + size, town.ringRadius + 32, size, taken);
                    }
                    if (site == null) yield null;
                }
                int rot = town.rotations.getOrDefault(key, 0);
                town.putSite(key, site, size, rot);
                yield Blueprints.building(level, u, site, rot, Style.of(level, v, town));
            }
        };
    }

    /**
     * Aldeas de facción: el radio de la empalizada/muralla que deja adentro a todos los edificios (no a las casas, que
     * pueden quedar afuera), con {@link Blueprints#RING_PAD} de aire, entre el de las camas y {@link Blueprints#RING_MAX}.
     */
    static int ringAround(BlockPos c, TownData.Town town) {
        double far = 0;
        for (Map.Entry<String, BlockPos> e : town.sites.entrySet()) {
            if (e.getKey().startsWith("house")) continue;
            BlockPos s = e.getValue();
            int n = town.siteSize(e.getKey()) - 1;
            for (int[] k : new int[][]{{0, 0}, {n, 0}, {0, n}, {n, n}}) {
                double dx = s.getX() + k[0] - c.getX(), dz = s.getZ() + k[1] - c.getZ();
                far = Math.max(far, Math.sqrt(dx * dx + dz * dz));
            }
        }
        int need = (int) Math.ceil(far) + Blueprints.RING_PAD;
        return Math.max(Blueprints.RING_MIN, Math.min(Blueprints.RING_MAX, Math.max(town.ringRadius, need)));
    }

    /** Arranca la obra con estos bloques (también lo usan las pruebas). */
    public static void start(ServerLevel level, TownData.Town town, Upgrade u, Blueprints.Plan plan) {
        int n = plan.size();
        long[] positions = new long[n];
        int[] states = new int[n];
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> e : plan.blocks.entrySet()) {
            positions[i] = e.getKey().asLong();
            states[i] = index.computeIfAbsent(e.getValue(), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            i++;
        }
        int ticks = Mth.clamp(n * 20 / BLOCKS_PER_SECOND, MIN_TICKS, MAX_TICKS);
        town.job = new TownData.Job(u, level.getGameTime(), ticks, positions, states, palette);
        TownData.get(level).changed();
    }

    /**
     * Aldeas de facción: la obra entera de una vez (sin constructor ni ruido), como si ya hubiera estado. Lo que estaba en
     * chunks sin cargar no se pone.
     */
    public static void buildNow(ServerLevel level, TownData.Town town, Upgrade u, Blueprints.Plan plan) {
        start(level, town, u, plan);
        TownData.Job job = town.job;
        for (int i = 0; i < job.size(); i++) {
            BlockPos p = job.pos(i);
            if (level.isLoaded(p)) place(level, p, job.state(i), false, null, u.isLevelUp(), false);
        }
        job.placed = job.size();
        finish(level, town);
    }

    // ------------------------------------------------------------------ obras

    /** Cada tick: las obras con la aldea cargada ponen los bloques que les tocan y mueven a su constructor. */
    public static void tick(ServerLevel level) {
        TownData data = TownData.get(level);
        long now = level.getGameTime();
        for (TownData.Town t : data.all()) {
            TownData.Job job = t.job;
            // la aldea cargada, o donde va la obra (pedido de Ale: lejos del centro se trababa, p. ej. allanar en el borde del territorio)
            if (job == null || !level.isPositionEntityTicking(t.center)
                    && !level.isPositionEntityTicking(job.pos(Math.min(job.placed, job.size() - 1)))) continue;
            int due = job.due(now);
            int before = job.placed;
            CampBlockEntity camp = null;
            if (job.placed < due) {
                VillageData.Village v = VillageData.get(level).at(t.center);
                camp = v == null ? null : VillageStorehouse.camp(level, v);
            }
            while (job.placed < due) {
                BlockPos p = job.pos(job.placed);
                if (!level.isLoaded(p)) break;   // esa parte todavía no está cargada: sigue después
                place(level, p, job.state(job.placed), job.placed % 3 == 0, camp, job.upgrade == Upgrade.DEMOLISH || job.upgrade == Upgrade.EARTH_LEVEL
                        || job.upgrade.isLevelUp());
                job.placed++;
            }
            if (job.placed != before) data.changed();
            if (job.placed >= job.size()) {
                finish(level, t);
                continue;
            }
            moveBuilder(level, t, job);
        }
    }

    /**
     * Pone un bloque de la obra. H22: lo que había (tierra, piedra, troncos...) va al almacén {@code camp} si hay. Con
     * {@code demolish} (demoler, o el nivel siguiente de un edificio, que rehace el de antes) también pisa barriles, camas
     * y cofres (lo de adentro al almacén); un Campamento, nunca.
     */
    private static void place(ServerLevel level, BlockPos p, BlockState s, boolean sound, @Nullable CampBlockEntity camp, boolean demolish) {
        place(level, p, s, sound, camp, demolish, true);
    }

    private static void place(ServerLevel level, BlockPos p, BlockState s, boolean sound, @Nullable CampBlockEntity camp, boolean demolish,
                              boolean particles) {
        BlockState old = level.getBlockState(p);
        if (old == s) return;
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
        if (be != null && !old.is(s.getBlock()) && (!demolish || be instanceof CampBlockEntity)) return;   // nunca pisa cofres, camas, campanas... (salvo al demoler)
        if (s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock || s.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.WallBlock || s.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
            s = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(s, level, p);   // que se unan con lo que ya está
        }
        if (camp != null && !old.isAir() && !old.canBeReplaced()) {
            for (ItemStack drop : net.minecraft.world.level.block.Block.getDrops(old, level, p, be)) BuildCost.give(drop, camp, null);
            if (demolish && be instanceof net.minecraft.world.Container c) {   // lo que tenía adentro también va al almacén
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack in = c.removeItemNoUpdate(i);
                    if (!in.isEmpty()) BuildCost.give(in, camp, null);
                }
            }
        }
        level.setBlock(p, s, 3);
        BlockState shown = s.isAir() ? old : s;
        if (particles && !shown.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, shown), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                    4, 0.3, 0.3, 0.3, 0.05);
            if (sound) level.playSound(null, p, shown.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.5F, 0.9F);
        }
    }

    private static void finish(ServerLevel level, TownData.Town t) {
        TownData.Job job = t.job;
        if (job == null) return;
        if (job.upgrade.listed()) t.built.add(job.upgrade);
        else if (job.upgrade == Upgrade.HOUSE) t.houses++;
        if (job.upgrade == Upgrade.REPAIR) WallRepair.forget(t.center);
        if (job.upgrade == Upgrade.PALISADE || job.upgrade == Upgrade.WALL) {
            t.ringWalls = true;
            t.cutColumns.clear();   // anillo nuevo: lo cortado a mano vuelve a ser muralla
            t.ringVersion = com.ale.warbandcraft.village.FactionTowns.RING_VERSION;
        }
        if (job.upgrade == Upgrade.PALISADE || job.upgrade == Upgrade.WALL || job.upgrade == Upgrade.REPAIR) ringGates(t, job);
        Works.finish(t, job);   // H22: muralla dibujada, portones, torres, demoler
        if (job.upgrade.defenseLevel() > 0 || job.upgrade == Upgrade.DEMOLISH) WallRepair.forget(t.center);
        if (job.upgrade.givesBuilder() > 0) {   // un taller más alto reemplaza al anterior
            for (Upgrade u : Upgrade.values()) if (u.givesBuilder() > 0 && u.givesBuilder() < job.upgrade.givesBuilder()) t.built.add(u);
        }
        removeBuilder(level, job);
        t.job = null;
        TownData.get(level).changed();
        VillageData.Village v = VillageData.get(level).at(t.center);
        if (v != null && v.owner() != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(v.owner());
            if (p != null) {
                p.sendSystemMessage(Component.translatable("upgrade.warbandcraft.finished", job.upgrade.name(v.castle()),
                        t.center.getX(), t.center.getZ()).withStyle(ChatFormatting.GREEN));
            }
        }
    }

    /** Los portones de la empalizada/muralla de anillo (puertas de cerco de abajo) pasan a abrir y cerrar solos. */
    private static void ringGates(TownData.Town t, TownData.Job job) {
        Blueprints.Plan plan = new Blueprints.Plan();
        for (int i = 0; i < job.size(); i++) plan.put(job.pos(i), job.state(i));
        for (BlockPos g : Blueprints.gatesOf(plan)) if (!t.gates.contains(g) && !t.gates.contains(g.below())) t.gates.add(g);
    }

    private static void removeBuilder(ServerLevel level, TownData.Job job) {
        BuilderNpc.remove(level, job.builder);
    }

    /** El constructor camina (se desliza) hacia el último bloque puesto; si no existe, aparece ahí. */
    private static void moveBuilder(ServerLevel level, TownData.Town t, TownData.Job job) {
        BlockPos target = job.pos(Math.min(job.placed, job.size() - 1));
        UUID id = BuilderNpc.update(level, job.builder, target, t.center, "town:" + t.center.asLong());
        if (id != null && !id.equals(job.builder)) {
            job.builder = id;
            TownData.get(level).changed();
        }
    }

    /** Un aldeano constructor que ya no tiene obra (se cargó de un guardado viejo): sobra. */
    public static boolean isOrphanBuilder(ServerLevel level, Entity e) {
        String owner = BuilderNpc.ownerOf(e);
        if (owner == null) return false;
        if (owner.startsWith("road:")) return com.ale.warbandcraft.route.Roads.isOrphanBuilder(level, e, owner.substring(5));
        if (!owner.startsWith("town:")) return true;
        TownData.Town t;
        try {
            t = TownData.get(level).at(BlockPos.of(Long.parseLong(owner.substring(5))));
        } catch (NumberFormatException ex) {
            return true;
        }
        return t == null || t.job == null || !e.getUUID().equals(t.job.builder);
    }

    // ------------------------------------------------------------------ efectos

    /** La producción de un día con las mejoras: nivel (pueblo/ciudad), mercado, herrería y granero. */
    public static VillageStock.Stock apply(ServerLevel level, VillageData.Village v, VillageStock.Stock base) {
        TownData.Town t = town(level, v.center());
        if (t == null) return base;
        double mult = 1 + LEVEL_BONUS * (t.level() - 1);
        int emeralds = (int) Math.round(base.emeralds() * mult) + (t.has(Upgrade.MARKET) ? MARKET_EMERALDS : 0);
        int food = (int) Math.round(base.food() * mult) + (t.has(Upgrade.GRANARY) ? GRANARY_FOOD : 0);
        int armament = base.armament() + (t.has(Upgrade.SMITHY) ? SMITHY_ARMAMENT : 0);
        return new VillageStock.Stock(emeralds, food, armament);
    }

    public static int foodCapFactor(ServerLevel level, VillageData.Village v) {
        TownData.Town t = town(level, v.center());
        return t != null && t.has(Upgrade.GRANARY) ? GRANARY_CAP_FACTOR : 1;
    }

    /** Lugares extra en el barracón del Campamento {@code camp}: cuarteles de tus aldeas/castillos cerca. */
    public static int barracksBonus(ServerLevel level, BlockPos camp) {
        UUID owner = CampRegistry.get(level).ownerOf(camp);
        if (owner == null) {
            BarracksData.Barracks b = BarracksData.get(level).at(camp);
            if (b == null) return 0;
            owner = b.owner;
        }
        int bonus = 0;
        TownData data = TownData.get(level);
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (!com.ale.warbandcraft.faction.Allies.same(owner, v.owner())) continue;
            if (VillageData.horizontalDistSq(v.center(), camp) > (double) BARRACKS_RANGE * BARRACKS_RANGE) continue;
            TownData.Town t = data.at(v.center());
            if (t != null && t.has(Upgrade.BARRACKS)) bonus += v.castle() ? CASTLE_BARRACKS_BONUS : BARRACKS_BONUS;
        }
        return bonus;
    }
}
