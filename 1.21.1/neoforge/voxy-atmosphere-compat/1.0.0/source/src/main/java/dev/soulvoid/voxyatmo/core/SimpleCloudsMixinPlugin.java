package dev.soulvoid.voxyatmo.core;

/**
 * Voxy x Simple Clouds mixins. Needs both mods. When Make it Compatible: Voxy is also installed, the three mixins that
 * duplicate its own (atmospheric-layer restore, rain re-ordering, fog stretch) stand down: two {@code @Redirect}s on
 * the same call would crash, and the others would run twice. Its versions do the same job.
 */
public final class SimpleCloudsMixinPlugin extends RequiredModsMixinPlugin {

    public SimpleCloudsMixinPlugin() {
        super("Voxy x Simple Clouds", ModIds.VOXY, ModIds.SIMPLE_CLOUDS);
    }

    @Override
    protected boolean allow(final String mixin) {
        if (!ModIds.isLoaded(ModIds.MIC_VOXY))
            return true;
        final boolean duplicate = mixin.equals("ShaderSupportPipelineMixin")
            || mixin.equals("SimpleCloudsRainOrderMixin")
            || mixin.equals("SimpleCloudsFogMixin");
        if (duplicate)
            LOGGER.info("[Voxy Atmosphere Compat] {} skipped: Make it Compatible: Voxy provides it.", mixin);
        return !duplicate;
    }
}
