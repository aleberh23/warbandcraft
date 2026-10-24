package com.ale.warbandcraft.client;

import com.ale.warbandcraft.client.aerial.AerialScreen;
import com.ale.warbandcraft.village.VillageHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * El cartelito de la aldea/castillo en que estás: arriba a la derecha (debajo de los efectos), chico y semitransparente.
 * Lo manda el servidor ({@link com.ale.warbandcraft.network.VillageHudMessage}); en el modo Gestión no se dibuja (esa
 * info está en la pestaña Resumen).
 */
@OnlyIn(Dist.CLIENT)
public final class VillageHudOverlay {
    private static final int PAD = 4, LINE = 10, MARGIN = 4;
    private static final int GRAY = 0xFFAAAAAA, WHITE = 0xFFFFFFFF, RED = 0xFFFF5555, GOLD = 0xFFFFAA00;

    @Nullable
    private static VillageHud.Info info;

    public static void receive(@Nullable VillageHud.Info newInfo) {
        info = newInfo;
    }

    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CHAT_PANEL.id(), "village_hud",
                (gui, gfx, partialTick, width, height) -> render(gfx, width));
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        info = null;
    }

    private static void render(GuiGraphics gfx, int screenWidth) {
        VillageHud.Info i = info;
        Minecraft mc = Minecraft.getInstance();
        if (i == null || mc.player == null || mc.options.renderDebug) return;
        if (mc.screen instanceof AerialScreen && AerialScreen.managing()) return;

        Font font = mc.font;
        List<Component> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        lines.add(i.name());
        colors.add(WHITE);
        switch (i.relation()) {
            case VillageHud.OTHER -> add(lines, colors, "De " + i.owner().getString(), GRAY);
            case VillageHud.LOOTED -> add(lines, colors, "Saqueada", RED);
            default -> {
            }
        }
        add(lines, colors, "Por día: " + i.daily().emeralds() + " esm · " + i.daily().food() + " pan"
                + (i.daily().armament() > 0 ? " · " + i.daily().armament() + " arm" : ""), WHITE);
        add(lines, colors, "Población: " + i.population(), WHITE);
        if (i.relation() == VillageHud.MINE) {
            add(lines, colors, "Barracón: " + i.barracks() + "/" + i.capacity() + " · Guardia: " + i.guards(), WHITE);
        } else if (i.relation() == VillageHud.FACTION || i.relation() == VillageHud.LOOTED) {
            add(lines, colors, "Guarnición: " + i.garrison(), WHITE);
            if (i.relation() == VillageHud.FACTION && i.garrison() > 0) {
                add(lines, colors, "[" + ChoiceScreen.keyName().getString() + "] Asediar", GOLD);   // H23
            }
        }
        if (i.invasionTicks() >= 0) {
            long sec = i.invasionTicks() / 20;
            add(lines, colors, "¡Invasión en " + sec / 60 + ":" + String.format("%02d", sec % 60) + "!", RED);
        }

        int w = 0;
        for (Component c : lines) w = Math.max(w, font.width(c));
        int boxW = w + PAD * 2, boxH = lines.size() * LINE + PAD * 2 - 2;
        int x = screenWidth - boxW - MARGIN, y = MARGIN + effectsHeight(mc);
        gfx.fill(x, y, x + boxW, y + boxH, 0x90000000);
        for (int n = 0; n < lines.size(); n++) {
            gfx.drawString(font, lines.get(n), x + PAD, y + PAD + n * LINE, colors.get(n));
        }
    }

    private static void add(List<Component> lines, List<Integer> colors, String text, int color) {
        lines.add(Component.literal(text));
        colors.add(color);
    }

    /** Lo que ocupan los íconos de efectos de vanilla arriba a la derecha (buenos en la 1ª fila, malos en la 2ª). */
    private static int effectsHeight(Minecraft mc) {
        boolean good = false, bad = false;
        for (MobEffectInstance e : mc.player.getActiveEffects()) {
            if (!e.showIcon()) continue;
            if (e.getEffect().isBeneficial()) good = true;
            else bad = true;
        }
        return bad ? 52 : good ? 26 : 0;
    }
}
