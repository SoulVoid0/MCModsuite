package dev.soulvoid.longhaul;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server and client settings. Server values are read on the server thread only. */
public final class Config {

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    public static final ModConfigSpec.IntValue MAX_ROUTE_LENGTH;
    public static final ModConfigSpec.DoubleValue MAX_GRADE;
    public static final ModConfigSpec.IntValue BRIDGE_THRESHOLD;
    public static final ModConfigSpec.IntValue MAX_BRIDGE_HEIGHT;
    public static final ModConfigSpec.IntValue MAX_BRIDGE_SPAN;
    public static final ModConfigSpec.IntValue WATER_CLEARANCE;
    public static final ModConfigSpec.IntValue TUNNEL_HEIGHT;
    public static final ModConfigSpec.IntValue PILLAR_SPACING;
    public static final ModConfigSpec.IntValue MAX_PILLAR_DEPTH;
    public static final ModConfigSpec.IntValue TUNNEL_WIDTH;
    public static final ModConfigSpec.IntValue CURVE_EXTRA_WIDTH;
    public static final ModConfigSpec.IntValue SEARCH_CELL;
    public static final ModConfigSpec.IntValue SEARCH_MAX_STEPS;
    public static final ModConfigSpec.DoubleValue TURN_COST;
    public static final ModConfigSpec.DoubleValue TUNNEL_COST;
    public static final ModConfigSpec.DoubleValue LAND_BRIDGE_COST;
    public static final ModConfigSpec.DoubleValue WATER_BRIDGE_COST;
    public static final ModConfigSpec.IntValue BLOCKS_PER_TRACK;
    public static final ModConfigSpec.IntValue LIGHT_SPACING;
    public static final ModConfigSpec.BooleanValue PAUSE_WHILE_PLANNING;
    public static final ModConfigSpec.BooleanValue PAUSE_ON_SERVERS;
    public static final ModConfigSpec.IntValue PAUSED_MILLIS_PER_TICK;
    public static final ModConfigSpec.IntValue PAUSED_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue SMOOTHING_RADIUS;
    public static final ModConfigSpec.IntValue SAMPLE_SPACING;
    public static final ModConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MILLIS_PER_TICK;
    public static final ModConfigSpec.IntValue PREFETCH_CHUNKS;
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
        MAX_ROUTE_LENGTH = s.comment("Longest route, in track blocks, one plan may cover. Long routes are planned as a coarse corridor first, then in sections of a few thousand blocks searched in parallel.")
            .defineInRange("maxRouteBlocks", 1000000, 64, 30000000);
        MAX_GRADE = s.comment("Steepest grade the planner lays, as rise per horizontal block. Create itself allows ~0.33.")
            .defineInRange("maxGrade", 0.1875, 0.02, 0.3);
        BRIDGE_THRESHOLD = s.comment("Gaps under the track up to this many blocks are filled as an embankment; deeper gaps become a bridge with pillars.")
            .defineInRange("bridgeThreshold", 3, 0, 16);
        MAX_BRIDGE_HEIGHT = s.comment("Highest a bridge over dry land may stand above the ground. Routes never climb on bridges (a bridge over dry land is level): they follow the ground, cut into slopes and tunnel; bridges only cross cracks and ravines (and water, which has no limit).")
            .defineInRange("maxBridgeHeight", 48, 4, 512);
        MAX_BRIDGE_SPAN = s.comment("Longest bridge over dry land, in blocks.")
            .defineInRange("maxBridgeSpan", 128, 8, 2048);
        WATER_CLEARANCE = s.comment("Blocks of air kept between a water surface and a bridge deck.")
            .defineInRange("waterClearance", 2, 0, 32);
        TUNNEL_HEIGHT = s.comment("Clear height of tunnels and cuttings, counted from the rail block up. 8 leaves room for players riding on top of a carriage.")
            .defineInRange("tunnelHeight", 8, 4, 16);
        TUNNEL_WIDTH = s.comment("Clear width of tunnels and cuttings on straight track.")
            .defineInRange("tunnelWidth", 7, 3, 15);
        CURVE_EXTRA_WIDTH = s.comment("Extra clear width on curves, where long carriages swing out over the inside and outside of the turn.")
            .defineInRange("curveExtraWidth", 2, 0, 8);
        PILLAR_SPACING = s.comment("Distance between bridge pillars, in track blocks.")
            .defineInRange("pillarSpacing", 8, 2, 64);
        MAX_PILLAR_DEPTH = s.comment("Deepest a pillar is built before it stops (the deck still spans). JJThunder worlds use the larger of this and 1024.")
            .defineInRange("maxPillarDepth", 384, 0, 4096);
        SMOOTHING_RADIUS = s.comment("How far, in blocks, terrain is averaged before a profile is fitted where no search heights are known.")
            .defineInRange("smoothingRadius", 12, 0, 256);
        SAMPLE_SPACING = s.comment("Terrain is sampled every this many blocks along the route (between samples it is interpolated).")
            .defineInRange("sampleSpacing", 4, 1, 32);
        s.pop();
        s.push("search");
        SEARCH_CELL = s.comment("Grid size, in blocks, of the route search. Smaller follows terrain more closely but searches slower. JJThunder worlds use at least 32.")
            .defineInRange("cellSize", 16, 8, 64);
        SEARCH_MAX_STEPS = s.comment("Most search steps for one section of a route (a few thousand blocks) before it is retried with other waypoints or climbed by the winding fallback.")
            .defineInRange("maxSteps", 300000, 10000, 5000000);
        TURN_COST = s.comment("Extra cost, in blocks of plain track, of every 45 degree heading change. Higher = straighter routes.")
            .defineInRange("turnCost", 8.0, 0.0, 200.0);
        TUNNEL_COST = s.comment("Extra cost per block of tunnel. Higher = more detours around hills.")
            .defineInRange("tunnelCost", 2.0, 0.0, 100.0);
        LAND_BRIDGE_COST = s.comment("Extra cost per block of bridge over dry land (valleys). Higher = the route follows valleys down and up instead.")
            .defineInRange("landBridgeCost", 5.0, 0.0, 100.0);
        WATER_BRIDGE_COST = s.comment("Extra cost per block of bridge over water.")
            .defineInRange("waterBridgeCost", 0.6, 0.0, 100.0);
        s.pop();
        s.push("build");
        BLOCKS_PER_TRACK = s.comment("Blocks of finished route one track item pays for (straight or curved). 16 = a 10,000 block route needs about 650 tracks.")
            .defineInRange("blocksPerTrackItem", 16, 1, 64);
        LIGHT_SPACING = s.comment("Distance between lights in tunnels and on bridges, when a light is chosen.")
            .defineInRange("lightSpacing", 10, 2, 64);
        CHUNKS_PER_TICK = s.comment("Most route chunks surveyed or built per server tick, over all jobs.")
            .defineInRange("chunksPerTick", 2, 1, 64);
        PREFETCH_CHUNKS = s.comment("Route chunks ahead of the builder that are loaded or generated in advance, in parallel. Higher is faster on slow world generation (JJThunder) and uses more memory.")
            .defineInRange("prefetchChunks", 12, 1, 64);
        MILLIS_PER_TICK = s.comment("Time budget per server tick for surveying and building, in milliseconds.")
            .defineInRange("millisPerTick", 15, 1, 200);
        SURVEY_BEFORE_BUILD = s.comment("Walk the whole route (loading chunks with tickets) to count the exact materials before asking for them. When off, the estimate is used and building pauses if it runs short.")
            .define("surveyBeforeBuild", true);
        CREATIVE_IS_FREE = s.comment("Players in creative mode may fill a job's materials for free.")
            .define("creativeIsFree", true);
        FAR_PICK_RANGE = s.comment("Furthest distance, in blocks, a surveyor can pick a point by aiming at distant (unloaded) terrain.")
            .defineInRange("farPickRange", 16384, 64, 65536);
        s.pop();
        s.push("pause");
        PAUSE_WHILE_PLANNING = s.comment("Pause while a player has the planner open, and give planning and building the freed time. Single player pauses the whole game like the Esc menu (time, weather, clouds, mobs); a LAN world with only the host online freezes the world like /tick freeze.")
            .define("pauseWhilePlanning", true);
        PAUSE_ON_SERVERS = s.comment("Also freeze dedicated servers while anyone has the planner open. Off by default: it freezes the world for every player.")
            .define("pauseOnServers", false);
        PAUSED_MILLIS_PER_TICK = s.comment("Building time per tick while the world is frozen for the planner, in milliseconds (a tick is 50).")
            .defineInRange("pausedMillisPerTick", 40, 1, 200);
        PAUSED_CHUNKS_PER_TICK = s.comment("Most route chunks surveyed or built per tick while the world is frozen for the planner.")
            .defineInRange("pausedChunksPerTick", 16, 1, 256);
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
