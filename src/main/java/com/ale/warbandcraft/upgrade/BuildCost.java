package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.camp.CampBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * H22: lo que cuesta una obra en materiales (estilo Stronghold): cada bloque del plano pide su madera, piedra, tierra...
 * Se paga todo al encargar, primero del almacén de la aldea y después del inventario; lo que no se puso se devuelve al
 * cancelar. Los bloques sin material (heno, calabazas, blancos...) son adornos gratis.
 */
public final class BuildCost {
    /** Hierro de un yunque (de verdad son 31: a revisar). */
    public static int ANVIL_IRON = 8;

    private BuildCost() {}

    /** Una forma de pagar un material: qué ítem y cuánto vale cada uno. */
    private record Accept(Predicate<ItemStack> match, int value) {}

    public enum Material {
        WOOD("Madera", Items.OAK_PLANKS, List.of(new Accept(s -> s.is(ItemTags.PLANKS), 1), new Accept(s -> s.is(ItemTags.LOGS), 4))),
        STONE("Piedra", Items.COBBLESTONE, List.of(new Accept(s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS) || s.is(Items.STONE), 1))),
        DIRT("Tierra", Items.DIRT, List.of(new Accept(s -> s.is(Items.DIRT) || s.is(Items.COARSE_DIRT), 1))),
        GLASS("Vidrio", Items.GLASS, List.of(new Accept(s -> s.is(Items.GLASS), 1))),
        WOOL("Lana", Items.WHITE_WOOL, List.of(new Accept(s -> s.is(ItemTags.WOOL), 1))),
        IRON("Hierro", Items.IRON_INGOT, List.of(new Accept(s -> s.is(Items.IRON_INGOT), 1)));

        public final String label;
        /** Lo que se devuelve al cancelar o de vuelto (1 = una unidad del material). */
        final Item unit;
        final List<Accept> accepts;

        Material(String label, Item unit, List<Accept> accepts) {
            this.label = label;
            this.unit = unit;
            this.accepts = accepts;
        }
    }

    /** La cuenta de una obra: unidades de cada material (por ordinal) y esmeraldas. {@code removed} = bloques que se sacan. */
    public record Bill(int[] amounts, int emeralds, int removed) {
        public static Bill none() {
            return new Bill(new int[Material.values().length], 0, 0);
        }

        public int get(Material m) {
            return m.ordinal() < amounts.length ? amounts[m.ordinal()] : 0;
        }

        /** "Madera 40 · Piedra 12 · 6 esm." */
        public String text() {
            StringBuilder b = new StringBuilder();
            for (Material m : Material.values()) {
                if (get(m) <= 0) continue;
                if (b.length() > 0) b.append(" · ");
                b.append(m.label).append(' ').append(get(m));
            }
            if (emeralds > 0) b.append(b.length() > 0 ? " · " : "").append(emeralds).append(" esm.");
            return b.length() == 0 ? "Gratis" : b.toString();
        }

        /** Esta cuenta por {@code f} (0..1), para devolver lo que no se usó. */
        public Bill times(double f) {
            int[] a = new int[amounts.length];
            for (int i = 0; i < a.length; i++) a[i] = (int) Math.floor(amounts[i] * f);
            return new Bill(a, (int) Math.floor(emeralds * f), 0);
        }
    }

    // ------------------------------------------------------------------ calcular

    /** Lo que cuesta poner {@code plan} en el mundo como está ahora, más {@code emeralds}. */
    public static Bill of(ServerLevel level, Blueprints.Plan plan, int emeralds) {
        double[] need = new double[Material.values().length];
        int removed = 0;
        for (Map.Entry<BlockPos, BlockState> e : plan.blocks.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            BlockState want = e.getValue();
            if (now == want || level.getBlockEntity(e.getKey()) != null) continue;   // ya está, o no se pisa
            if (!now.isAir() && !now.canBeReplaced()) removed++;
            add(want, need);
        }
        int[] amounts = new int[need.length];
        for (int i = 0; i < need.length; i++) amounts[i] = (int) Math.ceil(need[i] - 1e-6);
        return new Bill(amounts, emeralds, removed);
    }

    /** El material de un bloque (en unidades: tablones, adoquines...). */
    static void add(BlockState s, double[] n) {
        if (s.isAir()) return;
        Block b = s.getBlock();
        int wood = Material.WOOD.ordinal(), stone = Material.STONE.ordinal(), dirt = Material.DIRT.ordinal();
        int glass = Material.GLASS.ordinal(), wool = Material.WOOL.ordinal(), iron = Material.IRON.ordinal();
        if (b instanceof BedBlock) {
            if (s.getValue(BedBlock.PART) == BedPart.HEAD) {
                n[wool] += 3;
                n[wood] += 3;
            }
        } else if (b instanceof DoorBlock) {
            if (s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) n[wood] += 2;
        } else if (s.is(BlockTags.LOGS)) n[wood] += 4;
        else if (s.is(BlockTags.PLANKS)) n[wood] += 1;
        else if (s.is(BlockTags.WOODEN_SLABS)) n[wood] += 0.5;
        else if (s.is(BlockTags.WOODEN_STAIRS)) n[wood] += 1.5;
        else if (s.is(BlockTags.WOODEN_FENCES)) n[wood] += 1.5;
        else if (s.is(BlockTags.FENCE_GATES)) n[wood] += 4;
        else if (s.is(BlockTags.WOODEN_TRAPDOORS)) n[wood] += 3;
        else if (s.is(BlockTags.WOODEN_PRESSURE_PLATES)) n[wood] += 2;
        else if (s.is(Blocks.LADDER) || s.is(Blocks.SCAFFOLDING)) n[wood] += 0.5;
        else if (s.is(Blocks.CRAFTING_TABLE)) n[wood] += 4;
        else if (s.is(Blocks.BARREL)) n[wood] += 7;
        else if (s.is(Blocks.CHEST)) n[wood] += 8;
        else if (s.is(Blocks.COMPOSTER)) n[wood] += 3.5;
        else if (s.is(Blocks.LECTERN)) n[wood] += 6;
        else if (s.is(Blocks.CAMPFIRE)) n[wood] += 3;
        else if (s.is(Blocks.SMOKER)) {
            n[stone] += 8;
            n[wood] += 16;
        } else if (s.is(Blocks.FURNACE)) n[stone] += 8;
        else if (s.is(Blocks.BLAST_FURNACE)) {
            n[stone] += 8;
            n[iron] += 5;
        } else if (s.is(Blocks.GRINDSTONE)) {
            n[stone] += 1;
            n[wood] += 2;
        } else if (s.is(Blocks.STONECUTTER)) {
            n[stone] += 3;
            n[iron] += 1;
        } else if (s.is(Blocks.SMITHING_TABLE)) {
            n[wood] += 4;
            n[iron] += 2;
        } else if (s.is(BlockTags.ANVIL)) n[iron] += ANVIL_IRON;
        else if (s.is(Blocks.CAULDRON) || s.is(Blocks.LAVA_CAULDRON) || s.is(Blocks.WATER_CAULDRON)) n[iron] += 7;
        else if (s.is(Blocks.LANTERN)) n[iron] += 1;
        else if (s.is(Blocks.IRON_BARS)) n[iron] += 0.375;
        else if (s.is(Blocks.GLASS)) n[glass] += 1;
        else if (s.is(Blocks.GLASS_PANE)) n[glass] += 0.375;
        else if (s.is(BlockTags.WOOL)) n[wool] += 1;
        else if (s.is(BlockTags.WOOL_CARPETS)) n[wool] += 0.67;
        else if (s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.DIRT_PATH)
                || s.is(Blocks.FARMLAND) || s.is(Blocks.ROOTED_DIRT)) n[dirt] += 1;
        else if (s.is(BlockTags.SLABS)) n[stone] += 0.5;
        else if (s.is(BlockTags.STAIRS)) n[stone] += 1.5;
        else if (s.is(BlockTags.WALLS)) n[stone] += 1;
        else if (s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.STONE)
                || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.DIORITE)
                || s.is(Blocks.POLISHED_DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.POLISHED_GRANITE)) n[stone] += 1;
        // el resto (heno, calabazas, blancos...): adorno gratis
    }

    // ------------------------------------------------------------------ pagar

    /** De dónde sale: el almacén (Campamento + vinculados) y, si hay, el inventario del jugador. */
    private static List<Container> sources(@Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        List<Container> out = new ArrayList<>();
        if (camp != null) out.addAll(camp.storages());
        if (player != null) out.add(player.getInventory());
        return out;
    }

    /** Cuántas unidades de {@code m} hay entre el almacén y el inventario. */
    public static int available(Material m, @Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        int total = 0;
        for (Container c : sources(camp, player)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                for (Accept a : m.accepts) {
                    if (a.match.test(s)) {
                        total += s.getCount() * a.value;
                        break;
                    }
                }
            }
        }
        return total;
    }

    /** "Madera 12 · Piedra 4" de los materiales que faltan, o null si alcanza (las esmeraldas las cobra {@link Upgrades}). */
    @Nullable
    public static String missing(Bill bill, @Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        StringBuilder b = new StringBuilder();
        for (Material m : Material.values()) {
            int lack = bill.get(m) - available(m, camp, player);
            if (lack <= 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(m.label).append(' ').append(lack);
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** Cobra los materiales (primero el almacén, después el inventario). Hay que revisar antes con {@link #missing}. */
    public static void pay(Bill bill, @Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        List<Container> from = sources(camp, player);
        for (Material m : Material.values()) {
            int need = bill.get(m);
            for (Accept a : m.accepts) {   // primero lo que vale 1 (tablones), después lo que da vuelto (troncos)
                for (Container c : from) {
                    for (int i = 0; i < c.getContainerSize() && need > 0; i++) {
                        ItemStack s = c.getItem(i);
                        if (s.isEmpty() || !a.match.test(s)) continue;
                        int k = Math.min(s.getCount(), (need + a.value - 1) / a.value);
                        s.shrink(k);
                        if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                        c.setChanged();
                        need -= k * a.value;
                    }
                }
            }
            if (need < 0) give(new ItemStack(m.unit, -need), camp, player);   // el vuelto: tablones de un tronco
        }
    }

    /** Devuelve la cuenta (en unidades de cada material) al almacén; lo que no entra, al jugador o al piso. */
    public static void refund(Bill bill, @Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        for (Material m : Material.values()) {
            int n = bill.get(m);
            while (n > 0) {
                int k = Math.min(n, 64);
                give(new ItemStack(m.unit, k), camp, player);
                n -= k;
            }
        }
        int em = bill.emeralds();
        while (em > 0) {
            int k = Math.min(em, 64);
            give(new ItemStack(Items.EMERALD, k), camp, player);
            em -= k;
        }
    }

    /** Al almacén; si no entra, al inventario; si tampoco, al piso al lado del almacén o del jugador. */
    public static void give(ItemStack stack, @Nullable CampBlockEntity camp, @Nullable ServerPlayer player) {
        ItemStack rest = camp == null ? stack : camp.insert(stack);
        if (rest.isEmpty()) return;
        if (player != null && player.getInventory().add(rest) && rest.isEmpty()) return;
        if (camp != null && camp.getLevel() != null) Block.popResource(camp.getLevel(), camp.getBlockPos().above(), rest);
        else if (player != null) player.drop(rest, false);
    }
}
