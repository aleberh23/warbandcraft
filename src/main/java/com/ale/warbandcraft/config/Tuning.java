package com.ale.warbandcraft.config;

import com.ale.warbandcraft.WarbandCraft;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Todos los números de juego en un archivo: {@code config/warbandcraft-common.toml} (pedido de Ale).
 * <p>
 * Número de juego = campo {@code static} no {@code final} int/long/double/float en MAYÚSCULAS de las clases de
 * {@link TuningClasses} (las lista {@code tools/gen_tuning.py}). El valor del código es el de entrada; al cargar el
 * archivo (y cada vez que se edita con el juego abierto) se pisan los campos. Sección = paquete.Clase.
 * Ojo: una constante calculada con otra (p. ej. {@code A = B * 2}) toma el valor de entrada de B; se configura aparte.
 */
public final class Tuning {
    private record Entry(Field field, ForgeConfigSpec.ConfigValue<?> value) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static ForgeConfigSpec spec;

    private Tuning() {}

    public static void register(IEventBus modBus) {
        Properties comments = new Properties();
        try (InputStream in = Tuning.class.getResourceAsStream("/warbandcraft_tuning.properties")) {
            if (in != null) comments.load(in);
        } catch (Exception e) {
            WarbandCraft.LOGGER.warn("No se pudieron leer los comentarios de la configuración", e);
        }
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        for (String name : TuningClasses.NAMES) {
            Class<?> cls;
            try {
                cls = Class.forName("com.ale.warbandcraft." + name);
            } catch (Throwable e) {
                WarbandCraft.LOGGER.warn("Configuración: no se pudo cargar {}", name, e);
                continue;
            }
            b.push(name);
            for (Field f : cls.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) || Modifier.isFinal(mod) || !f.getName().matches("[A-Z][A-Z_0-9]*")) continue;
                Class<?> t = f.getType();
                if (t != int.class && t != long.class && t != double.class && t != float.class) continue;
                try {
                    f.setAccessible(true);
                    String comment = comments.getProperty(name + "." + f.getName(), "");
                    if (!comment.isEmpty()) b.comment(comment);
                    ForgeConfigSpec.ConfigValue<?> v;
                    if (t == int.class) v = b.defineInRange(f.getName(), f.getInt(null), Integer.MIN_VALUE, Integer.MAX_VALUE);
                    else if (t == long.class) v = b.defineInRange(f.getName(), f.getLong(null), Long.MIN_VALUE, Long.MAX_VALUE);
                    else v = b.defineInRange(f.getName(), f.getDouble(null), -Double.MAX_VALUE, Double.MAX_VALUE);
                    ENTRIES.add(new Entry(f, v));
                } catch (Exception e) {
                    WarbandCraft.LOGGER.warn("Configuración: {}.{} no se pudo leer", name, f.getName(), e);
                }
            }
            b.pop(name.split("[.]").length);   // push("village.Founding") abre dos niveles
        }
        spec = b.build();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec, WarbandCraft.MODID + "-common.toml");
        modBus.addListener((ModConfigEvent.Loading e) -> apply(e.getConfig()));
        modBus.addListener((ModConfigEvent.Reloading e) -> apply(e.getConfig()));
    }

    private static void apply(ModConfig config) {
        if (config.getSpec() != spec) return;
        int n = 0;
        for (Entry e : ENTRIES) {
            try {
                Object v = e.value.get();
                Class<?> t = e.field.getType();
                if (t == int.class) e.field.setInt(null, ((Number) v).intValue());
                else if (t == long.class) e.field.setLong(null, ((Number) v).longValue());
                else if (t == double.class) e.field.setDouble(null, ((Number) v).doubleValue());
                else e.field.setFloat(null, ((Number) v).floatValue());
                n++;
            } catch (Exception ex) {
                WarbandCraft.LOGGER.warn("Configuración: no se pudo poner {}", e.field, ex);
            }
        }
        WarbandCraft.LOGGER.info("Configuración: {} números cargados de {}", n, config.getFileName());
    }
}
