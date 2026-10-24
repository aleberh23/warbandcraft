package com.ale.warbandcraft.client.route;

import com.ale.warbandcraft.camp.CampTabs;
import com.ale.warbandcraft.client.camp.CampTabButtons;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.OpenRoutesMessage;
import com.ale.warbandcraft.network.RouteActionMessage;
import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.world.RecruitsRoute;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pantalla de Rutas (H13). Dos modos:
 * <ul>
 *   <li><b>Viajar</b>: a dónde se llega desde el punto donde estás, cuánto tarda y el botón Viajar.</li>
 *   <li><b>Rutas</b>: las rutas que dibujaste en el mapa de Recruits (están en tu compu) con qué une cada una y
 *   el botón Registrar; las ya registradas, con Borrar.</li>
 *   <li><b>Caminos</b> (H14, solo en la pestaña de un Campamento): las rutas que salen de él, con el botón Camino.
 *   Las aldeas/castillos los encargan desde Mejoras.</li>
 * </ul>
 * Se abre desde la pestaña Rutas del Campamento o con la tecla J. Sin textura propia, como el Barracón.
 */
@OnlyIn(Dist.CLIENT)
public class RoutesScreen extends Screen {
    private static final int W = 320, H = 222, ROW = 20, ROWS_TOP = 52, VISIBLE_ROWS = 7;

    /** Una fila del modo Rutas: la de Recruits (si está en esta compu) y/o la registrada. */
    private record Line(UUID id, String name, @Nullable RecruitsRoute local, @Nullable OpenRoutesMessage.RouteInfo registered) {}

    private static final int TRAVEL = 0, ROUTES = 1, ROADS = 2;

    private OpenRoutesMessage state;
    private int mode;
    private int scroll;

    public RoutesScreen(OpenRoutesMessage state) {
        super(Component.translatable("gui.warbandcraft.tab.routes"));
        this.state = state;
        this.mode = state.destinations.isEmpty() ? ROUTES : TRAVEL;
    }

    public void refresh(OpenRoutesMessage msg) {
        state = msg;
        if (mode == ROADS && !roadsHere()) mode = ROUTES;
        rebuildWidgets();
    }

    /** Se abrió desde la pestaña de un Campamento común (no el de una aldea/castillo): ahí se encargan los caminos. */
    private boolean roadsHere() {
        return state.camp != null && state.here != null && state.here.kind() == com.ale.warbandcraft.route.Routes.Kind.CAMP;
    }

    /** Las rutas que salen de este Campamento. */
    private List<OpenRoutesMessage.RouteInfo> roads() {
        List<OpenRoutesMessage.RouteInfo> out = new ArrayList<>();
        for (OpenRoutesMessage.RouteInfo r : state.routes) if (r.here()) out.add(r);
        return out;
    }

    private int ticks;

    @Override
    public void tick() {
        super.tick();
        // Con un camino en obra, el porcentaje se pide de nuevo cada 2 s
        if (mode == ROADS && ++ticks % 40 == 0 && roads().stream().anyMatch(r -> r.percent() >= 0)) {
            send(RouteActionMessage.simple(RouteActionMessage.Action.OPEN, state.camp));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** "1:05" a partir de ticks. */
    public static String time(int ticks) {
        int s = Math.max(0, ticks / 20);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private int left, top;
    /** Modo Rutas: se arma una vez por {@link #init} (leer las rutas de Recruits puede tocar el disco). */
    private List<Line> lines = List.of();

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        lines = buildLines();
        if (state.camp != null) {
            for (Button b : CampTabButtons.create(state.camp, CampTabs.ROUTES, left, top)) addRenderableWidget(b);
        }
        Button travel = Button.builder(Component.translatable("gui.warbandcraft.routes.mode_travel"), b -> setMode(TRAVEL))
                .bounds(left + 8, top + 26, 90, 16).build();
        travel.active = mode != TRAVEL;
        addRenderableWidget(travel);
        Button routes = Button.builder(Component.translatable("gui.warbandcraft.routes.mode_routes"), b -> setMode(ROUTES))
                .bounds(left + 102, top + 26, 90, 16).build();
        routes.active = mode != ROUTES;
        addRenderableWidget(routes);
        if (roadsHere()) {
            Button roads = Button.builder(Component.translatable("gui.warbandcraft.routes.mode_roads"), b -> setMode(ROADS))
                    .bounds(left + 196, top + 26, 90, 16).build();
            roads.active = mode != ROADS;
            addRenderableWidget(roads);
        }

        List<OpenRoutesMessage.RouteInfo> roads = roads();
        int count = count();
        scroll = Math.max(0, Math.min(scroll, count - VISIBLE_ROWS));
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < count; i++) {
            int y = top + ROWS_TOP + i * ROW;
            if (mode == ROADS) {
                OpenRoutesMessage.RouteInfo r = roads.get(scroll + i);
                if (r.road() || r.percent() >= 0) continue;
                Button road = Button.builder(Component.translatable("gui.warbandcraft.panel.build_road", r.cost()),
                                b -> send(new RouteActionMessage(RouteActionMessage.Action.BUILD_ROAD, state.camp, r.id(), "", List.of(), BlockPos.ZERO, state.post)))
                        .bounds(left + W - 98, y, 90, 16).build();
                road.active = r.active();
                road.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.warbandcraft.panel.build_road_hint")));
                addRenderableWidget(road);
            } else if (mode == ROUTES) {
                Line line = lines.get(scroll + i);
                if (line.local() != null) {
                    addRenderableWidget(Button.builder(Component.translatable(line.registered() == null ? "gui.warbandcraft.routes.register"
                                    : "gui.warbandcraft.routes.update"), b -> register(line.local()))
                            .bounds(left + W - 140, y, 70, 16).build());
                }
                if (line.registered() != null) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.routes.delete"),
                                    b -> send(new RouteActionMessage(RouteActionMessage.Action.DELETE, state.camp, line.id(), "", List.of(), BlockPos.ZERO, state.post)))
                            .bounds(left + W - 66, y, 58, 16).build());
                }
            } else {
                OpenRoutesMessage.DestInfo d = state.destinations.get(scroll + i);
                addRenderableWidget(Button.builder(Component.translatable("gui.warbandcraft.routes.travel"),
                                b -> send(new RouteActionMessage(RouteActionMessage.Action.TRAVEL, state.camp, new UUID(0, 0), "", List.of(), d.pos(), state.post)))
                        .bounds(left + W - 66, y, 58, 16).build());
                if (state.stock != null && d.camp()) {
                    // H10: mandar el almacén de esta aldea/castillo a ese Campamento
                    Button stock = Button.builder(Component.translatable("gui.warbandcraft.routes.send_stock"),
                                    b -> send(new RouteActionMessage(RouteActionMessage.Action.SEND_STOCK, state.camp, new UUID(0, 0), "", List.of(), d.pos(), state.post)))
                            .bounds(left + W - 128, y, 58, 16).build();
                    stock.active = state.stock[0] + state.stock[1] + state.stock[2] > 0;
                    addRenderableWidget(stock);
                }
            }
        }
    }

    private int count() {
        return switch (mode) {
            case ROUTES -> lines.size();
            case ROADS -> roads().size();
            default -> state.destinations.size();
        };
    }

    private void setMode(int mode) {
        this.mode = mode;
        scroll = 0;
        rebuildWidgets();
    }

    private void send(RouteActionMessage msg) {
        ModNetworking.CHANNEL.sendToServer(msg);
    }

    private void register(RecruitsRoute route) {
        List<BlockPos> points = new ArrayList<>();
        for (RecruitsRoute.Waypoint w : route.getWaypoints()) points.add(w.getPosition());
        send(new RouteActionMessage(RouteActionMessage.Action.REGISTER, state.camp, route.getId(), route.getName(), points, BlockPos.ZERO, state.post));
    }

    /** Rutas del mapa de Recruits de esta compu + las registradas que no están acá. */
    private List<Line> buildLines() {
        List<Line> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<RecruitsRoute> local;
        try {
            local = ClientManager.getRoutesList();
            if (local.isEmpty()) {
                ClientManager.loadRoutes();
                local = ClientManager.getRoutesList();
            }
        } catch (Exception e) {
            local = List.of();
        }
        for (RecruitsRoute r : local) {
            out.add(new Line(r.getId(), r.getName(), r, registered(r.getId())));
            seen.add(r.getId());
        }
        for (OpenRoutesMessage.RouteInfo r : state.routes) {
            if (!seen.contains(r.id())) out.add(new Line(r.id(), r.name(), null, r));
        }
        return out;
    }

    @Nullable
    private OpenRoutesMessage.RouteInfo registered(UUID id) {
        for (OpenRoutesMessage.RouteInfo r : state.routes) if (r.id().equals(id)) return r;
        return null;
    }

    /** El nombre del punto tuyo a ≤ radio de {@code pos}, o "?". */
    private Component nodeName(BlockPos pos) {
        OpenRoutesMessage.NodeInfo best = null;
        double bestSq = Double.MAX_VALUE;
        for (OpenRoutesMessage.NodeInfo n : state.nodes) {
            double dx = n.pos().getX() - pos.getX(), dz = n.pos().getZ() - pos.getZ();
            double d = dx * dx + dz * dz;
            if (d <= (double) state.endpointRadius * state.endpointRadius && d < bestSq) {
                bestSq = d;
                best = n;
            }
        }
        return best == null ? Component.literal("?").withStyle(ChatFormatting.RED) : best.name();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && (minecraft.options.keyInventory.matches(keyCode, scanCode)
                || com.ale.warbandcraft.client.ModKeyMappings.ROUTES.matches(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int count = count();
        int max = Math.max(0, count - VISIBLE_ROWS);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        gfx.fill(left - 1, top - 1, left + W + 1, top + H + 1, 0xFF6B5A3A);
        gfx.fill(left, top, left + W, top + H, 0xF0201810);

        Component here = state.here != null
                ? Component.translatable("gui.warbandcraft.routes.here", state.here.name())
                : Component.translatable("gui.warbandcraft.routes.nowhere", state.endpointRadius);
        gfx.drawString(font, here, left + 8, top + 8, state.here != null ? 0xFFD4A017 : 0xFFAAAAAA);
        if (state.stock != null) {
            // H10: lo que tiene guardado esta aldea/castillo
            Component stock = Component.translatable("gui.warbandcraft.routes.stock", state.stock[0], state.stock[1], state.stock[2]);
            gfx.drawString(font, stock, left + W - 8 - font.width(stock), top + 30, 0xFFFFE08A);
        }

        if (mode == ROUTES) renderRoutes(gfx);
        else if (mode == ROADS) renderRoads(gfx);
        else renderDestinations(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void renderDestinations(GuiGraphics gfx) {
        List<OpenRoutesMessage.DestInfo> dests = state.destinations;
        if (dests.isEmpty()) {
            Component msg = state.here == null ? Component.translatable("gui.warbandcraft.routes.go_to_node")
                    : Component.translatable("gui.warbandcraft.routes.no_destinations");
            gfx.drawWordWrap(font, msg, left + 8, top + ROWS_TOP + 3, W - 16, 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < dests.size(); i++) {
            OpenRoutesMessage.DestInfo d = dests.get(scroll + i);
            int y = top + ROWS_TOP + i * ROW;
            if (i % 2 == 0) gfx.fill(left + 4, y - 2, left + W - 4, y + ROW - 2, 0x20FFFFFF);
            gfx.drawString(font, d.name(), left + 8, y + 4, 0xFFFFFF);
            Component info = d.legs() > 1
                    ? Component.translatable("gui.warbandcraft.routes.dest_legs", time(d.ticks()), d.legs())
                    : Component.translatable("gui.warbandcraft.routes.dest", time(d.ticks()));
            int infoRight = state.stock != null && d.camp() ? left + W - 134 : left + W - 72;
            gfx.drawString(font, info, infoRight - font.width(info), y + 4, 0xBBBBBB);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.routes.no_road_hint"));
    }

    private void renderRoutes(GuiGraphics gfx) {
                if (lines.isEmpty()) {
            gfx.drawWordWrap(font, Component.translatable("gui.warbandcraft.routes.none", state.endpointRadius),
                    left + 8, top + ROWS_TOP + 3, W - 16, 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < lines.size(); i++) {
            Line line = lines.get(scroll + i);
            int y = top + ROWS_TOP + i * ROW;
            if (i % 2 == 0) gfx.fill(left + 4, y - 2, left + W - 4, y + ROW - 2, 0x20FFFFFF);
            Component ends;
            int color = 0xBBBBBB;
            if (line.registered() != null) {
                OpenRoutesMessage.RouteInfo r = line.registered();
                ends = Component.translatable("gui.warbandcraft.routes.ends", nodeName(r.a()), nodeName(r.b()));
                if (!r.active()) {
                    ends = Component.translatable("gui.warbandcraft.routes.inactive");
                    color = 0xFF5555;
                }
            } else if (line.local() != null && line.local().getWaypoints().size() >= 2) {
                List<RecruitsRoute.Waypoint> w = line.local().getWaypoints();
                ends = Component.translatable("gui.warbandcraft.routes.ends", nodeName(w.get(0).getPosition()),
                        nodeName(w.get(w.size() - 1).getPosition()));
            } else {
                ends = Component.translatable("gui.warbandcraft.routes.too_short");
                color = 0xFF5555;
            }
            String name = font.plainSubstrByWidth(line.name(), 90);
            gfx.drawString(font, name, left + 8, y + 4, line.registered() != null ? 0x55FF55 : 0xFFFFFF);
            String endsText = font.plainSubstrByWidth(ends.getString(), W - 150 - 100);
            gfx.drawString(font, endsText, left + 102, y + 4, color);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.routes.help", state.endpointRadius));
    }

    private void renderRoads(GuiGraphics gfx) {
        List<OpenRoutesMessage.RouteInfo> roads = roads();
        if (roads.isEmpty()) {
            gfx.drawWordWrap(font, Component.translatable("gui.warbandcraft.routes.no_roads"), left + 8, top + ROWS_TOP + 3, W - 16, 0xFFAAAAAA);
            return;
        }
        BlockPos here = state.here == null ? BlockPos.ZERO : state.here.pos();
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < roads.size(); i++) {
            OpenRoutesMessage.RouteInfo r = roads.get(scroll + i);
            int y = top + ROWS_TOP + i * ROW;
            if (i % 2 == 0) gfx.fill(left + 4, y - 2, left + W - 4, y + ROW - 2, 0x20FFFFFF);
            // El otro extremo: el que está más lejos de este Campamento
            BlockPos other = r.a().distSqr(here) > r.b().distSqr(here) ? r.a() : r.b();
            Component status = r.road() ? Component.translatable("gui.warbandcraft.panel.route_road")
                    : r.percent() >= 0 ? Component.translatable("gui.warbandcraft.panel.route_building", r.percent())
                    : !r.active() ? Component.translatable("gui.warbandcraft.panel.route_inactive")
                    : Component.literal(time(r.ticks()));
            Component text = Component.translatable("gui.warbandcraft.routes.road_line", nodeName(other), status);
            boolean button = !r.road() && r.percent() < 0;
            int maxW = W - 16 - (button ? 100 : 0);
            gfx.drawString(font, font.plainSubstrByWidth(r.name(), 80), left + 8, y + 4, r.road() ? 0x55FF55 : 0xFFFFFF);
            gfx.drawString(font, font.plainSubstrByWidth(text.getString(), maxW - 86), left + 92, y + 4,
                    r.percent() >= 0 ? 0xFFD86B : r.active() ? 0xBBBBBB : 0xFF5555);
        }
        footer(gfx, Component.translatable("gui.warbandcraft.routes.roads_help"));
    }

    private void footer(GuiGraphics gfx, Component text) {
        int fy = top + H - 22;
        gfx.fill(left + 4, fy - 5, left + W - 4, fy - 4, 0xFF6B5A3A);
        gfx.drawWordWrap(font, text, left + 8, fy, W - 16, 0xFF888888);
    }
}
