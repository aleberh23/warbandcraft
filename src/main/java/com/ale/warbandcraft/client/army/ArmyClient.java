package com.ale.warbandcraft.client.army;

import com.ale.warbandcraft.network.ArmyStatusMessage;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Lado cliente de H20: tus ejércitos en el campo (para la vista aérea), según el último aviso del servidor. */
@OnlyIn(Dist.CLIENT)
public final class ArmyClient {
    private static List<ArmyStatusMessage.Field> field = List.of();
    /** El ejército elegido en la vista aérea (índice en {@link #field}). */
    public static int selected;

    private ArmyClient() {}

    public static void receive(List<ArmyStatusMessage.Field> armies) {
        boolean changed = armies.size() != field.size();
        field = List.copyOf(armies);
        if (selected >= field.size()) selected = 0;
        if (changed && net.minecraft.client.Minecraft.getInstance().screen instanceof com.ale.warbandcraft.client.aerial.AerialScreen s) {
            s.armiesChanged();
        }
    }

    public static List<ArmyStatusMessage.Field> field() {
        return field;
    }

    public static ArmyStatusMessage.Field current() {
        return field.isEmpty() ? null : field.get(Math.min(selected, field.size() - 1));
    }
}
