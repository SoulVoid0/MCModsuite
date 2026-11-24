package dev.soulvoid.voxyatmo.clouds;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.soulvoid.voxyatmo.core.Config;

/**
 * Stretches the shader fog around Simple Clouds' pass so clouds fade over their own render distance, not the
 * terrain's. Simple Clouds' screen-space world fog reads the {@code RenderSystem} fog uniforms, which carry the
 * near-terrain fog; once clouds reach Voxy/SeedGen distances every cloud past the terrain fog end clamps to solid fog
 * colour. Start and end are scaled by the same ratio (the fog keeps its shape) and restored right after the pass.
 * Only ever pushes fog out. Adapted from Make it Compatible: Voxy (MIT, leon.raineri).
 */
public final class CloudFogBridge {

    private static float savedStart;
    private static float savedEnd;
    private static boolean active;

    public static void push() {
        if (active || !Config.fogFollowsClouds())
            return;
        final float start = RenderSystem.getShaderFogStart();
        final float end = RenderSystem.getShaderFogEnd();
        if (!Float.isFinite(start) || !Float.isFinite(end) || !(end > start) || end <= 0.0F || end > 1.0e7F)
            return;
        final float cloud = SimpleCloudsPatch.cloudRenderDistanceBlocks();
        if (!(cloud > 0.0F))
            return;
        final float target = cloud * (Config.fogPercent() / 100.0F);
        if (target <= end)
            return;
        final float ratio = target / end;
        savedStart = start;
        savedEnd = end;
        active = true;
        RenderSystem.setShaderFogStart(start * ratio);
        RenderSystem.setShaderFogEnd(target);
    }

    public static void pop() {
        if (!active)
            return;
        active = false;
        RenderSystem.setShaderFogStart(savedStart);
        RenderSystem.setShaderFogEnd(savedEnd);
    }

    private CloudFogBridge() {}
}
