package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.camp.CampRegistry;
import com.ale.warbandcraft.init.ModItems;
import com.ale.warbandcraft.invasion.AiWars;
import com.ale.warbandcraft.invasion.Hosts;
import com.ale.warbandcraft.invasion.InvasionData;
import com.ale.warbandcraft.invasion.Invasions;
import com.ale.warbandcraft.raid.RaidManager;
import com.ale.warbandcraft.village.VillageManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * H23: el botín de las batallas contra soldados de la IA (hueste, invasiones, batallas entre facciones en las que te
 * metiste, emboscadas, patrullas). Lo que sirve para armar a tus soldados (armaduras, armas, arcos, ballestas, escudos,
 * flechas) se le saca al que cae —así no queda tirado— y va a una bolsa tuya, con armamento y esmeraldas por cada caído.
 * Cuando la batalla termina (un rato sin bajas, o al ganar) la bolsa va al Campamento, aldea o castillo tuyo más
 * cercano a donde peleaste. Los ataques a aldeas (H3) tienen su propio botín (Saquear / Conquistar).
 */
public final class BattleLoot {
    /** Sin caídos nuevos durante esto, la batalla terminó: el botín se manda. */
    public static int FLUSH_TICKS = 20 * 20;
    /** Por cada enemigo caído, además de su equipo: armamento y esmeraldas. */
    public static double ARMAMENT_PER_FALLEN = 1.0, EMERALDS_PER_FALLEN = 0.5;

    private static final class Bag {
        final ResourceKey<Level> dimension;
        final List<ItemStack> items = new ArrayList<>();
        BlockPos where;
        long last;
        int fallen;
        double armament, emeralds;

        Bag(ResourceKey<Level> dimension, BlockPos where) {
            this.dimension = dimension;
            this.where = where;
        }
    }

    private static final Map<UUID, Bag> BAGS = new HashMap<>();

    private BattleLoot() {}

    public static void clear() {
        BAGS.clear();
    }

    /** Cayó un soldado de la IA: si la batalla era contra vos (o te metiste), su equipo útil va a tu bolsa. */
    public static void onDeath(ServerLevel level, AbstractRecruitEntity dead, @Nullable Entity killer) {
        if (!VillageManager.isGarrison(dead)) return;
        UUID owner = beneficiary(level, dead, killer);
        if (owner == null) return;
        Bag bag = bag(level, owner, dead.blockPosition());
        bag.fallen++;
        bag.armament += ARMAMENT_PER_FALLEN;
        bag.emeralds += EMERALDS_PER_FALLEN;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = dead.getItemBySlot(slot);
            if (useful(s)) {
                add(bag.items, s.copy());
                dead.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
        var inv = dead.getInventory();
        for (int i = 6; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (useful(s)) add(bag.items, inv.removeItemNoUpdate(i));
        }
    }

    /** ¿Para quién es el botín de este caído? Null = para nadie (se cae al piso como siempre). */
    @Nullable
    private static UUID beneficiary(ServerLevel level, AbstractRecruitEntity dead, @Nullable Entity killer) {
        var pd = dead.getPersistentData();
        if (pd.contains(VillageManager.TAG_VILLAGE) && RaidManager.get(level, BlockPos.of(pd.getLong(VillageManager.TAG_VILLAGE))) != null) {
            return null;   // un ataque tuyo: el botín es el de Saquear / Conquistar
        }
        String host = pd.getString(Hosts.TAG_HOST);
        if (!host.isEmpty()) {
            for (InvasionData.Host h : InvasionData.get(level).hosts()) if (h.id.toString().equals(host)) return h.owner;
        }
        if (pd.contains(Invasions.TAG_INVADER)) {
            InvasionData.Invasion inv = InvasionData.get(level).at(BlockPos.of(pd.getLong(Invasions.TAG_INVADER)));
            if (inv != null) return inv.owner;
        }
        UUID killerOwner = RaidManager.responsiblePlayer(killer);
        UUID helper = AiWars.helperAgainst(level, dead, killerOwner);
        if (helper != null) return helper;
        return killerOwner;   // emboscadas, patrullas: el que lo mató (o su dueño)
    }

    /** Lo que sirve para armar soldados: armaduras, armas, arcos y ballestas, escudos, flechas. */
    public static boolean useful(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.getItem() instanceof ArmorItem || s.getItem() instanceof TieredItem || s.getItem() instanceof ProjectileWeaponItem
                || s.getItem() instanceof ShieldItem || s.getItem() instanceof ArrowItem;
    }

    /** Suma esmeraldas de premio a la bolsa de {@code owner}. */
    public static void bonus(ServerLevel level, UUID owner, int emeralds, BlockPos where) {
        bag(level, owner, where).emeralds += emeralds;
    }

    /** Suma ítems a la bolsa de {@code owner} (el premio de una batalla); se mandan con el resto. */
    public static void give(ServerLevel level, UUID owner, List<ItemStack> items, BlockPos where) {
        Bag bag = bag(level, owner, where);
        for (ItemStack s : items) if (!s.isEmpty()) add(bag.items, s.copy());
    }

    private static Bag bag(ServerLevel level, UUID owner, BlockPos where) {
        Bag bag = BAGS.get(owner);
        if (bag == null || bag.dimension != level.dimension()) {
            if (bag != null) flush(level.getServer().getLevel(bag.dimension), owner);
            bag = new Bag(level.dimension(), where);
            BAGS.put(owner, bag);
        }
        bag.where = where;
        bag.last = level.getGameTime();
        return bag;
    }

    /** Junta con lo que ya hay del mismo ítem (las armaduras no se apilan: van una por una). */
    private static void add(List<ItemStack> items, ItemStack s) {
        for (ItemStack it : items) {
            if (s.isEmpty()) return;
            if (ItemStack.isSameItemSameTags(it, s) && it.getCount() < it.getMaxStackSize()) {
                int n = Math.min(s.getCount(), it.getMaxStackSize() - it.getCount());
                it.grow(n);
                s.shrink(n);
            }
        }
        if (!s.isEmpty()) items.add(s);
    }

    /** Cada segundo: las bolsas de batallas que ya terminaron (un rato sin caídos) se mandan. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Iterator<Map.Entry<UUID, Bag>> it = BAGS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Bag> e = it.next();
            if (e.getValue().dimension != level.dimension() || now - e.getValue().last < FLUSH_TICKS) continue;
            it.remove();
            send(level, e.getKey(), e.getValue());
        }
    }

    /** Manda ya la bolsa de {@code owner} (terminó la batalla). */
    public static void flush(@Nullable ServerLevel level, UUID owner) {
        Bag bag = BAGS.remove(owner);
        if (bag != null && level != null) send(level, owner, bag);
    }

    /** Lo que hay en la bolsa de {@code owner} (para las pruebas): ítems contados. */
    public static int count(UUID owner, net.minecraft.world.item.Item item) {
        Bag bag = BAGS.get(owner);
        if (bag == null) return 0;
        int n = 0;
        for (ItemStack s : bag.items) if (s.is(item)) n += s.getCount();
        if (item == ModItems.ARMAMENT.get()) n += (int) bag.armament;
        if (item == Items.EMERALD) n += (int) bag.emeralds;
        return n;
    }

    private static void send(ServerLevel level, UUID owner, Bag bag) {
        List<ItemStack> items = new ArrayList<>(bag.items);
        int gear = 0;
        for (ItemStack s : items) gear += s.getCount();
        int armament = (int) Math.floor(bag.armament), emeralds = (int) Math.floor(bag.emeralds);
        for (int n = armament; n > 0; n -= 64) items.add(new ItemStack(ModItems.ARMAMENT.get(), Math.min(64, n)));
        for (int n = emeralds; n > 0; n -= 64) add(items, new ItemStack(Items.EMERALD, Math.min(64, n)));
        if (items.isEmpty()) return;
        boolean stored = CampRegistry.get(level).deliver(level, owner, items, bag.where);
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) {
            p.sendSystemMessage(Component.translatable(stored ? "msg.warbandcraft.loot.sent" : "msg.warbandcraft.loot.pending",
                    bag.fallen, gear, armament, emeralds).withStyle(ChatFormatting.GREEN));
        }
    }
}
