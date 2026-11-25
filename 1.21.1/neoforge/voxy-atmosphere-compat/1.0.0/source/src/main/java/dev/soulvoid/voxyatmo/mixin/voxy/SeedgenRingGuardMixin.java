package dev.soulvoid.voxyatmo.mixin.voxy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import me.cortex.voxy.client.core.VoxyRenderSystem;

/**
 * Voxy SeedGen's {@code VoxyRingGuardMixin} clamps the ring Voxy is asked to draw to what the world and the geometry
 * buffer can hold ({@code RenderReach.ringFor(float)}), via {@code @ModifyVariable} on
 * {@code VoxyRenderSystem.setRenderDistance(float)}. voxy-forged's method takes an {@code int}, so SeedGen's mixin
 * fails to apply ("Implicit variable modifier injection failed") and the clamp is lost. This re-applies the same
 * clamp on the {@code int} signature, rounding the way SeedGen itself does when it calls the int variant.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class SeedgenRingGuardMixin {

    @Unique
    private static MethodHandle voxyatmo$ringFor;
    @Unique
    private static boolean voxyatmo$ringForMissing;

    @ModifyVariable(method = "setRenderDistance(I)V", at = @At("HEAD"), argsOnly = true, require = 0)
    private int voxyatmo$holdTheRing(final int sections) {
        if (voxyatmo$ringForMissing)
            return sections;
        try {
            if (voxyatmo$ringFor == null)
                voxyatmo$ringFor = MethodHandles.publicLookup().findStatic(
                    Class.forName("mobius.voxyseedgen.voxy.RenderReach", true, VoxyRenderSystem.class.getClassLoader()),
                    "ringFor", MethodType.methodType(float.class, float.class));
            final float ring = (float) voxyatmo$ringFor.invokeExact((float) sections);
            return Math.max(1, Math.round(ring));
        } catch (final Throwable t) {
            voxyatmo$ringForMissing = true;
            return sections;
        }
    }
}
