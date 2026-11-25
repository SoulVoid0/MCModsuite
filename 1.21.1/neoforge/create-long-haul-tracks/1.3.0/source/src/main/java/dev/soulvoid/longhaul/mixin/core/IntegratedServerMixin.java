package dev.soulvoid.longhaul.mixin.core;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.soulvoid.longhaul.job.JobManager;
import net.minecraft.client.server.IntegratedServer;

/**
 * While single player is paused (the planner pauses the game like the Esc menu does) the integrated server skips
 * its world ticks; this keeps surveying and building running in that time, with the whole tick to itself.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Inject(method = "tickPaused", at = @At("HEAD"))
    private void longhaultracks$tickPaused(final CallbackInfo ci) {
        JobManager.get((IntegratedServer) (Object) this).tick(true);
    }
}
