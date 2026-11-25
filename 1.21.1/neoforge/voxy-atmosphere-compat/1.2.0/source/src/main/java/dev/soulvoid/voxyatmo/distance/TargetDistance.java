package dev.soulvoid.voxyatmo.distance;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

/** The distance, in blocks, that clouds and atmosphere should reach. */
public final class TargetDistance {

    /**
     * @return the distant-terrain distance in blocks: SeedGen's generation reach when SeedGen is installed (that is
     *         how far terrain exists), else Voxy's LOD render distance, else -1. Public contract used by other mods
     *         (kept since 1.0.0).
     */
    public static int terrainBlocks() {
        final int seedgen = SeedgenDistance.reachChunks();
        if (seedgen > 0)
            return seedgen * 16;
        return ModIds.isLoaded(ModIds.VOXY) ? VoxyBridge.renderDistanceBlocks() : -1;
    }

    /** @return the cloud render distance to enforce in blocks, or -1 to leave Simple Clouds' own value alone. */
    public static int cloudBlocks() {
        if (!Config.cloudsFollowGeneration())
            return -1;
        final int terrain = terrainBlocks();
        long target = terrain > 0 ? Math.round(terrain * (Config.distancePercent() / 100.0)) : -1L;
        target = Math.max(target, DistanceRequests.maxBlocks());
        if (target <= 0)
            return -1;
        return (int) Math.max(1L, Math.min(target, Config.maxCloudDistance()));
    }

    private TargetDistance() {}
}
