package com.ale.warbandcraft.event;

import com.ale.warbandcraft.route.Routes;
import com.ale.warbandcraft.route.Travel;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Listeners de H13 (rutas y viaje): cada tick, los viajes en curso; cada segundo, los envíos que llegan.
 * Mientras viaja, el jugador no recibe daño; si se desconecta, el viaje se corta.
 */
public class RouteEvents {
    private static int SHIPMENT_INTERVAL = 20;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        Travel.tick(server);
        for (ServerLevel level : server.getAllLevels()) com.ale.warbandcraft.route.Roads.tick(level);   // H14: caminos en obra
        // En las pruebas automáticas H13GameTests entrega los envíos a mano (corren varias a la vez).
        if (server instanceof GameTestServer) return;
        if (server.getTickCount() % SHIPMENT_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) Routes.tickShipments(level);
        }
    }

    @SubscribeEvent
    public void onAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Travel.isTraveling(player.getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Travel.forget(event.getEntity().getUUID());
    }
}
