package dev.soulvoid.voxyatmo.core;

/** voxy-forged mixins (distant generation stand-down). Only meaningful when Voxy SeedGen is installed. */
public final class VoxyMixinPlugin extends RequiredModsMixinPlugin {

    public VoxyMixinPlugin() {
        super("voxy-forged x Voxy SeedGen", ModIds.VOXY);
    }

    @Override
    protected boolean allow(final String mixin) {
        return mixin.equals("IrisLodDepthReportMixin") || ModIds.isSeedgenLoaded();
    }
}
