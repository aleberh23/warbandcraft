package com.ale.warbandcraft.raid;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.battle.BattleChoices;
import com.ale.warbandcraft.battle.BattleGeneral;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.Allies;
import com.ale.warbandcraft.faction.RecruitsClaimBridge;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.Territory;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsDiplomacyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Asedio entre jugadores (pedido de Ale, 4/11: "el asedio ganado es lo que te da el claim"). Los asedios de Recruits
 * están apagados ({@code NoClaimSiegeMixin}); una aldea/castillo de otro jugador se gana con el asedio de Warband: el
 * mismo {@link RaidManager.Raid} y el mismo general ({@link BattleGeneral}: carriles, escaleras, zapadores), pero los que
 * defienden son los soldados del dueño y de su facción que están en la aldea (el cuartel sale solo).
 * <ul>
 *   <li>Empieza con la tecla B (o {@code /warband asediar}) parado en la aldea, en guerra con la facción del dueño en la
 *       Diplomacia de Recruits y con alguien de esa facción conectado.</li>
 *   <li>Se gana sin defensores ni jugadores de ese lado a {@link #DEFEND_RADIUS} del centro (y nada en el cuartel): la
 *       aldea, su Campamento con lo que tiene y su claim de Recruits pasan al ganador; los rendidos van presos; el barracón
 *       del que la pierde se muda a otro Campamento suyo (si no hay lugar, se pierde).</li>
 *   <li>Se pierde como cualquier ataque: alejándose más de {@link RaidManager#LEAVE_DISTANCE} un minuto.</li>
 * </ul>
 */
public final class PvpSiege {
    public static final String KIND = "pvp_siege";
    /** Defensores y jugadores del dueño a esta distancia del centro cuentan para la defensa. */
    public static int DEFEND_RADIUS = 64;

    private PvpSiege() {}

    // ------------------------------------------------------------------ empezar

    /** Por qué no se puede asediar esta aldea, o null si se puede. */
    @Nullable
    public static Component problem(ServerLevel level, ServerPlayer player, VillageData.Village v) {
        if (!v.isConquered() || Allies.same(player.getUUID(), v.owner())) return Component.translatable("msg.warbandcraft.pvp_siege.not_enemy");
        if (VillageData.get(level).isTest(v.center())) return Component.translatable("msg.warbandcraft.pvp_siege.not_enemy");
        RaidManager.Raid raid = RaidManager.get(level, v.center());
        if (raid != null) return Component.translatable("msg.warbandcraft.pvp_siege.already");
        String fa = Territory.factionOf(level, player.getUUID()), fb = Territory.factionOf(level, v.owner());
        if (com.ale.warbandcraft.faction.PlayerPeace.get(level.getServer()).truce(level, fa, fb)) {
            return Component.translatable("msg.warbandcraft.pvp_siege.truce", Allies.playerName(v.owner()),
                    com.ale.warbandcraft.faction.PlayerPeace.get(level.getServer()).truceUntil(fa, fb) - level.getDayTime() / 24000L);
        }
        if (!atWar(level, player.getUUID(), v.owner())) {
            return Component.translatable("msg.warbandcraft.pvp_siege.no_war", Allies.playerName(v.owner()));
        }
        if (!Allies.anyOnline(level.getServer(), v.owner())) {
            return Component.translatable("msg.warbandcraft.pvp_siege.offline", Allies.playerName(v.owner()));
        }
        return null;
    }

    /** ¿Las facciones de Recruits de los dos jugadores están en guerra (ENEMY en la Diplomacia de Recruits)? */
    public static boolean atWar(ServerLevel level, UUID a, @Nullable UUID b) {
        String fa = Territory.factionOf(level, a), fb = Territory.factionOf(level, b);
        RecruitsDiplomacyManager d = FactionEvents.recruitsDiplomacyManager;
        if (fa == null || fb == null || fa.equals(fb) || d == null) return false;
        return d.getRelation(fa, fb) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY
                || d.getRelation(fb, fa) == RecruitsDiplomacyManager.DiplomacyStatus.ENEMY;
    }

    /** Tecla B en una aldea de otro jugador: la pantalla de confirmar (o por qué no se puede). */
    public static void offer(ServerPlayer player, VillageData.Village v) {
        ServerLevel level = player.serverLevel();
        Component why = problem(level, player, v);
        if (why != null) {
            player.displayClientMessage(why.copy().withStyle(ChatFormatting.RED), true);
            return;
        }
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.warbandcraft.pvp_siege.owner", Allies.playerName(v.owner())));
        lines.add(Component.translatable("gui.warbandcraft.siege.yours", BattleChoices.soldiersNear(player).size(), BattleChoices.SIEGE_SOLDIER_RADIUS));
        lines.add(Component.translatable("gui.warbandcraft.pvp_siege.win", DEFEND_RADIUS).withStyle(ChatFormatting.GRAY));
        BattleChoices.send(player, KIND, Long.toString(v.center().asLong()),
                Component.translatable(v.castle() ? "gui.warbandcraft.siege.title_castle" : "gui.warbandcraft.siege.title", name),
                lines, List.of(Component.translatable("gui.warbandcraft.siege.go"), Component.translatable("gui.warbandcraft.siege.cancel")), true);
    }

    /** ¡Asedio! Hay que estar parado en la aldea. Tus soldados cerca entran en modo Batalla. */
    public static Component start(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        VillageData.Village here = com.ale.warbandcraft.village.VillageHud.at(level, player.blockPosition());
        if (v == null || here == null || !here.center().equals(center)) {
            return Component.translatable("msg.warbandcraft.siege.too_far").withStyle(ChatFormatting.RED);
        }
        Component why = problem(level, player, v);
        if (why != null) return why.copy().withStyle(ChatFormatting.RED);
        RaidManager.Raid raid = RaidManager.startPvp(level, v, player.getUUID(), player);
        List<AbstractRecruitEntity> mine = BattleChoices.soldiersNear(player);
        for (AbstractRecruitEntity s : mine) BattleGeneral.setBattle(s, true);
        Quarters.deploy(level, v, true);
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
        com.ale.warbandcraft.battle.BattleLog.begin(level, RaidManager.logKey(center), "Asedio de " + player.getGameProfile().getName()
                + " a la " + (v.castle() ? "castillo" : "aldea") + " de " + Allies.playerName(v.owner()), center, raid.initialGarrison + " defensores");
        for (UUID id : Allies.members(v.owner())) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_siege.alarm", player.getDisplayName(), name,
                    center.getX(), center.getZ()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.8F, 1.0F);
        }
        WarbandCraft.LOGGER.info("Asedio entre jugadores: {} a {} de {}", player.getGameProfile().getName(), center.toShortString(), v.owner());
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.6F, 1.2F);
        return Component.translatable("msg.warbandcraft.pvp_siege.started", name, mine.size()).withStyle(ChatFormatting.GOLD);
    }

    // ------------------------------------------------------------------ durante

    /** Los soldados del dueño (y de su facción) que defienden: en la aldea, de pie. */
    public static List<AbstractRecruitEntity> defenders(ServerLevel level, RaidManager.Raid raid) {
        double r = DEFEND_RADIUS;
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(raid.center).inflate(r, 64, r),
                s -> s.isAlive() && s.getIsOwned() && Allies.same(s.getOwnerUUID(), raid.defender) && !Surrender.is(s)
                        && Soldiers.isSoldier(s) && VillageData.horizontalDistSq(s.blockPosition(), raid.center) <= r * r);
    }

    /** Los jugadores del lado del dueño que están en la aldea (vivos, no espectadores). */
    public static List<Player> defendingPlayers(ServerLevel level, RaidManager.Raid raid) {
        double r = DEFEND_RADIUS;
        List<Player> out = new ArrayList<>();
        for (Player p : level.players()) {
            if (!p.isAlive() || p.isSpectator() || !Allies.same(p.getUUID(), raid.defender)) continue;
            if (VillageData.horizontalDistSq(p.blockPosition(), raid.center) <= r * r) out.add(p);
        }
        return out;
    }

    /** La aldea sigue siendo del mismo lado (si la tomó otro en el medio, el asedio no sigue). */
    public static boolean stillValid(ServerLevel level, RaidManager.Raid raid) {
        VillageData.Village v = VillageData.get(level).at(raid.center);
        return v != null && v.isConquered() && Allies.same(v.owner(), raid.defender);
    }

    /** Última vez que salió uno del barracón, por aldea (en memoria). */
    private static final java.util.Map<Long, Long> LAST_REINFORCE = new java.util.HashMap<>();

    /**
     * Cada segundo: el cuartel guardado sale a defender (aunque no haya nadie de la facción cerca) y, como contra la IA
     * ({@code SiegeDefense}), sale uno del barracón de la aldea cada {@code SiegeDefense.REINFORCE_EVERY} (el de más nivel
     * primero) mientras los defensores sean menos que {@code BattleSize.MAX_PER_SIDE}.
     */
    public static void tick(ServerLevel level, RaidManager.Raid raid) {
        VillageData.Village v = VillageData.get(level).at(raid.center);
        if (v == null || !level.isPositionEntityTicking(raid.center)) return;
        Quarters.deploy(level, v, true);
        BlockPos camp = VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        if (b == null || b.soldiers.isEmpty() || !level.isPositionEntityTicking(camp)) return;
        long now = level.getGameTime();
        Long last = LAST_REINFORCE.get(raid.center.asLong());
        if (last != null && now - last < com.ale.warbandcraft.invasion.SiegeDefense.REINFORCE_EVERY) return;
        List<AbstractRecruitEntity> defenders = defenders(level, raid);
        if (defenders.size() >= com.ale.warbandcraft.battle.BattleSize.MAX_PER_SIDE) return;
        BarracksData.Soldier sheet = null;
        for (BarracksData.Soldier x : b.soldiers) if (sheet == null || x.xpLevel > sheet.xpLevel) sheet = x;
        if (sheet == null) return;
        b.soldiers.remove(sheet);
        BarracksData.get(level).changed();
        LAST_REINFORCE.put(raid.center.asLong(), now);
        AbstractRecruitEntity s = Barracks.create(level, sheet, Barracks.standable(level, camp, null, camp), camp, 3, b.hungryDays);
        if (s == null) return;
        s.getPersistentData().putLong(Quarters.TAG_QUARTERS, v.center().asLong());
        s.getPersistentData().putBoolean(com.ale.warbandcraft.invasion.SiegeDefense.TAG_REINFORCEMENT, true);
        com.ale.warbandcraft.battle.BattleLog.lineAt(raid.center, "refuerzo del barracón: " + s.getName().getString()
                + " (nv " + s.getXpLevel() + "; " + defenders.size() + " defendiendo)");
        for (UUID id : Allies.members(raid.defender)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) p.displayClientMessage(Component.translatable("msg.warbandcraft.defense.reinforcement", s.getName()).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** Terminó el asedio: los que salieron del barracón vuelven caminando a él (como contra la IA). */
    private static void sendBack(ServerLevel level, RaidManager.Raid raid) {
        LAST_REINFORCE.remove(raid.center.asLong());
        VillageData.Village v = VillageData.get(level).at(raid.center);
        BlockPos camp = v == null ? null : VillageCamps.campOf(level, v);
        if (camp == null || !level.isPositionEntityTicking(raid.center)) return;
        double r = DEFEND_RADIUS + 32;
        for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(raid.center).inflate(r, 64, r),
                e -> e.isAlive() && e.getPersistentData().getBoolean(com.ale.warbandcraft.invasion.SiegeDefense.TAG_REINFORCEMENT)
                        && e.getPersistentData().getLong(Quarters.TAG_QUARTERS) == raid.center.asLong())) {
            s.getPersistentData().remove(com.ale.warbandcraft.invasion.SiegeDefense.TAG_REINFORCEMENT);
            s.getPersistentData().remove(Quarters.TAG_QUARTERS);
            s.setTarget(null);
            if (!Barracks.sendHome(level, s, null) && Barracks.store(level, s, camp, true) != Barracks.StoreResult.OK) s.setFollowState(0);
        }
    }

    /** ¿No queda nadie defendiendo? (ni soldados ni jugadores de ese lado en la aldea, ni cuartel guardado). */
    public static boolean defeated(ServerLevel level, RaidManager.Raid raid) {
        if (!defenders(level, raid).isEmpty() || !defendingPlayers(level, raid).isEmpty()) return false;
        VillageData.Village v = VillageData.get(level).at(raid.center);
        BlockPos camp = v == null ? null : VillageCamps.campOf(level, v);
        BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
        return b == null || (b.quarters.isEmpty() && b.soldiers.isEmpty());   // quedan en el barracón: todavía van a salir
    }

    // ------------------------------------------------------------------ resultado

    /**
     * Ganó el atacante: la aldea, su Campamento (con lo que hay adentro) y su claim de Recruits pasan a él. El barracón
     * y el cuartel guardado del que la pierde se mudan a su Campamento más cercano con lugar (si no hay, se pierden).
     */
    public static void conquer(ServerLevel level, RaidManager.Raid raid, int prisoners) {
        VillageData data = VillageData.get(level);
        VillageData.Village v = data.at(raid.center);
        LAST_REINFORCE.remove(raid.center.asLong());
        if (v == null || !v.isConquered()) return;
        UUID loser = v.owner(), winner = raid.attacker;
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));

        // El barracón del que la pierde: a otro Campamento suyo
        int moved = 0, lost = 0;
        BlockPos camp = data.storehouse(v.center());
        if (camp != null) {
            BarracksData bd = BarracksData.get(level);
            BarracksData.Barracks b = bd.remove(camp);
            if (b != null) {
                List<BarracksData.Soldier> sheets = new ArrayList<>(b.soldiers);
                sheets.addAll(b.quarters);
                List<BlockPos> others = new ArrayList<>(CampRegistry.get(level).campsOf(loser));
                others.remove(camp);
                others.removeIf(c -> VillageCamps.villageOf(level, c) != null && !Allies.same(VillageCamps.villageOf(level, c).owner(), loser));
                others.sort(Comparator.comparingDouble(c -> c.distSqr(v.center())));
                for (BarracksData.Soldier sheet : sheets) {
                    BlockPos to = null;
                    for (BlockPos c : others) {
                        if (bd.count(c) < Barracks.capacity(level, c)) {
                            to = c;
                            break;
                        }
                    }
                    if (to == null) {
                        lost++;
                        continue;
                    }
                    bd.getOrCreate(to, loser).soldiers.add(sheet);
                    moved++;
                }
                bd.changed();
            }
            CampRegistry.get(level).unregister(loser, camp);
            if (level.isLoaded(camp) && level.getBlockEntity(camp) instanceof CampBlockEntity c) c.setOwner(winner);
            else CampRegistry.get(level).register(winner, camp);
        }

        // Los trabajadores de la aldea (pedido de Ale: "pasan a ser del ganador"); los que no están cargados, al cargarse
        List<com.talhanation.workers.entities.AbstractWorkerEntity> workers = com.ale.warbandcraft.village.VillageManagement.workers(level, v.center(), loser);

        // La aldea es tuya
        VillageData.Village won = v.withOwner(winner).withLootedUntil(0);
        data.add(won);
        int handed = 0;
        for (com.talhanation.workers.entities.AbstractWorkerEntity w : workers) {
            Barracks.handTo(level, w, winner);
            handed++;
        }
        handed += adoptWorkers(level, won);

        // El claim de Recruits: el que tenía la aldea pasa a tu facción entero
        Territory.handOver(level, v.center());
        ServerPlayer conqueror = level.getServer().getPlayerList().getPlayer(winner);
        if (conqueror == null && RaidManager.attackerEntity(level, raid) instanceof ServerPlayer sp) conqueror = sp;   // pruebas
        if (conqueror != null) {
            try {
                RecruitsClaimBridge.claimVillage(level, won, conqueror);
            } catch (RuntimeException e) {
                WarbandCraft.LOGGER.error("No se pudo pasar el claim de {} al ganador", v.center().toShortString(), e);
            }
        }
        // Si no se pudo pasar (sin el ganador conectado), el claim del que la perdió se saca: si no, nadie la puede usar
        com.talhanation.recruits.world.RecruitsClaimManager m = com.talhanation.recruits.ClaimEvents.recruitsClaimManager;
        if (m != null) {
            com.talhanation.recruits.world.RecruitsClaim left = m.getClaim(new net.minecraft.world.level.ChunkPos(v.center()));
            String wf = Territory.factionOf(level, winner);
            if (left != null && !left.isRemoved && !left.isAdmin && (wf == null || !wf.equals(left.getOwnerFactionStringID()))) {
                try {
                    m.removeClaim(level, left.getUUID());
                    m.broadcastClaimsToAll(level);
                } catch (RuntimeException e) {
                    WarbandCraft.LOGGER.warn("No se pudo sacar el claim viejo de {}", v.center().toShortString(), e);
                }
            }
        }
        try {
            Territory.ensure(level, won);
        } catch (RuntimeException e) {
            WarbandCraft.LOGGER.warn("No se pudo armar el territorio de {}", v.center().toShortString(), e);
        }

        com.ale.warbandcraft.battle.BattleLog.end(level, RaidManager.logKey(v.center()), "ganó el atacante: la aldea pasa a "
                + Allies.playerName(winner) + " (" + prisoners + " presos, barracón: " + moved + " mudados, " + lost + " perdidos, "
                + handed + " trabajadores)");
        WarbandCraft.LOGGER.info("Asedio entre jugadores: {} conquistó {} de {} ({} presos, {} mudados, {} perdidos)",
                Allies.playerName(winner), v.center().toShortString(), Allies.playerName(loser), prisoners, moved, lost);
        ServerPlayer w = level.getServer().getPlayerList().getPlayer(winner);
        if (w != null) {
            w.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_siege.won", name, Allies.playerName(loser))
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            level.playSound(null, w.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
        }
        for (UUID id : Allies.members(loser)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_siege.lost", name, Allies.playerName(winner), moved, lost)
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        }
    }

    /**
     * Los trabajadores de una aldea (los anotados en {@code WorkerOutput} con esa aldea) y las zonas de Workers adentro de su
     * territorio que son de alguien de otro bando pasan al dueño de la aldea. Lo usa la conquista y, cada 5 s, toda aldea tuya
     * cargada ({@code VillageStorehouse.tick}): así pasan también los que no estaban cargados al ganarla.
     */
    public static int adoptWorkers(ServerLevel level, VillageData.Village v) {
        UUID owner = v.owner();
        if (owner == null) return 0;
        int n = 0;
        for (com.ale.warbandcraft.village.WorkerOutput.Rec r : com.ale.warbandcraft.village.WorkerOutput.get(level).of(v.center())) {
            if (level.getEntity(r.worker) instanceof com.talhanation.workers.entities.AbstractWorkerEntity w && w.isAlive()
                    && w.getOwnerUUID() != null && !Allies.same(w.getOwnerUUID(), owner)
                    && !w.getPersistentData().contains(com.ale.warbandcraft.camp.CampWorkers.TAG)) {
                Barracks.handTo(level, w, owner);
                n++;
            }
        }
        Territory t = Territory.get(level);
        if (!t.has(v.center())) return n;
        double r = com.ale.warbandcraft.village.VillageManagement.RADIUS + 32;
        String name = Allies.playerName(owner);
        for (com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity area : level.getEntitiesOfClass(
                com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity.class, new AABB(v.center()).inflate(r, 96, r))) {
            UUID p = area.getPlayerUUID();
            if (p == null || Allies.same(p, owner) || !t.contains(v.center(), area.blockPosition())) continue;
            area.setPlayerUUID(owner);
            area.setPlayerName(name);
            com.ale.warbandcraft.camp.WorkerZone.share(area);
            n++;
        }
        if (n > 0) WarbandCraft.LOGGER.info("Aldea {}: {} trabajadores/zonas pasan a {}", v.center().toShortString(), n, name);
        return n;
    }

    /** El atacante se fue: se avisa a los defensores. */
    public static void repelled(ServerLevel level, RaidManager.Raid raid) {
        sendBack(level, raid);
        VillageData.Village v = VillageData.get(level).at(raid.center);
        if (v == null || raid.defender == null) return;
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
        for (UUID id : Allies.members(raid.defender)) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(Component.translatable("msg.warbandcraft.pvp_siege.repelled", name, Allies.playerName(raid.attacker))
                    .withStyle(ChatFormatting.GREEN));
        }
    }
}
