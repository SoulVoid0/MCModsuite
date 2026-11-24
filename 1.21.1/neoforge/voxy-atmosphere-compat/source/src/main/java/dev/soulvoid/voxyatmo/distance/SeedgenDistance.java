package dev.soulvoid.voxyatmo.distance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.fml.loading.FMLPaths;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

/**
 * Voxy SeedGen's generation distance, in chunks.
 *
 * <p>SeedGen is closed source with no API, so the value comes from (1) this mod's override option, else (2) SeedGen's
 * own config file: any file under {@code config/} whose name contains "seedgen", scanned for a numeric key naming a
 * generation distance/radius (16..4096 chunks per SeedGen's documented range). Key choice, most specific first:
 * a key containing "generation" and "distance"/"radius", then "distance", then "radius"; keys about real chunks,
 * threads or minimums are ignored. The file is re-read when it changes, so the in-game SeedGen slider is followed.</p>
 */
public final class SeedgenDistance {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern KV = Pattern.compile("\"?([A-Za-z_][A-Za-z0-9_.\\-]*)\"?\\s*[:=]\\s*(\\d{1,5})\\b");
    private static final int MIN_CHUNKS = 16;
    private static final int MAX_CHUNKS = 4096;
    private static final long RECHECK_NANOS = 2_000_000_000L;

    private static long lastCheck;
    private static Path file;
    private static long fileStamp = Long.MIN_VALUE;
    private static int cachedChunks = -1;
    private static String cachedKey;

    /** @return SeedGen's generation distance in chunks, or -1 when SeedGen is absent or the value is unknown. */
    public static synchronized int chunks() {
        final int override = Config.seedgenDistanceOverride();
        if (override > 0)
            return override;
        if (!ModIds.isSeedgenLoaded())
            return -1;
        final long now = System.nanoTime();
        if (now - lastCheck < RECHECK_NANOS && lastCheck != 0)
            return cachedChunks;
        lastCheck = now;
        try {
            if (file == null || !Files.isRegularFile(file))
                file = findConfigFile();
            if (file == null)
                return cachedChunks;
            final long stamp = Files.getLastModifiedTime(file).toMillis();
            if (stamp != fileStamp) {
                fileStamp = stamp;
                parse(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.debug("[Voxy Atmosphere Compat] reading SeedGen config failed", e);
        }
        return cachedChunks;
    }

    /** @return a short description of where the value came from, for the status command. */
    public static synchronized String source() {
        if (Config.seedgenDistanceOverride() > 0)
            return "override in voxyatmocompat-client.toml";
        if (file == null)
            return "no SeedGen config file found under config/";
        return file.getFileName() + (cachedKey != null ? " key '" + cachedKey + "'" : " (no distance key recognised)");
    }

    private static Path findConfigFile() throws IOException {
        final Path dir = FMLPaths.CONFIGDIR.get();
        if (!Files.isDirectory(dir))
            return null;
        try (Stream<Path> s = Files.walk(dir, 2)) {
            final List<Path> hits = new ArrayList<>(s
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("seedgen"))
                .toList());
            // Prefer client configs (SeedGen is a client mod), then the shortest name.
            hits.sort((a, b) -> {
                final String an = a.getFileName().toString().toLowerCase(Locale.ROOT);
                final String bn = b.getFileName().toString().toLowerCase(Locale.ROOT);
                final int ac = an.contains("client") ? 0 : 1;
                final int bc = bn.contains("client") ? 0 : 1;
                return ac != bc ? Integer.compare(ac, bc) : Integer.compare(an.length(), bn.length());
            });
            final Path pick = hits.isEmpty() ? null : hits.get(0);
            if (pick != null)
                LOGGER.info("[Voxy Atmosphere Compat] reading Voxy SeedGen's generation distance from {}", pick);
            return pick;
        }
    }

    private static void parse(final String text) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        String bestKey = null;
        final Matcher m = KV.matcher(text);
        while (m.find()) {
            final String key = m.group(1);
            final String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("real") || k.contains("thread") || k.contains("min") || k.contains("near"))
                continue;
            final int rank;
            if (k.contains("generation") && (k.contains("distance") || k.contains("radius")))
                rank = 0;
            else if (k.contains("distance"))
                rank = 1;
            else if (k.contains("radius"))
                rank = 2;
            else
                continue;
            final int value;
            try {
                value = Integer.parseInt(m.group(2));
            } catch (final NumberFormatException e) {
                continue;
            }
            if (value < MIN_CHUNKS || value > MAX_CHUNKS)
                continue;
            if (rank < bestRank) {
                bestRank = rank;
                best = value;
                bestKey = key;
            }
        }
        if (best != cachedChunks)
            LOGGER.info("[Voxy Atmosphere Compat] Voxy SeedGen generation distance: {} chunks (key '{}')", best, bestKey);
        cachedChunks = best;
        cachedKey = bestKey;
    }

    private SeedgenDistance() {}
}
