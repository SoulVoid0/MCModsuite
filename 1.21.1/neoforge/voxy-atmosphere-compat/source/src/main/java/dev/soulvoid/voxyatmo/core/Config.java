package dev.soulvoid.voxyatmo.core;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config ({@code config/voxyatmocompat-client.toml}). Every option is read live. */
public final class Config {

    public static final ModConfigSpec SPEC;

    // Clouds vs. LOD terrain
    private static final ModConfigSpec.BooleanValue CLOUDS_DEPTH_TEST_LOD;
    private static final ModConfigSpec.BooleanValue RESTORE_ATMOSPHERIC_LAYER;
    private static final ModConfigSpec.BooleanValue RAIN_IN_FRONT_OF_CLOUDS;

    // Distance
    private static final ModConfigSpec.BooleanValue CLOUDS_FOLLOW_GENERATION;
    private static final ModConfigSpec.IntValue DISTANCE_PERCENT;
    private static final ModConfigSpec.IntValue SEEDGEN_DISTANCE_OVERRIDE;
    private static final ModConfigSpec.IntValue MAX_CLOUD_DISTANCE;
    private static final ModConfigSpec.BooleanValue GROW_CLOUD_AREA;
    private static final ModConfigSpec.BooleanValue ALLOW_COARSER_RING;
    private static final ModConfigSpec.BooleanValue FOG_FOLLOWS_CLOUDS;
    private static final ModConfigSpec.IntValue FOG_PERCENT;

    // voxy-forged x Voxy SeedGen
    private static final ModConfigSpec.BooleanValue STAND_DOWN_VOXY_DISTGEN;
    private static final ModConfigSpec.BooleanValue RAISE_VOXY_RENDER_DISTANCE;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("clouds_vs_lod");
        CLOUDS_DEPTH_TEST_LOD = b
            .comment("Draw Simple Clouds after terrain and depth-test them against Voxy's LOD depth, so clouds are",
                     "hidden behind distant terrain and drawn over distant terrain they are in front of (above or",
                     "below the camera). Without this, Simple Clouds' default pipeline stamps cloud depth into the",
                     "main buffer before terrain, and Voxy treats every cloud pixel as near terrain and skips its LOD there.")
            .define("cloudsDepthTestAgainstLod", true);
        RESTORE_ATMOSPHERIC_LAYER = b
            .comment("Keep Simple Clouds' high 2D atmospheric cloud layer, which its shader-support pipeline omits.")
            .define("restoreAtmosphericLayer", true);
        RAIN_IN_FRONT_OF_CLOUDS = b
            .comment("Draw the clouds before vanilla/Project Atmosphere precipitation so rain is not painted over.")
            .define("rainInFrontOfClouds", true);
        b.pop();

        b.push("distance");
        CLOUDS_FOLLOW_GENERATION = b
            .comment("Drive the cloud render distance from Voxy SeedGen's generation distance (or Voxy's LOD",
                     "render distance when SeedGen is not installed).")
            .define("cloudsFollowGenerationDistance", true);
        DISTANCE_PERCENT = b
            .comment("Cloud render distance as a percentage of that distance.")
            .defineInRange("distancePercent", 100, 10, 200);
        SEEDGEN_DISTANCE_OVERRIDE = b
            .comment("SeedGen generation distance in chunks. 0 = read it from SeedGen's config file automatically.")
            .defineInRange("seedgenDistanceChunksOverride", 0, 0, 4096);
        MAX_CLOUD_DISTANCE = b
            .comment("Hard cap on the cloud render distance in blocks (performance guard).")
            .defineInRange("maxCloudDistanceBlocks", 32768, 2048, 65536);
        GROW_CLOUD_AREA = b
            .comment("Grow Simple Clouds' cloud mesh area (its outer level-of-detail ring) when the target distance",
                     "is beyond what its Level Of Detail preset covers (HIGH reaches about 10240 blocks).")
            .define("growCloudArea", true);
        ALLOW_COARSER_RING = b
            .comment("Reach very large distances with a new, coarser LOD ring instead of widening the existing",
                     "outermost one. Cheaper on the GPU, but adds a LOD level Simple Clouds does not ship with.")
            .define("allowCoarserLodRing", false);
        FOG_FOLLOWS_CLOUDS = b
            .comment("During the cloud pass, stretch the shader fog so clouds fade over their own distance instead",
                     "of the terrain's (otherwise every far cloud is flattened into fog colour).")
            .define("fogFollowsClouds", true);
        FOG_PERCENT = b
            .comment("Where cloud fog closes, as a percentage of the cloud render distance.")
            .defineInRange("cloudFogPercent", 90, 10, 100);
        b.pop();

        b.push("seedgen");
        STAND_DOWN_VOXY_DISTGEN = b
            .comment("Pause voxy-forged's own distant generation while Voxy SeedGen is installed. SeedGen is meant",
                     "to replace it; running both generates and ingests the same area twice.")
            .define("standDownVoxyDistantGeneration", true);
        RAISE_VOXY_RENDER_DISTANCE = b
            .comment("Raise Voxy's LOD render distance at runtime so it reaches SeedGen's generation distance;",
                     "otherwise SeedGen fills an area Voxy never draws. Only persisted if you save Voxy's settings.")
            .define("raiseVoxyRenderDistance", true);
        b.pop();

        SPEC = b.build();
    }

    private static boolean get(final ModConfigSpec.BooleanValue v, final boolean fallback) {
        return SPEC.isLoaded() ? v.get() : fallback;
    }

    private static int get(final ModConfigSpec.IntValue v, final int fallback) {
        return SPEC.isLoaded() ? v.get() : fallback;
    }

    public static boolean cloudsDepthTestLod() { return get(CLOUDS_DEPTH_TEST_LOD, true); }
    public static boolean restoreAtmosphericLayer() { return get(RESTORE_ATMOSPHERIC_LAYER, true); }
    public static boolean rainInFrontOfClouds() { return get(RAIN_IN_FRONT_OF_CLOUDS, true); }
    public static boolean cloudsFollowGeneration() { return get(CLOUDS_FOLLOW_GENERATION, true); }
    public static int distancePercent() { return get(DISTANCE_PERCENT, 100); }
    public static int seedgenDistanceOverride() { return get(SEEDGEN_DISTANCE_OVERRIDE, 0); }
    public static int maxCloudDistance() { return get(MAX_CLOUD_DISTANCE, 32768); }
    public static boolean growCloudArea() { return get(GROW_CLOUD_AREA, true); }
    public static boolean allowCoarserRing() { return get(ALLOW_COARSER_RING, false); }
    public static boolean fogFollowsClouds() { return get(FOG_FOLLOWS_CLOUDS, true); }
    public static int fogPercent() { return get(FOG_PERCENT, 90); }
    public static boolean standDownVoxyDistgen() { return get(STAND_DOWN_VOXY_DISTGEN, true); }
    public static boolean raiseVoxyRenderDistance() { return get(RAISE_VOXY_RENDER_DISTANCE, true); }

    private Config() {}
}
