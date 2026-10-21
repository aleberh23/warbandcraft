package com.ale.warbandcraft.dungeon;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * H25: los túmulos nórdicos, inspirados en Skyrim (la idea, no sus modelos ni nombres): Draugr dormidos en ataúdes que se
 * levantan cuando pasás, la puerta de las garras (tres columnas que giran: la combinación está en la Garra de oro que
 * lleva el guardián) y el Señor del Túmulo en su trono. Todo con mobs y bloques de vanilla.
 * <ul>
 *   <li>Draugr = zombi con cota de malla y hacha (no se quema: casco), Draugr arquero = stray. El Señor = esqueleto
 *       wither con mucha vida, barra de jefe y, a la mitad de la vida, llama a dos draugr.</li>
 *   <li>Marca en cada uno: {@link Dungeons#TAG_DUNGEON} (id del túmulo); el jefe además {@link #TAG_LORD}.</li>
 * </ul>
 */
public final class Barrows {
    /** Lo que muestran las columnas: Lobo, Águila, Pez. */
    public static final Item[] SYMBOLS = {Items.BONE, Items.FEATHER, Items.COD};
    public static final String[] SYMBOL_NAMES = {"gui.warbandcraft.barrow.wolf", "gui.warbandcraft.barrow.eagle", "gui.warbandcraft.barrow.fish"};
    /** Marca del marco de una columna (= id del túmulo). */
    public static final String TAG_PILLAR = "warbandcraft:claw_pillar";
    public static final String TAG_LORD = "warbandcraft:barrow_lord";
    public static final String TAG_SUMMONED = "warbandcraft:lord_called";
    /** En la Garra de oro: de qué túmulo es. */
    public static final String TAG_CLAW = "WarbandClaw";

    /** Vida de los draugr y del Señor del Túmulo. */
    public static double DRAUGR_HEALTH = 30;
    public static double LORD_HEALTH = 160;
    public static double LORD_DAMAGE = 9;
    /** Se levanta el draugr de un ataúd si pasa alguien a esta distancia. */
    public static double COFFIN_WAKE = 4.5;
    /** Despierta el Señor si alguien llega a esta distancia del trono. */
    public static double LORD_WAKE = 11;
    /** Uno de cada N draugr de ataúd es arquero. */
    public static int ARCHER_1_IN = 3;
    /** Hasta dónde se ve la barra del jefe. */
    public static int BAR_RANGE = 40;

    /** Barras de jefe, por túmulo (en memoria). */
    private static final Map<Integer, ServerBossEvent> BARS = new HashMap<>();

    private Barrows() {}

    // ------------------------------------------------------------------ draugr

    public enum Type { WARRIOR, ARCHER, GUARDIAN }

    /** Un draugr nuevo en {@code pos} (ya en el mundo). */
    @Nullable
    public static Mob draugr(ServerLevel level, BlockPos pos, Type type, DungeonData.Dungeon d, RandomSource random) {
        EntityType<? extends Mob> et = type == Type.ARCHER ? EntityType.STRAY : EntityType.ZOMBIE;
        Mob m = et.create(level);
        if (m == null) return null;
        m.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360F, 0F);
        if (m instanceof net.minecraft.world.entity.monster.Zombie z) {
            z.setBaby(false);
            z.setCanPickUpLoot(false);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) m.setItemSlot(slot, ItemStack.EMPTY);
        m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(random.nextInt(3) == 0 ? Items.IRON_HELMET : Items.CHAINMAIL_HELMET));
        m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
        if (type == Type.ARCHER) {
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        } else {
            Item[] weapons = {Items.IRON_AXE, Items.STONE_AXE, Items.IRON_SWORD, Items.STONE_SWORD};
            m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapons[random.nextInt(weapons.length)]));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) m.setDropChance(slot, 0.06F);
        if (type == Type.GUARDIAN) {
            m.setItemSlot(EquipmentSlot.OFFHAND, claw(d));
            m.setDropChance(EquipmentSlot.OFFHAND, 2.0F);   // > 1: siempre, sin gastar
            m.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        }
        setHealth(m, DRAUGR_HEALTH * (type == Type.GUARDIAN ? 1.5 : 1));
        m.setCustomName(Component.translatable(type == Type.ARCHER ? "entity.warbandcraft.draugr_archer"
                : type == Type.GUARDIAN ? "entity.warbandcraft.draugr_guardian" : "entity.warbandcraft.draugr").withStyle(ChatFormatting.DARK_AQUA));
        mark(m, d);
        level.addFreshEntity(m);
        return m;
    }

    /** El Señor del Túmulo, que despierta en su trono. */
    @Nullable
    public static Mob lord(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        if (d.throne == null) return null;
        Mob m = EntityType.WITHER_SKELETON.create(level);
        if (m == null) return null;
        m.moveTo(d.throne.getX() + 0.5, d.throne.getY(), d.throne.getZ() + 0.5, 0F, 0F);
        m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
        m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
        m.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        m.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
        for (EquipmentSlot slot : EquipmentSlot.values()) m.setDropChance(slot, slot == EquipmentSlot.MAINHAND ? 0.25F : 0F);
        setHealth(m, LORD_HEALTH);
        AttributeInstance dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(LORD_DAMAGE);
        AttributeInstance kb = m.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(0.7);
        m.setCustomName(Component.translatable("entity.warbandcraft.barrow_lord").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        m.getPersistentData().putBoolean(TAG_LORD, true);
        mark(m, d);
        level.addFreshEntity(m);
        level.playSound(null, d.throne, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.6F);
        return m;
    }

    private static void setHealth(Mob m, double hp) {
        AttributeInstance a = m.getAttribute(Attributes.MAX_HEALTH);
        if (a != null) a.setBaseValue(hp);
        m.setHealth((float) hp);
    }

    private static void mark(Mob m, DungeonData.Dungeon d) {
        m.setPersistenceRequired();
        m.getPersistentData().putInt(Dungeons.TAG_DUNGEON, d.id);
        targetSoldiers(m);
    }

    /** Los de la mazmorra también van contra tus soldados (los zombis de vanilla solo buscan jugadores y aldeanos). */
    public static void targetSoldiers(Mob m) {
        if (!(m instanceof Monster mon)) return;
        for (WrappedGoal g : mon.targetSelector.getAvailableGoals()) if (g.getGoal() instanceof SoldierTarget) return;
        mon.targetSelector.addGoal(3, new SoldierTarget(mon));
    }

    static class SoldierTarget extends NearestAttackableTargetGoal<AbstractRecruitEntity> {
        SoldierTarget(Monster mob) {
            super(mob, AbstractRecruitEntity.class, 10, true, false, r -> r instanceof AbstractRecruitEntity s && s.getIsOwned());
        }
    }

    // ------------------------------------------------------------------ la Garra y las columnas

    /** La Garra de oro de un túmulo: dice la combinación de la puerta. */
    public static ItemStack claw(DungeonData.Dungeon d) {
        ItemStack s = new ItemStack(Items.GOLD_NUGGET);
        s.setHoverName(Component.translatable("item.warbandcraft.golden_claw").withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        CompoundTag display = s.getOrCreateTagElement("display");
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.translatable("item.warbandcraft.golden_claw.lore")
                .withStyle(ChatFormatting.GRAY))));
        for (int i = 0; i < d.answer.length; i++) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal((i + 1) + ". ")
                    .append(Component.translatable(SYMBOL_NAMES[d.answer[i]])).withStyle(ChatFormatting.YELLOW))));
        }
        display.put("Lore", lore);
        s.getOrCreateTag().putInt(TAG_CLAW, d.id);
        return s;
    }

    /** Pone el marco de una columna (fijo, invulnerable) mostrando el símbolo {@code symbol}. */
    public static void placePillar(ServerLevel level, BlockPos at, Direction facing, int symbol, int dungeon) {
        ItemFrame frame = new ItemFrame(level, at, facing);
        frame.setItem(new ItemStack(SYMBOLS[symbol]), false);
        CompoundTag tag = frame.saveWithoutId(new CompoundTag());
        tag.putBoolean("Fixed", true);
        tag.putBoolean("Invulnerable", true);
        frame.load(tag);
        frame.getPersistentData().putInt(TAG_PILLAR, dungeon);
        level.addFreshEntity(frame);
    }

    /** Click en una columna: pasa al símbolo siguiente. Devuelve true si era una columna. */
    public static boolean turn(ServerLevel level, ItemFrame frame) {
        if (!frame.getPersistentData().contains(TAG_PILLAR)) return false;
        int current = symbolOf(frame.getItem());
        frame.setItem(new ItemStack(SYMBOLS[(current + 1) % SYMBOLS.length]), false);
        level.playSound(null, frame.blockPosition(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.6F, 0.7F);
        DungeonData.Dungeon d = DungeonData.get(level).byId(frame.getPersistentData().getInt(TAG_PILLAR));
        if (d != null) checkDoor(level, d);
        return true;
    }

    private static int symbolOf(ItemStack s) {
        for (int i = 0; i < SYMBOLS.length; i++) if (s.is(SYMBOLS[i])) return i;
        return 0;
    }

    /** ¿Las columnas muestran la combinación? Entonces se abre la puerta (y queda abierta). */
    public static void checkDoor(ServerLevel level, DungeonData.Dungeon d) {
        if (d.doorOpen || d.pillars.size() != d.answer.length) return;
        for (int i = 0; i < d.pillars.size(); i++) {
            BlockPos p = d.pillars.get(i);
            ItemFrame frame = pillarAt(level, d, p);
            if (frame == null || symbolOf(frame.getItem()) != d.answer[i]) return;
        }
        openDoor(level, d);
    }

    /** El marco de la columna que está en {@code p} (exacto: las tres columnas están pegadas). */
    @Nullable
    public static ItemFrame pillarAt(ServerLevel level, DungeonData.Dungeon d, BlockPos p) {
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(p).inflate(0.5),
                fr -> fr.getPersistentData().getInt(TAG_PILLAR) == d.id && fr.blockPosition().equals(p));
        return frames.isEmpty() ? null : frames.get(0);
    }

    public static void openDoor(ServerLevel level, DungeonData.Dungeon d) {
        d.doorOpen = true;
        DungeonData.get(level).changed();
        for (BlockPos p : d.door) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.01);
        }
        if (!d.door.isEmpty()) {
            BlockPos mid = d.door.get(d.door.size() / 2);
            level.playSound(null, mid, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.5F, 0.5F);
            level.playSound(null, mid, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.5F, 0.5F);
            for (ServerPlayer p : level.getPlayers(pl -> pl.distanceToSqr(mid.getX(), mid.getY(), mid.getZ()) < 32 * 32)) {
                p.displayClientMessage(Component.translatable("msg.warbandcraft.barrow.door_open").withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    // ------------------------------------------------------------------ cada segundo, con alguien cerca

    /** Ataúdes que se abren, el Señor que despierta, su barra y su llamado. */
    static void tick(ServerLevel level, DungeonData.Dungeon d, List<? extends net.minecraft.world.entity.LivingEntity> near, RandomSource random) {
        for (int i = 0; i < d.coffins.size(); i++) {
            if (d.risen.contains(i)) continue;
            BlockPos c = d.coffins.get(i);
            for (var e : near) {
                if (e.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5) <= COFFIN_WAKE * COFFIN_WAKE) {
                    wakeCoffin(level, d, i, random);
                    break;
                }
            }
        }
        if (!d.bossAwake && !d.bossDead && d.throne != null) {
            for (var e : near) {
                if (e.distanceToSqr(d.throne.getX() + 0.5, d.throne.getY(), d.throne.getZ() + 0.5) <= LORD_WAKE * LORD_WAKE) {
                    wakeLord(level, d, random);
                    break;
                }
            }
        }
        updateBar(level, d, random);
    }

    /** El draugr del ataúd {@code i} se levanta (se saca la tapa). */
    public static void wakeCoffin(ServerLevel level, DungeonData.Dungeon d, int i, RandomSource random) {
        d.risen.add(i);
        DungeonData.get(level).changed();
        BlockPos c = d.coffins.get(i);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy < 2; dy++) {
                BlockPos lid = c.relative(dir).above(dy);
                if (level.getBlockState(lid).is(Blocks.SPRUCE_TRAPDOOR)) level.setBlock(lid, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        level.playSound(null, c, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.5F);
        level.playSound(null, c, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F);
        level.sendParticles(ParticleTypes.SOUL, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, 8, 0.3, 0.5, 0.3, 0.02);
        draugr(level, c, random.nextInt(ARCHER_1_IN) == 0 ? Type.ARCHER : Type.WARRIOR, d, random);
    }

    public static void wakeLord(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        if (lord(level, d, random) == null) return;
        d.bossAwake = true;
        DungeonData.get(level).changed();
        for (ServerPlayer p : level.getPlayers(pl -> d.inside(pl.blockPosition(), 8))) {
            p.displayClientMessage(Component.translatable("msg.warbandcraft.barrow.lord_wakes").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), true);
        }
    }

    @Nullable
    static Mob findLord(ServerLevel level, DungeonData.Dungeon d) {
        AABB box = new AABB(d.box[0], d.box[1], d.box[2], d.box[3] + 1, d.box[4] + 1, d.box[5] + 1).inflate(8);
        List<Mob> l = level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m.getPersistentData().getBoolean(TAG_LORD)
                && m.getPersistentData().getInt(Dungeons.TAG_DUNGEON) == d.id);
        return l.isEmpty() ? null : l.get(0);
    }

    private static void updateBar(ServerLevel level, DungeonData.Dungeon d, RandomSource random) {
        Mob lord = d.bossAwake && !d.bossDead ? findLord(level, d) : null;
        ServerBossEvent bar = BARS.get(d.id);
        if (lord == null) {
            if (bar != null) {
                bar.removeAllPlayers();
                BARS.remove(d.id);
            }
            return;
        }
        if (bar == null) {
            bar = new ServerBossEvent(lord.getDisplayName(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
            BARS.put(d.id, bar);
        }
        bar.setProgress(Math.max(0, lord.getHealth() / lord.getMaxHealth()));
        for (ServerPlayer p : level.players()) {
            boolean close = p.distanceToSqr(lord) < (double) BAR_RANGE * BAR_RANGE;
            if (close && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            else if (!close && bar.getPlayers().contains(p)) bar.removePlayer(p);
        }
        // A la mitad de la vida llama a dos draugr (una vez)
        if (!lord.getPersistentData().getBoolean(TAG_SUMMONED) && lord.getHealth() < lord.getMaxHealth() / 2) {
            lord.getPersistentData().putBoolean(TAG_SUMMONED, true);
            level.playSound(null, lord.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2F, 0.6F);
            for (int i = 0; i < 2; i++) {
                BlockPos at = lord.blockPosition().offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
                if (!level.getBlockState(at).isAir()) at = lord.blockPosition();
                level.sendParticles(ParticleTypes.SOUL, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 12, 0.3, 0.6, 0.3, 0.02);
                draugr(level, at, i == 0 ? Type.WARRIOR : Type.ARCHER, d, random);
            }
        }
    }

    public static void clearBars() {
        for (ServerBossEvent b : BARS.values()) b.removeAllPlayers();
        BARS.clear();
    }

    /** Para mensajes: "Lobo · Águila · Pez". */
    public static Component answerText(DungeonData.Dungeon d) {
        Component out = Component.empty();
        for (int i = 0; i < d.answer.length; i++) {
            if (i > 0) out = out.copy().append(" · ");
            out = out.copy().append(Component.translatable(SYMBOL_NAMES[d.answer[i]]));
        }
        return out;
    }
}
