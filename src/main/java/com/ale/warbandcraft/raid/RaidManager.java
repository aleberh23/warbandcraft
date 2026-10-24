package com.ale.warbandcraft.raid;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.Prisoners;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.RecruitsClaimBridge;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenRaidChoiceMessage;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.MutableComponent;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.village.VillageStock;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Los ataques a aldeas (H3).
 *
 * 1. Empieza cuando un jugador (o un soldado suyo) lastima a un soldado de guarnición.
 * 2. Toda la guarnición va contra el atacante y sus tropas. La facción lo quiere menos.
 * 3. El equipo de cada soldado de guarnición que cae se guarda para el atacante.
 * 4. Sin guarnición viva → victoria: el jugador elige saquear o conquistar (botones en el chat).
 *
 * H9: mientras dura, {@link BattleGeneral} maneja a los defensores (y a tus soldados con la orden Batalla). Los
 * rendidos ({@link Surrender}) van como prisioneros al ganador; si abandonás, los tuyos rendidos se pasan a la
 * guarnición. Los ataques se guardan ({@link RaidData}): si se cierra el servidor en medio, siguen al volver.
 */
public final class RaidManager {
    /** Si el atacante se aleja más que esto de la aldea... */
    public static int LEAVE_DISTANCE = 160;
    /** ...durante este tiempo, el ataque se abandona. */
    public static int LEAVE_GRACE_TICKS = 20 * 60;
    /** Desde dónde se puede ganar (hay que estar en la aldea, con todo cargado). */
    public static int VICTORY_DISTANCE = 128;
    /** Hasta dónde se busca un enemigo para reforzar la defensa (más amplio que la vista normal: ya saben que atacan la aldea). */
    public static double REINFORCE_RANGE = 96;
    /** Cuánto dura vacía una aldea saqueada: 3 días de juego. */
    public static long LOOTED_TICKS = 3 * 24000L;

    public enum State { FIGHTING, WON }

    public static final class Raid {
        public final ResourceKey<Level> dimension;
        public final BlockPos center;
        public final ResourceLocation faction;
        public final UUID attacker;
        public final long startTick;
        public final List<ItemStack> fallenGear = new ArrayList<>();
        public int killed;
        public State state = State.FIGHTING;
        /** Escaleras que pusieron tus soldados en un asedio (se sacan al terminar). */
        public final List<Long> ladders = new ArrayList<>();
        /** Soldados rendidos en este ataque (de los dos bandos), por UUID. */
        public final Set<UUID> surrendered = new LinkedHashSet<>();
        long lastNearTick;
        /** Ya se le mostró la pantalla Saquear/Conquistar desde que entró (no se guarda: al volver a entrar, otra vez). */
        boolean choiceShown;
        /** Soldados que tenía la guarnición al empezar (para la barra de asalto). */
        int initialGarrison;
        /** Barra de arriba de la pantalla: cuántos defensores quedan. */
        @Nullable ServerBossEvent bar;
        /** El atacante que empezó (sirve si no está en la lista de jugadores, p. ej. en las pruebas). */
        @Nullable Player attackerRef;

        Raid(ResourceKey<Level> dimension, BlockPos center, ResourceLocation faction,
             UUID attacker, long startTick) {
            this.dimension = dimension;
            this.center = center;
            this.faction = faction;
            this.attacker = attacker;
            this.startTick = startTick;
            this.lastNearTick = startTick;
        }

        public ResourceLocation factionId() {
            return faction;
        }
    }

    /** Resultado de saquear o conquistar, para mostrar y para las pruebas. */
    public record Outcome(boolean ok, Component message, List<ItemStack> loot) {
        static Outcome fail(Component msg) {
            return new Outcome(false, msg.copy().withStyle(ChatFormatting.RED), List.of());
        }
    }

    private static final Map<String, Raid> RAIDS = new HashMap<>();
    /** Dónde se guardan (se toma al arrancar el servidor). */
    @Nullable
    private static RaidData data;

    private RaidManager() {}

    private static String key(ResourceKey<Level> dim, BlockPos center) {
        return dim.location() + "@" + center.asLong();
    }

    @Nullable
    public static Raid get(ServerLevel level, BlockPos center) {
        return RAIDS.get(key(level.dimension(), center));
    }

    public static List<Raid> all() {
        return new ArrayList<>(RAIDS.values());
    }

    /** Borra todos los ataques de memoria (al cerrar el servidor; ya quedaron guardados). */
    public static void clear() {
        RAIDS.values().forEach(RaidManager::closeBar);
        RAIDS.clear();
        BattleGeneral.clear();
        com.ale.warbandcraft.invasion.Assault.clear();   // y el de las invasiones
        com.ale.warbandcraft.invasion.Hosts.clear();   // H23
        com.ale.warbandcraft.battle.BattleLoot.clear();
        com.ale.warbandcraft.battle.tactics.TeamAI.clear();
        com.ale.warbandcraft.invasion.SiegeDefense.clear();
        com.ale.warbandcraft.invasion.AiWars.clearSieges();
        com.ale.warbandcraft.battle.SiegePush.clear();
        com.ale.warbandcraft.village.GarrisonLife.clear();
        com.ale.warbandcraft.village.GarrisonReserve.clear();
        com.ale.warbandcraft.market.FactionMarket.clear();
        com.ale.warbandcraft.battle.BattleBar.clear();
        data = null;
    }

    /** Al arrancar el servidor: trae los ataques guardados. */
    public static void load(MinecraftServer server) {
        RAIDS.values().forEach(RaidManager::closeBar);
        RAIDS.clear();
        data = RaidData.get(server);
        for (Raid raid : data.loaded) RAIDS.put(key(raid.dimension, raid.center), raid);
        data.loaded.clear();
        if (!RAIDS.isEmpty()) WarbandCraft.LOGGER.info("Se retomaron {} ataques en curso", RAIDS.size());
    }

    /** Algo de un ataque cambió: hay que guardarlo. */
    public static void changed() {
        if (data != null) data.setDirty();
    }

    /** El jugador salió: al volver se le muestra de nuevo la elección de los ataques ganados. */
    public static void onLogout(UUID player) {
        for (Raid raid : RAIDS.values()) if (raid.attacker.equals(player)) raid.choiceShown = false;
    }

    // ------------------------------------------------------------------ empezar

    /** Quién es el responsable de un golpe: el jugador, o el dueño del soldado que pegó. */
    @Nullable
    public static UUID responsiblePlayer(@Nullable Entity attacker) {
        if (attacker instanceof Player p) return p.getUUID();
        if (attacker instanceof AbstractRecruitEntity r && r.getIsOwned()) return r.getOwnerUUID();
        return null;
    }

    /**
     * Un soldado de guarnición fue atacado por {@code attackerEntity} (responsable: {@code playerId}).
     * Empieza el ataque a su aldea si no había uno, y la guarnición sale a pelear.
     */
    @Nullable
    public static Raid onGarrisonAttacked(ServerLevel level, AbstractRecruitEntity soldier, LivingEntity attackerEntity, UUID playerId) {
        if (!soldier.getPersistentData().contains(VillageManager.TAG_VILLAGE)) return null;
        BlockPos center = BlockPos.of(soldier.getPersistentData().getLong(VillageManager.TAG_VILLAGE));
        VillageData.Village village = VillageData.get(level).at(center);
        if (village == null || village.isConquered()) return null;

        Raid raid = get(level, center);
        if (raid == null) raid = start(level, village, playerId, attackerEntity instanceof Player p ? p : null);
        if (raid != null && raid.state == State.FIGHTING) {
            for (AbstractRecruitEntity mate : VillageManager.garrison(level, center)) {
                LivingEntity t = mate.getTarget();
                if (t == null || !t.isAlive()) mate.setTarget(attackerEntity);
            }
        }
        return raid;
    }

    /** Empieza un ataque del jugador {@code playerId} a {@code village}. */
    @Nullable
    public static Raid start(ServerLevel level, VillageData.Village village, UUID playerId, @Nullable Player attacker) {
        if (village.isConquered()) return null;
        Raid existing = get(level, village.center());
        if (existing != null) return existing;

        Raid raid = new Raid(level.dimension(), village.center(), village.faction(), playerId, level.getGameTime());
        raid.attackerRef = attacker;
        raid.initialGarrison = Math.max(1, VillageManager.garrison(level, village.center()).size());
        // la barra la arma updateBar (H17: quién va ganando)
        RAIDS.put(key(level.dimension(), village.center()), raid);
        changed();
        boolean test = VillageData.get(level).isTest(village.center());
        if (!test) Relations.get(level.getServer()).add(playerId, village.faction(), Relations.RAID_STARTED);

        ServerPlayer player = onlinePlayer(level, raid);
        Faction faction = FactionManager.INSTANCE.get(village.faction());
        if (player != null && faction != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.raid_started",
                    Component.literal(faction.ofName()).withStyle(faction.color(), ChatFormatting.BOLD),
                    VillageManager.garrison(level, village.center()).size()).withStyle(ChatFormatting.GOLD));
            level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.6F, 1.2F);
        }
        WarbandCraft.LOGGER.info("Ataque a la aldea {} ({}) por {}", village.center().toShortString(), village.faction(), playerId);
        return raid;
    }

    // ------------------------------------------------------------------ durante el ataque

    /** Un soldado de guarnición murió: si había un ataque a su aldea, su equipo pasa a ser botín. */
    public static void onGarrisonDeath(ServerLevel level, AbstractRecruitEntity soldier) {
        if (!soldier.getPersistentData().contains(VillageManager.TAG_VILLAGE)) return;
        Raid raid = get(level, BlockPos.of(soldier.getPersistentData().getLong(VillageManager.TAG_VILLAGE)));
        if (raid == null || raid.state != State.FIGHTING) return;
        raid.killed++;
        changed();
        // Se saca todo antes de que Recruits lo tire al piso: así no se duplica.
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = soldier.getItemBySlot(slot);
            if (!s.isEmpty()) raid.fallenGear.add(s.copy());
            soldier.setItemSlot(slot, ItemStack.EMPTY);
        }
        var inv = soldier.getInventory();
        for (int i = 6; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.removeItemNoUpdate(i);
            if (!s.isEmpty()) raid.fallenGear.add(s);
        }
        inv.clearContent();
    }

    /** Revisa los ataques de este mundo: victoria o abandono. Se llama una vez por segundo. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Raid raid : all()) {
            if (raid.dimension != level.dimension()) continue;
            if (raid.state == State.WON) {
                offerChoice(level, raid);
                continue;
            }
            Player attacker = attackerEntity(level, raid);
            boolean near = attacker != null && attacker.level() == level
                    && VillageData.horizontalDistSq(attacker.blockPosition(), raid.center) <= (double) LEAVE_DISTANCE * LEAVE_DISTANCE;
            if (near) raid.lastNearTick = now;
            updateBar(level, raid, attacker, near);
            keepSurrendered(level, raid);
            BattleGeneral.tick(level, raid);
            changed();

            if (now - raid.lastNearTick > LEAVE_GRACE_TICKS) {
                abandon(level, raid);
                continue;
            }
            boolean canWin = attacker != null && attacker.level() == level
                    && VillageData.horizontalDistSq(attacker.blockPosition(), raid.center) <= (double) VICTORY_DISTANCE * VICTORY_DISTANCE
                    && level.isPositionEntityTicking(raid.center);
            if (canWin) {
                List<AbstractRecruitEntity> garrison = VillageManager.garrison(level, raid.center);
                VillageData.Village village = VillageData.get(level).at(raid.center);
                boolean captainFell = village != null && village.castle() && !garrison.isEmpty() && !CastleManager.captainAlive(garrison);
                boolean reserveLeft = com.ale.warbandcraft.village.GarrisonReserve.remaining(level, village) > 0;
                if ((garrison.isEmpty() && !reserveLeft) || captainFell) {
                    if (captainFell) {
                        ServerPlayer p = onlinePlayer(level, raid);
                        if (p != null) p.sendSystemMessage(Component.translatable("msg.warbandcraft.castle_captain_fell").withStyle(ChatFormatting.GOLD));
                    }
                    win(level, raid, captainFell ? garrison : List.of());
                }
            }
        }
    }

    /**
     * H17: la barra de arriba, al estilo Total War: tu ejército (vos + tus soldados cerca) contra la guarnición, y quién
     * va ganando. Solo la ves si estás cerca.
     */
    private static void updateBar(ServerLevel level, Raid raid, @Nullable Player attacker, boolean near) {
        if (!level.isPositionEntityTicking(raid.center)) return;
        int yours = BattleGeneral.attackerSoldiers(level, raid).size() + (near && attacker != null && attacker.isAlive() ? 1 : 0);
        int theirs = VillageManager.garrison(level, raid.center).size() + com.ale.warbandcraft.village.GarrisonReserve.remaining(level, raid.center);
        Faction f = FactionManager.INSTANCE.get(raid.factionId());
        Component them = f == null ? Component.literal("?") : Component.literal(f.name()).withStyle(f.color());
        java.util.List<ServerPlayer> viewers = attacker instanceof ServerPlayer sp && near ? java.util.List.of(sp) : java.util.List.of();
        com.ale.warbandcraft.battle.BattleBar.update(barKey(raid),
                new com.ale.warbandcraft.battle.BattleBar.Side(Component.translatable("msg.warbandcraft.bar.yours"), yours),
                new com.ale.warbandcraft.battle.BattleBar.Side(them, theirs), true, viewers);
    }

    private static String barKey(Raid raid) {
        return "raid:" + raid.dimension.location() + "@" + raid.center.asLong();
    }

    /** Los rendidos que estén cargados siguen quietos (la pose se puede perder al recargar). */
    private static void keepSurrendered(ServerLevel level, Raid raid) {
        for (UUID id : raid.surrendered) {
            if (level.getEntity(id) instanceof AbstractRecruitEntity s && s.isAlive() && Surrender.is(s)) Surrender.freeze(s);
        }
    }

    private static ServerBossEvent newBar(Raid raid) {
        Faction f = FactionManager.INSTANCE.get(raid.factionId());
        return new ServerBossEvent(Component.translatable("msg.warbandcraft.raid_bar", Component.literal(f == null ? "?" : f.ofName())),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    }

    private static void closeBar(Raid raid) {
        com.ale.warbandcraft.battle.BattleBar.close(barKey(raid));
        if (raid.bar != null) {
            raid.bar.removeAllPlayers();
            raid.bar = null;
        }
    }

    /** Saca un ataque sin resultado (lo usan las pruebas). */
    public static void discard(ServerLevel level, BlockPos center) {
        Raid raid = RAIDS.remove(key(level.dimension(), center));
        if (raid == null) return;
        closeBar(raid);
        for (UUID id : raid.surrendered) {
            if (level.getEntity(id) instanceof AbstractRecruitEntity s && Surrender.is(s)) Surrender.standUp(s);
        }
        BattleGeneral.end(level, raid);
        changed();
    }

    /**
     * Ganaste: los enemigos rendidos y los defensores que quedan en pie ({@code standing}, castillo con el capitán
     * caído) van como prisioneros a tu Campamento más cercano; tus rendidos se levantan. Después se abre la
     * pantalla Saquear/Conquistar (modal, no se puede cerrar sin elegir).
     */
    private static void win(ServerLevel level, Raid raid, List<AbstractRecruitEntity> standing) {
        raid.state = State.WON;
        closeBar(raid);
        BattleGeneral.end(level, raid);

        List<AbstractRecruitEntity> captives = new ArrayList<>(standing);
        for (UUID id : raid.surrendered) {
            if (!(level.getEntity(id) instanceof AbstractRecruitEntity s) || !s.isAlive()) continue;
            if (VillageManager.isGarrison(s)) captives.add(s);
            else Surrender.standUp(s);
        }
        raid.surrendered.clear();
        int taken = 0, freed = 0;
        for (AbstractRecruitEntity s : captives) {
            if (Prisoners.imprison(level, s, raid.attacker)) {
                taken++;
            } else {
                Prisoners.release(s);
                freed++;
            }
        }
        // Ejército de prueba: no hay nada que saquear ni conquistar, se borra y listo
        boolean test = VillageData.get(level).isTest(raid.center);
        if (test) {
            RAIDS.remove(key(raid.dimension, raid.center));
            VillageData.get(level).remove(raid.center);
        }
        changed();

        ServerPlayer player = onlinePlayer(level, raid);
        if (player == null) return;
        if (taken > 0) {
            BlockPos camp = CampRegistry.get(level).nearestCamp(raid.attacker, raid.center);
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.battle.prisoners", taken,
                    camp == null ? Component.literal("?") : CampRegistry.get(level).displayName(camp)).withStyle(ChatFormatting.GOLD));
        }
        if (freed > 0) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.battle.prisoners_freed", freed).withStyle(ChatFormatting.GRAY));
        }
        if (test) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.test.won").withStyle(ChatFormatting.GOLD));
        } else {
            offerChoice(level, raid);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** Abre la pantalla Saquear/Conquistar si el ganador está conectado y todavía no la vio desde que entró. */
    private static void offerChoice(ServerLevel level, Raid raid) {
        if (raid.choiceShown) return;
        ServerPlayer player = onlinePlayer(level, raid);
        if (player == null) return;
        raid.choiceShown = true;
        Faction faction = FactionManager.INSTANCE.get(raid.factionId());
        Component who = faction == null ? Component.literal("?") : Component.literal(faction.ofName()).withStyle(faction.color());
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenRaidChoiceMessage(who));
    }

    /**
     * Te alejaste demasiado: perdiste. Tus rendidos se pasan a la guarnición de la aldea (con su nivel y su
     * equipo); los enemigos rendidos se levantan. El equipo de los caídos igual llega a tu Campamento.
     */
    private static void abandon(ServerLevel level, Raid raid) {
        RAIDS.remove(key(raid.dimension, raid.center));
        closeBar(raid);
        BattleGeneral.end(level, raid);
        int captured = 0;
        for (UUID id : raid.surrendered) {
            if (!(level.getEntity(id) instanceof AbstractRecruitEntity s) || !s.isAlive()) continue;
            if (capture(level, s)) captured++;
        }
        changed();
        if (!raid.fallenGear.isEmpty() && !VillageData.get(level).isTest(raid.center)) CampRegistry.get(level).deliver(level, raid.attacker, raid.fallenGear, raid.center);
        ServerPlayer player = onlinePlayer(level, raid);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.raid_abandoned").withStyle(ChatFormatting.GRAY));
            Faction faction = FactionManager.INSTANCE.get(raid.factionId());
            if (captured > 0 && faction != null) {
                player.sendSystemMessage(Component.translatable("msg.warbandcraft.battle.captured", captured,
                        Component.literal(faction.ofName()).withStyle(faction.color())).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** Abandonar a mano (lo usan las pruebas: es lo mismo que alejarse más de un minuto). */
    public static void abandon(ServerLevel level, BlockPos center) {
        Raid raid = get(level, center);
        if (raid != null) abandon(level, raid);
    }

    /**
     * Un rendido cuyo ataque ya terminó (o se perdió): el de la guarnición se levanta; uno tuyo pasa a la guarnición
     * de la aldea donde se rindió, si sigue siendo de una facción (si no, se levanta). True si se lo llevaron.
     */
    public static boolean capture(ServerLevel level, AbstractRecruitEntity s) {
        BlockPos center = Surrender.raidCenter(s);
        if (center == null || VillageManager.isGarrison(s)) {
            if (Surrender.is(s)) Surrender.standUp(s);
            return false;
        }
        VillageData.Village village = VillageData.get(level).at(center);
        Faction faction = village == null ? null : FactionManager.INSTANCE.get(village.faction());
        if (village == null || faction == null || village.isConquered()) {
            Surrender.standUp(s);
            return false;
        }
        VillageManager.joinGarrison(level, s, village, faction);
        return true;
    }

    // ------------------------------------------------------------------ resultado

    /** El ataque ganado de este jugador (el más cercano a {@code near}), o null. */
    @Nullable
    public static Raid wonRaidOf(ServerLevel level, UUID player, BlockPos near) {
        Raid best = null;
        double bestD = Double.MAX_VALUE;
        for (Raid r : RAIDS.values()) {
            if (r.state != State.WON || !r.attacker.equals(player) || r.dimension != level.dimension()) continue;
            double d = VillageData.horizontalDistSq(r.center, near);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    /** Saquear: botín grande al Campamento, la aldea queda vacía 3 días, todas las facciones te quieren menos. */
    public static Outcome sack(ServerLevel level, UUID player, BlockPos near) {
        Raid raid = wonRaidOf(level, player, near);
        if (raid == null) return Outcome.fail(Component.translatable("msg.warbandcraft.no_won_raid"));
        RAIDS.remove(key(raid.dimension, raid.center));
        changed();

        RandomSource r = level.getRandom();
        List<ItemStack> loot = new ArrayList<>();
        int emeralds = 3 + raid.killed * 2 + r.nextInt(4);
        int iron = 2 + raid.killed + r.nextInt(3);
        int bread = 8 + r.nextInt(9);
        loot.add(new ItemStack(Items.EMERALD, emeralds));
        loot.add(new ItemStack(Items.IRON_INGOT, iron));
        loot.add(new ItemStack(Items.BREAD, bread));
        loot.addAll(raid.fallenGear);

        VillageData data = VillageData.get(level);
        VillageData.Village village = data.at(raid.center);
        List<ItemStack> stolen = village == null ? List.of() : Invasions.stealStock(level, village);   // H15a: su almacén
        if (village != null) data.add(village.withLootedUntil(level.getGameTime() + LOOTED_TICKS));
        Relations.get(level.getServer()).affectAll(player, raid.factionId(), Relations.SACKED_TARGET, Relations.SACKED_OTHERS);

        Component where = deliverLoot(level, player, loot, raid.center);
        MutableComponent msg = Component.translatable("msg.warbandcraft.sacked", emeralds, iron, bread, raid.fallenGear.size(), where)
                .withStyle(ChatFormatting.GOLD);
        appendStolen(level, player, stolen, raid.center, msg);
        return new Outcome(true, msg, loot);
    }

    /** Conquistar: la aldea pasa a ser tuya y paga impuestos. El equipo de los caídos va al Campamento. */
    public static Outcome conquer(ServerLevel level, UUID player, BlockPos near) {
        Raid raid = wonRaidOf(level, player, near);
        if (raid == null) return Outcome.fail(Component.translatable("msg.warbandcraft.no_won_raid"));
        RAIDS.remove(key(raid.dimension, raid.center));
        changed();

        VillageData data = VillageData.get(level);
        VillageData.Village village = data.at(raid.center);
        List<ItemStack> stolen = village == null ? List.of() : Invasions.stealStock(level, village);   // H15a: antes de vaciarse
        if (village != null) data.add(village.withOwner(player).withLootedUntil(0));
        Relations.get(level.getServer()).affectAll(player, raid.factionId(), Relations.CONQUERED_TARGET, Relations.CONQUERED_OTHERS);

        if (village != null) {
            ServerPlayer conqueror = level.getServer().getPlayerList().getPlayer(player);
            if (conqueror != null) {
                try {
                    RecruitsClaimBridge.claimVillage(level, village, conqueror);
                } catch (Exception e) {
                    WarbandCraft.LOGGER.error("No se pudo reclamar el territorio de {} en Recruits", village.center().toShortString(), e);
                }
            }
            com.ale.warbandcraft.village.FactionClaims.get(level).release(level, village.center());   // ya no es de la facción
        }

        Component where = raid.fallenGear.isEmpty() ? Component.empty() : deliverLoot(level, player, raid.fallenGear, raid.center);
        MutableComponent msg = Component.translatable("msg.warbandcraft.conquered", raid.fallenGear.size(), where).withStyle(ChatFormatting.AQUA);
        appendStolen(level, player, stolen, raid.center, msg);
        return new Outcome(true, msg, raid.fallenGear);
    }

    /** H15a: lo que tenía guardado la aldea va a tu Campamento; se suma una línea al mensaje. */
    private static void appendStolen(ServerLevel level, UUID player, List<ItemStack> stolen, BlockPos near, MutableComponent msg) {
        if (stolen.isEmpty()) return;
        int emeralds = 0, food = 0, armament = 0;
        for (ItemStack s : stolen) {
            if (s.is(Items.EMERALD)) emeralds += s.getCount();
            else if (s.is(Items.BREAD)) food += s.getCount();
            else armament += s.getCount();
        }
        Component where = deliverLoot(level, player, stolen, near);
        msg.append("\n").append(Component.translatable("msg.warbandcraft.stolen",
                new VillageStock.Stock(emeralds, food, armament).describe(), where).withStyle(ChatFormatting.YELLOW));
    }

    /** Lleva el botín al Campamento. Si el jugador no tiene ninguno, se lo da en mano. Devuelve "dónde quedó". */
    private static Component deliverLoot(ServerLevel level, UUID player, List<ItemStack> loot, BlockPos near) {
        CampRegistry camps = CampRegistry.get(level);
        if (camps.hasCamp(player)) {
            boolean now = camps.deliver(level, player, loot, near);
            return Component.translatable(now ? "msg.warbandcraft.loot_in_camp" : "msg.warbandcraft.loot_on_the_way");
        }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            for (ItemStack s : loot) {
                ItemStack copy = s.copy();
                if (!p.getInventory().add(copy)) p.drop(copy, false);
            }
        }
        return Component.translatable("msg.warbandcraft.loot_in_hand");
    }

    // ------------------------------------------------------------------ utilidades

    @Nullable
    public static Player attackerEntity(ServerLevel level, Raid raid) {
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(raid.attacker);
        if (online != null) return online;
        return raid.attackerRef != null && !raid.attackerRef.isRemoved() ? raid.attackerRef : null;
    }

    @Nullable
    private static ServerPlayer onlinePlayer(ServerLevel level, Raid raid) {
        return level.getServer().getPlayerList().getPlayer(raid.attacker);
    }
}
