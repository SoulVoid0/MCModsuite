package dev.soulvoid.pmwadv.area;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import dev.protomanly.pmweather.config.ServerConfig;
import dev.soulvoid.pmwadv.core.Config;

/**
 * Makes PMWeather/PMWeatherCM1 form storms across the Voxy area.
 *
 * <p>Both PMWeather's own natural spawner and CM1's replacement for it (GameBusEventsMixin#onLevelTickInject) pick
 * the spawn point as {@code player + random(-spawnRange, spawnRange)} on x and z, reading the public static
 * {@link ServerConfig#spawnRange} at that moment. Nothing else uses spawnRange except grouping players that are
 * within it of each other into one spawn centre. So before every server level tick this sets spawnRange to either
 * PMWeather's configured (local) range or the storm area radius, at random by {@code localSpawnShare}. CM1's
 * simulated storms ignore spawnRange (fixed 10000-block circle); {@link #cm1ScenarioRadius()} replaces that
 * constant with the same choice. CM1 still decides whether and which storm forms (its scenario selection, risk and
 * caps are untouched), only the "where" reaches the whole area.</p>
 *
 * <p>The values PMWeather's config set are remembered and restored when the server stops; if PMWeather reloads its
 * config, the new values become the base.</p>
 */
public final class StormAreaDriver {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_STORMS_BASE_RANGE = 2048;

    private static final Map<UUID, Integer> REPORTED = new ConcurrentHashMap<>();

    private static int baseRange = -1;
    private static int baseMaxStorms = -1;
    private static int lastWrittenRange = Integer.MIN_VALUE;
    private static int lastWrittenMax = Integer.MIN_VALUE;
    private static long lastTick = Long.MIN_VALUE;
    private static int area = -1;
    private static int loggedArea = -1;
    private static boolean far;

    /** CM1's own radius for simulated (scenario) storms, hard-coded in PMWeatherCM1 1.1.7. */
    public static final double CM1_SCENARIO_RADIUS = 10000.0;

    /**
     * Called from PMWeatherCM1's spawner in place of its 10000-block constant (see Cm1SpawnRadiusPlugin): this tick's
     * far/local choice, never smaller than CM1's own radius.
     */
    public static double cm1ScenarioRadius() {
        return far && area > 0 ? Math.max(area, CM1_SCENARIO_RADIUS) : CM1_SCENARIO_RADIUS;
    }

    public static void report(final UUID player, final int blocks) {
        if (blocks > 0)
            REPORTED.put(player, blocks);
        else
            REPORTED.remove(player);
        area = -1; // recompute on the next tick
    }

    @SubscribeEvent
    public static void onLogout(final PlayerEvent.PlayerLoggedOutEvent event) {
        REPORTED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLevelTick(final LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        apply(level.getServer().getTickCount());
    }

    /** Picks this tick's spawn range. spawnRange is global, so this runs once per server tick for every dimension. */
    public static void apply(final long tick) {
        if (tick == lastTick)
            return;
        lastTick = tick;
        captureBase();
        if (tick % 20 == 0 || area < 0)
            area = computeArea();

        far = ThreadLocalRandom.current().nextDouble() >= Config.localShare();
        write(far ? area : baseRange, scaledMaxStorms());
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        if (baseRange > 0) {
            ServerConfig.spawnRange = baseRange;
            ServerConfig.maxStorms = baseMaxStorms;
        }
        baseRange = baseMaxStorms = -1;
        lastWrittenRange = lastWrittenMax = Integer.MIN_VALUE;
        lastTick = Long.MIN_VALUE;
        area = loggedArea = -1;
        REPORTED.clear();
    }

    /** @return the storm area radius in blocks (at least PMWeather's own range). */
    public static int area() {
        return area > 0 ? area : computeArea();
    }

    public static int baseRange() {
        return baseRange;
    }

    public static Map<UUID, Integer> reported() {
        return REPORTED;
    }

    private static void captureBase() {
        // first tick, or PMWeather (re)loaded its config and overwrote our values
        if (baseRange < 0 || ServerConfig.spawnRange != lastWrittenRange)
            baseRange = ServerConfig.spawnRange;
        if (baseMaxStorms < 0 || ServerConfig.maxStorms != lastWrittenMax)
            baseMaxStorms = ServerConfig.maxStorms;
    }

    static int computeArea() {
        int wanted = -1;
        if (Config.followVoxy())
            for (final int v : REPORTED.values())
                wanted = Math.max(wanted, v);
        if (wanted <= 0)
            wanted = Config.fallbackArea();
        final int a = Math.max(Math.max(baseRange, 1), Math.min(wanted, Config.maxArea()));
        if (a != loggedArea) {
            loggedArea = a;
            LOGGER.info("[PMWeather Advanced Compat] storm area radius {} blocks ({}), PMWeather local range {}",
                a, REPORTED.isEmpty() ? "fallback, no Voxy report" : REPORTED.size() + " player report(s)", baseRange);
        }
        return a;
    }

    private static int scaledMaxStorms() {
        if (!Config.scaleMaxStorms() || baseMaxStorms <= 0)
            return baseMaxStorms;
        final long scaled = Math.round(baseMaxStorms * (double) area / MAX_STORMS_BASE_RANGE);
        return (int) Math.max(baseMaxStorms, Math.min(scaled, Config.maxStormsCap()));
    }

    private static void write(final int range, final int maxStorms) {
        ServerConfig.spawnRange = range;
        ServerConfig.maxStorms = maxStorms;
        lastWrittenRange = range;
        lastWrittenMax = maxStorms;
    }

    private StormAreaDriver() {}
}
