package dev.soulvoid.voxyatmo.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.cortex.voxy.client.distgen.DistantGenerationManager;

import dev.soulvoid.voxyatmo.core.Config;

/**
 * voxy-forged already stands its distant generator down when an external pregenerator (Groundwork) feeds Voxy: its
 * server tick returns early while {@code isGroundworkPresent()} is true, before any generator is created. Voxy SeedGen
 * is the same kind of mod and is meant to be used instead of Voxy's own worldgen, so it takes that same exit. Applied
 * only when SeedGen is installed ({@code VoxyMixinPlugin}).
 */
@Mixin(value = DistantGenerationManager.class, remap = false)
public abstract class DistantGenerationManagerMixin {

    @Inject(method = "isGroundworkPresent", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxyatmo$seedgenReplacesDistgen(final CallbackInfoReturnable<Boolean> cir) {
        if (Config.standDownVoxyDistgen())
            cir.setReturnValue(true);
    }
}
