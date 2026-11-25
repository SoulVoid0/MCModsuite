package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;

import dev.soulvoid.voxyatmo.clouds.CloudFogBridge;

/** Brackets both cloud draw phases with {@link CloudFogBridge}; push is not re-entrant and pop is a no-op when idle. */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class SimpleCloudsFogMixin {

    @Inject(method = "renderBeforeWeather", at = @At("HEAD"), require = 0)
    private void voxyatmo$fogPushBeforeWeather(final CallbackInfo ci) {
        CloudFogBridge.push();
    }

    @Inject(method = "renderBeforeWeather", at = @At("RETURN"), require = 0)
    private void voxyatmo$fogPopBeforeWeather(final CallbackInfo ci) {
        CloudFogBridge.pop();
    }

    @Inject(method = "renderAfterLevel", at = @At("HEAD"), require = 0)
    private void voxyatmo$fogPushAfterLevel(final CallbackInfo ci) {
        CloudFogBridge.push();
    }

    @Inject(method = "renderAfterLevel", at = @At("RETURN"), require = 0)
    private void voxyatmo$fogPopAfterLevel(final CallbackInfo ci) {
        CloudFogBridge.pop();
    }
}
