package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pedido de Ale: las estructuras chicas de otros mods (Dungeons and Taverns, Structory) aparecen mucho más en bosques,
 * junglas, taigas, pantanos y montañas (donde no se puede pelear una hueste) y casi nada en los biomas llanos.
 * <p>
 * Al cargarse el overworld se cambian sus "structure sets" (solo en memoria, no se guarda nada): la grilla se achica
 * ({@link #SPACING_FACTOR}) y cada lugar posible tira un dado según el bioma de ese chunk: {@link #KEEP_DENSE} en
 * {@code #warbandcraft:dense_structures}, {@link #KEEP_SPARSE} en {@code #warbandcraft:sparse_structures},
 * {@link #KEEP_OTHER} en el resto. El dado sale de la semilla: el mismo mundo da siempre lo mismo.
 * No llama código de esos mods: solo mira los ids de sus structure sets.
 */
public final class StructureDensity {
    /** La grilla de esos mods se achica a esto (0,55 = unas 3 veces más lugares posibles). */
    public static double SPACING_FACTOR = 0.55;
    /** Chance de que un lugar posible tenga su estructura: bosques, junglas, taigas, pantanos, montañas. */
    public static double KEEP_DENSE = 1.0;
    /** Lo mismo en biomas llanos (llanuras, sabana, desierto, llanura nevada): alguna que otra. */
    public static double KEEP_SPARSE = 0.15;
    /** Lo mismo en el resto (y biomas de otros mods que no estén en las listas). */
    public static double KEEP_OTHER = 0.5;

    /** Mods cuyos structure sets se tocan. */
    private static final Set<String> NAMESPACES = Set.of("nova_structures", "structory", "structory_towers");
    /** Structure sets de esos mods que no se tocan: aldeas (las de las facciones salen de las campanas) y Nether/End. */
    private static final Set<String> SKIP = Set.of("nova_structures:villages_jungle", "nova_structures:villages_swamp");

    public static final TagKey<Biome> DENSE = TagKey.create(Registries.BIOME, new ResourceLocation(WarbandCraft.MODID, "dense_structures"));
    public static final TagKey<Biome> SPARSE = TagKey.create(Registries.BIOME, new ResourceLocation(WarbandCraft.MODID, "sparse_structures"));

    /** Para que el dado no salga igual que el de la grilla. */
    private static final int SALT = 0x57A2C0DE;

    private StructureDensity() {}

    /** Desde {@code LevelEvent.Load}: cambia los structure sets del overworld. */
    public static void apply(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        try {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
            Field listField = null;
            List<Field> mapFields = new ArrayList<>();
            for (Field f : ChunkGeneratorStructureState.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (List.class.isAssignableFrom(f.getType())) listField = f;
                else if (Map.class.isAssignableFrom(f.getType())) mapFields.add(f);
            }
            if (listField == null) {
                WarbandCraft.LOGGER.warn("Estructuras por bioma: no encontré la lista de structure sets");
                return;
            }
            listField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Holder<StructureSet>> sets = (List<Holder<StructureSet>>) listField.get(state);
            Map<StructurePlacement, StructurePlacement> swapped = new IdentityHashMap<>();
            List<Holder<StructureSet>> out = new ArrayList<>(sets.size());
            for (Holder<StructureSet> h : sets) {
                Holder<StructureSet> replaced = h;
                String id = h.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString).orElse(null);
                if (id != null && touches(id) && h.value().placement() instanceof RandomSpreadStructurePlacement old
                        && !(old instanceof Weighted)) {
                    Weighted w = Weighted.of(old, biomes, state.randomState());
                    if (w != null) {
                        swapped.put(old, w);
                        replaced = Holder.direct(new StructureSet(h.value().structures(), w));
                    }
                }
                out.add(replaced);
            }
            if (swapped.isEmpty()) return;
            listField.set(state, List.copyOf(out));
            // Si ya se armó la lista de lugares por estructura (/locate, mapas, rumores), se cambia ahí también
            for (Field f : mapFields) {
                f.setAccessible(true);
                if (!(f.get(state) instanceof Map<?, ?> map)) continue;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof StructurePlacement) {
                        @SuppressWarnings("unchecked")
                        List<StructurePlacement> placements = (List<StructurePlacement>) list;
                        try {
                            placements.replaceAll(p -> swapped.getOrDefault(p, p));
                        } catch (UnsupportedOperationException ignored) {
                            // lista fija: queda como estaba (solo afecta a /locate)
                        }
                    }
                }
            }
            WarbandCraft.LOGGER.info("Estructuras por bioma: {} structure sets cambiados", swapped.size());
        } catch (Exception e) {
            WarbandCraft.LOGGER.error("Estructuras por bioma: no se pudo aplicar", e);
        }
    }

    public static boolean touches(String id) {
        if (SKIP.contains(id)) return false;
        int colon = id.indexOf(':');
        if (colon < 0 || !NAMESPACES.contains(id.substring(0, colon))) return false;
        String path = id.substring(colon + 1);
        return !path.contains("nether") && !path.contains("end_");
    }

    /** Chance de quedarse según el bioma. */
    public static double keep(Holder<Biome> biome) {
        if (biome.is(DENSE)) return KEEP_DENSE;
        if (biome.is(SPARSE)) return KEEP_SPARSE;
        return KEEP_OTHER;
    }

    /** Bioma en el medio del chunk, a la altura del mar (en cuartos de bloque, como lo mide el generador). */
    public static Holder<Biome> biomeAt(BiomeSource biomes, RandomState randomState, int chunkX, int chunkZ) {
        return biomes.getNoiseBiome((chunkX << 2) + 2, 63 >> 2, (chunkZ << 2) + 2, randomState.sampler());
    }

    /** La grilla de siempre, más chica, con el dado del bioma. */
    public static final class Weighted extends RandomSpreadStructurePlacement {
        private final BiomeSource biomes;
        private final RandomState randomState;
        private final int saltCopy;

        private Weighted(Vec3i offset, FrequencyReductionMethod method, float frequency, int salt, Optional<ExclusionZone> exclusion,
                         int spacing, int separation, net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType spread,
                         BiomeSource biomes, RandomState randomState) {
            super(offset, method, frequency, salt, exclusion, spacing, separation, spread);
            this.biomes = biomes;
            this.randomState = randomState;
            this.saltCopy = salt;
        }

        /** Copia {@code old} con la grilla achicada. Los datos protegidos se leen por tipo (sirve con y sin ofuscar). */
        @SuppressWarnings("unchecked")
        public static Weighted of(RandomSpreadStructurePlacement old, BiomeSource biomes, RandomState randomState) throws IllegalAccessException {
            Vec3i offset = Vec3i.ZERO;
            FrequencyReductionMethod method = FrequencyReductionMethod.DEFAULT;
            float frequency = 1f;
            int salt = 0;
            Optional<ExclusionZone> exclusion = Optional.empty();
            for (Field f : StructurePlacement.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(old);
                if (f.getType() == Vec3i.class) offset = (Vec3i) v;
                else if (f.getType() == FrequencyReductionMethod.class) method = (FrequencyReductionMethod) v;
                else if (f.getType() == float.class) frequency = (Float) v;
                else if (f.getType() == int.class) salt = (Integer) v;
                else if (f.getType() == Optional.class) exclusion = (Optional<ExclusionZone>) v;
            }
            int spacing = Math.max(4, (int) Math.round(old.spacing() * SPACING_FACTOR));
            int separation = Math.max(1, Math.min(spacing - 2, (int) Math.round(old.separation() * SPACING_FACTOR)));
            return new Weighted(offset, method, frequency, salt, exclusion, spacing, separation, old.spreadType(), biomes, randomState);
        }

        @Override
        public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
            if (!super.isStructureChunk(state, x, z)) return false;
            double keep = keep(biomeAt(biomes, randomState, x, z));
            if (keep >= 1) return true;
            if (keep <= 0) return false;
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureWithSalt(state.getLevelSeed(), x, z, saltCopy ^ SALT);
            return random.nextDouble() < keep;
        }
    }
}
