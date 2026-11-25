package dev.soulvoid.voxyatmo.mixin.simpleclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.CloudMeshGenerator;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetailConfig;

import dev.soulvoid.voxyatmo.clouds.CloudLodPlanner;

/**
 * Simple Clouds splits fixed 48 MB mesh buffers (opaque sides, transparent cubes) evenly across all mesh chunks. When
 * the cloud area is grown to the distant-terrain distance there are more chunks than its presets have, and each one's
 * share would shrink below what a dense storm cloud needs: the excess faces are dropped (holes, see-through slats).
 * Grows the buffers with the chunk count, capped at {@link CloudLodPlanner#MAX_BUFFER_SCALE}.
 */
@Mixin(value = CloudMeshGenerator.class, remap = false)
public abstract class CloudMeshBufferMixin {

    @Shadow
    public abstract LevelOfDetailConfig getLodConfig();

    @ModifyConstant(method = "setupShader", constant = @Constant(intValue = 50331648), require = 0)
    private int voxyatmo$scaleMeshBuffer(final int bytes) {
        final LevelOfDetailConfig lod = this.getLodConfig();
        if (lod == null || lod.getPreparedChunks() == null)
            return bytes;
        final double scale = CloudLodPlanner.bufferScale(lod.getPreparedChunks().size());
        return (int) Math.min(Integer.MAX_VALUE / 4L, Math.round(bytes * scale / 24.0) * 24L);
    }
}
