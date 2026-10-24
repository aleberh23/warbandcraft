package com.ale.warbandcraft.castle;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.upgrade.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Castillos dibujados por código (pedido de Ale: cada facción con el suyo, nunca dos iguales). Un terreno cuadrado
 * de {@link #pickSize} con el portón al sur (sin girar; el giro lo pone al azar), la torre del homenaje al norte y
 * el patio en el medio, donde aparece el capitán. Siempre: muros escalables (≤ 12, para las escaleras de asedio), un
 * portón abierto (los asedios entran por ahí o trepan), camino de ronda arriba, barriles de armería en las barracas y
 * el cofre del tesoro en la torre del homenaje ({@code CastleManager.fillLoot} les pone el botín).
 *
 * Coordenadas locales: x de oeste a este, z de norte (atrás) a sur (portón); y = 0 es el piso, se camina en y = 1.
 */
public final class CastleDesigns {
    /** Lados posibles de un castillo dibujado (con el foso adentro). */
    public static int SIZE_SMALL = 37, SIZE_MEDIUM = 41, SIZE_LARGE = 45;
    /** Alto de la caja que se allana. */
    public static int HEIGHT = 26;
    /** Chance (%) de que una fortaleza de Valdren tenga foso. */
    public static int FORTRESS_MOAT_PERCENT = 50;
    /** Chance (%) de que una fortaleza de Valdren tenga techos (torres en punta, torre del homenaje a dos aguas) en vez de almenas. */
    public static int FORTRESS_ROOFS_PERCENT = 50;

    private CastleDesigns() {}

    /** Un lado al azar (impar, así el patio tiene medio). */
    public static int pickSize(RandomSource r) {
        int[] sizes = {SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE};
        return sizes[r.nextInt(sizes.length)] | 1;
    }

    /** Dibuja y pone el castillo {@code kind} en el terreno ya allanado de esquina {@code origin}; devuelve su forma. */
    public static CastleShape build(ServerLevel level, BlockPos origin, int size, CastleKind kind, Rotation rot, Faction faction,
                                    RandomSource r) {
        Style st = Style.ofFaction(faction.id());
        Draft d = switch (kind) {
            case LONGFORT -> new LongFort(origin, rot, size, faction, st, r);
            case KASBAH -> new Kasbah(origin, rot, size, faction, st, r);
            case SWAMPFORT -> new SwampFort(origin, rot, size, faction, st, r);
            default -> new Fortress(origin, rot, size, faction, st, r);
        };
        d.draw();
        d.cv.place(level);
        return d.shape(kind);
    }

    // ================================================================== piezas comunes

    /** Cómo es la pared de una torre o casa en (x, y, z); {@code corner} = esquina o poste. */
    interface Shell {
        BlockState at(int x, int y, int z, boolean corner);
    }

    /** Arriba de una torre: almenas, almenas escalonadas (losa arriba), tejadito de madera o techo en punta. */
    enum Top { MERLONS, STEPPED, WOODEN, CONE }

    abstract static class Draft {
        final CastleCanvas cv;
        final Style st;
        final RandomSource r;
        final ItemStack banner;
        final int S, c;
        /** Borde de afuera del muro (desde el borde del terreno), el de adentro es a + 1; b = el del otro lado. */
        int a, b, H;
        boolean moat;

        Draft(BlockPos origin, Rotation rot, int size, Faction faction, Style st, RandomSource r) {
            this.cv = new CastleCanvas(origin, rot, size);
            this.st = st;
            this.r = r;
            this.banner = faction.banner();
            this.S = size;
            this.c = size / 2;
        }

        /** Con foso: borde, foso de 2 y un bloque de orilla antes del muro. */
        void init(boolean moat, int wallHeight) {
            this.moat = moat;
            this.H = wallHeight;
            this.a = moat ? 4 : 1;
            this.b = S - 1 - a;
        }

        abstract void draw();

        /** El piso en (x, z): {@code yard} = adentro de los muros. */
        abstract BlockState ground(int x, int z, boolean yard);

        /** El muro en (x, y, z); {@code outer} = la fila de afuera. */
        abstract BlockState wall(int x, int y, int z, boolean outer);

        BlockState merlon(int x, int z) {
            return wall(x, H + 1, z, true);
        }

        /** Arriba de cada almena (null = nada). */
        BlockState merlonCap() {
            return null;
        }

        CastleShape shape(CastleKind kind) {
            int half = c - a;
            int radius = (int) Math.ceil((half + 1) * 1.415);
            BlockPos o = cv.origin;
            return new CastleShape(kind, cv.rot, o.getX() + a + 2, o.getZ() + a + 2, o.getX() + b - 2, o.getZ() + b - 2, o.getY() + 1,
                    radius, Math.max(4, half - 4), radius + 3, half - 2, Math.max(4, half / 2), c + 5, c - 1, c - 1, HEIGHT - 2);
        }

        // -------------------------------------------------------------- ayudas

        int edge(int x, int z) {
            return Math.min(Math.min(x, z), Math.min(S - 1 - x, S - 1 - z));
        }

        boolean wallCell(int x, int z) {
            int e = edge(x, z);
            return e == a || e == a + 1;
        }

        boolean chance(int percent) {
            return r.nextInt(100) < percent;
        }

        static BlockState stairs(Block b, Direction facing, Half half) {
            return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
        }

        static BlockState slab(Block b) {
            return b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }

        static BlockState log(Block b) {
            BlockState s = b.defaultBlockState();
            return s.hasProperty(BlockStateProperties.AXIS) ? s.setValue(BlockStateProperties.AXIS, Direction.Axis.Y) : s;
        }

        static BlockState leaves(Block b) {
            return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        }

        void ladder(int x, int z, Direction facing, int y0, int y1) {
            BlockState l = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
            for (int y = y0; y <= y1; y++) cv.late(x, y, z, l);
        }

        void hanging(int x, int y, int z) {
            cv.late(x, y, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }

        /** Cerco con farol arriba. */
        void lampPost(int x, int z) {
            cv.set(x, 1, z, st.fence);
            cv.late(x, 2, z, Blocks.LANTERN.defaultBlockState());
        }

        void barrel(int x, int y, int z) {
            cv.set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        }

        void chest(int x, int y, int z, Direction facing) {
            cv.set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
        }

        /** Cama con los pies en (x, z) y la cabecera hacia {@code facing}. */
        void bed(int x, int y, int z, Direction facing) {
            BlockState foot = st.bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
            cv.late(x, y, z, foot);
            cv.late(x + facing.getStepX(), y, z + facing.getStepZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
        }

        /** Mesa: cerco + placa. */
        void table(int x, int y, int z) {
            cv.set(x, y, z, st.fence);
            cv.late(x, y + 1, z, st.plate.defaultBlockState());
        }

        /** Brasero: muro de piedra con una fogata arriba. */
        void brazier(int x, int z) {
            cv.set(x, 1, z, Blocks.COBBLESTONE_WALL);
            cv.late(x, 2, z, Blocks.CAMPFIRE.defaultBlockState());
        }

        // -------------------------------------------------------------- piso, foso, muros

        void groundAll() {
            for (int x = 0; x < S; x++) for (int z = 0; z < S; z++) {
                cv.set(x, -1, z, Blocks.DIRT);
                cv.set(x, 0, z, ground(x, z, edge(x, z) >= a + 2));
            }
        }

        void moatRing() {
            if (!moat) return;
            for (int x = 0; x < S; x++) for (int z = 0; z < S; z++) {
                int e = edge(x, z);
                if (e != 1 && e != 2) continue;
                cv.set(x, -2, z, st.found);
                cv.set(x, -1, z, Blocks.WATER);
                cv.set(x, 0, z, Blocks.WATER);
            }
        }

        /** El anillo de muros: 2 de grueso, alto H, almenas en la fila de afuera, cimientos de 2 abajo del piso. */
        void walls() {
            BlockState cap = merlonCap();
            for (int x = 0; x < S; x++) for (int z = 0; z < S; z++) {
                if (!wallCell(x, z)) continue;
                boolean outer = edge(x, z) == a;
                cv.fill(x, -2, z, x, 0, z, st.found);
                for (int y = 1; y <= H; y++) cv.set(x, y, z, wall(x, y, z, outer));
                if (outer && (x + z) % 2 == 0) {
                    cv.set(x, H + 1, z, merlon(x, z));
                    if (cap != null) cv.set(x, H + 2, z, cap);
                }
            }
        }

        /** Escaleras de mano para subir al camino de ronda, una por lado (no en el medio: ahí van otras cosas). */
        void wallLadders() {
            ladder(a + 2, c + 7, Direction.EAST, 1, H);
            ladder(b - 2, c + 7, Direction.WEST, 1, H);
            ladder(c + 7, a + 2, Direction.SOUTH, 1, H);
            ladder(c - 7, b - 2, Direction.NORTH, 1, H);
        }

        /** Camino del portón a la torre del homenaje (3 de ancho), y afuera hasta el borde. */
        void road(BlockState path) {
            for (int z = c - 3; z < S; z++) for (int x = c - 1; x <= c + 1; x++) {
                if (cv.get(x, 0, z) != null && cv.get(x, 0, z).is(Blocks.WATER)) continue;   // el puente lo pone el portón
                cv.set(x, 0, z, path);
            }
        }

        // -------------------------------------------------------------- torres

        /** Las cuatro esquinas. */
        void cornerTowers(int halfOrRadius, int h, Shell shell, BlockState floor, Top top, boolean round) {
            int[] at = {a + 2, b - 2};
            for (int x : at) for (int z : at) {
                if (round) roundTower(x, z, halfOrRadius, h, shell, floor, top);
                else squareTower(x, z, halfOrRadius, h, shell, floor, top);
            }
        }

        /**
         * Torre redonda de radio {@code rr} con centro (cx, cz): hueca, piso a la altura del camino de ronda (y = H, con
         * entrada desde los muros), arriba en {@code h}; puerta al patio y escalera de mano adentro.
         */
        void roundTower(int cx, int cz, int rr, int h, Shell shell, BlockState floor, Top top) {
            double out = (rr + 0.5) * (rr + 0.5), in = (rr - 0.5) * (rr - 0.5);
            for (int x = cx - rr; x <= cx + rr; x++) for (int z = cz - rr; z <= cz + rr; z++) {
                int dd = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (dd > out) continue;
                towerColumn(x, z, dd > in, h, shell, floor, top, false);
            }
            if (top == Top.CONE) {
                for (int k = 0; k <= rr; k++) {
                    double rad = (rr + 0.5 - k) * (rr + 0.5 - k);
                    for (int x = cx - rr; x <= cx + rr; x++) for (int z = cz - rr; z <= cz + rr; z++) {
                        if ((x - cx) * (x - cx) + (z - cz) * (z - cz) <= rad) cv.set(x, h + 1 + k, z, st.roofBlock);
                    }
                }
            }
            towerAccess(cx, cz, rr, h, top);
        }

        /** Torre cuadrada de medio lado {@code half}; lo mismo que la redonda. */
        void squareTower(int cx, int cz, int half, int h, Shell shell, BlockState floor, Top top) {
            for (int x = cx - half; x <= cx + half; x++) for (int z = cz - half; z <= cz + half; z++) {
                boolean rim = Math.abs(x - cx) == half || Math.abs(z - cz) == half;
                boolean corner = Math.abs(x - cx) == half && Math.abs(z - cz) == half;
                towerColumn(x, z, rim, h, shell, floor, top, corner);
            }
            if (top == Top.WOODEN) {
                for (int dx : new int[]{-half, half}) for (int dz : new int[]{-half, half}) {
                    cv.fill(cx + dx, h + 1, cz + dz, cx + dx, h + 3, cz + dz, log(st.frame));
                }
                for (int x = cx - half - 1; x <= cx + half + 1; x++) for (int z = cz - half - 1; z <= cz + half + 1; z++) {
                    cv.set(x, h + 4, z, slab(st.roofSlab));
                }
                for (int x = cx - half + 1; x <= cx + half - 1; x++) for (int z = cz - half + 1; z <= cz + half - 1; z++) {
                    cv.set(x, h + 5, z, slab(st.roofSlab));
                }
            } else if (top == Top.CONE) {
                for (int k = 0; k <= half; k++) {
                    cv.fill(cx - half + k, h + 1 + k, cz - half + k, cx + half - k, h + 1 + k, cz + half - k, st.roofBlock.defaultBlockState());
                }
            }
            towerAccess(cx, cz, half, h, top);
        }

        private void towerColumn(int x, int z, boolean rim, int h, Shell shell, BlockState floor, Top top, boolean corner) {
            cv.fill(x, -2, z, x, -1, z, st.found);
            cv.set(x, 0, z, rim ? st.found.defaultBlockState() : floor);
            for (int y = 1; y < h; y++) {
                if (rim) cv.set(x, y, z, shell.at(x, y, z, corner));
                else cv.air(x, y, z);
            }
            cv.set(x, h, z, rim ? shell.at(x, h, z, corner) : floor);
            if (!rim && H < h - 1) cv.set(x, H, z, floor);
            if (rim && wallCell(x, z) && H + 2 < h) {   // el camino de ronda entra a la torre
                cv.air(x, H + 1, z);
                cv.air(x, H + 2, z);
            }
            if (rim && (top == Top.MERLONS || top == Top.STEPPED) && (x + z) % 2 == 0) {
                cv.set(x, h + 1, z, shell.at(x, h + 1, z, corner));
                if (top == Top.STEPPED) cv.set(x, h + 2, z, slab(st.roofSlab));
            } else if (rim && top == Top.WOODEN) {
                cv.set(x, h + 1, z, st.fence);
            }
        }

        /** Puerta hacia el patio y escalera de mano del lado de afuera (sube hasta arriba salvo con techo en punta). */
        private void towerAccess(int cx, int cz, int half, int h, Top top) {
            int dx = Integer.signum(c - cx), dz = Integer.signum(c - cz);
            cv.air(cx + dx * half, 1, cz);
            cv.air(cx + dx * half, 2, cz);
            int lz = cz - dz * (half - 1);
            ladder(cx, lz, dz > 0 ? Direction.SOUTH : Direction.NORTH, 1, top == Top.CONE ? H : h);
        }

        // -------------------------------------------------------------- portón

        /**
         * Portón al sur: hueco de 3 × 4 (con {@code bars}, rastrillo arriba: queda de 3 × 3; con {@code arch}, arco), dos
         * torrecitas a los costados con parapeto a la altura del camino de ronda, estandarte arriba y puente si hay foso.
         */
        void gate(BlockState bars, boolean arch, Shell tower) {
            for (int z = b - 1; z <= b; z++) for (int x = c - 1; x <= c + 1; x++) for (int y = 1; y <= 4; y++) cv.air(x, y, z);
            for (int side : new int[]{-1, 1}) {
                int x0 = side < 0 ? c - 4 : c + 2, x1 = x0 + 2;
                for (int x = x0; x <= x1; x++) for (int z = b - 3; z <= b + 1; z++) {
                    boolean corner = (x == x0 || x == x1) && (z == b - 3 || z == b + 1);
                    cv.fill(x, -2, z, x, 0, z, st.found);
                    for (int y = 1; y <= H; y++) cv.set(x, y, z, tower.at(x, y, z, corner));
                    boolean outerRim = z == b + 1 || (side < 0 ? x == x0 : x == x1);
                    if (outerRim) {
                        cv.set(x, H + 1, z, tower.at(x, H + 1, z, corner));
                        if ((x + z) % 2 == 0) cv.set(x, H + 2, z, tower.at(x, H + 2, z, corner));
                    }
                }
            }
            if (bars != null) for (int x = c - 1; x <= c + 1; x++) cv.set(x, 4, b, bars);
            if (arch) {
                Block stairBlock = st.roofStairs;
                for (int z = b - 1; z <= b; z++) {
                    cv.set(c - 1, 4, z, stairs(stairBlock, Direction.WEST, Half.TOP));
                    cv.set(c + 1, 4, z, stairs(stairBlock, Direction.EAST, Half.TOP));
                }
            }
            cv.banner(c, H - 1, b + 1, Direction.SOUTH, banner);
            cv.banner(c, H - 1, b - 2, Direction.NORTH, banner);
            if (moat) for (int x = c - 1; x <= c + 1; x++) for (int z = S - 3; z <= S - 2; z++) cv.set(x, 0, z, st.planks);
        }

        // -------------------------------------------------------------- edificios

        /**
         * Torre del homenaje de piedra en x0..x1 × z0..z1: {@code floors} pisos de {@code fh}, entrada al sur sin
         * puerta (los asedios tienen que poder entrar), escalera de mano, armería abajo, camas arriba y el cofre del
         * tesoro en el último piso. Arriba: almenas (se puede subir) o techo a dos aguas.
         */
        void keep(int x0, int z0, int x1, int z1, int floors, int fh, Shell shell, BlockState floor, Top top) {
            int topY = floors * fh;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
                boolean rim = ex || ez, corner = ex && ez;
                cv.fill(x, -2, z, x, -1, z, st.found);
                cv.set(x, 0, z, rim ? st.found.defaultBlockState() : floor);
                for (int y = 1; y <= topY; y++) {
                    if (rim) {
                        boolean window = !corner && y % fh == 2 && (ex ? (z - z0) : (x - x0)) % 3 == 1 && z != z1;
                        cv.set(x, y, z, window ? st.glass.defaultBlockState() : shell.at(x, y, z, corner));
                    } else {
                        cv.set(x, y, z, y % fh == 0 ? floor : Blocks.AIR.defaultBlockState());
                    }
                }
                if (rim && (top == Top.MERLONS || top == Top.STEPPED) && (x + z) % 2 == 0) {
                    cv.set(x, topY + 1, z, shell.at(x, topY + 1, z, corner));
                    if (top == Top.STEPPED) cv.set(x, topY + 2, z, slab(st.roofSlab));
                }
            }
            if (top == Top.CONE || top == Top.WOODEN) gable(x0, z0, x1, z1, topY, x1 - x0 > z1 - z0, st.wall);
            int mid = (x0 + x1) / 2;
            for (int y = 1; y <= 3; y++) cv.air(mid, y, z1);
            ladder(x0 + 1, z0 + 1, Direction.SOUTH, 1, top == Top.MERLONS || top == Top.STEPPED ? topY : (floors - 1) * fh);
            barrel(x1 - 1, 1, z0 + 1);
            barrel(x1 - 1, 1, z0 + 2);
            barrel(x1 - 1, 2, z0 + 1);
            table(mid, 1, z0 + 2);
            for (int k = 0; k < floors; k++) hanging(mid, (k + 1) * fh - 1, (z0 + z1) / 2);
            if (floors >= 2) {
                bed(x1 - 2, fh + 1, z1 - 2, Direction.NORTH);
                bed(x1 - 3, fh + 1, z1 - 2, Direction.NORTH);
            }
            chest(x1 - 1, (floors - 1) * fh + 1, z0 + 1, Direction.SOUTH);
            cv.banner(mid, Math.min(topY - 1, fh + 2), z1 + 1, Direction.SOUTH, banner);
            cv.banner(mid, (floors - 1) * fh + 3, z0 + 1, Direction.SOUTH, banner);
        }

        /** Techo a dos aguas con alero de 1; la cumbrera a lo largo de z ({@code alongZ}) o de x; puntas cerradas con {@code end}. */
        void gable(int x0, int z0, int x1, int z1, int y, boolean alongX, Block end) {
            boolean alongZ = !alongX;
            int lo0 = (alongZ ? x0 : z0) - 1, hi0 = (alongZ ? x1 : z1) + 1;
            int r0 = (alongZ ? z0 : x0) - 1, r1 = (alongZ ? z1 : x1) + 1;
            int e0 = alongZ ? z0 : x0, e1 = alongZ ? z1 : x1;
            Direction up = alongZ ? Direction.EAST : Direction.SOUTH;
            for (int k = 0; ; k++) {
                int lo = lo0 + k, hi = hi0 - k, yy = y + k;
                if (lo > hi) break;
                for (int rr = r0; rr <= r1; rr++) {
                    if (lo == hi) {
                        roofPut(alongZ, lo, yy, rr, slab(st.roofSlab));
                    } else {
                        roofPut(alongZ, lo, yy, rr, stairs(st.roofStairs, up, Half.BOTTOM));
                        roofPut(alongZ, hi, yy, rr, stairs(st.roofStairs, up.getOpposite(), Half.BOTTOM));
                    }
                }
                if (k >= 1) for (int q = lo + 1; q <= hi - 1; q++) {
                    roofPut(alongZ, q, yy, e0, end.defaultBlockState());
                    roofPut(alongZ, q, yy, e1, end.defaultBlockState());
                }
                if (lo >= hi - 1) break;
            }
        }

        private void roofPut(boolean alongZ, int q, int y, int rr, BlockState s) {
            if (alongZ) cv.set(q, y, rr, s);
            else cv.set(rr, y, q, s);
        }

        /** Barracas contra los muros del oeste y del este: techito, camas y barriles de armería. */
        void leanTos(BlockState roof) {
            for (int side : new int[]{-1, 1}) {
                int xw = side < 0 ? a + 2 : b - 2, xo = xw - side * 2;
                int lo = Math.min(xw, xo), hi = Math.max(xw, xo);
                cv.fill(lo, 4, c - 2, hi, 4, c + 4, roof);
                for (int z : new int[]{c - 2, c + 1, c + 4}) cv.fill(xo, 1, z, xo, 3, z, st.fence.defaultBlockState());
                Direction head = side < 0 ? Direction.WEST : Direction.EAST;
                bed(xw - side, 1, c - 1, head);
                bed(xw - side, 1, c + 1, head);
                barrel(xw, 1, c + 3);
                barrel(xw, 1, c + 4);
                barrel(xw - side, 1, c + 4);
                barrel(xw, 2, c + 4);
                hanging(xw - side, 3, c + 2);
            }
        }

        /** Pozo de 3 × 3 con centro (x, z): agua hasta el piso, brocal, y techito si {@code roof}. */
        void well(int x, int z, BlockState rim, boolean roof) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                cv.fill(x + dx, -3, z + dz, x + dx, -1, z + dz, st.found);
                if (dx == 0 && dz == 0) continue;
                cv.set(x + dx, 0, z + dz, st.found);
                cv.set(x + dx, 1, z + dz, rim);
            }
            cv.set(x, -2, z, Blocks.WATER);
            cv.set(x, -1, z, Blocks.WATER);
            cv.set(x, 0, z, Blocks.WATER);
            if (roof) {
                for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) cv.fill(x + dx, 2, z + dz, x + dx, 3, z + dz, st.fence.defaultBlockState());
                cv.fill(x - 1, 4, z - 1, x + 1, 4, z + 1, slab(st.roofSlab));
            }
        }
    }

    // ================================================================== Reino de Valdren

    /** Ladrillo de piedra con musgo y grietas, torres redondas, torre del homenaje de 2-3 pisos, rastrillo, a veces foso. */
    static final class Fortress extends Draft {
        Fortress(BlockPos o, Rotation rot, int size, Faction f, Style st, RandomSource r) {
            super(o, rot, size, f, st, r);
        }

        @Override
        void draw() {
            init(chance(FORTRESS_MOAT_PERCENT), 8);
            boolean roofs = chance(FORTRESS_ROOFS_PERCENT);
            groundAll();
            moatRing();
            walls();
            Shell stone = (x, y, z, corner) -> weathered();
            cornerTowers(3, H + 4, stone, st.planks.defaultBlockState(), roofs ? Top.CONE : Top.MERLONS, true);
            int kw = S >= SIZE_MEDIUM && r.nextBoolean() ? 5 : 4;
            keep(c - kw, a + 3, c + kw, c - 4, S >= SIZE_MEDIUM ? 3 : 2, 4, stone, st.planks.defaultBlockState(),
                    roofs ? Top.CONE : Top.MERLONS);
            road(Blocks.COBBLESTONE.defaultBlockState());
            gate(Blocks.IRON_BARS.defaultBlockState(), false, stone);
            leanTos(slab(st.roofSlab));
            wallLadders();
            well(c + 5, c + 5, Blocks.STONE_BRICK_WALL.defaultBlockState(), true);
            lampPost(c - 3, c + 3);
            lampPost(c + 3, c + 3);
            lampPost(c - 3, b - 4);
            lampPost(c + 3, b - 4);
        }

        private BlockState weathered() {
            int roll = r.nextInt(100);
            return (roll < 8 ? Blocks.MOSSY_STONE_BRICKS : roll < 14 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
        }

        @Override
        BlockState ground(int x, int z, boolean yard) {
            int roll = r.nextInt(100);
            if (!yard) return Blocks.GRASS_BLOCK.defaultBlockState();
            return (roll < 65 ? Blocks.GRASS_BLOCK : roll < 88 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState();
        }

        @Override
        BlockState wall(int x, int y, int z, boolean outer) {
            return outer ? weathered() : Blocks.STONE_BRICKS.defaultBlockState();
        }
    }

    // ================================================================== Clanes del Norte

    /** Empalizada de troncos sobre piedra, torres de vigía de madera, gran salón con fogatas. */
    static final class LongFort extends Draft {
        LongFort(BlockPos o, Rotation rot, int size, Faction f, Style st, RandomSource r) {
            super(o, rot, size, f, st, r);
        }

        @Override
        void draw() {
            init(false, 7);
            groundAll();
            walls();
            Shell wood = (x, y, z, corner) -> corner ? log(st.frame) : y <= 2 ? Blocks.COBBLESTONE.defaultBlockState() : st.planks.defaultBlockState();
            cornerTowers(2, H + 4, wood, st.planks.defaultBlockState(), Top.WOODEN, false);
            hall(c - 4, a + 3, c + 4, c - 4);
            road(Blocks.DIRT_PATH.defaultBlockState());
            gate(null, false, (x, y, z, corner) -> corner || y > 2 ? log(st.frame) : Blocks.COBBLESTONE.defaultBlockState());
            leanTos(slab(st.roofSlab));
            wallLadders();
            brazier(c - 4, c + 3);
            brazier(c + 4, c + 3);
            brazier(c - 4, b - 4);
            brazier(c + 4, b - 4);
            cv.set(a + 3, 1, c + 6, Blocks.HAY_BLOCK);
            cv.set(a + 3, 1, c + 5, Blocks.HAY_BLOCK);
            cv.set(b - 3, 1, c + 6, Blocks.HAY_BLOCK);
        }

        /** El gran salón (casa larga): zócalo de piedra, postes de troncos, techo a dos aguas, fogatas en el medio. */
        private void hall(int x0, int z0, int x1, int z1) {
            int top = 4;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
                cv.fill(x, -2, z, x, -1, z, st.found);
                if (!ex && !ez) {
                    cv.set(x, 0, z, st.planks);
                    for (int y = 1; y <= top; y++) cv.air(x, y, z);
                    continue;
                }
                cv.set(x, 0, z, st.found);
                boolean post = (ex && ez) || (ez ? (x - x0) % 3 == 0 : (z - z0) % 3 == 0);
                for (int y = 1; y <= top; y++) {
                    cv.set(x, y, z, post ? log(st.frame) : y == 1 ? Blocks.COBBLESTONE.defaultBlockState() : st.planks.defaultBlockState());
                }
            }
            gable(x0, z0, x1, z1, top, false, st.planks);
            int mid = (x0 + x1) / 2;
            for (int x = mid - 1; x <= mid + 1; x++) for (int y = 1; y <= 3; y++) cv.air(x, y, z1);
            for (int z = z0 + 2; z <= z1 - 3; z += 4) cv.late(mid, 1, z, Blocks.CAMPFIRE.defaultBlockState());
            for (int z = z0 + 2; z <= z1 - 2; z += 3) {
                table(x0 + 2, 1, z);
                table(x1 - 2, 1, z);
            }
            chest(mid, 1, z0 + 1, Direction.SOUTH);
            barrel(x0 + 1, 1, z1 - 1);
            barrel(x1 - 1, 1, z1 - 1);
            barrel(x0 + 1, 1, z0 + 1);
            cv.banner(mid, 3, z0 + 1, Direction.SOUTH, banner);
            cv.banner(mid, 4, z1 + 1, Direction.SOUTH, banner);
            for (int z = z0 + 2; z <= z1 - 2; z += 4) hanging(mid, top, z + 1);
        }

        @Override
        BlockState ground(int x, int z, boolean yard) {
            int roll = r.nextInt(100);
            return (roll < 30 ? Blocks.PODZOL : roll < 55 ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).defaultBlockState();
        }

        @Override
        BlockState wall(int x, int y, int z, boolean outer) {
            if (outer) return y <= 2 ? Blocks.COBBLESTONE.defaultBlockState() : log(st.frame);
            return y == H ? st.planks.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
        }

        @Override
        BlockState merlon(int x, int z) {
            return log(st.frame);
        }
    }

    // ================================================================== Sultanato de Arena

    /** Muros altos de arenisca con almenas escalonadas y una franja, torres cuadradas, arco, fuentes y palmeras. */
    static final class Kasbah extends Draft {
        Kasbah(BlockPos o, Rotation rot, int size, Faction f, Style st, RandomSource r) {
            super(o, rot, size, f, st, r);
        }

        @Override
        void draw() {
            init(false, 9);
            groundAll();
            walls();
            Shell sand = (x, y, z, corner) -> (corner ? Blocks.CUT_SANDSTONE : y == H - 2 ? Blocks.ORANGE_TERRACOTTA : Blocks.SMOOTH_SANDSTONE).defaultBlockState();
            cornerTowers(3, H + 3, sand, st.planks.defaultBlockState(), Top.STEPPED, false);
            keep(c - 5, a + 3, c + 5, c - 4, 2, 5, sand, Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Top.STEPPED);
            road(Blocks.CUT_SANDSTONE.defaultBlockState());
            gate(null, true, sand);
            leanTos(slab(st.roofSlab));
            wallLadders();
            well(c - 6, c + 4, Blocks.SANDSTONE_WALL.defaultBlockState(), false);
            well(c + 6, c + 4, Blocks.SANDSTONE_WALL.defaultBlockState(), false);
            palm(c - 6, c);
            palm(c + 6, c);
            if (S >= SIZE_MEDIUM) palm(c - 6, b - 5);
        }

        private void palm(int x, int z) {
            int h = 4 + r.nextInt(2);
            cv.set(x, 0, z, Blocks.SAND);
            cv.fill(x, 1, z, x, h, z, log(Blocks.JUNGLE_LOG));
            BlockState leaf = leaves(Blocks.JUNGLE_LEAVES);
            cv.set(x, h + 1, z, leaf);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                cv.set(x + d.getStepX(), h + 1, z + d.getStepZ(), leaf);
                cv.set(x + d.getStepX() * 2, h, z + d.getStepZ() * 2, leaf);
                cv.set(x + d.getStepX() * 3, h - 1, z + d.getStepZ() * 3, leaf);
            }
        }

        @Override
        BlockState ground(int x, int z, boolean yard) {
            if (!yard) return Blocks.SAND.defaultBlockState();
            int roll = r.nextInt(100);
            return (roll < 70 ? Blocks.SMOOTH_SANDSTONE : roll < 92 ? Blocks.CUT_SANDSTONE : Blocks.ORANGE_TERRACOTTA).defaultBlockState();
        }

        @Override
        BlockState wall(int x, int y, int z, boolean outer) {
            if (!outer) return Blocks.SANDSTONE.defaultBlockState();
            return (y == H - 2 ? Blocks.ORANGE_TERRACOTTA : Blocks.SMOOTH_SANDSTONE).defaultBlockState();
        }

        @Override
        BlockState merlonCap() {
            return slab(Blocks.SMOOTH_SANDSTONE_SLAB);
        }
    }

    // ================================================================== Liga de los Pantanos

    /** Barro y mangle, foso de agua con puente, torres con tejadito, casa fuerte sobre pilotes. */
    static final class SwampFort extends Draft {
        SwampFort(BlockPos o, Rotation rot, int size, Faction f, Style st, RandomSource r) {
            super(o, rot, size, f, st, r);
        }

        @Override
        void draw() {
            init(true, 6);
            groundAll();
            moatRing();
            walls();
            Shell mud = (x, y, z, corner) -> corner ? log(st.frame) : y <= H ? Blocks.MUD_BRICKS.defaultBlockState() : st.planks.defaultBlockState();
            cornerTowers(2, H + 4, mud, st.planks.defaultBlockState(), Top.WOODEN, false);
            stiltHouse(c - 5, a + 3, c + 5, c - 4);
            road(st.planks.defaultBlockState());
            gate(null, false, mud);
            leanTos(slab(st.roofSlab));
            wallLadders();
            lampPost(c - 3, c + 3);
            lampPost(c + 3, c + 3);
            for (int i = 0; i < 10; i++) {   // musgo suelto por el patio
                int x = a + 3 + r.nextInt(b - a - 5), z = c + 2 + r.nextInt(Math.max(1, b - c - 4));
                if (Math.abs(x - c) > 2 && cv.get(x, 1, z) == null) cv.late(x, 1, z, Blocks.MOSS_CARPET.defaultBlockState());
            }
        }

        /** Casa fuerte: pilotes de mangle de 2, piso de tablones, paredes de barro, techo a dos aguas; se sube por una escalera de mano. */
        private void stiltHouse(int x0, int z0, int x1, int z1) {
            int floor = 3, top = 7;
            int mid = (x0 + x1) / 2;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
                boolean stilt = (ex && ez) || ((x - x0) % 3 == 0 && (z - z0) % 3 == 0) || (x == mid && z == z1);
                cv.fill(x, -2, z, x, -1, z, st.found);
                if (stilt) cv.fill(x, 1, z, x, floor - 1, z, log(st.frame));
                cv.set(x, floor, z, st.planks);
                for (int y = floor + 1; y <= top; y++) {
                    if (!ex && !ez) {
                        cv.air(x, y, z);
                        continue;
                    }
                    boolean window = !(ex && ez) && y == floor + 2 && (ex ? (z - z0) : (x - x0)) % 3 == 1;
                    cv.set(x, y, z, ex && ez ? log(st.frame) : window ? st.glass.defaultBlockState() : Blocks.MUD_BRICKS.defaultBlockState());
                }
            }
            gable(x0, z0, x1, z1, top, true, Blocks.MUD_BRICKS);
            cv.air(mid, floor + 1, z1);
            cv.air(mid, floor + 2, z1);
            ladder(mid, z1 + 1, Direction.SOUTH, 1, floor);
            chest(x1 - 1, floor + 1, z0 + 1, Direction.SOUTH);
            barrel(x0 + 1, floor + 1, z0 + 1);
            barrel(x0 + 1, floor + 1, z0 + 2);
            bed(x1 - 1, floor + 1, z1 - 2, Direction.NORTH);
            table(mid, floor + 1, z0 + 2);
            hanging(mid, top, (z0 + z1) / 2);
            cv.banner(mid, floor + 3, z1 + 1, Direction.SOUTH, banner);
            cv.banner(mid, floor + 3, z0 + 1, Direction.SOUTH, banner);
        }

        @Override
        BlockState ground(int x, int z, boolean yard) {
            int roll = r.nextInt(100);
            if (!yard) return (roll < 70 ? Blocks.GRASS_BLOCK : Blocks.MUD).defaultBlockState();
            return (roll < 25 ? Blocks.MUD : roll < 50 ? Blocks.PACKED_MUD : roll < 65 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK).defaultBlockState();
        }

        @Override
        BlockState wall(int x, int y, int z, boolean outer) {
            if (outer) return (x + z) % 4 == 0 ? log(st.frame) : Blocks.MUD_BRICKS.defaultBlockState();
            return y == H ? Blocks.MUD_BRICKS.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState();
        }

        @Override
        BlockState merlon(int x, int z) {
            return Blocks.MUD_BRICK_WALL.defaultBlockState();
        }
    }
}
