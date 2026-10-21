package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.item.MarshalBatonItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, WarbandCraft.MODID);

    public static final RegistryObject<Item> CAMP = ITEMS.register("camp",
            () -> new BlockItem(ModBlocks.CAMP.get(), new Item.Properties()));

    public static final RegistryObject<Item> ROUTE_POST = ITEMS.register("route_post",
            () -> new BlockItem(ModBlocks.ROUTE_POST.get(), new Item.Properties()));

    /** Bastón de Mariscal: elige una clase y se la asigna a un soldado. */
    public static final RegistryObject<Item> MARSHAL_BATON = ITEMS.register("marshal_baton",
            () -> new MarshalBatonItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    /** Armamento (H10): lote de armas y placas que reemplaza piezas de metal al armar. Lo hacen los herreros. */
    public static final RegistryObject<Item> ARMAMENT = ITEMS.register("armament",
            () -> new Item(new Item.Properties()));

    private ModItems() {}
}
