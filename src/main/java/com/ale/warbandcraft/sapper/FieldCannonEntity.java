package com.ale.warbandcraft.sapper;

import com.ale.warbandcraft.faction.Doctrine;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Cañón de campaña del Zapador (H6). No es un cañón de redstone: es una entidad que tira TNT encendido
 * en arco al objetivo, mientras su Zapador esté al lado y tenga TNT en la mochila.
 *
 * - Objetivo: lo pone la vista aérea (botón Cañón con el Zapador seleccionado, click al objetivo).
 * - Solo tira a aldeas o castillos que no son tuyos, hasta {@link SapperRules#CANNON_RANGE} bloques.
 * - Agachado + click derecho (el dueño): se desarma y devuelve los materiales.
 * Se ve como un dispensador mirando para arriba hasta que H7 le dé un modelo propio.
 */
public class FieldCannonEntity extends Entity {
    private static float MAX_HEALTH = 30.0F;
    private static double SAPPER_REACH = 5.0;

    @Nullable private UUID owner;
    @Nullable private UUID sapper;
    @Nullable private BlockPos target;
    private int cooldown = 40;
    private float health = MAX_HEALTH;
    private boolean warnedNoTnt;
    /** Cañón de un ejército de la IA (asedios, H17): sin dueño, dispara al muro que le marcan. */
    private boolean ai;

    public FieldCannonEntity(EntityType<? extends FieldCannonEntity> type, Level level) {
        super(type, level);
    }

    /** Cañón de la IA: lo atiende {@code sapper} (con TNT en la mochila) y dispara a {@code target}. */
    public void setAi(UUID sapper, BlockPos target, java.util.List<BlockPos> hole) {
        if (!ai || !target.equals(this.target)) this.cooldown = 60;
        this.ai = true;
        this.sapper = sapper;
        this.target = target;
        this.hole = hole;
    }

    /** Cañón de la IA: el paso que abre su TNT. */
    private java.util.List<BlockPos> hole = java.util.List.of();

    public boolean isAi() {
        return ai;
    }

    public void setOwnerAndSapper(UUID owner, UUID sapper) {
        this.owner = owner;
        this.sapper = sapper;
    }

    @Nullable
    public UUID getSapper() {
        return sapper;
    }

    public void setTarget(@Nullable BlockPos target) {
        this.target = target;
        this.warnedNoTnt = false;
        if (cooldown > 40) cooldown = 40;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        super.tick();
        if (!isNoGravity()) setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(onGround() ? Vec3.ZERO : getDeltaMovement().scale(0.98));

        if (level() instanceof ServerLevel server) {
            if (cooldown > 0) cooldown--;
            else tryFire(server);
        }
    }

    private void tryFire(ServerLevel level) {
        cooldown = 20;
        if (ai) {
            fireAi(level);
            return;
        }
        if (target == null || owner == null || sapper == null) return;
        if (!(level.getEntity(sapper) instanceof AbstractRecruitEntity crew) || !crew.isAlive()
                || crew.distanceTo(this) > SAPPER_REACH) return;

        Vec3 from = position().add(0, 1.1, 0);
        Vec3 to = Vec3.atBottomCenterOf(target).add(0, 0.5, 0);
        double horizontal = Math.sqrt(Mth.square(to.x - from.x) + Mth.square(to.z - from.z));
        if (horizontal > SapperRules.CANNON_RANGE) {
            SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.cannon.too_far", (int) SapperRules.CANNON_RANGE));
            target = null;
            return;
        }
        VillageData.Village village = SapperRules.enemyTarget(level, target, owner);
        if (village == null) {
            SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.not_enemy"));
            target = null;
            return;
        }
        if (!SapperRules.takeTnt(crew)) {
            if (!warnedNoTnt) SapperRules.tell(level, owner, Component.translatable("msg.warbandcraft.sapper.no_tnt"));
            warnedNoTnt = true;
            cooldown = 100;
            return;
        }

        // Tiro en arco: el TNT cae justo cuando se le acaba la mecha (física del PrimedTnt de vanilla).
        int flight = Mth.clamp((int) (20 + horizontal * 0.9), 20, 80);
        Vec3 velocity = launchVelocity(to.subtract(from), flight);
        Doctrine doctrine = SapperRules.doctrineOf(level, owner);
        SapperRules.spawnTnt(level, from, velocity, flight, crew, doctrine);
        SapperRules.startRaid(level, village, owner);

        setYRot((float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.6F, 1.6F);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 8, 0.2, 0.2, 0.2, 0.02);
        cooldown = Doctrine.cannonCooldown(doctrine);
        warnedNoTnt = false;
    }

    /** IA: si el zapador está al lado y tiene TNT, tira al muro (sin avisos ni ataques: eso lo maneja el asedio). */
    private void fireAi(ServerLevel level) {
        if (target == null || sapper == null) return;
        if (!(level.getEntity(sapper) instanceof AbstractRecruitEntity crew) || !crew.isAlive() || crew.distanceTo(this) > SAPPER_REACH) return;
        Vec3 from = position().add(0, 1.1, 0);
        Vec3 to = Vec3.atBottomCenterOf(target).add(0, 0.5, 0);
        double horizontal = Math.sqrt(Mth.square(to.x - from.x) + Mth.square(to.z - from.z));
        if (horizontal > SapperRules.CANNON_RANGE || !SapperRules.takeTnt(crew)) {
            cooldown = 100;
            return;
        }
        int flight = Mth.clamp((int) (20 + horizontal * 0.9), 20, 80);
        SapperRules.markHole(SapperRules.spawnTnt(level, from, launchVelocity(to.subtract(from), flight), flight, crew, null),
                hole.isEmpty() ? java.util.List.of(target, target.above()) : hole);
        setYRot((float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.6F, 1.6F);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 8, 0.2, 0.2, 0.2, 0.02);
        cooldown = AI_COOLDOWN;
    }

    /** Cada cuánto tira el cañón de la IA (ticks). */
    public static int AI_COOLDOWN = 20 * 6;

    /**
     * Velocidad inicial para recorrer {@code delta} en {@code ticks} ticks con la física del TNT:
     * cada tick suma -0.04 de gravedad, se mueve y multiplica la velocidad por 0.98.
     * Tanto x como y son lineales en la velocidad inicial (coeficiente {@code c} = Σ 0.98^i).
     */
    public static Vec3 launchVelocity(Vec3 delta, int ticks) {
        double c = 0, gravityDrop = 0, v = 0;
        double f = 1;
        for (int i = 0; i < ticks; i++) {
            c += f;
            f *= 0.98;
            v -= 0.04;
            gravityDrop += v;
            v *= 0.98;
        }
        return new Vec3(delta.x / c, (delta.y - gravityDrop) / c, delta.z / c);
    }

    // ---------- Interacción ----------

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) return false;
        if (level().isClientSide) return true;
        health -= amount;
        markHurt();
        if (health <= 0) {
            // Roto en combate: solo quedan algunos lingotes.
            spawnAtLocation(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 2));
            discard();
        }
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!Objects.equals(owner, player.getUUID())) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown()) {
            for (ItemStack stack : SapperRules.cannonRefund()) player.getInventory().placeItemBackInInventory(stack);
            player.displayClientMessage(Component.translatable("msg.warbandcraft.cannon.dismantled"), true);
            discard();
        } else {
            player.displayClientMessage(target == null
                    ? Component.translatable("msg.warbandcraft.cannon.no_target")
                    : Component.translatable("msg.warbandcraft.cannon.status", target.toShortString()), true);
        }
        return InteractionResult.CONSUME;
    }

    // ---------- Guardado ----------

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        sapper = tag.hasUUID("Sapper") ? tag.getUUID("Sapper") : null;
        target = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : null;
        cooldown = tag.getInt("Cooldown");
        health = tag.contains("Health") ? tag.getFloat("Health") : MAX_HEALTH;
        ai = tag.getBoolean("Ai");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (owner != null) tag.putUUID("Owner", owner);
        if (sapper != null) tag.putUUID("Sapper", sapper);
        if (target != null) tag.putLong("Target", target.asLong());
        tag.putInt("Cooldown", cooldown);
        tag.putFloat("Health", health);
        if (ai) tag.putBoolean("Ai", true);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /** El cañón de este Zapador (cargado, a menos de 64 bloques), o null. */
    @Nullable
    public static FieldCannonEntity of(ServerLevel level, Entity sapper) {
        for (FieldCannonEntity c : level.getEntitiesOfClass(FieldCannonEntity.class, sapper.getBoundingBox().inflate(64))) {
            if (sapper.getUUID().equals(c.getSapper())) return c;
        }
        return null;
    }
}
