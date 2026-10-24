package com.ale.warbandcraft.ai;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

import java.util.UUID;

/**
 * Los caballos de los soldados (los tuyos y los de la IA) no corren más que {@link #MAX_SPEED}: los de vanilla salen con
 * velocidad al azar y los rápidos dejaban a la caballería sola, cruzando el campo en un segundo. Se pone al montar (también
 * al cargar del disco: el jinete vuelve a montar) y se saca al bajar. Al jugador que monta su caballo no se lo toca.
 */
public final class HorseSpeed {
    /** Velocidad máxima (atributo de vanilla: 0,1125 a 0,3375; 0,17 ≈ 7 bloques por segundo, un poco más que correr). */
    public static double MAX_SPEED = 0.17;
    private static final UUID MODIFIER = UUID.fromString("6f0c9a1e-1b7d-4c55-9a3e-5d2f7b8c4e10");

    private HorseSpeed() {}

    /** Un soldado se sube ({@code mounting}) o se baja de {@code vehicle}. */
    public static void onMount(Entity rider, Entity vehicle, boolean mounting) {
        if (!(vehicle instanceof AbstractHorse horse) || rider.level().isClientSide) return;
        AttributeInstance speed = horse.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        speed.removeModifier(MODIFIER);
        if (!mounting || !(rider instanceof AbstractRecruitEntity)) return;
        double extra = speed.getBaseValue() - MAX_SPEED;
        if (extra > 0) speed.addTransientModifier(new AttributeModifier(MODIFIER, "warbandcraft:horse_cap", -extra, AttributeModifier.Operation.ADDITION));
    }
}
