package dev.soulvoid.pmwadv.gametest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import dev.protomanly.pmweather.cm1.ICm1Driven;
import dev.protomanly.pmweather.config.ServerConfig;
import dev.protomanly.pmweather.event.GameBusEvents;
import dev.protomanly.pmweather.weather.Storm;
import dev.protomanly.pmweather.weather.WeatherHandlerServer;
import dev.protomanly.pmweather.weather.WindEngine;
import dev.soulvoid.pmwadv.area.StormAreaDriver;

/**
 * Headless checks with the real stack (PMWeather 0.16.4, PMWeatherCM1, PMPHYSICS, PMWeather Aeronautics + Sable) in
 * whatever world the GameTest server makes (JJThunder when its datapack is in run-gametest/world/datapacks).
 */
@GameTestHolder("pmweatheradvcompat")
@PrefixGameTestTemplate(false)
public final class StormAreaTests {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int AREA = 24000;

    @GameTest(template = "empty")
    public static void stackLoads(final GameTestHelper h) {
        track(h, () -> stackLoadsImpl(h));
    }

    private static void stackLoadsImpl(final GameTestHelper h) throws Exception {
        for (final String id : List.of("pmweather", "pmweathercm1", "pmocean", "pmweather_aeronautics", "sable"))
            if (!ModList.get().isLoaded(id))
                h.fail(id + " not loaded");
        final String pmw = ModList.get().getModContainerById("pmweather").orElseThrow().getModInfo().getVersion().toString();
        if (!pmw.startsWith("0.16.4"))
            h.fail("expected PMWeather 0.16.4, got " + pmw);
        // PMPHYSICS 0.1.0 links to exactly this method; it must exist in 0.16.4
        try {
            WindEngine.class.getMethod("getWind", Vec3.class, net.minecraft.world.level.Level.class);
        } catch (final NoSuchMethodException e) {
            h.fail("WindEngine.getWind(Vec3, Level) missing: PMPHYSICS would crash");
        }
        final ServerLevel level = h.getLevel();
        LOG.info("[PMWADV test] world height {} (min {}), JJThunder {}", level.getHeight(), level.getMinBuildHeight(),
            level.getHeight() >= 1024);
        h.succeed();
    }

    /** The driver alternates PMWeather's local range and the storm area, in the configured share. */
    @GameTest(template = "empty")
    public static void spawnRangeFollowsArea(final GameTestHelper h) {
        track(h, () -> spawnRangeFollowsAreaImpl(h));
    }

    private static void spawnRangeFollowsAreaImpl(final GameTestHelper h) throws Exception {
        final UUID fake = UUID.randomUUID();
        StormAreaDriver.report(fake, AREA);
        final int base = ServerConfig.spawnRange;
        int local = 0, far = 0;
        for (long t = 1; t <= 4000; t++) {
            StormAreaDriver.apply(1_000_000 + t + h.getTick() * 10_000L);
            if (ServerConfig.spawnRange == AREA) far++;
            else if (ServerConfig.spawnRange == StormAreaDriver.baseRange()) local++;
            else h.fail("unexpected spawnRange " + ServerConfig.spawnRange);
        }
        StormAreaDriver.report(fake, -1);
        final double share = local / 4000.0;
        LOG.info("[PMWADV test] spawnRange before {} -> local {} / area {} (local share {}), maxStorms {}", base, local, far,
            share, ServerConfig.maxStorms);
        if (share < 0.30 || share > 0.40)
            h.fail("local share " + share + " not near 0.35");
        h.succeed();
    }

    /**
     * Runs PMWeather's level tick at spawn times with a player in the world, so CM1's own spawner decides when and
     * which storm forms; records where. Storms are removed after being counted (CM1 keeps at most two).
     */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 30)
    public static void cm1FormsStormsAcrossArea(final GameTestHelper h) {
        track(h, () -> cm1FormsStormsAcrossAreaImpl(h));
    }

    private static void cm1FormsStormsAcrossAreaImpl(final GameTestHelper h) throws Exception {
        final ServerLevel level = h.getLevel();
        // the integrated server's real player (a mock player trips Sable's UDP handshake)
        if (level.players().isEmpty())
            h.fail("needs a player in the world (run with runAutoTest)");
        final ServerPlayer player = level.players().get(0);
        final UUID fake = UUID.randomUUID();
        StormAreaDriver.report(fake, AREA);
        final WeatherHandlerServer handler = (WeatherHandlerServer) GameBusEvents.MANAGERS.get(level.dimension());
        if (handler == null)
            h.fail("no PMWeather handler for " + level.dimension());
        handler.clearAllStorms();

        final int attempts = Integer.getInteger("pmwadv.attempts", 1500);
        final List<double[]> spawned = new ArrayList<>();
        final ServerLevelData data = (ServerLevelData) level.getLevelData();
        final long t0 = level.getGameTime();
        long nanos = 0, worst = 0;
        for (int i = 1; i <= attempts; i++) {
            data.setGameTime((t0 / 1200 + i) * 1200);
            StormAreaDriver.apply(2_000_000L + i);
            final long s = System.nanoTime();
            final Thread me = Thread.currentThread();
            final java.util.concurrent.ScheduledFuture<?> dog = WATCHDOG.schedule(() -> {
                final StringBuilder sb = new StringBuilder();
                for (final StackTraceElement e : me.getStackTrace())
                    sb.append("\n    at ").append(e);
                LOG.warn("[PMWADV test] spawn check still running after 3 s:{}", sb);
            }, 3, java.util.concurrent.TimeUnit.SECONDS);
            GameBusEvents.onLevelTick(new LevelTickEvent.Post(() -> true, level));
            dog.cancel(false);
            final long dt = System.nanoTime() - s;
            nanos += dt;
            worst = Math.max(worst, dt);
            if (dt > 1_000_000_000L)
                LOG.warn("[PMWADV test] slow spawn check #{}: {} ms, storms now {}", i, dt / 1_000_000,
                    handler.getStorms().size());
            for (final Storm st : new ArrayList<>(handler.getStorms())) {
                final boolean cm1 = st instanceof ICm1Driven d && d.isCm1Driven();
                spawned.add(new double[] {st.position.x - player.getX(), st.position.z - player.getZ(), cm1 ? 1 : 0,
                    ServerConfig.spawnRange});
                handler.removeStorm(st.ID);
            }
        }
        StormAreaDriver.report(fake, -1);
        long near = spawned.stream().filter(d -> Math.max(Math.abs(d[0]), Math.abs(d[1])) <= StormAreaDriver.baseRange()).count();
        double maxd = spawned.stream().mapToDouble(d -> Math.max(Math.abs(d[0]), Math.abs(d[1]))).max().orElse(0);
        long cm1 = spawned.stream().filter(d -> d[2] == 1).count();
        LOG.info("[PMWADV test] {} spawn checks -> {} storms ({} CM1-driven), {} within local range {}, farthest {} "
            + "blocks (area {}); spawn tick avg {} ms, worst {} ms", attempts, spawned.size(), cm1, near,
            StormAreaDriver.baseRange(), Math.round(maxd), AREA, nanos / attempts / 1e6, worst / 1e6);
        for (final double[] d : spawned.subList(0, Math.min(20, spawned.size())))
            LOG.info("[PMWADV test]   storm at dx {} dz {} cm1 {} (range used {})", Math.round(d[0]), Math.round(d[1]),
                d[2] == 1, (int) d[3]);
        if (spawned.isEmpty())
            h.fail("CM1 formed no storm in " + attempts + " spawn checks");
        if (maxd <= StormAreaDriver.baseRange() * 2)
            h.fail("no storm formed outside the local range");
        final double maxCm1 = spawned.stream().filter(d -> d[2] == 1)
            .mapToDouble(d -> Math.hypot(d[0], d[1])).max().orElse(0);
        LOG.info("[PMWADV test] farthest CM1 simulated storm {} blocks (CM1 alone: 10000)", Math.round(maxCm1));
        if (dev.soulvoid.pmwadv.core.Cm1SpawnRadiusPlugin.patched != 1)
            h.fail("CM1 spawn radius not patched (" + dev.soulvoid.pmwadv.core.Cm1SpawnRadiusPlugin.patched + ")");
        if (maxCm1 > AREA + 1)
            h.fail("CM1 storm outside the area: " + maxCm1);
        if (cm1 >= 4 && maxCm1 <= 10000)
            h.fail("no CM1 storm beyond CM1's own 10000-block circle");
        h.succeed();
    }

    /** A CM1 storm's wind is what PMPHYSICS (WindEngine.getWind) and PMWeather Aeronautics (its API) read. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void stormWindReachesPhysicsAndAeronautics(final GameTestHelper h) {
        try {
            stormWindReachesPhysicsAndAeronauticsImpl(h);
        } catch (final Exception e) {
            fail(h, e.toString());
        }
    }

    private static void stormWindReachesPhysicsAndAeronauticsImpl(final GameTestHelper h) throws Exception {
        final ServerLevel level = h.getLevel();
        final WeatherHandlerServer handler = (WeatherHandlerServer) GameBusEvents.MANAGERS.get(level.dimension());
        handler.clearAllStorms();
        final BlockPos at = h.absolutePos(BlockPos.ZERO);
        final Vec3 probe = new Vec3(at.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
            .WORLD_SURFACE, at.getX(), at.getZ()) + 20, at.getZ() + 0.5);
        final Method aero = Class.forName("com.axes.pmweather_aeronautics.PMWeatherWindApi")
            .getMethod("sampleRawMph", ServerLevel.class, Vec3.class);
        final double calm = WindEngine.getWind(probe, level).length();
        final double calmAero = ((Vec3) aero.invoke(null, level, probe)).length();

        // a storm made exactly the way CM1's natural spawner makes one, put on the probe
        final Storm storm = dev.protomanly.pmweather.weather.storms.StormTypes.SUPERCELL.create(
            new dev.protomanly.pmweather.weather.storms.StormSpawnProperties(handler, level, probe, null));
        if (storm instanceof ICm1Driven d) {
            d.setCm1Driven(true);
            final String scenario = dev.protomanly.pmweather.cm1.ScenarioSelector.selectScenario(handler, probe, level);
            d.setCm1Subtype(scenario);
            d.setCm1TimeElapsed(0f);
            LOG.info("[PMWADV test] CM1 scenario for the probe storm: {}", scenario);
        }
        storm.width = 15f;
        storm.windspeed = 0;
        storm.stage = 1;
        storm.velocity = Vec3.ZERO;
        storm.energy = 99;
        storm.initFirstTime();
        storm.calcRain();
        handler.addStorm(storm);
        handler.syncStormNew(storm);
        PENDING++;
        h.onEachTick(() -> storm.position = new Vec3(probe.x, storm.position.y, probe.z));
        h.runAfterDelay(20 * 60 * 2, () -> {
            try {
                final double stormy = WindEngine.getWind(probe, level).length();
                final double stormyAero = ((Vec3) aero.invoke(null, level, probe)).length();
                final double far = WindEngine.getWind(probe.add(20000, 0, 0), level).length();
                LOG.info("[PMWADV test] wind at the storm, before -> after 2 min (stage {}, windspeed {}, width {}): "
                    + "PMWeather/PMPHYSICS {} -> {} mph, Aeronautics {} -> {} mph; 20000 blocks away {} mph",
                    storm.stage, storm.windspeed, storm.width, round(calm), round(stormy), round(calmAero),
                    round(stormyAero), round(far));
                handler.clearAllStorms();
                if (Math.abs(stormy - calm) < 0.5)
                    fail(h, "the CM1 storm did not change PMWeather's wind (what PMPHYSICS reads)");
                if (Math.abs(stormyAero - calmAero) < 0.5)
                    fail(h, "the CM1 storm did not change the wind PMWeather Aeronautics reads");
                done++;
                h.succeed();
            } catch (final ReflectiveOperationException e) {
                fail(h, e.toString());
            } finally {
                PENDING--;
            }
        });
    }

    private static double round(final double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static void fail(final GameTestHelper h, final String msg) {
        failed++;
        done++;
        LOG.error("[PMWADV test] FAILED: {}", msg);
        h.fail(msg);
    }

    private static int PENDING;
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "pmwadv-test-watchdog");
            t.setDaemon(true);
            return t;
        });

    public static final int TOTAL = 4;
    private static int done;
    private static int failed;

    interface Body {
        void run() throws Exception;
    }

    private static void track(final GameTestHelper h, final Body body) {
        try {
            body.run();
        } catch (final net.minecraft.gametest.framework.GameTestAssertException e) {
            failed++;
            LOG.error("[PMWADV test] FAILED: {}", e.getMessage());
            throw e;
        } catch (final Exception | LinkageError e) {
            failed++;
            LOG.error("[PMWADV test] FAILED with exception", e);
            throw new net.minecraft.gametest.framework.GameTestAssertException(e.toString());
        } finally {
            done++;
        }
    }

    public static int finished() {
        return done;
    }

    public static int failures() {
        return failed;
    }

    private StormAreaTests() {}
}
