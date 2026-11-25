package dev.soulvoid.voxyatmo.core;

/** Simple Clouds look/coverage mixins (1.1.0). Needs Simple Clouds; the Project Atmosphere one also needs it. */
public final class CloudsMixinPlugin extends RequiredModsMixinPlugin {

    public CloudsMixinPlugin() {
        super("Simple Clouds coverage", ModIds.SIMPLE_CLOUDS);
    }

    @Override
    protected boolean allow(final String mixin) {
        if (mixin.equals("PaSpawnReachMixin"))
            return ModIds.isLoaded(ModIds.PROJECT_ATMOSPHERE);
        return true;
    }
}
