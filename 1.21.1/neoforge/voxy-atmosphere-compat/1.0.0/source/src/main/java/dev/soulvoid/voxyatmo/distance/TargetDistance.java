package dev.soulvoid.voxyatmo.distance;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;

/** The distance, in blocks, that clouds and atmosphere should reach. */
public final class TargetDistance {

    /** @return the distant-terrain distance in blocks: SeedGen's generation distance, else Voxy's LOD distance. */
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
        if (terrain <= 0)
            return -1;
        final long scaled = Math.round(terrain * (Config.distancePercent() / 100.0));
        return (int) Math.max(1L, Math.min(scaled, Config.maxCloudDistance()));
    }

    private TargetDistance() {}
}
