package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, WarbandCraft.MODID);

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<CampBlockEntity>> CAMP = BLOCK_ENTITIES.register("camp",
            () -> BlockEntityType.Builder.of(CampBlockEntity::new, ModBlocks.CAMP.get()).build(null));

    private ModBlockEntities() {}
}
