package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.network.BattleOrderMessage;
import com.ale.warbandcraft.network.ChoiceMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageHud;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * H23: las pantallas de elegir de las batallas y la tecla B.
 * <ul>
 *   <li>Batalla entre facciones cerca: Ayudar a uno · Ayudar al otro · No involucrarse ({@link AiWars#join}).</li>
 *   <li>Parado en una aldea/castillo de facción: Asediar ({@link #siege}): empieza el ataque y tus soldados cerca entran
 *       en modo Batalla, sin tener que pegarle a nadie.</li>
 * </ul>
 */
public final class BattleChoices {
    public static final String KIND_JOIN = "join", KIND_SIEGE = "siege";
    /** Asediar: tus soldados a esta distancia entran en modo Batalla. */
    public static int SIEGE_SOLDIER_RADIUS = BattleOrderMessage.RADIUS;
    /** Un ataque tuyo a esta distancia: la tecla suma a tus soldados de cerca en vez de ofrecer otro asedio. */
    public static int OWN_RAID_RANGE = 128;

    private BattleChoices() {}

    public static void send(ServerPlayer player, String kind, String id, Component title, List<Component> lines, List<Component> options,
                            boolean asked) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer) return;   // las pruebas: sin conexión
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ChoiceMessage(kind, id, title, lines, options, asked));
    }

    /** Apretó B: qué se puede hacer acá, en este orden: meterse en una batalla, sumar soldados a tu ataque, asediar. */
    public static void onKey(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (AiWars.offerNear(level, player)) return;
        for (RaidManager.Raid raid : RaidManager.all()) {
            if (raid.attacker.equals(player.getUUID()) && raid.state == RaidManager.State.FIGHTING && raid.dimension == level.dimension()
                    && VillageData.horizontalDistSq(raid.center, player.blockPosition()) <= (double) OWN_RAID_RANGE * OWN_RAID_RANGE) {
                player.displayClientMessage(BattleOrderMessage.toggle(player, List.of(), true), true);
                return;
            }
        }
        VillageData.Village v = VillageHud.at(level, player.blockPosition());
        if (v != null && !v.isConquered()) {
            offerSiege(player, v);
            return;
        }
        player.displayClientMessage(Component.translatable("msg.warbandcraft.choice.nothing").withStyle(ChatFormatting.GRAY), true);
    }

    public static void answer(ServerPlayer player, String kind, String id, int option) {
        switch (kind) {
            case KIND_JOIN -> AiWars.answer(player, id, option);
            case KIND_SIEGE -> {
                if (option != 0) return;
                try {
                    Component msg = siege(player, BlockPos.of(Long.parseLong(id)));
                    player.sendSystemMessage(msg);
                } catch (NumberFormatException ignored) {
                }
            }
            case com.ale.warbandcraft.dungeon.Rumors.KIND_MENU, com.ale.warbandcraft.dungeon.Rumors.KIND_CONTRACT ->
                    com.ale.warbandcraft.dungeon.Rumors.answer(player, kind, id, option);   // H25: el Tabernero
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ asediar

    private static void offerSiege(ServerPlayer player, VillageData.Village v) {
        ServerLevel level = player.serverLevel();
        Faction f = FactionManager.INSTANCE.get(v.faction());
        int garrison = VillageManager.garrison(level, v.center()).size();
        if (garrison == 0) {
            player.displayClientMessage(Component.translatable("msg.warbandcraft.siege.no_garrison").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.warbandcraft.siege.garrison", garrison));
        lines.add(Component.translatable("gui.warbandcraft.siege.yours", soldiersNear(player).size(), SIEGE_SOLDIER_RADIUS));
        Relations rel = Relations.get(level.getServer());
        if (!rel.atWar(player.getUUID(), v.faction())) {
            lines.add(Component.translatable("gui.warbandcraft.siege.opinion", f == null ? "?" : f.name(), Relations.RAID_STARTED)
                    .withStyle(ChatFormatting.RED));
        }
        send(player, KIND_SIEGE, Long.toString(v.center().asLong()),
                Component.translatable(v.castle() ? "gui.warbandcraft.siege.title_castle" : "gui.warbandcraft.siege.title", name),
                lines, List.of(Component.translatable("gui.warbandcraft.siege.go"), Component.translatable("gui.warbandcraft.siege.cancel")), true);
    }

    /** Tus soldados cerca que pueden entrar en modo Batalla. */
    private static List<AbstractRecruitEntity> soldiersNear(ServerPlayer player) {
        double r = SIEGE_SOLDIER_RADIUS;
        return player.serverLevel().getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(player.blockPosition()).inflate(r),
                s -> s.isAlive() && com.ale.warbandcraft.faction.Allies.same(s.getOwnerUUID(), player.getUUID()) && s.getListen() && Soldiers.isSoldier(s));
    }

    /**
     * ¡Asedio! Empieza el ataque a la aldea/castillo (como si le hubieras pegado a la guarnición) y tus soldados cerca
     * entran en modo Batalla. Hay que estar parado ahí.
     */
    public static Component siege(ServerPlayer player, BlockPos center) {
        ServerLevel level = player.serverLevel();
        VillageData.Village v = VillageData.get(level).at(center);
        VillageData.Village here = VillageHud.at(level, player.blockPosition());
        if (v == null || here == null || !here.center().equals(center)) {
            return Component.translatable("msg.warbandcraft.siege.too_far").withStyle(ChatFormatting.RED);
        }
        if (v.isConquered()) return Component.translatable("msg.warbandcraft.siege.not_faction").withStyle(ChatFormatting.RED);
        if (VillageManager.garrison(level, center).isEmpty()) {
            return Component.translatable("msg.warbandcraft.siege.no_garrison").withStyle(ChatFormatting.RED);
        }
        RaidManager.Raid raid = RaidManager.start(level, v, player.getUUID(), player);
        if (raid == null) return Component.translatable("msg.warbandcraft.siege.not_faction").withStyle(ChatFormatting.RED);
        for (AbstractRecruitEntity g : VillageManager.garrison(level, center)) {   // la guarnición sale a pelear
            if (g.getTarget() == null || !g.getTarget().isAlive()) g.setTarget(player);
        }
        List<AbstractRecruitEntity> mine = soldiersNear(player);
        for (AbstractRecruitEntity s : mine) BattleGeneral.setBattle(s, true);
        return Component.translatable("msg.warbandcraft.siege.started", mine.size()).withStyle(ChatFormatting.GOLD);
    }
}
