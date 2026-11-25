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

    // Cloud look and coverage (1.1.0)
    private static final ModConfigSpec.BooleanValue SOFT_CLOUD_EDGES;
    private static final ModConfigSpec.BooleanValue MORE_FORMATIONS;
    private static final ModConfigSpec.IntValue FORMATION_SCALE;
    private static final ModConfigSpec.IntValue MAX_RENDERED_FORMATIONS;
    private static final ModConfigSpec.BooleanValue SPAWN_REACH_FOLLOWS_CLOUDS;
    private static final ModConfigSpec.BooleanValue AUTO_CLOUD_HEIGHT;
    private static final ModConfigSpec.BooleanValue STORM_FOG_SHAFTS;
    private static final ModConfigSpec.IntValue CLOUD_HEIGHT_PERCENTILE;
    private static final ModConfigSpec.IntValue CLOUD_HEIGHT_MARGIN;
    private static final ModConfigSpec.IntValue CLOUD_CLEAR_RADIUS;

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
            .comment("SeedGen distance in chunks. 0 = read it from Voxy SeedGen live (its generation distance, or its",
                     "extended render distance reach when that is on).")
            .defineInRange("seedgenDistanceChunksOverride", 0, 0, 1874999);
        MAX_CLOUD_DISTANCE = b
            .comment("Hard cap on the cloud render distance in blocks (performance guard). The default covers SeedGen's",
                     "maximum normal generation distance (4096 chunks); raise it for the extended render distance.")
            .defineInRange("maxCloudDistanceBlocks", 65536, 2048, 30000000);
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

        b.push("cloud_look_and_coverage");
        SOFT_CLOUD_EDGES = b
            .comment("Fade far clouds to transparent (built-in resource pack that overrides Simple Clouds' cloud",
                     "shaders). Fixes the opaque fog-coloured slab with a hard edge and the stripes and holes on its",
                     "side wall. Takes effect after a resource reload (F3+T) or restart.")
            .define("softCloudEdges", true);
        MORE_FORMATIONS = b
            .comment("Allow more cloud formations than Simple Clouds' limits (5 formations, 8 in data packs) and",
                     "render more than its 10-formation limit, so the sky out to the cloud distance is not empty.")
            .define("moreCloudFormations", true);
        FORMATION_SCALE = b
            .comment("Multiplier for Simple Clouds' formation counts, in percent. 0 = automatic from the cloud",
                     "distance (300% up to 6000 blocks, 400% up to 12000, 600% beyond).")
            .defineInRange("formationScalePercent", 0, 0, 2000);
        MAX_RENDERED_FORMATIONS = b
            .comment("Most cloud formations rendered at once (Simple Clouds renders 10). The nearest ones are kept.")
            .defineInRange("maxRenderedFormations", 64, 10, 256);
        SPAWN_REACH_FOLLOWS_CLOUDS = b
            .comment("Let Project Atmosphere spawn formations out to the cloud distance (it stops at 5000 blocks).")
            .define("spawnReachFollowsClouds", true);
        STORM_FOG_SHAFTS = b
            .comment("Simple Clouds' storm fog (rain shafts under storm clouds, ray-marched down to Y 0). Off by default:",
                     "with clouds lifted above tall/LOD terrain they hang in mid-air as dark columns.")
            .define("stormFogShafts", false);
        AUTO_CLOUD_HEIGHT = b
            .comment("Raise Simple Clouds' cloud height above the terrain around you (sampled from the world generator",
                     "out to the cloud distance, so Voxy/SeedGen terrain counts). Without it, tall worlds and mountains",
                     "cut the cloud layer into stripes and slabs. Never goes below the world's own cloud height.",
                     "Single player / LAN host only.")
            .define("autoCloudHeight", true);
        CLOUD_HEIGHT_PERCENTILE = b
            .comment("Which terrain height the clouds clear: 90 = higher than 90% of the sampled surface (the highest",
                     "peaks still reach into the clouds), 100 = above everything.")
            .defineInRange("cloudHeightTerrainPercentile", 90, 50, 100);
        CLOUD_HEIGHT_MARGIN = b
            .comment("Blocks between that terrain height and the cloud base.")
            .defineInRange("cloudHeightMarginBlocks", 48, 0, 1024);
        CLOUD_CLEAR_RADIUS = b
            .comment("Keep the cloud base above every peak within this many blocks of you (checked on a 128-block grid),",
                     "whatever the percentile says. Clouds that cut through nearby mountains show as dark, ragged",
                     "patches with streaks from above and as slabs from below. 0 = percentile only.")
            .defineInRange("cloudClearRadiusBlocks", 2048, 0, 8192);
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
    public static int maxCloudDistance() { return get(MAX_CLOUD_DISTANCE, 65536); }
    public static boolean growCloudArea() { return get(GROW_CLOUD_AREA, true); }
    public static boolean allowCoarserRing() { return get(ALLOW_COARSER_RING, false); }
    public static boolean fogFollowsClouds() { return get(FOG_FOLLOWS_CLOUDS, true); }
    public static int fogPercent() { return get(FOG_PERCENT, 90); }
    public static boolean softCloudEdges() { return get(SOFT_CLOUD_EDGES, true); }
    public static boolean moreFormations() { return get(MORE_FORMATIONS, true); }
    public static int formationScalePercent() { return get(FORMATION_SCALE, 0); }
    public static int maxRenderedFormations() { return get(MAX_RENDERED_FORMATIONS, 64); }
    public static boolean spawnReachFollowsClouds() { return get(SPAWN_REACH_FOLLOWS_CLOUDS, true); }
    public static boolean stormFogShafts() { return get(STORM_FOG_SHAFTS, false); }
    public static boolean autoCloudHeight() { return get(AUTO_CLOUD_HEIGHT, true); }
    public static int cloudHeightPercentile() { return get(CLOUD_HEIGHT_PERCENTILE, 90); }
    public static int cloudHeightMargin() { return get(CLOUD_HEIGHT_MARGIN, 48); }
    public static int cloudClearRadius() { return get(CLOUD_CLEAR_RADIUS, 2048); }
    public static boolean standDownVoxyDistgen() { return get(STAND_DOWN_VOXY_DISTGEN, true); }
    public static boolean raiseVoxyRenderDistance() { return get(RAISE_VOXY_RENDER_DISTANCE, true); }

    private Config() {}
}
