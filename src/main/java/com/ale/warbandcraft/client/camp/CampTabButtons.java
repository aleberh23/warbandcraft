package com.ale.warbandcraft.client.camp;

import com.ale.warbandcraft.network.CampTabMessage;
import com.ale.warbandcraft.network.ModNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/** Las pestañas del Campamento (Cofre · Doctrinas · Kits · Barracón · Rutas · Envíos), en una columna a la izquierda. */
@OnlyIn(Dist.CLIENT)
public final class CampTabButtons {
    private static final String[] KEYS = {"gui.warbandcraft.tab.chest", "gui.warbandcraft.tab.doctrines", "gui.warbandcraft.tab.kits",
            "gui.warbandcraft.tab.barracks", "gui.warbandcraft.tab.routes", "gui.warbandcraft.tab.shipments"};
    public static final int WIDTH = 64;

    private CampTabButtons() {}

    public static List<Button> create(BlockPos camp, int active, int panelLeft, int panelTop) {
        List<Button> out = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            int tab = i;
            Button b = Button.builder(Component.translatable(KEYS[i]),
                            x -> ModNetworking.CHANNEL.sendToServer(new CampTabMessage(camp, tab)))
                    .bounds(Math.max(2, panelLeft - WIDTH - 4), panelTop + 4 + i * 24, WIDTH, 20).build();
            b.active = i != active;
            out.add(b);
        }
        return out;
    }
}
