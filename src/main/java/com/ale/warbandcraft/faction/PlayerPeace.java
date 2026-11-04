package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.village.Territory;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsDiplomacyManager;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paz entre jugadores (pedido de Ale, 4/11): "se envía la propuesta desde el Tabernero, y el que recibe la propuesta pone
 * precio, el otro acepta o no". Entre facciones de Recruits en guerra (ENEMY en su Diplomacia):
 * <ol>
 *   <li>Uno propone la paz en un Tabernero (Negociar la paz → la facción). Le avisa a los de la otra facción.</li>
 *   <li>Alguien de la otra facción, en un Tabernero, le pone precio (esmeraldas, 0 = gratis) o la rechaza.</li>
 *   <li>El que propuso, en un Tabernero, paga (inventario y después su Campamento más cercano) o la rechaza. Lo pagado va
 *       al Campamento del que puso el precio.</li>
 * </ol>
 * Firmada: las dos facciones quedan NEUTRAL en la Diplomacia de Recruits y con tregua {@link #TRUCE_DAYS} días (sin asedios
 * entre ellas, {@code PvpSiege}). Guardado en el overworld (data/warbandcraft_player_peace.dat).
 */
public class PlayerPeace extends SavedData {
    private static final String FILE = "warbandcraft_player_peace";
    public static final String KIND_PRICE = "pvp_peace_price", KIND_ACCEPT = "pvp_peace_accept";
    /** Días de tregua al firmar la paz. */
    public static int TRUCE_DAYS = 10;
    /** Una propuesta sin contestar se borra a los tantos días. */
    public static int OFFER_DAYS = 3;
    /** Precios que se pueden poner (botones de la pantalla). */
    private static final int[] PRICES = {0, 10, 25, 50, 100, 250};

    /** Propuesta de {@code from} (facción de Recruits) a {@code to}. {@code price} -1 = todavía sin precio. */
    public static final class Offer {
        public final String from, to;
        public final UUID proposer;
        public int price = -1;
        @Nullable public UUID pricer;
        public final long created;

        Offer(String from, String to, UUID proposer, long created) {
            this.from = from;
            this.to = to;
            this.proposer = proposer;
            this.created = created;
        }
    }

    private final Map<String, Offer> offers = new HashMap<>();
    private final Map<String, Long> truces = new HashMap<>();

    public static PlayerPeace get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlayerPeace::load, PlayerPeace::new, FILE);
    }

    private static String offerKey(String from, String to) {
        return from + "|" + to;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    // ------------------------------------------------------------------ consultas

    /** ¿Tienen tregua estas dos facciones de Recruits? */
    public boolean truce(ServerLevel level, @Nullable String a, @Nullable String b) {
        if (a == null || b == null) return false;
        Long until = truces.get(pairKey(a, b));
        return until != null && level.getDayTime() / 24000L < until;
    }

    /** Hasta qué día dura la tregua (o -1). */
    public long truceUntil(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return -1;
        return truces.getOrDefault(pairKey(a, b), -1L);
    }

    /** Facciones de jugadores (de Recruits, no las de la IA) en guerra con la tuya y sin tregua. */
    public static List<RecruitsFaction> enemies(ServerLevel level, String mine) {
        List<RecruitsFaction> out = new ArrayList<>();
        RecruitsFactionManager fm = FactionEvents.recruitsFactionManager;
        if (fm == null || mine == null) return out;
        PlayerPeace pp = get(level.getServer());
        for (RecruitsFaction f : fm.getFactions()) {
            String id = f.getStringID();
            if (id == null || id.equals(mine) || FactionManager.INSTANCE.isFactionTeam(id)) continue;
            if (atWar(mine, id) && !pp.truce(level, mine, id)) out.add(f);
        }
        return out;
    }

    public static boolean atWar(String a, String b) {
        RecruitsDiplomacyManager d = FactionEvents.recruitsDiplomacyManager;
        return d != null && (d.getRelation(a, b) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY
                || d.getRelation(b, a) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY);
    }

    public static String nameOf(String factionId) {
        RecruitsFactionManager fm = FactionEvents.recruitsFactionManager;
        RecruitsFaction f = fm == null ? null : fm.getFactionByStringID(factionId);
        return f == null || f.getTeamDisplayName() == null || f.getTeamDisplayName().isEmpty() ? factionId : f.getTeamDisplayName();
    }

    /** Las propuestas que te tocan contestar (a tu facción, sin precio) y las tuyas que ya tienen precio. */
    public List<Offer> toPrice(ServerLevel level, String mine) {
        expire(level);
        List<Offer> out = new ArrayList<>();
        for (Offer o : offers.values()) if (o.to.equals(mine) && o.price < 0) out.add(o);
        return out;
    }

    public List<Offer> toAccept(ServerLevel level, String mine) {
        expire(level);
        List<Offer> out = new ArrayList<>();
        for (Offer o : offers.values()) if (o.from.equals(mine) && o.price >= 0) out.add(o);
        return out;
    }

    @Nullable
    public Offer offer(String from, String to) {
        return offers.get(offerKey(from, to));
    }

    private void expire(ServerLevel level) {
        long now = level.getGameTime();
        if (offers.values().removeIf(o -> now - o.created > OFFER_DAYS * 24000L)) setDirty();
    }

    // ------------------------------------------------------------------ el Tabernero

    /**
     * Lo que suma la paz entre jugadores a la pantalla "Negociar la paz" del Tabernero: una opción por cosa para hacer.
     * {@code ids} recibe un código por opción: "p:facción" proponer, "s:facción" ponerle precio, "a:facción" aceptar.
     */
    public static void addOptions(ServerLevel level, ServerPlayer player, List<Component> lines, List<Component> options, List<String> ids) {
        String mine = Territory.factionOf(level, player.getUUID());
        if (mine == null) return;
        PlayerPeace pp = get(level.getServer());
        for (Offer o : pp.toPrice(level, mine)) {
            lines.add(Component.translatable("gui.warbandcraft.pvp_peace.asked", nameOf(o.from)).withStyle(ChatFormatting.AQUA));
            options.add(Component.translatable("gui.warbandcraft.pvp_peace.set_price", nameOf(o.from)));
            ids.add("s:" + o.from);
        }
        for (Offer o : pp.toAccept(level, mine)) {
            lines.add(Component.translatable("gui.warbandcraft.pvp_peace.priced", nameOf(o.to), o.price).withStyle(ChatFormatting.AQUA));
            options.add(Component.translatable("gui.warbandcraft.pvp_peace.answer", nameOf(o.to), o.price));
            ids.add("a:" + o.to);
        }
        for (RecruitsFaction f : enemies(level, mine)) {
            String id = f.getStringID();
            if (pp.offer(mine, id) != null || pp.offer(id, mine) != null) continue;   // ya hay una propuesta en curso
            lines.add(Component.translatable("gui.warbandcraft.pvp_peace.war_line", nameOf(id)));
            options.add(Component.translatable("gui.warbandcraft.pvp_peace.propose", nameOf(id)));
            ids.add("p:" + id);
        }
    }

    /** Eligió una de las opciones de {@link #addOptions}. */
    public static void choose(ServerPlayer player, String code) {
        ServerLevel level = player.serverLevel();
        String mine = Territory.factionOf(level, player.getUUID());
        if (mine == null || code.length() < 3) return;
        String other = code.substring(2);
        switch (code.charAt(0)) {
            case 'p' -> player.sendSystemMessage(propose(level, player, mine, other));
            case 's' -> priceScreen(level, player, mine, other);
            case 'a' -> acceptScreen(level, player, mine, other);
            default -> {
            }
        }
    }

    public static Component propose(ServerLevel level, ServerPlayer player, String mine, String other) {
        PlayerPeace pp = get(level.getServer());
        if (!atWar(mine, other) || pp.truce(level, mine, other)) return Component.translatable("msg.warbandcraft.pvp_peace.no_war").withStyle(ChatFormatting.GRAY);
        if (pp.offer(mine, other) != null || pp.offer(other, mine) != null) return Component.translatable("msg.warbandcraft.pvp_peace.pending").withStyle(ChatFormatting.GRAY);
        pp.offers.put(offerKey(mine, other), new Offer(mine, other, player.getUUID(), level.getGameTime()));
        pp.setDirty();
        tellFaction(level, other, Component.translatable("msg.warbandcraft.pvp_peace.proposed_to_you", nameOf(mine), OFFER_DAYS).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        WarbandCraft.LOGGER.info("Paz entre jugadores: {} propone la paz a {}", mine, other);
        return Component.translatable("msg.warbandcraft.pvp_peace.proposed", nameOf(other)).withStyle(ChatFormatting.GREEN);
    }

    private static void priceScreen(ServerLevel level, ServerPlayer player, String mine, String from) {
        Offer o = get(level.getServer()).offer(from, mine);
        if (o == null || o.price >= 0) return;
        List<Component> lines = List.of(Component.translatable("gui.warbandcraft.pvp_peace.price_help", nameOf(from), TRUCE_DAYS));
        List<Component> options = new ArrayList<>();
        for (int p : PRICES) options.add(p == 0 ? Component.translatable("gui.warbandcraft.pvp_peace.free") : Component.translatable("gui.warbandcraft.pvp_peace.price", p));
        options.add(Component.translatable("gui.warbandcraft.pvp_peace.reject"));
        BattleChoices.send(player, KIND_PRICE, from, Component.translatable("gui.warbandcraft.pvp_peace.title", nameOf(from)), lines, options, true);
    }

    private static void acceptScreen(ServerLevel level, ServerPlayer player, String mine, String to) {
        Offer o = get(level.getServer()).offer(mine, to);
        if (o == null || o.price < 0) return;
        List<Component> lines = List.of(Component.translatable("gui.warbandcraft.pvp_peace.accept_help", nameOf(to), o.price, TRUCE_DAYS));
        List<Component> options = List.of(o.price == 0 ? Component.translatable("gui.warbandcraft.pvp_peace.sign")
                : Component.translatable("gui.warbandcraft.pvp_peace.pay", o.price), Component.translatable("gui.warbandcraft.pvp_peace.reject"));
        BattleChoices.send(player, KIND_ACCEPT, to, Component.translatable("gui.warbandcraft.pvp_peace.title", nameOf(to)), lines, options, true);
    }

    public static void answer(ServerPlayer player, String kind, String id, int option) {
        ServerLevel level = player.serverLevel();
        String mine = Territory.factionOf(level, player.getUUID());
        if (mine == null) return;
        if (KIND_PRICE.equals(kind)) {
            if (option >= 0 && option < PRICES.length) player.sendSystemMessage(setPrice(level, player, id, mine, PRICES[option]));
            else if (option == PRICES.length) player.sendSystemMessage(reject(level, id, mine, mine));
        } else if (KIND_ACCEPT.equals(kind)) {
            if (option == 0) player.sendSystemMessage(accept(level, player, mine, id));
            else if (option == 1) player.sendSystemMessage(reject(level, mine, id, mine));
        }
    }

    /** El que recibió la propuesta le pone precio. */
    public static Component setPrice(ServerLevel level, @Nullable ServerPlayer player, String from, String to, int price) {
        PlayerPeace pp = get(level.getServer());
        Offer o = pp.offer(from, to);
        if (o == null || o.price >= 0) return Component.translatable("msg.warbandcraft.pvp_peace.gone").withStyle(ChatFormatting.GRAY);
        o.price = Math.max(0, price);
        o.pricer = player == null ? null : player.getUUID();
        pp.setDirty();
        tellFaction(level, from, Component.translatable("msg.warbandcraft.pvp_peace.priced_to_you", nameOf(to), o.price).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        return Component.translatable("msg.warbandcraft.pvp_peace.price_set", nameOf(from), o.price).withStyle(ChatFormatting.GREEN);
    }

    /** Cualquiera de los dos la rechaza ({@code by} = la facción que la rechaza). */
    public static Component reject(ServerLevel level, String from, String to, String by) {
        PlayerPeace pp = get(level.getServer());
        if (pp.offers.remove(offerKey(from, to)) == null) return Component.translatable("msg.warbandcraft.pvp_peace.gone").withStyle(ChatFormatting.GRAY);
        pp.setDirty();
        String other = by.equals(from) ? to : from;
        tellFaction(level, other, Component.translatable("msg.warbandcraft.pvp_peace.rejected_you", nameOf(by)).withStyle(ChatFormatting.RED));
        return Component.translatable("msg.warbandcraft.pvp_peace.rejected", nameOf(other)).withStyle(ChatFormatting.GRAY);
    }

    /** El que propuso acepta el precio: paga y se firma la paz. */
    public static Component accept(ServerLevel level, ServerPlayer player, String from, String to) {
        PlayerPeace pp = get(level.getServer());
        Offer o = pp.offer(from, to);
        if (o == null || o.price < 0) return Component.translatable("msg.warbandcraft.pvp_peace.gone").withStyle(ChatFormatting.GRAY);
        if (o.price > 0 && !Peace.pay(player, o.price)) {
            return Component.translatable("msg.warbandcraft.peace.cant_pay", o.price).withStyle(ChatFormatting.RED);
        }
        if (o.price > 0) {
            UUID to2 = o.pricer != null ? o.pricer : null;
            if (to2 != null) {
                List<ItemStack> pay = new ArrayList<>();
                for (int left = o.price; left > 0; left -= 64) pay.add(new ItemStack(Items.EMERALD, Math.min(64, left)));
                CampRegistry.get(level).deliver(level, to2, pay, player.blockPosition());
            }
        }
        sign(level, from, to);
        return Component.translatable("msg.warbandcraft.pvp_peace.signed_you", nameOf(to), TRUCE_DAYS).withStyle(ChatFormatting.GREEN);
    }

    /** Se firma: NEUTRAL en Recruits, tregua y sin propuestas entre ellas. */
    public static void sign(ServerLevel level, String a, String b) {
        PlayerPeace pp = get(level.getServer());
        pp.offers.remove(offerKey(a, b));
        pp.offers.remove(offerKey(b, a));
        pp.truces.put(pairKey(a, b), level.getDayTime() / 24000L + TRUCE_DAYS);
        pp.setDirty();
        RecruitsDiplomacyManager d = FactionEvents.recruitsDiplomacyManager;
        if (d != null) {
            try {
                d.setRelation(a, b, RecruitsDiplomacyManager.DiplomacyStatus.NEUTRAL, level.getServer().overworld());
                d.setRelation(b, a, RecruitsDiplomacyManager.DiplomacyStatus.NEUTRAL, level.getServer().overworld());
            } catch (RuntimeException e) {
                WarbandCraft.LOGGER.warn("No se pudo poner la paz en la Diplomacia de Recruits entre {} y {}", a, b, e);
            }
        }
        Component msg = Component.translatable("msg.warbandcraft.pvp_peace.signed", nameOf(a), nameOf(b), TRUCE_DAYS).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
        tellFaction(level, a, msg);
        tellFaction(level, b, msg);
        WarbandCraft.LOGGER.info("Paz entre jugadores firmada: {} y {} ({} días de tregua)", a, b, TRUCE_DAYS);
    }

    /** Saca propuestas y tregua entre dos facciones (lo usan las pruebas). */
    public void forget(String a, String b) {
        offers.remove(offerKey(a, b));
        offers.remove(offerKey(b, a));
        truces.remove(pairKey(a, b));
        setDirty();
    }

    /** Al entrar: lo que le toca contestar a su facción. */
    public static void onLogin(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        String mine = Territory.factionOf(level, player.getUUID());
        if (mine == null) return;
        PlayerPeace pp = get(level.getServer());
        for (Offer o : pp.toPrice(level, mine)) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_peace.proposed_to_you", nameOf(o.from), OFFER_DAYS).withStyle(ChatFormatting.AQUA));
        }
        for (Offer o : pp.toAccept(level, mine)) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_peace.priced_to_you", nameOf(o.to), o.price).withStyle(ChatFormatting.AQUA));
        }
    }

    private static void tellFaction(ServerLevel level, String faction, Component msg) {
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.getTeam() != null && faction.equals(p.getTeam().getName())) p.sendSystemMessage(msg);
        }
    }

    // ------------------------------------------------------------------ guardado

    public static PlayerPeace load(CompoundTag tag) {
        PlayerPeace pp = new PlayerPeace();
        for (Tag t : tag.getList("Offers", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (!c.hasUUID("Proposer")) continue;
            Offer o = new Offer(c.getString("From"), c.getString("To"), c.getUUID("Proposer"), c.getLong("Created"));
            o.price = c.getInt("Price");
            if (c.hasUUID("Pricer")) o.pricer = c.getUUID("Pricer");
            pp.offers.put(offerKey(o.from, o.to), o);
        }
        CompoundTag tr = tag.getCompound("Truces");
        for (String k : tr.getAllKeys()) pp.truces.put(k, tr.getLong(k));
        return pp;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Offer o : offers.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("From", o.from);
            c.putString("To", o.to);
            c.putUUID("Proposer", o.proposer);
            c.putLong("Created", o.created);
            c.putInt("Price", o.price);
            if (o.pricer != null) c.putUUID("Pricer", o.pricer);
            list.add(c);
        }
        tag.put("Offers", list);
        CompoundTag tr = new CompoundTag();
        truces.forEach(tr::putLong);
        tag.put("Truces", tr);
        return tag;
    }
}
