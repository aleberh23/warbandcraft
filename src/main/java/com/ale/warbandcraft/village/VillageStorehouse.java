package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.init.ModBlocks;
import com.ale.warbandcraft.kit.Armament;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * El almacén de una aldea o castillo tuyo es un barril de verdad (H10, pedido de Ale): en una de las casas más
 * grandes de la aldea (la pieza "house" de mayor volumen de la estructura de vanilla) o adentro del castillo.
 * Lo que se produce con la aldea descargada espera como números ({@link VillageStock.Stock}) y entra al barril
 * cuando la aldea se carga. Si rompen el barril, se pone otro en la misma casa.
 */
public final class VillageStorehouse {
    /** Sin casa encontrada: se busca alrededor de la campana. */
    private static int FALLBACK_RADIUS = 6;
    /** Hasta cuántos bloques arriba se busca un techo (para que quede adentro). */
    private static int ROOF_SEARCH = 8;

    private VillageStorehouse() {}

    // ------------------------------------------------------------------ dónde

    /** La caja de la casa más grande de la aldea (pieza de vanilla con "houses" en el nombre), o null. */
    @Nullable
    public static BoundingBox biggestHouse(ServerLevel level, BlockPos center) {
        StructureStart start = level.structureManager().getStructureWithPieceAt(center, StructureTags.VILLAGE);
        if (start == null || !start.isValid()) return null;
        BoundingBox best = null;
        long bestVolume = 0;
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)) continue;
            if (!pool.getElement().toString().contains("/houses/")) continue;
            BoundingBox b = piece.getBoundingBox();
            long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
            if (volume > bestVolume || (volume == bestVolume && best != null && closer(b, best, center))) {
                best = b;
                bestVolume = volume;
            }
        }
        return best;
    }

    private static boolean closer(BoundingBox a, BoundingBox b, BlockPos to) {
        return a.getCenter().distSqr(to) < b.getCenter().distSqr(to);
    }

    /** Dónde se busca lugar para el barril: la casa más grande, el castillo, o alrededor de la campana. */
    public static Area areaFor(ServerLevel level, VillageData.Village v) {
        BlockPos c = v.center();
        if (v.castle()) {   // la caja del castillo (su forma: cada uno tiene su tamaño)
            com.ale.warbandcraft.castle.CastleShape s = CastleManager.shape(level, v);
            return new Area(new BoundingBox(c.getX() - s.halfX(), c.getY() - 1, c.getZ() - s.halfZ(),
                    c.getX() + s.halfX(), c.getY() + s.height(), c.getZ() + s.halfZ()), true);
        }
        BoundingBox house = biggestHouse(level, c);
        if (house != null) return new Area(house, true);
        return new Area(new BoundingBox(c.getX() - FALLBACK_RADIUS, c.getY() - 3, c.getZ() - FALLBACK_RADIUS,
                c.getX() + FALLBACK_RADIUS, c.getY() + 3, c.getZ() + FALLBACK_RADIUS), false);
    }

    /** {@code indoors}: el lugar tiene que tener techo (casa o castillo). */
    public record Area(BoundingBox box, boolean indoors) {}

    /**
     * Un lugar para el barril dentro de {@code area}: libre, con piso firme, con espacio arriba, con techo si va
     * adentro, y mejor contra una pared (no tapa el paso). El más cercano al centro de la caja.
     */
    @Nullable
    public static BlockPos findSpot(ServerLevel level, Area area) {
        BoundingBox b = area.box();
        BlockPos mid = b.getCenter();
        BlockPos best = null;
        boolean bestWall = false;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) continue;
            BlockState floor = level.getBlockState(p.below());
            if (!floor.isFaceSturdy(level, p.below(), Direction.UP) || !floor.getFluidState().isEmpty()) continue;
            if (area.indoors() && !hasRoof(level, p)) continue;
            boolean wall = againstWall(level, p);
            double d = p.distSqr(mid);
            if ((wall && !bestWall) || (wall == bestWall && d < bestDist)) {
                best = p.immutable();
                bestWall = wall;
                bestDist = d;
            }
        }
        return best;
    }

    private static boolean hasRoof(ServerLevel level, BlockPos p) {
        for (int dy = 2; dy <= ROOF_SEARCH; dy++) {
            if (!level.getBlockState(p.above(dy)).isAir()) return true;
        }
        return false;
    }

    private static boolean againstWall(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(p.relative(d)).isFaceSturdy(level, p.relative(d), d.getOpposite())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ el Campamento de la aldea (H16)

    /** El Campamento de la aldea si ya está puesto y cargado, o null. */
    @Nullable
    public static CampBlockEntity camp(ServerLevel level, VillageData.Village v) {
        BlockPos pos = VillageData.get(level).storehouse(v.center());
        if (pos == null || !level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof CampBlockEntity camp && v.center().equals(camp.getVillage()) ? camp : null;
    }

    /**
     * Devuelve el Campamento de la aldea, poniéndolo si falta (solo con la zona cargada: no se cargan chunks para
     * esto). Si había un barril de antes (H10), lo reemplaza y le pasa lo que tenía. Null si todavía no se puede.
     */
    @Nullable
    public static CampBlockEntity ensure(ServerLevel level, VillageData.Village v) {
        CampBlockEntity existing = camp(level, v);
        if (existing != null) {
            if (v.owner() != null && !v.owner().equals(existing.getOwner())) existing.setOwner(v.owner());
            return existing;
        }
        if (!level.isPositionEntityTicking(v.center())) return null;
        List<ItemStack> moved = new ArrayList<>();
        BlockPos spot = VillageData.get(level).storehouse(v.center());
        if (spot != null && level.isLoaded(spot) && level.getBlockEntity(spot) instanceof BarrelBlockEntity barrel) {
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                ItemStack s = barrel.removeItemNoUpdate(i);
                if (!s.isEmpty()) moved.add(s);
            }
        } else {
            Area area = areaFor(level, v);
            BoundingBox b = area.box();
            if (!level.hasChunksAt(b.minX(), b.minZ(), b.maxX(), b.maxZ())) return null;
            spot = findSpot(level, area);
            if (spot == null && area.indoors()) {
                // La casa no tiene lugar (o el castillo está tapado): alrededor de la campana
                spot = findSpot(level, new Area(new BoundingBox(v.center()).inflatedBy(FALLBACK_RADIUS), false));
            }
            if (spot == null) return null;
        }
        level.setBlockAndUpdate(spot, ModBlocks.CAMP.get().defaultBlockState());
        if (!(level.getBlockEntity(spot) instanceof CampBlockEntity camp)) return null;
        VillageData.get(level).setStorehouse(v.center(), spot);
        camp.setVillage(v.center());
        camp.rename(VillageCamps.nameFor(v));
        if (v.owner() != null) camp.setOwner(v.owner());
        for (ItemStack s : moved) {
            ItemStack left = camp.insert(s);
            if (!left.isEmpty()) Containers.dropItemStack(level, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, left);
        }
        return camp;
    }

    /** Pasa lo que espera como números al Campamento de la aldea (lo que no entra sigue esperando). */
    public static void flush(ServerLevel level, VillageData.Village v) {
        VillageData data = VillageData.get(level);
        VillageStock.Stock pending = data.stock(v.center());
        CampBlockEntity camp = ensure(level, v);
        if (camp == null || pending.isEmpty()) return;
        int emeralds = pending.emeralds() - insert(camp, Items.EMERALD, pending.emeralds());
        int food = pending.food() - insert(camp, Items.BREAD, pending.food());
        int armament = pending.armament() - insert(camp, Armament.item(), pending.armament());
        data.setStock(v.center(), new VillageStock.Stock(emeralds, food, armament));
    }

    /** Mete {@code count} de {@code item} en el Campamento. Devuelve cuántos entraron. */
    private static int insert(CampBlockEntity camp, Item item, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, item.getMaxStackSize());
            ItemStack rest = camp.insert(new ItemStack(item, n));
            left -= n - rest.getCount();
            if (!rest.isEmpty()) break;
        }
        return count - left;
    }

    /** Cuánto hay de los tres recursos en el Campamento de la aldea (si está cargado; cofre + vinculados). */
    public static VillageStock.Stock inBarrel(ServerLevel level, VillageData.Village v) {
        CampBlockEntity camp = camp(level, v);
        if (camp == null) return VillageStock.Stock.EMPTY;
        return new VillageStock.Stock(camp.count(Items.EMERALD), camp.count(Items.BREAD), camp.count(Armament.item()));
    }

    /** Saca los tres recursos del Campamento de la aldea (para retirar o mandar por ruta; el resto del cofre queda). */
    public static List<ItemStack> takeAll(ServerLevel level, VillageData.Village v) {
        List<ItemStack> out = new ArrayList<>();
        CampBlockEntity camp = camp(level, v);
        if (camp == null) return out;
        for (Item item : new Item[]{Items.EMERALD, Items.BREAD, Armament.item()}) {
            int n = camp.take(item, Integer.MAX_VALUE);
            while (n > 0) {
                int k = Math.min(n, item.getMaxStackSize());
                out.add(new ItemStack(item, k));
                n -= k;
            }
        }
        return out;
    }

    /** Saca {@code n} esmeraldas del Campamento de la aldea. Devuelve cuántas. */
    public static int takeEmeralds(ServerLevel level, VillageData.Village v, int n) {
        CampBlockEntity camp = camp(level, v);
        return camp == null || n <= 0 ? 0 : camp.take(Items.EMERALD, n);
    }

    /**
     * La aldea ya no es tuya (la perdiste): el Campamento desaparece. Sus esmeraldas, comida y armamento quedan como
     * almacén de la facción (H15: se lo podés volver a robar); el barracón se pierde.
     */
    public static void lose(ServerLevel level, VillageData.Village v, @Nullable BlockPos pos) {
        if (pos == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof CampBlockEntity camp)) return;
        if (!v.center().equals(camp.getVillage())) return;
        VillageStock.Stock s = new VillageStock.Stock(camp.count(Items.EMERALD), camp.count(Items.BREAD), camp.count(Armament.item()));
        VillageData data = VillageData.get(level);
        data.setStock(v.center(), data.stock(v.center()).plus(s));
        Territory.remove(level, v.center());
        BarracksData.get(level).remove(pos);
        camp.release();
        camp.clearContent();
        level.removeBlock(pos, false);
    }

    /**
     * Cada 5 s: tus aldeas cargadas tienen su Campamento y reciben lo que esperaba. Las que perdiste (vuelven a ser de
     * una facción) pierden el suyo.
     */
    public static void tick(ServerLevel level) {
        VillageData data = VillageData.get(level);
        for (VillageData.Village v : new ArrayList<>(data.all())) {
            if (v.isConquered()) Territory.ensure(level, v);   // territorio (y claim de Recruits)
            if (!level.isPositionEntityTicking(v.center())) continue;
            if (v.isConquered()) flush(level, v);
            else {
                if (Territory.get(level).has(v.center())) Territory.remove(level, v.center());
                lose(level, v, data.storehouse(v.center()));
            }
        }
    }

    /** H16: perdiste la aldea: su Campamento deja de ser tuyo ya (si está descargado, el bloque se saca al cargarse). */
    public static void forget(ServerLevel level, VillageData.Village v) {
        Territory.remove(level, v.center());
        BlockPos pos = VillageData.get(level).storehouse(v.center());
        if (pos == null || v.owner() == null) return;
        if (level.isLoaded(pos) && camp(level, v) != null) {
            lose(level, v, pos);
        } else {
            BarracksData.get(level).remove(pos);
            com.ale.warbandcraft.camp.CampRegistry.get(level).unregister(v.owner(), pos);
        }
    }

    /** H16: ¿es el Campamento de una aldea? (no se rompe ni explota). */
    public static boolean isVillageCamp(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CampBlockEntity camp && camp.getVillage() != null;
    }
}
