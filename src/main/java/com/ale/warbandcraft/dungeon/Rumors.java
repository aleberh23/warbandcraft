package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H25: las misiones al estilo Skyrim. Hablándole al Tabernero (H19):
 * <ul>
 *   <li><b>Rumor</b> ({@link #RUMOR_COST} esmeraldas): la mazmorra nuestra más cercana que no conocés (si no hay ninguna
 *       cerca, aparece una nueva lejos), o si no una mazmorra de otro mod (etiqueta {@code warbandcraft:rumored}). Queda
 *       en tu mapa M como un círculo con su nombre.</li>
 *   <li><b>Trabajo</b>: la facción en paz con vos más cercana paga por limpiar una guarida (o un túmulo): esmeraldas +
 *       opinión. Uno a la vez, con plazo de {@link #CONTRACT_DAYS} días.</li>
 * </ul>
 * La pantalla es la de elegir de H23 ({@code ChoiceMessage}); la respuesta llega por {@link BattleChoices#answer}.
 */
public final class Rumors {
    public static final String KIND_MENU = "tavern", KIND_CONTRACT = "contract";
    /** Mazmorras de otros mods que el tabernero puede contar. */
    public static final TagKey<Structure> RUMORED = TagKey.create(Registries.STRUCTURE, new ResourceLocation(WarbandCraft.MODID, "rumored"));

    public static int RUMOR_COST = 5;
    /** Hasta dónde se busca una mazmorra nuestra para el rumor o el trabajo. */
    public static int RUMOR_RANGE = 1200;
    /** Si no hay, aparece una nueva a esta distancia. */
    public static int NEW_MIN_DIST = 250;
    public static int NEW_MAX_DIST = 700;
    /** Radio (en chunks) para buscar mazmorras de otros mods. */
    public static int FOREIGN_SEARCH_CHUNKS = 64;
    /** Paga de un contrato: base + por cada bandido que queda. */
    public static int CONTRACT_BASE = 20;
    public static int CONTRACT_PER_BANDIT = 4;
    public static int CONTRACT_BARROW = 45;
    public static int CONTRACT_DAYS = 7;
    public static int CONTRACT_OPINION = 10;

    private Rumors() {}

    /** Click al Tabernero. */
    public static void interact(ServerPlayer player, InnkeeperEntity innkeeper) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.warbandcraft.tavern.greeting"));
        DungeonData.Contract c = DungeonData.get(player.serverLevel()).contractOf(player.getUUID());
        if (c != null) lines.add(contractLine(player.serverLevel(), c));
        BattleChoices.send(player, KIND_MENU, Integer.toString(innkeeper.getId()), Component.translatable("gui.warbandcraft.tavern.title"), lines,
                List.of(Component.translatable("gui.warbandcraft.tavern.rumor", RUMOR_COST), Component.translatable("gui.warbandcraft.tavern.work"),
                        Component.translatable("gui.warbandcraft.tavern.bye")), true);
    }

    public static void answer(ServerPlayer player, String kind, String id, int option) {
        if (KIND_MENU.equals(kind)) {
            InnkeeperEntity inn = player.serverLevel().getEntity(parse(id)) instanceof InnkeeperEntity i ? i : null;
            if (inn == null || inn.distanceTo(player) > 8) return;
            if (option == 0) player.sendSystemMessage(buyRumor(player, inn));
            else if (option == 1) offerContract(player);
        } else if (KIND_CONTRACT.equals(kind) && option == 0) {
            player.sendSystemMessage(accept(player, id));
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ rumores

    public static Component buyRumor(ServerPlayer player, @Nullable InnkeeperEntity inn) {
        ServerLevel level = player.serverLevel();
        if (!pay(player, inn, RUMOR_COST)) {
            return Component.translatable("msg.warbandcraft.tavern.no_money", RUMOR_COST).withStyle(ChatFormatting.RED);
        }
        Component r = rumor(level, player);
        if (r == null) {
            refund(player, RUMOR_COST);
            return Component.translatable("msg.warbandcraft.tavern.no_rumor").withStyle(ChatFormatting.GRAY);
        }
        return r;
    }

    /** Le cuenta un rumor (y lo deja en su mapa). Null = no sabe nada nuevo. */
    @Nullable
    public static Component rumor(ServerLevel level, ServerPlayer player) {
        DungeonData data = DungeonData.get(level);
        UUID who = player.getUUID();
        DungeonData.Dungeon best = null;
        double bestSq = (double) RUMOR_RANGE * RUMOR_RANGE;
        for (DungeonData.Dungeon d : data.all()) {
            if (d.cleared() || d.known.contains(who)) continue;
            double sq = VillageData.horizontalDistSq(d.entrance, player.blockPosition());
            if (sq < bestSq) {
                bestSq = sq;
                best = d;
            }
        }
        if (best == null) best = Dungeons.place(level, player.blockPosition(), NEW_MIN_DIST, NEW_MAX_DIST, null, level.getRandom());
        if (best != null) {
            best.known.add(who);
            data.changed();
            return Component.translatable(best.kind == DungeonData.Kind.BARROW ? "msg.warbandcraft.tavern.rumor_barrow" : "msg.warbandcraft.tavern.rumor_lair",
                    best.name, Dungeons.where(best.entrance), direction(player.blockPosition(), best.entrance)).withStyle(ChatFormatting.YELLOW);
        }
        // Una mazmorra de otro mod
        BlockPos found;
        try {
            found = level.findNearestMapStructure(RUMORED, player.blockPosition(), FOREIGN_SEARCH_CHUNKS, false);
        } catch (Exception e) {
            found = null;
        }
        if (found == null || data.hasRumor(who, found)) return null;
        String name = foreignName(level, found);
        data.addRumor(who, new DungeonData.Rumor(found, name));
        return Component.translatable("msg.warbandcraft.tavern.rumor_foreign", name, Dungeons.where(found), direction(player.blockPosition(), found))
                .withStyle(ChatFormatting.YELLOW);
    }

    /** "una mazmorra antigua" / el nombre que da el mod (betterdungeons:skeleton_dungeon → "skeleton dungeon"). */
    private static String foreignName(ServerLevel level, BlockPos pos) {
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : level.structureManager().getAllStructuresAt(pos).entrySet()) {
            ResourceLocation id = reg.getKey(e.getKey());
            if (id != null && reg.wrapAsHolder(e.getKey()).is(RUMORED)) return id.getPath().replace('_', ' ');
        }
        return "mazmorra";
    }

    /** "al norte", "al sudeste"... */
    private static Component direction(BlockPos from, BlockPos to) {
        double a = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));   // 0 = este, 90 = sur
        String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        int i = (int) Math.floorMod(Math.round(a / 45.0), 8);
        return Component.translatable("gui.warbandcraft.dir." + names[i]);
    }

    // ------------------------------------------------------------------ contratos

    private static void offerContract(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        DungeonData data = DungeonData.get(level);
        if (data.contractOf(player.getUUID()) != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.has_contract").withStyle(ChatFormatting.GRAY));
            return;
        }
        Faction faction = nearestFriend(level, player);
        if (faction == null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.no_work").withStyle(ChatFormatting.GRAY));
            return;
        }
        DungeonData.Dungeon target = null;
        double bestSq = (double) RUMOR_RANGE * RUMOR_RANGE;
        for (DungeonData.Dungeon d : data.all()) {
            if (d.cleared() || !d.populated) continue;
            double sq = VillageData.horizontalDistSq(d.entrance, player.blockPosition());
            if (sq < bestSq) {
                bestSq = sq;
                target = d;
            }
        }
        if (target == null) target = Dungeons.place(level, player.blockPosition(), NEW_MIN_DIST, NEW_MAX_DIST, DungeonData.Kind.LAIR, level.getRandom());
        if (target == null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.no_work").withStyle(ChatFormatting.GRAY));
            return;
        }
        int reward = reward(target);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.warbandcraft.contract.offer", Component.literal(faction.name()).withStyle(faction.color()), target.name,
                Dungeons.where(target.entrance)));
        lines.add(Component.translatable("gui.warbandcraft.contract.reward", reward, CONTRACT_OPINION, CONTRACT_DAYS));
        BattleChoices.send(player, KIND_CONTRACT, target.id + "|" + faction.id() + "|" + reward, Component.translatable("gui.warbandcraft.contract.title"),
                lines, List.of(Component.translatable("gui.warbandcraft.contract.accept"), Component.translatable("gui.warbandcraft.contract.decline")), true);
    }

    public static int reward(DungeonData.Dungeon d) {
        return d.kind == DungeonData.Kind.BARROW ? CONTRACT_BARROW : CONTRACT_BASE + CONTRACT_PER_BANDIT * Math.max(0, d.remaining);
    }

    /** "id|facción|paga" → contrato. */
    public static Component accept(ServerPlayer player, String id) {
        ServerLevel level = player.serverLevel();
        String[] parts = id.split("\\|");
        if (parts.length != 3) return Component.empty();
        DungeonData data = DungeonData.get(level);
        DungeonData.Dungeon d = data.byId(parse(parts[0]));
        ResourceLocation f = ResourceLocation.tryParse(parts[1]);
        if (d == null || d.cleared() || f == null) return Component.translatable("msg.warbandcraft.tavern.no_work").withStyle(ChatFormatting.GRAY);
        if (data.contractOf(player.getUUID()) != null) return Component.translatable("msg.warbandcraft.tavern.has_contract").withStyle(ChatFormatting.GRAY);
        data.setContract(player.getUUID(), new DungeonData.Contract(d.id, f, parse(parts[2]), level.getGameTime() + CONTRACT_DAYS * 24000L));
        d.known.add(player.getUUID());
        data.changed();
        return Component.translatable("msg.warbandcraft.tavern.contract_taken", d.name, Dungeons.where(d.entrance)).withStyle(ChatFormatting.GREEN);
    }

    /** La facción (no forajida, en paz con vos) con la aldea o castillo más cercano. */
    @Nullable
    static Faction nearestFriend(ServerLevel level, ServerPlayer player) {
        Relations rel = Relations.get(level.getServer());
        Faction best = null;
        double bestSq = Double.MAX_VALUE;
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (v.isConquered()) continue;
            Faction f = FactionManager.INSTANCE.get(v.faction());
            if (f == null || f.outlaw() || rel.atWar(player.getUUID(), f.id())) continue;
            double sq = VillageData.horizontalDistSq(v.center(), player.blockPosition());
            if (sq < bestSq) {
                bestSq = sq;
                best = f;
            }
        }
        return best;
    }

    public static Component contractText(ServerLevel level, DungeonData.Contract c) {
        return contractLine(level, c);
    }

    static Component contractLine(ServerLevel level, DungeonData.Contract c) {
        DungeonData.Dungeon d = DungeonData.get(level).byId(c.dungeon());
        long days = Math.max(0, (c.deadline() - level.getGameTime()) / 24000L);
        return Component.translatable("gui.warbandcraft.contract.current", d == null ? "?" : d.name, d == null ? "" : Dungeons.where(d.entrance),
                c.reward(), days).withStyle(ChatFormatting.GOLD);
    }

    /** Una mazmorra quedó limpia: se pagan los contratos sobre ella y se borran los rumores de ese lugar. */
    static void onCleared(ServerLevel level, DungeonData.Dungeon d) {
        DungeonData data = DungeonData.get(level);
        for (Map.Entry<UUID, DungeonData.Contract> e : new ArrayList<>(data.contracts().entrySet())) {
            DungeonData.Contract c = e.getValue();
            if (c.dungeon() != d.id) continue;
            Dungeons.pay(level, e.getKey(), c.reward(), d.entrance);
            Dungeons.opinion(level, e.getKey(), c.faction(), CONTRACT_OPINION);
            data.setContract(e.getKey(), null);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            Faction f = FactionManager.INSTANCE.get(c.faction());
            if (p != null) {
                p.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.contract_done", f == null ? c.faction().getPath() : f.name(),
                        c.reward(), CONTRACT_OPINION).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Contratos vencidos (cada segundo, desde {@link Dungeons#tick}). */
    static void tickContracts(ServerLevel level) {
        DungeonData data = DungeonData.get(level);
        long now = level.getGameTime();
        for (Map.Entry<UUID, DungeonData.Contract> e : new ArrayList<>(data.contracts().entrySet())) {
            if (now < e.getValue().deadline()) continue;
            data.setContract(e.getKey(), null);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p != null) p.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.contract_expired").withStyle(ChatFormatting.GRAY));
        }
    }

    // ------------------------------------------------------------------ plata

    /** Cobra: primero del inventario, después del Campamento de la aldea del tabernero. */
    private static boolean pay(ServerPlayer player, @Nullable InnkeeperEntity inn, int n) {
        int have = player.getInventory().countItem(Items.EMERALD);
        CampBlockEntity camp = inn != null && inn.getCamp() != null && player.serverLevel().getBlockEntity(inn.getCamp()) instanceof CampBlockEntity c ? c : null;
        int inCamp = camp == null ? 0 : camp.count(Items.EMERALD);
        if (have + inCamp < n) return false;
        int fromInv = Math.min(have, n);
        int left = fromInv;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            var s = player.getInventory().getItem(i);
            if (s.is(Items.EMERALD)) {
                int t = Math.min(left, s.getCount());
                s.shrink(t);
                left -= t;
            }
        }
        if (n - fromInv > 0 && camp != null) camp.take(Items.EMERALD, n - fromInv);
        return true;
    }

    private static void refund(ServerPlayer player, int n) {
        var s = new net.minecraft.world.item.ItemStack(Items.EMERALD, n);
        if (!player.getInventory().add(s)) player.drop(s, false);
    }
}
