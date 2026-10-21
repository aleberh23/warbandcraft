package com.ale.warbandcraft.kit;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * H26: armas más realistas (Epic Knights y agregados). Se reconoce el arma por el nombre del ítem, así anda con
 * cualquier material y con los agregados. Lo que Epic Knights hace para el jugador (alcance, carga de lanza) va por
 * el cliente: acá va lo de los soldados.
 */
public final class WeaponRules {
    /** Pica / arma de asta contra alguien montado (o contra el caballo): multiplicador de daño. */
    public static double PIKE_VS_MOUNTED = 2.0;
    /** Carga de lanza de un soldado montado: daño extra por cada bloque/tick de velocidad del caballo. */
    public static double LANCE_SPEED_BONUS = 6.0;
    /** Carga de lanza: multiplicador máximo. */
    public static double LANCE_MAX = 2.5;
    /** Maza, martillo, mangual, garrote contra armadura pesada: multiplicador de daño. */
    public static double BLUNT_VS_PLATE = 1.3;
    /** Armadura desde la que cuenta como pesada (puntos de armadura, el hierro completo da 15). */
    public static int PLATE_ARMOR = 14;
    /** Peso: puntos de armadura que no pesan. */
    public static int WEIGHT_FREE_ARMOR = 12;
    /** Peso: velocidad que se pierde por cada punto de armadura arriba de lo libre. */
    public static double WEIGHT_PER_POINT = 0.008;
    /** Peso: lo máximo que se pierde de velocidad (0,12 = 12%). */
    public static double WEIGHT_MAX = 0.12;

    /** Pica / arma de asta de un soldado: alcance en bloques (los demás pegan a ~2,5). */
    public static double POLEARM_REACH = 4.0;
    /** Pavés: parte del daño de flechas de frente que para (0,6 = 60%). */
    public static double PAVISE_BLOCK = 0.6;

    private static final UUID WEIGHT_ID = UUID.fromString("6f1d5a2e-3c8b-4e0a-9d1f-2a6b7c8d9e26");
    private static final String[] POLEARMS = {"pike", "ranseur", "ahlspiess", "guisarme", "halberd", "lochaber",
            "lucern", "sarissa", "doru", "spear", "pitchfork"};
    private static final String[] PAVISES = {"pavese", "pavise"};
    private static final String[] BLUNT = {"mace", "warhammer", "morgenstern", "club", "lucern", "hammer"};

    public static float damage(LivingEntity target, DamageSource source, float amount) {
        if (source.getDirectEntity() instanceof Projectile arrow && has(path(target.getOffhandItem()), PAVISES)) {
            Vec3 to = arrow.position().subtract(target.position());
            if (to.horizontalDistanceSqr() > 1e-4 && target.getViewVector(1).multiply(1, 0, 1).dot(to) > 0) {
                amount *= (float) (1 - PAVISE_BLOCK);
            }
        }
        if (!(source.getEntity() instanceof LivingEntity attacker)) return amount;
        String weapon = path(attacker.getMainHandItem());
        if (weapon.isEmpty()) return amount;
        double mult = 1;
        boolean mounted = target.getVehicle() instanceof AbstractHorse || target instanceof AbstractHorse;
        if (mounted && !(attacker.getVehicle() instanceof AbstractHorse) && has(weapon, POLEARMS)) mult *= PIKE_VS_MOUNTED;
        // el jugador ya tiene la carga de Epic Knights
        if (!(attacker instanceof Player) && weapon.contains("lance") && attacker.getVehicle() instanceof AbstractHorse horse) {
            double speed = horse.getDeltaMovement().horizontalDistance();
            mult *= Math.min(LANCE_MAX, 1 + speed * LANCE_SPEED_BONUS);
        }
        if (has(weapon, BLUNT) && target.getArmorValue() >= PLATE_ARMOR) mult *= BLUNT_VS_PLATE;
        return (float) (amount * mult);
    }

    /** Peso de la armadura: un poco más lento con placas completas. */
    @SubscribeEvent
    public void onEquipment(LivingEquipmentChangeEvent event) {
        if (event.getSlot().getType() != EquipmentSlot.Type.ARMOR || event.getEntity().level().isClientSide) return;
        LivingEntity e = event.getEntity();
        if (e instanceof AbstractHorse) return;
        int armor = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR && e.getItemBySlot(slot).getItem() instanceof ArmorItem a) {
                armor += a.getDefense();
            }
        }
        AttributeInstance speed = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.removeModifier(WEIGHT_ID);
        double slow = Math.min(WEIGHT_MAX, Math.max(0, armor - WEIGHT_FREE_ARMOR) * WEIGHT_PER_POINT);
        if (slow > 0) speed.addTransientModifier(new AttributeModifier(WEIGHT_ID, "warbandcraft armor weight", -slow,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    public static boolean isPolearm(LivingEntity e) {
        return has(path(e.getMainHandItem()), POLEARMS);
    }

    /** Alcance de la pica: el soldado con arma de asta pega desde más lejos que la IA de Recruits. */
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity s) || s.level().isClientSide || s.tickCount % 5 != 0) return;
        LivingEntity target = s.getTarget();
        if (target == null || !target.isAlive() || s.getVehicle() != null || !isPolearm(s)) return;
        double d = s.distanceTo(target);
        if (d < 2.6 || d > POLEARM_REACH || !s.hasLineOfSight(target)) return;
        double speed = s.getAttributes().hasAttribute(Attributes.ATTACK_SPEED) ? s.getAttributeValue(Attributes.ATTACK_SPEED) : 1;
        if (s.tickCount - s.getLastHurtMobTimestamp() < 20 / Math.max(0.3, speed)) return;
        s.getLookControl().setLookAt(target);
        s.swing(InteractionHand.MAIN_HAND);
        s.doHurtTarget(target);
    }

    private static String path(ItemStack stack) {
        if (stack.isEmpty()) return "";
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null || id.getNamespace().equals("minecraft") ? "" : id.getPath();
    }

    private static boolean has(String path, String[] words) {
        for (String w : words) if (path.contains(w)) return true;
        return false;
    }

    public WeaponRules() {}
}
