package dev.soulvoid.voxyatmo.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;

/**
 * Voxy SeedGen sizes its LOD rings against Voxy's geometry buffer, reading it reflectively through upstream Voxy's
 * {@code IGeometryData.getMaxCapacity()}. voxy-forged's {@code IGeometryData} has no such method (the size lives on
 * {@code BasicSectionGeometryData.getGeometryCapacityBytes()}), so SeedGen fell back to assuming a 4 GiB buffer and
 * could over-ask on GPUs with a smaller one. This answers SeedGen's {@code VoxyBuilds.geometryCapacity} with
 * voxy-forged's real figure.
 */
@Mixin(targets = "mobius.voxyseedgen.voxy.VoxyBuilds", remap = false)
public abstract class SeedgenGeometryCapacityMixin {

    @Inject(method = "geometryCapacity(Lme/cortex/voxy/client/core/rendering/section/geometry/IGeometryData;)J",
        at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxyatmo$forgedCapacity(final IGeometryData data, final CallbackInfoReturnable<Long> cir) {
        if (data instanceof BasicSectionGeometryData basic)
            cir.setReturnValue(basic.getGeometryCapacityBytes());
    }
}
