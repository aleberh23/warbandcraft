package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.FactionStyle;
import com.ale.warbandcraft.upgrade.TownData;
import com.talhanation.recruits.ClaimEvents;
import com.talhanation.recruits.world.RecruitsClaim;
import com.talhanation.recruits.world.RecruitsClaimManager;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsPlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido de Ale: toda aldea/castillo de una facción de la IA (sin dueño) está reclamada por su facción, entera (casas,
 * camas, puestos de trabajo, muralla, torres, terrenos de mejoras), y ahí un jugador no rompe nada con la mano. TNT,
 * cañones y bichos sí rompen (el asedio sigue siendo posible). Cuando la conquista alguien (un jugador o otra facción de
 * la IA) pasa a ser de él: el jugador la maneja con su claim de Recruits ({@code RecruitsClaimBridge}), otra facción de
 * la IA se queda con este claim.
 *
 * Dos capas: (1) un claim de Recruits por aldea a nombre de una facción de Recruits armada desde el JSON de la nuestra
 * (se ve en el mapa M con su color; "admin" para que Recruits no le haga sus propios asedios; tocar y poner bloques se
 * puede —puertas, cofres, escaleras de asedio—, romper no) y (2) nuestra propia protección ({@link #blocksBreaking}),
 * que no depende de la configuración de Recruits ni de que el claim exista. Se mide cerca de los jugadores (cada
 * {@link #RESYNC_SECONDS}, desde {@code VillageEvents}). Guardado en data/warbandcraft_faction_claims.dat.
 */
public class FactionClaims extends SavedData {
    private static final String FILE = "warbandcraft_faction_claims";
    /** Margen en bloques alrededor de todo lo de la aldea. */
    public static int MARGIN = 8;
    /** Medio lado mínimo de lo reclamado (24 = unos 5×5 chunks). */
    public static int MIN_RADIUS = 24;
    /** Medio lado mínimo de un castillo clásico (el plano mide 43×45; los dibujados usan su forma). */
    public static int CASTLE_RADIUS = 36;
    /** Hasta dónde se buscan camas, puestos de trabajo y campanas de la aldea. */
    public static int POI_RADIUS = 64;
    /** Tope: chunks a cada lado del chunk del centro (8 = 17×17). */
    public static int MAX_HALF_CHUNKS = 8;
    /** Cada cuánto se vuelve a medir una aldea con un jugador cerca. */
    public static int RESYNC_SECONDS = 60;
    /** Con un jugador a esta distancia de la aldea se mide y se reclama. */
    public static int SYNC_RANGE = 192;
    /** Sin medir todavía: a esta distancia del centro de una aldea de facción igual no se rompe nada. */
    public static int FALLBACK_RADIUS = 48;

    private final Map<Long, Set<Long>> chunks = new HashMap<>();
    private final Map<Long, UUID> claims = new HashMap<>();
    /** En memoria: última medición de cada aldea (game time). */
    private final Map<Long, Long> lastSync = new HashMap<>();

    public static FactionClaims get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FactionClaims::load, FactionClaims::new, FILE);
    }

    /** Los chunks reclamados por la facción de la aldea {@code center} (vacío si todavía no se midió). */
    public Set<Long> chunksOf(BlockPos center) {
        return chunks.getOrDefault(center.asLong(), Set.of());
    }

    @Nullable
    public UUID claimOf(BlockPos center) {
        return claims.get(center.asLong());
    }

    /** La aldea de facción (sin dueño) cuya zona reclamada tiene a {@code pos}, o null. */
    @Nullable
    public static VillageData.Village villageAt(ServerLevel level, BlockPos pos) {
        VillageData data = VillageData.get(level);
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        for (Map.Entry<Long, Set<Long>> e : get(level).chunks.entrySet()) {
            if (!e.getValue().contains(chunk)) continue;
            VillageData.Village v = data.at(BlockPos.of(e.getKey()));
            if (protects(level, v)) return v;
        }
        // Todavía sin medir (recién descubierta): el centro manda
        VillageData.Village near = data.nearest(pos, FALLBACK_RADIUS);
        return protects(level, near) && !get(level).chunks.containsKey(near.center().asLong()) ? near : null;
    }

    private static boolean protects(ServerLevel level, @Nullable VillageData.Village v) {
        return v != null && !v.isConquered() && !VillageData.get(level).isTest(v.center())
                && FactionManager.INSTANCE.get(v.faction()) != null;
    }

    /** ¿Este jugador no puede romper el bloque {@code pos}? (zona de una aldea de facción; creativo sí puede). */
    public static boolean blocksBreaking(ServerLevel level, Player player, BlockPos pos) {
        return !player.isCreative() && !player.isSpectator() && villageAt(level, pos) != null;
    }

    public static Component denied(ServerLevel level, BlockPos pos) {
        VillageData.Village v = villageAt(level, pos);
        Faction f = v == null ? null : FactionManager.INSTANCE.get(v.faction());
        return Component.translatable("msg.warbandcraft.faction_claim.no_break", f == null ? "?" : f.name()).withStyle(ChatFormatting.RED);
    }

    // ------------------------------------------------------------------ medir y reclamar

    /** Cada 5 s por jugador: las aldeas a {@link #SYNC_RANGE} se miden y se reclaman (cada {@link #RESYNC_SECONDS}). */
    public static void syncNear(ServerLevel level, BlockPos pos) {
        FactionClaims fc = get(level);
        long now = level.getGameTime();
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (VillageData.horizontalDistSq(v.center(), pos) > (double) SYNC_RANGE * SYNC_RANGE) continue;
            Long last = fc.lastSync.get(v.center().asLong());
            if (last != null && now - last < RESYNC_SECONDS * 20L) continue;
            if (!level.isLoaded(v.center())) continue;
            fc.lastSync.put(v.center().asLong(), now);
            try {
                sync(level, v);
            } catch (RuntimeException e) {
                WarbandCraft.LOGGER.warn("No se pudo reclamar la aldea {} para su facción", v.center().toShortString(), e);
            }
        }
    }

    /** Mide la aldea y pone al día su claim. Aldea con dueño: el claim de la facción se suelta (manda el del dueño). */
    public static void sync(ServerLevel level, VillageData.Village v) {
        FactionClaims fc = get(level);
        if (!protects(level, v)) {
            fc.release(level, v.center());
            return;
        }
        Faction f = FactionManager.INSTANCE.get(v.faction());
        Set<Long> area = measure(level, v);
        long key = v.center().asLong();
        if (!area.equals(fc.chunks.get(key))) {
            fc.chunks.put(key, area);
            fc.setDirty();
        }
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        if (m == null || level != level.getServer().overworld()) return;   // los claims de Recruits son del overworld

        UUID id = fc.claims.get(key);
        RecruitsClaim claim = id == null ? null : m.getClaim(id);
        if (claim == null || claim.isRemoved) {
            RecruitsClaim at = m.getClaim(new ChunkPos(v.center()));
            claim = at != null && !at.isRemoved && isFactionClaim(at) ? at : null;
        }
        boolean changed = false;
        RecruitsFaction rf = recruitsFaction(f);
        if (claim == null) {
            claim = new RecruitsClaim(VillageCamps.nameFor(v), rf);
            claim.setCenter(new ChunkPos(v.center()));
            changed = true;
        } else if (!f.teamName().equals(claim.getOwnerFactionStringID())) {
            claim.setOwnerFaction(rf);   // la tomó otra facción de la IA
            changed = true;
        }
        if (claim.getPlayerInfo() == null || !leaderOf(f).equals(claim.getPlayerInfo().getUUID())) {
            // Un claim sin jugador rompe Villager Workers (ver Territory.ensurePlayer): uno inventado, fijo por facción
            claim.setPlayer(new RecruitsPlayerInfo(leaderOf(f), f.name(), rf));
            changed = true;
        }
        if (!claim.isAdmin || !claim.isBlockInteractionAllowed() || !claim.isBlockPlacementAllowed() || claim.isBlockBreakingAllowed()) {
            claim.setAdminClaim(true);
            claim.setBlockInteractionAllowed(true);
            claim.setBlockPlacementAllowed(true);
            claim.setBlockBreakingAllowed(false);
            changed = true;
        }
        for (ChunkPos c : new ArrayList<>(claim.getClaimedChunks())) {
            if (!area.contains(c.toLong())) {
                claim.removeChunk(c);
                changed = true;
            }
        }
        for (long l : area) {
            ChunkPos c = new ChunkPos(l);
            if (claim.containsChunk(c)) continue;
            RecruitsClaim other = m.getClaim(c);
            if (other != null && !other.isRemoved && !other.getUUID().equals(claim.getUUID())) continue;   // de otro
            claim.addChunk(c);
            changed = true;
        }
        if (!claim.getUUID().equals(id)) {
            fc.claims.put(key, claim.getUUID());
            fc.setDirty();
        }
        if (changed) {
            m.addOrUpdateClaim(level, claim);
            m.broadcastClaimUpdateToAll(level, claim);
        }
    }

    /** Ya no es de una facción de la IA: se olvida la zona y se saca el claim si sigue siendo de la facción. */
    public void release(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        UUID id = claims.remove(key);
        if (chunks.remove(key) != null || id != null) setDirty();
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        if (id == null || m == null) return;
        RecruitsClaim claim = m.getClaim(id);
        if (claim == null || claim.isRemoved || !isFactionClaim(claim)) return;   // ya es del que la conquistó
        try {
            m.removeClaim(level, id);
            m.broadcastClaimsToAll(level);
        } catch (RuntimeException e) {
            WarbandCraft.LOGGER.warn("No se pudo sacar el claim de la facción en {}", center.toShortString(), e);
        }
    }

    /**
     * Toda la aldea: camas, puestos de trabajo y campanas que son de ella (no de una vecina), la muralla (anillo o
     * dibujada), portones, torres y terrenos de mejoras, + {@link #MARGIN}; como rectángulo de chunks.
     */
    public static Set<Long> measure(ServerLevel level, VillageData.Village v) {
        BlockPos c = v.center();
        int r = v.castle() ? CastleManager.shape(level, v).claimRadius() : MIN_RADIUS;
        int[] box = {c.getX() - r, c.getZ() - r, c.getX() + r, c.getZ() + r};
        VillageData data = VillageData.get(level);
        level.getPoiManager().getInSquare(t -> true, c, POI_RADIUS, PoiManager.Occupancy.ANY).forEach(p -> {
            VillageData.Village owner = data.nearest(p.getPos(), POI_RADIUS);
            if (owner != null && owner.center().equals(c)) extend(box, p.getPos().getX(), p.getPos().getZ(), 0);
        });
        TownData.Town town = TownData.get(level).at(c);
        if (town != null) {
            if (town.ringRadius > 0) {
                extend(box, c.getX() - town.ringRadius, c.getZ() - town.ringRadius, 0);
                extend(box, c.getX() + town.ringRadius, c.getZ() + town.ringRadius, 0);
            }
            town.sites.forEach((k, s) -> {
                extend(box, s.getX(), s.getZ(), 0);
                extend(box, s.getX() + town.siteSize(k) - 1, s.getZ() + town.siteSize(k) - 1, 0);
            });
            for (long l : town.defense.keySet()) extend(box, BlockPos.getX(l), BlockPos.getZ(l), 0);
            for (BlockPos t : town.towers) extend(box, t.getX(), t.getZ(), 4);
            for (BlockPos g : town.gates) extend(box, g.getX(), g.getZ(), 2);
        }
        ChunkPos home = new ChunkPos(c);
        int x0 = Math.max(home.x - MAX_HALF_CHUNKS, (box[0] - MARGIN) >> 4), z0 = Math.max(home.z - MAX_HALF_CHUNKS, (box[1] - MARGIN) >> 4);
        int x1 = Math.min(home.x + MAX_HALF_CHUNKS, (box[2] + MARGIN) >> 4), z1 = Math.min(home.z + MAX_HALF_CHUNKS, (box[3] + MARGIN) >> 4);
        Set<Long> out = new LinkedHashSet<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) out.add(ChunkPos.asLong(x, z));
        return out;
    }

    private static void extend(int[] box, int x, int z, int pad) {
        box[0] = Math.min(box[0], x - pad);
        box[1] = Math.min(box[1], z - pad);
        box[2] = Math.max(box[2], x + pad);
        box[3] = Math.max(box[3], z + pad);
    }

    // ------------------------------------------------------------------ la facción del lado de Recruits

    /** "Jugador" inventado y fijo de cada facción de la IA (dueño del claim y líder de su facción de Recruits). */
    public static UUID leaderOf(Faction f) {
        return UUID.nameUUIDFromBytes(("warbandcraft:faction:" + f.teamName()).getBytes(StandardCharsets.UTF_8));
    }

    /** ¿Es un claim nuestro de una facción de la IA? (su jugador es el inventado de esa facción). */
    public static boolean isFactionClaim(RecruitsClaim claim) {
        if (claim.getPlayerInfo() == null) return false;
        UUID player = claim.getPlayerInfo().getUUID();
        for (Faction f : FactionManager.INSTANCE.ordered()) if (leaderOf(f).equals(player)) return true;
        return false;
    }

    /**
     * La facción de Recruits de una facción nuestra, solo para el claim (va guardada adentro del claim; no se registra en
     * Recruits: así no sale en su lista de facciones ni se le pueden pedir entrar). Mismo id que su equipo del marcador.
     */
    private static RecruitsFaction recruitsFaction(Faction f) {
        RecruitsFaction rf = new RecruitsFaction();
        rf.setStringID(f.teamName());
        rf.setTeamDisplayName(f.name());
        rf.setTeamLeaderID(leaderOf(f));
        rf.setTeamLeaderName(f.name());
        rf.setBanner(f.banner().isEmpty() ? new CompoundTag() : f.banner().save(new CompoundTag()));
        Integer rgb = f.color().getColor();
        rf.setTeamColor(rgb == null ? 0xFFFFFF : rgb);
        rf.setUnitColor(FactionStyle.nearestUnitColor(rgb == null ? f.armorColor() : rgb));
        return rf;
    }

    // ------------------------------------------------------------------ guardado

    public static FactionClaims load(CompoundTag tag) {
        FactionClaims fc = new FactionClaims();
        CompoundTag c = tag.getCompound("Chunks");
        for (String k : c.getAllKeys()) {
            Set<Long> set = new LinkedHashSet<>();
            for (long l : c.getLongArray(k)) set.add(l);
            fc.chunks.put(Long.parseLong(k), set);
        }
        CompoundTag cl = tag.getCompound("Claims");
        for (String k : cl.getAllKeys()) if (cl.hasUUID(k)) fc.claims.put(Long.parseLong(k), cl.getUUID(k));
        return fc;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag c = new CompoundTag();
        chunks.forEach((k, set) -> c.put(Long.toString(k), new LongArrayTag(set.stream().mapToLong(Long::longValue).toArray())));
        tag.put("Chunks", c);
        CompoundTag cl = new CompoundTag();
        claims.forEach((k, id) -> cl.putUUID(Long.toString(k), id));
        tag.put("Claims", cl);
        return tag;
    }
}
