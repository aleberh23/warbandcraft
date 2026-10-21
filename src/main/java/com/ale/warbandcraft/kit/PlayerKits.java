package com.ale.warbandcraft.kit;

import com.ale.warbandcraft.ai.Soldiers;
import com.ale.warbandcraft.camp.CampBlockEntity;
import com.ale.warbandcraft.camp.CampFinder;
import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.faction.PlayerFactionData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Las clases tal como las ve cada jugador (H6): el JSON de {@link KitManager} con el nombre propio y los
 * kits propios del jugador encima. Todo lo que arma soldados de un jugador pasa por {@link #resolve}.
 */
public final class PlayerKits {
    private PlayerKits() {}

    @Nullable
    public static ClassKit resolve(@Nullable MinecraftServer server, UUID player, ResourceLocation id) {
        ClassKit base = KitManager.INSTANCE.get(id);
        if (base == null || server == null) return base;
        return resolve(base, PlayerFactionData.get(server), player);
    }

    public static ClassKit resolve(ClassKit base, PlayerFactionData data, UUID player) {
        String name = data.className(player, base.id());
        List<ClassKit.Tier> tiers = new ArrayList<>(base.tiers().size());
        for (int i = 0; i < base.tiers().size(); i++) {
            ClassKit.Tier t = base.tiers().get(i);
            KitOverride o = data.override(player, base.id(), i);
            tiers.add(o == null ? t
                    : new ClassKit.Tier(t.name(), t.minLevel(), o.equipment(), o.inventory(), t.extraCost(), t.horse(), true));
        }
        return new ClassKit(base.id(), name != null ? name : base.name(), base.entity(), base.order(), List.copyOf(tiers));
    }

    /** Todas las clases del jugador, en el orden del Bastón. */
    public static List<ClassKit> all(@Nullable MinecraftServer server, UUID player) {
        List<ClassKit> out = new ArrayList<>();
        for (ClassKit base : KitManager.INSTANCE.ordered()) {
            out.add(server == null ? base : resolve(base, PlayerFactionData.get(server), player));
        }
        return out;
    }

    // ---------- Reglas para que la IA de Recruits no se rompa ----------

    /**
     * Revisa un kit propio. Devuelve el problema (para mostrar) o null si se puede guardar.
     * Es código común: lo usa el servidor al guardar y la pantalla para marcar en rojo.
     */
    @Nullable
    public static Component validate(ResourceLocation entity, Map<EquipmentSlot, Item> equipment, List<ItemStack> inventory) {
        Item main = equipment.get(EquipmentSlot.MAINHAND);
        String type = entity.getPath();
        boolean wantsBow = type.equals("bowman") || type.equals("nomad");
        boolean wantsCrossbow = type.equals("crossbowman");
        if (wantsBow && !(main instanceof BowItem)) return Component.translatable("kit.warbandcraft.error.needs_bow");
        if (wantsCrossbow && !(main instanceof CrossbowItem)) return Component.translatable("kit.warbandcraft.error.needs_crossbow");
        if (!wantsBow && !wantsCrossbow && (main instanceof BowItem || main instanceof CrossbowItem)) {
            return Component.translatable("kit.warbandcraft.error.no_ranged");
        }
        if (wantsBow || wantsCrossbow) {
            boolean arrows = inventory.stream().anyMatch(s -> s.getItem() instanceof ArrowItem);
            if (!arrows) return Component.translatable("kit.warbandcraft.error.needs_arrows");
        }
        return null;
    }

    // ---------- Re-armado automático ----------

    public static boolean outdated(ServerLevel level, UUID player, AbstractRecruitEntity soldier) {
        String kit = soldier.getPersistentData().getString(ClassAssigner.TAG_CLASS);
        ResourceLocation id = ResourceLocation.tryParse(kit);
        if (kit.isEmpty() || id == null) return false;
        int current = PlayerFactionData.get(level.getServer()).kitVersion(player, id);
        return soldier.getPersistentData().getInt(ClassAssigner.TAG_KIT_VERSION) != current;
    }

    /**
     * Re-arma a los soldados del jugador que están cerca de alguno de sus Campamentos y tienen un kit
     * viejo. Los que no tienen materiales quedan como están (se reintenta en la próxima pasada).
     *
     * @param announceWaiting true para avisar también cuántos quedaron esperando (al guardar un kit)
     */
    public static void rearmNear(ServerLevel level, UUID player, boolean announceWaiting) {
        Map<String, Integer> done = new LinkedHashMap<>();
        int waiting = 0;
        Set<UUID> seen = new HashSet<>();
        for (BlockPos pos : CampRegistry.get(level).campsOf(player)) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof CampBlockEntity)) continue;
            AABB box = new AABB(pos).inflate(CampFinder.RADIUS);
            List<AbstractRecruitEntity> soldiers = level.getEntitiesOfClass(AbstractRecruitEntity.class, box,
                    r -> r.isAlive() && Objects.equals(r.getOwnerUUID(), player) && Soldiers.isSoldier(r) && outdated(level, player, r));
            for (AbstractRecruitEntity soldier : soldiers) {
                if (!seen.add(soldier.getUUID())) continue;
                ClassAssigner.Rearm result = ClassAssigner.rearm(level, player, soldier);
                if (result == ClassAssigner.Rearm.DONE) {
                    ClassKit kit = resolve(level.getServer(), player,
                            new ResourceLocation(soldier.getPersistentData().getString(ClassAssigner.TAG_CLASS)));
                    done.merge(kit == null ? "?" : kit.name(), 1, Integer::sum);
                } else if (result == ClassAssigner.Rearm.WAITING) {
                    waiting++;
                }
            }
        }
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
        if (online == null) return;
        if (!done.isEmpty()) {
            StringBuilder list = new StringBuilder();
            done.forEach((name, n) -> list.append(list.length() == 0 ? "" : ", ").append(n).append(" ").append(name));
            online.sendSystemMessage(Component.translatable("msg.warbandcraft.rearm.done", list.toString())
                    .withStyle(ChatFormatting.GREEN));
        }
        if (announceWaiting && waiting > 0) {
            online.sendSystemMessage(Component.translatable("msg.warbandcraft.rearm.waiting", waiting)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }
}
