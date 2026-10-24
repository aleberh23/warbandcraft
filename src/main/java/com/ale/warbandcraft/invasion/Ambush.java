package com.ale.warbandcraft.invasion;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.faction.Troop;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Emboscadas (H15b): si tu viaje por una ruta se cruza con un ejército enemigo en marcha, el viaje se corta y
 * aparecés a mitad de camino, peleando. El ejército deja de marchar: está ahí, contra vos. Si nadie se le acerca por
 * un rato, vuelve a su leva.
 */
public final class Ambush {
    /** Distancia (en horizontal) entre tu posición de viaje y el ejército para que te corte el paso. */
    public static int RADIUS = 48;
    /** Los emboscadores aparecen a esta distancia de vos. */
    public static int SPAWN_DISTANCE = 14;
    /** Marca de los soldados de un ejército en el campo (no van a ninguna aldea). */
    public static final String TAG_FIELD = "warbandcraft:field_army";
    /** Sin jugadores a esta distancia, el ejército del campo vuelve a casa (se borra y suma a la leva). */
    public static int GIVE_UP_DISTANCE = 128;

    private Ambush() {}

    /** Un ejército que te puede cortar el paso: una invasión contra vos o una marcha de una facción en guerra con vos. */
    public record Target(ResourceLocation faction, int size, Vec3 pos, @Nullable BlockPos invasion, @Nullable UUID march) {}

    /** El ejército enemigo en marcha a ≤{@link #RADIUS} de {@code at}, o null. */
    @Nullable
    public static Target find(ServerLevel level, UUID player, Vec3 at) {
        InvasionData data = InvasionData.get(level);
        long now = level.getGameTime();
        double r2 = (double) RADIUS * RADIUS;
        for (InvasionData.Invasion inv : data.all()) {
            if (!com.ale.warbandcraft.faction.Allies.same(inv.owner, player) || inv.state != InvasionData.State.MARCHING) continue;
            Vec3 p = AiWars.position(inv.from, inv.target, inv.startTick, inv.arriveTick, now);
            if (horizontalDistSq(p, at) <= r2) return new Target(inv.faction, inv.size, p, inv.target, null);
        }
        Relations rel = Relations.get(level.getServer());
        for (InvasionData.March m : data.marches()) {
            if (!rel.atWar(player, m.attacker)) continue;
            Vec3 p = AiWars.position(m.from, m.target, m.startTick, m.arriveTick, now);
            if (horizontalDistSq(p, at) <= r2) return new Target(m.attacker, m.size, p, null, m.id);
        }
        return null;
    }

    private static double horizontalDistSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** El ejército deja de marchar y aparece alrededor de {@code at} contra el jugador. Devuelve los soldados. */
    public static List<AbstractRecruitEntity> spring(ServerLevel level, @Nullable ServerPlayer player, BlockPos at, Target t) {
        InvasionData data = InvasionData.get(level);
        if (t.invasion() != null) data.remove(t.invasion());
        if (t.march() != null) data.removeMarch(t.march());
        List<AbstractRecruitEntity> out = new ArrayList<>();
        Faction f = FactionManager.INSTANCE.get(t.faction());
        if (f == null) return out;
        RandomSource random = level.getRandom();
        double a = random.nextDouble() * Math.PI * 2;
        BlockPos spawn = at.offset((int) Math.round(Math.cos(a) * SPAWN_DISTANCE), 0, (int) Math.round(Math.sin(a) * SPAWN_DISTANCE));
        for (int i = 0; i < t.size(); i++) {
            Troop troop = f.pickTroop(random);
            if (troop == null) break;
            AbstractRecruitEntity s = VillageManager.spawnTroop(level, spawn, f, troop, random, 4);
            if (s == null) continue;
            s.getPersistentData().putLong(VillageManager.TAG_VILLAGE, at.asLong());
            s.getPersistentData().putBoolean(TAG_FIELD, true);
            s.restrictTo(at, 24);
            out.add(s);
        }
        if (player != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.ambush", AiWars.name(f), out.size())
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        return out;
    }

    /**
     * H24: cada segundo, cada ejército del campo (emboscadas, marchas que cargaste) pelea con su general ({@link
     * com.ale.warbandcraft.battle.tactics.TeamAI}) contra el jugador más cercano y sus soldados. La Hueste va aparte.
     */
    public static void tick(ServerLevel level) {
        java.util.Map<Long, List<AbstractRecruitEntity>> groups = new java.util.HashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof AbstractRecruitEntity s) || !s.isAlive() || !s.getPersistentData().getBoolean(TAG_FIELD) || Hosts.isHostSoldier(s)
                    || com.ale.warbandcraft.battle.Surrender.is(s)) continue;
            groups.computeIfAbsent(s.getPersistentData().getLong(VillageManager.TAG_VILLAGE), k -> new ArrayList<>()).add(s);
        }
        for (java.util.Map.Entry<Long, List<AbstractRecruitEntity>> g : groups.entrySet()) {
            Vec3 c = com.ale.warbandcraft.battle.BattleGeneral.centroid(g.getValue());
            net.minecraft.world.entity.player.Player p = level.getNearestPlayer(c.x, c.y, c.z, 96, false);
            if (!(p instanceof ServerPlayer sp) || sp.isSpectator()) continue;
            List<net.minecraft.world.entity.LivingEntity> enemies = new ArrayList<>(level.getEntitiesOfClass(AbstractRecruitEntity.class,
                    new AABB(c, c).inflate(64, 32, 64), r -> r.isAlive() && com.ale.warbandcraft.faction.Allies.same(r.getOwnerUUID(), sp.getUUID())
                            && com.ale.warbandcraft.ai.Soldiers.isSoldier(r) && !com.ale.warbandcraft.battle.Surrender.is(r)));
            if (!sp.isCreative()) enemies.add(sp);
            Vec3 away = c.subtract(sp.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
            com.ale.warbandcraft.battle.tactics.TeamAI.tick(level, "field:" + g.getKey(), g.getValue(), enemies,
                    new com.ale.warbandcraft.battle.tactics.TeamAI.Context(c.add(away.scale(40)), null, true));
        }
    }

    /** Ejércitos del campo sin nadie cerca: vuelven a su leva (cada 10 s). */
    public static void giveUp(ServerLevel level) {
        List<Entity> gone = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof AbstractRecruitEntity) || !e.getPersistentData().getBoolean(TAG_FIELD) || !e.isAlive()) continue;
            if (Hosts.isHostSoldier(e)) continue;   // H23: la hueste se maneja sola
            if (level.getNearestPlayer(e, GIVE_UP_DISTANCE) == null) gone.add(e);
        }
        InvasionData data = InvasionData.get(level);
        for (Entity e : gone) {
            ResourceLocation f = ResourceLocation.tryParse(e.getPersistentData().getString(VillageManager.TAG_FACTION));
            if (f != null) data.setLevy(f, Math.min(Invasions.LEVY_CAP, data.levy(f) + 1));
            if (e.getVehicle() != null) e.getVehicle().discard();
            e.discard();
        }
    }

    /** Cuántos emboscadores siguen en pie cerca de {@code at} (para las pruebas). */
    public static int fieldNear(ServerLevel level, BlockPos at) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(at).inflate(48),
                e -> e.isAlive() && e.getPersistentData().getBoolean(TAG_FIELD)).size();
    }
}
