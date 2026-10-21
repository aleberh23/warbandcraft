package com.ale.warbandcraft.client;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Teclas del mod. Clase de solo-cliente: se registra desde {@code WarbandCraft} a través de
 * {@code DistExecutor}, nunca se carga en un servidor dedicado.
 */
public final class ModKeyMappings {
    public static final String CATEGORY = "key.categories.warbandcraft";

    public static final KeyMapping AERIAL_VIEW =
            new KeyMapping("key.warbandcraft.aerial_view", GLFW.GLFW_KEY_V, CATEGORY);

    /** H13: pantalla de Rutas y viaje. */
    public static final KeyMapping ROUTES =
            new KeyMapping("key.warbandcraft.routes", GLFW.GLFW_KEY_J, CATEGORY);

    /** H11: Panel de control. */
    public static final KeyMapping PANEL =
            new KeyMapping("key.warbandcraft.panel", GLFW.GLFW_KEY_K, CATEGORY);

    /** H23: meterse en una batalla entre facciones / asediar la aldea en que estás. */
    public static final KeyMapping BATTLE =
            new KeyMapping("key.warbandcraft.battle", GLFW.GLFW_KEY_B, CATEGORY);

    private ModKeyMappings() {}

    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(AERIAL_VIEW);
        event.register(ROUTES);
        event.register(PANEL);
        event.register(BATTLE);
    }
}
