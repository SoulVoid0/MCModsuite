package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.CloudsRenderPipeline;

import dev.soulvoid.voxyatmo.clouds.SimpleCloudsPatch;
import dev.soulvoid.voxyatmo.core.Config;

/**
 * {@code SHADER_SUPPORT} draws clouds in {@code afterLevel}, after vanilla (and Project Atmosphere's) precipitation,
 * which writes no depth, so clouds paint over the rain. When we forced that pipeline, the cloud pass moves to
 * {@code beforeWeather}: still after terrain (the main depth already holds Voxy's LOD depth), now before rain.
 * Project Atmosphere's tornado/hurricane injections live inside {@code afterLevel} and run unchanged.
 * Adapted from Make it Compatible: Voxy (MIT, leon.raineri).
 */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class SimpleCloudsRainOrderMixin {

    @Unique
    private static boolean voxyatmo$relocate(final CloudsRenderPipeline pipeline) {
        return pipeline == CloudsRenderPipeline.SHADER_SUPPORT && SimpleCloudsPatch.forcedThisFrame()
            && Config.rainInFrontOfClouds();
    }

    @Redirect(method = "renderBeforeWeather", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/nonamecrackers2/simpleclouds/client/renderer/pipeline/CloudsRenderPipeline;"
            + "beforeWeather(Lnet/minecraft/client/Minecraft;"
            + "Ldev/nonamecrackers2/simpleclouds/client/renderer/SimpleCloudsRenderer;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDDLnet/minecraft/client/renderer/culling/Frustum;)V"))
    private void voxyatmo$cloudsBeforeWeather(final CloudsRenderPipeline pipeline, final Minecraft mc,
                                              final SimpleCloudsRenderer renderer, final Matrix4f modelView,
                                              final Matrix4f proj, final float partialTick, final double camX,
                                              final double camY, final double camZ, final Frustum frustum) {
        if (voxyatmo$relocate(pipeline)) {
            pipeline.afterLevel(mc, renderer, modelView, proj, partialTick, camX, camY, camZ, frustum);
            mc.getMainRenderTarget().bindWrite(false);
        } else {
            pipeline.beforeWeather(mc, renderer, modelView, proj, partialTick, camX, camY, camZ, frustum);
        }
    }

    @Redirect(method = "renderAfterLevel", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/nonamecrackers2/simpleclouds/client/renderer/pipeline/CloudsRenderPipeline;"
            + "afterLevel(Lnet/minecraft/client/Minecraft;"
            + "Ldev/nonamecrackers2/simpleclouds/client/renderer/SimpleCloudsRenderer;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDDLnet/minecraft/client/renderer/culling/Frustum;)V"))
    private void voxyatmo$skipRelocatedAfterLevel(final CloudsRenderPipeline pipeline, final Minecraft mc,
                                                  final SimpleCloudsRenderer renderer, final Matrix4f modelView,
                                                  final Matrix4f proj, final float partialTick, final double camX,
                                                  final double camY, final double camZ, final Frustum frustum) {
        if (voxyatmo$relocate(pipeline))
            return;
        pipeline.afterLevel(mc, renderer, modelView, proj, partialTick, camX, camY, camZ, frustum);
    }
}
