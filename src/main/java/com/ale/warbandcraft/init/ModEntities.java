package com.ale.warbandcraft.init;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.sapper.FieldCannonEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, WarbandCraft.MODID);

    /** Cañón de campaña del Zapador (H6). */
    public static final RegistryObject<EntityType<FieldCannonEntity>> FIELD_CANNON = ENTITIES.register("field_cannon",
            () -> EntityType.Builder.<FieldCannonEntity>of(FieldCannonEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(10).build("field_cannon"));

    /** H16: el Herrero (trabajador hecho sobre la base de Villager Workers). */
    public static final RegistryObject<EntityType<com.ale.warbandcraft.worker.SmithEntity>> SMITH = ENTITIES.register("smith",
            () -> EntityType.Builder.<com.ale.warbandcraft.worker.SmithEntity>of(com.ale.warbandcraft.worker.SmithEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).build("smith"));

    /** H19: el Tabernero (trabajador hecho sobre la base de Villager Workers). */
    public static final RegistryObject<EntityType<com.ale.warbandcraft.worker.InnkeeperEntity>> INNKEEPER = ENTITIES.register("innkeeper",
            () -> EntityType.Builder.<com.ale.warbandcraft.worker.InnkeeperEntity>of(com.ale.warbandcraft.worker.InnkeeperEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).build("innkeeper"));

    /** H24: el Mercader (trabajador hecho sobre la base de Villager Workers). */
    public static final RegistryObject<EntityType<com.ale.warbandcraft.worker.MerchantEntity>> MERCHANT = ENTITIES.register("merchant",
            () -> EntityType.Builder.<com.ale.warbandcraft.worker.MerchantEntity>of(com.ale.warbandcraft.worker.MerchantEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).build("merchant"));

    /** El Organizador (trabajador hecho sobre la base de Villager Workers): ordena los cofres del almacén. */
    public static final RegistryObject<EntityType<com.ale.warbandcraft.worker.OrganizerEntity>> ORGANIZER = ENTITIES.register("organizer",
            () -> EntityType.Builder.<com.ale.warbandcraft.worker.OrganizerEntity>of(com.ale.warbandcraft.worker.OrganizerEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).build("organizer"));

    /** Pedido de Ale: el de Mantenimiento (trabajador hecho sobre la base de Villager Workers): embellece la aldea. */
    public static final RegistryObject<EntityType<com.ale.warbandcraft.worker.MaintainerEntity>> MAINTAINER = ENTITIES.register("maintainer",
            () -> EntityType.Builder.<com.ale.warbandcraft.worker.MaintainerEntity>of(com.ale.warbandcraft.worker.MaintainerEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.95F).clientTrackingRange(10).build("maintainer"));

    private ModEntities() {}
}
