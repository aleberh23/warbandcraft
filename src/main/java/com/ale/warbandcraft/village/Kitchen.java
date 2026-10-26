package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pedido de Ale: la Cocina. Con la Cocina hecha y un cocinero de Villager Workers en la aldea, cada {@link #BATCH_TICKS}
 * se cocina una tanda de {@link #PER_BATCH} cosas con lo del almacén: tortas de calabaza y galletas si hay con qué, el
 * trigo a pan, y todo lo crudo que va al horno (carne, pescado, papas, algas) a cocido. Deja {@link #KEEP_POTATOES} papas
 * y {@link #KEEP_WHEAT} de trigo por si acaso. El horno gasta poca leña (rinde mucho más que a mano). Al volver a la aldea
 * se pone al día con el tiempo que pasó (hasta {@link #MAX_DAYS} días). No hace nada que no salga del almacén.
 */
public final class Kitchen {
    /** Cada cuánto cocina una tanda y cuántas cosas (un pan, una cocción, una torta...) por tanda. */
    public static int BATCH_TICKS = 20 * 30, PER_BATCH = 4;
    /** Lo que deja sin cocinar por si acaso (las papas son semilla; el trigo, comida de los animales del corral). */
    public static int KEEP_POTATOES = 16, KEEP_WHEAT = 8;
    /** Cocciones por combustible (en un horno a mano: tronco o tablón 1,5, carbón 8). */
    public static int PLANK_COOKS = 3, LOG_COOKS = 12, COAL_COOKS = 24;
    /** Hasta cuántos días atrás se pone al día. */
    public static int MAX_DAYS = 7;

    private static final Map<Item, ItemStack> SMELTED = new HashMap<>();

    private Kitchen() {}

    /** Lo que hizo en una vuelta: lo cocinado y si quedó algo sin cocinar por falta de leña. */
    public record Result(List<ItemStack> made, boolean noFuel) {
        static final Result NOTHING = new Result(List.of(), false);
    }

    /** ¿La aldea tiene la Cocina? */
    public static boolean has(TownData.Town t) {
        return t != null && t.has(Upgrade.KITCHEN);
    }

    /** ¿Cocinó en el último día? (para la popularidad). */
    public static boolean active(ServerLevel level, TownData.Town t) {
        return has(t) && t.kitchenTick != 0 && level.getGameTime() - t.kitchenTick < 24000L;
    }

    /** Atiende la cocina de la aldea (la llame el cocinero que la llame, una vez por tanda). */
    public static Result cook(ServerLevel level, VillageData.Village v, CampBlockEntity camp) {
        TownData data = TownData.get(level);
        TownData.Town t = data.at(v.center());
        if (!has(t)) return Result.NOTHING;
        long now = level.getGameTime();
        if (t.kitchenTick == 0 || now < t.kitchenTick) {
            t.kitchenTick = now;
            data.changed();
            return Result.NOTHING;
        }
        long max = MAX_DAYS * 24000L;
        if (now - t.kitchenTick > max) t.kitchenTick = now - max;
        long batches = (now - t.kitchenTick) / Math.max(1, BATCH_TICKS);
        if (batches <= 0) return Result.NOTHING;
        t.kitchenTick += batches * Math.max(1, BATCH_TICKS);
        int[] ops = {(int) Math.min(1_000_000, batches * PER_BATCH)};
        List<ItemStack> made = new ArrayList<>();

        // tortas de calabaza (calabaza + huevo + azúcar, o caña de azúcar que se hace azúcar)
        int sugar = camp.count(Items.SUGAR), cane = camp.count(Items.SUGAR_CANE);
        int pies = min(ops[0], camp.count(Items.PUMPKIN), camp.count(Items.EGG), sugar + cane);
        if (pies > 0) {
            camp.take(Items.PUMPKIN, pies);
            camp.take(Items.EGG, pies);
            int s = camp.take(Items.SUGAR, pies);
            camp.take(Items.SUGAR_CANE, pies - s);
            made.add(new ItemStack(Items.PUMPKIN_PIE, pies));
            ops[0] -= pies;
        }
        // galletas (2 trigo + cacao → 8) y pan (3 trigo → 1)
        int cookies = min(ops[0], Math.max(0, camp.count(Items.WHEAT) - KEEP_WHEAT) / 2, camp.count(Items.COCOA_BEANS));
        if (cookies > 0) {
            camp.take(Items.WHEAT, cookies * 2);
            camp.take(Items.COCOA_BEANS, cookies);
            made.add(new ItemStack(Items.COOKIE, cookies * 8));
            ops[0] -= cookies;
        }
        int bread = Math.min(ops[0], Math.max(0, camp.count(Items.WHEAT) - KEEP_WHEAT) / 3);
        if (bread > 0) {
            camp.take(Items.WHEAT, bread * 3);
            made.add(new ItemStack(Items.BREAD, bread));
            ops[0] -= bread;
        }
        // lo crudo al horno
        Set<Item> raw = new LinkedHashSet<>();
        camp.forEachStack(s -> {
            if (!s.hasTag() && !smelted(level, s.getItem()).isEmpty()) raw.add(s.getItem());
        });
        boolean noFuel = false;
        for (Item item : raw) {
            if (ops[0] <= 0) break;
            int want = Math.min(ops[0], camp.count(item) - (item == Items.POTATO ? KEEP_POTATOES : 0));
            int done = 0;
            while (done < want) {
                if (t.kitchenFuel <= 0 && !refuel(camp, t)) {
                    noFuel = true;
                    break;
                }
                int k = Math.min(want - done, t.kitchenFuel);
                done += k;
                t.kitchenFuel -= k;
            }
            if (done <= 0) {
                if (noFuel) break;
                continue;
            }
            done = camp.take(item, done);
            ItemStack out = smelted(level, item).copy();
            out.setCount(out.getCount() * done);
            made.add(out);
            ops[0] -= done;
            if (noFuel) break;
        }
        // al almacén (lo que no entra, a otro Campamento o pendiente)
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack s : made) {
            for (int left = s.getCount(); left > 0; ) {
                int n = Math.min(left, s.getMaxStackSize());
                ItemStack back = camp.insert(new ItemStack(s.getItem(), n));
                if (!back.isEmpty()) rest.add(back);
                left -= n;
            }
        }
        if (!rest.isEmpty() && v.owner() != null) CampRegistry.get(level).deliver(level, v.owner(), rest, camp.getBlockPos());
        data.changed();
        return new Result(made, noFuel);
    }

    /** Un combustible del almacén: primero tablones, después troncos, después carbón. False = no hay. */
    private static boolean refuel(CampBlockEntity camp, TownData.Town t) {
        Object[][] fuels = {{ItemTags.PLANKS, PLANK_COOKS}, {ItemTags.LOGS_THAT_BURN, LOG_COOKS}};
        for (Object[] f : fuels) {
            @SuppressWarnings("unchecked") TagKey<Item> tag = (TagKey<Item>) f[0];
            Item[] found = {null};
            camp.forEachStack(s -> {
                if (found[0] == null && !s.hasTag() && s.is(tag)) found[0] = s.getItem();
            });
            if (found[0] != null && camp.take(found[0], 1) == 1) {
                t.kitchenFuel += (int) f[1];
                return true;
            }
        }
        for (Item coal : new Item[]{Items.CHARCOAL, Items.COAL}) {
            if (camp.take(coal, 1) == 1) {
                t.kitchenFuel += COAL_COOKS;
                return true;
            }
        }
        return false;
    }

    /** Lo que sale del horno si es comida (vacío si no va al horno o no sale comida). */
    private static ItemStack smelted(ServerLevel level, Item item) {
        return SMELTED.computeIfAbsent(item, i -> {
            if (i == Items.WHEAT) return ItemStack.EMPTY;
            ItemStack out = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(new ItemStack(i)), level)
                    .map(r -> r.getResultItem(level.registryAccess()).copy()).orElse(ItemStack.EMPTY);
            return !out.isEmpty() && out.isEdible() ? out : ItemStack.EMPTY;
        });
    }

    private static int min(int a, int b, int c) {
        return Math.min(a, Math.min(b, c));
    }

    private static int min(int a, int b, int c, int d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }
}
