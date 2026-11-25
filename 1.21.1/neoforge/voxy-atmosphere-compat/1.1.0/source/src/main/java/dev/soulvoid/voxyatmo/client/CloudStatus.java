package dev.soulvoid.voxyatmo.client;

import dev.soulvoid.voxyatmo.clouds.CloudLodPlanner;
import dev.soulvoid.voxyatmo.clouds.SimpleCloudsPatch;

/** Kept apart from {@link StatusCommand} so Simple Clouds classes load only when it is installed. */
final class CloudStatus {

    static String describe() {
        return "Clouds: render distance " + Math.round(SimpleCloudsPatch.cloudRenderDistanceBlocks())
            + " blocks, cloud area radius " + CloudLodPlanner.builtRadiusBlocks()
            + " blocks, depth-tested vs LOD this frame: " + SimpleCloudsPatch.forcedThisFrame();
    }

    private CloudStatus() {}
}
