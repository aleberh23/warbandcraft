package com.ale.warbandcraft.client;

import com.ale.warbandcraft.client.camp.CampScreen;
import com.ale.warbandcraft.client.camp.KitEditorScreen;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.init.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Registros de solo-cliente de H6: pantallas de las pestañas del Campamento y el dibujo del cañón. */
@OnlyIn(Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SuppressWarnings("unchecked")
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // CampMenu es un ChestMenu: su pantalla es la del cofre de vanilla con pestañas.
            MenuScreens.<ChestMenu, CampScreen>register((MenuType<ChestMenu>) (MenuType<?>) ModMenus.CAMP.get(), CampScreen::new);
            MenuScreens.register(ModMenus.KIT_EDITOR.get(), KitEditorScreen::new);
            MenuScreens.register(ModMenus.SHIPMENTS.get(), com.ale.warbandcraft.client.camp.ShipmentScreen::new);
        });
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.FIELD_CANNON.get(), FieldCannonRenderer::new);
        event.registerEntityRenderer(ModEntities.SMITH.get(), com.talhanation.workers.client.render.WorkerVillagerRenderer::new);   // H16
        event.registerEntityRenderer(ModEntities.INNKEEPER.get(), com.talhanation.workers.client.render.WorkerVillagerRenderer::new);   // H19
        event.registerEntityRenderer(ModEntities.MERCHANT.get(), com.talhanation.workers.client.render.WorkerVillagerRenderer::new);   // H24
        event.registerEntityRenderer(ModEntities.ORGANIZER.get(), com.talhanation.workers.client.render.WorkerVillagerRenderer::new);   // Organizador
    }
}
