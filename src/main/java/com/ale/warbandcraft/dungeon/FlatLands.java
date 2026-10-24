package com.ale.warbandcraft.dungeon;

import com.ale.warbandcraft.WarbandCraft;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * H25: más biomas llanos (praderas, desiertos, sabanas...). Pedido de Ale: no aplastar el terreno, sino que haya más
 * de los biomas llanos.
 *
 * En Minecraft, la "erosión" decide las dos cosas a la vez: con erosión alta el terreno es llano y ahí van los biomas
 * llanos; con erosión baja, montañas y picos. {@code data/minecraft/worldgen/density_function/overworld/erosion.json}
 * envuelve la de vanilla en {@link Bias} ({@code warbandcraft:flat_bias}), que le suma {@link #shift}. Lo usan el
 * terreno (las curvas de altura) y la elección de bioma, así que salen biomas llanos de verdad, no una pradera encima
 * de una montaña.
 *
 * Solo en mundos nuevos: el corrimiento con que se creó cada mundo queda en {@link #FILE} (en la carpeta del mundo) y
 * no cambia nunca, aunque cambie la configuración. Un mundo de antes de H25 queda en 0 (como vanilla): si no, lo nuevo
 * se generaría con otros biomas y quedaría un corte en el borde.
 */
public final class FlatLands {
    /** Cuánto se corre la erosión hacia lo llano en los mundos nuevos (0 = como vanilla, 0,2 = bastante más llano). */
    public static double EROSION_SHIFT = 0.2;
    /** Archivo en la carpeta del mundo con el corrimiento con que se creó. */
    public static final String FILE = "warbandcraft_terreno.txt";

    /** El corrimiento del mundo que está abierto (lo lee {@link Bias} en cada columna). */
    private static volatile double shift = 0;

    public static final DeferredRegister<Codec<? extends DensityFunction>> TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, WarbandCraft.MODID);

    static {
        TYPES.register("flat_bias", Bias.CODEC::codec);
    }

    private FlatLands() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

    public static double shift() {
        return shift;
    }

    /** Para las pruebas. */
    public static void setShift(double value) {
        shift = value;
    }

    /**
     * Antes de cargar el mundo (ServerAboutToStart, antes de que se genere nada): lee el corrimiento del mundo, o lo
     * fija si es nuevo (nunca abierto: {@code isInitialized} es false).
     */
    public static void onServerAboutToStart(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        double value = 0;
        try {
            if (Files.exists(file)) {
                value = Double.parseDouble(Files.readString(file).trim().split("\\s+")[0]);
            } else {
                boolean fresh = !server.getWorldData().overworldData().isInitialized();
                value = fresh ? Math.max(0, Math.min(1, EROSION_SHIFT)) : 0;
                Files.writeString(file, String.format(Locale.ROOT, "%.3f%n# Warband Craft: cuanto mas llano se genero este mundo (no cambiar)%n", value));
            }
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudo leer/guardar {}: el mundo se genera como vanilla", file, e);
            value = 0;
        }
        shift = value;
        WarbandCraft.LOGGER.info("Warband Craft: biomas llanos {} (erosión +{})", value > 0 ? "sí" : "no", value);
    }

    public static void onServerStopped() {
        shift = 0;
    }

    /** {@code warbandcraft:flat_bias}: la función de adentro + {@link #shift}, sin pasarse de [-1, 1]. */
    public record Bias(DensityFunction argument) implements DensityFunction {
        private static final MapCodec<Bias> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(Bias::argument)).apply(i, Bias::new));
        public static final KeyDispatchDataCodec<Bias> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

        private static double apply(double v) {
            double s = shift;
            return s == 0 ? v : Math.max(-1, Math.min(1, v + s));
        }

        @Override
        public double compute(FunctionContext ctx) {
            return apply(argument.compute(ctx));
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            argument.fillArray(values, provider);
            if (shift == 0) return;
            for (int i = 0; i < values.length; i++) values[i] = apply(values[i]);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Bias(argument.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return argument.minValue();   // sumar no baja nada, y el tope de -1 tampoco
        }

        @Override
        public double maxValue() {
            return Math.max(1, argument.maxValue());
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }
}
