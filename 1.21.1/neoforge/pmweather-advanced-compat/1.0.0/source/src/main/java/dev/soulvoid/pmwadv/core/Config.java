package dev.soulvoid.pmwadv.core;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    /** CM1's shader skips simulated storms further than this from the camera (hard-coded 32000 in PMWeatherCM1 1.1.7). */
    public static final int CM1_DRAW_LIMIT = 32000;

    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    private static final ModConfigSpec.BooleanValue FOLLOW_VOXY;
    private static final ModConfigSpec.IntValue FALLBACK_AREA;
    private static final ModConfigSpec.IntValue MAX_AREA;
    private static final ModConfigSpec.DoubleValue LOCAL_SHARE;
    private static final ModConfigSpec.BooleanValue SCALE_MAX_STORMS;
    private static final ModConfigSpec.IntValue MAX_STORMS_CAP;
    private static final ModConfigSpec.BooleanValue AVOID_FAR_LOADS;

    private static final ModConfigSpec.BooleanValue REPORT_DISTANCE;
    private static final ModConfigSpec.IntValue AREA_OVERRIDE;
    private static final ModConfigSpec.BooleanValue NEAREST_FIRST;

    static {
        final ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.push("stormArea");
        FOLLOW_VOXY = c.comment("Storms form across the area your Voxy / Voxy SeedGen terrain is drawn to, as reported by each",
                "player's client. false: always use fallbackAreaBlocks.")
            .define("followVoxyDistance", true);
        FALLBACK_AREA = c.comment("Storm area radius (blocks) used when no player reports a Voxy distance (e.g. players",
                "without this mod on a dedicated server, or Voxy not installed).")
            .defineInRange("fallbackAreaBlocks", 16384, 256, CM1_DRAW_LIMIT);
        MAX_AREA = c.comment("Upper limit for the storm area radius. PMWeatherCM1 does not draw simulated storms further",
                "than 32000 blocks from the camera, so a larger area would only add invisible storms.")
            .defineInRange("maxAreaBlocks", CM1_DRAW_LIMIT, 256, CM1_DRAW_LIMIT);
        LOCAL_SHARE = c.comment("Share of storm spawn checks that keep PMWeather's own (local) spawn range, so storms still",
                "form near you. 0 = every storm may form anywhere in the area; 1 = old behaviour.")
            .defineInRange("localSpawnShare", 0.35, 0.0, 1.0);
        SCALE_MAX_STORMS = c.comment("Raise PMWeather's maxStorms with the area size (PMWeather's own storm types; CM1 keeps",
                "its own limit of two simulated storms).")
            .define("scaleMaxStorms", true);
        MAX_STORMS_CAP = c.comment("Highest maxStorms value scaleMaxStorms may set. Every storm costs GPU time.")
            .defineInRange("maxStormsCap", 10, 1, 64);
        AVOID_FAR_LOADS = c.comment("Don't make PMWeather generate an unloaded chunk just to sample the atmosphere at a far",
                "storm spawn point (it only looks for fires there: atmosphere sampling and wind fire heat). Without this, every far spawn check generates a",
                "chunk on the server thread, which takes seconds in heavy worlds like JJThunder.")
            .define("avoidFarChunkLoads", true);
        c.pop();
        COMMON_SPEC = c.build();

        final ModConfigSpec.Builder cl = new ModConfigSpec.Builder();
        REPORT_DISTANCE = cl.comment("Tell the server how far Voxy / Voxy SeedGen draws terrain, so storms form out to it.")
            .define("reportVoxyDistance", true);
        AREA_OVERRIDE = cl.comment("Report this storm area radius (blocks) instead of reading Voxy. -1 = read Voxy / SeedGen.")
            .defineInRange("areaOverrideBlocks", -1, -1, CM1_DRAW_LIMIT);
        NEAREST_FIRST = cl.comment("Keep the client's storm list nearest-first, so CM1 (which draws one simulated supercell and",
                "one cyclone) draws the storm closest to you rather than the first one spawned.")
            .define("nearestCm1StormFirst", true);
        CLIENT_SPEC = cl.build();
    }

    public static boolean followVoxy() { return get(FOLLOW_VOXY, true); }
    public static int fallbackArea() { return get(FALLBACK_AREA, 16384); }
    public static int maxArea() { return get(MAX_AREA, CM1_DRAW_LIMIT); }
    public static double localShare() { return get(LOCAL_SHARE, 0.35); }
    public static boolean scaleMaxStorms() { return get(SCALE_MAX_STORMS, true); }
    public static int maxStormsCap() { return get(MAX_STORMS_CAP, 10); }
    public static boolean avoidFarChunkLoads() { return get(AVOID_FAR_LOADS, true); }
    public static boolean reportDistance() { return get(REPORT_DISTANCE, true); }
    public static int areaOverride() { return get(AREA_OVERRIDE, -1); }
    public static boolean nearestFirst() { return get(NEAREST_FIRST, true); }

    private static <T> T get(final ModConfigSpec.ConfigValue<T> v, final T fallback) {
        try {
            return v.get();
        } catch (final IllegalStateException notLoaded) {
            return fallback;
        }
    }

    private Config() {}
}
