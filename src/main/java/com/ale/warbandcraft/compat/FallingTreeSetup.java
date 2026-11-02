package com.ale.warbandcraft.compat;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * FallingTree (pedido de Ale: "talar todo el árbol de una, pero más lento"): mod aparte, no le copiamos código. Al arrancar
 * le dejamos la configuración como la queremos: el tronco tarda más cuanto más grande es el árbol. Con hacha cae el árbol
 * entero; sin herramienta se rompe el tronco de a uno, como en vanilla (pedido de Ale: `forceToolUsage` no dejaba romper
 * troncos con la mano, siempre se apaga). Se hace por reflexión (no está en el classpath) y en el juego de cada uno y en el
 * servidor: el servidor manda su configuración a los que entran. Si alguien cambió la velocidad a mano (no es 0, el valor de
 * FallingTree), no se toca.
 */
public final class FallingTreeSetup {
    /** Romper el tronco tarda {@code SPEED} × troncos del árbol (0,25: un roble de 5 = 1,25 troncos, un abeto de 30 = 7,5). */
    public static double SPEED = 0.25;

    private FallingTreeSetup() {}

    public static void apply() {
        if (!ModList.get().isLoaded("fallingtree")) return;
        try {
            Object mod = Class.forName("fr.rakambda.fallingtree.forge.FallingTree").getMethod("getMod").invoke(null);
            Object config = mod.getClass().getMethod("getOwnConfiguration").invoke(mod);
            Object tools = config.getClass().getMethod("getTools").invoke(config);
            boolean changed = false;
            if (forceToolUsage(tools)) {
                tools.getClass().getMethod("setForceToolUsage", boolean.class).invoke(tools, false);
                changed = true;
                WarbandCraft.LOGGER.info("FallingTree: los troncos se pueden romper sin herramienta");
            }
            double speed = (double) tools.getClass().getMethod("getSpeedMultiplicand").invoke(tools);
            if (speed <= 0) {
                tools.getClass().getMethod("setSpeedMultiplicand", double.class).invoke(tools, SPEED);
                changed = true;
                WarbandCraft.LOGGER.info("FallingTree: tala más lenta según el tamaño ({})", SPEED);
            }
            if (!changed) return;
            Method save = config.getClass().getMethod("onUpdate");
            save.invoke(config);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            WarbandCraft.LOGGER.warn("FallingTree: no se pudo poner la configuración ({})", e.toString());
        }
    }

    private static boolean forceToolUsage(Object tools) throws ReflectiveOperationException {
        Method get;
        try {
            get = tools.getClass().getMethod("isForceToolUsage");
        } catch (NoSuchMethodException e) {
            get = tools.getClass().getMethod("getForceToolUsage");
        }
        return (boolean) get.invoke(tools);
    }
}
