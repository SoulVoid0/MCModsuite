package dev.soulvoid.voxyatmo.mixin.clouds;

import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import dev.soulvoid.voxyatmo.clouds.CloudCoverage;

/**
 * Project Atmosphere only spawns cloud formations within 5000 blocks of a player (hard cap 10000). Let it spawn out to
 * the cloud distance so formations exist where the far clouds are drawn.
 */
@Pseudo
@Mixin(targets = "net.Gabou.projectatmosphere.modules.atmosphere.CloudManager", remap = false)
public abstract class PaSpawnReachMixin {

    @ModifyConstant(method = "findSpawnCandidate", constant = @Constant(doubleValue = 5000.0), require = 0)
    private static double voxyatmo$spawnDistance(final double original) {
        return CloudCoverage.paSpawnLimit(original);
    }

    @ModifyConstant(method = "findSpawnCandidate", constant = @Constant(doubleValue = 1.0E8), require = 0)
    private static double voxyatmo$hardLimitSq(final double original) {
        final double limit = CloudCoverage.paSpawnLimit(10000.0);
        return Math.max(original, limit * limit);
    }
}
