package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, WarbandCraft.MODID);

    /** Mesa de Intendencia: el Campamento donde se arman las tropas. */
    public static final RegistryObject<Block> CAMP = BLOCKS.register("camp",
            () -> new CampBlock(BlockBehaviour.Properties.copy(Blocks.BARREL).strength(2.5F)));

    private ModBlocks() {}
}
