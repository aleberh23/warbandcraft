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

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        // En las pruebas automáticas los dueños son inventados (nunca están conectados): todo se
        // guardaría solo en medio de otras pruebas. H8GameTests llama a esta lógica a mano.
        if (server instanceof GameTestServer) return;
        int tick = server.getTickCount();
        if (tick % QUARTERS_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) com.ale.warbandcraft.village.Quarters.tick(level);   // cuartel: patrullas
        }
        if (tick % ARRIVAL_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) Barracks.checkArrivals(level);
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
