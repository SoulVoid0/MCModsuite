package dev.soulvoid.voxyatmo.mixin.clouds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import dev.nonamecrackers2.simpleclouds.client.mesh.generator.MultiRegionCloudMeshGenerator;
import dev.soulvoid.voxyatmo.clouds.CloudCoverage;

/**
 * Simple Clouds uploads at most 10 formations to its region shader (the shader itself has no limit) and drops the
 * rest in list order, so distant formations can hide near ones. Raise the limit, size the buffer for it, and keep the
 * nearest formations when there are still too many (their original order, which decides overlaps, is kept).
 */
@Mixin(value = MultiRegionCloudMeshGenerator.class, remap = false)
public abstract class RegionRenderCapMixin {

    @Unique
    private int voxyatmo$allocatedRegions = CloudCoverage.SIMPLE_CLOUDS_RENDER_CAP;

    @ModifyConstant(method = "initExtra", constant = @Constant(intValue = 320), require = 0)
    private int voxyatmo$regionBufferBytes(final int original) {
        final int regions = Math.max(original / 32, CloudCoverage.renderCap());
        this.voxyatmo$allocatedRegions = regions;
        return regions * 32;
    }

    @ModifyConstant(method = "uploadCloudRegionData", constant = @Constant(intValue = 10), require = 0)
    private int voxyatmo$regionCap(final int original) {
        return this.voxyatmo$cap();
    }

    @ModifyVariable(method = "uploadCloudRegionData", at = @At("STORE"), ordinal = 0, require = 0)
    private List<float[]> voxyatmo$nearestFirst(final List<float[]> regions) {
        final int cap = this.voxyatmo$cap();
        if (regions == null || regions.size() <= cap)
            return regions;
        final Minecraft mc = Minecraft.getInstance();
        final Vec3 cam = mc.gameRenderer == null ? Vec3.ZERO : mc.gameRenderer.getMainCamera().getPosition();
        final double cx = cam.x / 8.0;
        final double cz = cam.z / 8.0;
        final int n = regions.size();
        final Integer[] order = new Integer[n];
        final double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            final float[] d = regions.get(i);
            order[i] = i;
            dist[i] = Math.max(0.0, Math.hypot(d[0] - cx, d[1] - cz) - d[3]);
        }
        Arrays.sort(order, (a, b) -> Double.compare(dist[a], dist[b]));
        final boolean[] keep = new boolean[n];
        for (int i = 0; i < cap; i++)
            keep[order[i]] = true;
        final List<float[]> out = new ArrayList<>(cap);
        for (int i = 0; i < n; i++)
            if (keep[i])
                out.add(regions.get(i));
        return out;
    }

    @Unique
    private int voxyatmo$cap() {
        return Math.max(CloudCoverage.SIMPLE_CLOUDS_RENDER_CAP,
            Math.min(CloudCoverage.renderCap(), this.voxyatmo$allocatedRegions));
    }
}
