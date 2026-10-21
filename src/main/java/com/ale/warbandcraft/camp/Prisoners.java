package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.faction.Relations;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.village.CastleManager;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Prisioneros (H9): soldados enemigos capturados al ganar un ataque. Se guardan como ficha en el barracón del
 * Campamento más cercano del ganador ({@link BarracksData.Prisoner}). Desde la pestaña Barracón se reclutan
 * (pasan a ser soldados tuyos guardados) o se devuelven por rescate.
 */
public final class Prisoners {
    /** Rescate de un prisionero: esto más {@link #RANSOM_PER_LEVEL} por nivel... */
    public static int RANSOM_BASE = 3;
    public static int RANSOM_PER_LEVEL = 2;
    /** ...o esto si es un capitán. */
    public static int RANSOM_CAPTAIN = 30;
    /** Cuánto mejora la opinión de su facción al devolverlo. */
    public static int RANSOM_OPINION = 3;

    private static final int FOLLOW_OWNER = 1;

    private Prisoners() {}

    /**
     * Lleva al soldado (rendido o el último en pie) como prisionero al Campamento de {@code captor} más
     * cercano. False si no tiene Campamento o la prisión está llena: entonces queda donde está.
     */
    public static boolean imprison(ServerLevel level, AbstractRecruitEntity soldier, UUID captor) {
        BlockPos camp = CampRegistry.get(level).nearestCamp(captor, soldier.blockPosition());
        if (camp == null || !soldier.isAlive()) return false;
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.getOrCreate(camp, captor);
        if (b.prisoners.size() >= BarracksData.PRISON_CAPACITY) return false;

        if (Surrender.is(soldier)) Surrender.standUp(soldier);
        AbstractHorse horse = soldier.getVehicle() instanceof AbstractHorse h ? h : null;
        if (soldier.isPassenger()) soldier.stopRiding();
        CompoundTag horseTag = null;
        if (horse != null) {
            horseTag = Barracks.save(horse);
            horseTag.remove("Passengers");
        }
        CompoundTag tag = Barracks.save(soldier);
        tag.remove("Passengers");

        CompoundTag pd = soldier.getPersistentData();
        ResourceLocation faction = pd.contains(VillageManager.TAG_FACTION) ? ResourceLocation.tryParse(pd.getString(VillageManager.TAG_FACTION)) : null;
        b.prisoners.add(new BarracksData.Prisoner(tag, horseTag, soldier.getName().getString(), faction,
                soldier.getXpLevel(), pd.getBoolean(CastleManager.TAG_CAPTAIN)));
        data.changed();

        leaveTeam(level, soldier);
        UUID formerOwner = soldier.getIsOwned() ? soldier.getOwnerUUID() : null;
        soldier.discard();
        if (horse != null) horse.discard();
        if (formerOwner != null) Barracks.recount(level, formerOwner);
        return true;
    }

    /**
     * No hubo dónde llevarlo: queda libre ahí mismo, sin marcas de guarnición (no ataca, no patrulla) y se lo
     * puede contratar con la interacción normal de Recruits (lo de H4).
     */
    public static void release(AbstractRecruitEntity soldier) {
        if (Surrender.is(soldier)) Surrender.standUp(soldier);
        CompoundTag pd = soldier.getPersistentData();
        pd.remove(VillageManager.TAG_FACTION);
        pd.remove(VillageManager.TAG_VILLAGE);
        pd.remove(CastleManager.TAG_CASTLE);
        pd.remove(CastleManager.TAG_CAPTAIN);
        soldier.setTarget(null);
        soldier.clearRestriction();
        if (soldier.level() instanceof ServerLevel level) leaveTeam(level, soldier);
    }

    /** Los prisioneros de una fila (nombre + nivel), o todos si {@code rowKey} es null. */
    @Nullable
    private static BarracksData.Prisoner first(BarracksData.Barracks b, String rowKey) {
        for (BarracksData.Prisoner p : b.prisoners) if (rowKey.equals(p.rowKey())) return p;
        return null;
    }

    public enum Result { OK, NONE, FULL }

    /** Recluta un prisionero de la fila: pasa a ser un soldado de {@code owner} guardado en ese barracón. */
    public static Result recruit(ServerLevel level, BlockPos camp, String rowKey, UUID owner) {
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(camp);
        BarracksData.Prisoner p = b == null ? null : first(b, rowKey);
        if (p == null) return Result.NONE;
        if (b.soldiers.size() >= Barracks.capacity(level, camp)) return Result.FULL;

        CompoundTag tag = p.entity.copy();
        tag.putUUID("OwnerUUID", owner);
        tag.putBoolean("isOwned", true);
        tag.putInt("FollowState", FOLLOW_OWNER);
        tag.putInt("AggroState", 0);
        tag.putBoolean("Invulnerable", false);
        CompoundTag pd = tag.getCompound("ForgeData");
        ResourceLocation kit = pd.contains(VillageManager.TAG_TROOP_KIT) ? ResourceLocation.tryParse(pd.getString(VillageManager.TAG_TROOP_KIT)) : null;
        int tier = pd.getInt(VillageManager.TAG_TROOP_TIER);
        for (String key : List.of(VillageManager.TAG_FACTION, VillageManager.TAG_VILLAGE, VillageManager.TAG_TROOP_KIT,
                VillageManager.TAG_TROOP_TIER, CastleManager.TAG_CASTLE, CastleManager.TAG_CAPTAIN, Surrender.TAG)) {
            pd.remove(key);
        }
        if (kit != null) {
            pd.putString(ClassAssigner.TAG_CLASS, kit.toString());
            pd.putInt(ClassAssigner.TAG_TIER, tier);
        } else if (pd.contains(ClassAssigner.TAG_CLASS)) {
            kit = ResourceLocation.tryParse(pd.getString(ClassAssigner.TAG_CLASS));
            tier = pd.getInt(ClassAssigner.TAG_TIER);
        }
        pd.putLong(Barracks.TAG_CAMP, camp.asLong());
        tag.put("ForgeData", pd);

        b.prisoners.remove(p);
        if (b.soldiers.isEmpty()) {
            b.lastFedDay = Barracks.today(level);
            b.hungryDays = 0;
        }
        b.soldiers.add(new BarracksData.Soldier(tag, p.horse, false, null, 0.0F, FOLLOW_OWNER, p.name, kit, tier, p.xpLevel));
        data.changed();
        return Result.OK;
    }

    /** Devuelve un prisionero de la fila por rescate: esmeraldas al Campamento y su facción te quiere un poco más. */
    public static int ransom(ServerLevel level, BlockPos camp, String rowKey) {
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(camp);
        BarracksData.Prisoner p = b == null ? null : first(b, rowKey);
        if (p == null) return 0;
        b.prisoners.remove(p);
        data.changed();
        int emeralds = ransomOf(p);
        CampRegistry.get(level).deliver(level, b.owner, List.of(new ItemStack(Items.EMERALD, emeralds)), camp);
        if (p.faction != null) Relations.get(level.getServer()).add(b.owner, p.faction, RANSOM_OPINION);
        return emeralds;
    }

    public static int ransomOf(BarracksData.Prisoner p) {
        return p.captain ? RANSOM_CAPTAIN : RANSOM_BASE + RANSOM_PER_LEVEL * p.xpLevel;
    }

    /** Lo saca del equipo del marcador en el que estaba (el de su facción): si no, seguiría con sus colores. */
    private static void leaveTeam(ServerLevel level, AbstractRecruitEntity soldier) {
        PlayerTeam team = level.getScoreboard().getPlayersTeam(soldier.getStringUUID());
        if (team != null) level.getScoreboard().removePlayerFromTeam(soldier.getStringUUID(), team);
    }
}
