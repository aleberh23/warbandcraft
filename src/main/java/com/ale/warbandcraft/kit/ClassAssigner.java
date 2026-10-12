package com.ale.warbandcraft.kit;

import com.ale.warbandcraft.WarbandCraft;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampFinder;
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
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 */
public final class ClassAssigner {
    public static final String TAG_CLASS = "warbandcraft:class";
    public static final String TAG_TIER = "warbandcraft:tier";

    /** Espacios 0-5 del inventario de Recruits son el equipo puesto; la mochila empieza en 6. */
    private static final int FIRST_BACKPACK_SLOT = 6;

    private ClassAssigner() {}

    public static void assign(ServerPlayer player, AbstractRecruitEntity recruit, ResourceLocation kitId) {
        if (!Objects.equals(recruit.getOwnerUUID(), player.getUUID())) {
            fail(player, Component.translatable("msg.warbandcraft.not_yours"));
            return;
        }
        ClassKit kit = KitManager.INSTANCE.get(kitId);
        if (kit == null) {
            fail(player, Component.translatable("msg.warbandcraft.unknown_class", kitId.toString()));
            return;
        }
        ServerLevel level = player.serverLevel();
        CampBlockEntity camp = CampFinder.findNearest(level, recruit.blockPosition(), player.getUUID());
        if (camp == null) {
            fail(player, Component.translatable("msg.warbandcraft.no_camp", CampFinder.RADIUS));
            return;
        }

        // Recursos disponibles = cofre + lo que el soldado ya tiene encima
        Map<Item, Integer> pool = new HashMap<>();
        for (int i = 0; i < camp.getContainerSize(); i++) addTo(pool, camp.getItem(i));
        SimpleContainer inv = recruit.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) addTo(pool, inv.getItem(i));

        int level0 = recruit.getXpLevel();
        List<ClassKit.Tier> tiers = kit.tiers();
        ClassKit.Tier chosen = null;
        int chosenIndex = -1;
        for (int i = tiers.size() - 1; i >= 0; i--) {
            ClassKit.Tier t = tiers.get(i);
            if (t.valid() && level0 >= t.minLevel() && canAfford(pool, t.cost())) {
                chosen = t;
                chosenIndex = i;
                break;
            }
        }
        if (chosen == null) {
            ClassKit.Tier base = kit.firstValidTier();
            if (base == null) {
                fail(player, Component.translatable("msg.warbandcraft.class_unavailable", kit.name()));
            } else {
                fail(player, Component.translatable("msg.warbandcraft.missing", kit.name(), missingList(pool, base.cost())));
            }
            return;
        }

        // 1) Devolver el equipo viejo al Campamento
        List<ItemStack> oldGear = stripRecruit(recruit);
        BlockPos campPos = camp.getBlockPos();
        for (ItemStack stack : oldGear) {
            ItemStack rest = camp.insert(stack);
            if (!rest.isEmpty()) {
                Containers.dropItemStack(level, campPos.getX() + 0.5, campPos.getY() + 1.1, campPos.getZ() + 0.5, rest);
            }
        }

        // 2) Cobrar el nivel elegido
        for (Map.Entry<Item, Integer> e : chosen.cost().entrySet()) {
            int taken = camp.take(e.getKey(), e.getValue());
            if (taken < e.getValue()) {
                // Solo pasa si el cofre estaba lleno y parte del equipo viejo cayó al piso.
                WarbandCraft.LOGGER.warn("Campamento en {}: faltaron {} de {}", campPos, e.getValue() - taken, e.getKey());
            }
        }

        // 3) Convertir a la entidad de la clase si hace falta
        AbstractRecruitEntity soldier = convertIfNeeded(level, recruit, kit.entity());

        // 4) Equipar
        for (Map.Entry<EquipmentSlot, Item> e : chosen.equipment().entrySet()) {
            soldier.setItemSlot(e.getKey(), new ItemStack(e.getValue()));
        }
        SimpleContainer soldierInv = soldier.getInventory();
        for (ItemStack stack : chosen.inventory()) {
            ItemStack rest = putInBackpack(soldierInv, stack.copy());
            if (!rest.isEmpty()) camp.insert(rest);
        }
        if (chosen.horse()) giveHorse(level, player, soldier);

        CompoundTag data = soldier.getPersistentData();
        data.putString(TAG_CLASS, kit.id().toString());
        data.putInt(TAG_TIER, chosenIndex);

        level.playSound(null, soldier.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.NEUTRAL, 1.0F, 1.0F);
        player.sendSystemMessage(Component.translatable("msg.warbandcraft.assigned",
                soldier.getDisplayName(),
                Component.literal(kit.name()).withStyle(ChatFormatting.GOLD),
                Component.literal(chosen.name()).withStyle(ChatFormatting.YELLOW)).withStyle(ChatFormatting.GREEN));
    }

    // ---------------------------------------------------------------------

    private static void addTo(Map<Item, Integer> pool, ItemStack stack) {
        if (!stack.isEmpty()) pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private static boolean canAfford(Map<Item, Integer> pool, Map<Item, Integer> cost) {
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            if (pool.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    private static MutableComponent missingList(Map<Item, Integer> pool, Map<Item, Integer> cost) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Map.Entry<Item, Integer> e : cost.entrySet()) {
            int missing = e.getValue() - pool.getOrDefault(e.getKey(), 0);
            if (missing <= 0) continue;
            if (!first) out.append(", ");
            out.append(missing + "x ").append(new ItemStack(e.getKey()).getHoverName());
            first = false;
        }
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

    private static ItemStack putInBackpack(SimpleContainer inv, ItemStack stack) {
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

    private static void giveHorse(ServerLevel level, ServerPlayer player, AbstractRecruitEntity soldier) {
        Horse horse = EntityType.HORSE.create(level);
        if (horse == null) return;
        horse.moveTo(soldier.getX(), soldier.getY(), soldier.getZ(), soldier.getYRot(), 0.0F);
        horse.finalizeSpawn(level, level.getCurrentDifficultyAt(soldier.blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
        horse.setTamed(true);
        horse.setOwnerUUID(player.getUUID());
        horse.equipSaddle(null);
        level.addFreshEntity(horse);
        soldier.startRiding(horse, true);
    }
}
