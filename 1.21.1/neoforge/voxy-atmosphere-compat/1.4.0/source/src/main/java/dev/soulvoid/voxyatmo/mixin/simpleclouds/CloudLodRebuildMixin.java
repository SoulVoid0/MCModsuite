package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.renderer.SimpleCloudsRenderer;

import dev.soulvoid.voxyatmo.clouds.CloudLodPlanner;

/**
 * Rebuilds Simple Clouds' mesh generator when the distant-terrain distance moves past the cloud area it was built
 * with (SeedGen distance changed, Voxy distance changed). Simple Clouds only rebuilds on its own settings changes,
 * and a resource reload alone keeps the old generator, so it is closed and nulled first; {@code baseTick} handles
 * the pending reload on its next line. Checked once a second.
 */
@Mixin(value = SimpleCloudsRenderer.class, remap = false)
public abstract class CloudLodRebuildMixin {

    @Shadow
    private CloudMeshGenerator meshGenerator;

    @Shadow
    public abstract void requestReload();

    @Unique
    private long voxyatmo$nextCheck;

    @Inject(method = "baseTick", at = @At("HEAD"), require = 0)
    private void voxyatmo$rebuildOnDistanceChange(final CallbackInfo ci) {
        final long now = System.currentTimeMillis();
        if (now < this.voxyatmo$nextCheck)
            return;
        this.voxyatmo$nextCheck = now + 1000L;
        if (this.meshGenerator == null || !CloudLodPlanner.needsRebuild())
            return;
        this.meshGenerator.close();
        this.meshGenerator = null;
        this.requestReload();
    }
}
