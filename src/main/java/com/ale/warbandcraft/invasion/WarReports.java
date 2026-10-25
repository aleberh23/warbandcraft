package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Allies;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido de Ale ("ayer me fui con una aldea y hoy me conecté, la perdí"): parte de guerra de cada invasión a una aldea o
 * castillo tuyo (ganada o perdida, peleada o autoresuelta). Se guarda en el overworld (data/warbandcraft_reports.dat),
 * va al log del servidor y le llega a todos los de tu facción: al que está conectado en el acto, al que no, al entrar.
 * {@code /warband partes} muestra los últimos.
 */
public class WarReports extends SavedData {
    private static final String FILE = "warbandcraft_reports";
    /** Cuántos partes se guardan (los más viejos se borran). */
    private static final int KEEP = 60;
    /** Cuántos muestra {@code /warband partes}. */
    private static final int LIST = 10;
    /** Hora de Argentina (el servidor puede estar en otra). */
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);

    public enum Outcome { LOST, WON, REPELLED, RETREATED }

    public static final class Report {
        public final long realTime, day;
        public final UUID owner;
        public final BlockPos center;
        /** El nombre que le puso el dueño ("" = el de siempre: "tu aldea en x, z"). */
        public final String place;
        public final boolean castle;
        public final ResourceLocation faction;
        public final Outcome outcome;
        /** true = se peleó de verdad; false = autoresolución (no había nadie cerca). */
        public final boolean fought;
        public final int attackers, defenders, guardsLost;
        /** A quiénes todavía no se les mostró. */
        public final Set<UUID> unread = new HashSet<>();

        Report(long realTime, long day, UUID owner, BlockPos center, String place, boolean castle, ResourceLocation faction, Outcome outcome,
               boolean fought, int attackers, int defenders, int guardsLost) {
            this.castle = castle;
            this.realTime = realTime;
            this.day = day;
            this.owner = owner;
            this.center = center;
            this.place = place;
            this.faction = faction;
            this.outcome = outcome;
            this.fought = fought;
            this.attackers = attackers;
            this.defenders = defenders;
            this.guardsLost = guardsLost;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Time", realTime);
            t.putLong("Day", day);
            t.putUUID("Owner", owner);
            t.put("Center", NbtUtils.writeBlockPos(center));
            t.putString("Place", place);
            t.putBoolean("Castle", castle);
            t.putString("Faction", faction.toString());
            t.putString("Outcome", outcome.name());
            t.putBoolean("Fought", fought);
            t.putInt("Attackers", attackers);
            t.putInt("Defenders", defenders);
            t.putInt("GuardsLost", guardsLost);
            ListTag u = new ListTag();
            for (UUID id : unread) u.add(NbtUtils.createUUID(id));
            t.put("Unread", u);
            return t;
        }

        @Nullable
        static Report load(CompoundTag t) {
            ResourceLocation f = ResourceLocation.tryParse(t.getString("Faction"));
            if (f == null || !t.hasUUID("Owner")) return null;
            Outcome o;
            try {
                o = Outcome.valueOf(t.getString("Outcome"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            Report r = new Report(t.getLong("Time"), t.getLong("Day"), t.getUUID("Owner"), NbtUtils.readBlockPos(t.getCompound("Center")),
                    t.getString("Place"), t.getBoolean("Castle"), f, o, t.getBoolean("Fought"), t.getInt("Attackers"), t.getInt("Defenders"), t.getInt("GuardsLost"));
            for (Tag x : t.getList("Unread", Tag.TAG_INT_ARRAY)) r.unread.add(NbtUtils.loadUUID(x));
            return r;
        }
    }

    private final List<Report> reports = new ArrayList<>();

    public static WarReports get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WarReports::load, WarReports::new, FILE);
    }

    public static WarReports load(CompoundTag tag) {
        WarReports d = new WarReports();
        for (Tag x : tag.getList("Reports", Tag.TAG_COMPOUND)) {
            Report r = Report.load((CompoundTag) x);
            if (r != null) d.reports.add(r);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag l = new ListTag();
        for (Report r : reports) l.add(r.save());
        tag.put("Reports", l);
        return tag;
    }

    /**
     * Anota el parte, lo escribe en el log y se lo manda a los de la facción del dueño que están conectados (a los otros,
     * al entrar).
     */
    public static void record(ServerLevel level, InvasionData.Invasion inv, Outcome outcome, boolean fought, int attackers,
                              int defenders, int guardsLost) {
        VillageData.Village v = VillageData.get(level).at(inv.target);
        String place = v == null ? "" : com.ale.warbandcraft.village.VillageCamps.displayName(level, v, Component.empty()).getString();
        Report r = new Report(System.currentTimeMillis(), level.getDayTime() / 24000L + 1, inv.owner, inv.target, place,
                v != null && v.castle(), inv.faction,
                outcome, fought, attackers, defenders, guardsLost);
        MinecraftServer server = level.getServer();
        r.unread.addAll(Allies.members(inv.owner));
        WarbandCraft.LOGGER.info("Parte de guerra: {} {} ({}, {}) de {} contra {} ({}): {} atacantes contra {} defensores, cayeron {} guardias",
                outcome, place.isEmpty() ? (v != null && v.castle() ? "castillo" : "aldea") : place, inv.target.getX(), inv.target.getZ(), Allies.playerName(inv.owner), inv.faction, fought ? "peleada" : "autoresuelta",
                attackers, defenders, guardsLost);
        for (UUID id : List.copyOf(r.unread)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.sendSystemMessage(text(r, id));
            r.unread.remove(id);
        }
        WarReports data = get(server);
        data.reports.add(r);
        while (data.reports.size() > KEEP) data.reports.remove(0);
        data.setDirty();
    }

    /** Al entrar: los partes que pasaron mientras no estabas. */
    public static void onLogin(ServerPlayer player) {
        WarReports data = get(player.getServer());
        boolean any = false;
        for (Report r : data.reports) {
            if (!r.unread.remove(player.getUUID())) continue;
            if (!any) player.sendSystemMessage(Component.translatable("msg.warbandcraft.report.while_away").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            any = true;
            player.sendSystemMessage(text(r, player.getUUID()));
        }
        if (any) data.setDirty();
    }

    /** {@code /warband partes}: los últimos de tu facción. */
    public static List<Component> recent(ServerPlayer player) {
        List<Component> out = new ArrayList<>();
        List<Report> all = get(player.getServer()).reports;
        for (int i = all.size() - 1; i >= 0 && out.size() < LIST; i--) {
            Report r = all.get(i);
            if (Allies.same(r.owner, player.getUUID())) out.add(text(r, player.getUUID()));
        }
        return out;
    }

    /**
     * "⚔ 24/10 23:15 (día 57) — Perdiste Aldea Pepe (120, -40) a manos de Clanes del Norte: 34 soldados contra tu
     * guarnición de 12. Se resolvió sin nadie cerca. Cayeron 12 guardias."
     */
    public static Component text(Report r, UUID viewer) {
        boolean lost = r.outcome == Outcome.LOST;
        Component faction = Invasions.factionName(r.faction);
        Component where = r.place.isEmpty()
                ? Component.translatable(r.castle ? "msg.warbandcraft.invasion.castle" : "msg.warbandcraft.invasion.village", r.center.getX(), r.center.getZ())
                : Component.literal(r.place + " (" + r.center.getX() + ", " + r.center.getZ() + ")");
        Component whose = r.owner.equals(viewer) ? Component.translatable("msg.warbandcraft.report.yours")
                : Component.translatable("msg.warbandcraft.report.of", Allies.playerName(r.owner));
        MutableComponent m = Component.literal("⚔ " + FORMAT.format(Instant.ofEpochMilli(r.realTime)) + " ")
                .append(Component.translatable("msg.warbandcraft.report.day", r.day)).append(" — ")
                .withStyle(ChatFormatting.GRAY);
        m.append(Component.translatable("msg.warbandcraft.report." + r.outcome.name().toLowerCase(java.util.Locale.ROOT), whose, where, faction)
                .withStyle(lost ? ChatFormatting.RED : ChatFormatting.GREEN));
        m.append(" ").append(Component.translatable("msg.warbandcraft.report.forces", r.attackers, faction, r.defenders).withStyle(ChatFormatting.WHITE));
        m.append(" ").append(Component.translatable(r.fought ? "msg.warbandcraft.report.fought" : "msg.warbandcraft.report.auto").withStyle(ChatFormatting.GRAY));
        m.append(" ").append(Component.translatable("msg.warbandcraft.report.lost_guards", r.guardsLost).withStyle(ChatFormatting.GRAY));
        return m;
    }
}
