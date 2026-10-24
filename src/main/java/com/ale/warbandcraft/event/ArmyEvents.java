package com.ale.warbandcraft.event;

import com.ale.warbandcraft.army.Armies;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * H20: ejércitos de campaña. Cada segundo la cuenta regresiva y las órdenes en el campo; cada 10 s, los que quedaron
 * sueltos vuelven a su barracón. Muertes, el golpe de la emboscada y el estado al entrar.
 */
public class ArmyEvents {
    private static final int TICK_INTERVAL = 20;
    private static final int SWEEP_INTERVAL = 20 * 10;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server instanceof GameTestServer) return;   // las pruebas llaman a Armies a mano
        int tick = server.getTickCount();
        if (tick % TICK_INTERVAL == 0) {
            for (ServerLevel level : server.getAllLevels()) Armies.tick(level);
        }
        if (tick % SWEEP_INTERVAL == 5) {
            for (ServerLevel level : server.getAllLevels()) Armies.sweepOrphans(level);
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof AbstractRecruitEntity s && s.level() instanceof ServerLevel level) {
            Armies.onDeath(level, s);
            com.ale.warbandcraft.battle.tactics.Morale.onDeath(level, s);   // H24: a los compañeros cerca les baja la moral
        }
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            event.setAmount(Armies.damage(level, event.getSource().getEntity(), event.getAmount()));
            // H26: picas contra montados, carga de lanza, contundentes contra placas, pavés contra flechas
            event.setAmount(com.ale.warbandcraft.kit.WeaponRules.damage(event.getEntity(), event.getSource(), event.getAmount()));
            // pedido de Ale: los bandidos pegan menos (equipo peor)
            event.setAmount(com.ale.warbandcraft.dungeon.Bandits.damage(event.getSource().getEntity(), event.getAmount()));
            // H23: entre soldados se pegan menos (las batallas duran más)
            event.setAmount(com.ale.warbandcraft.battle.BattleSize.npcDamage(event.getEntity(), event.getSource().getEntity(), event.getAmount()));
            // H24: por el costado o por detrás pega más (y baja la moral)
            event.setAmount(com.ale.warbandcraft.battle.tactics.Morale.onHurt(event.getEntity(), event.getSource().getEntity(), event.getAmount()));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) com.ale.warbandcraft.network.ArmyStatusMessage.send(p);
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        Armies.clear();
    }
}
