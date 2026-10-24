package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pedido de Ale ("volví a entrar al server y me teletransportó al spawn"): ServerOfflineAuth (login con contraseña) manda
 * al jugador al spawn hasta que se loguea. Con /login lo devuelve a lo que guardó al salir (si salió ya logueado), pero con
 * el token (login automático) no lo devuelve nunca. Acá se anota la posición de verdad al entrar (antes de que ese mod lo
 * mueva) y, apenas queda logueado, se lo devuelve ahí. Si se va sin loguearse, la posición queda en su ficha (vanilla lo
 * guarda en el spawn). Sin ese mod no hace nada.
 * Logueado = {@code LoginManager.isAuthenticated} por reflexión; si eso no anda, el modo de juego: mientras no se loguea
 * ese mod lo deja en Aventura y al loguearse le devuelve el que tenía.
 */
public class LoginPositionEvents {
    private static final String TAG = "warbandcraft:auth_pos";
    private static final String AUTH_MOD = "server_offline_auth";
    /** Hasta cuándo se espera que se loguee (después se olvida, ticks). */
    private static final int GIVE_UP_TICKS = 20 * 60 * 10;
    private static final Map<UUID, CompoundTag> PENDING = new HashMap<>();
    @Nullable
    private static Method isAuthenticated;
    private static boolean looked;

    private static boolean authModLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(AUTH_MOD);
    }

    @Nullable
    private static Method authCheck() {
        if (!looked) {
            looked = true;
            try {
                isAuthenticated = Class.forName("cn.citprobe.ServerOfflineAuth.LoginManager").getMethod("isAuthenticated", ServerPlayer.class);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                WarbandCraft.LOGGER.warn("Login: no se pudo usar ServerOfflineAuth por reflexión ({}); se mira el modo de juego", e.toString());
                isAuthenticated = null;
            }
        }
        return isAuthenticated;
    }

    /** ¿Ya se logueó? Null = no se sabe. */
    @Nullable
    private static Boolean authenticated(ServerPlayer player, @Nullable CompoundTag pos) {
        Method m = authCheck();
        if (m != null) {
            try {
                return (Boolean) m.invoke(null, player);
            } catch (ReflectiveOperationException | RuntimeException e) {
                WarbandCraft.LOGGER.warn("Login: falló ServerOfflineAuth.isAuthenticated ({}); se mira el modo de juego", e.toString());
                isAuthenticated = null;
            }
        }
        if (pos == null || !pos.contains("mode")) return null;
        GameType before = GameType.byId(pos.getInt("mode"));
        if (before == GameType.ADVENTURE) return null;   // no se distingue
        return player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !authModLoaded()) return;
        CompoundTag pd = p.getPersistentData();
        CompoundTag pos;
        if (pd.contains(TAG)) {   // la vez pasada se fue sin loguearse: lo que vale es lo de antes
            pos = pd.getCompound(TAG);
        } else {
            pos = new CompoundTag();
            pos.putString("dim", p.level().dimension().location().toString());
            pos.putDouble("x", p.getX());
            pos.putDouble("y", p.getY());
            pos.putDouble("z", p.getZ());
            pos.putFloat("yRot", p.getYRot());
            pos.putFloat("xRot", p.getXRot());
            pos.putInt("mode", p.gameMode.getGameModeForPlayer().getId());   // antes de que ese mod lo pase a Aventura
        }
        pos.putLong("since", p.getServer().getTickCount());
        PENDING.put(p.getUUID(), pos);
        WarbandCraft.LOGGER.info("Login: {} entró en ({}, {}, {}), se lo devuelve ahí al loguearse",
                p.getName().getString(), (int) pos.getDouble("x"), (int) pos.getDouble("y"), (int) pos.getDouble("z"));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag pos = PENDING.remove(p.getUUID());
        if (pos != null && !Boolean.TRUE.equals(authenticated(p, pos))) {
            pos.remove("since");
            p.getPersistentData().put(TAG, pos);
            WarbandCraft.LOGGER.info("Login: {} se fue sin loguearse; queda anotado dónde estaba", p.getName().getString());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)) return;
        if (PENDING.isEmpty() || p.tickCount % 5 != 0) return;
        CompoundTag pos = PENDING.get(p.getUUID());
        if (pos == null) return;
        if (p.getServer().getTickCount() - pos.getLong("since") > GIVE_UP_TICKS) {
            PENDING.remove(p.getUUID());
            return;
        }
        if (!Boolean.TRUE.equals(authenticated(p, pos))) return;
        PENDING.remove(p.getUUID());
        p.getPersistentData().remove(TAG);
        ResourceLocation dim = ResourceLocation.tryParse(pos.getString("dim"));
        ServerLevel level = dim == null ? null : p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level == null) return;
        double x = pos.getDouble("x"), y = pos.getDouble("y"), z = pos.getDouble("z");
        if (p.level() == level && p.distanceToSqr(x, y, z) < 4.0) {
            WarbandCraft.LOGGER.info("Login: {} ya estaba en su lugar", p.getName().getString());
            return;
        }
        p.stopRiding();
        p.teleportTo(level, x, y, z, pos.getFloat("yRot"), pos.getFloat("xRot"));
        p.fallDistance = 0.0F;
        WarbandCraft.LOGGER.info("Login: {} vuelve a donde estaba ({}, {}, {})", p.getName().getString(), (int) x, (int) y, (int) z);
    }
}
