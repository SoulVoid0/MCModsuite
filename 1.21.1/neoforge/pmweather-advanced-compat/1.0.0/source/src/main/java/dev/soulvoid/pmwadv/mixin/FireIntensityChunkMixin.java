package dev.soulvoid.pmwadv.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import dev.protomanly.pmweather.weather.WindEngine;
import dev.soulvoid.pmwadv.core.Config;

/**
 * PMWeather's wind adds fire heat read from the chunk under the sample point, loading (or generating) it if needed.
 * With storms spread over the Voxy area this made the server generate far chunks on its own thread (up to 11 s per
 * tick in a JJThunder world). A chunk that is not fully loaded yet has no fire we could see: answer 0 without loading or waiting for it and without caching the
 * answer, so the real value is read once the chunk is loaded.
 */
@Mixin(value = WindEngine.class, remap = false)
public abstract class FireIntensityChunkMixin {

    @Inject(method = "getFireIntensity", at = @At("HEAD"), cancellable = true, require = 0)
    private static void pmwadv$noFarChunkLoad(final Level level, final ChunkPos pos,
                                              final CallbackInfoReturnable<Float> cir) {
        if (!level.isClientSide() && Config.avoidFarChunkLoads() && level.getChunkSource().getChunkNow(pos.x, pos.z) == null)
            cir.setReturnValue(0f);
    }
}
