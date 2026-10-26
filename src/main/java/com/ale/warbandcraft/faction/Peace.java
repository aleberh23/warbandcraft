package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.dungeon.DungeonData;
import com.ale.warbandcraft.dungeon.Dungeons;
import com.ale.warbandcraft.dungeon.Rumors;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.invasion.Threat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hacer la paz con una facción de la IA (pedido de Ale: "pagando esmeraldas o haciendo algún favor"). Se pide con
 * {@code /warband paz} o al Tabernero. Pagar: {@link #price} esmeraldas (inventario y después tu Campamento más cercano).
 * Favor: limpiar una guarida o túmulo para ellos (un trabajo del Tabernero que en vez de pagar da la paz). La paz vale para
 * toda tu facción de Recruits: la opinión sube a {@link #PEACE_OPINION}, {@link #TRUCE_DAYS} días de tregua (no te atacan
 * aunque la opinión vuelva a bajar), y se vuelven las invasiones que venían en camino y las huestes acampadas. Atacarlos
 * rompe la tregua ({@link #breakTruce}).
 */
public final class Peace {
    public static final String KIND_LIST = "peace", KIND_OFFER = "peace_offer";

    /** Precio: base + por cada punto de odio debajo de la guerra + por cada aldea/castillo suyo que tenés. */
    public static int PRICE_BASE = 30, PRICE_PER_HATE = 1, PRICE_PER_TAKEN = 15;
    /** Opinión después de la paz. */
    public static int PEACE_OPINION = -10;
    /** Días de tregua. */
    public static int TRUCE_DAYS = 10;

    private Peace() {}

    public static int price(ServerLevel level, UUID player, ResourceLocation faction) {
        int hate = Math.max(0, Relations.WAR_THRESHOLD - Relations.get(level.getServer()).opinion(player, faction));
        return PRICE_BASE + PRICE_PER_HATE * hate + PRICE_PER_TAKEN * Invasions.takenFrom(level, player, faction).size();
    }

    /** Facciones (con aldeas, no forajidas) en guerra con el jugador. */
    public static List<Faction> enemies(ServerLevel level, UUID player) {
        Relations rel = Relations.get(level.getServer());
        List<Faction> out = new ArrayList<>();
        for (Faction f : FactionManager.INSTANCE.ordered()) if (!f.outlaw() && rel.atWar(player, f.id())) out.add(f);
        return out;
    }

    /** La lista: con quién querés la paz. */
    public static void open(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<Faction> enemies = enemies(level, player.getUUID());
        if (enemies.isEmpty()) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.peace.no_war").withStyle(ChatFormatting.GRAY));
            return;
        }
        Relations rel = Relations.get(level.getServer());
        List<Component> lines = new ArrayList<>();
        List<Component> options = new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        for (Faction f : enemies) {
            int near = (int) Math.round(Threat.proximityTo(level, f.id(), player.getUUID()) * 100);
            lines.add(Component.translatable("gui.warbandcraft.peace.line", Component.literal(f.name()).withStyle(f.color()),
                    rel.opinion(player.getUUID(), f.id()), near));
            options.add(Component.literal(f.name()).withStyle(f.color()));
            if (ids.length() > 0) ids.append(',');
            ids.append(f.id());
        }
        options.add(Component.translatable("gui.warbandcraft.peace.nothing"));
        BattleChoices.send(player, KIND_LIST, ids.toString(), Component.translatable("gui.warbandcraft.peace.title"), lines, options, true);
    }

    /** Lo que pide una facción: pagar o un favor. */
    public static void offer(ServerPlayer player, Faction f) {
        ServerLevel level = player.serverLevel();
        int price = price(level, player.getUUID(), f.id());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.warbandcraft.peace.terms", Component.literal(f.name()).withStyle(f.color()), price, TRUCE_DAYS));
        int taken = Invasions.takenFrom(level, player.getUUID(), f.id()).size();
        if (taken > 0) lines.add(Component.translatable("gui.warbandcraft.peace.taken", taken, PRICE_PER_TAKEN).withStyle(ChatFormatting.GRAY));
        List<Component> options = new ArrayList<>();
        options.add(Component.translatable("gui.warbandcraft.peace.pay", price));
        DungeonData.Dungeon favor = DungeonData.get(level).contractOf(player.getUUID()) == null ? Rumors.contractTarget(level, player, false) : null;
        if (favor != null) {
            lines.add(Component.translatable("gui.warbandcraft.peace.favor_line", favor.name, Dungeons.where(favor.entrance), Rumors.CONTRACT_DAYS));
            options.add(Component.translatable("gui.warbandcraft.peace.favor"));
        } else {
            options.add(Component.translatable("gui.warbandcraft.peace.no_favor"));
        }
        options.add(Component.translatable("gui.warbandcraft.peace.nothing"));
        BattleChoices.send(player, KIND_OFFER, f.id() + "|" + price, Component.translatable("gui.warbandcraft.peace.offer_title", f.name()), lines,
                options, true);
    }

    public static void answer(ServerPlayer player, String kind, String id, int option) {
        ServerLevel level = player.serverLevel();
        if (KIND_LIST.equals(kind)) {
            String[] ids = id.split(",");
            if (option < 0 || option >= ids.length) return;
            Faction f = FactionManager.INSTANCE.get(ResourceLocation.tryParse(ids[option]));
            if (f != null) offer(player, f);
        } else if (KIND_OFFER.equals(kind)) {
            String[] parts = id.split("\\|");
            Faction f = parts.length == 2 ? FactionManager.INSTANCE.get(ResourceLocation.tryParse(parts[0])) : null;
            if (f == null) return;
            if (option == 0) player.sendSystemMessage(buy(player, f));
            else if (option == 1) player.sendSystemMessage(askFavor(player, f));
        }
    }

    /** Pagar la paz (al precio de ahora). */
    public static Component buy(ServerPlayer player, Faction f) {
        ServerLevel level = player.serverLevel();
        if (!Relations.get(level.getServer()).atWar(player.getUUID(), f.id()))
            return Component.translatable("msg.warbandcraft.peace.already").withStyle(ChatFormatting.GRAY);
        int price = price(level, player.getUUID(), f.id());
        if (!pay(player, price)) return Component.translatable("msg.warbandcraft.peace.cant_pay", price).withStyle(ChatFormatting.RED);
        make(level, player.getUUID(), f.id());
        return Component.translatable("msg.warbandcraft.peace.paid", price, Component.literal(f.name()).withStyle(f.color()))
                .withStyle(ChatFormatting.GREEN);
    }

    /** El favor: un trabajo del Tabernero que en vez de pagar trae la paz. */
    public static Component askFavor(ServerPlayer player, Faction f) {
        ServerLevel level = player.serverLevel();
        DungeonData data = DungeonData.get(level);
        if (data.contractOf(player.getUUID()) != null) return Component.translatable("msg.warbandcraft.tavern.has_contract").withStyle(ChatFormatting.GRAY);
        DungeonData.Dungeon d = Rumors.contractTarget(level, player, true);
        if (d == null) return Component.translatable("msg.warbandcraft.tavern.no_work").withStyle(ChatFormatting.GRAY);
        data.setContract(player.getUUID(), new DungeonData.Contract(d.id, f.id(), 0, level.getGameTime() + Rumors.CONTRACT_DAYS * 24000L, true));
        d.known.add(player.getUUID());
        data.changed();
        return Component.translatable("msg.warbandcraft.peace.favor_taken", Component.literal(f.name()).withStyle(f.color()), d.name,
                Dungeons.where(d.entrance), Rumors.CONTRACT_DAYS).withStyle(ChatFormatting.GREEN);
    }

    /**
     * La paz: para {@code player} y toda su facción de Recruits, opinión a {@link #PEACE_OPINION} (si estaba más abajo),
     * tregua de {@link #TRUCE_DAYS} días, se vuelven las invasiones en camino y las huestes acampadas de {@code faction}.
     */
    public static void make(ServerLevel level, UUID player, ResourceLocation faction) {
        Relations rel = Relations.get(level.getServer());
        long until = level.getDayTime() / 24000L + TRUCE_DAYS;
        InvasionData data = InvasionData.get(level);
        for (UUID m : Allies.members(player)) {
            if (rel.opinion(m, faction) < PEACE_OPINION) rel.set(m, faction, PEACE_OPINION);
            rel.setTruce(m, faction, until);
            for (InvasionData.Invasion inv : new ArrayList<>(data.all())) {
                if (inv.owner.equals(m) && inv.faction.equals(faction) && inv.state == InvasionData.State.MARCHING) {
                    data.remove(inv.target);
                    data.setLevy(faction, data.levy(faction) + inv.size);
                }
            }
            for (InvasionData.Host h : new ArrayList<>(data.hosts())) {
                if (h.owner.equals(m) && h.faction.equals(faction) && h.state == InvasionData.Host.State.CAMPED) {
                    data.removeHost(h.id);
                    data.setHostPool(faction, data.hostPool(faction) + h.size);
                }
            }
        }
        Faction f = FactionManager.INSTANCE.get(faction);
        Component name = f == null ? Component.literal(faction.getPath()) : Component.literal(f.name()).withStyle(f.color());
        tellAll(level, player, Component.translatable("msg.warbandcraft.peace.made", name, TRUCE_DAYS).withStyle(ChatFormatting.GREEN));
    }

    /** Atacaste a una facción con la que tenías tregua: se rompe para toda tu facción. */
    public static void breakTruce(ServerLevel level, UUID player, ResourceLocation faction) {
        Relations rel = Relations.get(level.getServer());
        long day = level.getDayTime() / 24000L;
        if (!rel.truce(player, faction, day)) return;
        for (UUID m : Allies.members(player)) rel.setTruce(m, faction, -1);
        Faction f = FactionManager.INSTANCE.get(faction);
        tellAll(level, player, Component.translatable("msg.warbandcraft.peace.broken", f == null ? faction.getPath() : f.name())
                .withStyle(ChatFormatting.RED));
    }

    private static void tellAll(ServerLevel level, UUID player, Component msg) {
        for (UUID m : Allies.members(player)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(m);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    /** Cobra: inventario y después tu Campamento más cercano (si está cargado). */
    static boolean pay(ServerPlayer player, int n) {
        int have = player.getInventory().countItem(Items.EMERALD);
        CampBlockEntity camp = nearestCamp(player);
        int inCamp = camp == null ? 0 : camp.count(Items.EMERALD);
        if (have + inCamp < n) return false;
        int left = Math.min(have, n);
        int fromInv = left;
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

    @Nullable
    private static CampBlockEntity nearestCamp(ServerPlayer player) {
        BlockPos pos = CampRegistry.get(player.serverLevel()).nearestCamp(player.getUUID(), player.blockPosition());
        if (pos == null || !player.serverLevel().isLoaded(pos)) return null;
        return player.serverLevel().getBlockEntity(pos) instanceof CampBlockEntity c ? c : null;
    }
}
