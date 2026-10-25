package com.ale.warbandcraft.nav;

/**
 * Qué puede hacer quien busca el camino y cuánto le cuesta cada cosa (en "bloques caminados").
 *
 * @param water      puede cruzar agua (en bote); {@code waterCost} multiplica cada bloque de agua
 * @param climbWalls planea escaleras de mano (conexiones apagadas: solo para elegir dónde ponerlas)
 * @param breakWalls planea romper muros (zapador con TNT, cañón)
 * @param bashGates  sin abrir puertas, pasa igual por un portón (puertas de cerco) rompiéndolo a golpes ({@code Works.tickGates})
 */
public record NavCaps(boolean water, float waterCost, boolean openDoors, boolean climbWalls, float climbCost, boolean breakWalls,
                      float breakCost, int maxDrop, float ladderCost, boolean bashGates) {
    /** Lo que suma pasar por un portón cerrado rompiéndolo (en bloques caminados). */
    public static float GATE_BASH_COST = 12;
    /** El agua se evita lo más posible: cada bloque vale como 30 caminados. */
    public static float AVOID_WATER = 30;

    /** Un soldado yendo a algún lado: puertas sí, agua solo si no queda otra, escaleras que ya estén puestas. */
    public static final NavCaps SOLDIER = new NavCaps(true, AVOID_WATER, true, false, 0, false, 0, 4, 1.5F, false);
    /** Un invasor yendo a tu aldea: igual, pero no abre tus puertas (tiene que entrar por un hueco o una escalera). */
    public static final NavCaps INVADER = new NavCaps(true, AVOID_WATER, false, false, 0, false, 0, 5, 1.5F, true);

    /** Para planear un carril de asedio: además trepa (con escalera nueva) y, si tiene con qué, rompe. */
    public static NavCaps siege(boolean canBreak) {
        return new NavCaps(true, AVOID_WATER, false, true, 25, canBreak, 30, 5, 1.5F, true);
    }
}
