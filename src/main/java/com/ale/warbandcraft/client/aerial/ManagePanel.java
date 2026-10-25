package com.ale.warbandcraft.client.aerial;

import com.ale.warbandcraft.camp.CampStats;
import com.ale.warbandcraft.network.ManageActionMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.panel.Panel;
import com.ale.warbandcraft.upgrade.Upgrades;
import com.ale.warbandcraft.village.Housing;
import com.ale.warbandcraft.village.VillageManagement;
import com.ale.warbandcraft.village.WorkZones;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * H16: el modo Gestión de la vista aérea. Un panel a la izquierda (el mundo se sigue viendo) con sus pestañas:
 * Resumen, Aldeanos, Trabajo, Construir (H22: plantar edificios a mano), Rutas, Casas, Tropas y Pueblo. Resalta en el mundo la casa del almacén y los aldeanos libres
 * (en Casas: los aldeanos sin cama, las camas libres y un terreno sugerido para construir).
 */
final class ManagePanel {
    static final int X = 6, Y = 30, W = 300;
    private static final int ROW = 18;
    private static final int TAB_SUMMARY = 0, TAB_VILLAGERS = 1, TAB_WORK = 2, TAB_UPGRADES = 3, TAB_ROUTES = 4, TAB_HOUSING = 5, TAB_TROOPS = 6, TAB_GROWTH = 7;
    private static final String[] TABS = {"Resumen", "Aldeanos", "Trabajo", "Construir", "Rutas", "Casas", "Tropas", "Pueblo"};
    private static final String[] JOBS = {"Granjero", "Leñador", "Minero", "Pescador", "Ganadero", "Cocinero", "Herrero", "Tabernero", "Mercader"};
    private static final int VILLAGERS_VISIBLE = 6, UPGRADES_VISIBLE = 7, WORKERS_VISIBLE = 5, AREAS_VISIBLE = 5;
    private static final int GOLD = 0xFFFFD24A;
    /** Pestaña Tropas: filas visibles y su alto. */
    private static final int TROOPS_VISIBLE = 7, TROOP_ROW = 24;

    VillageManagement.Data data;
    Component feedback = Component.empty();
    private int tab = TAB_SUMMARY;
    private int page;
    @Nullable
    UUID selected;
    private int areaPage;
    /** El trabajador al que se le está marcando la zona (null = no se marca) y su oficio (ordinal de Job). */
    @Nullable
    UUID marking;
    int markingJob = -1;
    /** H22: el edificio que se está plantando (id de la mejora, null = ninguno), su giro y la categoría de Construir. */
    @Nullable
    String placing;
    int placeRot;
    private int buildCat;
    /** H22: la última vista previa pedida y la que llegó (clave "id,x,z,giro"). */
    private String sentKey = "", previewKey = "", previewText = "";
    private long sentAt;
    private boolean previewOk;
    private int previewY;

    ManagePanel(VillageManagement.Data data) {
        this.data = data;
    }

    int height(int screenHeight) {
        return screenHeight - Y - 2 * 22 - 10;
    }

    boolean contains(double x, double y, int screenHeight) {
        return x >= X && x <= X + W && y >= Y && y <= Y + height(screenHeight);
    }

    private static void send(ManageActionMessage.Action action, BlockPos pos, @Nullable UUID target, int value, String text) {
        ModNetworking.CHANNEL.sendToServer(new ManageActionMessage(action, pos, target, value, text));
    }

    // ------------------------------------------------------------------ botones

    void init(Consumer<AbstractWidget> add, Runnable rebuild, int screenHeight) {
        int tw = (W - 8) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            Button b = Button.builder(Component.literal(TABS[i]), x -> {
                tab = t;
                page = 0;
                rebuild.run();
            }).bounds(X + 4 + i * tw, Y + 16, tw - 2, 16).build();
            b.active = tab != i;
            add.accept(b);
        }
        int top = Y + 38;
        BlockPos c = data.center();
        switch (tab) {
            case TAB_VILLAGERS -> {
                List<VillageManagement.VillagerRow> free = data.free();
                page = Math.max(0, Math.min(page, Math.max(0, free.size() - VILLAGERS_VISIBLE)));
                for (int i = 0; i < VILLAGERS_VISIBLE && page + i < free.size(); i++) {
                    VillageManagement.VillagerRow v = free.get(page + i);
                    boolean sel = v.id().equals(selected);
                    add.accept(Button.builder(Component.literal((sel ? "▶ " : "") + v.name().getString() + " " + (page + i + 1)
                                    + (v.employed() ? " (con oficio)" : "")),
                            x -> {
                                selected = v.id();
                                rebuild.run();
                            }).bounds(X + 4, top + 12 + i * ROW, W - 30, 16).build());
                }
                if (free.size() > VILLAGERS_VISIBLE) {
                    add.accept(Button.builder(Component.literal("▲"), x -> {
                        page = Math.max(0, page - VILLAGERS_VISIBLE);
                        rebuild.run();
                    }).bounds(X + W - 24, top + 12, 20, 16).build());
                    add.accept(Button.builder(Component.literal("▼"), x -> {
                        page += VILLAGERS_VISIBLE;
                        rebuild.run();
                    }).bounds(X + W - 24, top + 12 + ROW, 20, 16).build());
                }
                int jy = top + 16 + VILLAGERS_VISIBLE * ROW;
                int jw = (W - 8) / 3;
                for (int j = 0; j < JOBS.length; j++) {
                    int job = j;
                    Button b = Button.builder(Component.literal(JOBS[j]), x -> {
                        if (selected != null) send(ManageActionMessage.Action.ASSIGN, c, selected, job, "");
                        selected = null;
                    }).bounds(X + 4 + (j % 3) * jw, jy + (j / 3) * ROW, jw - 2, 16).build();
                    b.active = selected != null && free.stream().anyMatch(v -> v.id().equals(selected));
                    add.accept(b);
                }
                // H21: Auto-asignar (los libres van solos a las zonas sin trabajador)
                boolean auto = data.growth().autoAssign();
                Button autoB = Button.builder(Component.literal("Auto-asignar a zonas vacías: " + (auto ? "SÍ" : "NO")),
                        x -> send(ManageActionMessage.Action.AUTO_ASSIGN, c, null, auto ? 0 : 1, ""))
                        .bounds(X + 4, jy + ((JOBS.length + 2) / 3) * ROW, W - 8, 16).build();
                autoB.active = !data.castle();
                add.accept(autoB);
            }
            case TAB_WORK -> {
                List<VillageManagement.WorkerRow> ws = data.workers();
                page = Math.max(0, Math.min(page, Math.max(0, ws.size() - WORKERS_VISIBLE)));
                for (int i = 0; i < WORKERS_VISIBLE && page + i < ws.size(); i++) {
                    VillageManagement.WorkerRow w = ws.get(page + i);
                    int ry = top + 12 + i * ROW;
                    Button zone = Button.builder(Component.literal("Zona"), x -> {
                        marking = w.id();
                        markingJob = w.jobIndex();
                        rebuild.run();
                    }).bounds(X + W - 86, ry, 40, 16).build();
                    zone.active = w.jobIndex() >= 0;
                    add.accept(zone);
                    Button auto = Button.builder(Component.literal("Auto"), x -> send(ManageActionMessage.Action.ZONE_AUTO, c, w.id(), 0, ""))
                            .bounds(X + W - 44, ry, 40, 16).build();
                    auto.active = w.jobIndex() >= 0;
                    add.accept(auto);
                }
                pager(add, rebuild, ws.size(), WORKERS_VISIBLE, top + 14 + WORKERS_VISIBLE * ROW, true);
                int ay = top + 40 + WORKERS_VISIBLE * ROW;
                List<WorkZones.AreaRow> areas = data.areas();
                areaPage = Math.max(0, Math.min(areaPage, Math.max(0, areas.size() - AREAS_VISIBLE)));
                for (int i = 0; i < AREAS_VISIBLE && areaPage + i < areas.size(); i++) {
                    WorkZones.AreaRow a = areas.get(areaPage + i);
                    add.accept(Button.builder(Component.literal("Borrar"), x -> send(ManageActionMessage.Action.ZONE_DELETE, c, a.id(), 0, ""))
                            .bounds(X + W - 50, ay + 12 + i * ROW, 46, 16).build());
                }
                pager(add, rebuild, areas.size(), AREAS_VISIBLE, ay + 14 + AREAS_VISIBLE * ROW, false);
            }
            case TAB_UPGRADES -> {   // H22: Construir
                String[] cats = {"Edificios", "Defensa", "Terreno", "Otros"};
                int cw = (W - 8) / cats.length;
                for (int k = 0; k < cats.length; k++) {
                    int cat = k;
                    Button b = Button.builder(Component.literal(cats[k]), x -> {
                        buildCat = cat;
                        page = 0;
                        rebuild.run();
                    }).bounds(X + 4 + k * cw, top, cw - 2, 14).build();
                    b.active = buildCat != k;
                    add.accept(b);
                }
                if (data.housing().jobPercent() >= 0) {
                    add.accept(Button.builder(Component.literal("Cancelar obra"), x -> send(ManageActionMessage.Action.CANCEL_JOB, c, null, 0, ""))
                            .bounds(X + W - 90, top + 18, 86, 14).build());
                }
                List<Panel.UpgradeRow> rows = buildRows();
                int listTop = top + 36;
                page = Math.max(0, Math.min(page, Math.max(0, rows.size() - UPGRADES_VISIBLE)));
                for (int i = 0; i < UPGRADES_VISIBLE && page + i < rows.size(); i++) {
                    Panel.UpgradeRow u = rows.get(page + i);
                    if (u.state() != Upgrades.State.AVAILABLE.ordinal()) continue;
                    boolean plant = plantable(u.id());
                    add.accept(Button.builder(Component.literal(plant ? toolLabel(u.id()) : u.cost() + " esm."), x -> {
                        if (plant) {
                            startPlacing(u.id());
                            rebuild.run();
                        } else {
                            send(ManageActionMessage.Action.BUILD, c, null, 0, u.id());
                        }
                    }).bounds(X + W - 56, listTop + i * 24, 50, 16).build());
                }
                if (rows.size() > UPGRADES_VISIBLE) {
                    int by = listTop + 2 + UPGRADES_VISIBLE * 24;
                    add.accept(Button.builder(Component.literal("▲"), x -> {
                        page = Math.max(0, page - UPGRADES_VISIBLE);
                        rebuild.run();
                    }).bounds(X + 4, by, 20, 16).build());
                    add.accept(Button.builder(Component.literal("▼"), x -> {
                        page += UPGRADES_VISIBLE;
                        rebuild.run();
                    }).bounds(X + 26, by, 20, 16).build());
                }
            }
            case TAB_SUMMARY -> {
                Button grow = Button.builder(Component.literal(expanding ? "Elegí un chunk pegado (Esc: cancelar)"
                                : "Agrandar territorio (" + com.ale.warbandcraft.village.Territory.CHUNK_COST + " esm. por chunk)"), x -> {
                    expanding = !expanding;
                    rebuild.run();
                }).bounds(X + 4, Y + height(screenHeight) - 40, W - 8, 16).build();
                grow.active = data.territory().length < com.ale.warbandcraft.village.Territory.MAX_CHUNKS;
                add.accept(grow);
            }
            case TAB_GROWTH -> {   // H21: raciones e impuestos ◀ ▶
                var g = data.growth();
                if (g.popularity() >= 0) {
                    int[] rows = {top + 52, top + 70};
                    ManageActionMessage.Action[] acts = {ManageActionMessage.Action.RATIONS, ManageActionMessage.Action.TAXES};
                    int[] values = {g.rations(), g.taxes()};
                    for (int k = 0; k < 2; k++) {
                        int value = values[k];
                        ManageActionMessage.Action act = acts[k];
                        Button less = Button.builder(Component.literal("◀"), x -> send(act, c, null, value - 1, "")).bounds(X + W - 4 - 2 * 18, rows[k], 16, 14).build();
                        Button more = Button.builder(Component.literal("▶"), x -> send(act, c, null, value + 1, "")).bounds(X + W - 4 - 18, rows[k], 16, 14).build();
                        less.active = value > 0;
                        more.active = value < 4;
                        add.accept(less);
                        add.accept(more);
                    }
                }
            }
            case TAB_HOUSING -> {
                Housing.Data h = data.housing();
                Button now = Button.builder(Component.literal("Plantar una casa (" + h.cost() + " esm. + materiales)"), x -> {
                    startPlacing(com.ale.warbandcraft.upgrade.Upgrade.HOUSE.id);
                    rebuild.run();
                }).bounds(X + 4, top, W - 8, 16).build();
                now.active = h.jobPercent() < 0;
                add.accept(now);
            }
            case TAB_TROOPS -> {   // pasar tropas entre el barracón y el cuartel
                List<com.ale.warbandcraft.village.Quarters.TroopRow> rows = data.troops();
                page = Math.max(0, Math.min(page, Math.max(0, rows.size() - TROOPS_VISIBLE)));
                for (int i = 0; i < TROOPS_VISIBLE && page + i < rows.size(); i++) {
                    com.ale.warbandcraft.village.Quarters.TroopRow r = rows.get(page + i);
                    int ry = top + 26 + i * TROOP_ROW;
                    if (com.ale.warbandcraft.village.Tavern.ROW.equals(r.key())) {   // H19: los de la taberna → cuartel / barracón, Auto
                        Button q = Button.builder(Component.literal("→C"), x -> send(ManageActionMessage.Action.TO_QUARTERS, c, null, 1000, r.key()))
                                .bounds(X + W - 4 - 3 * 25, ry, 23, 16).build();
                        q.active = r.barracks() > 0 && data.quarters() < data.quartersCapacity();
                        Button bk = Button.builder(Component.literal("→B"), x -> send(ManageActionMessage.Action.TO_BARRACKS, c, null, 1000, r.key()))
                                .bounds(X + W - 4 - 2 * 25, ry, 23, 16).build();
                        bk.active = r.barracks() > 0 && data.soldiers() < data.capacity();
                        Button auto = Button.builder(Component.literal(r.quarters() > 0 ? "A:sí" : "A:no"),
                                x -> send(ManageActionMessage.Action.TAVERN_AUTO, c, null, r.quarters() > 0 ? 0 : 1, "")).bounds(X + W - 4 - 25, ry, 23, 16).build();
                        auto.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                                "Auto-reclutar: cada mañana los de la taberna pasan solos al cuartel y después al barracón")));
                        add.accept(q);
                        add.accept(bk);
                        add.accept(auto);
                        continue;
                    }
                    String[] labels = {"→1", "→T", "←1", "←T"};
                    for (int k = 0; k < 4; k++) {
                        boolean in = k < 2;
                        int amount = k % 2 == 0 ? 1 : 1000;
                        Button b = Button.builder(Component.literal(labels[k]), x -> send(in ? ManageActionMessage.Action.TO_QUARTERS
                                : ManageActionMessage.Action.TO_BARRACKS, c, null, amount, r.key())).bounds(X + W - 4 - (4 - k) * 25, ry, 23, 16).build();
                        b.active = in ? r.barracks() > 0 && data.quarters() < data.quartersCapacity() : r.quarters() > 0;
                        add.accept(b);
                    }
                }
                int by = top + 28 + TROOPS_VISIBLE * TROOP_ROW;
                if (rows.size() > TROOPS_VISIBLE) {
                    add.accept(Button.builder(Component.literal("▲"), x -> {
                        page = Math.max(0, page - TROOPS_VISIBLE);
                        rebuild.run();
                    }).bounds(X + 4, by, 20, 14).build());
                    add.accept(Button.builder(Component.literal("▼"), x -> {
                        page += TROOPS_VISIBLE;
                        rebuild.run();
                    }).bounds(X + 26, by, 20, 14).build());
                }
                int half = (W - 8) / 2;
                Button all = Button.builder(Component.literal("Llenar el cuartel"),
                        x -> send(ManageActionMessage.Action.TO_QUARTERS, c, null, 1000, "*")).bounds(X + 4, by + 18, half - 2, 16).build();
                all.active = data.soldiers() > 0 && data.quarters() < data.quartersCapacity();
                add.accept(all);
                Button back = Button.builder(Component.literal("Todos al barracón"),
                        x -> send(ManageActionMessage.Action.TO_BARRACKS, c, null, 1000, "*")).bounds(X + 4 + half, by + 18, half - 2, 16).build();
                back.active = data.quarters() > 0;
                add.accept(back);
            }
            case TAB_ROUTES -> {
                Button b = Button.builder(Component.literal("Mandar almacén"), x -> send(ManageActionMessage.Action.SEND_STOCK, c, null, 0, ""))
                        .bounds(X + 4, top + 12, W - 8, 16).build();
                b.active = !data.sendTo().getString().isEmpty();
                add.accept(b);
                // H24: el Mercado (envíos programados, ventas, Lonja)
                add.accept(Button.builder(Component.literal("Mercado: ventas, envíos programados y Lonja"),
                        x -> ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.MarketActionMessage(
                                com.ale.warbandcraft.network.MarketActionMessage.Action.OPEN, c, null, null, "", 0, 0)))
                        .bounds(X + 4, top + 32, W - 8, 16).build());
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ dibujo

    void render(GuiGraphics gfx, Font font, int screenHeight) {
        int h = height(screenHeight);
        gfx.fill(X, Y, X + W, Y + h, 0xD0201810);
        gfx.drawString(font, font.plainSubstrByWidth(data.name().getString(), W - 8), X + 4, Y + 4, GOLD);
        int top = Y + 38;
        switch (tab) {
            case TAB_SUMMARY -> renderSummary(gfx, font, top);
            case TAB_VILLAGERS -> renderVillagers(gfx, font, top);
            case TAB_WORK -> renderWork(gfx, font, top);
            case TAB_UPGRADES -> renderUpgrades(gfx, font, top);
            case TAB_ROUTES -> renderRoutes(gfx, font, top);
            case TAB_HOUSING -> renderHousing(gfx, font, top);
            case TAB_TROOPS -> renderTroops(gfx, font, top);
            case TAB_GROWTH -> renderGrowth(gfx, font, top);
            default -> {
            }
        }
        if (!feedback.getString().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(feedback, W - 8);
            int fy = Y + h - 4 - lines.size() * 10;
            for (net.minecraft.util.FormattedCharSequence l : lines) {
                gfx.drawString(font, l, X + 4, fy, 0xFFFFFFFF);
                fy += 10;
            }
        }
    }

    private int line(GuiGraphics gfx, Font font, String text, int y, int color) {
        gfx.drawString(font, font.plainSubstrByWidth(text, W - 8), X + 4, y, color);
        return y + 11;
    }

    private void renderSummary(GuiGraphics gfx, Font font, int y) {
        int[] s = data.stats();
        y = line(gfx, font, "Almacén", y, GOLD);
        if (s == null) {
            y = line(gfx, font, "Todavía no hay almacén (acercate a la aldea).", y, 0xFFAAAAAA);
        } else {
            y = line(gfx, font, "Esmeraldas: " + s[CampStats.EMERALDS], y, 0xFFFFFFFF);
            String days = s[CampStats.FOOD_DAYS] < 0 ? "" : " (" + s[CampStats.FOOD_DAYS] + " días)";
            y = line(gfx, font, "Comida: " + s[CampStats.FOOD] + days, y, 0xFFFFFFFF);
            y = line(gfx, font, "Armamento: " + s[CampStats.ARMAMENT], y, 0xFFFFFFFF);
            y = line(gfx, font, "Herreros: " + s[CampStats.SMITHS] + " · Trabajadores: " + s[CampStats.WORKERS], y, 0xFFFFFFFF);
        }
        y += 4;
        y = line(gfx, font, "Por día", y, GOLD);
        y = line(gfx, font, data.daily().emeralds() + " esmeraldas · " + data.daily().food() + " panes · "
                + data.daily().armament() + " armamento", y, 0xFFFFFFFF);
        // Pedido de Ale: lo que producen los trabajadores por día (también cuando la aldea no está cargada)
        List<net.minecraft.util.FormattedCharSequence> made = font.split(Component.literal("Trabajadores: " + data.production().getString()), W - 8);
        for (int i = 0; i < made.size() && i < 2; i++) {
            gfx.drawString(font, made.get(i), X + 4, y, 0xFF9FE870);
            y += 11;
        }
        y += 4;
        y = line(gfx, font, "Guarnición", y, GOLD);
        y = line(gfx, font, "Barracón: " + data.soldiers() + " / " + data.capacity(), y, 0xFFFFFFFF);
        y = line(gfx, font, "Cuartel: " + data.quarters() + " / " + data.quartersCapacity() + " (torres y patrulla; pestaña Tropas)", y, 0xFFFFFFFF);
        y = line(gfx, font, "Guardias: " + data.guards(), y, 0xFFFFFFFF);
        y += 4;
        y = line(gfx, font, "Población: " + (data.free().size() + data.workers().size()), y, GOLD);
        y = line(gfx, font, "Aldeanos libres: " + (data.free().size() - data.employed()) + " · con oficio: " + data.employed()
                + " · trabajadores: " + data.workers().size(), y, 0xFFFFFFFF);
        if (data.housing().homeless() > 0) {
            y = line(gfx, font, "Sin cama: " + data.housing().homeless() + " (pestaña Casas)", y, 0xFFFF7777);
        }
        y = line(gfx, font, "Territorio: " + data.territory().length + " / " + com.ale.warbandcraft.village.Territory.MAX_CHUNKS
                + " chunks (borde celeste)", y, 0xFFFFFFFF);
        if (data.invasionTicks() >= 0) {
            y += 4;
            long sec = data.invasionTicks() / 20;
            line(gfx, font, "¡Invasión de " + data.invader().getString() + " en " + sec / 60 + ":" + String.format("%02d", sec % 60) + "!",
                    y, 0xFFFF5555);
        }
        if (data.storehouse() != null) {
            BlockPos p = data.storehouse();
            line(gfx, font, "Almacén en " + p.getX() + ", " + p.getY() + ", " + p.getZ() + " (resaltado)", y + 16, 0xFFAAAAAA);
        }
    }

    private void renderVillagers(GuiGraphics gfx, Font font, int y) {
        long free = data.free().stream().filter(v -> !v.employed()).count();
        line(gfx, font, "Libres: " + free + " (elegí uno, o click en el mundo). Con oficio: al final (pierden sus tratos)", y, GOLD);
        line(gfx, font, "Tus trabajadores y sus zonas: pestaña Trabajo.", y + 16 + VILLAGERS_VISIBLE * ROW + 4 * ROW + 4, 0xFFAAAAAA);
    }

    private void renderWork(GuiGraphics gfx, Font font, int y) {
        line(gfx, font, "Tus trabajadores: " + data.workers().size() + " (Zona: arrastrá en el piso)", y, GOLD);
        for (int i = 0; i < WORKERS_VISIBLE && page + i < data.workers().size(); i++) {
            VillageManagement.WorkerRow w = data.workers().get(page + i);
            boolean mark = w.id().equals(marking);
            int ry = y + 16 + i * ROW;
            String job = (mark ? "▶ " : "") + w.job().getString() + " · ";
            gfx.drawString(font, job, X + 4, ry, mark ? GOLD : 0xFFFFFFFF);
            int sx = X + 4 + font.width(job);
            gfx.drawString(font, font.plainSubstrByWidth(w.status().getString(), Math.max(0, X + W - 90 - sx)), sx, ry, w.statusColor());
        }
        int ay = y + 40 + WORKERS_VISIBLE * ROW;
        line(gfx, font, "Zonas de la aldea: " + data.areas().size(), ay, GOLD);
        for (int i = 0; i < AREAS_VISIBLE && areaPage + i < data.areas().size(); i++) {
            WorkZones.AreaRow a = data.areas().get(areaPage + i);
            int ry = ay + 16 + i * ROW;
            gfx.fill(X + 4, ry, X + 10, ry + 6, a.color());
            int w = a.max().getX() - a.min().getX() + 1, d = a.max().getZ() - a.min().getZ() + 1;
            String kind = a.kind().getString() + " " + w + "×" + d;
            gfx.drawString(font, font.plainSubstrByWidth(kind, W - 70), X + 14, ry, 0xFFFFFFFF);
            int px = X + 14 + font.width(kind + " · ");
            if (!a.production().getString().isEmpty() && px < X + W - 60) {   // pedido de Ale: cuánto produce por día
                gfx.drawString(font, font.plainSubstrByWidth("· " + a.production().getString(), X + W - 56 - px + font.width("· ")),
                        px - font.width("· "), ry, 0xFF9FE870);
            }
        }
    }

    /** ▲ ▼ para una lista larga ({@code workers}: la de trabajadores; si no, la de zonas). */
    private void pager(Consumer<AbstractWidget> add, Runnable rebuild, int count, int visible, int y, boolean workers) {
        if (count <= visible) return;
        add.accept(Button.builder(Component.literal("▲"), x -> {
            if (workers) page = Math.max(0, page - visible);
            else areaPage = Math.max(0, areaPage - visible);
            rebuild.run();
        }).bounds(X + 4, y, 20, 14).build());
        add.accept(Button.builder(Component.literal("▼"), x -> {
            if (workers) page += visible;
            else areaPage += visible;
            rebuild.run();
        }).bounds(X + 26, y, 20, 14).build());
    }

    /** H22: ¿esta fila se planta / dibuja / marca en el mundo en vez de encargarse con un botón? */
    private boolean plantable(String id) {
        if (isTool(id)) return true;
        com.ale.warbandcraft.upgrade.Upgrade up = com.ale.warbandcraft.upgrade.Upgrade.byId(id);
        return up != null && up.handPlaced() && !up.isLevelUp() && !data.castle();
    }

    /** Las herramientas de Defensa y Terreno (H22b/c): "wall:1", "gate:2", "tower:1", "earth:0", "earth:1", "demolish". */
    private static boolean isTool(String id) {
        return id.contains(":") || id.equals("demolish");
    }

    private static String toolLabel(String id) {
        if (id.startsWith("wall:")) return "Dibujar";
        if (id.startsWith("earth:")) return "Marcar";
        if (id.equals("demolish")) return "Elegir";
        return "Plantar";
    }

    /** 0 = edificios (y casas), 1 = defensa, 2 = terreno, 3 = otros (pueblo/ciudad, caminos). */
    private static int category(String id) {
        com.ale.warbandcraft.upgrade.Upgrade up = com.ale.warbandcraft.upgrade.Upgrade.byId(id);
        if (up == null) return 3;
        return switch (up) {
            case PALISADE, WALL, TOWERS, REPAIR -> 1;
            case TOWN, CITY -> 3;
            default -> 0;
        };
    }

    /** Una fila de herramienta: bloqueada si hay obra o falta el taller del nivel. */
    private Panel.UpgradeRow toolRow(String id, String name, String effect, int cost, int builder) {
        boolean busy = data.housing().jobPercent() >= 0;
        int has = data.upgrades() == null ? 0 : data.upgrades().builder();
        Component reason = busy ? Component.literal("hay una obra") : has < builder ? Component.literal("pide taller nivel " + builder) : Component.empty();
        boolean locked = busy || has < builder;
        return new Panel.UpgradeRow(id, Component.literal(name), Component.literal(effect), cost,
                (locked ? Upgrades.State.LOCKED : Upgrades.State.AVAILABLE).ordinal(), reason, 0);
    }

    /** Las filas de la categoría elegida; en Edificios, primero la casa; en Defensa y Terreno, las herramientas. */
    private List<Panel.UpgradeRow> buildRows() {
        List<Panel.UpgradeRow> out = new java.util.ArrayList<>();
        Panel.VillageDetail v = data.upgrades();
        if (data.castle()) {
            if (v != null) for (Panel.UpgradeRow u : v.upgrades()) if (category(u.id()) == buildCat || buildCat == 0) out.add(u);
            return out;
        }
        switch (buildCat) {
            case 0 -> out.add(toolRow(com.ale.warbandcraft.upgrade.Upgrade.HOUSE.id, "Casa", "2 camas: llega gente nueva (pestaña Casas)",
                    com.ale.warbandcraft.upgrade.Upgrade.HOUSE.cost, 0));
            case 1 -> {
                out.add(toolRow("wall:1", "Empalizada (muralla nivel 1)", "Troncos de 3: click por punto, Enter encarga", 2, 1));
                out.add(toolRow("wall:2", "Muralla de piedra (nivel 2)", "Ladrillo de 4 con almenas; reemplaza la empalizada", 4, 2));
                out.add(toolRow("gate:1", "Portón de madera (control 1)", "Sobre la muralla: se abre solo para los tuyos", 4, 1));
                out.add(toolRow("gate:2", "Portón de piedra (control 2)", "Más alto y fuerte; va sobre uno de madera", 8, 2));
                out.add(toolRow("tower:1", "Torre de madera (nivel 1)", "5×5 de troncos: arqueros del cuartel arriba", 6, 1));
                out.add(toolRow("tower:2", "Torre de piedra (nivel 2)", "Va encima de la de madera o suelta", 12, 2));
            }
            case 2 -> {
                out.add(toolRow("earth:0", "Allanar", "Arrastrá un rectángulo: corta y rellena a la altura del 1er click", 1, 0));
                out.add(toolRow("earth:1", "Rellenar con tierra", "Arrastrá: solo sube (tierra del almacén)", 1, 0));
                out.add(toolRow("demolish", "Demoler", "Click en un edificio, torre o tramo de muralla: lo que se saca va al almacén", 0, 0));
            }
            default -> {
            }
        }
        if (v != null) for (Panel.UpgradeRow u : v.upgrades()) if (category(u.id()) == buildCat) out.add(u);
        return out;
    }

    private void renderUpgrades(GuiGraphics gfx, Font font, int y) {
        Housing.Data h = data.housing();
        if (h.jobPercent() >= 0) line(gfx, font, "Obra: " + h.job().getString() + " · " + h.jobPercent() + "%", y + 21, GOLD);
        else line(gfx, font, "Sin obra. Pagan esmeraldas y materiales (almacén, después inventario).", y + 21, 0xFFAAAAAA);
        List<Panel.UpgradeRow> rows = buildRows();
        int listTop = y + 36;
        for (int i = 0; i < UPGRADES_VISIBLE && page + i < rows.size(); i++) {
            Panel.UpgradeRow u = rows.get(page + i);
            Upgrades.State st = Upgrades.State.values()[u.state()];
            int ry = listTop + i * 24;
            String status = switch (st) {
                case DONE -> " · hecha";
                case BUILDING -> " · obra " + u.percent() + "%";
                case AVAILABLE -> plantable(u.id()) ? " · " + (u.cost() > 0 ? u.cost() + " esm. + " : "") + "materiales" : " · + materiales";
                case LOCKED -> " · " + u.reason().getString();
            };
            int color = switch (st) {
                case DONE -> 0xFF55FF55;
                case BUILDING -> GOLD;
                case AVAILABLE -> 0xFFFFFFFF;
                case LOCKED -> 0xFF888888;
            };
            int right = st == Upgrades.State.AVAILABLE ? 60 : 8;
            gfx.drawString(font, font.plainSubstrByWidth(u.name().getString() + status, W - right), X + 4, ry, color);
            gfx.drawString(font, font.plainSubstrByWidth(u.effect().getString(), W - right), X + 4, ry + 10, 0xFFAAAAAA);
        }
        int hy = listTop + 22 + UPGRADES_VISIBLE * 24;
        wrap(gfx, font, placing != null ? hint()
                : "Plantar: elegís el lugar en el mundo (el terreno se allana solo). Lo que se saca va al almacén.", hy, 0xFFAAAAAA);
    }

    private void renderTroops(GuiGraphics gfx, Font font, int y) {
        line(gfx, font, "Barracón: " + data.soldiers() + " / " + data.capacity() + " · Cuartel: " + data.quarters() + " / "
                + data.quartersCapacity(), y, GOLD);
        line(gfx, font, "→ al cuartel · ← al barracón · T = todos. Sueltos: " + data.patrolling() + " · en torres: " + data.onTowers()
                + " / " + data.towerPosts() + " puestos", y + 12, 0xFFAAAAAA);
        List<com.ale.warbandcraft.village.Quarters.TroopRow> rows = data.troops();
        if (rows.isEmpty()) line(gfx, font, "No hay soldados en el barracón ni en el cuartel.", y + 28, 0xFFAAAAAA);
        for (int i = 0; i < TROOPS_VISIBLE && page + i < rows.size(); i++) {
            com.ale.warbandcraft.village.Quarters.TroopRow r = rows.get(page + i);
            int ry = y + 26 + i * TROOP_ROW;
            int w = W - 8 - 4 * 25;
            gfx.drawString(font, font.plainSubstrByWidth(r.label().getString(), w), X + 4, ry, 0xFFFFFFFF);
            String second = com.ale.warbandcraft.village.Tavern.ROW.equals(r.key())
                    ? "Esperando " + r.barracks() + " · Auto-reclutar " + (r.quarters() > 0 ? "sí" : "no")
                    : "Barracón " + r.barracks() + " · Cuartel " + r.quarters();
            gfx.drawString(font, font.plainSubstrByWidth(second, w), X + 4, ry + 10, 0xFFAAAAAA);
        }
        int by = y + 28 + TROOPS_VISIBLE * TROOP_ROW;
        wrap(gfx, font, "Arqueros a las torres, el resto patrulla el territorio. Los manejás solo durante una invasión; después vuelven solos a lo suyo.",
                by + 40, 0xFFAAAAAA);
    }

    private static final String[] RATIONS = {"Ninguna", "Media", "Normal", "Abundante", "Doble"};
    private static final String[] TAXES = {"Sin impuestos", "Bajos", "Normales", "Altos", "Abusivos"};

    /** H21: popularidad, población y el informe de factores (como en Stronghold). */
    private void renderGrowth(GuiGraphics gfx, Font font, int y) {
        var g = data.growth();
        if (g.popularity() < 0) {
            line(gfx, font, "Los castillos no crecen.", y, 0xFFAAAAAA);
            return;
        }
        int color = g.popularity() >= com.ale.warbandcraft.village.Popularity.THRESHOLD ? 0xFF55FF55 : 0xFFFF7777;
        String arrow = g.delta() > 0 ? "  ▲ +" + g.delta() + "/min" : g.delta() < 0 ? "  ▼ " + g.delta() + "/min" : "  = 0/min";
        line(gfx, font, "Popularidad: " + g.popularity() + " / 100" + arrow, y, color);
        int bw = W - 8;
        gfx.fill(X + 4, y + 11, X + 4 + bw, y + 15, 0xFF333333);
        gfx.fill(X + 4, y + 11, X + 4 + bw * g.popularity() / 100, y + 15, color);
        int mid = X + 4 + bw * com.ale.warbandcraft.village.Popularity.THRESHOLD / 100;
        gfx.fill(mid, y + 10, mid + 1, y + 16, 0xFFFFFFFF);
        line(gfx, font, "Habitantes: " + (g.villagers() + g.workers()) + " / " + g.cap() + " (trabajadores " + g.workers()
                + ", sin oficio " + g.free() + ")", y + 20, 0xFFFFFFFF);
        String next = g.arriving() ? (g.villagers() + g.workers() >= g.cap() ? "Tope de habitantes (agrandá la aldea: Pueblo / Ciudad)"
                : "Llega uno en " + g.minutesToNext() + " min (si hay cama libre)") : "Se va uno en " + g.minutesToNext() + " min";
        line(gfx, font, next, y + 32, g.arriving() ? 0xFFAAAAAA : 0xFFFF7777);
        line(gfx, font, "Raciones: " + RATIONS[Math.max(0, Math.min(4, g.rations()))], y + 55, GOLD);
        line(gfx, font, "Impuestos: " + TAXES[Math.max(0, Math.min(4, g.taxes()))], y + 73, GOLD);
        int ry = y + 92;
        ry = line(gfx, font, "Qué la mueve (por minuto):", ry, 0xFFAAAAAA);
        for (var f : g.factors()) {
            String v = (f.value() > 0 ? "+" : "") + f.value();
            int c = f.value() > 0 ? 0xFF55FF55 : f.value() < 0 ? 0xFFFF7777 : 0xFFAAAAAA;
            String label = Component.translatable("gui.warbandcraft.growth.factor." + f.key(), f.arg()).getString();
            gfx.drawString(font, font.plainSubstrByWidth(label, W - 40), X + 8, ry, 0xFFFFFFFF);
            gfx.drawString(font, v, X + W - 6 - font.width(v), ry, c);
            ry += 10;
        }
        wrap(gfx, font, "Con 50 o más llegan aldeanos sin oficio: dales trabajo en Aldeanos. Con menos se van.", ry + 4, 0xFFAAAAAA);
    }

    private void renderRoutes(GuiGraphics gfx, Font font, int y) {
        int ry = y + 34;
        ry = line(gfx, font, data.sendTo().getString().isEmpty() ? "Sin ruta a un Campamento."
                : "Mandar almacén a: " + data.sendTo().getString(), y + 2, data.sendTo().getString().isEmpty() ? 0xFFAAAAAA : 0xFFFFFFFF);
        ry = Math.max(ry, y + 52);   // debajo de los botones Mandar almacén y Mercado
        ry = line(gfx, font, "Desde acá llegás a:", ry, GOLD);
        if (data.routes().isEmpty()) line(gfx, font, "Ningún lugar (dibujá una ruta en el mapa M).", ry, 0xFFAAAAAA);
        for (VillageManagement.RouteRow r : data.routes()) {
            long sec = r.ticks() / 20;
            ry = line(gfx, font, r.name().getString() + " · " + sec / 60 + ":" + String.format("%02d", sec % 60), ry, 0xFFFFFFFF);
        }
    }

    /** Varias líneas cortadas al ancho del panel. */
    private int wrap(GuiGraphics gfx, Font font, String text, int y, int color) {
        for (net.minecraft.util.FormattedCharSequence l : font.split(Component.literal(text), W - 8)) {
            gfx.drawString(font, l, X + 4, y, color);
            y += 10;
        }
        return y + 1;
    }

    private void renderHousing(GuiGraphics gfx, Font font, int y) {
        Housing.Data h = data.housing();
        int white = 0xFFFFFFFF, red = 0xFFFF7777, grey = 0xFFAAAAAA, green = 0xFF55FF55;
        y += 20;   // el botón de arriba
        if (h.jobPercent() >= 0) {
            y = line(gfx, font, "Obra: " + h.job().getString() + " · " + h.jobPercent() + "%", y, GOLD);
        } else if (h.auto() && h.missingBeds() > 0 && h.stockEmeralds() < h.cost()) {
            y = wrap(gfx, font, "Faltan camas pero el almacén no tiene " + h.cost() + " esmeraldas (tiene " + h.stockEmeralds() + ").", y, red);
        } else if (h.auto() && h.missingBeds() > 0) {
            y = line(gfx, font, "El constructor va a empezar una casa en un rato.", y, GOLD);
        }
        y = line(gfx, font, "Casas hechas por el constructor: " + h.houses(), y, grey);
        y += 4;
        y = line(gfx, font, "Población", y, GOLD);
        y = line(gfx, font, "Aldeanos: " + h.adults() + (h.babies() > 0 ? " · chicos: " + h.babies() : ""), y, white);
        y = line(gfx, font, "Sin cama: " + h.homeless() + " (marca roja)", y, h.homeless() > 0 ? red : white);
        y = line(gfx, font, "Sin trabajo: " + h.jobless(), y, h.jobless() > 0 ? red : white);
        y = line(gfx, font, "Sin cama y sin trabajo: " + h.both(), y, h.both() > 0 ? red : white);
        y += 4;
        y = line(gfx, font, "Camas y trabajo", y, GOLD);
        y = line(gfx, font, "Camas: " + h.beds() + " · libres: " + h.freeBeds() + " (marca verde)", y, white);
        y = line(gfx, font, "Puestos de trabajo libres: " + h.freeJobSites(), y, white);
        y += 4;
        y = line(gfx, font, "Qué hacer", y, GOLD);
        int missing = h.missingBeds();
        if (missing > 0) {
            y = wrap(gfx, font, "Faltan " + missing + " camas" + (h.babies() > 0 ? " (contando a los chicos)" : "")
                    + ": hacen falta " + (missing + 1) / 2 + " casas de 2 camas" + (h.auto() ? " (se hacen solas, de a una)." : "."), y, red);
        } else if (h.adults() > 0) {
            y = wrap(gfx, font, "Todos tienen cama. Cada cama libre de más deja nacer un aldeano nuevo (si tienen comida).", y, green);
        }
        if (h.homeless() > 0 && h.freeBeds() > 0) {
            y = wrap(gfx, font, "Hay camas libres sin usar: si en un rato no las toman, no llegan caminando (puerta, escalones).", y, grey);
        }
        if (h.jobless() > 0) {
            y = wrap(gfx, font, h.freeJobSites() >= h.jobless()
                    ? "Hay puestos de trabajo libres: los sin trabajo los toman solos (los tontos no)."
                    : "Para darles trabajo: un oficio en la pestaña Aldeanos, o un bloque de oficio (compostera, atril...).", y, grey);
        }
        if (!h.auto()) {
            y = wrap(gfx, font, "Una casa a mano: cama con un bloque libre al lado, puerta, techo y luz adentro.", y, grey);
        }
        if (h.site() != null) {
            BlockPos p = h.site();
            wrap(gfx, font, "Terreno plano sugerido (9×9, punteado): " + (p.getX() + 4) + ", " + p.getY() + ", " + (p.getZ() + 4), y, white);
        } else {
            wrap(gfx, font, "No encontré un terreno plano libre cerca del centro.", y, grey);
        }
    }

    // ------------------------------------------------------------------ territorio

    /** Agrandando el territorio: el próximo click en el piso compra ese chunk. */
    boolean expanding;
    @Nullable
    private long[] borderKey;
    private List<Vec3[]> border = List.of();

    boolean inTerritory(int cx, int cz) {
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        for (long l : data.territory()) if (l == key) return true;
        return false;
    }

    /** Los tramos del borde del territorio (lados de chunk sin vecino), sobre el piso; se recalcula si cambia. */
    private List<Vec3[]> border() {
        if (borderKey == data.territory()) return border;
        borderKey = data.territory();
        List<Vec3[]> out = new java.util.ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return border = out;
        int y = data.center().getY();
        for (long l : data.territory()) {
            int cx = net.minecraft.world.level.ChunkPos.getX(l), cz = net.minecraft.world.level.ChunkPos.getZ(l);
            int x0 = cx << 4, z0 = cz << 4, x1 = x0 + 16, z1 = z0 + 16;
            if (!inTerritory(cx, cz - 1)) edge(out, mc, x0, z0, x1, z0, y);
            if (!inTerritory(cx, cz + 1)) edge(out, mc, x0, z1, x1, z1, y);
            if (!inTerritory(cx - 1, cz)) edge(out, mc, x0, z0, x0, z1, y);
            if (!inTerritory(cx + 1, cz)) edge(out, mc, x1, z0, x1, z1, y);
        }
        return border = out;
    }

    private static void edge(List<Vec3[]> out, Minecraft mc, int xa, int za, int xb, int zb, int hint) {
        for (int i = 0; i < 16; i += 2) {
            double t0 = i / 16.0, t1 = (i + 2) / 16.0;
            double ax = xa + (xb - xa) * t0, az = za + (zb - za) * t0, bx = xa + (xb - xa) * t1, bz = za + (zb - za) * t1;
            Integer ya = com.ale.warbandcraft.util.Ground.surfaceY(mc.level, (int) Math.floor(ax), (int) Math.floor(az), hint, 24, 24);
            Integer yb = com.ale.warbandcraft.util.Ground.surfaceY(mc.level, (int) Math.floor(bx), (int) Math.floor(bz), hint, 24, 24);
            if (ya == null || yb == null) continue;
            out.add(new Vec3[]{new Vec3(ax, ya + 0.1, az), new Vec3(bx, yb + 0.1, bz)});
        }
    }

    void renderTerritory(GuiGraphics gfx, Camera camera, int sw, int sh) {
        for (Vec3[] seg : border()) dots(gfx, camera, seg[0], seg[1], 0xFF4FE0E0, sw, sh);
    }

    /** El chunk bajo el cursor mientras se agranda: borde verde si se puede comprar (pegado), rojo si no. */
    void renderExpandHover(GuiGraphics gfx, Camera camera, @Nullable Vec3 ground, int sw, int sh) {
        if (ground == null) return;
        int cx = Mth.floor(ground.x) >> 4, cz = Mth.floor(ground.z) >> 4;
        boolean ok = !inTerritory(cx, cz) && (inTerritory(cx + 1, cz) || inTerritory(cx - 1, cz) || inTerritory(cx, cz + 1) || inTerritory(cx, cz - 1));
        int color = ok ? 0xFF55FF55 : 0xFFFF5555;
        double y = ground.y + 0.1, x0 = cx << 4, z0 = cz << 4;
        Vec3[] c = {new Vec3(x0, y, z0), new Vec3(x0 + 16, y, z0), new Vec3(x0 + 16, y, z0 + 16), new Vec3(x0, y, z0 + 16)};
        for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], color, sw, sh);
    }

    // ------------------------------------------------------------------ en el mundo

    /** La casa del almacén (caja dorada con el cartel) y una marca sobre cada aldeano libre. */
    void renderWorld(GuiGraphics gfx, Font font, Camera camera, int sw, int sh) {
        renderTerritory(gfx, camera, sw, sh);
        if (data.houseMin() != null && data.houseMax() != null) {
            drawBox(gfx, camera, data.houseMin(), data.houseMax(), sw, sh);
            Vec3 top = new Vec3((data.houseMin().getX() + data.houseMax().getX() + 1) / 2.0, data.houseMax().getY() + 1.5,
                    (data.houseMin().getZ() + data.houseMax().getZ() + 1) / 2.0);
            double[] s = AerialScreen.worldToScreen(camera, top, sw, sh);
            if (s != null) {
                String label = "Almacén";
                int w = font.width(label);
                gfx.fill((int) s[0] - w / 2 - 3, (int) s[1] - 3, (int) s[0] + w / 2 + 3, (int) s[1] + 10, 0xC0000000);
                gfx.drawCenteredString(font, label, (int) s[0], (int) s[1], GOLD);
            }
        }
        for (WorkZones.AreaRow a : data.areas()) {   // las zonas de trabajo: el borde de la base, con su color
            double x0 = a.min().getX(), z0 = a.min().getZ(), x1 = a.max().getX() + 1, z1 = a.max().getZ() + 1, yb = a.min().getY() + 1;
            Vec3[] c = {new Vec3(x0, yb, z0), new Vec3(x1, yb, z0), new Vec3(x1, yb, z1), new Vec3(x0, yb, z1)};
            for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], a.color(), sw, sh);
            double[] s = AerialScreen.worldToScreen(camera, new Vec3((x0 + x1) / 2, yb + 0.5, (z0 + z1) / 2), sw, sh);
            if (s != null) sign(gfx, font, (int) s[0], (int) s[1], a.kind().getString(), a.color(), a.status().getString(), a.statusColor(),
                    a.production().getString());
        }
        if (tab == TAB_UPGRADES || placing != null) renderSites(gfx, font, camera, sw, sh);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (tab == TAB_HOUSING) {
            renderHousingWorld(gfx, font, camera, sw, sh);
            return;
        }
        for (VillageManagement.VillagerRow v : data.free()) {
            Entity e = villager(v.id());
            if (e == null) continue;
            double[] s = AerialScreen.worldToScreen(camera, e.getEyePosition(1F).add(0, 0.8, 0), sw, sh);
            if (s == null) continue;
            int x = (int) s[0], y = (int) s[1];
            boolean sel = v.id().equals(selected);
            int color = sel ? 0xFF3CD24A : v.employed() ? 0xFF55AAFF : 0xFFFFD24A;   // H21: con oficio de vanilla, en celeste
            gfx.fill(x - 3, y - 6, x + 3, y, color);
            if (sel) gfx.drawCenteredString(font, "elegido", x, y - 16, color);
        }
    }

    /** Pestaña Casas: los aldeanos sin cama (rojo), las camas libres (verde) y el terreno sugerido. */
    private void renderHousingWorld(GuiGraphics gfx, Font font, Camera camera, int sw, int sh) {
        Housing.Data h = data.housing();
        for (UUID id : h.homelessIds()) {
            Entity e = entity(id);
            if (e == null) continue;
            double[] s = AerialScreen.worldToScreen(camera, e.getEyePosition(1F).add(0, 0.8, 0), sw, sh);
            if (s != null) gfx.fill((int) s[0] - 3, (int) s[1] - 6, (int) s[0] + 3, (int) s[1], 0xFFFF5555);
        }
        for (BlockPos b : h.freeBedPos()) {
            double[] s = AerialScreen.worldToScreen(camera, Vec3.atCenterOf(b).add(0, 0.6, 0), sw, sh);
            if (s != null) gfx.fill((int) s[0] - 3, (int) s[1] - 3, (int) s[0] + 3, (int) s[1] + 3, 0xFF55FF55);
        }
        if (h.site() != null) {
            BlockPos p = h.site();
            int size = com.ale.warbandcraft.upgrade.Blueprints.SITE;
            double x0 = p.getX(), z0 = p.getZ(), x1 = x0 + size, z1 = z0 + size, yb = p.getY() + 1.05;
            Vec3[] c = {new Vec3(x0, yb, z0), new Vec3(x1, yb, z0), new Vec3(x1, yb, z1), new Vec3(x0, yb, z1)};
            for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], 0xFFFFFFFF, sw, sh);
            double[] s = AerialScreen.worldToScreen(camera, new Vec3((x0 + x1) / 2, yb + 0.5, (z0 + z1) / 2), sw, sh);
            if (s != null) gfx.drawCenteredString(font, "Casa nueva", (int) s[0], (int) s[1], 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ H22: plantar edificios

    /** Nombre de un terreno de {@code TownData.Town.sites} ("house_3" → Casa). */
    private static String siteLabel(String key) {
        if (key.startsWith("house")) return "Casa";
        if (key.startsWith("tower:")) return "Torre";
        return switch (key) {
            case "builder" -> "Taller del constructor";
            case "tavern" -> "Taberna";
            case "training" -> "Entrenamiento";
            default -> Component.translatable("upgrade.warbandcraft." + key).getString();
        };
    }

    /** Los terrenos de los edificios y torres que ya hay (celeste punteado, con su nombre). */
    private void renderSites(GuiGraphics gfx, Font font, Camera camera, int sw, int sh) {
        for (VillageManagement.SiteRow s : data.sites()) {
            int size = s.size();
            double x0 = s.corner().getX(), z0 = s.corner().getZ(), x1 = x0 + size, z1 = z0 + size, yb = s.corner().getY() + 1.05;
            Vec3[] c = {new Vec3(x0, yb, z0), new Vec3(x1, yb, z0), new Vec3(x1, yb, z1), new Vec3(x0, yb, z1)};
            for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], 0xFF7FC8FF, sw, sh);
            double[] p = AerialScreen.worldToScreen(camera, new Vec3((x0 + x1) / 2, yb + 4, (z0 + z1) / 2), sw, sh);
            if (p != null) sign(gfx, font, (int) p[0], (int) p[1], siteLabel(s.key()), 0xFF7FC8FF, "", 0, "");
        }
    }

    /** Cómo se marca lo que se está plantando. */
    enum Mode { BUILDING, POINT, WALL, DRAG, PICK }

    Mode mode() {
        if (placing == null) return Mode.BUILDING;
        if (placing.startsWith("wall:")) return Mode.WALL;
        if (placing.startsWith("gate:") || placing.startsWith("tower:")) return Mode.POINT;
        if (placing.startsWith("earth:")) return Mode.DRAG;
        if (placing.equals("demolish")) return Mode.PICK;
        return Mode.BUILDING;
    }

    /** Los puntos de la muralla que se está dibujando; el rectángulo de terreno (esquina y altura del primer click). */
    final List<int[]> wallPts = new java.util.ArrayList<>();
    @Nullable
    int[] dragStart;
    int dragY;

    void startPlacing(String id) {
        placing = id;
        placeRot = 0;
        previewKey = "";
        wallPts.clear();
        dragStart = null;
    }

    void stopPlacing() {
        placing = null;
        wallPts.clear();
        dragStart = null;
    }

    String hint() {
        return switch (mode()) {
            case WALL -> "Muralla: click agrega un punto, click derecho saca el último, Enter encarga, Esc cancela";
            case DRAG -> dragStart == null ? "Terreno: click y arrastrá el rectángulo (la altura es la del primer click; rueda ±1). Esc cancela"
                    : "Soltá para encargar · rueda del mouse: altura ±1 (ahora " + dragY + ")";
            case PICK -> "Demoler: click en un edificio, torre (celeste) o tramo de muralla. Esc cancela";
            case POINT -> placing.startsWith("gate:") ? "Portón: click sobre la muralla. Esc cancela" : "Torre: click donde va el centro. Esc cancela";
            default -> "Click en el piso: plantar · R: girar · Esc o click derecho: cancelar";
        };
    }

    private static int blockY(Vec3 ground) {
        return Mth.floor(ground.y - 0.01);
    }

    /** El terreno del edificio, torre o lo demolible bajo el mouse. */
    @Nullable
    private VillageManagement.SiteRow siteAt(@Nullable Vec3 ground) {
        if (ground == null) return null;
        int x = Mth.floor(ground.x), z = Mth.floor(ground.z);
        for (VillageManagement.SiteRow s : data.sites()) {
            if (x >= s.corner().getX() && x < s.corner().getX() + s.size() && z >= s.corner().getZ() && z < s.corner().getZ() + s.size()) return s;
        }
        return null;
    }

    /** Lo que se manda para la vista previa con el mouse en {@code ground}, o null si no hay nada que mostrar. */
    @Nullable
    String placeTarget(@Nullable Vec3 ground) {
        if (placing == null) return null;
        switch (mode()) {
            case WALL -> {
                if (wallPts.isEmpty() && ground == null) return null;
                StringBuilder b = new StringBuilder(placing).append(':');
                for (int[] p : wallPts) b.append(p[0]).append(',').append(p[1]).append(';');
                if (ground != null) b.append(Mth.floor(ground.x)).append(',').append(Mth.floor(ground.z));
                return b.toString();
            }
            case DRAG -> {
                if (dragStart == null || ground == null) return null;
                return placing + ":" + dragStart[0] + "," + dragStart[1] + "," + Mth.floor(ground.x) + "," + Mth.floor(ground.z) + "," + dragY;
            }
            case PICK -> {
                VillageManagement.SiteRow s = siteAt(ground);
                if (s != null) return "demolish:" + s.key();
                return ground == null ? null : "demolish:wall:" + Mth.floor(ground.x) + "," + Mth.floor(ground.z);   // un tramo de muralla
            }
            case POINT -> {
                return ground == null ? null : placing + ":" + Mth.floor(ground.x) + "," + Mth.floor(ground.z);
            }
            default -> {
                if (ground == null) return null;
                int half = placingSize(placing) / 2;
                return placing + "," + (Mth.floor(ground.x) - half) + "," + (Mth.floor(ground.z) - half);
            }
        }
    }

    /** El texto de la muralla dibujada para encargar (sin el mouse), o null si tiene menos de 2 puntos. */
    @Nullable
    String wallOrder() {
        if (mode() != Mode.WALL || wallPts.size() < 2) return null;
        StringBuilder b = new StringBuilder(placing).append(':');
        for (int[] p : wallPts) b.append(p[0]).append(',').append(p[1]).append(';');
        return b.toString();
    }

    void receivePreview(String key, boolean ok, String text, int floorY) {
        previewKey = key;
        previewOk = ok;
        previewText = text;
        previewY = floorY;
    }

    /** El piso (bloque) de una columna del lado del cliente, para dibujar. */
    private double groundTop(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        Integer y = mc.level == null ? null : com.ale.warbandcraft.util.Ground.surfaceY(mc.level, x, z, data.center().getY(), 24, 24);
        return (y == null ? data.center().getY() : y) + 1.05;
    }

    /** Lo que se está marcando: la huella o el trazado (verde = se puede, rojo = no, blanco = esperando) y la cuenta. */
    void renderPlacing(GuiGraphics gfx, Font font, Camera camera, @Nullable Vec3 ground, int mx, int my, int sw, int sh) {
        String target = placeTarget(ground);
        String full = target == null ? "" : target + "," + placeRot;
        long now = System.currentTimeMillis();
        if (target != null && !full.equals(sentKey) && now - sentAt > 200) {   // máx. 5 pedidos por segundo
            sentKey = full;
            sentAt = now;
            send(ManageActionMessage.Action.PREVIEW, data.center(), null, placeRot, target);
        }
        boolean fresh = target != null && full.equals(previewKey);
        int color = !fresh ? 0xFFFFFFFF : previewOk ? 0xFF55FF55 : 0xFFFF5555;
        Mode m = mode();
        switch (m) {
            case WALL -> {
                List<Vec3> pts = new java.util.ArrayList<>();
                for (int[] p : wallPts) pts.add(new Vec3(p[0] + 0.5, groundTop(p[0], p[1]), p[1] + 0.5));
                if (ground != null) pts.add(new Vec3(Mth.floor(ground.x) + 0.5, groundTop(Mth.floor(ground.x), Mth.floor(ground.z)), Mth.floor(ground.z) + 0.5));
                for (int i = 0; i + 1 < pts.size(); i++) {
                    dots(gfx, camera, pts.get(i), pts.get(i + 1), color, sw, sh);
                    dots(gfx, camera, pts.get(i).add(0, 3, 0), pts.get(i + 1).add(0, 3, 0), color & 0x80FFFFFF, sw, sh);
                }
                for (int i = 0; i < wallPts.size(); i++) {
                    double[] s = AerialScreen.worldToScreen(camera, pts.get(i), sw, sh);
                    if (s != null) gfx.fill((int) s[0] - 3, (int) s[1] - 3, (int) s[0] + 3, (int) s[1] + 3, GOLD);
                }
            }
            case DRAG -> {
                if (dragStart != null && ground != null) {
                    double y = dragY + 1.05;
                    double x0 = Math.min(dragStart[0], Mth.floor(ground.x)), x1 = Math.max(dragStart[0], Mth.floor(ground.x)) + 1;
                    double z0 = Math.min(dragStart[1], Mth.floor(ground.z)), z1 = Math.max(dragStart[1], Mth.floor(ground.z)) + 1;
                    Vec3[] c = {new Vec3(x0, y, z0), new Vec3(x1, y, z0), new Vec3(x1, y, z1), new Vec3(x0, y, z1)};
                    for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], color, sw, sh);
                    for (double gx = x0 + 0.5; gx < x1; gx += 2) dots(gfx, camera, new Vec3(gx, y, z0), new Vec3(gx, y, z1), color & 0x60FFFFFF, sw, sh);
                }
            }
            case PICK -> {
                VillageManagement.SiteRow st = siteAt(ground);
                if (st != null) {
                    double x0 = st.corner().getX(), z0 = st.corner().getZ(), x1 = x0 + st.size(), z1 = z0 + st.size(), yb = st.corner().getY() + 1.05;
                    Vec3[] c = {new Vec3(x0, yb, z0), new Vec3(x1, yb, z0), new Vec3(x1, yb, z1), new Vec3(x0, yb, z1)};
                    for (int i = 0; i < 4; i++) {
                        dots(gfx, camera, c[i], c[(i + 1) % 4], 0xFFFF5555, sw, sh);
                        dots(gfx, camera, c[i].add(0, 5, 0), c[(i + 1) % 4].add(0, 5, 0), 0x80FF5555, sw, sh);
                    }
                }
            }
            case POINT -> {
                if (ground != null) {
                    int half = placing.startsWith("tower:") ? 2 : 1;
                    double cx = Mth.floor(ground.x), cz = Mth.floor(ground.z), yb = fresh ? groundTop((int) cx, (int) cz) : ground.y + 0.05;
                    Vec3[] c = {new Vec3(cx - half, yb, cz - half), new Vec3(cx + half + 1, yb, cz - half), new Vec3(cx + half + 1, yb, cz + half + 1),
                            new Vec3(cx - half, yb, cz + half + 1)};
                    for (int i = 0; i < 4; i++) {
                        dots(gfx, camera, c[i], c[(i + 1) % 4], color, sw, sh);
                        dots(gfx, camera, c[i].add(0, half == 2 ? 9 : 4, 0), c[(i + 1) % 4].add(0, half == 2 ? 9 : 4, 0), color & 0x80FFFFFF, sw, sh);
                    }
                }
            }
            default -> renderBuildingGhost(gfx, font, camera, ground, target, fresh, color, sw, sh);
        }
        String name = toolName();
        int ty = my + 14;
        gfx.drawString(font, name, mx + 12, ty, GOLD);
        String text = target == null ? (m == Mode.PICK ? "Pasá el mouse por un edificio celeste" : "") : fresh ? previewText : "…";
        for (net.minecraft.util.FormattedCharSequence l : font.split(Component.literal(text), 260)) {
            ty += 10;
            gfx.drawString(font, l, mx + 12, ty, color);
        }
    }

    private String toolName() {
        if (placing == null) return "";
        if (placing.equals(com.ale.warbandcraft.upgrade.Upgrade.HOUSE.id)) return "Casa";
        for (Panel.UpgradeRow r : buildRowsAll()) if (r.id().equals(placing)) return r.name().getString();
        return Component.translatable("upgrade.warbandcraft." + placing).getString();
    }

    /** Las filas de herramientas de todas las categorías (para el nombre de lo que se planta). */
    private List<Panel.UpgradeRow> buildRowsAll() {
        int keep = buildCat;
        List<Panel.UpgradeRow> all = new java.util.ArrayList<>();
        for (int k = 0; k < 4; k++) {
            buildCat = k;
            all.addAll(buildRows());
        }
        buildCat = keep;
        return all;
    }

    /** El lado del terreno de lo que se planta (la taberna reserva el de su nivel 3). */
    private static int placingSize(String id) {
        com.ale.warbandcraft.upgrade.Upgrade u = com.ale.warbandcraft.upgrade.Upgrade.byId(id);
        return u == null ? com.ale.warbandcraft.upgrade.Blueprints.SITE : u.siteSize();
    }

    /** El terreno del edificio bajo el mouse con su frente. */
    private void renderBuildingGhost(GuiGraphics gfx, Font font, Camera camera, @Nullable Vec3 ground, @Nullable String target, boolean fresh,
                                     int color, int sw, int sh) {
        if (target == null || ground == null) return;
        String[] p = target.split(",");
        int size = placingSize(p[0]);
        double x0 = Integer.parseInt(p[1]), z0 = Integer.parseInt(p[2]), x1 = x0 + size, z1 = z0 + size;
        double yb = fresh ? previewY + 1.05 : ground.y + 0.05;
        Vec3[] c = {new Vec3(x0, yb, z0), new Vec3(x1, yb, z0), new Vec3(x1, yb, z1), new Vec3(x0, yb, z1)};
        for (int i = 0; i < 4; i++) dots(gfx, camera, c[i], c[(i + 1) % 4], color, sw, sh);
        for (int i = 0; i < 4; i++) dots(gfx, camera, c[i].add(0, 4, 0), c[(i + 1) % 4].add(0, 4, 0), color & 0x80FFFFFF, sw, sh);
        // El frente (la puerta): sin girar mira al norte, en el medio del lado
        int m = size - 1, lx = size / 2, lz = -1;
        int fx, fz;
        switch (Math.floorMod(placeRot, 4)) {
            case 1 -> { fx = m - lz; fz = lx; }
            case 2 -> { fx = m - lx; fz = m - lz; }
            case 3 -> { fx = lz; fz = m - lx; }
            default -> { fx = lx; fz = lz; }
        }
        double[] door = AerialScreen.worldToScreen(camera, new Vec3(x0 + fx + 0.5, yb + 0.3, z0 + fz + 0.5), sw, sh);
        if (door != null) {
            gfx.fill((int) door[0] - 3, (int) door[1] - 3, (int) door[0] + 3, (int) door[1] + 3, GOLD);
            gfx.drawCenteredString(font, "frente", (int) door[0], (int) door[1] - 12, GOLD);
        }
    }

    /** Click izquierdo en el mundo mientras se planta. Devuelve el texto a encargar (null = sigue marcando). */
    @Nullable
    String click(@Nullable Vec3 ground) {
        switch (mode()) {
            case WALL -> {
                if (ground != null && wallPts.size() < com.ale.warbandcraft.upgrade.Works.MAX_POINTS) {
                    wallPts.add(new int[]{Mth.floor(ground.x), Mth.floor(ground.z)});
                }
                return null;
            }
            case DRAG -> {
                if (ground != null) {
                    dragStart = new int[]{Mth.floor(ground.x), Mth.floor(ground.z)};
                    dragY = blockY(ground);
                }
                return null;
            }
            default -> {
                return placeTarget(ground);
            }
        }
    }

    /** H18: el cartelito de una zona: su nombre, cómo está su trabajador (qué le falta) y lo que produce por día. */
    private static void sign(GuiGraphics gfx, Font font, int x, int y, String title, int titleColor, String status, int statusColor,
                             String production) {
        int w = Math.max(font.width(title), Math.max(Math.min(font.width(status), 220), Math.min(font.width(production), 220)));
        int h = 10 + (status.isEmpty() ? 0 : 10) + (production.isEmpty() ? 0 : 10);
        gfx.fill(x - w / 2 - 3, y - 3, x + w / 2 + 3, y + h, 0xB0000000);
        gfx.drawCenteredString(font, title, x, y, titleColor);
        int ly = y + 10;
        if (!status.isEmpty()) {
            gfx.drawCenteredString(font, font.plainSubstrByWidth(status, 220), x, ly, statusColor);
            ly += 10;
        }
        if (!production.isEmpty()) gfx.drawCenteredString(font, font.plainSubstrByWidth(production, 220), x, ly, 0xFF9FE870);
    }

    @Nullable
    static Entity villager(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof Villager && e.getUUID().equals(id)) return e;
        return null;
    }

    /** Cualquier entidad cargada en el cliente (los trabajadores sin cama no son aldeanos). */
    @Nullable
    static Entity entity(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(id)) return e;
        return null;
    }

    // ------------------------------------------------------------------ marcar una zona (H18)

    @Nullable
    private String paintKey;
    private List<Vec3> paint = List.of();

    /** ¿{@code (x, z)} se puede marcar? Adentro del territorio (sin territorio: a {@link WorkZones#REACH} del centro). */
    boolean markable(int x, int z) {
        if (data.territory().length == 0) {
            double dx = x - data.center().getX(), dz = z - data.center().getZ();
            return dx * dx + dz * dz <= (double) WorkZones.REACH * WorkZones.REACH;
        }
        return inTerritory(x >> 4, z >> 4);
    }

    /** ¿{@code (x, z)} está dentro de una zona que ya existe? */
    private boolean taken(int x, int z) {
        for (WorkZones.AreaRow a : data.areas()) {
            if (x >= a.min().getX() && x <= a.max().getX() && z >= a.min().getZ() && z <= a.max().getZ()) return true;
        }
        return false;
    }

    /** Por qué no sirve el rectángulo (null = sirve). Lo mismo que revisa el servidor en {@link WorkZones#mark}. */
    @Nullable
    String rectProblem(int x0, int z0, int x1, int z1) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        if (markingJob == VillageManagement.Job.SMITH.ordinal() || markingJob == VillageManagement.Job.INNKEEPER.ordinal()
                || markingJob == VillageManagement.Job.MERCHANT.ordinal()) return markable(ax, az) ? null : "Afuera del territorio";
        if (!markable(ax, az) || !markable(bx, az) || !markable(ax, bz) || !markable(bx, bz)) {
            // Pedido de Ale: el leñador puede tener un puesto afuera (con su cofre y su cama)
            if (markingJob != VillageManagement.Job.LUMBERJACK.ordinal()) return "Afuera del territorio";
            double dx = (ax + bx) / 2.0 - data.center().getX(), dz = (az + bz) / 2.0 - data.center().getZ();
            if (dx * dx + dz * dz > (double) WorkZones.OUTPOST_RANGE * WorkZones.OUTPOST_RANGE) {
                return "Muy lejos para un puesto (máximo " + WorkZones.OUTPOST_RANGE + ")";
            }
        }
        int max = markingJob == VillageManagement.Job.MINER.ordinal() ? WorkZones.MAX_MINE_SIDE : WorkZones.MAX_SIDE;
        if (bx - ax + 1 < WorkZones.MIN_SIDE || bz - az + 1 < WorkZones.MIN_SIDE) return "Muy chica (mínimo " + WorkZones.MIN_SIDE + ")";
        if (bx - ax + 1 > max || bz - az + 1 > max) return "Muy grande (máximo " + max + ")";
        if (markingJob == VillageManagement.Job.MINER.ordinal()) {
            int len = Math.max(bx - ax + 1, bz - az + 1), wide = Math.min(bx - ax + 1, bz - az + 1);
            if (len < WorkZones.MIN_MINE_LENGTH) return "Escalera muy corta (mínimo " + WorkZones.MIN_MINE_LENGTH + " de largo)";
            if (wide > WorkZones.MAX_MINE_WIDTH) return "Escalera muy ancha (máximo " + WorkZones.MAX_MINE_WIDTH + ")";
        }
        for (WorkZones.AreaRow a : data.areas()) {
            if (ax <= a.max().getX() && bx >= a.min().getX() && az <= a.max().getZ() && bz >= a.min().getZ()) return "Se pisa con otra zona";
        }
        return null;
    }

    /** Mientras se marca: el piso donde se puede poner la zona, en verde (y rojo donde ya hay una zona). */
    void renderMarkable(GuiGraphics gfx, Camera camera, int sw, int sh) {
        for (Vec3 p : paintPoints()) {
            double[] s = AerialScreen.worldToScreen(camera, p, sw, sh);
            if (s == null) continue;
            int color = taken(Mth.floor(p.x), Mth.floor(p.z)) ? 0x90FF5555 : 0x7055FF55;
            gfx.fill((int) s[0] - 1, (int) s[1] - 1, (int) s[0] + 1, (int) s[1] + 1, color);
        }
    }

    /** Puntos sobre el piso cada 2 bloques en lo marcable (se recalcula si cambian el territorio o las zonas). */
    private List<Vec3> paintPoints() {
        StringBuilder k = new StringBuilder(java.util.Arrays.toString(data.territory()));
        for (WorkZones.AreaRow a : data.areas()) k.append(a.min()).append(a.max());
        String key = k.toString();
        if (key.equals(paintKey)) return paint;
        paintKey = key;
        List<Vec3> out = new java.util.ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return paint = out;
        int hint = data.center().getY();
        List<int[]> boxes = new java.util.ArrayList<>();   // x0, z0, x1, z1 a recorrer
        if (data.territory().length == 0) {
            int r = WorkZones.REACH, cx = data.center().getX(), cz = data.center().getZ();
            boxes.add(new int[]{cx - r, cz - r, cx + r, cz + r});
        } else {
            for (long l : data.territory()) {
                int x0 = net.minecraft.world.level.ChunkPos.getX(l) << 4, z0 = net.minecraft.world.level.ChunkPos.getZ(l) << 4;
                boxes.add(new int[]{x0, z0, x0 + 15, z0 + 15});
            }
        }
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[2]; x += 2) {
                for (int z = b[1]; z <= b[3]; z += 2) {
                    if (!markable(x, z)) continue;
                    Integer y = com.ale.warbandcraft.util.Ground.surfaceY(mc.level, x, z, hint, 24, 24);
                    if (y != null) out.add(new Vec3(x + 0.5, y + 1.05, z + 0.5));
                }
            }
        }
        return paint = out;
    }

    /** Click en el mundo: el aldeano libre más cercano al cursor (a ≤ 14 px) queda elegido. */
    boolean pick(Camera camera, double mx, double my, int sw, int sh) {
        UUID best = null;
        double bestD = 14 * 14;
        for (VillageManagement.VillagerRow v : data.free()) {
            Entity e = villager(v.id());
            if (e == null) continue;
            for (double dy : new double[]{0.3, 1.0, 1.6}) {
                double[] s = AerialScreen.worldToScreen(camera, e.position().add(0, dy, 0), sw, sh);
                if (s == null) continue;
                double d = (s[0] - mx) * (s[0] - mx) + (s[1] - my) * (s[1] - my);
                if (d < bestD) {
                    bestD = d;
                    best = v.id();
                }
            }
        }
        if (best == null) return false;
        selected = best;
        tab = TAB_VILLAGERS;
        return true;
    }

    /** Una línea de puntos entre dos puntos del mundo. */
    static void dots(GuiGraphics gfx, Camera camera, Vec3 a, Vec3 b, int color, int sw, int sh) {
        int steps = (int) Math.max(4, a.distanceTo(b) * 2);
        for (int i = 0; i <= steps; i++) {
            double[] s = AerialScreen.worldToScreen(camera, a.lerp(b, i / (double) steps), sw, sh);
            if (s != null) gfx.fill((int) s[0] - 1, (int) s[1] - 1, (int) s[0] + 1, (int) s[1] + 1, color);
        }
    }

    /** Las 12 aristas de la caja, punteadas, y la base rellena suave. */
    private static void drawBox(GuiGraphics gfx, Camera camera, BlockPos min, BlockPos max, int sw, int sh) {
        double x0 = min.getX(), y0 = min.getY(), z0 = min.getZ(), x1 = max.getX() + 1, y1 = max.getY() + 1, z1 = max.getZ() + 1;
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            Vec3 a = new Vec3(c[e[0]][0], c[e[0]][1], c[e[0]][2]), b = new Vec3(c[e[1]][0], c[e[1]][1], c[e[1]][2]);
            int steps = (int) Math.max(4, a.distanceTo(b) * 3);
            for (int i = 0; i <= steps; i++) {
                double[] s = AerialScreen.worldToScreen(camera, a.lerp(b, i / (double) steps), sw, sh);
                if (s != null) gfx.fill((int) s[0] - 1, (int) s[1] - 1, (int) s[0] + 1, (int) s[1] + 1, GOLD);
            }
        }
    }
}
