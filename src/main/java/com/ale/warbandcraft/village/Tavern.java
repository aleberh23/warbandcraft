package com.ale.warbandcraft.village;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.BarracksData;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.upgrade.TownData;
import com.ale.warbandcraft.upgrade.Upgrade;
import com.ale.warbandcraft.worker.InnkeeperEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * H19: la taberna de una aldea tuya (mejora de H12, 3 niveles). Cada día, con un Tabernero trabajando y comida en el
 * almacén, llegan reclutas que esperan en la taberna (se ven ahí: reclutas quietos, sin dueño). Pasan solos al cuartel y
 * después al barracón cada mañana (Auto-reclutar), o los pasás a mano desde Gestión → Tropas, o los contratás con
 * click derecho (gratis: lo que cuesta es armarlos). Si nadie los usa en {@link #LEAVE_DAYS} días, se van.
 * <p>
 * Los que esperan son datos ({@link TownData.Town#tavernWaiting}: nivel y día de llegada); los que se ven son una copia
 * que no se guarda ({@link #TAG}) y se vuelve a armar cuando la aldea está cargada.
 */
public final class Tavern {
    /** Marca de los reclutas que se ven en la taberna (centro de la aldea) y su nivel. */
    public static final String TAG = "warbandcraft:tavern", TAG_LEVEL = "warbandcraft:tavern_level";

    /** Reclutas que llegan por día, cuántos pueden esperar y entre qué niveles vienen, por nivel de taberna. */
    public static int ARRIVALS_1 = 2, ARRIVALS_2 = 4, ARRIVALS_3 = 6;
    public static int MAX_WAITING_1 = 4, MAX_WAITING_2 = 8, MAX_WAITING_3 = 12;
    public static int MIN_LEVEL_1 = 0, MAX_LEVEL_1 = 1, MIN_LEVEL_2 = 0, MAX_LEVEL_2 = 3, MIN_LEVEL_3 = 2, MAX_LEVEL_3 = 5;
    /** Puntos de comida (los de la barra de hambre) que se come cada recluta que llega. Sin comida no llega nadie. */
    public static int FOOD_PER_RECRUIT = 10;
    /** Puntos de comida de cada pan del almacén cuando la aldea no está cargada. */
    public static int BREAD_POINTS = 5;
    /** Días que espera un recluta antes de irse. */
    public static int LEAVE_DAYS = 3;
    /** Cada cuánto se acomodan los que se ven en la taberna (y se mira si hay tabernero). */
    public static int EVERY_TICKS = 100;

    private Tavern() {}

    // ------------------------------------------------------------------ tabla

    public static int arrivals(int level) {
        return level >= 3 ? ARRIVALS_3 : level == 2 ? ARRIVALS_2 : level == 1 ? ARRIVALS_1 : 0;
    }

    public static int maxWaiting(int level) {
        return level >= 3 ? MAX_WAITING_3 : level == 2 ? MAX_WAITING_2 : level == 1 ? MAX_WAITING_1 : 0;
    }

    private static int minLevel(int level) {
        return level >= 3 ? MIN_LEVEL_3 : level == 2 ? MIN_LEVEL_2 : MIN_LEVEL_1;
    }

    private static int maxLevel(int level) {
        return level >= 3 ? MAX_LEVEL_3 : level == 2 ? MAX_LEVEL_2 : MAX_LEVEL_1;
    }

    // ------------------------------------------------------------------ lugar

    /** Dónde atiende el Tabernero: el medio de la taberna (null si la aldea no tiene taberna). */
    @Nullable
    public static BlockPos workplace(ServerLevel level, VillageData.Village v) {
        TownData.Town t = TownData.get(level).at(v.center());
        if (t == null || t.tavernLevel() == 0) return null;
        BlockPos site = t.sites.get(Upgrade.TAVERN_1.siteKey());
        return site == null ? null : site.offset(4, 1, 4);
    }

    /** Hay un Tabernero tuyo de esta aldea cargado. */
    public static boolean hasInnkeeper(ServerLevel level, VillageData.Village v) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null) return false;
        return !level.getEntitiesOfClass(InnkeeperEntity.class, new AABB(v.center()).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS),
                k -> k.isAlive() && camp.equals(k.getCamp()) && Objects.equals(k.getOwnerUUID(), v.owner())).isEmpty();
    }

    // ------------------------------------------------------------------ cada día

    /** Día nuevo: se van los que esperaron demasiado, llegan los nuevos y (con Auto-reclutar) pasan al cuartel/barracón. */
    public static void onNewDay(ServerLevel level, long day) {
        TownData towns = TownData.get(level);
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (!v.isConquered() || v.castle()) continue;
            TownData.Town t = towns.at(v.center());
            if (t == null || t.tavernLevel() == 0) continue;
            int arrived = arrive(level, v, t, day);
            int moved = t.autoRecruit ? autoRecruit(level, v, t) : 0;
            towns.changed();
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(v.owner());
            if (owner != null && (arrived > 0 || moved > 0)) {
                owner.sendSystemMessage(Component.translatable("msg.warbandcraft.tavern.day", placeName(level, v), arrived, moved,
                        t.tavernWaiting.size()).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** Se van los viejos y llegan los nuevos (con tabernero y comida). Devuelve cuántos llegaron. */
    public static int arrive(ServerLevel level, VillageData.Village v, TownData.Town t, long day) {
        t.tavernWaiting.removeIf(w -> day - w[1] >= LEAVE_DAYS);
        if (level.isPositionEntityTicking(v.center())) t.innkeeper = hasInnkeeper(level, v);
        if (!t.innkeeper) return 0;
        int lvl = t.tavernLevel();
        int n = Math.min(arrivals(lvl), maxWaiting(lvl) - t.tavernWaiting.size());
        int arrived = 0;
        for (int i = 0; i < n; i++) {
            if (!payFood(level, v)) break;
            int min = minLevel(lvl), max = Math.max(min, maxLevel(lvl));
            t.tavernWaiting.add(new int[]{min + level.random.nextInt(max - min + 1), (int) day});
            arrived++;
        }
        return arrived;
    }

    /** Un recluta come: del almacén de la aldea (cargado) o de lo que espera como números (descargado). */
    private static boolean payFood(ServerLevel level, VillageData.Village v) {
        CampBlockEntity camp = VillageStorehouse.camp(level, v);
        if (camp != null) {
            if (Barracks.foodPoints(camp) < FOOD_PER_RECRUIT) return false;
            Barracks.takeFood(camp, FOOD_PER_RECRUIT);
            return true;
        }
        VillageData data = VillageData.get(level);
        VillageStock.Stock s = data.stock(v.center());
        int bread = (FOOD_PER_RECRUIT + BREAD_POINTS - 1) / Math.max(1, BREAD_POINTS);
        if (s.food() < bread) return false;
        data.setStock(v.center(), new VillageStock.Stock(s.emeralds(), s.food() - bread, s.armament()));
        return true;
    }

    /** Todos los que esperan: primero al cuartel (hasta su lugar), después al barracón de la aldea. Devuelve cuántos pasaron. */
    public static int autoRecruit(ServerLevel level, VillageData.Village v, TownData.Town t) {
        int n = toQuarters(level, v, t, Integer.MAX_VALUE);
        return n + toBarracks(level, v, t, Integer.MAX_VALUE);
    }

    /** Pasa hasta {@code count} reclutas (los de más nivel primero) al cuartel. Devuelve cuántos. */
    public static int toQuarters(ServerLevel level, VillageData.Village v, TownData.Town t, int count) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null) return 0;
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.getOrCreate(camp, v.owner());
        int free = Quarters.capacity(level, v) - b.quarters.size() - Quarters.loaded(level, v.center()).size();
        int n = move(level, v, t, camp, b.quarters, Math.min(count, free), Barracks.BACK_TO_POS);
        if (n > 0) data.changed();
        return n;
    }

    /** Pasa hasta {@code count} reclutas al barracón de la aldea. Devuelve cuántos. */
    public static int toBarracks(ServerLevel level, VillageData.Village v, TownData.Town t, int count) {
        BlockPos camp = VillageCamps.campOf(level, v);
        if (camp == null) return 0;
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.getOrCreate(camp, v.owner());
        int free = Barracks.capacity(level, camp) - b.soldiers.size();
        int n = move(level, v, t, camp, b.soldiers, Math.min(count, free), Barracks.FOLLOW_OWNER);
        if (n > 0) data.changed();
        return n;
    }

    private static int move(ServerLevel level, VillageData.Village v, TownData.Town t, BlockPos camp, List<BarracksData.Soldier> into,
                            int count, int follow) {
        t.tavernWaiting.sort(Comparator.comparingInt((int[] w) -> w[0]).reversed());
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        int moved = 0;
        while (moved < count && !t.tavernWaiting.isEmpty()) {
            int[] w = t.tavernWaiting.get(0);
            BarracksData.Soldier sheet = sheet(level, v.owner(), camp, w[0], follow);
            if (sheet == null) break;
            if (b != null && b.eaters() == 0) {   // barracón vacío: empieza a comer mañana
                b.lastFedDay = Barracks.today(level);
                b.hungryDays = 0;
            }
            t.tavernWaiting.remove(0);
            into.add(sheet);
            moved++;
        }
        if (moved > 0) {
            TownData.get(level).changed();
            removeVisuals(level, v.center(), moved);
        }
        return moved;
    }

    /** La ficha de un recluta nuevo tuyo (sin clase, sin equipo) de nivel {@code xpLevel}, atado al Campamento. */
    @Nullable
    public static BarracksData.Soldier sheet(ServerLevel level, UUID owner, BlockPos camp, int xpLevel, int follow) {
        AbstractRecruitEntity r = create(level, xpLevel);
        if (r == null) return null;
        r.setOwnerUUID(Optional.of(owner));
        r.setIsOwned(true);
        r.setFollowState(follow);
        r.getPersistentData().putLong(Barracks.TAG_CAMP, camp.asLong());
        CompoundTag tag = Barracks.save(r);
        String name = r.getName().getString();
        int lvl = r.getXpLevel();
        r.discard();
        return new BarracksData.Soldier(tag, null, false, null, 0F, follow, name, null, 0, lvl);
    }

    /** Un recluta de Recruits (sin agregar al mundo) que ya subió hasta {@code xpLevel}. */
    @Nullable
    private static AbstractRecruitEntity create(ServerLevel level, int xpLevel) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", "recruit"));
        Entity e = type == null ? null : type.create(level);
        if (!(e instanceof AbstractRecruitEntity r)) {
            if (e != null) e.discard();
            return null;
        }
        try {
            r.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.ZERO), MobSpawnType.EVENT, null, null);
        } catch (RuntimeException ignored) {
            // como en las guarniciones: Recruits a veces falla acá y no importa
        }
        levelUp(r, xpLevel);
        return r;
    }

    /** Sube al recluta {@code levels} niveles con la experiencia de Recruits (así recibe las mejoras de cada nivel). */
    public static void levelUp(AbstractRecruitEntity r, int levels) {
        int need = com.talhanation.recruits.config.RecruitsServerConfig.RecruitsMaxXpForLevelUp.get();
        for (int i = 0; i < levels; i++) {
            r.addXp(need);
            r.checkLevel();
        }
        r.setHealth(r.getMaxHealth());
    }

    // ------------------------------------------------------------------ los que se ven

    /** Cada {@link #EVERY_TICKS}: en tus aldeas cargadas con taberna, tantos reclutas a la vista como esperan. */
    public static void tick(ServerLevel level) {
        TownData towns = TownData.get(level);
        long now = level.getGameTime();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (VillageData.Village v : new ArrayList<>(VillageData.get(level).all())) {
            if (v.castle() || !level.isPositionEntityTicking(v.center())) continue;
            TownData.Town t = towns.at(v.center());
            if (t == null || t.tavernLevel() == 0) continue;
            keepInside(level, v, now, seen);   // pedido de Ale: los que se pueden contratar esperan adentro, no vagan
            if (!v.isConquered()) continue;
            boolean keeper = hasInnkeeper(level, v);
            if (keeper != t.innkeeper) {
                t.innkeeper = keeper;
                towns.changed();
            }
            syncVisuals(level, v, t);
        }
        PATRONS.keySet().retainAll(seen);
    }

    // ------------------------------------------------------------------ adentro de la taberna

    /** Un recluta que no llega a su lugar en la taberna en tanto tiempo, aparece ahí. */
    public static int WALK_IN_TICKS = 20 * 30;
    /** Los reclutas sueltos a esta distancia de la campana van a la taberna. */
    public static int GATHER_RADIUS = 64;

    private record Patron(Vec3 spot, long since) {}

    private static final java.util.Map<UUID, Patron> PATRONS = new java.util.HashMap<>();
    private static final java.util.Map<Long, CachedSpots> SPOTS = new java.util.HashMap<>();

    private record CachedSpots(long at, List<BlockPos> spots) {}

    /**
     * Los reclutas que se pueden contratar (los de la taberna y los sueltos de Recruits, sin dueño ni facción) se quedan
     * adentro de la taberna, cada uno en su lugar.
     */
    private static void keepInside(ServerLevel level, VillageData.Village v, long now, java.util.Set<UUID> seen) {
        List<AbstractRecruitEntity> patrons = level.getEntitiesOfClass(AbstractRecruitEntity.class,
                new AABB(v.center()).inflate(GATHER_RADIUS, 32, GATHER_RADIUS), Tavern::isHireable);
        if (patrons.isEmpty()) return;
        List<BlockPos> spots = spots(level, v, now);
        if (spots.isEmpty()) return;
        patrons.sort(Comparator.comparing(e -> e.getUUID().toString()));
        for (int i = 0; i < patrons.size(); i++) {
            AbstractRecruitEntity r = patrons.get(i);
            Vec3 spot = Vec3.atBottomCenterOf(spots.get(i % spots.size()));
            seen.add(r.getUUID());
            Patron p = PATRONS.get(r.getUUID());
            if (p == null || p.spot().distanceToSqr(spot) > 0.25) {
                p = new Patron(spot, now);
                PATRONS.put(r.getUUID(), p);
            }
            double d = r.position().distanceToSqr(spot);
            if (d > 2.25 && (now - p.since() > WALK_IN_TICKS || d > 48 * 48)) {   // no llega (puerta, escalones): aparece adentro
                r.teleportTo(spot.x, spot.y, spot.z);
                r.getNavigation().stop();
                PATRONS.put(r.getUUID(), new Patron(spot, now));
            }
            Vec3 h = r.getHoldPos();
            if (r.getFollowState() != 3 || h == null || h.distanceToSqr(spot) > 0.25) {
                r.setHoldPos(spot);
                r.setFollowState(3);
            }
            if (r.getTarget() == null && r.getState() != 3 && isVisual(r)) r.setAggroState(3);
        }
    }

    /** Un recluta para contratar: sin dueño, sin facción, sin ninguna otra marca nuestra (los de la taberna sí). */
    public static boolean isHireable(AbstractRecruitEntity r) {
        if (!r.isAlive() || r.getIsOwned() || r.isPassenger() || com.ale.warbandcraft.ai.Soldiers.isWorker(r)) return false;
        if (isVisual(r)) return true;
        for (String k : r.getPersistentData().getAllKeys()) if (k.startsWith("warbandcraft:")) return false;
        return true;
    }

    /** Lugares adentro de la taberna: piso firme, dos de aire y techo arriba (cache 1 min). */
    private static List<BlockPos> spots(ServerLevel level, VillageData.Village v, long now) {
        CachedSpots c = SPOTS.get(v.center().asLong());
        if (c != null && now - c.at() < 20 * 60) return c.spots();
        List<BlockPos> out = new ArrayList<>();
        TownData.Town t = TownData.get(level).at(v.center());
        BlockPos site = t == null ? null : t.sites.get(Upgrade.TAVERN_1.siteKey());
        if (site != null) {
            for (int dy = 0; dy <= 2 && out.isEmpty(); dy++) {
                for (int dx = 1; dx <= 7; dx++) {
                    for (int dz = 1; dz <= 7; dz++) {
                        BlockPos p = site.offset(dx, 1 + dy, dz);
                        if (com.ale.warbandcraft.util.Ground.canStand(level, p) && !level.canSeeSky(p)
                                && level.getFluidState(p).isEmpty()) out.add(p);
                    }
                }
            }
            BlockPos mid = site.offset(4, 1, 4);
            out.sort(Comparator.comparingDouble(p -> p.distSqr(mid)));
            if (out.size() > 2) out.remove(0);   // el medio es del Tabernero
        }
        SPOTS.put(v.center().asLong(), new CachedSpots(now, out));
        return out;
    }

    private static List<AbstractRecruitEntity> visuals(ServerLevel level, BlockPos center) {
        long key = center.asLong();
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(center).inflate(VillageCamps.MANAGE_RADIUS, 32, VillageCamps.MANAGE_RADIUS),
                r -> r.isAlive() && r.getPersistentData().contains(TAG) && r.getPersistentData().getLong(TAG) == key);
    }

    private static void syncVisuals(ServerLevel level, VillageData.Village v, TownData.Town t) {
        BlockPos place = workplace(level, v);
        if (place == null) return;
        List<AbstractRecruitEntity> seen = visuals(level, v.center());
        int want = t.tavernWaiting.size();
        for (int i = want; i < seen.size(); i++) seen.get(i).discard();
        for (int i = seen.size(); i < want; i++) spawnVisual(level, v.center(), place, t.tavernWaiting.get(i)[0]);
    }

    private static void spawnVisual(ServerLevel level, BlockPos center, BlockPos place, int xpLevel) {
        AbstractRecruitEntity r = create(level, xpLevel);
        if (r == null) return;
        double x = place.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 4;
        double z = place.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 4;
        r.moveTo(x, place.getY(), z, level.random.nextFloat() * 360F, 0F);
        r.setIsOwned(false);
        r.despawnTimer = -1;
        r.setAggroState(3);   // pasivo
        r.setInvulnerable(true);
        r.restrictTo(place, 3);
        r.getPersistentData().putLong(TAG, center.asLong());
        r.getPersistentData().putInt(TAG_LEVEL, xpLevel);
        r.setCustomName(Component.translatable("entity.warbandcraft.tavern_recruit", xpLevel));
        var speed = r.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 0.5);
        level.addFreshEntity(r);
    }

    private static void removeVisuals(ServerLevel level, BlockPos center, int n) {
        List<AbstractRecruitEntity> seen = visuals(level, center);
        for (int i = 0; i < n && i < seen.size(); i++) seen.get(i).discard();
    }

    /** True si es un recluta de los que se ven en una taberna. */
    public static boolean isVisual(Entity e) {
        return e.getPersistentData().contains(TAG);
    }

    /**
     * Click derecho a un recluta de la taberna: si la aldea es tuya, se une a vos (gratis) y te sigue. Devuelve el
     * mensaje (null = no pasó nada).
     */
    @Nullable
    public static Component hire(ServerPlayer player, AbstractRecruitEntity r) {
        ServerLevel level = player.serverLevel();
        BlockPos center = BlockPos.of(r.getPersistentData().getLong(TAG));
        VillageData.Village v = VillageData.get(level).at(center);
        TownData.Town t = TownData.get(level).at(center);
        if (v == null || t == null || !player.getUUID().equals(v.owner())) {
            return Component.translatable("msg.warbandcraft.tavern.not_yours").withStyle(ChatFormatting.RED);
        }
        int lvl = r.getPersistentData().getInt(TAG_LEVEL);
        int[] entry = null;
        for (int[] w : t.tavernWaiting) if (w[0] == lvl) entry = w;
        if (entry == null && !t.tavernWaiting.isEmpty()) entry = t.tavernWaiting.get(0);
        if (entry == null) {
            r.discard();
            return null;
        }
        t.tavernWaiting.remove(entry);
        TownData.get(level).changed();
        r.getPersistentData().remove(TAG);
        r.getPersistentData().remove(TAG_LEVEL);
        r.setInvulnerable(false);
        r.clearRestriction();
        var speed = r.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 2);
        r.setCustomName(null);
        r.setOwnerUUID(Optional.of(player.getUUID()));
        r.setIsOwned(true);
        r.setAggroState(0);
        r.setFollowState(1);
        r.setPersistenceRequired();
        r.updateTeam();
        Barracks.recount(level, player.getUUID());
        WarbandCraft.LOGGER.debug("{} contrató un recluta de la taberna de {}", player.getName().getString(), center);
        return Component.translatable("msg.warbandcraft.tavern.hired").withStyle(ChatFormatting.GREEN);
    }

    // ------------------------------------------------------------------ Gestión → Tropas

    /** Clave de la fila de la taberna en Gestión → Tropas ({@code barracks} = esperando, {@code quarters} = 1 si Auto). */
    public static final String ROW = "tavern";

    /** Las filas de Tropas con la de la taberna arriba (si la aldea tiene taberna). */
    public static List<Quarters.TroopRow> withRow(ServerLevel level, VillageData.Village v, List<Quarters.TroopRow> rows) {
        TownData.Town t = TownData.get(level).at(v.center());
        if (t == null || t.tavernLevel() == 0) return rows;
        List<Quarters.TroopRow> out = new ArrayList<>();
        out.add(new Quarters.TroopRow(ROW, Component.translatable("gui.warbandcraft.tavern.row", t.tavernLevel(),
                Component.translatable(t.innkeeper ? "gui.warbandcraft.yes" : "gui.warbandcraft.no")), t.tavernWaiting.size(), t.autoRecruit ? 1 : 0));
        out.addAll(rows);
        return out;
    }

    /** Gestión → Tropas: los de la taberna al cuartel o al barracón. */
    public static Component send(ServerLevel level, VillageData.Village v, boolean quarters, int amount) {
        TownData.Town t = TownData.get(level).at(v.center());
        if (t == null || t.tavernLevel() == 0) return Component.translatable("msg.warbandcraft.tavern.none").withStyle(ChatFormatting.RED);
        int n = quarters ? toQuarters(level, v, t, amount) : toBarracks(level, v, t, amount);
        return Component.translatable(quarters ? "msg.warbandcraft.tavern.to_quarters" : "msg.warbandcraft.tavern.to_barracks", n)
                .withStyle(n > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
    }

    public static Component setAuto(ServerLevel level, VillageData.Village v, boolean on) {
        TownData.Town t = TownData.get(level).getOrCreate(v.center());
        t.autoRecruit = on;
        TownData.get(level).changed();
        return Component.translatable(on ? "msg.warbandcraft.tavern.auto_on" : "msg.warbandcraft.tavern.auto_off").withStyle(ChatFormatting.GREEN);
    }

    private static Component placeName(ServerLevel level, VillageData.Village v) {
        return VillageCamps.displayName(level, v, Component.literal(VillageCamps.nameFor(v)));
    }
}
