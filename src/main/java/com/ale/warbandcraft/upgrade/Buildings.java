package com.ale.warbandcraft.upgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Los planos de los edificios de las aldeas (pedido de Ale: menos básicos, según la facción o el bioma, y distintos por
 * nivel). Cada edificio se dibuja en su terreno de {@link Upgrade#siteSize()} de lado con los materiales de su
 * {@link Style}: x, z desde la esquina, y desde el piso, la puerta mira al norte (z = 0) sin girar. Los niveles de un mismo
 * edificio comparten el terreno (se reserva el del más grande): la Taberna 1 es una sala, la 2 una posada con cuartos y
 * la 3 un hostal de dos pisos con camas en fila arriba.
 */
final class Buildings {
    private Buildings() {}

    /**
     * Dónde se para quien trabaja en el edificio ya hecho (x, z del terreno sin girar, a la altura del piso + 1): el
     * Tabernero detrás de la barra, el Mercader en la plaza, los del cuartel en el medio. Null = el medio.
     */
    static int[] anchor(Upgrade u) {
        return switch (u) {
            case TAVERN_1 -> new int[]{4, 8};
            case TAVERN_2 -> new int[]{3, 9};
            case TAVERN_3 -> new int[]{3, 8};
            case MARKET, MARKET_2 -> new int[]{6, 6};
            case MARKET_3 -> new int[]{6, 4};
            case BARRACKS -> new int[]{5, 5};
            case MAINTENANCE -> new int[]{3, 3};
            case KITCHEN -> new int[]{4, 5};
            default -> null;
        };
    }

    /** Dibuja {@code u} en el lienzo (el terreno ya viene allanado). */
    static void draw(Canvas c, Upgrade u) {
        switch (u) {
            case TAVERN_1 -> tavern1(c);
            case TAVERN_2 -> tavern2(c);
            case TAVERN_3 -> tavern3(c);
            case HOUSE -> house(c);
            case BARRACKS -> barracks(c);
            case SMITHY -> smithy(c);
            case MARKET, MARKET_2, MARKET_3 -> market(c, u.marketLevel());
            case TRAINING_1, TRAINING_2, TRAINING_3 -> training(c, u.trainingLevel());
            case CHAPEL -> chapel(c);
            case GRANARY -> granary(c);
            case MAINTENANCE -> maintenance(c);
            case KITCHEN -> kitchen(c);
            case BUILDER_1, BUILDER_2, BUILDER_3 -> builder(c, u.givesBuilder());
            default -> {
            }
        }
        c.finish();
    }

    // ------------------------------------------------------------------ taberna

    /** Taberna 1: una sala de 9×7 con barra, dos mesas, banco y farol en la puerta. */
    private static void tavern1(Canvas c) {
        Style s = c.st;
        c.walls(2, 4, 10, 10, 0, 4);
        c.roof(2, 4, 10, 10, 4, false);
        c.door(6, 1, 4, Direction.SOUTH);
        c.beam(3, 4, 7, 9, 4, 7);
        // la barra y los barriles de atrás
        for (int x = 3; x <= 5; x++) c.set(x, 1, 7, Canvas.slab(slabOf(s.planks), SlabType.TOP));
        c.barrel(3, 1, 9);
        c.barrel(3, 2, 9);
        c.barrel(4, 1, 9);
        c.set(5, 1, 9, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.table(8, 6, Direction.WEST, Direction.EAST, Direction.NORTH);
        c.table(8, 8, Direction.WEST, Direction.EAST, Direction.SOUTH);
        c.hanging(6, 3, 7);
        c.hanging(8, 3, 7);
        // afuera: camino, banco contra la pared, barril y farol
        for (int z = 0; z <= 3; z++) c.path(6, z);
        c.set(3, 1, 3, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.set(4, 1, 3, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.barrel(9, 1, 3);
        c.post(8, 2);
        c.banner(6, 3, 3, Direction.NORTH);
        c.pot(2, 1, 3);
    }

    /** Taberna 2: posada de 11×9 con sala, cocina con hogar, tres cuartos y un porche. */
    private static void tavern2(Canvas c) {
        Style s = c.st;
        c.walls(1, 3, 11, 11, 0, 5);
        c.roof(1, 3, 11, 11, 5, false);
        c.door(5, 1, 3, Direction.SOUTH);
        // tabique de los cuartos (x = 8) con su puerta
        c.fill(8, 1, 4, 8, 5, 10, s.planks);
        c.door(8, 1, 7, Direction.EAST);
        c.beam(2, 5, 7, 7, 5, 7);
        // barra, estantes y cocina
        for (int x = 2; x <= 4; x++) c.set(x, 1, 8, Canvas.slab(slabOf(s.planks), SlabType.TOP));
        c.barrel(2, 1, 10);
        c.barrel(2, 2, 10);
        c.barrel(3, 1, 10);
        c.set(4, 1, 10, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(5, 1, 10, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
        c.hearth(6, 11, 12, 8);
        c.late(6, 1, 10, s.carpet.defaultBlockState());
        // mesas
        c.table(3, 5, Direction.WEST, Direction.EAST);
        c.table(7, 5, Direction.WEST, Direction.NORTH, Direction.SOUTH);
        c.table(3, 7, Direction.WEST, Direction.EAST);
        c.table(7, 8, Direction.WEST, Direction.SOUTH);
        c.hanging(3, 4, 7);
        c.hanging(6, 4, 7);
        // los cuartos: tres camas contra la pared, un baúl con farol
        c.bed(10, 1, 5, Direction.NORTH);
        c.bed(10, 1, 7, Direction.NORTH);
        c.bed(10, 1, 9, Direction.SOUTH);
        c.chest(10, 1, 8, Direction.WEST);
        c.lantern(10, 2, 8);
        // porche con techo, bancos y faroles
        c.fill(2, 0, 1, 10, 0, 2, s.planks);
        for (int y = 1; y <= 3; y++) {
            c.log(2, y, 1, Direction.Axis.Y);
            c.log(10, y, 1, Direction.Axis.Y);
        }
        c.fill(2, 4, 1, 10, 4, 2, Canvas.slab(s.roofSlab, SlabType.BOTTOM));
        for (int x = 3; x <= 9; x++) if (x != 5) c.set(x, 1, 1, s.fence.defaultBlockState());
        c.set(3, 1, 2, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.set(4, 1, 2, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.set(7, 1, 2, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.set(8, 1, 2, Canvas.stairs(s.seat, Direction.SOUTH, Half.BOTTOM));
        c.barrel(9, 1, 2);
        c.hanging(4, 3, 1);
        c.hanging(7, 3, 1);
        c.banner(5, 3, 2, Direction.NORTH);
        c.path(5, 0);
    }

    /**
     * Taberna 3: hostal de 11×11 y dos pisos. Abajo: barra, cocina, hogar, cuatro mesas y la escalera; arriba: el
     * dormitorio con nueve camas en fila, baúles y faroles.
     */
    private static void tavern3(Canvas c) {
        Style s = c.st;
        c.walls(1, 1, 11, 11, 0, 4);
        c.floor(2, 2, 10, 10, 5, s.planks);
        c.walls(1, 1, 11, 11, 5, 4, null);   // el piso de arriba, sobre una viga corrida
        c.roof(1, 1, 11, 11, 9, false);
        c.door(6, 1, 1, Direction.SOUTH);
        // escalera contra la pared del este (sube hacia el norte) con su hueco y baranda arriba
        int[][] steps = {{1, 9}, {2, 8}, {3, 7}, {4, 6}, {5, 5}};
        for (int[] st : steps) {
            c.set(10, st[0], st[1], Canvas.stairs(s.seat, Direction.NORTH, Half.BOTTOM));
            for (int y = 1; y < st[0]; y++) c.set(10, y, st[1], s.planks.defaultBlockState());
        }
        for (int z = 6; z <= 9; z++) {
            c.air(10, 5, z);
            c.set(9, 6, z, s.fence.defaultBlockState());
        }
        c.set(10, 6, 10, s.fence.defaultBlockState());
        // barra en L, estantes, cocina y hogar
        for (int x = 2; x <= 5; x++) c.set(x, 1, 7, Canvas.slab(slabOf(s.planks), SlabType.TOP));
        c.barrel(2, 1, 9);
        c.barrel(2, 2, 9);
        c.barrel(2, 1, 10);
        c.barrel(3, 1, 10);
        c.barrel(3, 2, 10);
        c.set(4, 1, 10, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(5, 1, 10, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.hearth(8, 11, 12, 12);
        c.late(8, 1, 10, s.carpet.defaultBlockState());
        c.table(3, 3, Direction.WEST, Direction.EAST);
        c.table(3, 5, Direction.WEST, Direction.EAST);
        c.table(8, 3, Direction.WEST, Direction.EAST);
        c.table(8, 5, Direction.WEST, Direction.EAST);
        c.hanging(4, 4, 4);
        c.hanging(8, 4, 4);
        c.hanging(4, 4, 8);
        c.hanging(7, 4, 9);
        // arriba: el dormitorio
        for (int z : new int[]{2, 3, 5, 6, 8, 9}) c.bed(3, 6, z, Direction.WEST);
        for (int z : new int[]{2, 3, 4}) c.bed(9, 6, z, Direction.EAST);
        c.chest(2, 6, 4, Direction.EAST);
        c.lantern(2, 7, 4);
        c.barrel(2, 6, 7);
        c.chest(2, 6, 10, Direction.EAST);
        c.lantern(2, 7, 10);
        for (int z = 2; z <= 10; z++) c.late(6, 6, z, s.carpet.defaultBlockState());
        c.beam(2, 9, 4, 10, 9, 4);
        c.beam(2, 9, 8, 10, 9, 8);
        c.hanging(6, 8, 4);
        c.hanging(6, 8, 8);
        // la fachada
        c.path(6, 0);
        c.post(2, 0);
        c.post(10, 0);
        c.banner(4, 3, 0, Direction.NORTH);
        c.banner(8, 3, 0, Direction.NORTH);
    }

    // ------------------------------------------------------------------ casa, cuartel, herrería

    /** Casa de 7×6 con dos camas, baúl, horno, mesa y macetas en la puerta. */
    private static void house(Canvas c) {
        c.walls(1, 2, 7, 7, 0, 4);
        c.roof(1, 2, 7, 7, 4, false);
        c.door(4, 1, 2, Direction.SOUTH);
        c.beam(2, 4, 4, 6, 4, 4);
        c.bed(2, 1, 5, Direction.SOUTH);
        c.bed(6, 1, 5, Direction.SOUTH);
        c.chest(3, 1, 6, Direction.NORTH);
        c.set(5, 1, 6, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(4, 1, 6, Blocks.CRAFTING_TABLE.defaultBlockState());
        c.table(4, 4, Direction.EAST);
        c.hanging(3, 3, 4);
        c.path(4, 0);
        c.path(4, 1);
        c.pot(2, 1, 1);
        c.pot(6, 1, 1);
    }

    /** Cuartel de 9×8: dos filas de cuatro camas con baúles, piedra de afilar, estandartes y un muñeco afuera. */
    private static void barracks(Canvas c) {
        Style s = c.st;
        c.walls(1, 2, 9, 9, 0, 4);
        c.roof(1, 2, 9, 9, 4, true);
        c.door(5, 1, 2, Direction.SOUTH);
        for (int z : new int[]{3, 4, 6, 7}) {
            c.bed(3, 1, z, Direction.WEST);
            c.bed(7, 1, z, Direction.EAST);
        }
        c.barrel(2, 1, 5);
        c.barrel(8, 1, 5);
        c.chest(2, 1, 8, Direction.EAST);
        c.chest(8, 1, 8, Direction.WEST);
        c.barrel(4, 1, 8);
        c.set(5, 1, 8, Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        c.barrel(6, 1, 8);
        c.banner(5, 3, 8, Direction.NORTH);
        c.beam(2, 4, 4, 8, 4, 4);
        c.beam(2, 4, 7, 8, 4, 7);
        c.hanging(5, 3, 4);
        c.hanging(5, 3, 7);
        c.path(5, 0);
        c.path(5, 1);
        c.banner(3, 3, 1, Direction.NORTH);
        c.banner(7, 3, 1, Direction.NORTH);
        c.post(1, 0);
        c.dummy(9, 0, Direction.NORTH);
    }

    /** Herrería de 9×9 abierta: fragua con campana y chimenea, yunque en el medio, mesa de herrero, piedra de afilar. */
    private static void smithy(Canvas c) {
        Style s = c.st;
        c.floor(1, 1, 9, 9, 0, s.found);
        for (int x : new int[]{1, 5, 9}) for (int z : new int[]{1, 9}) for (int y = 1; y <= 3; y++) c.log(x, y, z, Direction.Axis.Y);
        for (int y = 1; y <= 3; y++) {
            c.log(1, y, 5, Direction.Axis.Y);
            c.log(9, y, 5, Direction.Axis.Y);
        }
        c.beam(1, 4, 1, 9, 4, 1);
        c.beam(1, 4, 9, 9, 4, 9);
        c.beam(1, 4, 1, 1, 4, 9);
        c.beam(9, 4, 1, 9, 4, 9);
        c.beam(2, 4, 5, 8, 4, 5);
        // medias paredes atrás y al oeste
        for (int x = 2; x <= 8; x++) if (x != 5) c.fill(x, 1, 9, x, 2, 9, s.found);
        for (int z = 2; z <= 8; z++) if (z != 5) c.fill(1, 1, z, 1, 2, z, s.found);
        c.roof(1, 1, 9, 9, 4, false);
        // la fragua
        c.set(2, 1, 8, Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(3, 1, 8, Blocks.LAVA_CAULDRON.defaultBlockState());
        c.set(4, 1, 8, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.fill(2, 3, 8, 4, 3, 8, s.found);
        c.chimney(3, 9, 3, 8);
        c.set(5, 1, 5, Blocks.ANVIL.defaultBlockState());
        c.set(6, 1, 6, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
        c.set(7, 1, 8, Blocks.SMITHING_TABLE.defaultBlockState());
        c.set(8, 1, 8, Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        c.chest(8, 1, 2, Direction.SOUTH);
        c.barrel(2, 1, 2);
        c.barrel(2, 2, 2);
        c.set(8, 1, 3, Blocks.IRON_BARS.defaultBlockState());
        c.hanging(3, 3, 5);
        c.hanging(7, 3, 5);
        c.path(5, 0);
    }

    // ------------------------------------------------------------------ mercado, entrenamiento

    /**
     * Mercado (nivel 1): tres puestos con toldo a rayas en el color de la aldea. Plaza de mercado: tres más atrás y bancos.
     * Lonja: piso de piedra, un pozo techado en el medio y faroles en las esquinas.
     */
    private static void market(Canvas c, int lvl) {
        Style s = c.st;
        int m = c.size - 1;
        if (lvl == 3) {
            for (int x = 0; x <= m; x++) for (int z = 0; z <= m; z++) {
                c.set(x, 0, z, ((x + z) % 2 == 0 ? s.found : Blocks.POLISHED_ANDESITE).defaultBlockState());
            }
        }
        Block[] goods = {Blocks.PUMPKIN, Blocks.MELON, Blocks.HAY_BLOCK, Blocks.IRON_BLOCK, Blocks.COAL_BLOCK, Blocks.BOOKSHELF};
        for (int k = 0; k < 3; k++) c.stall(1 + k * 4, 1, goods[k], false);
        if (lvl >= 2) {
            for (int k = 0; k < 3; k++) c.stall(1 + k * 4, 9, goods[3 + k], true);
            c.set(1, 1, 6, Canvas.stairs(s.seat, Direction.WEST, Half.BOTTOM));
            c.set(1, 1, 7, Canvas.stairs(s.seat, Direction.WEST, Half.BOTTOM));
            c.set(11, 1, 6, Canvas.stairs(s.seat, Direction.EAST, Half.BOTTOM));
            c.set(11, 1, 7, Canvas.stairs(s.seat, Direction.EAST, Half.BOTTOM));
        } else {
            c.set(3, 1, 8, Blocks.COMPOSTER.defaultBlockState());
            c.barrel(9, 1, 8);
            c.barrel(10, 1, 8);
        }
        if (lvl == 3) {   // el pozo
            for (int x = 5; x <= 7; x++) for (int z = 5; z <= 7; z++) {
                if (x == 6 && z == 6) continue;
                c.set(x, 1, z, s.found.defaultBlockState());
            }
            c.set(6, 0, 6, s.found.defaultBlockState());
            c.set(6, 1, 6, Blocks.WATER.defaultBlockState());
            for (int[] p : new int[][]{{5, 5}, {7, 5}, {5, 7}, {7, 7}}) c.fill(p[0], 2, p[1], p[0], 3, p[1], s.fence);
            c.fill(5, 4, 5, 7, 4, 7, Canvas.slab(s.roofSlab, SlabType.BOTTOM));
            c.hanging(6, 3, 6);
            for (int[] p : new int[][]{{0, 0}, {m, 0}, {0, m}, {m, m}}) c.post(p[0], p[1]);
        } else {
            c.post(0, 6);
            c.post(m, 6);
        }
    }

    /** Campo de entrenamiento con cerco: muñecos, blancos de tiro y, en el nivel 3, un ring y un cobertizo de armas. */
    private static void training(Canvas c, int lvl) {
        Style s = c.st;
        int m = c.size - 1;
        for (int i = 0; i <= m; i++) {
            c.set(i, 1, 0, s.fence.defaultBlockState());
            c.set(i, 1, m, s.fence.defaultBlockState());
            c.set(0, 1, i, s.fence.defaultBlockState());
            c.set(m, 1, i, s.fence.defaultBlockState());
        }
        c.set(6, 1, 0, gateOf(s.fence).defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH));
        c.path(6, 0);
        c.post(0, 0);
        c.post(m, 0);
        int dummies = lvl + 1;
        for (int k = 0; k < dummies; k++) c.dummy(2 + k * 2, 3, Direction.SOUTH);
        int targets = lvl == 1 ? 1 : 3;
        for (int k = 0; k < targets; k++) {
            int x = 4 + k * 2;
            c.set(x, 1, 11, s.fence.defaultBlockState());
            c.set(x, 2, 11, Blocks.TARGET.defaultBlockState());
        }
        c.barrel(1, 1, 1);
        c.barrel(1, 2, 1);
        c.set(2, 1, 1, Canvas.stairs(s.seat, Direction.NORTH, Half.BOTTOM));
        c.set(3, 1, 1, Canvas.stairs(s.seat, Direction.NORTH, Half.BOTTOM));
        if (lvl >= 2) {
            c.set(11, 1, 1, Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
            c.set(10, 1, 1, Blocks.HAY_BLOCK.defaultBlockState());
            c.set(10, 2, 1, Blocks.HAY_BLOCK.defaultBlockState());
        }
        if (lvl >= 3) {
            // ring de arena para pelear
            for (int x = 2; x <= 6; x++) for (int z = 6; z <= 9; z++) c.set(x, 0, z, Blocks.SAND.defaultBlockState());
            for (int x = 1; x <= 7; x++) {
                c.set(x, 1, 5, s.fence.defaultBlockState());
                c.set(x, 1, 10, s.fence.defaultBlockState());
            }
            for (int z = 6; z <= 9; z++) {
                c.set(1, 1, z, s.fence.defaultBlockState());
                if (z != 7) c.set(7, 1, z, s.fence.defaultBlockState());
            }
            // cobertizo de armas
            for (int[] p : new int[][]{{9, 5}, {11, 5}, {9, 9}, {11, 9}}) for (int y = 1; y <= 3; y++) c.log(p[0], y, p[1], Direction.Axis.Y);
            c.fill(9, 4, 5, 11, 4, 9, Canvas.slab(s.roofSlab, SlabType.BOTTOM));
            c.fill(11, 1, 6, 11, 2, 8, s.wall);
            c.set(10, 1, 6, Blocks.ANVIL.defaultBlockState());
            c.set(10, 1, 7, Blocks.SMITHING_TABLE.defaultBlockState());
            c.chest(10, 1, 8, Direction.WEST);
            c.hanging(10, 3, 7);
        }
    }

    // ------------------------------------------------------------------ capilla, granero, taller

    /** Capilla de piedra: nave con vitrales del color de la aldea, bancos, pasillo, altar con velas y campanario. */
    private static void chapel(Canvas c) {
        Style s = c.st;
        c.walls(2, 1, 8, 9, 0, 5, s.found, s.found, s.frame, s.glass);
        c.roof(2, 1, 8, 9, 5, true);
        c.door(5, 1, 1, Direction.SOUTH);
        if (!s.flat) c.set(5, 7, 1, s.glass.defaultBlockState());
        for (int z : new int[]{3, 5}) for (int x : new int[]{3, 4, 6, 7}) c.set(x, 1, z, Canvas.stairs(s.seat, Direction.NORTH, Half.BOTTOM));
        for (int z = 2; z <= 6; z++) c.late(5, 1, z, s.carpet.defaultBlockState());
        c.set(5, 1, 8, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        c.set(5, 1, 7, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.NORTH));
        c.late(4, 1, 8, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true).setValue(CandleBlock.CANDLES, 3));
        c.late(6, 1, 8, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true).setValue(CandleBlock.CANDLES, 3));
        c.late(5, 2, 8, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
        c.banner(5, 4, 8, Direction.NORTH);
        c.beam(3, 5, 3, 7, 5, 3);
        c.beam(3, 5, 6, 7, 5, 6);
        c.hanging(5, 4, 3);
        c.hanging(5, 4, 6);
        // campanario sobre la punta de atrás (con un farol: una campana sería otra aldea)
        int top = s.flat ? 7 : 9;
        c.set(5, top, 9, s.found.defaultBlockState());
        for (int y = top; y <= top + 2; y++) {
            c.set(4, y, 9, s.found.defaultBlockState());
            c.set(6, y, 9, s.found.defaultBlockState());
        }
        c.fill(4, top + 3, 9, 6, top + 3, 9, Canvas.slab(s.roofSlab, SlabType.BOTTOM));
        c.hanging(5, top + 2, 9);
        c.path(5, 0);
        c.pot(3, 1, 0);
        c.pot(7, 1, 0);
    }

    /** Granero de 7×7: fardos de heno apilados, compostera, barriles y una ventana de carga. */
    private static void granary(Canvas c) {
        c.walls(1, 1, 7, 7, 0, 5);
        c.roof(1, 1, 7, 7, 5, false);
        c.door(4, 1, 1, Direction.SOUTH);
        c.fill(2, 1, 4, 6, 2, 6, Blocks.HAY_BLOCK);
        c.fill(2, 3, 5, 6, 3, 6, Blocks.HAY_BLOCK);
        c.set(2, 1, 2, Blocks.COMPOSTER.defaultBlockState());
        c.barrel(6, 1, 2);
        c.barrel(6, 2, 2);
        c.beam(2, 5, 3, 6, 5, 3);
        c.hanging(4, 4, 3);
        c.path(4, 0);
        c.late(7, 1, 0, Blocks.HAY_BLOCK.defaultBlockState());
    }

    /**
     * Caseta de mantenimiento (pedido de Ale, un mini cobertizo) de 5×4: abierta adelante, con banco de trabajo, piedra
     * de afilar, barril de herramientas, pila de adoquines y troncos, estandarte atrás y un farol en la entrada.
     */
    private static void maintenance(Canvas c) {
        c.walls(1, 2, 5, 5, 0, 3);
        c.roof(1, 2, 5, 5, 3, false);
        for (int x = 2; x <= 4; x++) for (int y = 1; y <= 2; y++) c.air(x, y, 2);   // el frente abierto
        c.set(2, 1, 4, Blocks.CRAFTING_TABLE.defaultBlockState());
        c.set(4, 1, 4, Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
        c.barrel(4, 1, 3);
        c.banner(3, 3, 4, Direction.NORTH);
        c.hanging(3, 3, 3);
        c.set(0, 1, 5, Blocks.COBBLESTONE.defaultBlockState());   // la pila de materiales al costado
        c.log(0, 1, 4, Direction.Axis.Z);
        c.log(0, 2, 4, Direction.Axis.Z);
        c.post(6, 1);
        c.path(3, 0);
        c.path(3, 1);
    }

    /**
     * Cocina (pedido de Ale): sala de 7×7 con el hogar y su chimenea atrás, dos ahumadores y un horno, barriles y la mesa
     * de amasar en el medio. El cocinero se para entre la mesa y el hogar.
     */
    private static void kitchen(Canvas c) {
        c.walls(1, 1, 7, 7, 0, 4);
        c.roof(1, 1, 7, 7, 4, false);
        c.door(4, 1, 1, Direction.SOUTH);
        c.path(4, 0);
        c.hearth(4, 7, 8, 7);
        c.set(2, 1, 6, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(6, 1, 6, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        c.set(6, 1, 5, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        c.set(2, 1, 5, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
        c.barrel(2, 1, 2);
        c.barrel(2, 1, 3);
        c.barrel(2, 2, 2);
        c.set(6, 1, 2, Blocks.CRAFTING_TABLE.defaultBlockState());
        c.set(6, 1, 3, Blocks.HAY_BLOCK.defaultBlockState());
        c.table(4, 3);
        c.hanging(4, 3, 4);
        c.set(0, 1, 3, Blocks.OAK_LOG.defaultBlockState());   // la leña al costado
        c.log(0, 1, 4, Direction.Axis.Z);
        c.log(0, 2, 4, Direction.Axis.Z);
    }

    /** Taller del constructor: cobertizo (1), taller con pila de troncos (2), casa de dos pisos con su cuarto (3). */
    private static void builder(Canvas c, int lvl) {
        Style s = c.st;
        if (lvl == 1) {
            c.walls(3, 3, 7, 7, 0, 4);
            c.roof(3, 3, 7, 7, 4, false);
            c.door(5, 1, 3, Direction.SOUTH);
            c.set(4, 1, 6, Blocks.CRAFTING_TABLE.defaultBlockState());
            c.chest(6, 1, 6, Direction.NORTH);
            c.beam(4, 4, 5, 6, 4, 5);
            c.hanging(5, 3, 5);
            c.path(5, 2);
            return;
        }
        if (lvl == 2) {
            c.walls(2, 2, 8, 8, 0, 4);
            c.roof(2, 2, 8, 8, 4, false);
            c.door(5, 1, 2, Direction.SOUTH);
            c.set(3, 1, 7, Blocks.CRAFTING_TABLE.defaultBlockState());
            c.set(4, 1, 7, Blocks.STONECUTTER.defaultBlockState());
            c.chest(6, 1, 7, Direction.NORTH);
            c.barrel(7, 1, 7);
            c.fill(7, 1, 3, 7, 2, 3, Blocks.SCAFFOLDING);
            c.beam(3, 4, 5, 7, 4, 5);
            c.hanging(5, 3, 5);
            for (int z = 3; z <= 6; z++) c.log(9, 1, z, Direction.Axis.Z);
            c.log(9, 2, 4, Direction.Axis.Z);
            c.log(9, 2, 5, Direction.Axis.Z);
            c.path(5, 1);
            return;
        }
        c.walls(1, 1, 9, 9, 0, 4);
        c.floor(2, 2, 8, 8, 4, s.planks);
        c.walls(1, 1, 9, 9, 4, 4, null);
        c.roof(1, 1, 9, 9, 8, false);
        c.door(5, 1, 1, Direction.SOUTH);
        c.air(2, 4, 2);
        for (int y = 1; y <= 4; y++) c.late(2, y, 2, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        c.set(3, 1, 8, Blocks.CRAFTING_TABLE.defaultBlockState());
        c.set(4, 1, 8, Blocks.STONECUTTER.defaultBlockState());
        c.chest(6, 1, 8, Direction.NORTH);
        c.barrel(7, 1, 8);
        c.barrel(8, 1, 8);
        c.fill(8, 1, 3, 8, 3, 3, Blocks.SCAFFOLDING);
        c.hanging(5, 3, 5);
        c.hanging(5, 7, 5);
        c.beam(2, 8, 5, 8, 8, 5);
        c.bed(7, 5, 7, Direction.SOUTH);
        c.chest(6, 5, 8, Direction.NORTH);
        c.set(3, 5, 8, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        c.set(4, 5, 8, Blocks.BOOKSHELF.defaultBlockState());
        c.path(5, 0);
    }

    // ------------------------------------------------------------------ ayudas

    static Block slabOf(Block planks) {
        if (planks == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_SLAB;
        if (planks == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_SLAB;
        if (planks == Blocks.JUNGLE_PLANKS) return Blocks.JUNGLE_SLAB;
        if (planks == Blocks.MANGROVE_PLANKS) return Blocks.MANGROVE_SLAB;
        if (planks == Blocks.ACACIA_PLANKS) return Blocks.ACACIA_SLAB;
        if (planks == Blocks.BIRCH_PLANKS) return Blocks.BIRCH_SLAB;
        return Blocks.OAK_SLAB;
    }

    static Block gateOf(Block fence) {
        if (fence == Blocks.SPRUCE_FENCE) return Blocks.SPRUCE_FENCE_GATE;
        if (fence == Blocks.DARK_OAK_FENCE) return Blocks.DARK_OAK_FENCE_GATE;
        if (fence == Blocks.JUNGLE_FENCE) return Blocks.JUNGLE_FENCE_GATE;
        if (fence == Blocks.MANGROVE_FENCE) return Blocks.MANGROVE_FENCE_GATE;
        if (fence == Blocks.ACACIA_FENCE) return Blocks.ACACIA_FENCE_GATE;
        return Blocks.OAK_FENCE_GATE;
    }

    /**
     * Para dibujar en coordenadas del terreno girado {@code rot} (como {@code StructureTemplate}: horario = (x, z) →
     * (lado−1−z, x)); los bloques se giran con él. Lo que se cuelga o se apoya (puertas, camas, faroles, estandartes,
     * alfombras) va con {@link #late}: se pone al final, cuando ya está lo que lo sostiene.
     */
    static final class Canvas {
        final Blueprints.Plan plan;
        final BlockPos site;
        final Rotation rot;
        final int size;
        final Style st;
        private final Map<BlockPos, BlockState> late = new LinkedHashMap<>();

        Canvas(Blueprints.Plan plan, BlockPos site, Rotation rot, int size, Style st) {
            this.plan = plan;
            this.site = site;
            this.rot = rot;
            this.size = size;
            this.st = st;
        }

        BlockPos at(int x, int y, int z) {
            int m = size - 1;
            return switch (rot) {
                case CLOCKWISE_90 -> site.offset(m - z, y, x);
                case CLOCKWISE_180 -> site.offset(m - x, y, m - z);
                case COUNTERCLOCKWISE_90 -> site.offset(z, y, m - x);
                default -> site.offset(x, y, z);
            };
        }

        void set(int x, int y, int z, BlockState s) {
            BlockPos p = at(x, y, z);
            late.remove(p);
            plan.put(p, s.rotate(rot));
        }

        void set(int x, int y, int z, Block b) {
            set(x, y, z, b.defaultBlockState());
        }

        void late(int x, int y, int z, BlockState s) {
            BlockPos p = at(x, y, z);
            plan.blocks.remove(p);
            late.remove(p);
            late.put(p, s.rotate(rot));
        }

        void air(int x, int y, int z) {
            set(x, y, z, Blocks.AIR.defaultBlockState());
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y, z, s);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
            fill(x0, y0, z0, x1, y1, z1, b.defaultBlockState());
        }

        /** Lo de {@link #late} al final del plano. */
        void finish() {
            late.forEach(plan::put);
            late.clear();
        }

        // -------------------------------------------------------------- estados

        static BlockState stairs(Block b, Direction facing, Half half) {
            return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
        }

        static BlockState slab(Block b, SlabType type) {
            return b.defaultBlockState().setValue(SlabBlock.TYPE, type);
        }

        /** El poste/viga del estilo con su eje (si es un tronco). */
        BlockState frame(Direction.Axis axis) {
            BlockState s = st.frame.defaultBlockState();
            return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, axis) : s;
        }

        void log(int x, int y, int z, Direction.Axis axis) {
            set(x, y, z, frame(axis));
        }

        /** Una viga recta del estilo de (x0, y0, z0) a (x1, y1, z1) (uno de los dos lados tiene que ser igual). */
        void beam(int x0, int y0, int z0, int x1, int y1, int z1) {
            Direction.Axis axis = x0 != x1 ? Direction.Axis.X : z0 != z1 ? Direction.Axis.Z : Direction.Axis.Y;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) set(x, y, z, frame(axis));
        }

        // -------------------------------------------------------------- partes

        void floor(int x0, int z0, int x1, int z1, int y, Block b) {
            fill(x0, y, z0, x1, y, z1, b);
        }

        void walls(int x0, int z0, int x1, int z1, int y0, int h) {
            walls(x0, z0, x1, z1, y0, h, st.found);
        }

        void walls(int x0, int z0, int x1, int z1, int y0, int h, Block base) {
            walls(x0, z0, x1, z1, y0, h, base, st.wall, st.frame, Blocks.GLASS_PANE);
        }

        /**
         * Paredes de la caja x0..x1 × z0..z1: en y0 el zócalo {@code base} (null = una viga corrida, para un piso de
         * arriba) y el piso de adentro de tablones; de y0+1 a y0+h el relleno {@code wall} con esquinas y postes cada 4
         * de {@code post}, una viga arriba de todo y ventanas de {@code glass} entre los postes. Adentro queda vacío.
         */
        void walls(int x0, int z0, int x1, int z1, int y0, int h, Block base, Block wall, Block post, Block glass) {
            fill(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y0 + h, z1 - 1, Blocks.AIR.defaultBlockState());
            if (base != null) floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, y0, st.planks);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
                if (!ex && !ez) continue;
                boolean corner = ex && ez;
                Direction.Axis along = ez ? Direction.Axis.X : Direction.Axis.Z;
                int i = ez ? x - x0 : z - z0, len = ez ? x1 - x0 : z1 - z0;
                set(x, y0, z, base != null ? base.defaultBlockState() : frame(along));
                for (int dy = 1; dy <= h; dy++) {
                    BlockState s;
                    boolean windowRow = dy == 2 || (dy == 3 && h >= 5);
                    if (corner || i % 4 == 0) s = withAxis(post, Direction.Axis.Y);
                    else if (dy == h) s = withAxis(post, along);
                    else if (windowRow && i % 4 == 2 && i >= 2 && i <= len - 2) s = glass.defaultBlockState();
                    else s = wall.defaultBlockState();
                    set(x, y0 + dy, z, s);
                }
            }
        }

        private static BlockState withAxis(Block b, Direction.Axis axis) {
            BlockState s = b.defaultBlockState();
            return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, axis) : s;
        }

        /** El techo del estilo sobre la caja, con la pared terminando en {@code y}: a dos aguas o plano. */
        void roof(int x0, int z0, int x1, int z1, int y, boolean ridgeAlongZ) {
            if (st.flat) flatRoof(x0, z0, x1, z1, y + 1);
            else gable(x0, z0, x1, z1, y, ridgeAlongZ);
        }

        /**
         * Techo a dos aguas con alero de 1 alrededor; la cumbrera corre a lo largo de z ({@code alongZ}) o de x. Las
         * puntas se cierran con la pared del estilo.
         */
        void gable(int x0, int z0, int x1, int z1, int y, boolean alongZ) {
            int a = (alongZ ? x0 : z0) - 1, b = (alongZ ? x1 : z1) + 1;
            int r0 = (alongZ ? z0 : x0) - 1, r1 = (alongZ ? z1 : x1) + 1;
            int end0 = alongZ ? z0 : x0, end1 = alongZ ? z1 : x1;
            Direction up = alongZ ? Direction.EAST : Direction.SOUTH;
            for (int k = 0; ; k++) {
                int lo = a + k, hi = b - k, yy = y + k;
                if (lo > hi) break;
                for (int r = r0; r <= r1; r++) {
                    if (lo == hi) {
                        put(alongZ, lo, yy, r, slab(st.roofSlab, SlabType.BOTTOM));
                    } else {
                        put(alongZ, lo, yy, r, stairs(st.roofStairs, up, Half.BOTTOM));
                        put(alongZ, hi, yy, r, stairs(st.roofStairs, up.getOpposite(), Half.BOTTOM));
                    }
                }
                if (k >= 1) for (int q = lo + 1; q <= hi - 1; q++) {
                    put(alongZ, q, yy, end0, st.wall.defaultBlockState());
                    put(alongZ, q, yy, end1, st.wall.defaultBlockState());
                }
                if (lo >= hi - 1) break;
            }
        }

        /** (q, r) del techo: q a lo ancho de la pendiente, r a lo largo de la cumbrera. */
        private void put(boolean alongZ, int q, int y, int r, BlockState s) {
            if (alongZ) set(q, y, r, s);
            else set(r, y, q, s);
        }

        /** Techo plano (losa del estilo) con pretil de losas y almenas en las esquinas. */
        void flatRoof(int x0, int z0, int x1, int z1, int y) {
            fill(x0, y, z0, x1, y, z1, st.roofBlock);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
                if (!ex && !ez) continue;
                set(x, y + 1, z, ex && ez ? st.roofBlock.defaultBlockState() : slab(st.roofSlab, SlabType.BOTTOM));
            }
        }

        void door(int x, int y, int z, Direction facing) {
            BlockState d = st.door.defaultBlockState().setValue(DoorBlock.FACING, facing);
            late(x, y, z, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            late(x, y + 1, z, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        /** Cama con los pies en (x, z) y la cabecera hacia {@code facing}. */
        void bed(int x, int y, int z, Direction facing) {
            BlockState foot = st.bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
            late(x, y, z, foot);
            late(x + facing.getStepX(), y, z + facing.getStepZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
        }

        /** Mesa (cerco + placa) en (x, z) del piso 0 con sillas a los lados que se piden. */
        void table(int x, int z, Direction... chairs) {
            set(x, 1, z, st.fence.defaultBlockState());
            late(x, 2, z, st.plate.defaultBlockState());
            for (Direction d : chairs) set(x + d.getStepX(), 1, z + d.getStepZ(), stairs(st.seat, d, Half.BOTTOM));
        }

        void barrel(int x, int y, int z) {
            set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }

        void chest(int x, int y, int z, Direction facing) {
            set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        }

        void hanging(int x, int y, int z) {
            late(x, y, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }

        void lantern(int x, int y, int z) {
            late(x, y, z, Blocks.LANTERN.defaultBlockState());
        }

        /** Un cerco con un farol arriba. */
        void post(int x, int z) {
            set(x, 1, z, st.fence.defaultBlockState());
            lantern(x, 2, z);
        }

        /** Estandarte del color de la aldea colgado de la pared de atrás de (x, y, z), mirando a {@code facing}. */
        void banner(int x, int y, int z, Direction facing) {
            late(x, y, z, st.banner.defaultBlockState().setValue(WallBannerBlock.FACING, facing));
        }

        void path(int x, int z) {
            set(x, 0, z, Blocks.DIRT_PATH.defaultBlockState());
        }

        /** Maceta con una flor que depende del lugar (siempre la misma para el mismo terreno). */
        void pot(int x, int y, int z) {
            Block[] flowers = {Blocks.POTTED_POPPY, Blocks.POTTED_DANDELION, Blocks.POTTED_BLUE_ORCHID, Blocks.POTTED_AZURE_BLUET,
                    Blocks.POTTED_RED_TULIP, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_FERN};
            BlockPos p = at(x, y, z);
            late(x, y, z, flowers[Math.floorMod(p.getX() * 31 + p.getZ() * 17, flowers.length)].defaultBlockState());
        }

        /** Muñeco de práctica: cerco, heno y calabaza tallada mirando a {@code facing}. */
        void dummy(int x, int z, Direction facing) {
            set(x, 1, z, st.fence.defaultBlockState());
            set(x, 2, z, Blocks.HAY_BLOCK.defaultBlockState());
            late(x, 3, z, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, facing));
        }

        /** Chimenea del zócalo del estilo en la columna (x, z), de y0 a y1, con una fogata arriba que echa humo. */
        void chimney(int x, int z, int y0, int y1) {
            fill(x, y0, z, x, y1, z, st.found);
            late(x, y1 + 1, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        }

        /**
         * Hogar en la pared de atrás (x, z = la pared) con su chimenea por afuera (en {@code outZ}) hasta {@code top}: la
         * fogata adentro de un marco del zócalo.
         */
        void hearth(int x, int z, int outZ, int top) {
            fill(x - 1, 1, z, x + 1, 2, z, st.found);
            set(x, 3, z, st.found.defaultBlockState());
            late(x, 1, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.FACING, Direction.NORTH));
            chimney(x, outZ, 0, top);
        }

        /**
         * Puesto de mercado de 3×3 desde (x0, z0): postes, toldo a rayas (color del estilo y blanco), mostrador del lado
         * de la plaza, barril con mercadería.
         */
        void stall(int x0, int z0, Block good, boolean back) {
            for (int[] p : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) fill(x0 + p[0], 1, z0 + p[1], x0 + p[0], 2, z0 + p[1], st.fence);
            for (int dx = 0; dx <= 2; dx++) for (int dz = 0; dz <= 2; dz++) {
                set(x0 + dx, 3, z0 + dz, (dx % 2 == 0 ? st.wool : Blocks.WHITE_WOOL).defaultBlockState());
            }
            int front = back ? z0 : z0 + 2;
            set(x0 + 1, 1, front, slab(slabOf(st.planks), SlabType.TOP));
            barrel(x0 + 1, 1, z0 + 1);
            set(x0 + 1, 2, z0 + 1, good.defaultBlockState());
            hanging(x0 + 1, 2, front == z0 ? z0 + 2 : z0);
        }
    }
}
