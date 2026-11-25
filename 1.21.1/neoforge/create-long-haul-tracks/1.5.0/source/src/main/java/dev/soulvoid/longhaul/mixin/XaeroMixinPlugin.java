package dev.soulvoid.longhaul.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import dev.soulvoid.longhaul.ModIds;

/** Applies the world-map mixin only when Xaero's World Map is installed. */
public final class XaeroMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(final String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return ModIds.isLoaded(ModIds.XAERO_WORLD_MAP);
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String t, final ClassNode c, final String m, final IMixinInfo i) {}

    @Override
    public void postApply(final String t, final ClassNode c, final String m, final IMixinInfo i) {}
}
