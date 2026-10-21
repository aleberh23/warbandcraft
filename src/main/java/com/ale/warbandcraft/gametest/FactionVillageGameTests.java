package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.market.FactionMarket;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.GarrisonReserve;
import com.ale.warbandcraft.village.Tavern;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.worker.MerchantEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pedidos de Ale sobre las aldeas de facción: la reserva de los pueblos grandes, el mercado que compra y vende según el
 * bioma, y los reclutas para contratar que esperan adentro de la taberna.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class FactionVillageGameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(13, 1, 13);

    /** Pueblo con empalizada: reserva = pueblo + empalizada; ciudad con muralla, más; aldea tuya o castillo, nada. */
    @GameTest(template = TEMPLATE)
    public static void reserveBySize(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        try {
            TownData.Town t = TownData.get(level).getOrCreate(v.center());
            check(GarrisonReserve.remaining(level, v) == 0, "sin tamaño ya tiene reserva");
            t.npcTier = 2;
            t.built.add(Upgrade.PALISADE);
            int town = GarrisonReserve.remaining(level, v);
            check(town == GarrisonReserve.RESERVE_TOWN + GarrisonReserve.PALISADE_BONUS, "reserva del pueblo: " + town);
            t.npcTier = 3;
            t.built.add(Upgrade.WALL);
            check(GarrisonReserve.remaining(level, v) == GarrisonReserve.RESERVE_CITY + GarrisonReserve.WALL_BONUS, "reserva de la ciudad");
            t.npcReserveUsed = 5;
            check(GarrisonReserve.remaining(level, v) == GarrisonReserve.RESERVE_CITY + GarrisonReserve.WALL_BONUS - 5, "no descuenta los caídos");
            check(com.ale.warbandcraft.invasion.AiWars.defense(level, v, 0) > 0, "la autoresolución no cuenta la reserva");
            VillageData.Village mine = v.withOwner(java.util.UUID.randomUUID());
            check(GarrisonReserve.remaining(level, mine) == 0, "una aldea tuya tiene reserva");
        } finally {
            cleanup(level, v);
        }
        h.succeed();
    }

    /** Ofertas según el bioma: cada ítem en un solo sentido y lo que compran, más barato que lo que venden. */
    @GameTest(template = TEMPLATE)
    public static void factionMarketRolls(GameTestHelper h) {
        var biomes = h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        MerchantOffers desert = FactionMarket.roll(biomes.getHolderOrThrow(Biomes.DESERT), 3, RandomSource.create(42));
        check(desert.size() == FactionMarket.OFFERS_BASE + FactionMarket.OFFERS_PER_LEVEL * 3, "ofertas: " + desert.size());
        Set<Item> seen = new HashSet<>();
        boolean sells = false, buys = false;
        for (MerchantOffer o : desert) {
            Item item = o.getResult().is(Items.EMERALD) ? o.getCostA().getItem() : o.getResult().getItem();
            check(seen.add(item), "el mismo ítem dos veces: " + item);
            if (o.getResult().is(Items.EMERALD)) buys = true;
            else sells = true;
        }
        check(sells && buys, "no vende y compra a la vez");
        // varias tiradas: en el desierto aparece algo del desierto
        boolean local = false;
        for (int i = 0; i < 10 && !local; i++) {
            for (MerchantOffer o : FactionMarket.roll(biomes.getHolderOrThrow(Biomes.DESERT), 1, RandomSource.create(i))) {
                Item it = o.getResult().is(Items.EMERALD) ? o.getCostA().getItem() : o.getResult().getItem();
                if (it == Items.SAND || it == Items.CACTUS || it == Items.SANDSTONE || it == Items.TERRACOTTA || it == Items.GOLD_INGOT) local = true;
            }
        }
        check(local, "el desierto no ofrece nada del desierto");
        h.succeed();
    }

    /** Aldea de facción con mercado: aparece su mercader en el mercado y tiene ofertas. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void factionMarketMerchant(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        VillageData.Village v = village(h);
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        t.built.add(Upgrade.MARKET);
        t.sites.put(Upgrade.MARKET.siteKey(), h.absolutePos(new BlockPos(2, 1, 2)));   // piso de piedra en y=1
        FactionMarket.tick(level);
        List<MerchantEntity> first = level.getEntitiesOfClass(MerchantEntity.class, new AABB(v.center()).inflate(40), e -> e.isAlive() && FactionMarket.is(e));
        check(first.size() == 1, "no apareció el mercader (" + first.size() + ", lugar " + com.ale.warbandcraft.market.Market.workplace(level, v) + ")");
        MerchantEntity spawned = first.get(0);
        h.runAfterDelay(60, () -> {
            try {
                FactionMarket.tick(level);   // si una prueba vecina lo limpió, vuelve a aparecer (y no aparece uno de más)
                List<MerchantEntity> m = level.getEntitiesOfClass(MerchantEntity.class, new AABB(v.center()).inflate(40),
                        e -> e.isAlive() && FactionMarket.is(e));
                check(m.size() == 1, "mercaderes: " + m.size() + " (el que apareció: vivo " + spawned.isAlive() + ", vida " + spawned.getHealth()
                        + ", sacado " + spawned.getRemovalReason() + ", en " + spawned.blockPosition().toShortString() + ")");
                check(!FactionMarket.offers(level, v).isEmpty(), "sin ofertas");
                VillageData.get(level).add(v.withOwner(java.util.UUID.randomUUID()));
                FactionMarket.tick(level);
                check(!m.get(0).isAlive(), "conquistada y el mercader de la facción sigue");
            } finally {
                cleanup(level, v);
                for (MerchantEntity e : level.getEntitiesOfClass(MerchantEntity.class, new AABB(v.center()).inflate(40))) e.discard();
            }
            h.succeed();
        });
    }

    /** Los reclutas sueltos para contratar son "contratables"; los de guarnición, los tuyos y los trabajadores, no. */
    @GameTest(template = TEMPLATE)
    public static void hireablesGoToTavern(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        check(type != null, "no hay recluta de Recruits");
        AbstractRecruitEntity free = (AbstractRecruitEntity) type.create(level);
        AbstractRecruitEntity garrison = (AbstractRecruitEntity) type.create(level);
        try {
            check(free != null && garrison != null, "no se crearon");
            free.setPos(h.absolutePos(CENTER).getCenter());
            check(Tavern.isHireable(free), "un recluta suelto no es contratable");
            garrison.getPersistentData().putString(com.ale.warbandcraft.village.VillageManager.TAG_FACTION, "warbandcraft:x");
            check(!Tavern.isHireable(garrison), "uno de guarnición es contratable");
            free.setIsOwned(true);
            check(!Tavern.isHireable(free), "uno con dueño es contratable");
        } finally {
            if (free != null) free.discard();
            if (garrison != null) garrison.discard();
        }
        h.succeed();
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), new ResourceLocation(WarbandCraft.MODID, "prueba_fv"), 0);
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static void cleanup(ServerLevel level, VillageData.Village v) {
        TownData.get(level).remove(v.center());
        VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
