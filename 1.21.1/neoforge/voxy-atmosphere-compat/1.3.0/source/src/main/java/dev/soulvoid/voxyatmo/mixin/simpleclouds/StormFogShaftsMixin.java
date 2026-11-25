package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.Minecraft;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;
import dev.soulvoid.voxyatmo.core.Config;

/**
 * Simple Clouds' storm fog ray-marches rain shafts from the cloud base down to Y 0. Once clouds are lifted above tall
 * terrain those shafts hang in mid-air as dark purple columns. When disabled, the storm fog target is only cleared, so
 * the blur/blit that follows draws nothing.
 */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class StormFogShaftsMixin {

    @Shadow
    private RenderTarget stormFogTarget;

    @Inject(method = "doStormPostProcessing", at = @At("HEAD"), cancellable = true, require = 0)
    private void voxyatmo$skipStormShafts(final CallbackInfo ci) {
        if (Config.stormFogShafts() || this.stormFogTarget == null)
            return;
        this.stormFogTarget.clear(Minecraft.ON_OSX);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        ci.cancel();
    }
}
