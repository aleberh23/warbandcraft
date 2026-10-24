package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pedido de Ale ("volví a entrar al server y me teletransportó al spawn"): ServerOfflineAuth (login con contraseña) manda
 * al jugador al spawn hasta que se loguea y después lo devuelve a lo que guardó al salir, pero solo si salió ya logueado
 * (la primera vez, si se desconectó antes de loguearse o si el servidor se cerró de golpe, lo deja en el spawn). Acá se anota
 * la posición de verdad al entrar (antes de que ese mod lo mueva) y, apenas queda logueado, se lo devuelve ahí.
 * Si se va sin loguearse, la posición queda en su ficha (vanilla lo guarda en el spawn). Sin ese mod no hace nada.
 */
public class LoginPositionEvents {
    private static final String TAG = "warbandcraft:auth_pos";
    private static final Map<UUID, CompoundTag> PENDING = new HashMap<>();
    @Nullable
    private static Method isAuthenticated;
    private static boolean looked;

    @Nullable
    private static Method authCheck() {
        if (!looked) {
            looked = true;
            try {
                isAuthenticated = Class.forName("cn.citprobe.ServerOfflineAuth.LoginManager").getMethod("isAuthenticated", ServerPlayer.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                isAuthenticated = null;   // el mod no está
            }
        }
        return isAuthenticated;
    }

    /** Null = no se sabe (sin el mod, o falló): no se hace nada. */
    @Nullable
    private static Boolean authenticated(ServerPlayer player) {
        Method m = authCheck();
        if (m == null) return null;
        try {
            return (Boolean) m.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || authCheck() == null) return;
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
        }
        PENDING.put(p.getUUID(), pos);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag pos = PENDING.remove(p.getUUID());
        if (pos != null && Boolean.FALSE.equals(authenticated(p))) p.getPersistentData().put(TAG, pos);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)) return;
        if (PENDING.isEmpty() || p.tickCount % 5 != 0) return;
        CompoundTag pos = PENDING.get(p.getUUID());
        if (pos == null || !Boolean.TRUE.equals(authenticated(p))) return;
        PENDING.remove(p.getUUID());
        p.getPersistentData().remove(TAG);
        ResourceLocation dim = ResourceLocation.tryParse(pos.getString("dim"));
        ServerLevel level = dim == null ? null : p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level == null) return;
        double x = pos.getDouble("x"), y = pos.getDouble("y"), z = pos.getDouble("z");
        if (p.level() == level && p.distanceToSqr(x, y, z) < 4.0) return;   // ya está ahí
        p.stopRiding();
        p.teleportTo(level, x, y, z, pos.getFloat("yRot"), pos.getFloat("xRot"));
        p.fallDistance = 0.0F;
        WarbandCraft.LOGGER.info("Login: {} vuelve a donde estaba ({}, {}, {})", p.getName().getString(), (int) x, (int) y, (int) z);
    }
}
