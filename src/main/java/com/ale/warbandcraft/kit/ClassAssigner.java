package com.ale.warbandcraft.kit;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.Barracks;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampFinder;
import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.faction.FactionStyle;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.talhanation.recruits.RecruitEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Asigna una clase a un soldado de Recruits y lo arma con recursos del Campamento.
 *
 * Pasos:
 * 1. Busca un Campamento del jugador a menos de 24 bloques del soldado.
 * 2. Elige el mejor nivel de equipo que el soldado puede usar (por experiencia) y el Campamento puede pagar.
 *    El equipo viejo del soldado cuenta como recurso (se devuelve al cofre).
 * 3. Si la clase usa otra entidad de Recruits (p. ej. arquero = recruits:bowman), convierte al soldado
 *    copiando sus datos (dueño, grupo, nivel, nombre...).
 * 4. Lo equipa, guarda munición en su inventario y, si corresponde, le da un caballo.
 * 5. Le da la orden de seguir al jugador.
 */
public final class ClassAssigner {
    public static final String TAG_CLASS = "warbandcraft:class";
    public static final String TAG_TIER = "warbandcraft:tier";
    /** Versión del kit con que se armó (H6): si el jugador cambia el kit, se re-arma solo. */
    public static final String TAG_KIT_VERSION = "warbandcraft:kit_version";
    /** En caballos dados por el Campamento: el UUID del jugador (para la doctrina de Caballería). */
    public static final String TAG_HORSE_OWNER = "warbandcraft:owner";

    /** Espacios 0-5 del inventario de Recruits son el equipo puesto; la mochila empieza en 6. */
    private static final int FIRST_BACKPACK_SLOT = 6;
    /** Estados de órdenes de Recruits: 0 libre, 1 seguir al dueño, 2 mantener posición... */
    public static final int FOLLOW_OWNER = 1;

    private ClassAssigner() {}

    /**
     * Resultado de una asignación. {@code soldier} es el soldado final (puede ser una entidad nueva
     * si hubo conversión) o null si falló. {@code message} es lo que se le muestra al jugador.
     */
    public record Result(boolean ok, Component message, @Nullable AbstractRecruitEntity soldier) {
        static Result fail(Component msg) {
            return new Result(false, msg.copy().withStyle(ChatFormatting.RED), null);
        }
    }

    /** Versión para jugadores: hace la asignación y le muestra el mensaje en el chat. */
    public static Result assign(ServerPlayer player, AbstractRecruitEntity recruit, ResourceLocation kitId) {
        Result result = assign(player.serverLevel(), player.getUUID(), recruit, kitId);
        player.sendSystemMessage(result.message());
        return result;
    }

    /** Lógica pura (sin jugador real): la usan el juego y los GameTests. */
    public static Result assign(ServerLevel level, UUID playerId, AbstractRecruitEntity recruit, ResourceLocation kitId) {
        if (!Objects.equals(recruit.getOwnerUUID(), playerId)) {
            return Result.fail(Component.translatable("msg.warbandcraft.not_yours"));
        }
        // H6: la clase como la ve este jugador (nombre propio y kits propios encima del JSON)
        ClassKit kit = PlayerKits.resolve(level.getServer(), playerId, kitId);
        if (kit == null) {
            return Result.fail(Component.translatable("msg.warbandcraft.unknown_class", kitId.toString()));
        }
        CampBlockEntity camp = CampFinder.findNearest(level, recruit.blockPosition(), playerId);
        if (camp == null) {
            return Result.fail(Component.translatable("msg.warbandcraft.no_camp", CampFinder.RADIUS));
        }

        Map<Item, Integer> pool = pool(camp, recruit);
        int xpLevel = recruit.getXpLevel();
        List<ClassKit.Tier> tiers = kit.tiers();
        ClassKit.Tier chosen = null;
        int chosenIndex = -1;
        for (int i = tiers.size() - 1; i >= 0; i--) {
            ClassKit.Tier t = tiers.get(i);
            if (t.valid() && xpLevel >= t.minLevel() && canAfford(pool, t.cost(), i == 0)) {
                chosen = t;
                chosenIndex = i;
                break;
            }
        }
        if (chosen == null) {
            ClassKit.Tier base = kit.firstValidTier();
            if (base == null) {
                return Result.fail(Component.translatable("msg.warbandcraft.class_unavailable", kit.name()));
            }
            return Result.fail(Component.translatable("msg.warbandcraft.missing", kit.name(), missingList(pool, base.cost(), true)));
        }

        AbstractRecruitEntity soldier = outfit(level, playerId, camp, recruit, kit, chosen, chosenIndex);

        // Recruits deja a los recién contratados en "mantener posición": armado, el soldado pasa a seguirte.
        soldier.setFollowState(FOLLOW_OWNER);

        level.playSound(null, soldier.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.NEUTRAL, 1.0F, 1.0F);
        Component msg = Component.translatable("msg.warbandcraft.assigned",
                soldier.getDisplayName(),
                Component.literal(kit.name()).withStyle(ChatFormatting.GOLD),
                Component.literal(chosen.name()).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.GREEN);
        return new Result(true, msg, soldier);
    }

    /** Resultado del re-armado automático (H6). */
    public enum Rearm { DONE, WAITING, SKIP }

    /**
     * Re-arma a un soldado ya armado con el kit actual de su clase y su mismo nivel (H6: el jugador
     * cambió el kit). No le cambia las órdenes ni el nivel; si el Campamento no alcanza, no toca nada.
     */
    public static Rearm rearm(ServerLevel level, UUID playerId, AbstractRecruitEntity soldier) {
        if (!Objects.equals(soldier.getOwnerUUID(), playerId)) return Rearm.SKIP;
        ResourceLocation kitId = ResourceLocation.tryParse(soldier.getPersistentData().getString(TAG_CLASS));
        if (kitId == null) return Rearm.SKIP;
        ClassKit kit = PlayerKits.resolve(level.getServer(), playerId, kitId);
        int index = soldier.getPersistentData().getInt(TAG_TIER);
        if (kit == null || index < 0 || index >= kit.tiers().size() || !kit.tiers().get(index).valid()) return Rearm.SKIP;
        ClassKit.Tier tier = kit.tiers().get(index);

        CampBlockEntity camp = CampFinder.findNearest(level, soldier.blockPosition(), playerId);
        if (camp == null || !canAfford(pool(camp, soldier), tier.cost(), index == 0)) return Rearm.WAITING;

        AbstractRecruitEntity result = outfit(level, playerId, camp, soldier, kit, tier, index);
        level.playSound(null, result.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.NEUTRAL, 0.6F, 1.1F);
        return Rearm.DONE;
    }

    /**
     * Devuelve el equipo viejo al Campamento, cobra el nivel, convierte a la entidad de la clase si hace
     * falta y lo equipa (con los colores de tu facción y tu doctrina). Ya se sabe que alcanza.
     */
    private static AbstractRecruitEntity outfit(ServerLevel level, UUID playerId, CampBlockEntity camp,
                                                AbstractRecruitEntity recruit, ClassKit kit, ClassKit.Tier tier, int tierIndex) {
        // 1) Devolver el equipo viejo al Campamento
        List<ItemStack> oldGear = stripRecruit(recruit);
        BlockPos campPos = camp.getBlockPos();
        for (ItemStack stack : oldGear) {
            ItemStack rest = camp.insert(stack);
            if (!rest.isEmpty()) {
                Containers.dropItemStack(level, campPos.getX() + 0.5, campPos.getY() + 1.1, campPos.getZ() + 0.5, rest);
            }
        }

        // 2) Cobrar el nivel elegido (H10: lo que falte de metal, en armamento)
        int unpaid = Armament.INSTANCE.pay(camp, tier.cost(), tierIndex == 0);   // H19: el básico, también con solo armamento
        if (unpaid > 0) {
            // Solo pasa si el almacén estaba lleno y parte del equipo viejo cayó al piso.
            WarbandCraft.LOGGER.warn("Campamento en {}: faltaron {} de armamento", campPos, unpaid);
        }

        // 3) Convertir a la entidad de la clase si hace falta
        AbstractRecruitEntity soldier = convertIfNeeded(level, recruit, kit.entity());

        // 4) Equipar
        for (Map.Entry<EquipmentSlot, Item> e : tier.equipment().entrySet()) {
            soldier.setItemSlot(e.getKey(), new ItemStack(e.getValue()));
        }
        SimpleContainer soldierInv = soldier.getInventory();
        for (ItemStack stack : tier.inventory()) {
            ItemStack rest = putInBackpack(soldierInv, stack.copy());
            if (!rest.isEmpty()) camp.insert(rest);
        }
        if (tier.horse() && !(soldier.getVehicle() instanceof AbstractHorse)) giveHorse(level, playerId, soldier);

        // H6: los colores y el estandarte de tu facción de Recruits (si tenés una) y tu doctrina
        FactionStyle style = FactionStyle.forSoldier(soldier);
        if (style != null) style.applyTo(soldier);

        CompoundTag data = soldier.getPersistentData();
        data.putString(TAG_CLASS, kit.id().toString());
        data.putInt(TAG_TIER, tierIndex);
        data.putInt(TAG_KIT_VERSION, PlayerFactionData.get(level.getServer()).kitVersion(playerId, kit.id()));
        Doctrine.apply(soldier, PlayerFactionData.get(level.getServer()).doctrine(playerId));
        Barracks.attach(soldier, campPos); // H8: pertenece al Campamento que pagó su equipo
        return soldier;
    }

    /**
     * Equipa gratis (sin Campamento ni materiales) a un soldado tuyo que ya es de la entidad de la clase:
     * lo usan los ejércitos de prueba de {@code /warband prueba mios}.
     */
    public static void equipFree(ServerLevel level, UUID playerId, AbstractRecruitEntity soldier, ClassKit kit, int tierIndex) {
        ClassKit.Tier tier = kit.tiers().get(tierIndex);
        for (Map.Entry<EquipmentSlot, Item> e : tier.equipment().entrySet()) {
            soldier.setItemSlot(e.getKey(), new ItemStack(e.getValue()));
        }
        for (ItemStack stack : tier.inventory()) putInBackpack(soldier.getInventory(), stack.copy());
        if (tier.horse() && !(soldier.getVehicle() instanceof AbstractHorse)) giveHorse(level, playerId, soldier);

        FactionStyle style = FactionStyle.forSoldier(soldier);
        if (style != null) style.applyTo(soldier);
        CompoundTag data = soldier.getPersistentData();
        data.putString(TAG_CLASS, kit.id().toString());
        data.putInt(TAG_TIER, tierIndex);
        data.putInt(TAG_KIT_VERSION, PlayerFactionData.get(level.getServer()).kitVersion(playerId, kit.id()));
        Doctrine.apply(soldier, PlayerFactionData.get(level.getServer()).doctrine(playerId));
        soldier.setFollowState(FOLLOW_OWNER);
    }

    /** Recursos disponibles = almacén (cofre + cofres vinculados) + lo que el soldado ya tiene encima. */
    private static Map<Item, Integer> pool(CampBlockEntity camp, AbstractRecruitEntity recruit) {
        Map<Item, Integer> pool = new HashMap<>();
        camp.forEachStack(stack -> addTo(pool, stack));
        SimpleContainer inv = recruit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) addTo(pool, inv.getItem(i));
        return pool;
    }

    // ---------------------------------------------------------------------

    private static void addTo(Map<Item, Integer> pool, ItemStack stack) {
        if (!stack.isEmpty()) pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    /** Alcanza con piezas de verdad o, lo que falte de metal, con armamento (H10). */
    private static boolean canAfford(Map<Item, Integer> pool, Map<Item, Integer> cost, boolean basic) {
        return Armament.INSTANCE.plan(pool, cost, basic).ok();
    }

    /** "2x Lingote de hierro (o 2 de armamento), 1x Arco" y cuánto armamento hay, si alguna pieza se podía cubrir. */
    private static MutableComponent missingList(Map<Item, Integer> pool, Map<Item, Integer> cost, boolean basic) {
        Armament.Plan plan = Armament.INSTANCE.plan(pool, cost, basic);
        MutableComponent out = Component.empty();
        boolean first = true;
        boolean coverable = false;
        for (Map.Entry<Item, Integer> e : plan.shortfall().entrySet()) {
            if (!first) out.append(", ");
            out.append(e.getValue() + "x ").append(new ItemStack(e.getKey()).getHoverName());
            int v = Armament.INSTANCE.cost(e.getKey(), e.getValue(), basic);
            if (v > 0) {
                out.append(Component.translatable("msg.warbandcraft.armament.or", v));
                coverable = true;
            }
            first = false;
        }
        if (coverable) out.append(Component.translatable("msg.warbandcraft.armament.have", plan.armamentAvailable()));
        return out.withStyle(ChatFormatting.WHITE);
    }

    /** Saca todo lo que el soldado tiene (equipo + mochila) y lo devuelve. */
    private static List<ItemStack> stripRecruit(AbstractRecruitEntity recruit) {
        List<ItemStack> out = new ArrayList<>();
        SimpleContainer inv = recruit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.removeItemNoUpdate(i);
            if (!stack.isEmpty()) out.add(stack);
        }
        // Los espacios 0-5 del inventario son el mismo equipo puesto: se vacían también en el mob
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            recruit.setItemSlot(slot, ItemStack.EMPTY);
        }
        return out;
    }

    /** Guarda en la mochila del soldado (espacios 6+); devuelve lo que no entró. */
    public static ItemStack putInBackpack(SimpleContainer inv, ItemStack stack) {
        for (int i = FIRST_BACKPACK_SLOT; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                inv.setItem(i, stack);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int n = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(n);
                stack.shrink(n);
            }
        }
        return stack;
    }

    /**
     * Recruits tiene una entidad por tipo de tropa (la IA del arquero vive en BowmanEntity, etc.).
     * Para cambiar de clase se crea la entidad nueva con los mismos datos y se borra la vieja.
     */
    private static AbstractRecruitEntity convertIfNeeded(ServerLevel level, AbstractRecruitEntity recruit, ResourceLocation typeId) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(typeId);
        if (type == null || type == recruit.getType()) return recruit;

        Entity created = type.create(level);
        if (!(created instanceof AbstractRecruitEntity converted)) {
            WarbandCraft.LOGGER.warn("La clase pide la entidad {}, que no es un soldado de Recruits", typeId);
            if (created != null) created.discard();
            return recruit;
        }

        if (recruit.isPassenger()) recruit.stopRiding();
        CompoundTag tag = recruit.saveWithoutId(new CompoundTag());
        tag.remove("UUID");
        converted.load(tag);
        converted.moveTo(recruit.getX(), recruit.getY(), recruit.getZ(), recruit.getYRot(), recruit.getXRot());
        converted.setYHeadRot(recruit.getYHeadRot());

        recruit.discard();
        level.addFreshEntity(converted);

        try {
            converted.updateTeam();
            if (RecruitEvents.recruitsPlayerUnitManager != null && converted.getOwnerUUID() != null) {
                RecruitEvents.recruitsPlayerUnitManager.recountRecruits(level.getServer(), converted.getOwnerUUID());
            }
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudo actualizar equipo/conteo de Recruits tras convertir: {}", e.toString());
        }
        return converted;
    }

    private static void giveHorse(ServerLevel level, UUID playerId, AbstractRecruitEntity soldier) {
        Horse horse = EntityType.HORSE.create(level);
        if (horse == null) return;
        horse.moveTo(soldier.getX(), soldier.getY(), soldier.getZ(), soldier.getYRot(), 0.0F);
        horse.finalizeSpawn(level, level.getCurrentDifficultyAt(soldier.blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
        horse.setTamed(true);
        horse.setOwnerUUID(playerId);
        horse.equipSaddle(null);
        horse.getPersistentData().putUUID(TAG_HORSE_OWNER, playerId);
        level.addFreshEntity(horse);
        soldier.startRiding(horse, true);
    }
}
