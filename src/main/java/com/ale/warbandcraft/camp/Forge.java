package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.kit.Armament;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Herreros (H10): aldeanos de vanilla con oficio de herrero, armero o fabricante de herramientas dentro de
 * la jurisdicción del Campamento. Cada día convierten hierro + carbón del almacén en armamento.
 * Cuenta perezosa como la comida del barracón: los días que el Campamento estuvo descargado se cobran al
 * volver a cargarse (con los herreros que haya en ese momento).
 */
public final class Forge {
    public static final Set<VillagerProfession> SMITHS =
            Set.of(VillagerProfession.ARMORER, VillagerProfession.WEAPONSMITH, VillagerProfession.TOOLSMITH);
    /** Máximo de herreros que cuentan por Campamento. */
    public static int MAX_SMITHS = 4;
    /** Lingotes que trabaja cada herrero por día (cada uno con un carbón). */
    public static int IRON_PER_SMITH = 8;
    /** Armamento que sale de cada lingote: con herreros el hierro rinde el doble. */
    public static int ARMAMENT_PER_IRON = 2;
    /** Hasta cuántos días atrasados se cobran. */
    public static int MAX_CATCH_UP_DAYS = 7;

    private Forge() {}

    /** Los herreros del Campamento (adultos, a ≤24 bloques, hasta {@link #MAX_SMITHS}). */
    public static List<Villager> smiths(ServerLevel level, BlockPos camp) {
        return level.getEntitiesOfClass(Villager.class, CampBlockEntity.area(level, camp),   // H16: aldea = su territorio
                        v -> v.isAlive() && !v.isBaby() && SMITHS.contains(v.getVillagerData().getProfession())
                                && CampBlockEntity.covers(level, camp, v.blockPosition()))
                .stream().limit(MAX_SMITHS).toList();
    }

    /** Un día de trabajo con {@code smiths} herreros. Devuelve cuánto armamento salió. */
    public static int workDay(ServerLevel level, CampBlockEntity camp, int smiths) {
        if (smiths <= 0) return 0;
        int coal = camp.count(Items.COAL) + camp.count(Items.CHARCOAL);
        int iron = Math.min(smiths * IRON_PER_SMITH, Math.min(camp.count(Items.IRON_INGOT), coal));
        if (iron <= 0) return 0;
        camp.take(Items.IRON_INGOT, iron);
        int fromCoal = camp.take(Items.COAL, iron);
        camp.take(Items.CHARCOAL, iron - fromCoal);
        int made = iron * ARMAMENT_PER_IRON;
        ItemStack rest = camp.insert(new ItemStack(Armament.item(), made));
        if (!rest.isEmpty()) {
            BlockPos p = camp.getBlockPos();
            Containers.dropItemStack(level, p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, rest);
        }
        return made;
    }

    /** Cobra los días que pasaron desde la última vez (máx. {@link #MAX_CATCH_UP_DAYS}). Devuelve el armamento. */
    public static int catchUp(ServerLevel level, CampBlockEntity camp, long today) {
        long last = camp.lastForgeDay();
        if (last == CampBlockEntity.NEVER || last > today) {   // primera vez: no se cobra el día en curso
            camp.setLastForgeDay(today);
            return 0;
        }
        long days = Math.min(MAX_CATCH_UP_DAYS, today - last);
        if (days <= 0) return 0;
        camp.setLastForgeDay(today);
        int smiths = smiths(level, camp.getBlockPos()).size();
        int made = 0;
        for (int i = 0; i < days; i++) made += workDay(level, camp, smiths);
        return made;
    }

    /** Revisión periódica: todos los Campamentos cargados de este mundo. */
    public static void tick(ServerLevel level) {
        long today = Barracks.today(level);
        for (BlockPos pos : CampRegistry.get(level).allCamps()) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof CampBlockEntity camp) catchUp(level, camp, today);
        }
    }
}
