package com.ale.warbandcraft.client.route;

import com.ale.warbandcraft.network.MapPointsMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.route.Routes;
import com.talhanation.recruits.client.gui.worldmap.WorldMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Dibuja tus Campamentos, aldeas y castillos con su nombre arriba del mapa de Recruits (tecla M), para ver a
 * dónde podés trazar rutas. No toca el código de Recruits: se dibuja después de su pantalla, con la misma cuenta
 * que usa él (pantalla = offset + bloque × escala).
 */
@OnlyIn(Dist.CLIENT)
public final class MapMarkers {
    private static final int CAMP_COLOR = 0xFFD4A017, VILLAGE_COLOR = 0xFF55DD55, CASTLE_COLOR = 0xFF5599FF;

    private static List<MapPointsMessage.Point> points = List.of();
    /** H15b: ejércitos en marcha. */
    private static List<MapPointsMessage.Army> armies = List.of();
    private static final int ARMY_HOSTILE = 0xFFFF4444, ARMY_OTHER = 0xFFFFAA33;
    private static Field offsetX, offsetZ;
    private static boolean broken;

    /** H23: dónde invocar tu ejército contra una hueste. */
    private static List<MapPointsMessage.Muster> musters = List.of();
    private static final int MUSTER_COLOR = 0xFFFFE040;

    public static void receive(List<MapPointsMessage.Point> list, List<MapPointsMessage.Army> armyList, List<MapPointsMessage.Muster> musterList) {
        points = list;
        armies = armyList;
        musters = musterList;
    }

    @SubscribeEvent
    public void onInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof WorldMapScreen) ModNetworking.CHANNEL.sendToServer(new MapPointsMessage.Request());
    }

    @SubscribeEvent
    public void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof WorldMapScreen map) || points.isEmpty() && armies.isEmpty() && musters.isEmpty() || broken || popupOpen(map)) return;
        double ox, oz;
        try {
            if (offsetX == null) {
                offsetX = WorldMapScreen.class.getDeclaredField("offsetX");
                offsetZ = WorldMapScreen.class.getDeclaredField("offsetZ");
                offsetX.setAccessible(true);
                offsetZ.setAccessible(true);
            }
            ox = offsetX.getDouble(map);
            oz = offsetZ.getDouble(map);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;   // otra versión de Recruits: mejor no dibujar nada que romper el mapa
            return;
        }
        double scale = WorldMapScreen.scale;
        GuiGraphics gfx = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 400);
        for (MapPointsMessage.Point p : points) {
            int x = (int) Math.round(ox + (p.pos().getX() + 0.5) * scale);
            int y = (int) Math.round(oz + (p.pos().getZ() + 0.5) * scale);
            if (x < -100 || y < -20 || x > map.width + 100 || y > map.height + 20) continue;
            int color = switch (p.kind()) {
                case CAMP -> CAMP_COLOR;
                case VILLAGE -> VILLAGE_COLOR;
                case CASTLE -> CASTLE_COLOR;
            };
            int r = p.kind() == Routes.Kind.CASTLE ? 4 : 3;
            gfx.fill(x - r - 1, y - r - 1, x + r + 2, y + r + 2, 0xFF000000);
            gfx.fill(x - r, y - r, x + r + 1, y + r + 1, color);
            int tw = font.width(p.label());
            int tx = x - tw / 2, ty = y - r - 12;
            gfx.fill(tx - 2, ty - 1, tx + tw + 2, ty + 9, 0x90000000);
            gfx.drawString(font, p.label(), tx, ty, color, false);
        }
        drawArmies(gfx, font, map, ox, oz, scale);
        drawMusters(gfx, font, map, ox, oz, scale);
        gfx.pose().popPose();
    }

    /** H23: el punto de reunión contra una hueste: un círculo punteado del tamaño real, una cruz y "Invocá tu ejército acá". */
    private static void drawMusters(GuiGraphics gfx, Font font, WorldMapScreen map, double ox, double oz, double scale) {
        for (MapPointsMessage.Muster m : musters) {
            int x = (int) Math.round(ox + (m.pos().getX() + 0.5) * scale);
            int y = (int) Math.round(oz + (m.pos().getZ() + 0.5) * scale);
            double r = Math.max(6, m.radius() * scale);
            if (x < -r - 100 || y < -r - 20 || x > map.width + r + 100 || y > map.height + r + 20) continue;
            int steps = Math.max(24, (int) (r * 1.5));
            for (int i = 0; i < steps; i += 2) {
                double a = i * Math.PI * 2 / steps;
                int px = (int) Math.round(x + Math.cos(a) * r), py = (int) Math.round(y + Math.sin(a) * r);
                gfx.fill(px - 1, py - 1, px + 2, py + 2, 0xFF000000);
                gfx.fill(px, py, px + 1, py + 1, MUSTER_COLOR);
            }
            gfx.fill(x - 5, y - 1, x + 6, y + 2, 0xFF000000);
            gfx.fill(x - 1, y - 5, x + 2, y + 6, 0xFF000000);
            gfx.fill(x - 4, y, x + 5, y + 1, MUSTER_COLOR);
            gfx.fill(x, y - 4, x + 1, y + 5, MUSTER_COLOR);
            int tw = font.width(m.label());
            int lx = x - tw / 2, ly = y - 16;
            gfx.fill(lx - 2, ly - 1, lx + tw + 2, ly + 9, 0x90000000);
            gfx.drawString(font, m.label(), lx, ly, MUSTER_COLOR, false);
        }
    }

    /** H15b: cada ejército en marcha, donde va ahora, con una línea de puntos hasta su destino. */
    private static void drawArmies(GuiGraphics gfx, Font font, WorldMapScreen map, double ox, double oz, double scale) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        for (MapPointsMessage.Army a : armies) {
            double f = a.arrive() <= a.start() ? 1 : Math.max(0, Math.min(1, (now - a.start()) / (double) (a.arrive() - a.start())));
            double wx = a.from().getX() + (a.to().getX() - a.from().getX()) * f + 0.5;
            double wz = a.from().getZ() + (a.to().getZ() - a.from().getZ()) * f + 0.5;
            int x = (int) Math.round(ox + wx * scale), y = (int) Math.round(oz + wz * scale);
            int tx = (int) Math.round(ox + (a.to().getX() + 0.5) * scale), ty = (int) Math.round(oz + (a.to().getZ() + 0.5) * scale);
            int color = a.hostile() ? ARMY_HOSTILE : ARMY_OTHER;
            // Línea de puntos hasta el destino
            double len = Math.sqrt((double) (tx - x) * (tx - x) + (double) (ty - y) * (ty - y));
            for (int d = 6; d < len; d += 6) {
                int px = (int) Math.round(x + (tx - x) * d / len), py = (int) Math.round(y + (ty - y) * d / len);
                gfx.fill(px, py, px + 2, py + 2, color & 0x99FFFFFF);
            }
            if (x < -100 || y < -20 || x > map.width + 100 || y > map.height + 20) continue;
            // Rombo
            for (int r = 0; r <= 4; r++) {
                gfx.fill(x - (4 - r), y - r - 1, x + (4 - r) + 1, y - r, 0xFF000000);
                gfx.fill(x - (4 - r), y + r, x + (4 - r) + 1, y + r + 1, 0xFF000000);
            }
            for (int r = 0; r <= 3; r++) {
                gfx.fill(x - (3 - r), y - r, x + (3 - r) + 1, y - r + 1, color);
                gfx.fill(x - (3 - r), y + r, x + (3 - r) + 1, y + r + 1, color);
            }
            int tw = font.width(a.label());
            int lx = x - tw / 2, ly = y + 7;
            gfx.fill(lx - 2, ly - 1, lx + tw + 2, ly + 9, 0x90000000);
            gfx.drawString(font, a.label(), lx, ly, color, false);
        }
    }

    /** Con una ventanita de Recruits abierta (menú, nombrar o editar una ruta) no se tapa nada. */
    private static boolean popupOpen(WorldMapScreen map) {
        return (map.contextMenu != null && map.contextMenu.isVisible())
                || (map.routeNamePopup != null && map.routeNamePopup.isVisible())
                || (map.routeEditPopup != null && map.routeEditPopup.isVisible())
                || (map.waypointEditPopup != null && map.waypointEditPopup.isVisible());
    }
}
