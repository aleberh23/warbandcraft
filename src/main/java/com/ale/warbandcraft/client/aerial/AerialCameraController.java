package com.ale.warbandcraft.client.aerial;

import com.ale.warbandcraft.client.ModKeyMappings;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Cámara aérea (H5): lo que NO depende de una pantalla — mover el centro de cámara con WASD, congelar
 * a Steve en su lugar, salir solo si le bajan la vida, y abrir/cerrar {@link AerialScreen} con la
 * tecla V. Toda la interacción de mouse/teclado de la vista (selección, click derecho, botones) vive
 * en {@link AerialScreen}: una {@code Screen} de Minecraft recibe las coordenadas de mouse ya en
 * escala de GUI, a diferencia de los eventos de input "crudos" que usaba la primera versión.
 *
 * La cámara es un {@link Marker} de vanilla usado puramente como ancla de render (nunca se agrega al
 * mundo): es la técnica estándar para una cámara libre desacoplada del jugador real
 * ({@code Minecraft#setCameraEntity}). Mientras está activa, forzamos la perspectiva en primera
 * persona: en tercera persona Minecraft aleja la cámara de las paredes/piso para no atravesarlos, y
 * eso es justo lo que no queremos acá (la cámara debe poder volar como un espectador).
 */
public final class AerialCameraController {
    private static final float PAN_SPEED = 0.9F;

    final AerialCameraState state = new AerialCameraState();
    private Marker cameraMarker;
    private CameraType previousCameraType;

    @SubscribeEvent
    public void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || state.active) return;
        if (ModKeyMappings.AERIAL_VIEW.consumeClick()) {
            enter();
        }
    }

    void enter() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || state.active) return;

        state.active = true;
        state.center = player.position();
        state.yaw = player.getYRot();
        state.pitch = AerialCameraState.DEFAULT_PITCH;
        state.distance = AerialCameraState.DEFAULT_DISTANCE;
        state.enterHealth = player.getHealth();
        state.selected.clear();
        state.orderMode = AerialCameraState.OrderMode.AUTO;
        state.formation = AerialCameraState.Formation.NONE;

        previousCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);

        cameraMarker = new Marker(EntityType.MARKER, mc.level);
        placeCameraMarker();
        mc.setCameraEntity(cameraMarker);
        mc.setScreen(new AerialScreen(this, state));
    }

    void exit() {
        Minecraft mc = Minecraft.getInstance();
        state.reset();
        cameraMarker = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
            previousCameraType = null;
        }
        if (mc.screen instanceof AerialScreen) mc.setScreen(null);
    }

    /**
     * {@code /camara}: comando solo de cliente para cuando la vista aérea queda trabada (la cámara
     * sigue en el Marker, o en una perspectiva rara). Vuelve todo a primera persona sobre el jugador
     * sin importar lo que diga {@code state.active}.
     */
    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("camara").executes(ctx -> {
            resetCamera();
            ctx.getSource().sendSuccess(() -> Component.translatable("message.warbandcraft.camera_reset"), false);
            return 1;
        }));
    }

    void resetCamera() {
        Minecraft mc = Minecraft.getInstance();
        state.reset();
        cameraMarker = null;
        previousCameraType = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (mc.screen instanceof AerialScreen) mc.setScreen(null);
    }

    /**
     * Llamado desde {@link AerialScreen#tick()} con las 4 direcciones ya resueltas por la propia
     * Screen (ver {@code AerialScreen#keyPressed/keyReleased}). Minecraft deja de actualizar
     * {@code KeyMapping.isDown()} para las teclas de movimiento mientras una Screen tiene el foco —
     * es una limitación conocida de Forge en 1.20.1 (Reign of Nether tiene el mismo problema: su
     * {@code TopdownGui.java} comenta que el campo {@code passEvents} que lo resolvía en versiones
     * viejas "lo sacaron, no sé por qué"). Por eso las 4 direcciones se rastrean a mano con los
     * propios {@code keyPressed}/{@code keyReleased} de la Screen, que sí se siguen llamando.
     */
    void tick(boolean forward, boolean back, boolean left, boolean right) {
        if (!state.active) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || cameraMarker == null || cameraMarker.level() != mc.level) {
            exit();
            return;
        }
        if (player.getHealth() < state.enterHealth) {
            exit();
            return;
        }
        state.enterHealth = Math.min(state.enterHealth, player.getHealth());

        double speed = PAN_SPEED * (state.distance / AerialCameraState.DEFAULT_DISTANCE);
        float yawRad = (float) Math.toRadians(state.yaw);
        Vec3 fwdVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 rightVec = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad));

        Vec3 move = Vec3.ZERO;
        if (forward) move = move.add(fwdVec);
        if (back) move = move.subtract(fwdVec);
        if (right) move = move.add(rightVec);
        if (left) move = move.subtract(rightVec);
        if (move.lengthSqr() > 0) {
            state.center = state.center.add(move.normalize().scale(speed));
        }

        placeCameraMarker();
    }

    private void placeCameraMarker() {
        cameraMarker.xo = cameraMarker.getX();
        cameraMarker.yo = cameraMarker.getY();
        cameraMarker.zo = cameraMarker.getZ();
        cameraMarker.yRotO = cameraMarker.getYRot();
        cameraMarker.xRotO = cameraMarker.getXRot();

        Vec3 look = lookVector(state.yaw, state.pitch);
        cameraMarker.setPos(state.center.subtract(look.scale(state.distance)));
        cameraMarker.setYRot(state.yaw);
        cameraMarker.setXRot(state.pitch);
    }

    /** Mismo cálculo que {@code Entity.calculateViewVector}: de yaw/pitch a vector de mirada. */
    static Vec3 lookVector(float yawDeg, float pitchDeg) {
        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad = Math.toRadians(-yawDeg);
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    @SubscribeEvent
    public void onMovementInput(MovementInputUpdateEvent event) {
        if (!state.active) return;
        var input = event.getInput();
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
        input.leftImpulse = 0;
        input.forwardImpulse = 0;
    }

    // Forzamos CameraType.FIRST_PERSON (para que la cámara atraviese paredes/piso como espectador),
    // pero eso hace que Minecraft dibuje el brazo/ítem en mano de Steve como en primera persona real.
    // La cámara en sí ya no es Steve, así que no corresponde verlo: se cancela, igual que Reign of Nether.
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (state.active) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRenderArm(RenderArmEvent event) {
        if (state.active) event.setCanceled(true);
    }
}
