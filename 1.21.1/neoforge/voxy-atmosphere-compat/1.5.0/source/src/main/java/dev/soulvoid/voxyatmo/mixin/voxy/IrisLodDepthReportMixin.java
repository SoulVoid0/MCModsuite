package dev.soulvoid.voxyatmo.mixin.voxy;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;

import dev.soulvoid.voxyatmo.clouds.IrisLodDepth;

/** Hands Voxy's LOD depth (Iris shaderpack path) to {@link IrisLodDepth} so clouds can be occluded by LOD terrain. */
@Mixin(value = IrisVoxyRenderPipeline.class, remap = false)
public abstract class IrisLodDepthReportMixin {

    @Shadow
    @Final
    public DepthFramebuffer fbTranslucent;

    @Inject(method = "finish", at = @At("TAIL"), require = 0)
    private void voxyatmo$reportLodDepth(final Viewport<?> viewport, final int sourceFrameBuffer, final int srcWidth,
                                         final int srcHeight, final CallbackInfo ci) {
        if (this.fbTranslucent == null || this.fbTranslucent.getDepthTex() == null)
            return;
        IrisLodDepth.report(this.fbTranslucent.getDepthTex().id, viewport.MVP, viewport.vanillaProjection,
            viewport.modelView);
    }
}
