package com.ale.warbandcraft.event;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.kit.PlayerKits;
import com.ale.warbandcraft.sapper.SapperGoal;
import com.ale.warbandcraft.sapper.SapperRules;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Listeners de H6 (tu facción): doctrina en soldados/caballos/flechas, IA extra del Zapador,
 * TNT reforzado de Ingeniería y re-armado automático cerca de los Campamentos.
 */
public class MyFactionEvents {
    private static final String TAG_ARROW_BOOSTED = "warbandcraft:boosted";
    /** Cada cuánto se revisa si hay soldados con kit viejo cerca de un Campamento. */
    private static int REARM_INTERVAL = 20 * 10;

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();

        if (entity instanceof AbstractRecruitEntity soldier && Soldiers.isSoldier(soldier)) {
            if (soldier.getOwnerUUID() != null) Doctrine.apply(soldier, doctrine(level.getServer(), soldier.getOwnerUUID()));
            // Aunque no tenga dueño todavía (se contrata después): sin órdenes la IA no hace nada.
            if (SapperRules.isSapper(soldier)) soldier.goalSelector.addGoal(1, new SapperGoal(soldier));
        } else if (entity instanceof AbstractHorse horse && horse.getPersistentData().hasUUID(ClassAssigner.TAG_HORSE_OWNER)) {
            Doctrine.applyHorse(horse, doctrine(level.getServer(), horse.getPersistentData().getUUID(ClassAssigner.TAG_HORSE_OWNER)));
        } else if (entity instanceof AbstractArrow arrow && !arrow.getPersistentData().getBoolean(TAG_ARROW_BOOSTED)
                && arrow.getOwner() instanceof AbstractRecruitEntity shooter && shooter.getOwnerUUID() != null
                && doctrine(level.getServer(), shooter.getOwnerUUID()) == Doctrine.ARCHERY) {
            arrow.setBaseDamage(arrow.getBaseDamage() * Doctrine.ARROW_DAMAGE_MULTIPLIER);
            arrow.getPersistentData().putBoolean(TAG_ARROW_BOOSTED, true); // no volver a subirlo al recargar el chunk
        }
    }

    /** Ingeniería: el TNT de tus zapadores explota más fuerte (se reemplaza la explosión normal). */
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Start event) {
        if (!(event.getExplosion().getExploder() instanceof PrimedTnt tnt)) return;
        if (!tnt.getPersistentData().getBoolean(SapperRules.TAG_STRONG_TNT)) return;
        event.setCanceled(true);
        tnt.getPersistentData().remove(SapperRules.TAG_STRONG_TNT);
        tnt.level().explode(tnt, tnt.getX(), tnt.getY(0.0625), tnt.getZ(), Doctrine.BLAST_POWER_ENGINEERING,
                Level.ExplosionInteraction.TNT);
    }

    /** TNT de asedio: no hace cráter, abre un paso de 1 x 2 que atraviesa el muro (pedido de Ale). */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public void onSiegeBlast(ExplosionEvent.Detonate event) {
        if (!(event.getExplosion().getExploder() instanceof PrimedTnt tnt)) return;
        if (!tnt.getPersistentData().contains(SapperRules.TAG_HOLE) || !(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().clear();
        SapperRules.openHole(level, tnt, event.getExplosion().getPosition());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % REARM_INTERVAL != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerKits.rearmNear(player.serverLevel(), player.getUUID(), false);
        }
    }

    @Nullable
    private static Doctrine doctrine(MinecraftServer server, UUID player) {
        return PlayerFactionData.get(server).doctrine(player);
    }

    /** Después de cambiar de doctrina: la aplica ya a todos los soldados y caballos cargados del jugador. */
    public static void reapplyDoctrine(MinecraftServer server, UUID player) {
        Doctrine d = doctrine(server, player);
        for (ServerLevel level : server.getAllLevels()) {
            for (AbstractRecruitEntity soldier : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                    r -> Objects.equals(r.getOwnerUUID(), player) && Soldiers.isSoldier(r))) {
                Doctrine.apply(soldier, d);
            }
            for (AbstractHorse horse : level.getEntities(EntityTypeTest.forClass(AbstractHorse.class),
                    h -> h.getPersistentData().hasUUID(ClassAssigner.TAG_HORSE_OWNER)
                            && player.equals(h.getPersistentData().getUUID(ClassAssigner.TAG_HORSE_OWNER)))) {
                Doctrine.applyHorse(horse, d);
            }
        }
    }
}
