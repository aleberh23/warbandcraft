package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.Armament;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Items;

/**
 * Totales del almacén para la pestaña Cofre (H10): esmeraldas, comida, armamento, herreros y trabajadores.
 * Viaja al cliente con el menú (ContainerData) y se recalcula una vez por segundo.
 */
public class CampStats implements ContainerData {
    public static final int EMERALDS = 0, FOOD = 1, FOOD_DAYS = 2, ARMAMENT = 3, SMITHS = 4, WORKERS = 5, SIZE = 6;
    private static final int REFRESH_TICKS = 20;

    private final CampBlockEntity camp;
    private final int[] values = new int[SIZE];
    private long computedAt = Long.MIN_VALUE;

    public CampStats(CampBlockEntity camp) {
        this.camp = camp;
    }

    /** Cuenta todo de nuevo (también lo usa /warband almacen). */
    public static int[] compute(ServerLevel level, CampBlockEntity camp) {
        int[] v = new int[SIZE];
        v[EMERALDS] = camp.count(Items.EMERALD);
        v[FOOD] = Barracks.foodPoints(camp);
        BarracksData.Barracks b = BarracksData.get(level).at(camp.getBlockPos());
        v[FOOD_DAYS] = b == null || b.eaters() == 0 ? -1 : Barracks.foodDays(camp, b);
        v[ARMAMENT] = camp.count(Armament.item());
        v[SMITHS] = com.ale.warbandcraft.village.VillageManagement.smithsOf(level, camp.getBlockPos());   // H16: + los Herreros nuestros
        v[WORKERS] = WorkerZone.workersNear(level, camp.getBlockPos(), camp.getOwner());
        for (int i = 0; i < SIZE; i++) v[i] = Math.min(v[i], Short.MAX_VALUE);   // el menú los manda como short
        return v;
    }

    @Override
    public int get(int index) {
        if (camp.getLevel() instanceof ServerLevel level) {
            long now = level.getGameTime();
            if (now - computedAt >= REFRESH_TICKS) {
                System.arraycopy(compute(level, camp), 0, values, 0, SIZE);
                computedAt = now;
            }
        }
        return values[index];
    }

    @Override
    public void set(int index, int value) {
        values[index] = value;
    }

    @Override
    public int getCount() {
        return SIZE;
    }
}
