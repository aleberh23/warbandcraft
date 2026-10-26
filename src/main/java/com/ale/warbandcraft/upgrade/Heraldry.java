package com.ale.warbandcraft.upgrade;

import com.ale.warbandcraft.faction.Faction;
import com.ale.warbandcraft.faction.FactionManager;
import com.ale.warbandcraft.faction.FactionStyle;
import com.ale.warbandcraft.village.VillageData;
import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.world.RecruitsFaction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Pedido de Ale: los vidrios y estandartes de los edificios de una aldea son del color de la facción que la tiene, y los
 * estandartes son el de la facción misma (el de su JSON o el de su facción de Recruits). Vale para los edificios nuevos
 * (el plano ya sale con ese color y al terminar la obra se pinta el estandarte), para los de antes y para lo que pone el
 * trabajador de Mantenimiento. Si la aldea cambia de dueño (la conquistás o te la conquistan) o la facción cambia su
 * estandarte, {@link #tick} lo nota y repinta todo.
 */
public final class Heraldry {
    private Heraldry() {}

    /** El color de la facción (vidrios y fondo del estandarte) y su estandarte con los dibujos (vacío = liso). */
    public record Colors(DyeColor dye, ItemStack banner) {
        /** Lo que se guarda para saber si cambió. */
        String key() {
            CompoundTag be = banner.getTagElement("BlockEntityTag");
            return dye.getName() + "|" + (be == null ? "" : be.toString());
        }
    }

    /** Los colores de quien tiene la aldea, o null si no se sabe (un dueño sin facción de Recruits: quedan los del estilo). */
    @Nullable
    public static Colors of(VillageData.Village v) {
        ItemStack banner;
        int rgb;
        if (v.owner() != null) {
            String team = com.ale.warbandcraft.faction.Allies.factionOf(v.owner());
            RecruitsFaction f = team == null || FactionEvents.recruitsFactionManager == null ? null
                    : FactionEvents.recruitsFactionManager.getFactionByStringID(team);
            if (f == null) return null;
            FactionStyle st = FactionStyle.of(f);
            banner = st.banner();
            rgb = st.armorColor();
        } else {
            Faction f = FactionManager.INSTANCE.get(v.faction());
            if (f == null) return null;
            banner = f.banner();
            rgb = f.armorColor();
        }
        DyeColor dye = banner.getItem() instanceof BannerItem b ? b.getColor() : nearest(rgb);
        return new Colors(dye, banner.getItem() instanceof BannerItem ? banner : ItemStack.EMPTY);
    }

    /** El tinte más parecido a un color RGB. */
    static DyeColor nearest(int rgb) {
        DyeColor best = DyeColor.WHITE;
        double bestD = Double.MAX_VALUE;
        for (DyeColor d : DyeColor.values()) {
            float[] c = d.getTextureDiffuseColors();
            double dr = c[0] * 255 - ((rgb >> 16) & 255), dg = c[1] * 255 - ((rgb >> 8) & 255), db = c[2] * 255 - (rgb & 255);
            double dist = dr * dr + dg * dg + db * db;
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ bloques

    private static Block block(DyeColor dye, String suffix) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", dye.getName() + suffix));
        return b == null ? Blocks.AIR : b;
    }

    /** Copia las propiedades que tengan en común (dirección, uniones, agua...). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState copy(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property p : from.getProperties()) if (s.hasProperty(p)) s = s.setValue(p, from.getValue(p));
        return s;
    }

    /** El bloque {@code s} pintado del color {@code dye}, o null si no es un vidrio ni un estandarte. */
    @Nullable
    public static BlockState tinted(BlockState s, DyeColor dye) {
        Block b = s.getBlock();
        if (b instanceof StainedGlassPaneBlock || b == Blocks.GLASS_PANE) return copy(s, block(dye, "_stained_glass_pane"));
        if (b instanceof StainedGlassBlock) return copy(s, block(dye, "_stained_glass"));
        if (b instanceof WallBannerBlock) return copy(s, block(dye, "_wall_banner"));
        if (b instanceof BannerBlock) return copy(s, block(dye, "_banner"));
        return null;
    }

    /** Un plano de obra con los vidrios y estandartes del color de la aldea (los dibujos del estandarte se ponen al terminar). */
    public static void tint(ServerLevel level, TownData.Town town, Blueprints.Plan plan) {
        VillageData.Village v = VillageData.get(level).at(town.center);
        Colors c = v == null ? null : of(v);
        if (c == null) return;
        for (Map.Entry<BlockPos, BlockState> e : plan.blocks.entrySet()) {
            BlockState t = tinted(e.getValue(), c.dye());
            if (t != null && !t.isAir()) e.setValue(t);
        }
    }

    /** Pinta el bloque de {@code p} (si es un vidrio o un estandarte) con los colores {@code c}; true si cambió algo. */
    public static boolean paint(ServerLevel level, BlockPos p, Colors c) {
        BlockState s = level.getBlockState(p);
        BlockState t = tinted(s, c.dye());
        if (t == null || t.isAir()) return false;
        boolean changed = false;
        if (t != s) {
            level.setBlock(p, t, 2);
            changed = true;
        }
        if (level.getBlockEntity(p) instanceof BannerBlockEntity be) {
            ItemStack item = c.banner().isEmpty() ? new ItemStack(ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("minecraft", c.dye().getName() + "_banner"))) : c.banner();
            CompoundTag before = be.saveWithoutMetadata();
            be.fromItem(item, c.dye());
            if (!before.equals(be.saveWithoutMetadata())) {
                be.setChanged();
                level.sendBlockUpdated(p, t, t, 3);
                changed = true;
            }
        }
        return changed;
    }

    /** Repinta todos los edificios, torres y adornos de la aldea; con {@code force}, aunque los colores no hayan cambiado. */
    public static int refresh(ServerLevel level, TownData.Town town, boolean force) {
        VillageData.Village v = VillageData.get(level).at(town.center);
        Colors c = v == null ? null : of(v);
        if (c == null) return 0;
        String key = c.key();
        if (!force && key.equals(town.heraldry)) return 0;
        int n = 0;
        for (Map.Entry<String, BlockPos> e : town.sites.entrySet()) {
            BlockPos corner = e.getValue();
            int size = town.siteSize(e.getKey());
            for (int dx = 0; dx < size; dx++) for (int dz = 0; dz < size; dz++) for (int dy = -1; dy <= Blueprints.CLEAR_HEIGHT; dy++) {
                BlockPos p = corner.offset(dx, dy, dz);
                if (!level.isLoaded(p)) continue;
                if (paint(level, p, c)) n++;
            }
        }
        for (BlockPos p : town.decor) if (level.isLoaded(p) && paint(level, p, c)) n++;
        town.heraldry = key;
        TownData.get(level).changed();
        return n;
    }

    /** Cada 10 s: las aldeas cargadas cuyos colores cambiaron (o nunca se pintaron) se repintan. */
    public static void tick(ServerLevel level) {
        for (TownData.Town t : TownData.get(level).all()) {
            if (!level.isPositionEntityTicking(t.center)) continue;
            if (t.sites.isEmpty() && t.decor.isEmpty()) continue;
            refresh(level, t, false);
        }
    }
}
