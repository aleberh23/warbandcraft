package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.faction.FactionStyle;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * H16: el territorio de cada aldea/castillo tuyo, en chunks (data/warbandcraft_territory.dat, por dimensión). Al
 * conquistarla se le dan {@link #START_RADIUS} chunks alrededor del centro; se agranda de a un chunk pegado, pagando
 * {@link #CHUNK_COST} esmeraldas, hasta {@link #MAX_CHUNKS}. Es la jurisdicción del Campamento de la aldea (armar,
 * vincular cofres, guardar soldados) y donde se pueden marcar zonas de trabajo.
 * Si el dueño tiene una facción de Recruits, el territorio es además un claim de Recruits (se ve en el mapa M).
 */
public class Territory extends SavedData {
    private static final String FILE = "warbandcraft_territory";
    /** Al conquistar: chunks alrededor del chunk del centro (2 = 5×5). */
    public static int START_RADIUS = 2;
    /** Tope (el de un claim de Recruits) y precio de cada chunk de más. */
    public static int MAX_CHUNKS = RecruitsClaim.DEFAULT_MAX_SIZE, CHUNK_COST = 10;

    private final Map<Long, Set<Long>> chunks = new HashMap<>();
    private final Map<Long, UUID> claims = new HashMap<>();

    public static Territory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Territory::load, Territory::new, FILE);
    }

    public boolean has(BlockPos center) {
        return chunks.containsKey(center.asLong());
    }

    /** Los chunks de la aldea (vacío si no tiene territorio). */
    public List<ChunkPos> of(BlockPos center) {
        List<ChunkPos> out = new ArrayList<>();
        for (long l : chunks.getOrDefault(center.asLong(), Set.of())) out.add(new ChunkPos(l));
        return out;
    }

    public boolean contains(BlockPos center, BlockPos pos) {
        Set<Long> set = chunks.get(center.asLong());
        return set != null && set.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** La aldea (de cualquiera) cuyo territorio tiene el chunk {@code c}, o null. */
    @Nullable
    public BlockPos centerAt(ChunkPos c) {
        long key = c.toLong();
        for (Map.Entry<Long, Set<Long>> e : chunks.entrySet()) if (e.getValue().contains(key)) return BlockPos.of(e.getKey());
        return null;
    }

    /** Tu aldea/castillo cuyo territorio tiene a {@code pos}, o null. */
    @Nullable
    public static VillageData.Village villageAt(ServerLevel level, BlockPos pos, UUID owner) {
        BlockPos center = get(level).centerAt(new ChunkPos(pos));
        if (center == null) return null;
        VillageData.Village v = VillageData.get(level).at(center);
        return v != null && owner.equals(v.owner()) ? v : null;
    }

    // ------------------------------------------------------------------ crear, agrandar, sacar

    /** La aldea es tuya y todavía no tiene territorio: se le dan los chunks de arranque (los libres). Después, el claim. */
    public static void ensure(ServerLevel level, VillageData.Village v) {
        Territory t = get(level);
        long key = v.center().asLong();
        // Si la aldea ya está dentro de un claim de tu facción de Recruits, ese claim ES su territorio (y manda: si lo
        // cambiás desde Recruits, el territorio lo sigue)
        RecruitsClaim own = factionClaimAt(level, v, new ChunkPos(v.center()));
        if (own != null) {
            RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
            if (ensurePlayer(level, own, v.owner())) {   // claims de antes, sin jugador (ver ensurePlayer)
                try {
                    m.addOrUpdateClaim(level, own);
                    m.broadcastClaimUpdateToAll(level, own);
                } catch (RuntimeException e) {
                    WarbandCraft.LOGGER.warn("No se pudo arreglar el claim de la aldea {}", v.center(), e);
                }
            }
            UUID old = t.claims.get(key);
            if (old != null && !old.equals(own.getUUID())) {   // uno nuestro de antes (vacío): se saca
                RecruitsClaim o = m.getClaim(old);
                if (o != null && o.getClaimedChunks().size() <= 1) {
                    try {
                        m.removeClaim(level, old);
                        m.broadcastClaimsToAll(level);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            Set<Long> set = new LinkedHashSet<>();
            for (ChunkPos c : own.getClaimedChunks()) set.add(c.toLong());
            if (!set.equals(t.chunks.get(key)) || !own.getUUID().equals(old)) {
                t.chunks.put(key, set);
                t.claims.put(key, own.getUUID());
                t.setDirty();
            }
            return;
        }
        Set<Long> current = t.chunks.get(key);
        if (current != null && current.size() <= 1 && t.claims.containsKey(key)) {
            // Territorio que quedó en un solo chunk (error de antes): se arma de nuevo
            t.chunks.remove(key);
            UUID old = t.claims.remove(key);
            RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
            if (old != null && m != null) {
                try {
                    m.removeClaim(level, old);
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (!t.has(v.center())) {
            Set<Long> set = new LinkedHashSet<>();
            ChunkPos c = new ChunkPos(v.center());
            for (int dx = -START_RADIUS; dx <= START_RADIUS; dx++) {
                for (int dz = -START_RADIUS; dz <= START_RADIUS; dz++) {
                    ChunkPos p = new ChunkPos(c.x + dx, c.z + dz);
                    if (t.centerAt(p) == null && !claimedByOther(p, null)) set.add(p.toLong());
                }
            }
            set.add(c.toLong());
            t.chunks.put(v.center().asLong(), set);
            t.setDirty();
        }
        t.syncClaim(level, v);
    }

    /** Agregar el chunk {@code c} (pegado al territorio, libre), pagando {@link #CHUNK_COST} esmeraldas. */
    public static Component expand(ServerPlayer player, VillageData.Village v, ChunkPos c) {
        ServerLevel level = player.serverLevel();
        Territory t = get(level);
        Set<Long> set = t.chunks.get(v.center().asLong());
        if (set == null) return error("msg.warbandcraft.territory.none");
        if (set.contains(c.toLong())) return error("msg.warbandcraft.territory.already");
        if (set.size() >= MAX_CHUNKS) return error("msg.warbandcraft.territory.max", MAX_CHUNKS);
        boolean touches = set.contains(ChunkPos.asLong(c.x + 1, c.z)) || set.contains(ChunkPos.asLong(c.x - 1, c.z))
                || set.contains(ChunkPos.asLong(c.x, c.z + 1)) || set.contains(ChunkPos.asLong(c.x, c.z - 1));
        if (!touches) return error("msg.warbandcraft.territory.not_next");
        if (t.centerAt(c) != null || claimedByOther(c, t.claims.get(v.center().asLong()))) return error("msg.warbandcraft.territory.taken");
        int inStock = VillageStock.total(level, v).emeralds();
        int inHand = player.getInventory().countItem(Items.EMERALD);
        if (inStock + inHand < CHUNK_COST) return error("msg.warbandcraft.territory.no_money", CHUNK_COST, inStock, inHand);
        int paid = VillageStock.takeEmeralds(level, v, CHUNK_COST);
        if (paid < CHUNK_COST) com.ale.warbandcraft.upgrade.Upgrades.takeFromInventory(player, CHUNK_COST - paid);
        set.add(c.toLong());
        t.setDirty();
        t.syncClaim(level, v);
        return Component.translatable("msg.warbandcraft.territory.expanded", set.size(), MAX_CHUNKS, CHUNK_COST).withStyle(ChatFormatting.GREEN);
    }

    /** La aldea ya no es tuya: sin territorio y sin claim. */
    public static void remove(ServerLevel level, BlockPos center) {
        Territory t = get(level);
        UUID claim = t.claims.remove(center.asLong());
        if (t.chunks.remove(center.asLong()) != null || claim != null) t.setDirty();
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        if (claim != null && m != null) {
            try {
                m.removeClaim(level, claim);
                m.broadcastClaimsToAll(level);
            } catch (RuntimeException e) {
                WarbandCraft.LOGGER.warn("No se pudo sacar el claim de la aldea {}", center, e);
            }
        }
    }

    private static Component error(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.RED);
    }

    // ------------------------------------------------------------------ claim de Recruits

    /** El id de la facción de Recruits del jugador (su equipo del marcador), aunque no esté conectado; null si no tiene. */
    @Nullable
    public static String factionOf(ServerLevel level, @Nullable UUID player) {
        if (player == null) return null;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        String name = p != null ? p.getScoreboardName()
                : level.getServer().getProfileCache() == null ? null
                : level.getServer().getProfileCache().get(player).map(com.mojang.authlib.GameProfile::getName).orElse(null);
        if (name == null) return null;
        net.minecraft.world.scores.PlayerTeam team = level.getScoreboard().getPlayersTeam(name);
        return team == null ? null : team.getName();
    }

    /** El claim de la facción del dueño de la aldea que tiene el chunk {@code c}, o null. */
    @Nullable
    private static RecruitsClaim factionClaimAt(ServerLevel level, VillageData.Village v, ChunkPos c) {
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        String fid = factionOf(level, v.owner());
        if (m == null || fid == null) return null;
        RecruitsClaim claim = m.getClaim(c);
        return claim != null && !claim.isRemoved && fid.equals(claim.getOwnerFactionStringID()) && !claim.getClaimedChunks().isEmpty()
                ? claim : null;
    }

    /**
     * Un claim sin jugador rompe Villager Workers: al actualizarlo (p. ej. sumarle un chunk desde el mapa M) les pasa ese
     * jugador (null) a las zonas de trabajo de adentro, tira NullPointerException y Recruits no termina de guardar el
     * cambio (el mapa queda desfasado y "se borran" chunks). Se le pone el dueño de la aldea. Devuelve si lo cambió.
     */
    public static boolean ensurePlayer(ServerLevel level, @Nullable RecruitsClaim claim, @Nullable UUID owner) {
        if (claim == null || owner == null) return false;
        RecruitsPlayerInfo info = claim.getPlayerInfo();
        if (info != null && info.getUUID() != null) return false;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        String name = p != null ? p.getGameProfile().getName()
                : level.getServer().getProfileCache() == null ? null
                : level.getServer().getProfileCache().get(owner).map(com.mojang.authlib.GameProfile::getName).orElse(null);
        claim.setPlayer(new RecruitsPlayerInfo(owner, name == null ? "?" : name, claim.getOwnerFaction()));
        return true;
    }

    /** ¿El chunk ya es de otro claim de Recruits (no el nuestro {@code own})? */
    private static boolean claimedByOther(ChunkPos c, @Nullable UUID own) {
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        if (m == null) return false;
        RecruitsClaim claim = m.getClaim(c);
        return claim != null && !claim.isRemoved && (own == null || !own.equals(claim.getUUID()));
    }

    /**
     * El territorio como claim de Recruits del dueño (si está conectado y tiene facción de Recruits). Se crea o se pone al
     * día (chunks, nombre). Sin facción no hay claim: el territorio igual vale para el mod.
     */
    public void syncClaim(ServerLevel level, VillageData.Village v) {
        RecruitsClaimManager m = ClaimEvents.recruitsClaimManager;
        Set<Long> set = chunks.get(v.center().asLong());
        if (m == null || set == null || v.owner() == null) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(v.owner());
        UUID id = claims.get(v.center().asLong());
        RecruitsClaim claim = id == null ? null : m.getClaim(id);
        boolean fixed = ensurePlayer(level, claim, v.owner());
        if (!fixed && claim != null && claim.getClaimedChunks().size() == set.size()) return;   // al día
        if (owner == null) return;
        RecruitsFaction faction = FactionStyle.recruitsFactionOf(owner);
        if (faction == null) return;
        try {
            if (claim == null) {
                claim = new RecruitsClaim(VillageCamps.nameFor(v), faction);
                claim.setCenter(new ChunkPos(v.center()));
                claim.setPlayer(new RecruitsPlayerInfo(owner.getUUID(), owner.getGameProfile().getName(), faction));
                claims.put(v.center().asLong(), claim.getUUID());
                setDirty();
            }
            for (ChunkPos c : new ArrayList<>(claim.getClaimedChunks())) {
                if (!set.contains(c.toLong())) claim.removeChunk(c);
            }
            for (long l : set) {
                ChunkPos c = new ChunkPos(l);
                if (!claim.containsChunk(c) && !claimedByOther(c, claim.getUUID())) claim.addChunk(c);
            }
            m.addOrUpdateClaim(level, claim);
            m.broadcastClaimUpdateToAll(level, claim);
        } catch (RuntimeException e) {
            WarbandCraft.LOGGER.warn("No se pudo armar el claim de la aldea {}", v.center(), e);
        }
    }

    // ------------------------------------------------------------------ guardado

    public static Territory load(CompoundTag tag) {
        Territory t = new Territory();
        CompoundTag c = tag.getCompound("Chunks");
        for (String k : c.getAllKeys()) {
            Set<Long> set = new LinkedHashSet<>();
            for (long l : c.getLongArray(k)) set.add(l);
            t.chunks.put(Long.parseLong(k), set);
        }
        CompoundTag cl = tag.getCompound("Claims");
        for (String k : cl.getAllKeys()) if (cl.hasUUID(k)) t.claims.put(Long.parseLong(k), cl.getUUID(k));
        return t;
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
