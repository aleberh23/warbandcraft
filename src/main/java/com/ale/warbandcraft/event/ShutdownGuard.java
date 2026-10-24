package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Red de seguridad del servidor dedicado. Después de guardar y apagar ({@link ServerStoppedEvent}), si algún mod dejó
 * vivo un hilo que no es daemon, Java no termina y el panel del host queda en "apagando" hasta que se fuerza.
 * Acá esperamos un poco: si el proceso no terminó solo, dejamos en el log qué hilos lo sostienen (para encontrar al
 * culpable) y lo cerramos. El mundo ya está guardado a esta altura.
 * Solo en servidor dedicado: en un mundo de un jugador el juego sigue abierto después de cerrar el mundo.
 */
public class ShutdownGuard {
    /** Cuánto esperamos a que Java termine solo. */
    private static final long GRACE_MS = 15_000;
    /** Si System.exit también se traba (un shutdown hook colgado), cortamos en seco después de esto. */
    private static final long HALT_MS = 15_000;

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        Thread guard = new Thread(ShutdownGuard::watch, "Warband Craft shutdown guard");
        guard.setDaemon(true);   // si Java termina solo, este hilo no lo frena
        guard.start();
    }

    private static void watch() {
        try {
            Thread.sleep(GRACE_MS);
        } catch (InterruptedException e) {
            return;
        }
        StringBuilder hilos = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (thread.isDaemon() || !thread.isAlive() || thread == Thread.currentThread()) return;
            hilos.append("\n  hilo '").append(thread.getName()).append("' (").append(thread.getState()).append(')');
            for (int i = 0; i < Math.min(8, stack.length); i++) hilos.append("\n      at ").append(stack[i]);
        });
        WarbandCraft.LOGGER.warn("Warband Craft: el servidor ya guardó y se apagó, pero Java no termina. "
                + "Hilos que lo sostienen:{}\nCierro el proceso.", hilos);

        Thread halt = new Thread(() -> {
            try {
                Thread.sleep(HALT_MS);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "Warband Craft halt");
        halt.setDaemon(true);
        halt.start();
        System.exit(0);
    }
}
