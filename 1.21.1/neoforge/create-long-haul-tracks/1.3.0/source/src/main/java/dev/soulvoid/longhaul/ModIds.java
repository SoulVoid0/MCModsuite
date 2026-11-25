package dev.soulvoid.longhaul;

import net.neoforged.fml.loading.LoadingModList;

/** Mod ids this mod integrates with. Presence checks work before and after mod construction. */
public final class ModIds {
    public static final String CREATE = "create";
    public static final String XAERO_WORLD_MAP = "xaeroworldmap";
    public static final String XAERO_TRAIN_MAP = "xaerotrainmap";
    public static final String VOXY_ATMO_COMPAT = "voxyatmocompat";
    public static final String VOXY = "voxy";

    public static boolean isLoaded(final String id) {
        final LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(id) != null;
    }

    private ModIds() {}
}
