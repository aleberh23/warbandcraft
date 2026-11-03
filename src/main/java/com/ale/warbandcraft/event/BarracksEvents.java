package com.ale.warbandcraft.event;

import com.ale.warbandcraft.camp.Forge;
import com.ale.warbandcraft.camp.Barracks;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Listeners de H8 (barracones): cada segundo, los que iban al barracón y llegaron se guardan; cada 10 s,
 * comida de los barracones, guardado automático de los que quedaron lejos y vuelta de los guardias.
 */
public class BarracksEvents {
    private static int ARRIVAL_INTERVAL = 20;
    private static int CHECK_INTERVAL = 20 * 10;
    private static int QUARTERS_INTERVAL = 40;

    /** Diagnóstico (pedido de Ale: los del cuartel desaparecen): cada vez que uno del cuartel deja el mundo, al log. */
    @SubscribeEvent
    public void onLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof com.talhanation.recruits.entities.AbstractRecruitEntity s)) return;
        if (!s.getPersistentData().contains(com.ale.warbandcraft.village.Quarters.TAG_QUARTERS)) return;
        net.minecraft.world.entity.Entity.RemovalReason why = s.getRemovalReason();
        if (why == net.minecraft.world.entity.Entity.RemovalReason.KILLED && s.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.ale.warbandcraft.invasion.Invasions.noteFallen(sl, net.minecraft.core.BlockPos.of(s.getPersistentData().getLong(com.ale.warbandcraft.village.Quarters.TAG_QUARTERS)));
        }
        if (why == net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK) return;
        com.ale.warbandcraft.WarbandCraft.LOGGER.info("Cuartel: {} deja el mundo en {} ({}), estado {}, vida {}, marcas {}",
                s.getName().getString(), s.blockPosition().toShortString(), why, s.getFollowState(), s.getHealth(),
                com.ale.warbandcraft.village.Quarters.debugTags(s));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        // En las pruebas automáticas los dueños son inventados (nunca están conectados): todo se
        // guardaría solo en medio de otras pruebas. H8GameTests llama a esta lógica a mano.
        if (server instanceof GameTestServer) return;
        int tick = server.getTickCount();
        if (tick % 20 == 0) com.ale.warbandcraft.faction.OnlineDays.tick(server);   // qué días hubo alguien de cada facción conectado
        if (tick % QUARTERS_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) com.ale.warbandcraft.village.Quarters.tick(level);   // cuartel: patrullas
        }
        if (tick % ARRIVAL_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                Barracks.checkArrivals(level);
                com.ale.warbandcraft.camp.CampWorkers.tick(level);   // trabajadores de Campamentos comunes
            }
        }
        if (tick % CHECK_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) {
                Barracks.tick(level);
                Forge.tick(level);   // H10: herreros
                com.ale.warbandcraft.panel.Panel.refreshStats(level);   // H11: números del panel
            }
        }
    }
}
