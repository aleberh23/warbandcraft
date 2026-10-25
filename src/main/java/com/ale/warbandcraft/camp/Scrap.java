package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.Armament;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Desguace (pedido de Ale): el dueño marca en la pestaña Cofre qué piezas no quiere guardar (armaduras oxidadas de los
 * bandidos, cotas de malla...). Los herreros del Campamento (aldeanos de H10 y el Herrero de H16), además del hierro,
 * agarran esas piezas del almacén y las convierten en armamento. La lista va en el Campamento ({@link CampBlockEntity#scrapList}).
 */
public final class Scrap {
    /** Parte del valor de armamento de una pieza que sale al desguazarla (entera y sin gastar). */
    public static double YIELD = 0.5;
    /** Piezas que desguaza cada herrero aldeano (H10) por día. */
    public static int PIECES_PER_SMITH = 16;
    /** Piezas que desguaza el Herrero (H16) en cada vuelta de trabajo (en vez de un lingote). */
    public static int PIECES_PER_JOB = 4;

    private Scrap() {}

    /** ¿Se puede marcar para desguazar? Armas y armaduras (se gastan) con valor de armamento. */
    public static boolean scrappable(Item item) {
        return item.canBeDepleted() && Armament.INSTANCE.coverable(item, true);
    }

    /** Armamento que da una pieza: valor × {@link #YIELD} × lo que le queda de vida; mínimo 1. */
    public static int valueOf(ItemStack stack) {
        Item item = stack.getItem();
        int base = Armament.INSTANCE.value(item);
        if (base <= 0) base = Armament.INSTANCE.cost(item, 1, true);
        if (base <= 0) return 0;
        double life = stack.isDamageableItem() && stack.getMaxDamage() > 0
                ? 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage() : 1.0;
        return Math.max(1, (int) Math.floor(base * YIELD * life));
    }

    /** Lo que se desguazaría de esta pila (marcada, sin nombre puesto a mano). */
    private static boolean wanted(CampBlockEntity camp, ItemStack stack) {
        return !stack.isEmpty() && !stack.hasCustomHoverName() && camp.isScrap(stack.getItem()) && scrappable(stack.getItem());
    }

    /** ¿Hay algo marcado para desguazar en el almacén (Campamento o vinculados)? */
    public static boolean any(CampBlockEntity camp) {
        if (!camp.hasScrap()) return false;
        for (Container c : camp.storages()) {
            for (int i = 0; i < c.getContainerSize(); i++) if (wanted(camp, c.getItem(i))) return true;
        }
        return false;
    }

    /** Desguaza hasta {@code maxPieces} piezas marcadas del almacén. Devuelve el armamento que salió (ya guardado). */
    public static int work(CampBlockEntity camp, int maxPieces) {
        if (maxPieces <= 0 || !camp.hasScrap()) return 0;
        int made = 0, pieces = 0;
        for (Container c : camp.storages()) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && pieces < maxPieces; i++) {
                ItemStack stack = c.getItem(i);
                if (!wanted(camp, stack)) continue;
                int n = Math.min(stack.getCount(), maxPieces - pieces);
                made += valueOf(stack) * n;
                pieces += n;
                stack.shrink(n);
                if (stack.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) c.setChanged();
            if (pieces >= maxPieces) break;
        }
        if (pieces > 0) {
            // Pedido de Ale ("no lo deja en ningún lado"): queda en el log cuánto salió y cuánto armamento hay después
            com.ale.warbandcraft.WarbandCraft.LOGGER.info("Desguace en el Campamento {}: {} piezas -> {} de armamento",
                    camp.getBlockPos().toShortString(), pieces, made);
        }
        if (made > 0) {
            ItemStack rest = camp.insert(new ItemStack(Armament.item(), made));
            com.ale.warbandcraft.WarbandCraft.LOGGER.info("Desguace en el Campamento {}: armamento en el almacén ahora {}{}",
                    camp.getBlockPos().toShortString(), camp.count(Armament.item()), rest.isEmpty() ? "" : " (no entró " + rest.getCount() + ", al piso)");
            if (!rest.isEmpty() && camp.getLevel() != null) {
                net.minecraft.core.BlockPos p = camp.getBlockPos();
                net.minecraft.world.Containers.dropItemStack(camp.getLevel(), p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, rest);
            }
        }
        return made;
    }
}
