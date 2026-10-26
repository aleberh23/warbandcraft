package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Allies;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.village.VillageData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quién te ataca y cuándo (pedido de Ale: "es insostenible una hueste y invasiones cada tan poco; que sea por cercanía").
 * Una sola tirada por día para cada facción de jugadores (vos y tus compañeros de Recruits juntos), no una por facción
 * enemiga ni por jugador. Cada facción de la IA pesa según lo cerca que tiene una aldea o castillo de los tuyos
 * ({@link #proximity}): pegada, como antes; lejos, casi nunca. Después de una invasión, una hueste o una reconquista,
 * {@link #REST_DAYS} días tranquilos; con una tregua comprada ({@code faction/Peace}) esa facción no te ataca.
 * Sin nadie de tu facción conectado no sale nada.
 */
public final class Threat {
    /** Hasta esta distancia (de su aldea/castillo más cercano a uno tuyo) la facción te ataca con todas las chances. */
    public static int NEAR_DISTANCE = 500;
    /** Desde esta distancia, solo con {@link #REMOTE_FACTOR} de las chances. */
    public static int FAR_DISTANCE = 1500;
    public static double REMOTE_FACTOR = 0.1;
    /** Chance por día de una invasión de una facción en guerra, pegada a vos. */
    public static double INVASION_CHANCE = 0.25;
    /** Días tranquilos después de una invasión o una reconquista, y después de una hueste. */
    public static int REST_DAYS = 3, REST_AFTER_HOST_DAYS = 5;

    private Threat() {}

    /** Lo que puede pasar hoy: una facción que hace algo, con su chance. */
    public enum Kind { INVASION, HOST, RECONQUER }

    public record Option(ResourceLocation faction, Kind kind, double chance, UUID owner) {}

    /** De 1 (pegada) a {@link #REMOTE_FACTOR} (lejos), según la distancia en bloques. */
    public static double proximity(double distance) {
        if (distance <= NEAR_DISTANCE) return 1;
        if (distance >= FAR_DISTANCE) return REMOTE_FACTOR;
        double t = (distance - NEAR_DISTANCE) / (double) (FAR_DISTANCE - NEAR_DISTANCE);
        return 1 + (REMOTE_FACTOR - 1) * t;
    }

    /** Distancia de la aldea/castillo de {@code faction} más cercana a uno de {@code mine}; infinito si no tiene. */
    public static double distance(ServerLevel level, ResourceLocation faction, List<VillageData.Village> mine) {
        double best = Double.MAX_VALUE;
        for (VillageData.Village h : Invasions.settlements(level, faction)) {
            for (VillageData.Village v : mine) best = Math.min(best, VillageData.horizontalDistSq(h.center(), v.center()));
        }
        return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
    }

    /** Clave del grupo de jugadores (facción de Recruits o el jugador solo). */
    public static String groupKey(UUID owner) {
        String f = Allies.factionOf(owner);
        return f != null ? "team:" + f : "player:" + owner;
    }

    /** Día nuevo: a lo sumo una cosa por grupo de jugadores. */
    public static void onNewDay(ServerLevel level) {
        Map<String, List<VillageData.Village>> groups = new LinkedHashMap<>();
        for (VillageData.Village v : VillageData.get(level).all()) {
            if (v.isConquered() && !VillageData.get(level).isTest(v.center())) groups.computeIfAbsent(groupKey(v.owner()), k -> new ArrayList<>()).add(v);
        }
        long day = level.getDayTime() / 24000L;
        RandomSource random = level.getRandom();
        for (Map.Entry<String, List<VillageData.Village>> e : groups.entrySet()) {
            List<VillageData.Village> mine = e.getValue();
            if (!Hosts.factionOnline(level, mine.get(0).owner())) continue;   // pedido de Ale: sin nadie conectado, nada
            if (InvasionData.get(level).restUntil(e.getKey()) >= day) continue;
            List<Option> options = options(level, mine);
            Option pick = roll(options, random);
            if (pick == null) continue;
            if (run(level, pick)) {
                InvasionData.get(level).setRest(e.getKey(), day + (pick.kind == Kind.HOST ? REST_AFTER_HOST_DAYS : REST_DAYS));
                WarbandCraft.LOGGER.info("Amenaza del día para {}: {} de {} (chance {})", e.getKey(), pick.kind, pick.faction,
                        String.format("%.2f", pick.chance));
            }
        }
    }

    /** Todo lo que cada facción de la IA podría hacer hoy contra este grupo, con su chance (ya multiplicada por la cercanía). */
    public static List<Option> options(ServerLevel level, List<VillageData.Village> mine) {
        Relations rel = Relations.get(level.getServer());
        InvasionData data = InvasionData.get(level);
        long day = level.getDayTime() / 24000L;
        List<Option> out = new ArrayList<>();
        for (Faction f : FactionManager.INSTANCE.ordered()) {
            double d = distance(level, f.id(), mine);
            if (d == Double.MAX_VALUE) continue;   // no tiene de dónde salir
            double prox = proximity(d);
            // El que manda: el dueño de la aldea más cercana a sus tierras (contra esa van)
            UUID owner = closestOwner(level, f.id(), mine);
            if (rel.truce(owner, f.id(), day)) continue;
            if (rel.atWar(owner, f.id())) {
                if (data.levy(f.id()) >= Invasions.MIN_ARMY) out.add(new Option(f.id(), Kind.INVASION, INVASION_CHANCE * prox, owner));
                if (Hosts.hostOf(level, f.id(), owner) == null && data.hostCooldown(f.id(), owner) < day && data.hostPool(f.id()) >= Hosts.MIN_SIZE) {
                    out.add(new Option(f.id(), Kind.HOST, Hosts.chance(rel.opinion(owner, f.id())) * prox, owner));
                }
            } else if (data.levy(f.id()) >= Invasions.MIN_ARMY && !Invasions.takenFrom(level, owner, f.id()).isEmpty()) {
                out.add(new Option(f.id(), Kind.RECONQUER, Invasions.RECONQUER_CHANCE * prox, owner));
            }
        }
        return out;
    }

    /** La chance del día es la de la opción más probable (varias guerras no se suman); si sale, gana una al azar según su peso. */
    public static Option roll(List<Option> options, RandomSource random) {
        double best = 0, total = 0;
        for (Option o : options) {
            best = Math.max(best, o.chance);
            total += o.chance;
        }
        if (best <= 0 || random.nextDouble() >= best) return null;
        double r = random.nextDouble() * total;
        for (Option o : options) {
            r -= o.chance;
            if (r <= 0) return o;
        }
        return options.get(options.size() - 1);
    }

    private static boolean run(ServerLevel level, Option o) {
        return switch (o.kind) {
            case INVASION -> {
                boolean army = InvasionData.get(level).levy(o.faction) >= Invasions.GENERAL_ARMY_LEVY
                        && level.getRandom().nextDouble() < Invasions.GENERAL_ARMY_CHANCE;
                yield Invasions.launch(level, o.owner, o.faction, -1, Invasions.COUNTDOWN_TICKS, null,
                        army ? InvasionData.Kind.ARMY : InvasionData.Kind.RAID) != null;
            }
            case HOST -> Hosts.create(level, o.faction, o.owner, -1) != null;
            case RECONQUER -> Invasions.reconquer(level, o.owner, o.faction, -1, Invasions.COUNTDOWN_TICKS) != null;
        };
    }

    private static UUID closestOwner(ServerLevel level, ResourceLocation faction, List<VillageData.Village> mine) {
        UUID best = mine.get(0).owner();
        double bestSq = Double.MAX_VALUE;
        for (VillageData.Village h : Invasions.settlements(level, faction)) {
            for (VillageData.Village v : mine) {
                double sq = VillageData.horizontalDistSq(h.center(), v.center());
                if (sq < bestSq) {
                    bestSq = sq;
                    best = v.owner();
                }
            }
        }
        return best;
    }

    /** Para el Panel / los mensajes: qué tan cerca está cada facción (0..1) del grupo de {@code owner}. */
    public static double proximityTo(ServerLevel level, ResourceLocation faction, UUID owner) {
        List<VillageData.Village> mine = new ArrayList<>();
        for (VillageData.Village v : VillageData.get(level).all()) if (v.isConquered() && Allies.same(v.owner(), owner)) mine.add(v);
        if (mine.isEmpty()) return 0;
        double d = distance(level, faction, mine);
        return d == Double.MAX_VALUE ? 0 : proximity(d);
    }
}
