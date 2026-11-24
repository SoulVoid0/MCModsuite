package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.ShaderSupportPipeline;
import dev.nonamecrackers2.simpleclouds.common.config.SimpleCloudsConfig;

import dev.soulvoid.voxyatmo.clouds.SimpleCloudsPatch;
import dev.soulvoid.voxyatmo.core.Config;

/**
 * Puts back Simple Clouds' 2D atmospheric cloud layer when we force {@code SHADER_SUPPORT}: only the {@code DEFAULT}
 * pipeline draws it (in {@code afterSky}). Same call, same phase, same Simple Clouds toggle; the main target is
 * rebound after, as Simple Clouds does. Adapted from Make it Compatible: Voxy (MIT, leon.raineri).
 */
@Mixin(value = ShaderSupportPipeline.class, remap = false)
public abstract class ShaderSupportPipelineMixin {

    @Inject(method = "afterSky", at = @At("HEAD"), require = 0)
    private void voxyatmo$restoreAtmosphericLayer(final Minecraft mc, final SimpleCloudsRenderer renderer,
                                                  final Matrix4f modelViewMatrix, final Matrix4f projectionMatrix,
                                                  final float partialTick, final double camX, final double camY,
                                                  final double camZ, final Frustum frustum, final CallbackInfo ci) {
        if (!SimpleCloudsPatch.forcedThisFrame() || !Config.restoreAtmosphericLayer())
            return;
        if (!SimpleCloudsConfig.CLIENT.atmosphericClouds.get())
            return;
        final float[] c = renderer.getCloudColor(partialTick);
        final PoseStack poseStack = new PoseStack();
        poseStack.mulPose(modelViewMatrix);
        renderer.getAtmosphericCloudRenderer().render(poseStack, projectionMatrix, partialTick, camX, camY, camZ,
            c[0], c[1], c[2]);
        mc.getMainRenderTarget().bindWrite(false);
    }
}
