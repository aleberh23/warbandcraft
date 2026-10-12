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

    /** Bastón de Mariscal: elige una clase y se la asigna a un soldado. */
    public static final RegistryObject<Item> MARSHAL_BATON = ITEMS.register("marshal_baton",
            () -> new MarshalBatonItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    private ModItems() {}
}
