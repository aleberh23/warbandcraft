package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampMenu;
import com.ale.warbandcraft.camp.KitEditorMenu;
import com.ale.warbandcraft.camp.ShipmentMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, WarbandCraft.MODID);

    /** Pestaña Cofre del Campamento (H6: el cofre de siempre, con pestañas). */
    public static final RegistryObject<MenuType<CampMenu>> CAMP = MENUS.register("camp",
            () -> IForgeMenuType.create(CampMenu::fromNetwork));

    /** Pestaña Kits del Campamento (H6). */
    public static final RegistryObject<MenuType<KitEditorMenu>> KIT_EDITOR = MENUS.register("kit_editor",
            () -> IForgeMenuType.create(KitEditorMenu::fromNetwork));

    /** Pestaña Envíos del Campamento (H13). */
    public static final RegistryObject<MenuType<ShipmentMenu>> SHIPMENTS = MENUS.register("shipments",
            () -> IForgeMenuType.create(ShipmentMenu::fromNetwork));

    private ModMenus() {}
}
