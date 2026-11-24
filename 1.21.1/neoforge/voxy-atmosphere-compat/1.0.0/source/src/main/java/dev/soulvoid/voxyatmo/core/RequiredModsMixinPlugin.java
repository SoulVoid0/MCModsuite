package dev.soulvoid.voxyatmo.core;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.mojang.logging.LogUtils;

/**
 * Applies a mixin config only when every mod it bridges is installed, so missing targets never error and their
 * classes are never force-loaded. Subclasses may veto individual mixins with {@link #allow(String)}.
 */
public abstract class RequiredModsMixinPlugin implements IMixinConfigPlugin {

    protected static final Logger LOGGER = LogUtils.getLogger();

    private final String patchName;
    private final String[] requiredMods;
    private boolean active;

    protected RequiredModsMixinPlugin(final String patchName, final String... requiredMods) {
        this.patchName = patchName;
        this.requiredMods = requiredMods;
    }

    @Override
    public void onLoad(final String mixinPackage) {
        this.active = ModIds.allLoaded(this.requiredMods);
        LOGGER.info("[Voxy Atmosphere Compat] patch '{}' {}.", this.patchName,
            this.active ? "active" : "dormant (needs " + String.join(", ", this.requiredMods) + ")");
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return this.active && this.allow(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }

    /** @return whether the named mixin (simple class name) should apply, given the patch is active. */
    protected boolean allow(final String mixinSimpleName) {
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
                         final IMixinInfo mixinInfo) {}

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
                          final IMixinInfo mixinInfo) {}
}
