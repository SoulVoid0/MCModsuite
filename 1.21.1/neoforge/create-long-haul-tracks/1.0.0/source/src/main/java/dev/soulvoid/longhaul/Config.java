package dev.soulvoid.longhaul;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server and client settings. Server values are read on the server thread only. */
public final class Config {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    public static final ModConfigSpec.IntValue MAX_ROUTE_LENGTH;
    public static final ModConfigSpec.DoubleValue MAX_GRADE;
    public static final ModConfigSpec.IntValue BRIDGE_THRESHOLD;
    public static final ModConfigSpec.IntValue WATER_CLEARANCE;
    public static final ModConfigSpec.IntValue TUNNEL_HEIGHT;
    public static final ModConfigSpec.IntValue PILLAR_SPACING;
    public static final ModConfigSpec.IntValue MAX_PILLAR_DEPTH;
    public static final ModConfigSpec.DoubleValue BRIDGE_PREFERENCE;
    public static final ModConfigSpec.DoubleValue MOUNTAIN_BRIDGE_PREFERENCE;
    public static final ModConfigSpec.IntValue SMOOTHING_RADIUS;
    public static final ModConfigSpec.IntValue SAMPLE_SPACING;
    public static final ModConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MILLIS_PER_TICK;
    public static final ModConfigSpec.IntValue FAR_PICK_RANGE;
    public static final ModConfigSpec.BooleanValue SURVEY_BEFORE_BUILD;
    public static final ModConfigSpec.BooleanValue CREATIVE_IS_FREE;
    public static final ModConfigSpec.BooleanValue JJTHUNDER_PROFILE;

    public static final ModConfigSpec.BooleanValue SHOW_ON_MAP;
    public static final ModConfigSpec.IntValue HOLOGRAM_DETAIL_RANGE;
    public static final ModConfigSpec.BooleanValue ALWAYS_SHOW_HOLOGRAMS;

    static {
        final ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        s.push("route");
        MAX_ROUTE_LENGTH = s.comment("Longest route, in track blocks, one plan may cover.")
            .defineInRange("maxRouteLength", 20000, 64, 100000);
        MAX_GRADE = s.comment("Steepest grade the planner lays, as rise per horizontal block. Create itself allows ~0.33.")
            .defineInRange("maxGrade", 0.1875, 0.02, 0.3);
        BRIDGE_THRESHOLD = s.comment("Gaps under the track up to this many blocks are filled as an embankment; deeper gaps become a bridge with pillars.")
            .defineInRange("bridgeThreshold", 3, 0, 16);
        WATER_CLEARANCE = s.comment("Blocks of air kept between a water surface and a bridge deck.")
            .defineInRange("waterClearance", 2, 0, 32);
        TUNNEL_HEIGHT = s.comment("Clear height of tunnels and cuttings above the rails.")
            .defineInRange("tunnelHeight", 5, 4, 12);
        PILLAR_SPACING = s.comment("Distance between bridge pillars, in track blocks.")
            .defineInRange("pillarSpacing", 8, 2, 64);
        MAX_PILLAR_DEPTH = s.comment("Deepest a pillar is built before it stops (the deck still spans). JJThunder worlds use the larger of this and 1024.")
            .defineInRange("maxPillarDepth", 384, 0, 4096);
        BRIDGE_PREFERENCE = s.comment("0 = cut through every rise with a tunnel, 1 = bridge every dip; 0.5 balances the two.")
            .defineInRange("bridgePreference", 0.5, 0.0, 1.0);
        MOUNTAIN_BRIDGE_PREFERENCE = s.comment("Same as bridgePreference, used inside mountain biomes (#minecraft:is_mountain), where tunnels are usually wanted.")
            .defineInRange("mountainBridgePreference", 0.3, 0.0, 1.0);
        SMOOTHING_RADIUS = s.comment("How far, in blocks, terrain is averaged before the profile is fitted. Larger = straighter, more tunnels/bridges.")
            .defineInRange("smoothingRadius", 12, 0, 256);
        SAMPLE_SPACING = s.comment("Terrain is sampled every this many blocks along the route (between samples it is interpolated).")
            .defineInRange("sampleSpacing", 4, 1, 32);
        s.pop();
        s.push("build");
        CHUNKS_PER_TICK = s.comment("Most route chunks surveyed or built per server tick, over all jobs.")
            .defineInRange("chunksPerTick", 2, 1, 64);
        MILLIS_PER_TICK = s.comment("Time budget per server tick for surveying and building, in milliseconds.")
            .defineInRange("millisPerTick", 15, 1, 200);
        SURVEY_BEFORE_BUILD = s.comment("Walk the whole route (loading chunks with tickets) to count the exact materials before asking for them. When off, the estimate is used and building pauses if it runs short.")
            .define("surveyBeforeBuild", true);
        CREATIVE_IS_FREE = s.comment("Players in creative mode may fill a job's materials for free.")
            .define("creativeIsFree", true);
        FAR_PICK_RANGE = s.comment("Furthest distance, in blocks, a surveyor can pick a point by aiming at distant (unloaded) terrain.")
            .defineInRange("farPickRange", 16384, 64, 65536);
        s.pop();
        s.push("compat");
        JJTHUNDER_PROFILE = s.comment("Use the JJThunder To The Max terrain profile (more tunnels, deeper pillars, coarser sampling) when that datapack is active.")
            .define("jjthunderProfile", true);
        s.pop();
        SERVER_SPEC = s.build();

        final ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        SHOW_ON_MAP = c.comment("Draw planned and running routes on Xaero's World Map.").define("showRoutesOnMap", true);
        HOLOGRAM_DETAIL_RANGE = c.comment("Within this distance the hologram draws rails and sleepers; further away only the route line.")
            .defineInRange("hologramDetailRange", 96, 16, 512);
        ALWAYS_SHOW_HOLOGRAMS = c.comment("Show route holograms even when not holding a Track Surveyor.").define("alwaysShowHolograms", false);
        CLIENT_SPEC = c.build();
    }

    private Config() {}
}
