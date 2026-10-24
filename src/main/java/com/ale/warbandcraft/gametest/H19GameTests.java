package com.ale.warbandcraft.gametest;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.Training;
import com.ale.warbandcraft.init.ModEntities;
import com.ale.warbandcraft.kit.Armament;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.village.Quarters;
import com.ale.warbandcraft.village.Tavern;
import com.ale.warbandcraft.village.VillageData;
import com.ale.warbandcraft.village.VillageStorehouse;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pruebas de H19: llegan reclutas a la taberna (con tabernero y comida; sin comida, ninguno), Auto-reclutar los pasa al
 * cuartel, el campo de entrenamiento sube el nivel de las fichas hasta su tope, y el nivel básico se paga solo con armamento.
 */
@GameTestHolder(WarbandCraft.MODID)
@PrefixGameTestTemplate(false)
public class H19GameTests {
    private static final String TEMPLATE = "empty";
    private static final BlockPos CENTER = new BlockPos(4, 2, 4);
    private static final ResourceLocation FACTION = new ResourceLocation(WarbandCraft.MODID, "norte");

    @GameTest(template = TEMPLATE)
    public static void tavernRecruitsArriveWithFood(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        VillageData.Village v = village(h);
        try {
            CampBlockEntity camp = VillageStorehouse.ensure(level, v);
            check(camp != null, "no se puso el Campamento de la aldea");
            TownData.Town t = TownData.get(level).getOrCreate(v.center());
            t.built.add(Upgrade.TAVERN_1);
            t.built.add(Upgrade.TAVERN_2);
            t.sites.put(Upgrade.TAVERN_1.siteKey(), h.absolutePos(new BlockPos(1, 1, 1)));

            // Sin tabernero: nadie
            check(Tavern.arrive(level, v, t, 10) == 0, "llegaron reclutas sin tabernero");
            InnkeeperEntity keeper = ModEntities.INNKEEPER.get().create(level);
            check(keeper != null, "no se pudo crear el tabernero");
            keeper.moveTo(h.absolutePos(new BlockPos(2, 2, 2)).getCenter());
            keeper.setOwnerUUID(Optional.of(owner));
            keeper.setIsOwned(true);
            keeper.setCamp(camp.getBlockPos());
            level.addFreshEntity(keeper);

            // Sin comida: nadie
            check(Tavern.arrive(level, v, t, 10) == 0, "llegaron reclutas sin comida");
            // Con comida: los 4 del nivel 2, y se comieron 4 × 10 puntos
            camp.insert(new ItemStack(Items.BREAD, 10));
            int arrived = Tavern.arrive(level, v, t, 11);
            check(arrived == Tavern.ARRIVALS_2, "llegaron " + arrived + " reclutas");
            check(t.tavernWaiting.size() == Tavern.ARRIVALS_2, "esperan " + t.tavernWaiting.size());
            check(camp.count(Items.BREAD) == 2, "no se comió la comida: quedan " + camp.count(Items.BREAD) + " panes");
            // Al tope de espera no llega nadie más; a los 3 días se van
            camp.insert(new ItemStack(Items.BREAD, 30));
            Tavern.arrive(level, v, t, 12);
            check(t.tavernWaiting.size() == Tavern.MAX_WAITING_2, "esperan más que el tope: " + t.tavernWaiting.size());
            Tavern.arrive(level, v, t, 11 + Tavern.LEAVE_DAYS);
            check(t.tavernWaiting.stream().noneMatch(w -> w[1] == 11), "los viejos no se fueron");

            // Auto-reclutar: al cuartel primero
            int waiting = t.tavernWaiting.size();
            int moved = Tavern.autoRecruit(level, v, t);
            BarracksData.Barracks b = BarracksData.get(level).at(camp.getBlockPos());
            check(moved == waiting && t.tavernWaiting.isEmpty(), "Auto-reclutar pasó " + moved + " de " + waiting);
            check(b != null && b.quarters.size() == Math.min(waiting, Quarters.capacity(level, v)), "no fueron al cuartel");
            keeper.discard();
        } finally {
            TownData.get(level).remove(v.center());
            VillageStorehouse.forget(level, v);
            VillageData.get(level).add(v.withOwner(null).withLootedUntil(Long.MAX_VALUE));
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void trainingLevelsUpUntilCap(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = owner(h);
        BlockPos camp = h.absolutePos(new BlockPos(4, 2, 4));
        BarracksData.Barracks b = BarracksData.get(level).getOrCreate(camp, owner);
        try {
            BarracksData.Soldier s = Tavern.sheet(level, owner, camp, 0, 1);
            check(s != null, "no se armó la ficha");
            int start = s.xpLevel;
            b.soldiers.add(s);
            check(Training.anyToTrain(b, 1), "nadie para entrenar");
            int days = 0;
            while (Training.anyToTrain(b, 1) && days < 100) {
                Training.train(level, b, 1);
                days++;
            }
            int lvl = b.soldiers.get(0).xpLevel;
            check(lvl == Training.CAP_1, "llegó a nivel " + lvl + " (tope " + Training.CAP_1 + ")");
            check(days <= (Training.CAP_1 - start) * (100 / Training.XP_PERCENT_PER_DAY) + 1, "tardó " + days + " días");
            Training.train(level, b, 1);
            check(b.soldiers.get(0).xpLevel == Training.CAP_1, "pasó el tope");
        } finally {
            BarracksData.get(level).remove(camp);
        }
        h.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void basicTierWithOnlyArmament(GameTestHelper h) {
        Map<Item, Integer> cost = Map.of(Items.BOW, 1, Items.LEATHER_CHESTPLATE, 1, Items.ARROW, 32);
        Map<Item, Integer> pool = Map.of(Armament.item(), 30);
        Armament.Plan basic = Armament.INSTANCE.plan(pool, cost, true);
        check(basic.ok(), "el básico no se pudo pagar con armamento");
        check(basic.armament() == 6 + 6 + 4, "costó " + basic.armament() + " de armamento");
        check(!Armament.INSTANCE.plan(pool, cost, false).ok(), "un nivel alto se pagó con armamento sin metal");
        check(!Armament.INSTANCE.plan(Map.of(Armament.item(), 10), cost, true).ok(), "alcanzó con menos armamento");
        h.succeed();
    }

    private static VillageData.Village village(GameTestHelper h) {
        VillageData.Village v = new VillageData.Village(h.absolutePos(CENTER), FACTION, 0).withOwner(owner(h));
        VillageData.get(h.getLevel()).add(v);
        return v;
    }

    private static UUID owner(GameTestHelper h) {
        return UUID.nameUUIDFromBytes(("warbandcraft-h19@" + h.absolutePos(BlockPos.ZERO).toShortString()).getBytes());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new GameTestAssertException(message);
    }
}
