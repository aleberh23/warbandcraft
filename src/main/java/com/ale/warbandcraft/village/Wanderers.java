package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pedido de Ale: reclutas sueltos que andan por el mapa, para no tener que ir sí o sí a una aldea. Grupitos de 1-3
 * reclutas de Recruits sin dueño y de nivel 0 (menos que los de la taberna), que caminan de a tramos cortos. Se
 * contratan como cualquier recluta suelto de Recruits (su pantalla). No se guardan: al descargarse se van.
 * Si pasan cerca de una aldea, la taberna los mete adentro como a los demás ({@link Tavern#isHireable}).
 */
public final class Wanderers {
    /** Marca de un recluta errante (dónde apareció). */
    public static final String TAG = "warbandcraft:wanderer";

    /** Chance por jugador y por minuto de que aparezca un grupito. */
    public static double CHANCE = 0.35;
    /** Con tantos errantes cerca del jugador ya no aparecen más. */
    public static int MAX_NEAR = 4;
    /** Tamaño del grupito. */
    public static int GROUP_MIN = 1, GROUP_MAX = 3;
    /** A qué distancia del jugador aparecen. */
    public static int SPAWN_MIN = 40, SPAWN_MAX = 90;
    /** No aparecen tan cerca de una aldea/castillo (ahí ya hay reclutas). */
    public static int VILLAGE_DISTANCE = 80;
    /** Largo de cada tramo que caminan. */
    public static int STEP_MIN = 10, STEP_MAX = 24;
    /** Chance de que sea arquero (si no, de a pie; algunos con escudo). */
    public static double BOWMAN_CHANCE = 0.3, SHIELD_CHANCE = 0.15;
    /** Nivel de Recruits con el que aparecen. */
    public static int XP_LEVEL = 0;

    private Wanderers() {}

    /** Cada minuto: cerca de cada jugador puede aparecer un grupito. */
    public static void spawnNearPlayers(ServerLevel level) {
        RandomSource r = level.getRandom();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || r.nextDouble() >= CHANCE || near(level, p.blockPosition()) >= MAX_NEAR) continue;
            spawn(level, p);
        }
    }

    /** Aparece un grupito a {@link #SPAWN_MIN}-{@link #SPAWN_MAX} del jugador, en tierra y lejos de aldeas. */
    public static List<AbstractRecruitEntity> spawn(ServerLevel level, ServerPlayer player) {
        List<AbstractRecruitEntity> out = new ArrayList<>();
        RandomSource r = level.getRandom();
        VillageData data = VillageData.get(level);
        BlockPos at = null;
        for (int i = 0; i < 12 && at == null; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int d = SPAWN_MIN + r.nextInt(Math.max(1, SPAWN_MAX - SPAWN_MIN + 1));
            BlockPos ground = ground(level, player.getBlockX() + (int) Math.round(Math.cos(a) * d), player.getBlockZ() + (int) Math.round(Math.sin(a) * d));
            if (ground == null || data.nearest(ground, VILLAGE_DISTANCE) != null) continue;
            at = ground;
        }
        if (at == null) return out;
        int n = GROUP_MIN + r.nextInt(Math.max(1, GROUP_MAX - GROUP_MIN + 1));
        for (int i = 0; i < n; i++) {
            double roll = r.nextDouble();
            String type = roll < BOWMAN_CHANCE ? "bowman" : roll < BOWMAN_CHANCE + SHIELD_CHANCE ? "recruit_shieldman" : "recruit";
            AbstractRecruitEntity s = create(level, type);
            if (s == null) continue;
            s.moveTo(at.getX() + 0.5 + r.nextInt(3) - 1, at.getY(), at.getZ() + 0.5 + r.nextInt(3) - 1, r.nextFloat() * 360F, 0F);
            s.setIsOwned(false);
            s.setAggroState(0);   // se defienden si los atacan
            s.getPersistentData().putLong(TAG, at.asLong());
            level.addFreshEntity(s);
            out.add(s);
        }
        if (!out.isEmpty()) {
            player.displayClientMessage(Component.translatable("msg.warbandcraft.wanderers.seen", out.size()), true);
            WarbandCraft.LOGGER.info("Reclutas errantes ({}) en {}", out.size(), at);
        }
        return out;
    }

    /** Cada 10 s: los contratados dejan de ser errantes; los demás, si llegaron (o no llegan), a otro tramo. */
    public static void tick(ServerLevel level) {
        RandomSource r = level.getRandom();
        List<AbstractRecruitEntity> all = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class), e -> e.isAlive() && e.getPersistentData().contains(TAG), all);
        for (AbstractRecruitEntity s : all) {
            if (s.getIsOwned()) {
                s.getPersistentData().remove(TAG);
                continue;
            }
            if (s.getTarget() != null || Tavern.isKept(s.getUUID())) continue;   // pelea, o una taberna ya lo tiene
            Vec3 hold = s.getHoldPos();
            if (s.getFollowState() == 3 && hold != null && s.position().distanceToSqr(hold) > 9 && r.nextInt(6) != 0) continue;
            for (int i = 0; i < 6; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                int d = STEP_MIN + r.nextInt(Math.max(1, STEP_MAX - STEP_MIN + 1));
                BlockPos g = ground(level, s.getBlockX() + (int) Math.round(Math.cos(a) * d), s.getBlockZ() + (int) Math.round(Math.sin(a) * d));
                if (g == null || Math.abs(g.getY() - s.getBlockY()) > 6) continue;
                s.setHoldPos(Vec3.atBottomCenterOf(g));
                s.setFollowState(3);
                break;
            }
        }
    }

    /** Al cargarse del disco: un errante sin dueño no vuelve (devuelve true = cancelar); uno contratado pierde la marca. */
    public static boolean onLoad(AbstractRecruitEntity s) {
        if (!s.getPersistentData().contains(TAG)) return false;
        if (s.getIsOwned()) {
            s.getPersistentData().remove(TAG);
            return false;
        }
        return true;
    }

    /** Errantes sin dueño a 128 bloques. */
    public static int near(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(128, 64, 128),
                e -> e.isAlive() && !e.getIsOwned() && e.getPersistentData().contains(TAG)).size();
    }

    @Nullable
    private static BlockPos ground(ServerLevel level, int x, int z) {
        BlockPos probe = new BlockPos(x, 64, z);
        if (!level.isPositionEntityTicking(probe)) return null;
        BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.getFluidState(g).isEmpty() || !level.getFluidState(g.below()).isEmpty()) return null;
        return g;
    }

    @Nullable
    private static AbstractRecruitEntity create(ServerLevel level, String type) {
        EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", type));
        Entity e = t == null ? null : t.create(level);
        if (!(e instanceof AbstractRecruitEntity s)) {
            if (e != null) e.discard();
            return null;
        }
        try {
            s.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException ignored) {
            // como en las guarniciones: Recruits a veces falla acá y no importa
        }
        if (XP_LEVEL > 0) Tavern.levelUp(s, XP_LEVEL);
        return s;
    }
}
