package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.util.Ground;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * El "constructor ficticio" de las obras (H12) y los caminos (H14): un aldeano albañil sin IA e invulnerable que
 * se desliza hasta el bloque que se está poniendo. Lleva la marca {@link #TAG} con el dueño de la obra
 * ("town:&lt;centro&gt;" o "road:&lt;ruta&gt;") para reconocer a los que sobran.
 */
public final class BuilderNpc {
    public static final String TAG = "warbandcraft:builder";
    /** Velocidad (bloques por tick) y distancia a la que deja de caminar y aparece al lado. */
    private static double SPEED = 0.25, TELEPORT = 12;

    /** Hacia dónde camina cada constructor (en memoria). */
    private static final Map<UUID, Vec3> TARGETS = new HashMap<>();

    private BuilderNpc() {}

    /**
     * Mueve al constructor {@code current} hacia {@code target} (se para a un costado, del lado de {@code side}); si no
     * existe, lo crea ahí. Devuelve su UUID (el mismo o el nuevo), o null si no se pudo crear.
     */
    @Nullable
    public static UUID update(ServerLevel level, @Nullable UUID current, BlockPos target, BlockPos side, String owner) {
        Entity e = current == null ? null : level.getEntity(current);
        if (e == null) {
            Vec3 at = Ground.nearestStandable(level, target, 6);
            if (at == null) at = Vec3.atBottomCenterOf(target.above());
            Villager builder = EntityType.VILLAGER.create(level);
            if (builder == null) return null;
            builder.setVillagerData(builder.getVillagerData().setProfession(VillagerProfession.MASON).setLevel(5));
            builder.setNoAi(true);
            builder.setInvulnerable(true);
            builder.setPersistenceRequired();
            builder.setCustomName(Component.translatable("entity.warbandcraft.builder"));
            builder.setCustomNameVisible(true);
            builder.getPersistentData().putString(TAG, owner);
            builder.moveTo(at.x, at.y, at.z, 0, 0);
            level.addFreshEntity(builder);
            return builder.getUUID();
        }
        Vec3 dest = TARGETS.get(e.getUUID());
        if (dest == null || dest.distanceToSqr(Vec3.atCenterOf(target)) > 16) {
            Vec3 dir = Vec3.atCenterOf(side).subtract(Vec3.atCenterOf(target)).multiply(1, 0, 1);
            dir = dir.lengthSqr() < 1e-4 ? new Vec3(1.5, 0, 0) : dir.normalize().scale(1.5);
            dest = new Vec3(target.getX() + 0.5 + dir.x, target.getY(), target.getZ() + 0.5 + dir.z);
            TARGETS.put(e.getUUID(), dest);
        }
        Vec3 pos = e.position();
        Vec3 delta = new Vec3(dest.x - pos.x, 0, dest.z - pos.z);
        double dist = delta.length();
        float yaw = (float) (Mth.atan2(target.getZ() + 0.5 - pos.z, target.getX() + 0.5 - pos.x) * (180 / Math.PI)) - 90F;
        double nx = pos.x, ny, nz = pos.z;
        if (dist > 0.1) {
            Vec3 step = delta.scale(Math.min(1.0, SPEED / dist));
            nx += step.x;
            nz += step.z;
        }
        Integer y = Ground.surfaceY(level, Mth.floor(nx), Mth.floor(nz), Mth.floor(pos.y), 3, 6);
        ny = y == null ? pos.y : y;
        if (dist > TELEPORT) {
            Vec3 at = Ground.nearestStandable(level, BlockPos.containing(dest), 4);
            if (at != null) {
                nx = at.x;
                ny = at.y;
                nz = at.z;
            }
        }
        e.moveTo(nx, ny, nz, yaw, 0);
        e.setYHeadRot(yaw);
        return e.getUUID();
    }

    public static void remove(ServerLevel level, @Nullable UUID id) {
        if (id == null) return;
        Entity e = level.getEntity(id);
        if (e != null) e.discard();
        TARGETS.remove(id);
    }

    /** El dueño de la obra de un constructor ("town:..." / "road:..."), o null si no es un constructor. */
    @Nullable
    public static String ownerOf(Entity e) {
        return e.getPersistentData().contains(TAG) ? e.getPersistentData().getString(TAG) : null;
    }
}
