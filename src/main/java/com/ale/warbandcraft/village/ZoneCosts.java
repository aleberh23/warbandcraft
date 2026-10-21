package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.CampBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * H16: lo que sale del almacén de la aldea para armar una zona de trabajo: la herramienta del trabajador, lo que
 * necesita para arrancar (semillas, retoños, trigo) y el cofre de la zona. Sin eso, la zona no se crea.
 */
public final class ZoneCosts {
    public static int SEEDS = 8, SAPLINGS = 4, WHEAT = 8;

    private ZoneCosts() {}

    /**
     * Una cosa que hace falta. {@code key} = el texto ({@code msg.warbandcraft.need.<key>}); {@code toWorker} = se le da
     * al trabajador (herramienta); si no, va al cofre de la zona (salvo el cofre mismo).
     */
    public record Need(String key, Predicate<ItemStack> match, int count, boolean toWorker) {}

    private static final Need CHEST = new Need("chest", s -> s.is(Items.CHEST), 1, false);
    public static final Need ANVIL = new Need("anvil", s -> s.is(ItemTags.ANVIL), 1, false);

    public static List<Need> needs(VillageManagement.Job job) {
        return switch (job) {
            case FARMER -> List.of(new Need("hoe", s -> s.is(ItemTags.HOES), 1, true),
                    new Need("seeds", s -> s.is(ItemTags.VILLAGER_PLANTABLE_SEEDS), SEEDS, false), CHEST);
            case LUMBERJACK -> List.of(new Need("axe", s -> s.is(ItemTags.AXES), 1, true),
                    new Need("saplings", s -> s.is(ItemTags.SAPLINGS), SAPLINGS, false), CHEST);
            case MINER -> List.of(new Need("pickaxe", s -> s.is(ItemTags.PICKAXES), 1, true), CHEST);
            case FISHERMAN -> List.of(new Need("rod", s -> s.is(Items.FISHING_ROD), 1, true), CHEST);
            case ANIMAL_FARMER -> List.of(new Need("wheat", s -> s.is(Items.WHEAT), WHEAT, false), CHEST);
            default -> List.of();
        };
    }

    public static boolean isChest(Need n) {
        return n == CHEST;
    }

    public static int count(CampBlockEntity camp, Predicate<ItemStack> match) {
        int[] n = {0};
        camp.forEachStack(s -> {
            if (match.test(s)) n[0] += s.getCount();
        });
        return n[0];
    }

    /** "Falta en el almacén: 1 pico, 1 cofre." o null si está todo. */
    @Nullable
    public static Component missing(CampBlockEntity camp, List<Need> needs) {
        MutableComponent list = null;
        for (Need n : needs) {
            int lack = n.count() - count(camp, n.match());
            if (lack <= 0) continue;
            Component part = Component.translatable("msg.warbandcraft.need." + n.key(), lack);
            list = list == null ? Component.empty().append(part) : list.append(", ").append(part);
        }
        return list == null ? null : Component.translatable("msg.warbandcraft.zone.missing", list).withStyle(ChatFormatting.RED);
    }

    /** Saca {@code n.count()} de lo que pide {@code n} del almacén (cofre y vinculados). Devuelve lo sacado. */
    public static List<ItemStack> take(CampBlockEntity camp, Need n) {
        List<ItemStack> out = new ArrayList<>();
        int left = n.count();
        for (Container c : camp.storages()) {
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !n.match().test(s)) continue;
                int k = Math.min(left, s.getCount());
                out.add(s.split(k));
                left -= k;
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
            }
            if (left <= 0) break;
        }
        return out;
    }
}
