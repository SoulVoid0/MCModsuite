package dev.soulvoid.voxyatmo.distance;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import me.cortex.voxy.client.config.VoxyConfig;

/**
 * Read/write access to voxy-forged's config singleton. Voxy has no public API; {@code VoxyConfig.CONFIG} is the
 * stable point (voxy-forged 1.1.0: {@code sectionRenderDistance} counts 32x32-chunk regions = 512 blocks, and
 * {@code getLodRenderDistanceBlocks()} clamps it to 64..32768 blocks).
 *
 * <p>References Voxy classes directly: only call when the {@code voxy} mod is loaded.</p>
 */
public final class VoxyBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int BLOCKS_PER_SECTION = 512;
    private static final int MAX_SECTIONS = 64; // 32768 blocks, voxy-forged's own clamp

    private static volatile int lastValidBlocks = -1;
    private static int raisedTo = -1;

    /** @return Voxy's LOD render distance in blocks, or the last valid reading, or -1 if never readable. */
    public static int renderDistanceBlocks() {
        try {
            final VoxyConfig cfg = VoxyConfig.CONFIG;
            if (cfg != null && cfg.sectionRenderDistance > 0) {
                lastValidBlocks = cfg.getLodRenderDistanceBlocks();
                return lastValidBlocks;
            }
        } catch (final Throwable ignored) {
            // Voxy reloading its config: keep the cached value
        }
        return lastValidBlocks;
    }

    /** @return whether Voxy rendering is switched on. */
    public static boolean renderingEnabled() {
        try {
            final VoxyConfig cfg = VoxyConfig.CONFIG;
            return cfg != null && cfg.isRenderingEnabled();
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Raises (never lowers) Voxy's LOD render distance so it covers {@code blocks}. Applied to the live config
     * object; it is persisted only if the player later saves Voxy's settings.
     */
    public static void ensureRenderDistanceAtLeast(final int blocks) {
        if (blocks <= 0)
            return;
        try {
            final VoxyConfig cfg = VoxyConfig.CONFIG;
            if (cfg == null)
                return;
            final int wanted = Math.min(MAX_SECTIONS, (blocks + BLOCKS_PER_SECTION - 1) / BLOCKS_PER_SECTION);
            if (cfg.sectionRenderDistance >= wanted)
                return;
            final int before = cfg.sectionRenderDistance;
            cfg.sectionRenderDistance = wanted;
            if (raisedTo != wanted) {
                raisedTo = wanted;
                LOGGER.info("[Voxy Atmosphere Compat] Voxy LOD render distance raised {} -> {} sections ({} blocks) "
                    + "to cover Voxy SeedGen's generation distance.", before, wanted, wanted * BLOCKS_PER_SECTION);
            }
        } catch (final Throwable t) {
            LOGGER.warn("[Voxy Atmosphere Compat] could not adjust Voxy's render distance", t);
        }
    }

    private VoxyBridge() {}
}
