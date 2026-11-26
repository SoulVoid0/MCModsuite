package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.nonamecrackers2.simpleclouds.client.renderer.pipeline.ShaderSupportPipeline;
import dev.soulvoid.voxyatmo.clouds.IrisLodDepth;
import dev.soulvoid.voxyatmo.core.Config;

/** Merges Voxy's LOD depth (Iris path) into the cloud depth right after Simple Clouds copies the main depth. */
@Mixin(value = ShaderSupportPipeline.class, remap = false)
public abstract class CloudLodDepthMergeMixin {

    @Inject(method = "afterLevel", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Ldev/nonamecrackers2/simpleclouds/client/renderer/SimpleCloudsRenderer;copyDepthFromMainToClouds()V"))
    private void voxyatmo$mergeLodDepth(final Minecraft mc, final SimpleCloudsRenderer renderer, final Matrix4f camMat,
                                        final Matrix4f projMat, final float partialTick, final double camX,
                                        final double camY, final double camZ, final Frustum frustum,
                                        final CallbackInfo ci) {
        if (!Config.cloudsDepthTestLod())
            return;
        renderer.getCloudTarget().bindWrite(false);
        IrisLodDepth.mergeIntoBoundTarget();
        renderer.getCloudTarget().bindWrite(false);
    }
}
