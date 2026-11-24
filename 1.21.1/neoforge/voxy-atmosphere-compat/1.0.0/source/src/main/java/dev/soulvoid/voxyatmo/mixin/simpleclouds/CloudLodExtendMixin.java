package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetailConfig;

import dev.soulvoid.voxyatmo.clouds.CloudLodPlanner;

/**
 * Feeds Simple Clouds' mesh generator a level-of-detail config whose area reaches the distant-terrain distance.
 * {@code SimpleCloudsRenderer.setupMeshGenerator} passes the preset's config through {@code Builder#lodConfig} before
 * building the generator; the preset objects themselves are never mutated.
 */
@Mixin(targets = "dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator$Builder", remap = false)
public abstract class CloudLodExtendMixin {

    @ModifyVariable(method = "lodConfig", at = @At("HEAD"), argsOnly = true, require = 0)
    private LevelOfDetailConfig voxyatmo$growCloudArea(final LevelOfDetailConfig original) {
        return CloudLodPlanner.plan(original);
    }
}
