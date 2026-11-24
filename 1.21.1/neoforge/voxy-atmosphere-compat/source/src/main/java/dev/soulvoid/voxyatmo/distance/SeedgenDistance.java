package dev.soulvoid.voxyatmo.distance;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.fml.loading.FMLPaths;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

/**
 * Voxy SeedGen's distances, in chunks.
 *
 * <p>SeedGen (mod id {@code voxyseedgen}, 0.3.x) keeps its settings in {@code mobius.voxyseedgen.config.VoxySeedGenConfig},
 * a Gson-backed singleton saved to {@code config/voxyseedgen.json}:</p>
 * <ul>
 *   <li>{@code renderDistance}: "Generation distance", terrain generated around the player (16..4096 chunks).</li>
 *   <li>{@code extendedDistance} + {@code distantReach}: the experimental "extended render distance", drawn past
 *       Voxy's own reach from coarser sections (2048 chunks up to the world border). {@code reach()} returns
 *       {@code distantReach} when it is on, else 0.</li>
 * </ul>
 * <p>The live singleton is read reflectively (so the in-game slider is followed immediately); if that fails the JSON
 * file is read instead. Re-read at most every two seconds.</p>
 */
public final class SeedgenDistance {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_CLASS = "mobius.voxyseedgen.config.VoxySeedGenConfig";
    private static final String CONFIG_FILE = "voxyseedgen.json";
    private static final long RECHECK_NANOS = 2_000_000_000L;

    private static long lastCheck;
    private static int generation = -1;
    private static int reach = -1;
    private static String source = "not read yet";
    private static boolean reflectionBroken;
    private static Method get;
    private static Field renderDistance;
    private static Field extendedDistance;
    private static Field distantReach;

    /** @return SeedGen's generation distance in chunks, or -1 when SeedGen is absent or the value is unknown. */
    public static synchronized int chunks() {
        final int override = Config.seedgenDistanceOverride();
        if (override > 0)
            return override;
        refresh();
        return generation;
    }

    /**
     * @return how far SeedGen draws terrain in chunks: the larger of the generation distance and, when the extended
     * render distance is on, its reach. -1 when unknown.
     */
    public static synchronized int reachChunks() {
        final int override = Config.seedgenDistanceOverride();
        if (override > 0)
            return override;
        refresh();
        return Math.max(generation, reach);
    }

    /** @return a short description of where the value came from, for the status command. */
    public static synchronized String source() {
        if (Config.seedgenDistanceOverride() > 0)
            return "override in voxyatmocompat-client.toml";
        return source;
    }

    private static void refresh() {
        if (!ModIds.isSeedgenLoaded()) {
            generation = reach = -1;
            source = "Voxy SeedGen not installed";
            return;
        }
        final long now = System.nanoTime();
        if (lastCheck != 0 && now - lastCheck < RECHECK_NANOS)
            return;
        lastCheck = now;
        if (!reflectionBroken && readLive())
            return;
        readFile();
    }

    private static boolean readLive() {
        try {
            if (get == null) {
                final Class<?> c = Class.forName(CONFIG_CLASS, false, SeedgenDistance.class.getClassLoader());
                get = c.getMethod("get");
                renderDistance = c.getField("renderDistance");
                extendedDistance = c.getField("extendedDistance");
                distantReach = c.getField("distantReach");
            }
            final Object cfg = get.invoke(null);
            if (cfg == null)
                return false;
            store(renderDistance.getInt(cfg), extendedDistance.getBoolean(cfg) ? distantReach.getInt(cfg) : 0,
                "Voxy SeedGen live config");
            return true;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            reflectionBroken = true;
            LOGGER.warn("[Voxy Atmosphere Compat] could not read Voxy SeedGen's config class, falling back to {}",
                CONFIG_FILE, e);
            return false;
        }
    }

    private static void readFile() {
        final Path file = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
        if (!Files.isRegularFile(file)) {
            source = "config/" + CONFIG_FILE + " not found (SeedGen default 256 chunks assumed)";
            store(256, 0, source);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            final JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            final int gen = intOr(o.get("renderDistance"), 256);
            final boolean ext = o.has("extendedDistance") && o.get("extendedDistance").getAsBoolean();
            store(gen, ext ? intOr(o.get("distantReach"), 0) : 0, "config/" + CONFIG_FILE);
        } catch (final Exception e) {
            LOGGER.debug("[Voxy Atmosphere Compat] reading {} failed", file, e);
        }
    }

    private static int intOr(final JsonElement e, final int fallback) {
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
    }

    private static void store(final int gen, final int ext, final String from) {
        final int g = gen > 0 ? gen : -1;
        final int x = ext > 0 ? ext : -1;
        if (g != generation || x != reach)
            LOGGER.info("[Voxy Atmosphere Compat] Voxy SeedGen distance: generation {} chunks, extended reach {} ({})",
                g, x > 0 ? x + " chunks" : "off", from);
        generation = g;
        reach = x;
        source = from;
    }

    private SeedgenDistance() {}
}
