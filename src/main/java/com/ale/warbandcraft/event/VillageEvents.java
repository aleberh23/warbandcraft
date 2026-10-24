package com.ale.warbandcraft.event;

import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.command.WarbandCommand;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.GarrisonDefense;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageEconomy;
import com.ale.warbandcraft.village.VillageManager;
import com.ale.warbandcraft.village.VillageHud;
import com.ale.warbandcraft.network.ModNetworking;
import com.ale.warbandcraft.network.VillageHudMessage;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.raid.Raider;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Eventos de H2: detectar aldeas, cuidar a las guarniciones y avisarle al jugador dónde está. */
public class VillageEvents {
    /** H16: el Campamento de una aldea no se rompe (salvo en creativo: el almacén lo vuelve a poner). */
    /** Castillos viejos: cofres con la tabla de botín rota reciben la nuestra al abrirlos o romperlos. */
    @SubscribeEvent
    public void onOpenCastleChest(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level) com.ale.warbandcraft.village.CastleManager.fixOldLoot(level, event.getPos());
    }

    @SubscribeEvent
    public void onBreakCastleChest(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) com.ale.warbandcraft.village.CastleManager.fixOldLoot(level, event.getPos());
    }

    @SubscribeEvent
    public void onBreakVillageCamp(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !event.getPlayer().isCreative()
                && com.ale.warbandcraft.village.VillageStorehouse.isVillageCamp(level, event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable("msg.warbandcraft.village_camp.unbreakable")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }

    /** Aldea de facción: con la mano no se rompe nada (TNT y bichos sí). Antes que todo lo demás que mira el romper. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreakFactionVillage(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getPlayer() != null
                && com.ale.warbandcraft.village.FactionClaims.blocksBreaking(level, event.getPlayer(), event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(com.ale.warbandcraft.village.FactionClaims.denied(level, event.getPos()), true);
        }
    }

    /** Ni pisando los cultivos. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onTrampleFactionVillage(net.minecraftforge.event.level.BlockEvent.FarmlandTrampleEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof net.minecraft.world.entity.player.Player p
                && com.ale.warbandcraft.village.FactionClaims.blocksBreaking(level, p, event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** H16: ni explota. */
    @SubscribeEvent
    public void onExplodeVillageCamp(net.minecraftforge.event.level.ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            event.getAffectedBlocks().removeIf(p -> com.ale.warbandcraft.village.VillageStorehouse.isVillageCamp(level, p));
        }
    }

    private static int SCAN_EVERY_TICKS = 100;    // 5 segundos
    private static int ANNOUNCE_EVERY_TICKS = 20; // 1 segundo

    /** Última aldea en que estuvo cada jugador (para avisar solo al entrar). */
    private final Map<UUID, Long> lastVillage = new HashMap<>();
    /** La aldea del cartelito que tiene a la vista cada jugador (para esconderlo una sola vez al salir). */
    private final Map<UUID, Long> hudShown = new HashMap<>();
    private static int HUD_EVERY_TICKS = 40;   // 2 segundos
    private int tick;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tick % SCAN_EVERY_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            ServerLevel level = player.serverLevel();
            try {
                VillageManager.scan(level, player.blockPosition(), VillageManager.SCAN_RADIUS, VillageData.get(level));
                CastleManager.scan(level, player.blockPosition());
                com.ale.warbandcraft.dungeon.Dungeons.scan(level, player.blockPosition());   // H25: guaridas y túmulos
                com.ale.warbandcraft.village.FactionClaims.syncNear(level, player.blockPosition());   // claims de las facciones
                com.ale.warbandcraft.village.WorldTaverns.scan(level, player);   // tabernas del mundo (Dungeons and Taverns)
            } catch (Exception e) {
                WarbandCraft.LOGGER.error("Error buscando aldeas cerca de {}", player.getName().getString(), e);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % ANNOUNCE_EVERY_TICKS != 0) return;

        ServerLevel level = player.serverLevel();
        updateHud(player, level);
        CastleManager.trespassTick(player);   // entrar a los muros de un castillo de facción = te atacan
        com.ale.warbandcraft.village.Offenses.tick(player);   // te echaron de una aldea: salí o te atacan
        VillageData.Village village = VillageData.get(level).nearest(player.blockPosition(), VillageManager.ENTER_RADIUS);
        Long key = village == null ? null : village.center().asLong();
        Long previous = lastVillage.get(player.getUUID());
        if (key == null) {
            lastVillage.remove(player.getUUID());
            return;
        }
        if (key.equals(previous)) return;
        lastVillage.put(player.getUUID(), key);

        Faction faction = FactionManager.INSTANCE.get(village.faction());
        if (faction == null) return;
        Component of = Component.literal(faction.ofName()).withStyle(faction.color(), ChatFormatting.BOLD);
        String kind = village.castle() ? "castle" : "village";
        if (village.isConquered()) {
            Component msg = player.getUUID().equals(village.owner())
                    ? Component.translatable("msg.warbandcraft.enter_own_" + kind, VillageEconomy.taxOf(level, village)).withStyle(ChatFormatting.AQUA)
                    : Component.translatable("msg.warbandcraft.enter_other_" + kind, ownerName(level, village)).withStyle(ChatFormatting.GRAY);
            player.displayClientMessage(msg, true);
            // H10: si su almacén tiene algo, botón para retirarlo
            VillageStock.Stock stock = VillageStock.total(level, village);
            if (player.getUUID().equals(village.owner()) && !stock.isEmpty()) {
                player.sendSystemMessage(Component.translatable("msg.warbandcraft.stock.waiting", stock.describe(),
                                VillageStock.where(level, village))
                        .withStyle(ChatFormatting.AQUA).append(" ").append(VillageStock.withdrawButton()));
            }
            return;
        }
        if (village.isLooted(level.getGameTime())) {
            player.displayClientMessage(Component.translatable("msg.warbandcraft.enter_looted_" + kind, of).withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        int alive = VillageManager.garrison(level, village.center()).size();
        player.displayClientMessage(Component.translatable("msg.warbandcraft.enter_" + kind,
                Component.literal(faction.ofName()).withStyle(faction.color(), ChatFormatting.BOLD),
                Component.literal(String.valueOf(alive)).withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY), true);
    }

    /** El cartelito de resumen: cada 2 s mientras estás en una aldea/castillo; al salir, se esconde. */
    private void updateHud(ServerPlayer player, ServerLevel level) {
        VillageData.Village v = VillageHud.at(level, player.blockPosition());
        if (v == null) {
            if (hudShown.remove(player.getUUID()) != null) {
                ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new VillageHudMessage(null));
            }
            return;
        }
        Long shown = hudShown.put(player.getUUID(), v.center().asLong());
        if (shown != null && shown == v.center().asLong() && player.tickCount % HUD_EVERY_TICKS != 0) return;
        Component owner = v.isConquered() && !player.getUUID().equals(v.owner()) ? Component.literal(ownerName(level, v)) : Component.empty();
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new VillageHudMessage(VillageHud.info(player, v, owner)));
    }

    private static String ownerName(ServerLevel level, VillageData.Village v) {
        ServerPlayer p = v.owner() == null ? null : level.getServer().getPlayerList().getPlayer(v.owner());
        if (p != null) return p.getName().getString();
        return level.getServer().getProfileCache() == null ? "?" : level.getServer().getProfileCache().get(v.owner())
                .map(com.mojang.authlib.GameProfile::getName).orElse("?");
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastVillage.remove(event.getEntity().getUUID());
        hudShown.remove(event.getEntity().getUUID());
        com.ale.warbandcraft.village.Offenses.forget(event.getEntity().getUUID());
    }

    /** Romper heno o cultivos en una aldea de facción, con un testigo = te echan. */
    @SubscribeEvent
    public void onBreakVillageCrops(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getPlayer() instanceof ServerPlayer player
                && com.ale.warbandcraft.village.Offenses.isOffenseBlock(event.getState())) {
            com.ale.warbandcraft.village.Offenses.onOffense(player, event.getPos(), false);
        }
    }

    /** Abrir un cofre/barril en una aldea de facción, con un testigo = robo. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onOpenVillageChest(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)
                || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (player.isShiftKeyDown() && !player.getMainHandItem().isEmpty()) return;   // agachado con algo en la mano no abre
        if (level.getBlockEntity(event.getPos()) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity) {
            com.ale.warbandcraft.village.Offenses.onOffense(player, event.getPos(), true);
        }
    }

    /** La zona de patrulla no se guarda con la entidad: se vuelve a poner cada vez que el soldado se carga. */
    @SubscribeEvent
    public void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!mob.getPersistentData().contains(VillageManager.TAG_VILLAGE)) return;
        if (VillageManager.isGarrison(mob) && mob instanceof AbstractRecruitEntity soldier) {
            int radius = CastleManager.isCastleSoldier(soldier) ? CastleManager.PATROL_RADIUS : VillageManager.PATROL_RADIUS;
            soldier.restrictTo(BlockPos.of(soldier.getPersistentData().getLong(VillageManager.TAG_VILLAGE)), radius);
            GarrisonDefense.install(soldier);
        }
    }

    /** Cuando un soldado de guarnición va por un illager, avisa a los de su aldea. */
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (event.getNewTarget() instanceof Raider raider && event.getEntity() instanceof AbstractRecruitEntity soldier
                && VillageManager.isGarrison(soldier)) {
            GarrisonDefense.alertComrades(soldier, raider);
        }
    }

    /** Los soldados de guarnición no se contratan: sirven a su facción. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!VillageManager.isGarrison(event.getTarget())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof ServerPlayer player && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            Faction faction = FactionManager.INSTANCE.get(
                    net.minecraft.resources.ResourceLocation.tryParse(event.getTarget().getPersistentData().getString(VillageManager.TAG_FACTION)));
            Component who = faction == null ? Component.literal("?")
                    : Component.literal(faction.ofName()).withStyle(faction.color());
            player.displayClientMessage(Component.translatable("msg.warbandcraft.garrison_soldier", who).withStyle(ChatFormatting.GRAY), true);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        WarbandCommand.register(event.getDispatcher());
    }
}
