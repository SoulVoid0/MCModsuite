package dev.soulvoid.voxyatmo.core;

import java.util.Locale;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

/** Mod ids this patch bridges, plus presence checks that work both before and after mod construction. */
public final class ModIds {

    public static final String SELF = "voxyatmocompat";
    public static final String VOXY = "voxy";
    public static final String SIMPLE_CLOUDS = "simpleclouds";
    public static final String PROJECT_ATMOSPHERE = "projectatmosphere";
    public static final String DISTANT_HORIZONS = "distanthorizons";
    /** Make it Compatible: Voxy ships overlapping Simple Clouds patches; ours stand down where they would collide. */
    public static final String MIC_VOXY = "micvoxy";

    private static String seedgenId;
    private static boolean seedgenResolved;

    /**
     * Uses {@link LoadingModList}: mixin plugins run before {@code ModList} exists, but discovered mod files are
     * already known then.
     */
    public static boolean isLoaded(final String modId) {
        final LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
    }

    public static boolean allLoaded(final String... modIds) {
        for (final String id : modIds)
            if (!isLoaded(id))
                return false;
        return true;
    }

    /**
     * Voxy SeedGen is closed source and its mod id is not documented, so it is found by id or display name
     * containing "seedgen" (it is the only mod that does). Returns {@code null} when it is not installed.
     */
    public static synchronized String seedgenModId() {
        if (seedgenResolved)
            return seedgenId;
        seedgenResolved = true;
        final LoadingModList mods = LoadingModList.get();
        if (mods == null)
            return null;
        for (final ModInfo info : mods.getMods()) {
            final String id = info.getModId().toLowerCase(Locale.ROOT);
            final String name = info.getDisplayName() == null ? "" : info.getDisplayName().toLowerCase(Locale.ROOT);
            if (id.contains("seedgen") || name.replace(" ", "").contains("seedgen")) {
                seedgenId = info.getModId();
                break;
            }
        }
        return seedgenId;
    }

    public static boolean isSeedgenLoaded() {
        return seedgenModId() != null;
    }

    private ModIds() {}
}
