package com.ale.warbandcraft.market;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStock;
import com.ale.warbandcraft.worker.MerchantEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El mercado de las aldeas de facción (pedido de Ale: "como las del usuario, cosas random, quizás según el bioma"). Una
 * aldea de facción con mercado tiene tantos Mercaderes como el nivel del mercado; con click derecho abren la tienda de
 * vanilla: unas cosas te las venden y otras te las compran, por esmeraldas. Lo que ofrecen sale de una lista del bioma
 * (desierto: arena, oro, terracota; taiga: abeto, bayas, pescado...) más una general (comida, hierro, armas), y cambia
 * cada día. Con una facción en guerra con vos no comercian. Las esmeraldas que les pagás van al almacén de la aldea
 * (lo que te llevás si la saqueás).
 */
public final class FactionMarket {
    /** Marca del Mercader de una aldea de facción (centro de la aldea). */
    public static final String TAG = "warbandcraft:faction_market";

    /** Ofertas por día: base + tantas por nivel del mercado. */
    public static int OFFERS_BASE = 5, OFFERS_PER_LEVEL = 3;
    /** Cuántos lotes de cada oferta por día (mínimo + al azar). */
    public static int USES_MIN = 3, USES_EXTRA = 6;
    /** Lo que te compran lo pagan a esta parte de su precio (siempre menos de lo que lo venden). */
    public static double BUY_FACTOR = 0.6;
    /** Chance de que algo del bioma sea para vender (si no, lo compran); lo general, mitad y mitad. */
    public static double LOCAL_SELL_CHANCE = 0.7;
    /** Un mercader de facción a más que esto de su puesto vuelve (aparece ahí). */
    public static int STRAY_DISTANCE = 24;

    /** Un ítem de la lista: id, lote y esmeraldas por lote. */
    private record Ware(String id, int lot, int price) {}

    private static Ware w(String id, int lot, int price) {
        return new Ware(id, lot, price);
    }

    private static final List<Ware> COMMON = List.of(
            w("minecraft:bread", 16, 3), w("minecraft:wheat", 32, 2), w("minecraft:carrot", 32, 2), w("minecraft:potato", 32, 2),
            w("minecraft:apple", 16, 2), w("minecraft:cooked_beef", 16, 5), w("minecraft:leather", 8, 2), w("minecraft:white_wool", 16, 2),
            w("minecraft:iron_ingot", 8, 5), w("minecraft:coal", 16, 2), w("minecraft:arrow", 32, 2), w("minecraft:torch", 32, 1),
            w("minecraft:cobblestone", 64, 1), w("minecraft:oak_log", 32, 2), w("minecraft:string", 16, 2), w("minecraft:feather", 16, 1),
            w("minecraft:book", 4, 3), w("minecraft:paper", 24, 2), w("minecraft:saddle", 1, 6), w("minecraft:shield", 1, 4),
            w("minecraft:iron_sword", 1, 5), w("minecraft:iron_axe", 1, 4), w("minecraft:bow", 1, 4), w("minecraft:crossbow", 1, 5),
            w("minecraft:lead", 2, 2), w("minecraft:name_tag", 1, 8), w("warbandcraft:armament", 4, 3), w("minecraft:glass", 32, 2),
            w("minecraft:bucket", 1, 3), w("minecraft:chainmail_chestplate", 1, 8), w("minecraft:iron_helmet", 1, 5),
            w("minecraft:bone", 16, 1), w("minecraft:flint", 16, 1), w("minecraft:gunpowder", 8, 3));

    private static final List<Ware> DESERT = List.of(
            w("minecraft:sand", 64, 1), w("minecraft:red_sand", 64, 1), w("minecraft:cactus", 16, 1), w("minecraft:terracotta", 32, 2),
            w("minecraft:sandstone", 64, 2), w("minecraft:gold_ingot", 4, 5), w("minecraft:rabbit_hide", 8, 2), w("minecraft:dead_bush", 16, 1),
            w("minecraft:red_dye", 16, 2), w("minecraft:orange_dye", 16, 2), w("minecraft:gold_nugget", 32, 2), w("minecraft:rabbit", 16, 2),
            w("minecraft:golden_apple", 1, 8), w("minecraft:sugar_cane", 32, 2));

    private static final List<Ware> COLD = List.of(
            w("minecraft:spruce_log", 32, 2), w("minecraft:sweet_berries", 32, 1), w("minecraft:salmon", 16, 2), w("minecraft:cod", 16, 2),
            w("minecraft:cooked_salmon", 16, 4), w("minecraft:ice", 32, 2), w("minecraft:packed_ice", 16, 3), w("minecraft:rabbit_hide", 8, 2),
            w("minecraft:lantern", 4, 3), w("minecraft:campfire", 2, 2), w("minecraft:spyglass", 1, 6), w("minecraft:white_wool", 16, 2),
            w("minecraft:iron_ingot", 8, 5), w("minecraft:powder_snow_bucket", 1, 3), w("minecraft:spruce_boat", 1, 2));

    private static final List<Ware> SAVANNA = List.of(
            w("minecraft:acacia_log", 32, 2), w("minecraft:beef", 16, 3), w("minecraft:leather", 8, 2), w("minecraft:saddle", 1, 5),
            w("minecraft:hay_block", 8, 3), w("minecraft:melon_slice", 32, 1), w("minecraft:pumpkin", 16, 2),
            w("minecraft:iron_horse_armor", 1, 8), w("minecraft:golden_horse_armor", 1, 12), w("minecraft:leather_horse_armor", 1, 4),
            w("minecraft:lead", 2, 2), w("minecraft:yellow_dye", 16, 2));

    private static final List<Ware> WETLANDS = List.of(
            w("minecraft:mangrove_log", 32, 2), w("minecraft:jungle_log", 32, 2), w("minecraft:cocoa_beans", 16, 2), w("minecraft:bamboo", 32, 1),
            w("minecraft:slime_ball", 8, 3), w("minecraft:lily_pad", 16, 1), w("minecraft:vine", 16, 1), w("minecraft:melon_slice", 32, 1),
            w("minecraft:red_mushroom", 16, 1), w("minecraft:brown_mushroom", 16, 1), w("minecraft:clay_ball", 32, 1),
            w("minecraft:green_dye", 16, 2), w("minecraft:glass_bottle", 8, 1), w("minecraft:mud", 32, 1), w("minecraft:spider_eye", 8, 2));

    private static final List<Ware> MOUNTAIN = List.of(
            w("minecraft:stone", 64, 1), w("minecraft:coal", 16, 2), w("minecraft:raw_iron", 16, 4), w("minecraft:copper_ingot", 16, 2),
            w("minecraft:lapis_lazuli", 16, 3), w("minecraft:flint", 16, 1), w("minecraft:granite", 64, 1), w("minecraft:iron_pickaxe", 1, 5),
            w("minecraft:goat_horn", 1, 6), w("minecraft:emerald_ore", 1, 3), w("minecraft:redstone", 16, 2));

    private static final List<Ware> TEMPERATE = List.of(
            w("minecraft:birch_log", 32, 2), w("minecraft:dark_oak_log", 32, 2), w("minecraft:honeycomb", 8, 3), w("minecraft:honey_bottle", 4, 2),
            w("minecraft:cooked_porkchop", 16, 5), w("minecraft:egg", 16, 1), w("minecraft:beetroot", 32, 2), w("minecraft:pumpkin_pie", 8, 3),
            w("minecraft:cooked_chicken", 16, 4), w("minecraft:cake", 1, 3), w("minecraft:blue_dye", 16, 2), w("minecraft:sugar", 32, 2));

    /** Las ofertas del día de cada aldea (se gastan; mañana son otras). */
    private record Daily(long day, MerchantOffers offers) {}

    private static final Map<Long, Daily> OFFERS = new HashMap<>();

    private FactionMarket() {}

    public static void clear() {
        OFFERS.clear();
    }

    public static boolean is(MerchantEntity m) {
        return m.getPersistentData().contains(TAG);
    }

    // ------------------------------------------------------------------ los mercaderes

    /** Cada 5 s: las aldeas de facción cargadas y en paz con mercado tienen sus mercaderes; los que sobran se van. */
    public static void tick(ServerLevel level) {
        VillageData data = VillageData.get(level);
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            BlockPos c = v.center();
            if (data.isTest(c) || !level.isPositionEntityTicking(c)) continue;
            List<MerchantEntity> mine = merchants(level, c);
            TownData.Town t = towns.at(c);
            int want = v.isConquered() || t == null ? 0 : t.marketLevel();
            BlockPos place = want > 0 ? Market.workplace(level, v) : null;
            if (place == null) want = 0;
            for (int i = want; i < mine.size(); i++) mine.get(i).discard();
            if (want == 0) continue;
            for (MerchantEntity m : mine) {
                m.setWorkplace(place);
                if (m.position().distanceToSqr(Vec3.atBottomCenterOf(place)) > (double) STRAY_DISTANCE * STRAY_DISTANCE) {
                    m.teleportTo(place.getX() + 0.5, place.getY(), place.getZ() + 0.5);
                }
            }
            boolean peace = RaidManager.get(level, c) == null && InvasionData.get(level).battleAt(c) == null;
            if (!peace) continue;
            for (int i = mine.size(); i < want; i++) spawn(level, v, place);
        }
    }

    private static List<MerchantEntity> merchants(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        return level.getEntitiesOfClass(MerchantEntity.class, new AABB(center).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS),
                m -> m.isAlive() && m.getPersistentData().contains(TAG) && m.getPersistentData().getLong(TAG) == key);
    }

    @Nullable
    private static MerchantEntity spawn(ServerLevel level, VillageData.Village v, BlockPos place) {
        MerchantEntity m = ModEntities.MERCHANT.get().create(level);
        if (m == null) return null;
        double x = place.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 2, z = place.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 2;
        m.moveTo(x, place.getY(), z, level.random.nextFloat() * 360F, 0F);
        try {
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(place), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException e) {
            WarbandCraft.LOGGER.debug("finalizeSpawn del mercader de facción falló, se sigue igual", e);
        }
        m.initSpawn();
        Faction f = FactionManager.INSTANCE.get(v.faction());
        m.setCustomName(f == null ? Component.translatable("entity.warbandcraft.merchant")
                : Component.translatable("entity.warbandcraft.faction_merchant", f.soldierSuffix()).withStyle(f.color()));
        m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
        m.setWorkplace(place);
        m.setPersistenceRequired();
        m.despawnTimer = -1;
        m.restrictTo(place, 6);
        m.getPersistentData().putLong(TAG, v.center().asLong());
        if (!level.addFreshEntity(m)) {
            WarbandCraft.LOGGER.warn("No apareció el mercader de la aldea de facción en {}", v.center().toShortString());
            return null;
        }
        return m;
    }

    // ------------------------------------------------------------------ comerciar

    /** Click derecho a un mercader de facción: la tienda (si la facción no está en guerra con vos). */
    public static void interact(ServerPlayer player, MerchantEntity merchant) {
        ServerLevel level = player.serverLevel();
        BlockPos center = BlockPos.of(merchant.getPersistentData().getLong(TAG));
        VillageData.Village v = VillageData.get(level).at(center);
        if (v == null || v.isConquered()) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.market.closed").withStyle(ChatFormatting.RED));
            return;
        }
        if (Relations.get(level.getServer()).atWar(player.getUUID(), v.faction())) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.faction_market.enemy").withStyle(ChatFormatting.RED));
            return;
        }
        if (RaidManager.get(level, center) != null || InvasionData.get(level).battleAt(center) != null) {
            player.sendSystemMessage(Component.translatable("msg.warbandcraft.faction_market.fighting").withStyle(ChatFormatting.RED));
            return;
        }
        MerchantOffers offers = offers(level, v);
        Trader trader = new Trader(level, center, merchant, offers);
        trader.setTradingPlayer(player);
        Faction f = FactionManager.INSTANCE.get(v.faction());
        Component title = Component.translatable("gui.warbandcraft.faction_market.title",
                f == null ? Component.literal("?") : Component.literal(f.name()).withStyle(f.color()));
        trader.openTradingScreen(player, title, 0);
    }

    /** Las ofertas de hoy (las mismas para todos hasta mañana; lo que se compró, se gastó). */
    public static MerchantOffers offers(ServerLevel level, VillageData.Village v) {
        long day = level.getDayTime() / 24000L;
        Daily d = OFFERS.get(v.center().asLong());
        if (d != null && d.day() == day) return d.offers();
        TownData.Town t = TownData.get(level).at(v.center());
        MerchantOffers offers = roll(level.getBiome(v.center()), t == null ? 1 : Math.max(1, t.marketLevel()),
                RandomSource.create(v.center().asLong() * 31 + day * 7919));
        OFFERS.put(v.center().asLong(), new Daily(day, offers));
        return offers;
    }

    /** Arma las ofertas: algunas del bioma, el resto generales; cada ítem va en un solo sentido. */
    public static MerchantOffers roll(Holder<Biome> biome, int marketLevel, RandomSource r) {
        List<Ware> local = new ArrayList<>(localWares(biome));
        List<Ware> common = new ArrayList<>(COMMON);
        int n = OFFERS_BASE + OFFERS_PER_LEVEL * marketLevel;
        MerchantOffers out = new MerchantOffers();
        Set<Item> used = new HashSet<>();
        int tries = 0;
        while (out.size() < n && tries++ < n * 6 && (!local.isEmpty() || !common.isEmpty())) {
            boolean fromLocal = !local.isEmpty() && (common.isEmpty() || r.nextDouble() < 0.6);
            List<Ware> pool = fromLocal ? local : common;
            Ware ware = pool.remove(r.nextInt(pool.size()));
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(ware.id()));
            if (item == null || item == Items.AIR || !used.add(item)) continue;
            boolean theySell = r.nextDouble() < (fromLocal ? LOCAL_SELL_CHANCE : 0.5);
            int uses = USES_MIN + r.nextInt(USES_EXTRA + 1);
            int lot = Math.max(1, Math.min(ware.lot(), new ItemStack(item).getMaxStackSize()));
            if (theySell) {
                out.add(new MerchantOffer(new ItemStack(Items.EMERALD, Math.min(64, ware.price())), ItemStack.EMPTY,
                        new ItemStack(item, lot), 0, uses, 0, 0F));
            } else {
                int pay = Math.max(1, (int) Math.floor(ware.price() * BUY_FACTOR));
                out.add(new MerchantOffer(new ItemStack(item, lot), ItemStack.EMPTY, new ItemStack(Items.EMERALD, pay), 0, uses, 0, 0F));
            }
        }
        return out;
    }

    private static List<Ware> localWares(Holder<Biome> b) {
        if (b.is(Tags.Biomes.IS_DESERT) || b.is(BiomeTags.IS_BADLANDS)) return DESERT;
        if (b.is(Tags.Biomes.IS_SNOWY) || b.is(BiomeTags.IS_TAIGA)) return COLD;
        if (b.is(BiomeTags.IS_SAVANNA)) return SAVANNA;
        if (b.is(Tags.Biomes.IS_SWAMP) || b.is(BiomeTags.IS_JUNGLE)) return WETLANDS;
        if (b.is(BiomeTags.IS_MOUNTAIN) || b.is(Tags.Biomes.IS_MOUNTAIN)) return MOUNTAIN;
        return TEMPERATE;
    }

    /** Lo que pagó el jugador en esmeraldas va al almacén de la aldea. */
    private static void paid(ServerLevel level, BlockPos center, int emeralds) {
        if (emeralds <= 0) return;
        VillageData data = VillageData.get(level);
        VillageStock.Stock s = data.stock(center);
        data.setStock(center, new VillageStock.Stock(s.emeralds() + emeralds, s.food(), s.armament()));
    }

    /** La tienda de vanilla de un mercader de facción. */
    private static final class Trader implements Merchant {
        private final ServerLevel level;
        private final BlockPos center;
        private final MerchantEntity merchant;
        private final MerchantOffers offers;
        @Nullable
        private Player buyer;

        Trader(ServerLevel level, BlockPos center, MerchantEntity merchant, MerchantOffers offers) {
            this.level = level;
            this.center = center;
            this.merchant = merchant;
            this.offers = offers;
        }

        @Override
        public void setTradingPlayer(@Nullable Player player) {
            this.buyer = player;
        }

        @Nullable
        @Override
        public Player getTradingPlayer() {
            if (buyer != null && (!merchant.isAlive() || buyer.distanceToSqr(merchant) > Market.TRADE_RANGE * Market.TRADE_RANGE)) return null;
            return buyer;
        }

        @Override
        public MerchantOffers getOffers() {
            return offers;
        }

        @Override
        public void overrideOffers(MerchantOffers offers) {
        }

        @Override
        public void notifyTrade(MerchantOffer offer) {
            offer.increaseUses();
            if (offer.getCostA().is(Items.EMERALD)) paid(level, center, offer.getCostA().getCount());
        }

        @Override
        public void notifyTradeUpdated(ItemStack stack) {
        }

        @Override
        public int getVillagerXp() {
            return 0;
        }

        @Override
        public void overrideXp(int xp) {
        }

        @Override
        public boolean showProgressBar() {
            return false;
        }

        @Override
        public SoundEvent getNotifyTradeSound() {
            return SoundEvents.VILLAGER_YES;
        }

        @Override
        public boolean isClientSide() {
            return false;
        }
    }
}
