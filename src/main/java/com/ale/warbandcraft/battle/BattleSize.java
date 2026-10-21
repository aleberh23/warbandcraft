package com.ale.warbandcraft.battle;

/**
 * Tamaño de batalla (H23, como la opción de Bannerlord): cuántos soldados de cada bando puede haber a la vez en el
 * campo. El resto espera y entra en oleadas por la retaguardia a medida que caen los de adelante. Lo usan la Hueste,
 * tus ejércitos de campaña (H20) y las invasiones grandes. Bajarlo si el juego se traba.
 */
public final class BattleSize {
    /** Máximo de soldados de cada bando en el campo a la vez (el resto entra en oleadas). */
    public static int MAX_PER_SIDE = 60;
    /** Cuántos refuerzos entran como mucho por segundo. */
    public static int REINFORCE_PER_SECOND = 6;

    /** Daño entre soldados (de cualquier bando) por esto: menos = las batallas duran más. A vos y a los bichos, normal. */
    public static double NPC_DAMAGE = 0.5;

    private BattleSize() {}

    public static float npcDamage(net.minecraft.world.entity.Entity victim, @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity attacker,
                                  float amount) {
        if (victim instanceof com.talhanation.recruits.entities.AbstractRecruitEntity
                && attacker instanceof com.talhanation.recruits.entities.AbstractRecruitEntity) return (float) (amount * NPC_DAMAGE);
        return amount;
    }

    /** Cuántos pueden entrar ahora: lugar libre en el campo, recortado a la reserva y a la oleada por segundo. */
    public static int room(int onField, int reserve) {
        return Math.max(0, Math.min(Math.min(reserve, REINFORCE_PER_SECOND), MAX_PER_SIDE - onField));
    }
}
