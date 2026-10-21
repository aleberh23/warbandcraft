package com.ale.warbandcraft.client.aerial;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.client.ModKeyMappings;
import com.ale.warbandcraft.network.AerialCampsMessage;
import com.ale.warbandcraft.network.AerialInspectMessage;
import com.ale.warbandcraft.network.AerialOrderMessage;
import com.ale.warbandcraft.network.AerialSetStateMessage;
import com.ale.warbandcraft.network.BattleOrderMessage;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.SapperOrderMessage;
import com.ale.warbandcraft.network.ToBarracksMessage;
import com.ale.warbandcraft.util.Ground;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pantalla de la vista aérea (H5): no pausa el juego, no tiene fondo (el mundo se sigue viendo
 * detrás), y es donde vive toda la interacción de mouse/teclado de la cámara.
 *
 * Minecraft deja de actualizar {@code KeyMapping.isDown()} de las teclas de movimiento mientras una
 * Screen tiene el foco (ver el comentario sobre {@code passEvents} en
 * {@link AerialCameraController#tick}), así que WASD se rastrea a mano con los
 * {@code keyPressed}/{@code keyReleased} de esta Screen, que sí se siguen llamando siempre.
 *
 * Click izquierdo: arrastrar selecciona por rectángulo, un click selecciona la unidad más cercana
 * (con Shift, además abre su inventario). Click derecho: si no hay nada seleccionado, arrastrar rota
 * la cámara; si hay soldados seleccionados, arrastrar los despliega en línea (como en Total War —
 * la rueda del mouse mientras se arrastra cambia entre 1, 2 o 3 filas); un click corto (sin arrastre)
 * da la orden de mover/atacar en el punto bajo el cursor. El minimapa es Xaero's Minimap (mod
 * aparte en {@code libs/}, corre solo, esta pantalla no le llama nada).
 *
 * H8: sobre cada Campamento tuyo se ve su nombre y cuántos hay en el barracón. Click izquierdo sobre un
 * Campamento lo selecciona y dibuja su jurisdicción ({@link CampBlockEntity#WORK_RADIUS}). Click derecho
 * sobre un Campamento con soldados seleccionados los manda a guardarse ahí; el botón Barracón abre la
 * lista de tus Campamentos para elegir a cuál (o "cada uno al suyo").
 */
public final class AerialScreen extends Screen {
    private static final double SCAN_RADIUS = 100.0;
    private static final double CLICK_TOLERANCE_PX = 6.0;
    private static final long CLICK_TOLERANCE_MS = 300;
    private static final double SELECT_PICK_RADIUS_PX = 20.0;
    private static final float ROTATE_SENSITIVITY = 0.4F;
    private static final double RANK_SPACING = 2.0;
    private static final int ROW_HEIGHT = 22;

    /** Un color por grupo (1-9), para el estandarte ficticio que marca a qué grupo pertenece cada soldado. */
    private static final int[] GROUP_COLORS = {
            0xFFE6194B, 0xFFF58231, 0xFFFFE119, 0xFF3CB44B, 0xFF42D4F4,
            0xFF4363D8, 0xFF911EB4, 0xFFF032E6, 0xFFFFFFFF,
    };

    private final AerialCameraController controller;
    private final AerialCameraState state;
    private final Button[] groupButtons = new Button[9];
    private Button hitboxButton;

    private boolean moveForward, moveBack, moveLeft, moveRight;

    private boolean leftDragging = false;
    private double leftDownX, leftDownY;

    private boolean rightDown = false;
    private boolean lineDragging = false;
    private int lineRanks = 1;
    private double rightDownX, rightDownY;
    private double rightDragDistance = 0;
    private long rightDownAtMillis = 0;

    private enum Category { CAVALRY, RANGED, INFANTRY }

    /** H16: el modo Gestión de la aldea (null = cerrado) y cada cuánto se refresca. */
    @Nullable
    private static ManagePanel manage;
    private int manageRefresh;
    /** H16: dónde empezó el rectángulo de la zona que se está marcando. */
    @Nullable
    private Vec3 markStart;

    /** H8: lista "¿a qué barracón?" abierta (botón Barracón). */
    private boolean campPicker = false;
    private boolean campsRequested = false;
    private static final int PICKER_WIDTH = 200;
    private static final int PICKER_TOP = 30;
    private static final int PICKER_MAX = 8;

    public AerialScreen(AerialCameraController controller, AerialCameraState state) {
        super(Component.translatable("key.warbandcraft.aerial_view"));
        this.controller = controller;
        this.state = state;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        controller.tick(moveForward, moveBack, moveLeft, moveRight);
        if (manage != null && ++manageRefresh >= 40) {   // H16: cada 2 s, datos frescos
            manageRefresh = 0;
            ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.ManageActionMessage(
                    com.ale.warbandcraft.network.ManageActionMessage.Action.REFRESH, manage.data.center(), null, 0, ""));
        }
    }

    // ------------------------------------------------------------------ botones

    @Override
    protected void init() {
        if (!campsRequested) {
            campsRequested = true;
            ModNetworking.CHANNEL.sendToServer(new AerialCampsMessage.Request());
            ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.ArmyActionMessage(
                    com.ale.warbandcraft.network.ArmyActionMessage.Action.STATUS, null, null, "", 0, null, false));   // H20
        }
        int y = this.height - ROW_HEIGHT;

        // Grupos 1-9: abajo a la izquierda.
        int x = 8;
        for (int i = 0; i < 9; i++) {
            int slot = i;
            groupButtons[i] = Button.builder(Component.literal(String.valueOf(i + 1)), b -> onGroupClicked(slot))
                    .bounds(x, y, 20, 20).build();
            addRenderableWidget(groupButtons[i]);
            x += 22;
        }

        // Órdenes + formación: abajo al centro.
        addRow(y, 44,
                btn("Mover", b -> state.orderMode = AerialCameraState.OrderMode.MOVE),
                btn("Atacar", b -> state.orderMode = AerialCameraState.OrderMode.ATTACK),
                btn("Auto", b -> state.orderMode = AerialCameraState.OrderMode.AUTO),
                btn("Volar", b -> state.orderMode = AerialCameraState.OrderMode.BLAST),
                btn("Cañón", b -> state.orderMode = AerialCameraState.OrderMode.CANNON),
                btn("Libre", b -> state.formation = AerialCameraState.Formation.NONE),
                btn("Línea", b -> state.formation = AerialCameraState.Formation.LINE));

        y -= ROW_HEIGHT;
        addRow(y, 50,
                btn("Seguir", b -> setFollow(1)),
                btn("Mantener", b -> holdHere()),
                btn("Vagar", b -> setFollow(0)),
                btn("Neutral", b -> setAggro(0)),
                btn("Agresivo", b -> setAggro(1)),
                btn("Raid", b -> setAggro(2)),
                btn("Pasivo", b -> setAggro(3)),
                btn("Barracón", b -> toggleCampPicker()),
                btn("Batalla", b -> ModNetworking.CHANNEL.sendToServer(new BattleOrderMessage(new ArrayList<>(state.selected)))));

        // Categorías + hitbox: columna abajo a la derecha (Xaero's Minimap vive arriba a la derecha
        // por defecto, así que esta esquina queda libre).
        int mmX = this.width - 90 - 10;
        int rw = 90;
        int ry = this.height - ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("Caballería"), b -> selectCategory(Category.CAVALRY))
                .bounds(mmX, ry, rw, 20).build());
        ry -= ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("A Distancia"), b -> selectCategory(Category.RANGED))
                .bounds(mmX, ry, rw, 20).build());
        ry -= ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.literal("Infantería"), b -> selectCategory(Category.INFANTRY))
                .bounds(mmX, ry, rw, 20).build());
        ry -= ROW_HEIGHT;
        hitboxButton = Button.builder(hitboxLabel(), b -> {
            state.showHitboxes = !state.showHitboxes;
            hitboxButton.setMessage(hitboxLabel());
        }).bounds(mmX, ry, rw, 20).build();
        addRenderableWidget(hitboxButton);

        // H16: Gestión, arriba a la izquierda, si estás en una aldea/castillo tuyo
        if (manage != null || inOwnVillage()) {
            addRenderableWidget(Button.builder(Component.literal(manage == null ? "Gestión" : "Cerrar gestión"), b -> toggleManage())
                    .bounds(ManagePanel.X, 8, 100, 18).build());
        }
        if (manage != null) manage.init(this::addRenderableWidget, this::rebuildWidgets, this.height);

        if (campPicker) addCampPicker();
        addArmyRow();
    }

    // ------------------------------------------------------------------ H20: ejércitos en el campo

    /** Arriba al medio: ◀ ejército ▶ · Elegir · Mantener · Avanzar · Cargar · Retirada · Licenciar. */
    private void addArmyRow() {
        com.ale.warbandcraft.network.ArmyStatusMessage.Field army = com.ale.warbandcraft.client.army.ArmyClient.current();
        if (army == null) return;
        int count = com.ale.warbandcraft.client.army.ArmyClient.field().size();
        int y = 28;
        int total = 16 + 4 + 90 + 4 + 16 + 7 * (58 + 4);
        int x = this.width / 2 - total / 2;
        Button prev = Button.builder(Component.literal("◀"), b -> cycleArmy(-1)).bounds(x, y, 16, 18).build();
        prev.active = count > 1;
        addRenderableWidget(prev);
        x += 20;
        Button name = Button.builder(Component.literal(army.name()), b -> selectArmy()).bounds(x, y, 90, 18).build();
        name.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.warbandcraft.army.select_tip")));
        addRenderableWidget(name);
        x += 94;
        Button next = Button.builder(Component.literal("▶"), b -> cycleArmy(1)).bounds(x, y, 16, 18).build();
        next.active = count > 1;
        addRenderableWidget(next);
        x += 20;
        com.ale.warbandcraft.network.ArmyActionMessage.Action[] orders = {
                com.ale.warbandcraft.network.ArmyActionMessage.Action.HOLD, null,
                com.ale.warbandcraft.network.ArmyActionMessage.Action.CHARGE, com.ale.warbandcraft.network.ArmyActionMessage.Action.RETREAT,
                com.ale.warbandcraft.network.ArmyActionMessage.Action.DISMISS, com.ale.warbandcraft.network.ArmyActionMessage.Action.DELEGATE};
        String[] keys = {"hold", "advance", "charge", "retreat", "dismiss", "delegate"};
        for (int i = 0; i < orders.length; i++) {
            com.ale.warbandcraft.network.ArmyActionMessage.Action order = orders[i];
            Button b = Button.builder(Component.translatable("gui.warbandcraft.army." + keys[i]), btn -> {
                if (order == null) {
                    state.orderMode = AerialCameraState.OrderMode.ARMY_ADVANCE;
                } else {
                    ModNetworking.CHANNEL.sendToServer(com.ale.warbandcraft.network.ArmyActionMessage.order(order, army.id(), null));
                }
            }).bounds(x, y, 58, 18).build();
            if (order == com.ale.warbandcraft.network.ArmyActionMessage.Action.DELEGATE) {   // H24
                b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.warbandcraft.army.delegate_tip")));
                if (army.mode() == 2) b.setMessage(Component.translatable("gui.warbandcraft.army.delegated"));
            }
            addRenderableWidget(b);
            x += 62;
        }
    }

    private void cycleArmy(int delta) {
        int n = com.ale.warbandcraft.client.army.ArmyClient.field().size();
        if (n == 0) return;
        com.ale.warbandcraft.client.army.ArmyClient.selected = Math.floorMod(com.ale.warbandcraft.client.army.ArmyClient.selected + delta, n);
        rebuildWidgets();
    }

    /** Elige a todos los del ejército (para darles órdenes sueltas: salen del control del ejército hasta la próxima orden). */
    private void selectArmy() {
        com.ale.warbandcraft.network.ArmyStatusMessage.Field army = com.ale.warbandcraft.client.army.ArmyClient.current();
        if (army == null) return;
        state.selected.clear();
        state.selected.addAll(army.members());
    }

    /** Llegó otro estado de los ejércitos (uno llegó o se licenció): rehacer los botones. */
    public void armiesChanged() {
        rebuildWidgets();
    }

    /** H8: la lista de tus Campamentos para mandar a los seleccionados a guardarse. */
    private void addCampPicker() {
        int x = this.width / 2 - PICKER_WIDTH / 2;
        int y = PICKER_TOP + 14;
        boolean any = !state.selected.isEmpty();
        Button own = Button.builder(Component.literal("Cada uno a su Campamento"), b -> sendToBarracks(null))
                .bounds(x, y, PICKER_WIDTH, 20).build();
        own.active = any;
        addRenderableWidget(own);
        y += ROW_HEIGHT;
        for (int i = 0; i < Math.min(PICKER_MAX, state.camps.size()); i++) {
            AerialCampsMessage.CampInfo camp = state.camps.get(i);
            Button b = Button.builder(Component.empty().append(camp.name())
                                    .append(" (" + camp.count() + "/" + camp.capacity() + ")"),
                            x2 -> sendToBarracks(camp.pos()))
                    .bounds(x, y, PICKER_WIDTH, 20).build();
            b.active = any && camp.count() < camp.capacity();
            addRenderableWidget(b);
            y += ROW_HEIGHT;
        }
        addRenderableWidget(Button.builder(Component.literal("Cancelar"), b -> toggleCampPicker())
                .bounds(x, y, PICKER_WIDTH, 20).build());
    }

    private int pickerHeight() {
        return 14 + (2 + Math.min(PICKER_MAX, state.camps.size())) * ROW_HEIGHT + 4;
    }

    private void toggleCampPicker() {
        campPicker = !campPicker;
        if (campPicker) ModNetworking.CHANNEL.sendToServer(new AerialCampsMessage.Request());
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ H16: gestión de la aldea

    /** Tu cuerpo (no la cámara) está en una aldea/castillo tuyo. */
    private boolean inOwnVillage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = com.ale.warbandcraft.village.VillageCamps.MANAGE_RADIUS;
        for (BlockPos c : state.villages) {
            double dx = mc.player.getX() - (c.getX() + 0.5), dz = mc.player.getZ() - (c.getZ() + 0.5);
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private void toggleManage() {
        if (manage != null) {
            manage = null;
            rebuildWidgets();
        } else {
            ModNetworking.CHANNEL.sendToServer(com.ale.warbandcraft.network.ManageActionMessage.open());
        }
    }

    /** El modo Gestión está abierto (el cartelito de la aldea se esconde: su info está en el panel). */
    public static boolean managing() {
        return manage != null;
    }

    /** Llegaron los datos del modo Gestión (null = cerrarlo). */
    public static void receiveManage(@Nullable com.ale.warbandcraft.village.VillageManagement.Data data, Component feedback) {
        if (!(Minecraft.getInstance().screen instanceof AerialScreen screen)) return;
        if (data == null) {
            manage = null;
            if (!feedback.getString().isEmpty() && screen.minecraft.player != null) screen.minecraft.player.displayClientMessage(feedback, true);
        } else if (manage == null || !manage.data.center().equals(data.center())) {
            manage = new ManagePanel(data);
            manage.feedback = feedback;
        } else {
            manage.data = data;
            if (!feedback.getString().isEmpty()) manage.feedback = feedback;
        }
        screen.rebuildWidgets();
    }

    /** H22: encarga lo marcado en el modo Gestión. */
    private void sendPlace(String text) {
        if (manage == null) return;
        ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.ManageActionMessage(
                com.ale.warbandcraft.network.ManageActionMessage.Action.PLACE, manage.data.center(), null, manage.placeRot, text));
    }

    /** H22: llegó la vista previa de lo que se está plantando. */
    public static void receivePreview(String key, boolean ok, String text, int floorY) {
        if (manage != null) manage.receivePreview(key, ok, text, floorY);
    }

    @Override
    public void removed() {
        manage = null;
        super.removed();
    }

    /** Llegó la lista de Campamentos del servidor (al entrar a la vista o al abrir la lista). */
    public static void receiveCamps(List<AerialCampsMessage.CampInfo> camps, List<BlockPos> villages) {
        if (Minecraft.getInstance().screen instanceof AerialScreen screen) {
            screen.state.camps = List.copyOf(camps);
            boolean had = screen.inOwnVillage();
            screen.state.villages = List.copyOf(villages);
            if (screen.campPicker || had != screen.inOwnVillage()) screen.rebuildWidgets();
        }
    }

    private record ButtonSpec(String label, Button.OnPress onPress) {}

    private static ButtonSpec btn(String label, Button.OnPress onPress) {
        return new ButtonSpec(label, onPress);
    }

    private void addRow(int y, int buttonWidth, ButtonSpec... specs) {
        int total = specs.length * (buttonWidth + 4) - 4;
        int x = this.width / 2 - total / 2;
        for (ButtonSpec spec : specs) {
            addRenderableWidget(Button.builder(Component.literal(spec.label()), spec.onPress())
                    .bounds(x, y, buttonWidth, 20).build());
            x += buttonWidth + 4;
        }
    }

    private boolean sapperMode() {
        return state.orderMode == AerialCameraState.OrderMode.BLAST || state.orderMode == AerialCameraState.OrderMode.CANNON;
    }

    private Component hitboxLabel() {
        return Component.literal(state.showHitboxes ? "Hitbox: ON" : "Hitbox: OFF");
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = this.minecraft;
        Camera camera = mc.gameRenderer.getMainCamera();

        if (state.showHitboxes) {
            for (AbstractRecruitEntity recruit : ownedRecruitsNear(mc)) {
                drawHitbox(gfx, camera, recruit.getBoundingBox());
            }
        }

        for (AbstractRecruitEntity recruit : ownedRecruitsNear(mc)) {
            int groupIndex = firstGroupOf(recruit.getUUID());
            if (groupIndex >= 0) {
                double[] gs = worldToScreen(camera, recruit.getEyePosition(1.0F).add(0, 1.1, 0), this.width, this.height);
                if (gs != null) drawGroupBanner(gfx, (int) gs[0], (int) gs[1], groupIndex);
            }

            if (!state.selected.contains(recruit.getUUID())) continue;
            double[] s = worldToScreen(camera, recruit.getEyePosition(1.0F).add(0, 0.4, 0), this.width, this.height);
            if (s == null) continue;
            int x = (int) s[0], y = (int) s[1];
            gfx.fill(x - 4, y - 7, x + 4, y - 5, 0xFF3CD24A);
        }

        if (leftDragging) {
            drawRectOutline(gfx, leftDownX, leftDownY, mouseX, mouseY, 0xFFFFFFFF);
        }

        if (lineDragging && mc.level != null && mc.player != null) {
            Vec3 start = groundPointAt(camera, mc.level, mc.player, rightDownX, rightDownY);
            Vec3 end = groundPointAt(camera, mc.level, mc.player, mouseX, mouseY);
            if (start != null && end != null) {
                for (Vec3 p : computeLineTargets(state.selected.size(), start, end, lineRanks)) {
                    double[] s = worldToScreen(camera, p, this.width, this.height);
                    if (s == null) continue;
                    int x = (int) s[0], y = (int) s[1];
                    gfx.fill(x - 3, y - 3, x + 3, y + 3, 0x90FFFFFF);
                }
            }
        }

        drawCamps(gfx, camera, mc);
        if (manage != null) {   // H16
            manage.renderWorld(gfx, this.font, camera, this.width, this.height);
            manage.render(gfx, this.font, this.height);
            if (manage.expanding) {
                manage.renderExpandHover(gfx, camera, groundPointAt(camera, mc.level, mc.player, mouseX, mouseY), this.width, this.height);
            }
            if (manage.placing != null) {   // H22: plantando un edificio, dibujando una muralla, marcando terreno...
                String hint = manage.hint();
                int hw = this.font.width(hint);
                gfx.fill(this.width / 2 - hw / 2 - 4, 8, this.width / 2 + hw / 2 + 4, 22, 0xC0000000);
                gfx.drawCenteredString(this.font, hint, this.width / 2, 11, 0xFFFFD24A);
                manage.renderPlacing(gfx, this.font, camera, groundPointAt(camera, mc.level, mc.player, mouseX, mouseY), mouseX, mouseY,
                        this.width, this.height);
            }
            if (manage.marking != null) {
                String hint = manage.markingJob == com.ale.warbandcraft.village.VillageManagement.Job.SMITH.ordinal()
                        ? "Click en el piso: el lugar del herrero (pone un yunque). Esc: cancelar"
                        : manage.markingJob == com.ale.warbandcraft.village.VillageManagement.Job.MERCHANT.ordinal()
                        ? "Click en el piso: dónde atiende el mercader. Esc: cancelar"
                        : "Arrastrá en el piso el rectángulo de la zona. Esc: cancelar";
                int hw = this.font.width(hint);
                gfx.fill(this.width / 2 - hw / 2 - 4, 8, this.width / 2 + hw / 2 + 4, 22, 0xC0000000);
                gfx.drawCenteredString(this.font, hint, this.width / 2, 11, 0xFFFFD24A);
                manage.renderMarkable(gfx, camera, this.width, this.height);   // H18: en verde, dónde se puede
                Vec3 now = markStart == null ? null : groundPointAt(camera, mc.level, mc.player, mouseX, mouseY);
                if (now != null) {
                    double y = Math.max(markStart.y, now.y) + 0.1;
                    double x0 = Math.floor(Math.min(markStart.x, now.x)), x1 = Math.floor(Math.max(markStart.x, now.x)) + 1;
                    double z0 = Math.floor(Math.min(markStart.z, now.z)), z1 = Math.floor(Math.max(markStart.z, now.z)) + 1;
                    String problem = manage.rectProblem((int) x0, (int) z0, (int) x1 - 1, (int) z1 - 1);
                    int color = problem == null ? 0xFF55FF55 : 0xFFFF5555;
                    Vec3[] c = {new Vec3(x0, y, z0), new Vec3(x1, y, z0), new Vec3(x1, y, z1), new Vec3(x0, y, z1)};
                    for (int i = 0; i < 4; i++) ManagePanel.dots(gfx, camera, c[i], c[(i + 1) % 4], color, this.width, this.height);
                    String size = (int) (x1 - x0) + " × " + (int) (z1 - z0) + (problem == null ? "" : " · " + problem);
                    gfx.drawString(this.font, size, mouseX + 10, mouseY + 4, color);
                }
            }
        }
        if (state.orderMode == AerialCameraState.OrderMode.ARMY_ADVANCE) {   // H20
            String hint = Component.translatable("gui.warbandcraft.army.advance_hint").getString();
            int hw = this.font.width(hint);
            gfx.fill(this.width / 2 - hw / 2 - 4, 50, this.width / 2 + hw / 2 + 4, 64, 0xC0000000);
            gfx.drawCenteredString(this.font, hint, this.width / 2, 53, 0xFFFFD24A);
        }
        if (campPicker) {
            int x = this.width / 2 - PICKER_WIDTH / 2;
            gfx.fill(x - 4, PICKER_TOP - 4, x + PICKER_WIDTH + 4, PICKER_TOP + pickerHeight(), 0xC0201810);
            gfx.drawCenteredString(this.font, state.selected.isEmpty() ? "Elegí soldados primero" : "¿A qué barracón?",
                    this.width / 2, PICKER_TOP, 0xFFD4A017);
        }

        for (int i = 0; i < 9; i++) {
            groupButtons[i].setMessage(Component.literal((i + 1) + (state.groups[i].isEmpty() ? "" : "*")));
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    /** H8: nombre y barracón sobre cada Campamento tuyo; la jurisdicción del seleccionado. */
    private void drawCamps(GuiGraphics gfx, Camera camera, Minecraft mc) {
        if (mc.level == null) return;
        if (state.selectedCamp != null) drawJurisdiction(gfx, camera, mc.level, state.selectedCamp);
        for (AerialCampsMessage.CampInfo camp : state.camps) {
            double[] s = worldToScreen(camera, Vec3.atCenterOf(camp.pos()).add(0, 1.4, 0), this.width, this.height);
            if (s == null) continue;
            boolean selected = camp.pos().equals(state.selectedCamp);
            Component label = Component.empty().append(camp.name()).append(" " + camp.count() + "/" + camp.capacity());
            int w = this.font.width(label);
            gfx.fill((int) s[0] - w / 2 - 2, (int) s[1] - 2, (int) s[0] + w / 2 + 2, (int) s[1] + 9, 0x90000000);
            gfx.drawCenteredString(this.font, label, (int) s[0], (int) s[1], selected ? 0xFFFFD24A : 0xFFFFFFFF);
        }
    }

    /**
     * El borde de la jurisdicción: un círculo de puntos sobre el terreno. El piso se busca bloque por
     * bloque desde la altura del Campamento (el cliente no tiene el mapa de alturas "sin hojas": daba la
     * bedrock). Los puntos se recalculan cada 2 s, no en cada cuadro.
     */
    private void drawJurisdiction(GuiGraphics gfx, Camera camera, Level level, BlockPos camp) {
        if (!camp.equals(circleCamp) || circlePoints == null || level.getGameTime() - circleTime > 40) {
            circleCamp = camp;
            circleTime = level.getGameTime();
            circlePoints = jurisdictionPoints(level, camp);
        }
        for (Vec3 p : circlePoints) {
            double[] s = worldToScreen(camera, p, this.width, this.height);
            if (s == null) continue;
            gfx.fill((int) s[0] - 1, (int) s[1] - 1, (int) s[0] + 2, (int) s[1] + 2, 0xE0FFD24A);
        }
    }

    @Nullable
    private BlockPos circleCamp;
    private long circleTime;
    @Nullable
    private List<Vec3> circlePoints;

    private static List<Vec3> jurisdictionPoints(Level level, BlockPos camp) {
        int r = CampBlockEntity.WORK_RADIUS;
        int points = 180;
        List<Vec3> out = new ArrayList<>(points);
        int lastY = camp.getY();
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            double x = camp.getX() + 0.5 + Math.cos(a) * r;
            double z = camp.getZ() + 0.5 + Math.sin(a) * r;
            // Se busca cerca del punto anterior: así sigue las lomas sin saltar al techo de una cueva
            Integer y = Ground.surfaceY(level, (int) Math.floor(x), (int) Math.floor(z), lastY, 12, 24);
            if (y == null) continue;
            lastY = y;
            out.add(new Vec3(x, y + 0.05, z));
        }
        return out;
    }

    /** El Campamento tuyo en ese bloque, o null. */
    @Nullable
    private BlockPos campAt(BlockPos pos) {
        for (AerialCampsMessage.CampInfo camp : state.camps) if (camp.pos().equals(pos)) return camp.pos();
        return null;
    }

    /** El bloque bajo el cursor, o null. */
    @Nullable
    private BlockPos blockUnderCursor(double sx, double sy) {
        Minecraft mc = this.minecraft;
        if (mc.level == null || mc.player == null) return null;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 far = origin.add(screenToDirection(camera, sx, sy, this.width, this.height).scale(SCAN_RADIUS * 2));
        BlockHitResult hit = mc.level.clip(new ClipContext(origin, far, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getBlockPos();
    }

    /** A qué grupo (0-8) pertenece este soldado, o -1 si no está en ninguno. Si está en varios, el primero. */
    private int firstGroupOf(UUID id) {
        for (int i = 0; i < state.groups.length; i++) {
            if (state.groups[i].contains(id)) return i;
        }
        return -1;
    }

    /** Estandarte ficticio (palo + tela de color, sin arte nuevo) para identificar el grupo a simple vista. */
    private void drawGroupBanner(GuiGraphics gfx, int x, int y, int groupIndex) {
        gfx.fill(x, y - 11, x + 1, y, 0xFFD9D9D9);
        gfx.fill(x + 1, y - 11, x + 7, y - 5, GROUP_COLORS[groupIndex]);
        gfx.drawString(this.minecraft.font, String.valueOf(groupIndex + 1), x + 9, y - 12, 0xFFFFFFFF);
    }

    private void drawRectOutline(GuiGraphics gfx, double x0, double y0, double x1, double y1, int color) {
        int minX = (int) Math.min(x0, x1), maxX = (int) Math.max(x0, x1);
        int minY = (int) Math.min(y0, y1), maxY = (int) Math.max(y0, y1);
        gfx.fill(minX, minY, maxX, minY + 1, color);
        gfx.fill(minX, maxY - 1, maxX, maxY, color);
        gfx.fill(minX, minY, minX + 1, maxY, color);
        gfx.fill(maxX - 1, minY, maxX, maxY, color);
    }

    /**
     * Solo la base (los 4 bordes al nivel del piso), no la caja completa: proyectar las 8 esquinas
     * de una AABB con la cámara mirando hacia abajo da un rectángulo que se estira desde los pies
     * hasta bien arriba de la cabeza (la esquina de arriba, en perspectiva, queda más arriba en
     * pantalla que la de abajo) — se veía como un bloque alto y feo en vez de marcar dónde está
     * parado el soldado. Con solo la base queda pegado al piso, como una sombra.
     */
    private void drawHitbox(GuiGraphics gfx, Camera camera, AABB box) {
        double minSx = Double.MAX_VALUE, minSy = Double.MAX_VALUE;
        double maxSx = -Double.MAX_VALUE, maxSy = -Double.MAX_VALUE;
        boolean any = false;
        for (double px : new double[]{box.minX, box.maxX}) {
            for (double pz : new double[]{box.minZ, box.maxZ}) {
                double[] s = worldToScreen(camera, new Vec3(px, box.minY, pz), this.width, this.height);
                if (s == null) continue;
                any = true;
                minSx = Math.min(minSx, s[0]);
                maxSx = Math.max(maxSx, s[0]);
                minSy = Math.min(minSy, s[1]);
                maxSy = Math.max(maxSy, s[1]);
            }
        }
        if (!any) return;
        gfx.fill((int) minSx, (int) minSy, (int) maxSx, (int) maxSy, 0x6000FF4A);
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (manage != null) {   // H16: el panel no deja pasar clicks; click izquierdo en un aldeano libre lo elige
            if (manage.contains(mouseX, mouseY, this.height)) return true;
            if (manage.expanding && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {   // agrandar el territorio: este chunk
                Vec3 g = groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level, this.minecraft.player, mouseX, mouseY);
                if (g != null) {
                    ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.ManageActionMessage(
                            com.ale.warbandcraft.network.ManageActionMessage.Action.EXPAND, manage.data.center(), null, 0,
                            (Mth.floor(g.x) >> 4) + "," + (Mth.floor(g.z) >> 4)));
                }
                return true;
            }
            if (manage.placing != null) {   // H22: click izquierdo planta / agrega un punto / empieza el rectángulo; derecho saca o cancela
                Vec3 g = groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level, this.minecraft.player, mouseX, mouseY);
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    String order = manage.click(g);
                    if (order != null) {
                        sendPlace(order);
                        manage.stopPlacing();
                        rebuildWidgets();
                    }
                    return true;
                }
                if (manage.mode() == ManagePanel.Mode.WALL && !manage.wallPts.isEmpty()) {
                    manage.wallPts.remove(manage.wallPts.size() - 1);
                    return true;
                }
                manage.stopPlacing();
                rebuildWidgets();
                return true;
            }
            if (manage.marking != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {   // marcar zona: empieza el rectángulo
                markStart = groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level, this.minecraft.player, mouseX, mouseY);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && manage.pick(this.minecraft.gameRenderer.getMainCamera(), mouseX, mouseY, this.width, this.height)) {
                rebuildWidgets();
                return true;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            leftDragging = true;
            leftDownX = mouseX;
            leftDownY = mouseY;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            rightDown = true;
            lineDragging = false;
            lineRanks = 1;
            rightDragDistance = 0;
            rightDownX = mouseX;
            rightDownY = mouseY;
            rightDownAtMillis = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (markStart != null || manage != null && manage.dragStart != null) return true;   // H16: marcando una zona; H22: un rectángulo de terreno
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && rightDown) {
            rightDragDistance += Math.hypot(dragX, dragY);
            if (!state.selected.isEmpty() && !sapperMode()) {
                lineDragging = true; // desplegando en línea: no rota la cámara
            } else {
                state.yaw -= (float) (dragX * ROTATE_SENSITIVITY);
                state.pitch = Mth.clamp(state.pitch - (float) (dragY * ROTATE_SENSITIVITY),
                        AerialCameraState.MIN_PITCH, AerialCameraState.MAX_PITCH);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (manage != null && manage.dragStart != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {   // H22: termina el rectángulo de terreno
            String order = manage.placeTarget(groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level,
                    this.minecraft.player, mouseX, mouseY));
            if (order != null) sendPlace(order);
            manage.stopPlacing();
            rebuildWidgets();
            return true;
        }
        if (markStart != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {   // H16: termina el rectángulo: se manda la zona
            Vec3 end = groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level, this.minecraft.player, mouseX, mouseY);
            if (end != null && manage != null && manage.marking != null) {
                String rect = Mth.floor(markStart.x) + "," + Mth.floor(markStart.z) + "," + Mth.floor(end.x) + "," + Mth.floor(end.z);
                ModNetworking.CHANNEL.sendToServer(new com.ale.warbandcraft.network.ManageActionMessage(
                        com.ale.warbandcraft.network.ManageActionMessage.Action.ZONE, manage.data.center(), manage.marking, 0, rect));
            }
            markStart = null;
            if (manage != null) manage.marking = null;
            rebuildWidgets();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && leftDragging) {
            leftDragging = false;
            finishDragSelect(leftDownX, leftDownY, mouseX, mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && rightDown) {
            rightDown = false;
            if (lineDragging) {
                lineDragging = false;
                issueLineFormation(rightDownX, rightDownY, mouseX, mouseY);
            } else {
                boolean quick = System.currentTimeMillis() - rightDownAtMillis < CLICK_TOLERANCE_MS;
                if (rightDragDistance <= CLICK_TOLERANCE_PX || (quick && rightDragDistance <= SELECT_PICK_RADIUS_PX)) {
                    issueOrder(mouseX, mouseY);
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (manage != null && manage.dragStart != null) {   // H22: altura del terreno ±1
            manage.dragY += delta > 0 ? 1 : -1;
            return true;
        }
        if (rightDown && !state.selected.isEmpty()) {
            lineRanks = Mth.clamp(lineRanks + (delta > 0 ? 1 : -1), 1, 3);
            return true;
        }
        state.distance = Mth.clamp((float) (state.distance - delta * 2.0),
                AerialCameraState.MIN_DISTANCE, AerialCameraState.MAX_DISTANCE);
        return true;
    }

    // ------------------------------------------------------------------ teclado

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (setMoveFlag(keyCode, true)) return true;

        if (keyCode == GLFW.GLFW_KEY_R && manage != null && manage.placing != null && manage.mode() == ManagePanel.Mode.BUILDING) {   // H22: girar
            manage.placeRot = (manage.placeRot + 1) % 4;
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && manage != null && manage.placing != null) {   // H22: encargar la muralla
            String order = manage.wallOrder();
            if (order != null) {
                sendPlace(order);
                manage.stopPlacing();
                rebuildWidgets();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && manage != null && (manage.marking != null || manage.expanding || manage.placing != null)) {   // H16: Esc cancela
            manage.stopPlacing();
            manage.expanding = false;
            manage.marking = null;
            markStart = null;
            rebuildWidgets();
            return true;
        }
        if (keyCode == ModKeyMappings.AERIAL_VIEW.getKey().getValue() || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            controller.exit();
            return true;
        }

        int digit = keyCode - GLFW.GLFW_KEY_1;
        if (digit >= 0 && digit <= 8) {
            this.minecraft.options.keyHotbarSlots[digit].consumeClick();
            onGroupClicked(digit);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (setMoveFlag(keyCode, false)) return true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** WASD (según el bind real del jugador) rastreado a mano — ver el comentario de la clase. */
    private boolean setMoveFlag(int keyCode, boolean down) {
        Minecraft mc = this.minecraft;
        if (keyCode == mc.options.keyUp.getKey().getValue()) {
            moveForward = down;
            return true;
        }
        if (keyCode == mc.options.keyDown.getKey().getValue()) {
            moveBack = down;
            return true;
        }
        if (keyCode == mc.options.keyLeft.getKey().getValue()) {
            moveLeft = down;
            return true;
        }
        if (keyCode == mc.options.keyRight.getKey().getValue()) {
            moveRight = down;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ acciones

    private void onGroupClicked(int slot) {
        if (hasControlDown()) {
            state.groups[slot] = new LinkedHashSet<>(state.selected);
            return;
        }
        Set<UUID> group = state.groups[slot];
        if (group.isEmpty()) return;
        state.selected.clear();
        for (AbstractRecruitEntity recruit : ownedRecruitsNear(this.minecraft)) {
            if (group.contains(recruit.getUUID())) state.selected.add(recruit.getUUID());
        }
    }

    private void setFollow(int followState) {
        if (state.selected.isEmpty()) return;
        ModNetworking.CHANNEL.sendToServer(new AerialSetStateMessage(
                new ArrayList<>(state.selected), AerialSetStateMessage.Kind.FOLLOW, followState));
    }

    private void holdHere() {
        if (state.selected.isEmpty()) return;
        ModNetworking.CHANNEL.sendToServer(new AerialSetStateMessage(
                new ArrayList<>(state.selected), AerialSetStateMessage.Kind.HOLD_HERE, 0));
    }

    /** H8: los seleccionados caminan a ese Campamento (null = cada uno al suyo) y se guardan al llegar. */
    private void sendToBarracks(@Nullable BlockPos camp) {
        if (state.selected.isEmpty()) return;
        ModNetworking.CHANNEL.sendToServer(new ToBarracksMessage(new ArrayList<>(state.selected), camp));
        state.selected.clear();
        if (camp != null) state.selectedCamp = camp;
        campPicker = false;
        rebuildWidgets();
    }

    private void setAggro(int aggroState) {
        if (state.selected.isEmpty()) return;
        ModNetworking.CHANNEL.sendToServer(new AerialSetStateMessage(
                new ArrayList<>(state.selected), AerialSetStateMessage.Kind.AGGRO_STATE, aggroState));
    }

    private void selectCategory(Category category) {
        state.selected.clear();
        for (AbstractRecruitEntity recruit : ownedRecruitsNear(this.minecraft)) {
            if (categoryOf(recruit) == category) state.selected.add(recruit.getUUID());
        }
    }

    private static Category categoryOf(AbstractRecruitEntity r) {
        if (r.isPassenger() && r.getVehicle() instanceof Horse) return Category.CAVALRY;
        ItemStack main = r.getMainHandItem();
        if (main.getItem() instanceof BowItem || main.getItem() instanceof CrossbowItem) return Category.RANGED;
        return Category.INFANTRY;
    }

    private void finishDragSelect(double x0, double y0, double x1, double y1) {
        Minecraft mc = this.minecraft;
        boolean isClick = Math.abs(x1 - x0) < CLICK_TOLERANCE_PX && Math.abs(y1 - y0) < CLICK_TOLERANCE_PX;
        double minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        double minY = Math.min(y0, y1), maxY = Math.max(y0, y1);

        Camera camera = mc.gameRenderer.getMainCamera();
        List<UUID> inBox = new ArrayList<>();
        UUID closest = null;
        double closestDist = Double.MAX_VALUE;

        for (AbstractRecruitEntity recruit : ownedRecruitsNear(mc)) {
            double[] screen = worldToScreen(camera, recruit.getEyePosition(1.0F), this.width, this.height);
            if (screen == null) continue;

            if (isClick) {
                double dist = Math.hypot(screen[0] - x0, screen[1] - y0);
                if (dist < SELECT_PICK_RADIUS_PX && dist < closestDist) {
                    closestDist = dist;
                    closest = recruit.getUUID();
                }
            } else if (screen[0] >= minX && screen[0] <= maxX && screen[1] >= minY && screen[1] <= maxY) {
                inBox.add(recruit.getUUID());
            }
        }

        state.selected.clear();
        if (isClick && closest == null) {
            // H8: click sobre un Campamento tuyo lo selecciona (se ve su jurisdicción); en otro lado, lo suelta
            BlockPos block = blockUnderCursor(x0, y0);
            state.selectedCamp = block == null ? null : campAt(block);
        }
        if (isClick) {
            if (closest != null) {
                state.selected.add(closest);
                if (hasShiftDown()) {
                    ModNetworking.CHANNEL.sendToServer(new AerialInspectMessage(closest));
                }
            }
        } else {
            state.selected.addAll(inBox);
        }
    }

    private void issueOrder(double mouseX, double mouseY) {
        if (state.orderMode == AerialCameraState.OrderMode.ARMY_ADVANCE) {   // H20: el ejército avanza hasta acá
            state.orderMode = AerialCameraState.OrderMode.AUTO;
            com.ale.warbandcraft.network.ArmyStatusMessage.Field army = com.ale.warbandcraft.client.army.ArmyClient.current();
            Vec3 g = groundPointAt(this.minecraft.gameRenderer.getMainCamera(), this.minecraft.level, this.minecraft.player, mouseX, mouseY);
            if (army != null && g != null) {
                ModNetworking.CHANNEL.sendToServer(com.ale.warbandcraft.network.ArmyActionMessage.order(
                        com.ale.warbandcraft.network.ArmyActionMessage.Action.ADVANCE, army.id(), g));
            }
            return;
        }
        if (state.selected.isEmpty()) return;
        Minecraft mc = this.minecraft;
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 dir = screenToDirection(camera, mouseX, mouseY, this.width, this.height);
        Vec3 far = origin.add(dir.scale(SCAN_RADIUS * 2));

        // H8: click derecho sobre un Campamento tuyo = van a guardarse ahí
        if (!sapperMode()) {
            BlockPos block = blockUnderCursor(mouseX, mouseY);
            BlockPos camp = block == null ? null : campAt(block);
            if (camp != null) {
                sendToBarracks(camp);
                return;
            }
        }

        // H6: Zapador. Volar = TNT en el bloque bajo el cursor; Cañón = armarlo ahí o apuntarle ahí.
        if (state.orderMode == AerialCameraState.OrderMode.BLAST || state.orderMode == AerialCameraState.OrderMode.CANNON) {
            BlockHitResult hit = level.clip(new ClipContext(origin, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return;
            ModNetworking.CHANNEL.sendToServer(new SapperOrderMessage(new ArrayList<>(state.selected),
                    state.orderMode == AerialCameraState.OrderMode.CANNON, hit.getBlockPos()));
            return;
        }

        LivingEntity bestTarget = null;
        if (state.orderMode != AerialCameraState.OrderMode.MOVE) {
            double bestDist = Double.MAX_VALUE;
            for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, new AABB(origin, far).inflate(1.0))) {
                if (living == player || !living.isAlive()) continue;
                if (living instanceof AbstractRecruitEntity r && Objects.equals(r.getOwnerUUID(), player.getUUID())) continue;

                Vec3 center = living.getBoundingBox().getCenter();
                double t = center.subtract(origin).dot(dir);
                if (t < 0) continue;
                double perpDist = origin.add(dir.scale(t)).distanceTo(center);
                if (perpDist < Math.max(0.6, living.getBbWidth())) {
                    double dist = origin.distanceTo(center);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestTarget = living;
                    }
                }
            }
        }

        boolean attack;
        UUID targetEntity = null;
        Vec3 clickedPos;
        if (bestTarget != null) {
            attack = true;
            targetEntity = bestTarget.getUUID();
            clickedPos = bestTarget.position();
        } else if (state.orderMode == AerialCameraState.OrderMode.ATTACK) {
            return; // modo forzado "atacar" pero no hay nada bajo el cursor: no hacemos nada
        } else {
            BlockHitResult hit = level.clip(new ClipContext(
                    origin, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return;
            attack = false;
            clickedPos = hit.getLocation();
        }

        List<UUID> ids = new ArrayList<>(state.selected);
        List<Vec3> targets = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) targets.add(clickedPos);

        ModNetworking.CHANNEL.sendToServer(new AerialOrderMessage(ids, attack, targets, targetEntity));
    }

    /** Arrastre de click derecho con soldados seleccionados: los despliega en línea, como en Total War. */
    private void issueLineFormation(double x0, double y0, double x1, double y1) {
        Minecraft mc = this.minecraft;
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || state.selected.isEmpty()) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 start = groundPointAt(camera, level, player, x0, y0);
        Vec3 end = groundPointAt(camera, level, player, x1, y1);
        if (start == null || end == null) return;

        List<UUID> ids = new ArrayList<>(state.selected);
        List<Vec3> targets = computeLineTargets(ids.size(), start, end, lineRanks);
        ModNetworking.CHANNEL.sendToServer(new AerialOrderMessage(ids, false, targets, null));
    }

    @Nullable
    private Vec3 groundPointAt(Camera camera, Level level, LocalPlayer player, double sx, double sy) {
        Vec3 origin = camera.getPosition();
        Vec3 dir = screenToDirection(camera, sx, sy, this.width, this.height);
        Vec3 far = origin.add(dir.scale(SCAN_RADIUS * 2));
        BlockHitResult hit = level.clip(new ClipContext(origin, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /** Reparte "count" soldados entre "ranks" filas paralelas a lo largo de la línea start→end. */
    private List<Vec3> computeLineTargets(int count, Vec3 start, Vec3 end, int ranks) {
        List<Vec3> out = new ArrayList<>(count);
        Vec3 lineDir = end.subtract(start);
        double lineLen = lineDir.length();
        if (lineLen < 0.5) {
            lineDir = new Vec3(0, 0, 1);
            lineLen = 0;
        } else {
            lineDir = lineDir.normalize();
        }
        Vec3 perp = new Vec3(-lineDir.z, 0, lineDir.x);

        int filesPerRank = Math.max(1, (int) Math.ceil(count / (double) ranks));
        double spacing = filesPerRank > 1 ? lineLen / (filesPerRank - 1) : 0;

        int idx = 0;
        for (int r = 0; r < ranks && idx < count; r++) {
            for (int f = 0; f < filesPerRank && idx < count; f++) {
                double along = filesPerRank > 1 ? f * spacing : lineLen / 2.0;
                out.add(start.add(lineDir.scale(along)).add(perp.scale(r * RANK_SPACING)));
                idx++;
            }
        }
        return out;
    }

    /**
     * Todos los soldados propios cargados del lado cliente — no solo los cercanos a la cámara.
     * Escanear por distancia a {@code state.center} hacía que un grupo "desapareciera" apenas
     * paneabas la cámara lejos de donde estaban esos soldados (o ellos se alejaban con una orden):
     * parecía que el grupo no se había guardado, pero en realidad el UUID seguía ahí, solo que la
     * búsqueda no los encontraba. {@code entitiesForRendering()} no depende de esa distancia.
     */
    private List<AbstractRecruitEntity> ownedRecruitsNear(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return List.of();
        List<AbstractRecruitEntity> list = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractRecruitEntity r && r.isAlive() && Objects.equals(r.getOwnerUUID(), player.getUUID())
                    && Soldiers.isSoldier(r)) {
                list.add(r);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ proyección mundo↔pantalla

    /** Proyecta un punto del mundo a coordenadas de pantalla (escala de GUI), o null si queda atrás. */
    static double[] worldToScreen(Camera camera, Vec3 world, int guiWidth, int guiHeight) {
        Vec3 rel = world.subtract(camera.getPosition());
        Vector3f f = camera.getLookVector();
        Vector3f u = camera.getUpVector();
        Vector3f l = camera.getLeftVector();

        double depth = rel.x * f.x() + rel.y * f.y() + rel.z * f.z();
        if (depth < 0.1) return null;
        double rightComp = -(rel.x * l.x() + rel.y * l.y() + rel.z * l.z());
        double upComp = rel.x * u.x() + rel.y * u.y() + rel.z * u.z();

        double tanHalf = fovTanHalf();
        double aspect = (double) guiWidth / guiHeight;

        double ndcX = rightComp / (depth * tanHalf * aspect);
        double ndcY = upComp / (depth * tanHalf);

        double sx = (ndcX * 0.5 + 0.5) * guiWidth;
        double sy = (0.5 - ndcY * 0.5) * guiHeight;
        return new double[]{sx, sy};
    }

    /** Inversa de {@link #worldToScreen}: de un punto de pantalla a una dirección del mundo. */
    private static Vec3 screenToDirection(Camera camera, double sx, double sy, int guiWidth, int guiHeight) {
        double ndcX = (sx / guiWidth) * 2 - 1;
        double ndcY = 1 - (sy / guiHeight) * 2;

        double tanHalf = fovTanHalf();
        double aspect = (double) guiWidth / guiHeight;
        double rightComp = ndcX * tanHalf * aspect;
        double upComp = ndcY * tanHalf;

        Vector3f f = camera.getLookVector();
        Vector3f u = camera.getUpVector();
        Vector3f l = camera.getLeftVector();

        double dx = f.x() - l.x() * rightComp + u.x() * upComp;
        double dy = f.y() - l.y() * rightComp + u.y() * upComp;
        double dz = f.z() - l.z() * rightComp + u.z() * upComp;
        return new Vec3(dx, dy, dz).normalize();
    }

    private static double fovTanHalf() {
        double fovDeg = Minecraft.getInstance().options.fov().get();
        return Math.tan(Math.toRadians(fovDeg) / 2.0);
    }
}
