package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.init.ModItems;
import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Supplier;

/**
 * H25: lo que encontrás abajo sirve arriba. A los cofres de las mazmorras de otros mods (los de {@code namespaces}, solo
 * tablas de cofres, menos las que tengan algo de {@code exclude}) se les suma armamento (H10) y esmeraldas (pedido de Ale:
 * más esmeraldas al principio; también mazmorras/minas de vanilla y estructuras chicas). Las nuestras ya los traen en su tabla.
 * Se configura en {@code data/warbandcraft/loot_modifiers/dungeon_chests.json}.
 */
public class DungeonLoot extends LootModifier {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, WarbandCraft.MODID);

    public static final Supplier<Codec<DungeonLoot>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.create(i -> i.group(
            LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(m -> m.conditions),
            Codec.STRING.listOf().fieldOf("namespaces").forGetter(m -> m.namespaces),
            Codec.FLOAT.fieldOf("armament_chance").forGetter(m -> m.armamentChance),
            Codec.INT.fieldOf("armament_min").forGetter(m -> m.armamentMin),
            Codec.INT.fieldOf("armament_max").forGetter(m -> m.armamentMax),
            Codec.FLOAT.fieldOf("emerald_chance").forGetter(m -> m.emeraldChance),
            Codec.INT.fieldOf("emerald_max").forGetter(m -> m.emeraldMax),
            Codec.INT.optionalFieldOf("emerald_min", 1).forGetter(m -> m.emeraldMin),
            Codec.STRING.listOf().optionalFieldOf("exclude", List.of()).forGetter(m -> m.exclude)
    ).apply(i, DungeonLoot::new)));

    static {
        SERIALIZERS.register("dungeon_chests", CODEC);
    }

    private final List<String> namespaces, exclude;
    private final float armamentChance, emeraldChance;
    private final int armamentMin, armamentMax, emeraldMin, emeraldMax;

    public DungeonLoot(LootItemCondition[] conditions, List<String> namespaces, float armamentChance, int armamentMin, int armamentMax,
                       float emeraldChance, int emeraldMax, int emeraldMin, List<String> exclude) {
        super(conditions);
        this.namespaces = namespaces;
        this.armamentChance = armamentChance;
        this.armamentMin = armamentMin;
        this.armamentMax = armamentMax;
        this.emeraldChance = emeraldChance;
        this.emeraldMax = emeraldMax;
        this.emeraldMin = emeraldMin;
        this.exclude = exclude;
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        ResourceLocation table = context.getQueriedLootTableId();
        if (table == null || !namespaces.contains(table.getNamespace()) || !table.getPath().contains("chest")) return loot;
        for (String part : exclude) if (table.getPath().contains(part)) return loot;
        RandomSource random = context.getRandom();
        if (random.nextFloat() < armamentChance) {
            loot.add(new ItemStack(ModItems.ARMAMENT.get(), armamentMin + random.nextInt(Math.max(1, armamentMax - armamentMin + 1))));
        }
        if (random.nextFloat() < emeraldChance) loot.add(new ItemStack(Items.EMERALD,
                emeraldMin + random.nextInt(Math.max(1, emeraldMax - emeraldMin + 1))));
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
