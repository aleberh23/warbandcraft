package com.ale.warbandcraft.faction;

import com.ale.warbandcraft.kit.ClassAssigner;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * Doctrina de tu facción (H6): una bonificación de estilo, una sola a la vez.
 *
 * Las de atributos se aplican como modificadores con UUID fijo: aplicar dos veces no apila, y
 * {@link #apply} siempre saca primero los de todas las doctrinas, así cambiar de doctrina deja al
 * soldado limpio. Arquería e Ingeniería no tocan atributos: se leen en el momento (flechas, TNT, cañón).
 */
public enum Doctrine {
    CAVALRY("cavalry"),
    SHIELDS("shields"),
    ARCHERY("archery"),
    ENGINEERING("engineering");

    // ---------- Números (para ajustar después de probar) ----------

    /** Caballería: caballos de tus soldados. */
    public static double HORSE_SPEED_BONUS = 0.25;   // +25% (multiplica la base)
    public static double HORSE_HEALTH_BONUS = 6.0;
    /** Escudos: infantería de línea. */
    public static double SHIELDS_ARMOR_BONUS = 3.0;
    public static double SHIELDS_KNOCKBACK_BONUS = 0.4;
    public static final Set<String> SHIELD_CLASSES = Set.of(
            "warbandcraft:infante", "warbandcraft:lancero", "warbandcraft:abanderado");
    /** Arquería: daño de las flechas de tus soldados. */
    public static double ARROW_DAMAGE_MULTIPLIER = 1.25;
    /** Ingeniería: TNT del Zapador y del cañón. */
    public static int FUSE_TICKS = 60, FUSE_TICKS_ENGINEERING = 40;
    public static float BLAST_POWER = 4.0F, BLAST_POWER_ENGINEERING = 5.0F;
    public static int CANNON_COOLDOWN = 160, CANNON_COOLDOWN_ENGINEERING = 100;

    /** Cambiar de doctrina (la primera vez es gratis). */
    public static int CHANGE_COST_EMERALDS = 20;
    public static long CHANGE_WAIT_TICKS = 24000L;

    private static final UUID HORSE_SPEED_ID = UUID.fromString("6f0c2a3e-8a1b-4c55-9a3f-1d2e3c4b5a01");
    private static final UUID HORSE_HEALTH_ID = UUID.fromString("6f0c2a3e-8a1b-4c55-9a3f-1d2e3c4b5a02");
    private static final UUID SHIELDS_ARMOR_ID = UUID.fromString("6f0c2a3e-8a1b-4c55-9a3f-1d2e3c4b5a03");
    private static final UUID SHIELDS_KNOCKBACK_ID = UUID.fromString("6f0c2a3e-8a1b-4c55-9a3f-1d2e3c4b5a04");

    private final String id;

    Doctrine(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Component displayName() {
        return Component.translatable("doctrine.warbandcraft." + id);
    }

    public Component description() {
        return Component.translatable("doctrine.warbandcraft." + id + ".desc");
    }

    @Nullable
    public static Doctrine byId(String id) {
        for (Doctrine d : values()) if (d.id.equals(id)) return d;
        return null;
    }

    @Nullable
    public static Doctrine byOrdinal(int i) {
        return i >= 0 && i < values().length ? values()[i] : null;
    }

    // ---------- Aplicar ----------

    /** Deja al soldado con los modificadores de {@code doctrine} (o ninguno). */
    public static void apply(AbstractRecruitEntity soldier, @Nullable Doctrine doctrine) {
        remove(soldier, Attributes.ARMOR, SHIELDS_ARMOR_ID);
        remove(soldier, Attributes.KNOCKBACK_RESISTANCE, SHIELDS_KNOCKBACK_ID);
        if (doctrine == SHIELDS && SHIELD_CLASSES.contains(soldier.getPersistentData().getString(ClassAssigner.TAG_CLASS))) {
            add(soldier, Attributes.ARMOR, SHIELDS_ARMOR_ID, "Doctrina: Escudos", SHIELDS_ARMOR_BONUS, AttributeModifier.Operation.ADDITION);
            add(soldier, Attributes.KNOCKBACK_RESISTANCE, SHIELDS_KNOCKBACK_ID, "Doctrina: Escudos", SHIELDS_KNOCKBACK_BONUS, AttributeModifier.Operation.ADDITION);
        }
    }

    public static void applyHorse(AbstractHorse horse, @Nullable Doctrine doctrine) {
        remove(horse, Attributes.MOVEMENT_SPEED, HORSE_SPEED_ID);
        remove(horse, Attributes.MAX_HEALTH, HORSE_HEALTH_ID);
        if (doctrine == CAVALRY) {
            add(horse, Attributes.MOVEMENT_SPEED, HORSE_SPEED_ID, "Doctrina: Caballería", HORSE_SPEED_BONUS, AttributeModifier.Operation.MULTIPLY_BASE);
            add(horse, Attributes.MAX_HEALTH, HORSE_HEALTH_ID, "Doctrina: Caballería", HORSE_HEALTH_BONUS, AttributeModifier.Operation.ADDITION);
        }
        if (horse.getHealth() > horse.getMaxHealth()) horse.setHealth(horse.getMaxHealth());
    }

    public static int fuseTicks(@Nullable Doctrine d) {
        return d == ENGINEERING ? FUSE_TICKS_ENGINEERING : FUSE_TICKS;
    }

    public static int cannonCooldown(@Nullable Doctrine d) {
        return d == ENGINEERING ? CANNON_COOLDOWN_ENGINEERING : CANNON_COOLDOWN;
    }

    private static void remove(LivingEntity entity, Attribute attribute, UUID id) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null) inst.removeModifier(id);
    }

    private static void add(LivingEntity entity, Attribute attribute, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null && inst.getModifier(id) == null) {
            // Transitorio: no se guarda con la entidad; se vuelve a poner cada vez que aparece en el mundo.
            inst.addTransientModifier(new AttributeModifier(id, name, amount, op));
        }
    }
}
