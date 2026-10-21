package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlock;
import com.ale.warbandcraft.route.RoutePostBlock;
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

    /** H13: Poste de caminos, abre la pantalla de Rutas para viajar desde ahí. */
    public static final RegistryObject<Block> ROUTE_POST = BLOCKS.register("route_post",
            () -> new RoutePostBlock(BlockBehaviour.Properties.copy(Blocks.OAK_FENCE).strength(2.0F).noOcclusion()));

    private ModBlocks() {}
}
