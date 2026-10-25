package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.ai.Soldiers;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.workers.entities.workarea.AbstractWorkAreaEntity;
import com.talhanation.workers.entities.workarea.StorageArea;
import com.talhanation.workers.init.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Puente con Villager Workers (H10). Sus trabajadores dejan lo que producen en los cofres que estén dentro de
 * una "zona de almacén" (StorageArea). El Campamento es un cofre más para ellos, así que una zona que lo
 * cubra a él y a sus cofres vinculados hace que la producción entre sola al almacén.
 */
public final class WorkerZone {
    /** Tamaño mínimo de la zona (ancho/alto/fondo), como la que pone Workers por defecto. */
    public static int MIN_SIZE = 5;
    /** Hasta dónde se buscan zonas ya puestas que se superpongan. */
    private static int SEARCH = CampBlockEntity.WORK_RADIUS + 16;

    public enum Result { CREATED, OVERLAPPING, FAILED }

    private WorkerZone() {}

    /**
     * Pedido de Ale ("mi compañero no puede ni abrir el Campamento"): Workers no deja tocar cofres ni bloques adentro de una zona
     * que no es tuya salvo que la zona tenga acceso de equipo. Las zonas de un jugador con facción de Recruits quedan abiertas a
     * todo su equipo (sin facción, como estaban).
     */
    public static void share(AbstractWorkAreaEntity area) {
        String team = com.ale.warbandcraft.faction.Allies.factionOf(area.getPlayerUUID());
        if (team == null) return;
        if (!area.getTeamAccess()) area.setTeamAccess(true);
        if (!team.equals(area.getTeamStringID())) area.setTeamStringID(team);
    }

    /**
     * Antes de que Workers mire un click (sus zonas a 8 de {@code pos}, como él): las zonas de un compañero de facción se abren
     * al equipo. Así andan también las zonas de antes y las de alguien que entró a la facción después.
     */
    public static void shareAround(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level, BlockPos pos) {
        shareAround(player, level, pos, 8);
    }

    public static void shareAround(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level, BlockPos pos, int radius) {
        for (AbstractWorkAreaEntity area : level.getEntitiesOfClass(AbstractWorkAreaEntity.class, new AABB(pos).inflate(radius))) {
            UUID owner = area.getPlayerUUID();
            if (owner != null && !owner.equals(player.getUUID()) && com.ale.warbandcraft.faction.Allies.same(owner, player.getUUID())) share(area);
        }
    }

    /** La caja (en bloques, inclusiva) que encierra al Campamento y sus vinculados, +1 de margen y mínimo 5. */
    public static BoundingBox boxFor(CampBlockEntity camp) {
        BlockPos c = camp.getBlockPos();
        int x0 = c.getX(), y0 = c.getY(), z0 = c.getZ(), x1 = x0, y1 = y0, z1 = z0;
        for (BlockPos p : camp.getLinked()) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        x0--; z0--; x1++; z1++; y1++;
        int[] xs = grow(x0, x1), ys = {y0, Math.max(y1, y0 + 2)}, zs = grow(z0, z1);
        return new BoundingBox(xs[0], ys[0], zs[0], xs[1], ys[1], zs[1]);
    }

    private static int[] grow(int a, int b) {
        int missing = MIN_SIZE - (b - a + 1);
        if (missing > 0) {
            a -= missing / 2;
            b += missing - missing / 2;
        }
        return new int[]{a, b};
    }

    public record BoundingBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        public int width() { return x1 - x0 + 1; }
        public int height() { return y1 - y0 + 1; }
        public int depth() { return z1 - z0 + 1; }
        public AABB aabb() { return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1); }
    }

    /** Crea la zona de almacén de Workers para el Campamento, del jugador {@code owner}. */
    public static Result create(ServerLevel level, CampBlockEntity camp, UUID owner, String ownerName) {
        BoundingBox box = boxFor(camp);
        if (existing(level, box.aabb()) != null) return Result.OVERLAPPING;
        return storage(level, box, owner, ownerName, Component.literal(camp.nameString())) != null ? Result.CREATED : Result.FAILED;
    }

    /** H16: una zona de almacén de Workers sobre {@code box} (sin mirar si se superpone). Null si no se pudo. */
    @Nullable
    public static StorageArea storage(ServerLevel level, BoundingBox box, UUID owner, String ownerName, Component name) {
        StorageArea area = ModEntityTypes.STORAGEAREA.get().create(level);
        if (area == null) return null;
        area.setWidthSize(box.width() + 1);   // Workers arma la caja un bloque más corta
        area.setHeightSize(box.height());
        area.setDepthSize(box.depth() + 1);
        area.setPlayerUUID(owner);
        area.setPlayerName(ownerName);
        share(area);
        area.setCustomName(name);
        area.setStorageTypes(area.getStorageMask(EnumSet.allOf(StorageArea.StorageType.class)));
        if (!place(area, box)) {
            area.discard();
            return null;
        }
        area.setDone(false);
        level.addFreshEntity(area);
        area.scanStorageBlocks();
        return area;
    }

    /**
     * Workers arma la caja desde la posición de la entidad hacia donde mira (la orientación decide hacia qué
     * lado crece). Se prueba cada orientación hasta encontrar una cuya caja empiece en la esquina pedida.
     * Según hacia dónde crece, la entidad queda en una esquina de bloque (x0 / z0) o un bloque afuera (x1 + 1 / z1 + 1):
     * antes solo se probaban x1 / z1 y los rectángulos que no eran cuadrados no entraban nunca ("No se pudo crear la zona").
     * Primero la caja justa; si no hay, una que lo cubra.
     */
    public static boolean place(AbstractWorkAreaEntity area, BoundingBox box) {
        int[] xs = {box.x0(), box.x1() + 1, box.x1(), box.x0() - 1}, zs = {box.z0(), box.z1() + 1, box.z1(), box.z0() - 1};
        for (boolean exact : new boolean[]{true, false}) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                area.setFacing(facing);
                for (int x : xs) {
                    for (int z : zs) {
                        area.moveTo(x + 0.5, box.y0(), z + 0.5, 0, 0);
                        AABB made = area.createArea();
                        if (exact ? fits(made, box) : covers(made, box)) {
                            area.area = made;
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** La caja de Workers es justo la marcada (de costado). */
    private static boolean fits(AABB made, BoundingBox box) {
        return made.minX == box.x0() && made.minZ == box.z0() && made.maxX == box.x1() + 1 && made.maxZ == box.z1() + 1
                && made.minY <= box.y0();
    }

    /** La caja de Workers (de esquina a esquina de bloques) contiene a todos los bloques de {@code box}. */
    private static boolean covers(AABB made, BoundingBox box) {
        return made.minX <= box.x0() && made.minZ <= box.z0() && made.maxX >= box.x1() + 1 && made.maxZ >= box.z1() + 1
                && made.minY <= box.y0();
    }

    /** Una zona de Workers que ya se superpone con {@code box}, o null. */
    @Nullable
    public static AbstractWorkAreaEntity existing(ServerLevel level, AABB box) {
        List<AbstractWorkAreaEntity> near = level.getEntitiesOfClass(AbstractWorkAreaEntity.class, box.inflate(SEARCH),
                a -> a.isAlive() && a.getArea() != null && a.getArea().intersects(box));
        return near.isEmpty() ? null : near.get(0);
    }

    /** Trabajadores de Workers del jugador dentro de la jurisdicción del Campamento. */
    public static int workersNear(ServerLevel level, BlockPos camp, @Nullable UUID owner) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, CampBlockEntity.area(level, camp),
                w -> w.isAlive() && Soldiers.isWorker(w) && (owner == null || com.ale.warbandcraft.faction.Allies.same(w.getOwnerUUID(), owner))
                        && CampBlockEntity.covers(level, camp, w.blockPosition())).size();
    }
}
