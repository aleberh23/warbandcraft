package com.ale.warbandcraft.village;

import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.panel.Panel;
import com.talhanation.workers.entities.AbstractWorkerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * El cartelito de resumen que se ve mientras estás en una aldea o castillo (en su territorio o a
 * {@link VillageManager#ENTER_RADIUS} del centro): nombre, producción por día, población y guarnición.
 */
public final class VillageHud {
    /** De quién es: tuya, de otro jugador, de una facción, o saqueada (de una facción). */
    public static final int MINE = 0, OTHER = 1, FACTION = 2, LOOTED = 3;

    /**
     * {@code barracks}/{@code capacity}/{@code guards}: solo las tuyas (-1 si no); {@code garrison}: soldados de la
     * facción (las que no son de un jugador); {@code owner}: el dueño si es de otro jugador.
     */
    public record Info(Component name, boolean castle, int relation, Component owner, VillageStock.Stock daily, int population,
                       int barracks, int capacity, int guards, int garrison, int invasionTicks) {}

    private VillageHud() {}

    /** La aldea/castillo en que está el jugador: la del territorio que pisa o, si no, la más cercana a ≤ ENTER_RADIUS. */
    @Nullable
    public static VillageData.Village at(ServerLevel level, BlockPos pos) {
        VillageData data = VillageData.get(level);
        BlockPos center = Territory.get(level).centerAt(new ChunkPos(pos));
        VillageData.Village v = center == null ? null : data.at(center);
        if (v == null) v = data.nearest(pos, VillageManager.ENTER_RADIUS);
        if (v != null && data.isTest(v.center())) return null;
        return v;
    }

    public static Info info(ServerPlayer player, VillageData.Village v, Component ownerName) {
        ServerLevel level = player.serverLevel();
        Faction faction = FactionManager.INSTANCE.get(v.faction());
        boolean mine = player.getUUID().equals(v.owner());
        int relation = mine ? MINE : v.isConquered() ? OTHER : v.isLooted(level.getGameTime()) ? LOOTED : FACTION;

        // El nombre de su Campamento si le pusiste uno; si no, "Aldea de ..." con el color de la facción
        Component name = VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v))).copy()
                .withStyle(mine ? ChatFormatting.GOLD : faction == null ? ChatFormatting.GRAY : faction.color());

        int barracks = -1, capacity = -1, guards = -1, garrison = 0;
        if (mine) {
            BlockPos camp = VillageCamps.campOf(level, v);
            BarracksData.Barracks b = camp == null ? null : BarracksData.get(level).at(camp);
            barracks = b == null ? 0 : b.soldiers.size();
            capacity = VillageCamps.capacity(level, v);
            guards = Panel.guardsAt(level, player.getUUID(), v.center());
        } else if (!v.isConquered()) {
            garrison = VillageManager.garrison(level, v.center()).size();
        }

        InvasionData.Invasion inv = mine ? InvasionData.get(level).at(v.center()) : null;
        InvasionData.Host host = mine && inv == null ? com.ale.warbandcraft.invasion.Hosts.hostAt(level, v.center()) : null;   // H23
        long arrive = inv != null ? inv.arriveTick : host != null ? host.deadline : -1;
        return new Info(name, v.castle(), relation, ownerName, VillageStock.daily(level, v), population(level, v), barracks, capacity,
                guards, garrison, arrive < 0 ? -1 : (int) Math.max(0, arrive - level.getGameTime()));
    }

    /** Aldeanos (grandes y chicos) + trabajadores de Villager Workers alrededor del centro (si está cargada). */
    public static int population(ServerLevel level, VillageData.Village v) {
        if (!level.isPositionEntityTicking(v.center())) return 0;
        int r = VillageCamps.MANAGE_RADIUS;
        AABB box = new AABB(v.center()).inflate(r, 32, r);
        return level.getEntitiesOfClass(Villager.class, box, Villager::isAlive).size()
                + level.getEntitiesOfClass(AbstractWorkerEntity.class, box, AbstractWorkerEntity::isAlive).size();
    }
}
