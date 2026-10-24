package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.village.VillageCamps;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * H19: campo de entrenamiento. En una aldea o castillo es una mejora de H12 (3 niveles); en un Campamento común se
 * mejora desde la pestaña Barracón pagando del almacén (vale desde el día siguiente). Da más lugar en el barracón y,
 * cada día que comen (el doble), los guardados ganan experiencia de Recruits hasta un tope por nivel.
 */
public final class Training {
    /** Lugar extra en el barracón por nivel. */
    public static int BONUS_1 = 20, BONUS_2 = 40, BONUS_3 = 60;
    /** Hasta qué nivel de Recruits entrena cada nivel del campo. */
    public static int CAP_1 = 3, CAP_2 = 6, CAP_3 = 10;
    /** Experiencia por día, en % de la que pide Recruits para subir un nivel (25 = un nivel cada 4 días). */
    public static int XP_PERCENT_PER_DAY = 25;
    /** Los días que entrenan comen esto por la comida normal. */
    public static int FOOD_FACTOR = 2;
    /** Campamento común: esmeraldas de cada nivel y días hasta que vale. */
    public static int CAMP_COST_1 = 16, CAMP_COST_2 = 32, CAMP_COST_3 = 64, CAMP_WAIT_DAYS = 1;

    private Training() {}

    public static int bonus(int level) {
        return level >= 3 ? BONUS_3 : level == 2 ? BONUS_2 : level == 1 ? BONUS_1 : 0;
    }

    public static int cap(int level) {
        return level >= 3 ? CAP_3 : level == 2 ? CAP_2 : level == 1 ? CAP_1 : 0;
    }

    public static int campCost(int nextLevel) {
        return nextLevel >= 3 ? CAMP_COST_3 : nextLevel == 2 ? CAMP_COST_2 : CAMP_COST_1;
    }

    /** El nivel del campo que vale hoy para el Campamento en {@code camp} (el de su aldea, o el propio). */
    public static int level(ServerLevel level, BlockPos camp) {
        VillageData.Village v = VillageCamps.villageOf(level, camp);
        if (v != null) {
            TownData.Town t = TownData.get(level).at(v.center());
            return t == null ? 0 : t.trainingLevel();
        }
        int[] tr = CampRegistry.get(level).trainingOf(camp);
        if (tr == null) return 0;
        long today = Barracks.today(level);
        return today >= tr[1] ? tr[0] : Math.max(0, tr[0] - 1);   // el nivel nuevo todavía no vale
    }

    /** El nivel encargado (aunque todavía no valga) de un Campamento común. */
    public static int ordered(ServerLevel level, BlockPos camp) {
        int[] tr = CampRegistry.get(level).trainingOf(camp);
        return tr == null ? 0 : tr[0];
    }

    /** Mejora el campo de un Campamento común un nivel, pagando del almacén. Devuelve el mensaje. */
    public static Component upgradeCamp(ServerLevel level, CampBlockEntity camp) {
        BlockPos pos = camp.getBlockPos();
        if (VillageCamps.villageOf(level, pos) != null) return error("msg.warbandcraft.training.village");
        int next = ordered(level, pos) + 1;
        if (next > 3) return error("msg.warbandcraft.training.max");
        if (level(level, pos) < next - 1) return error("msg.warbandcraft.training.wait");
        int cost = campCost(next);
        int have = camp.count(Items.EMERALD);
        if (have < cost) return error("msg.warbandcraft.training.no_money", cost, have);
        camp.take(Items.EMERALD, cost);
        CampRegistry.get(level).setTraining(pos, next, (int) (Barracks.today(level) + CAMP_WAIT_DAYS));
        return Component.translatable("msg.warbandcraft.training.ordered", next, cost, CAMP_WAIT_DAYS).withStyle(ChatFormatting.GREEN);
    }

    /** ¿Alguien del barracón (o del cuartel) todavía tiene para entrenar? */
    public static boolean anyToTrain(BarracksData.Barracks b, int level) {
        int cap = cap(level);
        for (BarracksData.Soldier s : b.soldiers) if (s.xpLevel < cap) return true;
        for (BarracksData.Soldier s : b.quarters) if (s.xpLevel < cap) return true;
        return false;
    }

    /** Un día de entrenamiento: experiencia a cada ficha por debajo del tope. Devuelve cuántos subieron de nivel. */
    public static int train(ServerLevel level, BarracksData.Barracks b, int trainingLevel) {
        return train(level, b.soldiers, trainingLevel) + train(level, b.quarters, trainingLevel);
    }

    private static int train(ServerLevel level, List<BarracksData.Soldier> list, int trainingLevel) {
        int cap = cap(trainingLevel);
        int max = com.talhanation.recruits.config.RecruitsServerConfig.RecruitsMaxXpForLevelUp.get();
        int xp = Math.max(1, (max * XP_PERCENT_PER_DAY + 99) / 100);   // redondeado para arriba: 25% = un nivel cada 4 días justos
        int up = 0;
        for (int i = 0; i < list.size(); i++) {
            BarracksData.Soldier s = list.get(i);
            if (s.xpLevel >= cap || s.entity.isEmpty()) continue;
            Optional<Entity> e = EntityType.create(s.entity, level);
            if (e.isEmpty() || !(e.get() instanceof AbstractRecruitEntity r)) continue;
            r.addXp(xp);
            r.checkLevel();
            int lvl = r.getXpLevel();
            CompoundTag tag = Barracks.save(r);
            r.discard();
            if (lvl > s.xpLevel) up++;
            list.set(i, new BarracksData.Soldier(tag, s.horse, s.guard, s.post, s.yRot, s.followState, s.name, s.kit, s.tier, lvl));
        }
        return up;
    }

    private static Component error(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.RED);
    }
}
