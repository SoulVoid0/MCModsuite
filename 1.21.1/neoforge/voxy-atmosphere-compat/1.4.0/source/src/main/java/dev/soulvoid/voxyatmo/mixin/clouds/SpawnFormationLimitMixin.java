package dev.soulvoid.voxyatmo.mixin.clouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.nonamecrackers2.simpleclouds.common.cloud.spawning.CloudSpawningConfig;
import dev.soulvoid.voxyatmo.clouds.CloudCoverage;

/** Scales Simple Clouds' formation limits (used by Simple Clouds' and Project Atmosphere's spawning). */
@Mixin(value = CloudSpawningConfig.class, remap = false)
public abstract class SpawnFormationLimitMixin {

    @Inject(method = "getMaxRegions", at = @At("RETURN"), cancellable = true)
    private void voxyatmo$scaleMax(final CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(CloudCoverage.scaleFormations(cir.getReturnValueI()));
    }

    @Inject(method = "getMaxInitialRegions", at = @At("RETURN"), cancellable = true)
    private void voxyatmo$scaleInitial(final CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(CloudCoverage.scaleFormations(cir.getReturnValueI()));
    }
}
