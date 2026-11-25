package dev.soulvoid.voxyatmo.clouds;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import dev.nonamecrackers2.simpleclouds.api.client.event.ModifyCloudRenderDistanceEvent;
import dev.nonamecrackers2.simpleclouds.client.event.impl.DetermineCloudRenderPipelineEvent;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.core.ModIds;
import dev.soulvoid.voxyatmo.distance.TargetDistance;
import dev.soulvoid.voxyatmo.distance.VoxyBridge;

/**
 * Voxy x Simple Clouds event handlers (client, NeoForge game bus). Registered only when both mods are present.
 *
 * <h2>Depth: clouds above and below LOD terrain</h2>
 * <p>Without shaders, Simple Clouds uses its {@code DEFAULT} pipeline: clouds render in {@code afterSky}, before any
 * terrain, and their depth is copied into the main depth buffer. voxy-forged then builds its LOD stencil from that
 * buffer ({@code AbstractRenderPipeline.initDepthStencil}: every pixel whose depth is not 1.0 counts as near vanilla
 * terrain and is masked out of the LOD pass). Every cloud pixel is therefore treated as near terrain: LOD terrain in
 * front of a cloud is cut out and the cloud shows through, and when the camera is above the clouds the LOD below
 * them is never drawn where clouds cover it.</p>
 * <p>Forcing {@code SHADER_SUPPORT} fixes both directions: clouds render after the level, copy the main depth (which
 * now holds Voxy's LOD depth, blitted in with the vanilla projection) into the cloud target and depth-test against
 * it. A cloud nearer than the LOD draws over it; a cloud behind LOD terrain is hidden; above or below the camera
 * makes no difference. Iris already selects {@code SHADER_SUPPORT}. Distant Horizons installs its own pipeline,
 * which is left alone.</p>
 *
 * <h2>Distance</h2>
 * <p>{@link ModifyCloudRenderDistanceEvent} sets the cloud fog range (Simple Clouds fogs from 1/4 of it to all of it).
 * It is driven from Voxy SeedGen's generation distance, else Voxy's LOD distance. The geometry itself is limited by
 * the cloud mesh area, which {@link CloudLodPlanner} grows to match.</p>
 */
public final class SimpleCloudsPatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean forcedThisFrame;
    private static volatile float cloudRenderDistanceBlocks = -1.0F;
    private static int lastLogged = -1;

    /** @return whether this frame's {@code SHADER_SUPPORT} pipeline was forced by us over a non-shader default. */
    public static boolean forcedThisFrame() {
        return forcedThisFrame;
    }

    /** @return the cloud render distance in force this frame, in blocks, or -1 before the first frame. */
    public static float cloudRenderDistanceBlocks() {
        return cloudRenderDistanceBlocks;
    }

    @SubscribeEvent
    public static void onDeterminePipeline(final DetermineCloudRenderPipelineEvent event) {
        forcedThisFrame = false;
        if (!Config.cloudsDepthTestLod() || ModIds.isLoaded(ModIds.DISTANT_HORIZONS) || !VoxyBridge.renderingEnabled())
            return;
        final CloudsRenderPipeline current =
            event.getOverridenPipeline() != null ? event.getOverridenPipeline() : event.getRenderPipeline();
        if (current == CloudsRenderPipeline.DEFAULT) {
            event.overridePipeline(CloudsRenderPipeline.SHADER_SUPPORT);
            forcedThisFrame = true;
        }
    }

    /** Runs last so the final value is ours, then records whatever is in force for the fog bridge. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onModifyRenderDistance(final ModifyCloudRenderDistanceEvent event) {
        try {
            final int target = TargetDistance.cloudBlocks();
            if (target > 0) {
                event.setRenderDistance(target);
                if (target != lastLogged) {
                    lastLogged = target;
                    LOGGER.info("[Voxy Atmosphere Compat] cloud render distance set to {} blocks.", target);
                }
            }
        } finally {
            cloudRenderDistanceBlocks = event.getRenderDistance();
        }
    }

    private SimpleCloudsPatch() {}
}
