package com.ale.warbandcraft;

import com.ale.warbandcraft.event.CommonEvents;
import com.ale.warbandcraft.init.ModBlockEntities;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.init.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
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

        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        modBus.addListener(this::addCreative);

        MinecraftForge.EVENT_BUS.register(new CommonEvents());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.CAMP);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.MARSHAL_BATON);
        }
    }
}
