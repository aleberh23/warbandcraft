package com.ale.warbandcraft.client.aerial;

import com.ale.warbandcraft.network.AerialCampsMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de la vista aérea (H5). Vive solo del lado cliente: no se guarda ni se sincroniza, se pierde
 * al cerrar sesión o salir de la vista. {@code groups} son los 9 grupos de control (Ctrl+1..9 o
 * Ctrl+click asigna, 1..9 o click selecciona) — un concepto propio de esta cámara, no los grupos con
 * nombre de Villager Recruits.
 */
public final class AerialCameraState {
    public static final float DEFAULT_PITCH = 55.0F;
    public static final float MIN_PITCH = 20.0F;
    public static final float MAX_PITCH = 85.0F;
    public static final float MIN_DISTANCE = 8.0F;
    public static final float MAX_DISTANCE = 56.0F;
    public static final float DEFAULT_DISTANCE = 24.0F;

    /** A qué apunta un click derecho corto cuando no hay nada bajo el cursor que lo decida solo. */
    public enum OrderMode { AUTO, MOVE, ATTACK, BLAST, CANNON, ARMY_ADVANCE }  // ARMY_ADVANCE: H20, el próximo click derecho mueve al ejército  // BLAST/CANNON: órdenes del Zapador (H6)

    /** Cómo se reparten los destinos entre los soldados seleccionados en una orden de movimiento. */
    public enum Formation { NONE, LINE }

    public boolean active = false;
    public Vec3 center = Vec3.ZERO;
    public float yaw = 0.0F;
    public float pitch = DEFAULT_PITCH;
    public float distance = DEFAULT_DISTANCE;
    public double enterHealth = 0.0D;

    public OrderMode orderMode = OrderMode.AUTO;
    public Formation formation = Formation.NONE;
    public boolean showHitboxes = false;

    public final Set<UUID> selected = new LinkedHashSet<>();
    /** H8: tus Campamentos en este mundo (los manda el servidor) y el seleccionado (se le dibuja la jurisdicción). */
    public List<AerialCampsMessage.CampInfo> camps = List.of();
    /** H16: centros de tus aldeas/castillos. */
    public List<net.minecraft.core.BlockPos> villages = List.of();
    @Nullable
    public BlockPos selectedCamp;
    @SuppressWarnings("unchecked")
    public final Set<UUID>[] groups = new Set[9];

    public AerialCameraState() {
        for (int i = 0; i < groups.length; i++) groups[i] = new LinkedHashSet<>();
    }

    public void reset() {
        active = false;
        selected.clear();
    }
}
