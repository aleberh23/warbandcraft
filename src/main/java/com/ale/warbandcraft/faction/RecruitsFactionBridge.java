package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Registra las facciones NPC del mod (Clanes del Norte, etc.) como facciones "fantasma" de Recruits:
 * sin líder, con cupo cero (nadie se puede unir), solo para que aparezcan listadas en el menú de
 * Facciones de Recruits con su nombre, color y estandarte.
 *
 * La guerra y las relaciones siguen manejadas enteramente por nuestro {@link Relations}: esto no toca
 * la diplomacia de Recruits, así que su sistema de asedios a "claims" no se activa por esto.
 */
public final class RecruitsFactionBridge {
    private static final UUID GHOST_LEADER = new UUID(0L, 0L);

    private RecruitsFactionBridge() {}

    /** Da de alta en Recruits cualquier facción NPC que todavía no esté registrada. Se puede llamar seguido. */
    public static void sync(ServerLevel overworld) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        if (manager == null) return;
        for (Faction faction : FactionManager.INSTANCE.ordered()) {
            if (manager.getFactionByStringID(faction.teamName()) != null) continue;
            register(overworld, manager, faction);
        }
    }

    private static void register(ServerLevel overworld, RecruitsFactionManager manager, Faction faction) {
        VillageManager.team(overworld, faction); // asegura que exista el equipo del marcador (scoreboard)
        manager.addTeam(faction.teamName(), faction.name(), GHOST_LEADER, "",
                faction.banner().serializeNBT(), (byte) 0, faction.color());
        RecruitsFaction registered = manager.getFactionByStringID(faction.teamName());
        if (registered != null) registered.setMaxPlayers(0); // fantasma: nadie se puede unir
        manager.save(overworld);
        WarbandCraft.LOGGER.info("Facción {} registrada en el menú de facciones de Recruits", faction.id());
    }
}
