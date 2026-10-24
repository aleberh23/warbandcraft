package com.ale.warbandcraft.event;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.dungeon.Bandits;
import com.ale.warbandcraft.dungeon.Barrows;
import com.ale.warbandcraft.dungeon.DungeonData;
import com.ale.warbandcraft.dungeon.Dungeons;
import com.ale.warbandcraft.dungeon.FlatLands;
import com.ale.warbandcraft.dungeon.StructureDensity;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * H25: las mazmorras (guaridas y túmulos) y los biomas llanos.
 * Cada segundo: ataúdes, el jefe, si quedó limpia, si vuelve a llenarse. Muertes que cuentan, presos que se liberan con
 * click, las columnas de la puerta de las garras, los draugr contra tus soldados.
 */
public class DungeonEvents {
    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent event) {
        FlatLands.onServerAboutToStart(event.getServer());
    }

    /** Estructuras de otros mods según el bioma (antes de generar nada). */
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) StructureDensity.apply(level);
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        FlatLands.onServerStopped();
        Barrows.clearBars();
        Bandits.clearMemory();
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        if (level.getGameTime() % 20 != 7) return;
        try {
            Dungeons.tick(level);
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Error en el tick de mazmorras", e);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        Dungeons.onDeath(level, event.getEntity(), event.getSource().getEntity());
    }

    /** Pegarle a un bandido: los de su guarida salen todos (no es un ataque a una aldea). */
    @SubscribeEvent
    public void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity bandit) || !(bandit.level() instanceof ServerLevel level)) return;
        if (!bandit.getPersistentData().contains(Dungeons.TAG_DUNGEON) || !VillageManager.isGarrison(bandit)) return;
        if (event.getSource().getEntity() instanceof LivingEntity attacker && RaidManager.responsiblePlayer(attacker) != null) {
            Dungeons.onBanditHurt(level, bandit, attacker);
        }
    }

    /** Los de una mazmorra que se cargan de nuevo: los draugr vuelven a buscar a tus soldados. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (e instanceof Mob m && !(e instanceof AbstractRecruitEntity) && e.getPersistentData().contains(Dungeons.TAG_DUNGEON)) Barrows.targetSoldiers(m);
        if (e instanceof AbstractRecruitEntity r && Dungeons.isCaptive(r)) r.restrictTo(r.blockPosition(), 2);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (handle(event.getEntity(), event.getTarget(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (handle(event.getEntity(), event.getTarget(), event.getHand())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Las columnas no se rompen ni se les saca el ítem. */
    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (event.getTarget() instanceof ItemFrame f && f.getPersistentData().contains(Barrows.TAG_PILLAR)) event.setCanceled(true);
    }

    private static boolean handle(Player player, Entity target, InteractionHand hand) {
        boolean pillar = target instanceof ItemFrame f && f.getPersistentData().contains(Barrows.TAG_PILLAR);
        boolean captive = Dungeons.isCaptive(target);
        if (!pillar && !captive) return false;
        if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer sp)) return true;
        if (pillar) {
            Barrows.turn(sp.serverLevel(), (ItemFrame) target);
        } else {
            DungeonData.Dungeon d = DungeonData.get(sp.serverLevel()).byId(target.getPersistentData().getInt(Dungeons.TAG_CAPTIVE));
            Component msg = Dungeons.free(sp, (AbstractRecruitEntity) target);
            sp.displayClientMessage(msg, true);
            if (d != null) d.known.add(sp.getUUID());
        }
        return true;
    }
}
