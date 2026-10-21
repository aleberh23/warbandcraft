package com.ale.warbandcraft.event;

import com.ale.warbandcraft.ai.Unstuck;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Destrabador de soldados ({@link Unstuck}), cada medio segundo. */
public class SoldierEvents {
    /** Los caballos de los soldados no corren más que {@link com.ale.warbandcraft.ai.HorseSpeed#MAX_SPEED}. */
    @SubscribeEvent
    public void onMount(net.minecraftforge.event.entity.EntityMountEvent event) {
        com.ale.warbandcraft.ai.HorseSpeed.onMount(event.getEntityMounting(), event.getEntityBeingMounted(), event.isMounting());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        // En las pruebas automáticas moverlos solos rompería otras pruebas; H8GameTests lo llama a mano.
        if (server instanceof GameTestServer) return;
        // Escolta: defenderte, teletransporte, nado y botes (cada tick)
        for (ServerLevel level : server.getAllLevels()) com.ale.warbandcraft.ai.Escort.tick(level);
        if (server.getTickCount() % Unstuck.INTERVAL != 0) return;
        for (ServerLevel level : server.getAllLevels()) Unstuck.tick(level);
    }
}
