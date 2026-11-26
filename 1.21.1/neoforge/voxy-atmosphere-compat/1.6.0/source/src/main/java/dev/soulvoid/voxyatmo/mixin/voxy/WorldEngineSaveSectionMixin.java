package dev.soulvoid.voxyatmo.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

/**
 * Voxy SeedGen 0.3.x is compiled against upstream Voxy, where {@code WorldEngine.saveSection(WorldSection)} returns
 * {@code void}. voxy-forged 1.1.0 changed it to return {@code boolean}, so SeedGen's {@code LadderWriter.markDirty}
 * call ({@code saveSection(Lme/cortex/voxy/common/world/WorldSection;)V}) throws {@code NoSuchMethodError} the first
 * time it writes a coarse LOD section. This adds the {@code void} overload back (same name, different return type is
 * legal in bytecode) and forwards it to voxy-forged's own method. Applied only when SeedGen is installed.
 */
@Mixin(value = WorldEngine.class, remap = false)
public abstract class WorldEngineSaveSectionMixin {

    public void saveSection(final WorldSection section) {
        ((WorldEngine) (Object) this).saveSection(section, false, false);
    }
}
