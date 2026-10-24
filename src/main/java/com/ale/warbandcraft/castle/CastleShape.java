package com.ale.warbandcraft.castle;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;

/**
 * La forma de un castillo ya plantado, en coordenadas del mundo (ya girada): lo que antes se daba por hecho del plano
 * único de 43×28×45. La guarda {@link com.ale.warbandcraft.village.CastleData} por centro; los castillos de antes no
 * tienen y usan {@link com.ale.warbandcraft.village.CastleManager#legacyShape}.
 *
 * @param minX..maxZ  adentro de los muros (patio y edificios), de {@code floorY − INSIDE_BELOW} a {@code + INSIDE_ABOVE}
 * @param floorY      altura del centro (donde se para el capitán)
 * @param radius      de dónde se cuenta lo de afuera en un asedio ({@code Breach.Walls.radius})
 * @param bandMin     un obstáculo entre {@code bandMin} y {@code bandMax} del centro es el muro
 * @param inside      a menos de esto del centro ya entró
 * @param inner       a dónde va el que entra
 * @param claimRadius medio lado del claim de la facción
 * @param halfX       medio ancho, medio largo y alto de la caja donde se busca lugar para el almacén
 */
public record CastleShape(CastleKind kind, Rotation rotation, int minX, int minZ, int maxX, int maxZ, int floorY,
                          int radius, int bandMin, int bandMax, int inside, int inner, int claimRadius,
                          int halfX, int halfZ, int height) {
    /** Adentro de los muros: cuánto abajo y arriba del piso del centro. */
    public static int INSIDE_BELOW = 4, INSIDE_ABOVE = 26;

    public boolean contains(BlockPos p) {
        int dy = p.getY() - floorY;
        return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ && dy >= -INSIDE_BELOW && dy <= INSIDE_ABOVE;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Kind", kind.id);
        t.putString("Rotation", rotation.name());
        t.putIntArray("Box", new int[]{minX, minZ, maxX, maxZ, floorY});
        t.putIntArray("Siege", new int[]{radius, bandMin, bandMax, inside, inner});
        t.putIntArray("Area", new int[]{claimRadius, halfX, halfZ, height});
        return t;
    }

    public static CastleShape load(CompoundTag t) {
        CastleKind kind = CastleKind.byId(t.getString("Kind"));
        Rotation rot;
        try {
            rot = Rotation.valueOf(t.getString("Rotation"));
        } catch (IllegalArgumentException e) {
            rot = Rotation.NONE;
        }
        int[] b = t.getIntArray("Box"), s = t.getIntArray("Siege"), a = t.getIntArray("Area");
        if (b.length < 5 || s.length < 5 || a.length < 4) return null;
        return new CastleShape(kind == null ? CastleKind.CLASSIC : kind, rot, b[0], b[1], b[2], b[3], b[4],
                s[0], s[1], s[2], s[3], s[4], a[0], a[1], a[2], a[3]);
    }
}
