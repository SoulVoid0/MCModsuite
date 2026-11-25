package dev.soulvoid.voxyatmo.clouds;

import net.neoforged.fml.loading.FMLEnvironment;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.distance.TargetDistance;

/**
 * Cloud coverage tuning. Simple Clouds keeps at most 5 formations (8 allowed in data packs) inside a 10000-block spawn
 * radius and renders at most 10; Project Atmosphere only spawns within 5000 blocks. At Voxy/SeedGen distances that
 * leaves most of the visible sky empty. Server-side parts only act in the client JVM (single player / LAN host),
 * where the cloud distance is known.
 */
public final class CloudCoverage {

    /** Simple Clouds' own render limit. */
    public static final int SIMPLE_CLOUDS_RENDER_CAP = 10;
    private static final int PA_SPAWN_LIMIT = 5000;

    public static boolean active() {
        return FMLEnvironment.dist.isClient() && Config.moreFormations();
    }

    /** @return how many formations may be rendered at once. */
    public static int renderCap() {
        return active() ? Math.max(SIMPLE_CLOUDS_RENDER_CAP, Config.maxRenderedFormations()) : SIMPLE_CLOUDS_RENDER_CAP;
    }

    /** @return the cloud distance in blocks used for coverage decisions, or -1. */
    public static int reachBlocks() {
        if (!FMLEnvironment.dist.isClient())
            return -1;
        try {
            return TargetDistance.cloudBlocks();
        } catch (final Throwable t) {
            return -1;
        }
    }

    /** @return the formation-count multiplier, in percent. */
    public static int scalePercent() {
        final int configured = Config.formationScalePercent();
        if (configured > 0)
            return configured;
        final int reach = reachBlocks();
        if (reach <= 6000)
            return 300;
        if (reach <= 12000)
            return 400;
        return 600;
    }

    /** Scales one of Simple Clouds' formation limits. */
    public static int scaleFormations(final int original) {
        if (!active() || original <= 0)
            return original;
        final long scaled = Math.round(original * (scalePercent() / 100.0));
        return (int) Math.max(original, Math.min(scaled, renderCap()));
    }

    /** @return Project Atmosphere's maximum spawn distance in blocks. */
    public static double paSpawnLimit(final double original) {
        if (!FMLEnvironment.dist.isClient() || !Config.spawnReachFollowsClouds())
            return original;
        final int reach = reachBlocks();
        return reach > original ? reach : original;
    }

    private CloudCoverage() {}
}
