package com.ale.warbandcraft;

import com.ale.warbandcraft.event.BarracksEvents;
import com.ale.warbandcraft.event.CommonEvents;
import com.ale.warbandcraft.event.MyFactionEvents;
import com.ale.warbandcraft.event.RaidEvents;
import com.ale.warbandcraft.event.SoldierEvents;
import com.ale.warbandcraft.event.RouteEvents;
import com.ale.warbandcraft.event.VillageEvents;
import com.ale.warbandcraft.init.ModBlockEntities;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.init.ModItems;
import com.ale.warbandcraft.init.ModMenus;
import com.ale.warbandcraft.network.ModNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Warband Craft: addon de Villager Recruits que convierte Minecraft en un Mount & Blade.
 * Ver DESIGN.md / CLAUDE.md en la raíz del proyecto.
 */
@Mod(WarbandCraft.MODID)
public class WarbandCraft {
    public static final String MODID = "warbandcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WarbandCraft() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModNetworking.register();

        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        com.ale.warbandcraft.config.Tuning.register(modBus);
        com.ale.warbandcraft.dungeon.FlatLands.register(modBus);    // H25: más biomas llanos
        com.ale.warbandcraft.dungeon.DungeonLoot.register(modBus);  // H25: armamento en los cofres de las mazmorras
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.ale.warbandcraft.compat.FallingTreeSetup::apply));   // tala de árboles más lenta según el tamaño
        modBus.addListener(com.ale.warbandcraft.dungeon.CompatPacks::onAddPackFinders);   // H25: Cataclysm más espaciado   // todos los números en config/warbandcraft-common.toml

        modBus.addListener(this::addCreative);
        modBus.addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(com.ale.warbandcraft.init.ModEntities.SMITH.get(), com.ale.warbandcraft.worker.SmithEntity.setAttributes().build()));   // H16
        modBus.addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(com.ale.warbandcraft.init.ModEntities.INNKEEPER.get(), com.ale.warbandcraft.worker.InnkeeperEntity.setAttributes().build()));   // H19
        modBus.addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(com.ale.warbandcraft.init.ModEntities.MERCHANT.get(), com.ale.warbandcraft.worker.MerchantEntity.setAttributes().build()));   // H24
        modBus.addListener((net.minecraftforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(com.ale.warbandcraft.init.ModEntities.ORGANIZER.get(), com.ale.warbandcraft.worker.OrganizerEntity.setAttributes().build()));   // Organizador

        MinecraftForge.EVENT_BUS.register(new CommonEvents());
        MinecraftForge.EVENT_BUS.register(new VillageEvents());
        MinecraftForge.EVENT_BUS.register(new RaidEvents());
        MinecraftForge.EVENT_BUS.register(new MyFactionEvents());
        MinecraftForge.EVENT_BUS.register(new BarracksEvents());
        MinecraftForge.EVENT_BUS.register(new SoldierEvents());
        MinecraftForge.EVENT_BUS.register(new RouteEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.BattleEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.UpgradeEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.InvasionEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.TavernEvents());   // H19
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.NavEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.ClaimFixEvents());
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.ArmyEvents());   // H20
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.DungeonEvents());   // H25
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.TutorialEvents());   // salón del tutorial en el spawn
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.kit.WeaponRules());   // H26: peso de la armadura
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.ShutdownGuard());   // servidor dedicado: que el proceso termine al apagar
        MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.event.LoginPositionEvents());
        // Compañeros de facción: lo de la facción es de todos sus miembros (faction/Allies)
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerAboutToStartEvent e) -> com.ale.warbandcraft.faction.Allies.setServer(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> com.ale.warbandcraft.faction.Allies.setServer(null));   // login con contraseña: volver a donde estabas

        // Vista aérea (H5): registro cliente-only. Un servidor dedicado nunca debe cargar estas clases
        // (KeyMapping, Minecraft, ...), por eso va detrás de DistExecutor, igual que en network/.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            modBus.addListener(com.ale.warbandcraft.client.ModKeyMappings::onRegister);
            modBus.addListener(com.ale.warbandcraft.client.ClientSetup::onClientSetup);
            modBus.addListener(com.ale.warbandcraft.client.ClientSetup::onRegisterRenderers);
            modBus.addListener(com.ale.warbandcraft.client.VillageHudOverlay::onRegisterOverlays);
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.VillageHudOverlay());
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.aerial.AerialCameraController());
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.route.RouteClientHandler());
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.route.MapMarkers());
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.panel.PanelClientHandler());
            MinecraftForge.EVENT_BUS.register(new com.ale.warbandcraft.client.ChoiceScreen.KeyHandler());   // H23: tecla B
        });
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.CAMP);
            event.accept(ModItems.ROUTE_POST);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.MARSHAL_BATON);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.ARMAMENT);
        }
    }
}
