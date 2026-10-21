package com.ale.warbandcraft.faction;

import com.talhanation.recruits.FactionEvents;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.world.RecruitsFaction;
import com.talhanation.recruits.world.RecruitsFactionManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Cómo se ve una facción en sus soldados: el estandarte que reemplaza a cualquier estandarte del kit,
 * el color con que se tiñe el cuero y el estandarte pintado en los escudos.
 *
 * Sale de una facción NPC (su JSON) o de la facción de Recruits del jugador (H6): nombre, colores y
 * estandarte se editan en la pantalla de facción de Recruits y acá solo se leen.
 */
public record FactionStyle(ItemStack banner, int armorColor) {

    /**
     * Colores de unidad de Recruits, en el mismo orden que su pantalla de facción
     * ({@code RecruitsFaction.unitColor} es el índice). Copiados como valores RGB porque su lista vive
     * en una clase de pantalla, que no existe en un servidor dedicado.
     */
    private static final int[] RECRUITS_UNIT_COLORS = {
            0xFFFFFF, 0x000000, 0xD3D3D3, 0x808080, 0x404040, // blanco, negro, gris claro, gris, gris oscuro
            0x337B9A, 0x335C9A, 0x15253E,                     // celeste, azul, azul oscuro
            0x269C08, 0x008000, 0x0E3903,                     // verde claro, verde, verde oscuro
            0xB91515, 0x941010, 0x4A0808,                     // rojo claro, rojo, rojo oscuro
            0x8C4C18, 0x5E3B1D, 0x3F2713,                     // marrón claro, marrón, marrón oscuro
            0x009696, 0x174444, 0x0B2222,                     // cian claro, cian, cian oscuro
            0xEDCA12, 0xE56A11, 0xFF00FF, 0x570265, 0xDD9A19  // amarillo, naranja, magenta, violeta, dorado
    };

    /** Aplica el estilo a una pieza: estandarte → el propio, cuero → teñido. Devuelve la pieza a usar. */
    public ItemStack apply(ItemStack stack) {
        if (stack.getItem() instanceof BannerItem) {
            return banner.isEmpty() ? stack : banner.copy();
        }
        if (stack.getItem() instanceof DyeableLeatherItem dyeable) {
            dyeable.setColor(stack, armorColor);
        }
        // H26: escudo (vanilla o de Epic Knights) con el estandarte de la facción pintado, como en la mesa de crafteo
        if (stack.getItem() instanceof ShieldItem && banner.getItem() instanceof BannerItem b) {
            CompoundTag be = banner.getTagElement("BlockEntityTag");
            CompoundTag tag = be == null ? new CompoundTag() : be.copy();
            tag.putInt("Base", b.getColor().getId());
            if (!tag.equals(stack.getTagElement("BlockEntityTag"))) stack.addTagElement("BlockEntityTag", tag);
        }
        return stack;
    }

    /** Pasa todo el equipo puesto del soldado por el estilo. */
    public void applyTo(AbstractRecruitEntity soldier) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = soldier.getItemBySlot(slot);
            if (worn.isEmpty()) continue;
            ItemStack styled = apply(worn.copy());
            if (!ItemStack.matches(styled, worn)) soldier.setItemSlot(slot, styled);
        }
    }

    // ---------- Estilo del jugador (su facción de Recruits) ----------

    /** El estilo de la facción de Recruits del soldado (la de su dueño), o null si no tiene. */
    @Nullable
    public static FactionStyle forSoldier(AbstractRecruitEntity soldier) {
        RecruitsFaction faction = recruitsFaction(soldier.getTeam());
        if (faction == null && soldier.getOwner() instanceof ServerPlayer owner) {
            faction = recruitsFaction(owner.getTeam());
        }
        return faction == null ? null : of(faction);
    }

    @Nullable
    public static FactionStyle forPlayer(ServerPlayer player) {
        RecruitsFaction faction = recruitsFaction(player.getTeam());
        return faction == null ? null : of(faction);
    }

    public static FactionStyle of(RecruitsFaction faction) {
        CompoundTag tag = faction.getBanner();
        ItemStack banner = tag == null || tag.isEmpty() ? ItemStack.EMPTY : ItemStack.of(tag);
        int index = faction.getUnitColor();
        int color = index >= 0 && index < RECRUITS_UNIT_COLORS.length ? RECRUITS_UNIT_COLORS[index] : RECRUITS_UNIT_COLORS[0];
        // Sin estandarte cargado en Recruits se deja el del kit (estandarte blanco) en vez de sacarlo.
        return new FactionStyle(banner.getItem() instanceof BannerItem ? banner : ItemStack.EMPTY, color);
    }

    /** La facción de Recruits del jugador (la de su equipo del marcador), o null. */
    @Nullable
    public static RecruitsFaction recruitsFactionOf(ServerPlayer player) {
        return recruitsFaction(player.getTeam());
    }

    @Nullable
    private static RecruitsFaction recruitsFaction(@Nullable Team team) {
        RecruitsFactionManager manager = FactionEvents.recruitsFactionManager;
        if (team == null || manager == null) return null;
        return manager.getFactionByStringID(team.getName());
    }
}
