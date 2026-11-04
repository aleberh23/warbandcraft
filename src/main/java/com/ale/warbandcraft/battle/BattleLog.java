package com.ale.warbandcraft.battle;

import com.ale.warbandcraft.WarbandCraft;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pedido de Ale (4/11): todo lo que pasa en las batallas, en un archivo aparte para mejorarlas después:
 * {@code logs/warbandcraft-batallas.txt} en la carpeta del servidor (o del juego). Cada batalla tiene un nombre corto
 * ({@code key}) y un centro: empieza ({@link #begin}), se anota lo que pasa ({@link #line}: carriles, tácticas, planes),
 * cada {@code SNAPSHOT_SECONDS} cuántos quedan en pie de cada bando cerca, cada caído con quién lo mató, y cómo termina
 * ({@link #end}). Una batalla sin novedades {@code IDLE_SECONDS} se da por terminada sola.
 */
public final class BattleLog {
    private BattleLog() {}

    /** Cada cuánto se cuenta quién queda en pie en cada batalla (segundos). */
    public static int SNAPSHOT_SECONDS = 30;
    /** Una batalla sin nada que anotar se cierra sola a los tantos segundos. */
    public static int IDLE_SECONDS = 300;
    /** Hasta dónde del centro de una batalla cuenta un caído o un soldado en pie (bloques). */
    public static int RANGE = 96;
    /** Con el archivo más grande que esto (MB) se pasa a {@code -viejo.txt} y se empieza otro. */
    public static int MAX_MB = 20;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private record Battle(String key, String name, BlockPos center, long started, long[] last, int[] fallen) {}

    private static final Map<String, Battle> ACTIVE = new LinkedHashMap<>();
    @Nullable private static BufferedWriter out;

    /** Empieza una batalla (si ya estaba, solo anota). */
    public static void begin(ServerLevel level, String key, String name, BlockPos center, String detail) {
        Battle b = ACTIVE.get(key);
        if (b == null) {
            b = new Battle(key, name, center, level.getGameTime(), new long[]{level.getGameTime()}, new int[1]);
            ACTIVE.put(key, b);
            write("=== EMPIEZA [" + key + "] " + name + " en " + center.toShortString() + (detail.isEmpty() ? "" : " — " + detail));
        } else {
            line(key, detail);
        }
    }

    /** Algo que pasó en una batalla (si no está anotada, igual se escribe con su clave). */
    public static void line(String key, String what) {
        if (what == null || what.isEmpty()) return;
        Battle b = ACTIVE.get(key);
        if (b != null) b.last[0] = Long.MAX_VALUE / 4;   // "ahora": se corrige en el próximo tick
        write("[" + key + "] " + what);
    }

    /** Algo que pasó cerca de un lugar: va a la batalla más cercana a {@code RANGE}, o sola. */
    public static void lineAt(BlockPos at, String what) {
        Battle b = nearest(Vec3.atCenterOf(at));
        line(b != null ? b.key : "?", what);
    }

    /** Terminó: cuánto duró, cuántos cayeron cerca y cómo. */
    public static void end(ServerLevel level, String key, String result) {
        Battle b = ACTIVE.remove(key);
        if (b == null) {
            write("[" + key + "] termina: " + result);
            return;
        }
        long secs = (level.getGameTime() - b.started) / 20;
        write("=== TERMINA [" + key + "] " + b.name + ": " + result + " (" + secs / 60 + " min " + secs % 60 + " s, " + b.fallen[0] + " caídos cerca)");
    }

    /** Termina la batalla que está en {@code center} (por si no se sabe la clave). */
    public static void endAt(ServerLevel level, BlockPos center, String result) {
        for (Battle b : new ArrayList<>(ACTIVE.values())) if (b.center.distSqr(center) < 4 * 4) end(level, b.key, result);
    }

    // ------------------------------------------------------------------ desde los eventos

    /** Un caído: si está cerca de una batalla, quién era, dónde, quién lo mató y con qué. */
    public static void onDeath(LivingEntity dead, DamageSource src) {
        if (!(dead instanceof AbstractRecruitEntity) && !(dead instanceof Player)) return;
        Battle b = nearest(dead.position());
        if (b == null) return;
        b.fallen[0]++;
        Entity killer = src.getEntity();
        String by = killer == null ? src.getMsgId() : who(killer) + (killer instanceof LivingEntity l && !l.getMainHandItem().isEmpty()
                ? " con " + l.getMainHandItem().getItem().toString() : "") + " a " + Math.round(killer.distanceTo(dead)) + " bloques";
        line(b.key, "† " + who(dead) + " en " + dead.blockPosition().toShortString() + " (a " + Math.round(Math.sqrt(dead.blockPosition().distSqr(b.center)))
                + " del centro) por " + by);
    }

    /** Cada segundo: cuenta de cada bando cada {@code SNAPSHOT_SECONDS}, y cierra las que quedaron quietas. */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Battle b : new ArrayList<>(ACTIVE.values())) {
            if (b.last[0] == Long.MAX_VALUE / 4) b.last[0] = now;
            if (now - b.last[0] > IDLE_SECONDS * 20L) {
                end(level, b.key, "sin novedades hace " + IDLE_SECONDS + " s");
                continue;
            }
            if ((now - b.started) % (SNAPSHOT_SECONDS * 20L) >= 20) continue;
            if (!level.isLoaded(b.center)) continue;
            Map<String, int[]> sides = new TreeMap<>();
            for (AbstractRecruitEntity s : level.getEntitiesOfClass(AbstractRecruitEntity.class, new AABB(b.center).inflate(RANGE, 48, RANGE), e -> e.isAlive())) {
                int[] c = sides.computeIfAbsent(side(s), k -> new int[2]);
                c[0]++;
                if (s.getTarget() != null) c[1]++;
            }
            StringBuilder sb = new StringBuilder("en pie: ");
            sides.forEach((k, c) -> sb.append(k).append(' ').append(c[0]).append(" (").append(c[1]).append(" peleando) · "));
            write("[" + b.key + "] " + (sides.isEmpty() ? "nadie cerca" : sb.substring(0, sb.length() - 3)));
        }
    }

    // ------------------------------------------------------------------ ayudas

    private static String side(AbstractRecruitEntity s) {
        var pd = s.getPersistentData();
        if (s.getOwnerUUID() != null) return s.getTeam() != null ? "tuyos/" + s.getTeam().getName() : "con dueño";
        if (pd.contains("warbandcraft:faction")) return pd.getString("warbandcraft:faction").replace("warbandcraft:", "");
        return "otros";
    }

    private static String who(Entity e) {
        if (e instanceof Player p) return "jugador " + p.getName().getString();
        String n = e.getName().getString();
        if (e instanceof AbstractRecruitEntity s) return n + " [" + side(s) + ", nv " + s.getXpLevel() + "]";
        return n;
    }

    @Nullable
    private static Battle nearest(Vec3 p) {
        Battle best = null;
        double bd = (double) RANGE * RANGE;
        for (Battle b : ACTIVE.values()) {
            double d = Vec3.atCenterOf(b.center).distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    private static synchronized void write(String text) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Path file = dir.resolve("warbandcraft-batallas.txt");
            if (out == null) {
                Files.createDirectories(dir);
                if (Files.exists(file) && Files.size(file) > MAX_MB * 1024L * 1024L)
                    Files.move(file, dir.resolve("warbandcraft-batallas-viejo.txt"), StandardCopyOption.REPLACE_EXISTING);
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write(ZonedDateTime.now(ZONE).format(TIME) + " " + text);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            WarbandCraft.LOGGER.warn("No se pudo escribir el registro de batallas: {}", e.toString());
            out = null;
        }
    }

    /** Al cerrar el servidor. */
    public static synchronized void close() {
        ACTIVE.clear();
        if (out != null) try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }
}
