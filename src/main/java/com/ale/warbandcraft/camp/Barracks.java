package com.ale.warbandcraft.camp;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.battle.Surrender;
import com.ale.warbandcraft.kit.ClassAssigner;
import com.ale.warbandcraft.util.Ground;
import com.mojang.datafixers.util.Pair;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Barracones (H8): guardar soldados como fichas en su Campamento y volver a sacarlos, la comida del
 * barracón y la deserción, el guardado automático cuando el dueño se aleja y la vuelta de los guardias.
 * Los datos viven en {@link BarracksData}; acá está la lógica (también la usan los GameTests).
 */
public final class Barracks {
    /** En el soldado: a qué Campamento pertenece (posición como long). */
    public static final String TAG_CAMP = "warbandcraft:camp";
    /** En el soldado: va caminando a guardarse (orden "Al barracón" de la vista aérea). */
    public static final String TAG_TO_BARRACKS = "warbandcraft:to_barracks";

    /** "Guardar a los de cerca": hasta dónde del Campamento (su jurisdicción). */
    public static int STORE_RADIUS = CampBlockEntity.WORK_RADIUS;
    /** Si el dueño está más lejos que esto de un soldado que no lo sigue, el soldado se guarda solo. */
    public static int AUTO_STORE_DISTANCE = 96;
    /** Un guardia guardado vuelve a su puesto cuando el dueño está a menos de esto del puesto. */
    public static int GUARD_RETURN_DISTANCE = 80;
    /** A cuánto de su puesto tiene que estar parado para contar como guardia. */
    public static double POST_TOLERANCE = 3.0;
    /** Al sacarlo a mano: si su puesto está más lejos que esto del Campamento, sale al lado y camina. */
    public static int POST_MAX_FROM_CAMP = 64;
    /** "Al barracón": a qué distancia del Campamento se guarda. */
    public static double ARRIVE_DISTANCE = 4.0;

    /** Estados de órdenes de Recruits. */
    public static final int FOLLOW_OWNER = 1, HOLD = 2, BACK_TO_POS = 3, HOLD_OWNER_POS = 4, PROTECT = 5;

    private Barracks() {}

    /** Cuántos entran en el barracón de {@code camp}: {@link BarracksData#CAPACITY} + cuarteles de aldeas cerca (H12). */
    public static int capacity(ServerLevel level, BlockPos camp) {
        com.ale.warbandcraft.village.VillageData.Village v = com.ale.warbandcraft.village.VillageCamps.villageOf(level, camp);
        if (v != null) return com.ale.warbandcraft.village.VillageCamps.capacity(level, v);   // H16: guarnición de la aldea
        return BarracksData.CAPACITY + com.ale.warbandcraft.upgrade.Upgrades.barracksBonus(level, camp)
                + com.ale.warbandcraft.camp.Training.bonus(com.ale.warbandcraft.camp.Training.level(level, camp));   // H19
    }

    public static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ atar a un Campamento

    /** Ata el soldado al Campamento: ahí se guarda y ahí va a buscar comida (upkeep de Recruits). */
    public static void attach(AbstractRecruitEntity soldier, BlockPos camp) {
        soldier.getPersistentData().putLong(TAG_CAMP, camp.asLong());
        soldier.setUpkeepPos(camp);
    }

    /**
     * El Campamento del soldado en su mundo. Si no tiene (o el suyo ya no existe), se ata al Campamento
     * de su dueño más cercano. Null si el dueño no tiene ninguno en este mundo.
     */
    @Nullable
    public static BlockPos campOf(ServerLevel level, AbstractRecruitEntity soldier) {
        UUID owner = soldier.getOwnerUUID();
        if (owner == null) return null;
        CampRegistry registry = CampRegistry.get(level);
        CompoundTag data = soldier.getPersistentData();
        if (data.contains(TAG_CAMP)) {
            BlockPos pos = BlockPos.of(data.getLong(TAG_CAMP));
            if (registry.isCampOf(owner, pos)) return pos;
        }
        BlockPos nearest = registry.nearestCamp(owner, soldier.blockPosition());
        if (nearest != null) attach(soldier, nearest);
        return nearest;
    }

    // ------------------------------------------------------------------ guardar

    public enum StoreResult { OK, FULL, FIGHTING, NOT_YOURS }

    /** Peleando: tiene a quién pegarle o le pegaron hace poco (Recruits/vanilla lo olvidan a los 5 s). */
    public static boolean isFighting(AbstractRecruitEntity soldier) {
        LivingEntity target = soldier.getTarget();
        return (target != null && target.isAlive()) || soldier.getLastHurtByMob() != null;
    }

    /** Te sigue (o te protege): va con vos, no se guarda solo. */
    public static boolean isWithOwner(AbstractRecruitEntity soldier) {
        int s = soldier.getFollowState();
        return s == FOLLOW_OWNER || s == PROTECT;
    }

    /** Guardia: tiene orden de quedarse en un lugar y ya está parado ahí (si todavía camina, no). */
    public static boolean isGuard(AbstractRecruitEntity soldier) {
        int s = soldier.getFollowState();
        if (s != HOLD && s != BACK_TO_POS && s != HOLD_OWNER_POS) return false;
        Vec3 post = soldier.getHoldPos();
        return post != null && post.distanceToSqr(soldier.position()) <= POST_TOLERANCE * POST_TOLERANCE;
    }

    /**
     * Guarda al soldado (y su caballo) en el barracón de {@code camp}: escribe la ficha y lo saca del mundo.
     * Con {@code notGuard} no cuenta como guardia aunque esté en su puesto ("Al barracón").
     */
    public static StoreResult store(ServerLevel level, AbstractRecruitEntity soldier, BlockPos camp, boolean notGuard) {
        UUID owner = soldier.getOwnerUUID();
        if (owner == null || !soldier.isAlive() || Soldiers.isWorker(soldier)) return StoreResult.NOT_YOURS;
        if (isFighting(soldier) || Surrender.is(soldier)) return StoreResult.FIGHTING;
        BarracksData data = BarracksData.get(level);
        // Del cuartel de una aldea: vuelve a su lista (no ocupa lugar del barracón, ni es guardia con puesto)
        boolean quarters = com.ale.warbandcraft.village.Quarters.belongsTo(level, soldier, camp);
        if (quarters) notGuard = true;
        else if (data.count(camp) >= capacity(level, camp)) return StoreResult.FULL;

        boolean guard = !notGuard && isGuard(soldier);
        Vec3 post = guard ? soldier.getHoldPos() : null;
        int follow = soldier.getFollowState();

        AbstractHorse horse = soldier.getVehicle() instanceof AbstractHorse h ? h : null;
        if (soldier.isPassenger()) soldier.stopRiding();
        CompoundTag horseTag = null;
        if (horse != null) {
            horseTag = save(horse);
            horseTag.remove("Passengers");
        }

        attach(soldier, camp);
        soldier.getPersistentData().remove(TAG_TO_BARRACKS);
        CompoundTag tag = save(soldier);
        tag.remove("Passengers");

        CompoundTag pd = soldier.getPersistentData();
        ResourceLocation kit = pd.contains(ClassAssigner.TAG_CLASS) ? ResourceLocation.tryParse(pd.getString(ClassAssigner.TAG_CLASS)) : null;
        BarracksData.Soldier sheet = new BarracksData.Soldier(tag, horseTag, guard, post, soldier.getYRot(), follow,
                soldier.getName().getString(), kit, pd.getInt(ClassAssigner.TAG_TIER), soldier.getXpLevel());

        BarracksData.Barracks b = data.getOrCreate(camp, owner);
        if (b.eaters() == 0) {   // barracón vacío: empieza a comer mañana
            b.lastFedDay = today(level);
            b.hungryDays = 0;
        }
        (quarters ? b.quarters : b.soldiers).add(sheet);
        data.changed();

        soldier.discard();
        if (horse != null) horse.discard();
        recount(level, owner);
        return StoreResult.OK;
    }

    /** "Guardar a los de cerca": tus soldados a {@link #STORE_RADIUS} bloques que no estén peleando. */
    public static int storeNear(ServerLevel level, BlockPos camp, UUID owner) {
        int stored = 0;
        for (AbstractRecruitEntity s : storableNear(level, camp, owner)) {
            StoreResult r = store(level, s, camp, false);
            if (r == StoreResult.FULL) break;
            if (r == StoreResult.OK) stored++;
        }
        return stored;
    }

    public static List<AbstractRecruitEntity> storableNear(ServerLevel level, BlockPos camp, UUID owner) {
        return level.getEntitiesOfClass(AbstractRecruitEntity.class, CampBlockEntity.area(level, camp),   // H16: aldea = su territorio
                r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), owner) && !isFighting(r) && !Surrender.is(r) && Soldiers.isSoldier(r)
                        && CampBlockEntity.covers(level, camp, r.blockPosition()));
    }

    // ------------------------------------------------------------------ sacar

    /** Dónde aparece un soldado que sale. */
    public enum Where {
        /** En su puesto (guardias) o al lado del Campamento siguiéndote (el resto). Botones de la pestaña. */
        MANUAL,
        /** En su puesto, porque el dueño pasó cerca (vuelta automática de un guardia). */
        AT_POST,
        /** El Campamento se rompió: guardias a su puesto, el resto quieto ahí mismo. */
        CAMP_BROKEN
    }

    /**
     * Saca hasta {@code amount} soldados de la fila {@code rowKey} (null = cualquiera), primero los que no
     * tienen puesto. Devuelve cuántos salieron.
     */
    public static int deploy(ServerLevel level, BlockPos camp, @Nullable String rowKey, int amount) {
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        if (b == null) return 0;
        List<BarracksData.Soldier> pick = new ArrayList<>();
        for (boolean guards : new boolean[]{false, true}) {
            for (BarracksData.Soldier s : b.soldiers) {
                if (pick.size() >= amount) break;
                if (s.guard == guards && (rowKey == null || rowKey.equals(s.rowKey()))) pick.add(s);
            }
        }
        int out = 0;
        for (BarracksData.Soldier s : pick) if (spawn(level, camp, b, s, Where.MANUAL) != null) out++;
        return out;
    }

    /** "Guardias a sus puestos": saca a todos los que tienen puesto. */
    public static int deployGuards(ServerLevel level, BlockPos camp) {
        BarracksData.Barracks b = BarracksData.get(level).at(camp);
        if (b == null) return 0;
        int out = 0;
        for (BarracksData.Soldier s : new ArrayList<>(b.soldiers)) {
            if (s.guard && spawn(level, camp, b, s, Where.MANUAL) != null) out++;
        }
        return out;
    }

    /**
     * Crea al soldado desde su ficha y lo saca del barracón. Si la ficha está rota (p. ej. se quitó un
     * mod), se descarta con un aviso en el log y devuelve null.
     */
    @Nullable
    public static AbstractRecruitEntity spawn(ServerLevel level, BlockPos camp, BarracksData.Barracks b,
                                              BarracksData.Soldier sheet, Where where) {
        b.soldiers.remove(sheet);
        BarracksData.get(level).changed();

        boolean toPost = sheet.guard && sheet.post != null && switch (where) {
            case AT_POST -> true;
            case MANUAL -> sheet.post.distanceTo(Vec3.atCenterOf(camp)) <= POST_MAX_FROM_CAMP;
            case CAMP_BROKEN -> level.isLoaded(BlockPos.containing(sheet.post));
        };
        Vec3 at = toPost ? standable(level, BlockPos.containing(sheet.post), sheet.post, camp) : standable(level, camp, null, camp);
        int follow = sheet.guard && sheet.post != null ? -1 : where == Where.CAMP_BROKEN ? HOLD : FOLLOW_OWNER;
        return create(level, sheet, at, camp, follow, b.hungryDays);
    }

    /**
     * Crea en {@code at} al soldado de la ficha (con su caballo) y lo ata a {@code camp} (null = a ninguno).
     * {@code follow} -1 = guardia: vuelve a su puesto con su orden; si no, esa orden de Recruits.
     */
    @Nullable
    public static AbstractRecruitEntity create(ServerLevel level, BarracksData.Soldier sheet, Vec3 at, @Nullable BlockPos camp,
                                               int follow, int hungryDays) {
        Entity created = EntityType.loadEntityRecursive(sheet.entity, level, e -> {
            e.moveTo(at.x, at.y, at.z, sheet.yRot, 0.0F);
            return e;
        });
        if (!(created instanceof AbstractRecruitEntity soldier)) {
            WarbandCraft.LOGGER.warn("Ficha de {} (Campamento {}): no se pudo recrear", sheet.name, camp);
            if (created != null) created.discard();
            return null;
        }
        soldier.setYHeadRot(sheet.yRot);
        soldier.setYBodyRot(sheet.yRot);
        addWithFreeUuid(level, soldier);

        // Órdenes: el guardia sigue con la suya (y va a su puesto aunque haya salido lejos)
        soldier.setTarget(null);
        if (follow < 0 && sheet.post != null) {
            soldier.setFollowState(sheet.followState); // el estado 2 pisa el puesto con la posición actual...
            soldier.setHoldPos(sheet.post);            // ...así que el puesto va después
        } else {
            soldier.setFollowState(Math.max(follow, 0));
        }

        // Comida: si el barracón venía comido salen llenos; si no, desanimados
        if (hungryDays == 0) {
            soldier.setHunger(100.0F);
            soldier.setMoral(100.0F);
        } else {
            soldier.setMoral(Math.max(0.0F, 40.0F - 10.0F * hungryDays));
        }

        if (sheet.horse != null) {
            Entity horse = EntityType.loadEntityRecursive(sheet.horse, level, e -> {
                e.moveTo(at.x, at.y, at.z, sheet.yRot, 0.0F);
                return e;
            });
            if (horse != null) {
                addWithFreeUuid(level, horse);
                soldier.startRiding(horse, true);
            }
        }

        if (camp != null) attach(soldier, camp);
        try {
            soldier.updateTeam();
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudo actualizar el equipo de Recruits al sacar a {}: {}", sheet.name, e.toString());
        }
        if (soldier.getOwnerUUID() != null) recount(level, soldier.getOwnerUUID());
        return soldier;
    }

    /** Lo agrega al mundo; si su UUID ya está en uso (no debería), le da uno nuevo. */
    static void addWithFreeUuid(ServerLevel level, Entity entity) {
        if (level.getEntity(entity.getUUID()) != null) entity.setUUID(Mth.createInsecureUUID(level.random));
        if (!level.addFreshEntity(entity)) {
            entity.setUUID(Mth.createInsecureUUID(level.random));
            level.addFreshEntity(entity);
        }
    }

    /**
     * Un lugar donde pararse: {@code exact} si está libre (el puesto tal cual), si no el bloque libre más
     * cercano a {@code center} (hasta 4 bloques), y si no, al lado del Campamento.
     */
    public static Vec3 standable(ServerLevel level, BlockPos center, @Nullable Vec3 exact, BlockPos camp) {
        if (exact != null && Ground.canStand(level, center)) return exact;
        Vec3 near = Ground.nearestStandable(level, center, 4);
        if (near != null) return near;
        Vec3 byCamp = Ground.nearestStandable(level, camp, 4);
        return byCamp != null ? byCamp : Vec3.atBottomCenterOf(camp.above());
    }

    // ------------------------------------------------------------------ comida y deserción

    /** Una comida que sirve para el barracón: alimenta y no envenena (nada de carne podrida ni ojo de araña). */
    @Nullable
    public static FoodProperties food(ItemStack stack) {
        FoodProperties props = stack.getItem().getFoodProperties(stack, null);
        if (props == null || props.getNutrition() <= 0) return null;
        for (Pair<MobEffectInstance, Float> effect : props.getEffects()) {
            if (effect.getFirst().getEffect().getCategory() == MobEffectCategory.HARMFUL) return null;
        }
        return props;
    }

    /** Puntos de comida que hay en el almacén del Campamento (cofre + vinculados). */
    public static int foodPoints(CampBlockEntity camp) {
        int[] total = {0};
        camp.forEachStack(stack -> {
            FoodProperties f = food(stack);
            if (f != null) total[0] += f.getNutrition() * stack.getCount();
        });
        return total[0];
    }

    /** Para cuántos días alcanza la comida del almacén (-1 = el barracón está vacío). */
    public static int foodDays(CampBlockEntity camp, @Nullable BarracksData.Barracks b) {
        if (b == null || b.eaters() == 0) return -1;
        return (foodPoints(camp) + b.foodCredit) / (b.eaters() * BarracksData.FOOD_PER_SOLDIER);
    }

    /** Saca comida del almacén (la más barata primero) hasta juntar {@code points}. Devuelve cuántos juntó. */
    public static int takeFood(CampBlockEntity camp, int points) {
        Map<Item, Integer> nutrition = new LinkedHashMap<>();
        camp.forEachStack(stack -> {
            FoodProperties f = food(stack);
            if (f != null) nutrition.putIfAbsent(stack.getItem(), f.getNutrition());
        });
        List<Map.Entry<Item, Integer>> order = new ArrayList<>(nutrition.entrySet());
        order.sort(Map.Entry.comparingByValue());
        int got = 0;
        for (Map.Entry<Item, Integer> e : order) {
            while (got < points && camp.take(e.getKey(), 1) == 1) got += e.getValue();
            if (got >= points) break;
        }
        return got;
    }

    /**
     * Cobra la comida de los días que pasaron desde la última vez (hasta {@link BarracksData#MAX_CATCH_UP_DAYS}).
     * Sin comida: el 1er día avisa; desde el {@link BarracksData#HUNGRY_DAYS_BEFORE_DESERTING}º se va el 10%
     * (mínimo 1) cada día, empezando por los de menor nivel. Devuelve cuántos desertaron.
     */
    public static int feed(ServerLevel level, BlockPos campPos, CampBlockEntity camp, long today) {
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(campPos);
        if (b == null || b.eaters() == 0) return 0;
        if (b.lastFedDay < 0) {
            b.lastFedDay = today;
            data.changed();
            return 0;
        }
        long days = Math.min(today - b.lastFedDay, BarracksData.MAX_CATCH_UP_DAYS);
        if (days <= 0) return 0;

        int deserted = 0;
        boolean warned = false;
        int trainingLevel = Training.level(level, campPos);   // H19
        for (int d = 0; d < days && b.eaters() > 0; d++) {
            boolean training = trainingLevel > 0 && Training.anyToTrain(b, trainingLevel);
            int need = b.eaters() * BarracksData.FOOD_PER_SOLDIER * (training ? Training.FOOD_FACTOR : 1)
                    + com.ale.warbandcraft.army.Armies.outFrom(level, campPos) * BarracksData.FOOD_PER_SOLDIER;   // H20: los que están en un ejército
            int have = b.foodCredit;
            if (have < need) have += takeFood(camp, need - have);
            if (have >= need) {
                b.foodCredit = have - need;
                b.hungryDays = 0;
                if (training) Training.train(level, b, trainingLevel);   // H19: comieron y entrenaron
                continue;
            }
            b.foodCredit = 0;
            b.hungryDays++;
            if (b.hungryDays >= BarracksData.HUNGRY_DAYS_BEFORE_DESERTING) {
                deserted += desert(b);
            } else {
                warned = true;
            }
        }
        b.lastFedDay = today;
        data.changed();

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(b.owner);
        if (owner != null) {
            Component name = CampRegistry.get(level).displayName(campPos);
            if (deserted > 0) {
                owner.sendSystemMessage(Component.translatable("msg.warbandcraft.barracks.deserted", deserted, name).withStyle(ChatFormatting.RED));
            } else if (warned) {
                owner.sendSystemMessage(Component.translatable("msg.warbandcraft.barracks.hungry", name).withStyle(ChatFormatting.GOLD));
            }
        }
        return deserted;
    }

    /** Se va el 10% (mínimo 1), los de menor nivel primero (del barracón y del cuartel). */
    private static int desert(BarracksData.Barracks b) {
        int n = Math.max(1, (int) Math.ceil(b.eaters() * BarracksData.DESERT_FRACTION));
        n = Math.min(n, b.eaters());
        List<BarracksData.Soldier> byLevel = new ArrayList<>(b.soldiers);
        byLevel.addAll(b.quarters);
        byLevel.sort(Comparator.comparingInt(s -> s.xpLevel));
        for (int i = 0; i < n; i++) {
            if (!b.soldiers.remove(byLevel.get(i))) b.quarters.remove(byLevel.get(i));
        }
        return n;
    }

    // ------------------------------------------------------------------ automático

    /**
     * Guardado automático de un soldado: si no te sigue, no pelea y su dueño está lejos ({@code ownerPos}
     * null = desconectado o en otro mundo), se guarda en su Campamento. Devuelve null si no corresponde.
     */
    @Nullable
    public static StoreResult autoStore(ServerLevel level, AbstractRecruitEntity soldier, @Nullable Vec3 ownerPos) {
        if (soldier.getOwnerUUID() == null || !soldier.isAlive() || Soldiers.isWorker(soldier)) return null;
        if (isWithOwner(soldier) || isFighting(soldier)) return null;
        if (com.ale.warbandcraft.army.Armies.inArmy(soldier)) return null;   // H20: el ejército se guarda solo
        if (soldier.isPassenger() && !(soldier.getVehicle() instanceof AbstractHorse)) return null;
        BlockPos quarters = com.ale.warbandcraft.village.Quarters.villageOf(soldier);
        if (quarters != null) {   // del cuartel: se guarda cuando el dueño se aleja de la aldea, no del soldado
            if (!com.ale.warbandcraft.village.Quarters.ownerAway(ownerPos, quarters)) return null;
        } else if (ownerPos != null && ownerPos.distanceToSqr(soldier.position()) <= (double) AUTO_STORE_DISTANCE * AUTO_STORE_DISTANCE) return null;
        BlockPos camp = campOf(level, soldier);
        if (camp == null) return null;
        return store(level, soldier, camp, false);
    }

    /** Los guardias guardados de {@code owner} cuyo puesto quedó cerca de {@code ownerPos} vuelven a su puesto. */
    public static int returnGuards(ServerLevel level, UUID owner, Vec3 ownerPos) {
        int out = 0;
        BarracksData data = BarracksData.get(level);
        for (Map.Entry<Long, BarracksData.Barracks> e : new ArrayList<>(data.all().entrySet())) {
            BarracksData.Barracks b = e.getValue();
            if (!owner.equals(b.owner)) continue;
            BlockPos camp = BlockPos.of(e.getKey());
            for (BarracksData.Soldier s : new ArrayList<>(b.soldiers)) {
                if (!s.guard || s.post == null) continue;
                if (s.post.distanceToSqr(ownerPos) >= (double) GUARD_RETURN_DISTANCE * GUARD_RETURN_DISTANCE) continue;
                if (!level.isLoaded(BlockPos.containing(s.post))) continue;
                if (spawn(level, camp, b, s, Where.AT_POST) != null) out++;
            }
        }
        return out;
    }

    /**
     * Orden "Al barracón": camina a un Campamento y se guarda al llegar. {@code target} null = al suyo;
     * si no, a ese (tiene que ser de su dueño) y pasa a pertenecerle.
     */
    public static boolean sendHome(ServerLevel level, AbstractRecruitEntity soldier, @Nullable BlockPos target) {
        if (Soldiers.isWorker(soldier)) return false;
        BlockPos camp;
        if (target != null) {
            if (soldier.getOwnerUUID() == null || !CampRegistry.get(level).isCampOf(soldier.getOwnerUUID(), target)) return false;
            attach(soldier, target);
            camp = target;
        } else {
            camp = campOf(level, soldier);
        }
        if (camp == null) return false;
        soldier.getPersistentData().putBoolean(TAG_TO_BARRACKS, true);
        soldier.setTarget(null);
        soldier.setFollowState(BACK_TO_POS);
        soldier.setHoldPos(Vec3.atBottomCenterOf(camp));
        return true;
    }

    /** Los que iban al barracón y ya llegaron, se guardan. */
    public static void checkArrivals(ServerLevel level) {
        for (AbstractRecruitEntity s : level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getPersistentData().getBoolean(TAG_TO_BARRACKS))) {
            BlockPos camp = campOf(level, s);
            if (camp == null) {
                s.getPersistentData().remove(TAG_TO_BARRACKS);
                continue;
            }
            if (s.position().distanceToSqr(Vec3.atBottomCenterOf(camp)) > ARRIVE_DISTANCE * ARRIVE_DISTANCE) continue;
            StoreResult r = store(level, s, camp, true);
            if (r == StoreResult.FULL) {
                s.getPersistentData().remove(TAG_TO_BARRACKS);
                tellOwner(level, s.getOwnerUUID(), Component.translatable("msg.warbandcraft.barracks.full",
                        CampRegistry.get(level).displayName(camp)).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** Revisión de cada 10 s: comida de los Campamentos cargados, guardado automático y vuelta de guardias. */
    public static void tick(ServerLevel level) {
        long today = today(level);
        BarracksData data = BarracksData.get(level);
        for (Long pos : new ArrayList<>(data.all().keySet())) {
            BlockPos campPos = BlockPos.of(pos);
            if (level.isLoaded(campPos) && level.getBlockEntity(campPos) instanceof CampBlockEntity camp) {
                feed(level, campPos, camp, today);
            }
        }

        List<? extends AbstractRecruitEntity> owned = level.getEntities(EntityTypeTest.forClass(AbstractRecruitEntity.class),
                r -> r.isAlive() && r.getOwnerUUID() != null);
        java.util.Set<Long> warnedFull = new java.util.HashSet<>();
        for (AbstractRecruitEntity s : owned) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(s.getOwnerUUID());
            Vec3 ownerPos = owner != null && owner.level() == level ? owner.position() : null;
            StoreResult r = autoStore(level, s, ownerPos);
            if (r == StoreResult.FULL) {
                BlockPos camp = campOf(level, s);
                if (camp != null && warnedFull.add(camp.asLong()) && owner != null && FULL_WARNED.add(camp.asLong())) {
                    owner.sendSystemMessage(Component.translatable("msg.warbandcraft.barracks.full",
                            CampRegistry.get(level).displayName(camp)).withStyle(ChatFormatting.RED));
                }
            }
        }
        FULL_WARNED.removeIf(p -> data.count(BlockPos.of(p)) < capacity(level, BlockPos.of(p)));

        for (ServerPlayer player : level.players()) returnGuards(level, player.getUUID(), player.position());
    }

    /** Campamentos llenos que ya avisaron (para no repetir el aviso cada 10 s). */
    private static final java.util.Set<Long> FULL_WARNED = new java.util.HashSet<>();

    // ------------------------------------------------------------------ Campamento roto

    /**
     * Se rompió un Campamento con gente adentro: salen todos (guardias a su puesto, el resto quieto ahí) y se
     * atan al Campamento más cercano de su dueño que quede, o a ninguno.
     */
    public static int campBroken(ServerLevel level, BlockPos camp, UUID owner) {
        BarracksData data = BarracksData.get(level);
        BarracksData.Barracks b = data.at(camp);
        if (b == null) return 0;
        b.soldiers.addAll(b.quarters);   // el cuartel sale igual que el resto
        b.quarters.clear();
        BlockPos next = CampRegistry.get(level).nearestCamp(owner, camp);
        int out = 0;
        for (Iterator<BarracksData.Soldier> it = new ArrayList<>(b.soldiers).iterator(); it.hasNext(); ) {
            AbstractRecruitEntity s = spawn(level, camp, b, it.next(), Where.CAMP_BROKEN);
            if (s == null) continue;
            out++;
            if (next != null) {
                attach(s, next);
            } else {
                s.getPersistentData().remove(TAG_CAMP);
                s.clearUpkeepPos();
            }
        }
        // Los prisioneros (H9) pasan al Campamento más cercano que quede; si no hay lugar, se escapan
        if (next != null && !b.prisoners.isEmpty()) {
            BarracksData.Barracks to = data.getOrCreate(next, owner);
            for (BarracksData.Prisoner p : b.prisoners) {
                if (to.prisoners.size() < BarracksData.PRISON_CAPACITY) to.prisoners.add(p);
            }
        }
        data.remove(camp);
        return out;
    }

    // ------------------------------------------------------------------ ayudas

    public static CompoundTag save(Entity entity) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation id = EntityType.getKey(entity.getType());
        tag.putString("id", id.toString());
        entity.saveWithoutId(tag);
        return tag;
    }

    public static void recount(ServerLevel level, UUID owner) {
        try {
            if (RecruitEvents.recruitsPlayerUnitManager != null) {
                RecruitEvents.recruitsPlayerUnitManager.recountRecruits(level.getServer(), owner);
            }
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudo actualizar el conteo de Recruits: {}", e.toString());
        }
    }

    private static void tellOwner(ServerLevel level, @Nullable UUID owner, Component msg) {
        if (owner == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) p.sendSystemMessage(msg);
    }
}
