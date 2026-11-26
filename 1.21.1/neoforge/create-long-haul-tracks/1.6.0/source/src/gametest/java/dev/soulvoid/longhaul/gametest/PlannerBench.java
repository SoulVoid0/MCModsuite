package dev.soulvoid.longhaul.gametest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.route.Anchor;
import dev.soulvoid.longhaul.route.LongRouter;
import dev.soulvoid.longhaul.job.Corridor;
import dev.soulvoid.longhaul.route.PlanProgress;
import dev.soulvoid.longhaul.route.RoutePlanner;
import dev.soulvoid.longhaul.route.Style;
import dev.soulvoid.longhaul.route.TerrainRouter;
import dev.soulvoid.longhaul.route.TerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Plans a JJThunder-scale climb (about 1100 blocks up over 2400 blocks across, like a player's report) on synthetic
 * terrain, off-thread, and checks it finishes in reasonable time with a valid result or a clear error.
 */
@GameTestHolder("longhaultracks")
@PrefixGameTestTemplate(false)
public final class PlannerBench {

    private static final Logger LOG = LogUtils.getLogger();
    static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
            final Thread t = new Thread(r, "LHT bench planner");
            t.setDaemon(true);
            return t;
        });

    static boolean on(final String name) {
        return ("," + System.getProperty("lht.bench", "climb,big,user,far") + ",").contains("," + name + ",");
    }

    /** JJThunder world-gen noise, or null (test skipped) without the datapack. */
    static TerrainSampler.NoiseSource jjNoise(final GameTestHelper h, final String name) {
        final TerrainSampler.NoiseSource noise = overworldNoise(h.getLevel());
        if (noise.heights().getHeight() < 1024 || Boolean.getBoolean("lht.noJJ") || !on(name)) {
            LOG.info("[LHT bench] {}: skipped (JJThunder height {})", name, noise.heights().getHeight());
            return null;
        }
        return noise;
    }

    static BlockPos ground(final TerrainRouter.Terrain t, final int x, final int z) {
        return new BlockPos(x, t.at(x, z).ground() + 1, z);
    }

    /** The player's report: about 6,800 blocks across and several hundred up on JJThunder. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 30)
    public static void plansJJThunderUserRoute(final GameTestHelper h) {
        final TerrainSampler.NoiseSource noise = jjNoise(h, "user");
        if (noise == null) {
            h.succeed();
            return;
        }
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        final TerrainRouter.Terrain terrain = (x, z) -> {
            samples.incrementAndGet();
            return noise.sample(x, z);
        };
        tick(h, CompletableFuture.supplyAsync(() -> {
            // like the report: about 6,800 blocks away toward (2889, -5926), on the highest dry ground there
            final BlockPos s = ground(terrain, 1, 240);
            BlockPos e = null;
            final double a0 = Math.atan2(-5926 - 240, 2889 - 1);
            for (int k = -12; k <= 12; k++)
                for (int r = 6000; r <= 7200; r += 300) {
                    final int x = 1 + (int) (r * Math.cos(a0 + k * Math.PI / 72)), z = 240 + (int) (r * Math.sin(a0 + k * Math.PI / 72));
                    final TerrainSampler.Column c = terrain.at(x, z);
                    if (!c.wet() && (e == null || c.ground() + 1 > e.getY()))
                        e = new BlockPos(x, c.ground() + 1, z);
                }
            LOG.info("[LHT bench] user route from {} to {} ({} across, {} up)", s, e,
                (int) Math.hypot(e.getX() - s.getX(), e.getZ() - s.getZ()), e.getY() - s.getY());
            return plan(s, e, terrain, progress, samples, t0);
        }), progress, samples);
    }

    /** Harder than the report: from low ground about 6,800 blocks out up to one of the highest JJThunder peaks. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 30)
    public static void plansJJThunderLongClimb(final GameTestHelper h) {
        final TerrainSampler.NoiseSource noise = jjNoise(h, "longclimb");
        if (noise == null) {
            h.succeed();
            return;
        }
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        final TerrainRouter.Terrain terrain = (x, z) -> {
            samples.incrementAndGet();
            return noise.sample(x, z);
        };
        tick(h, CompletableFuture.supplyAsync(() -> {
            final BlockPos e = ground(terrain, 3840, 1536);
            BlockPos s = null;
            for (int a = 0; a < 72; a++) {
                final int x = 3840 + (int) (6800 * Math.cos(a * Math.PI / 36)), z = 1536 + (int) (6800 * Math.sin(a * Math.PI / 36));
                final TerrainSampler.Column c = terrain.at(x, z);
                if (!c.wet() && (s == null || c.ground() + 1 < s.getY()))
                    s = new BlockPos(x, c.ground() + 1, z);
            }
            LOG.info("[LHT bench] long climb from {} to {} ({} across, {} up)", s, e,
                (int) Math.hypot(e.getX() - s.getX(), e.getZ() - s.getZ()), e.getY() - s.getY());
            return plan(s, e, terrain, progress, samples, t0);
        }), progress, samples);
    }

    /** A 100,000-block JJThunder route. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 90)
    public static void plansJJThunderFar(final GameTestHelper h) {
        final TerrainSampler.NoiseSource noise = jjNoise(h, "far");
        if (noise == null) {
            h.succeed();
            return;
        }
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        final TerrainRouter.Terrain terrain = (x, z) -> {
            samples.incrementAndGet();
            return noise.sample(x, z);
        };
        tick(h, CompletableFuture.supplyAsync(() -> {
            final BlockPos s = ground(terrain, 1, 240), e = ground(terrain, 1 + 70711, 240 - 70711);
            LOG.info("[LHT bench] far route from {} to {} ({} across, {} up)", s, e,
                (int) Math.hypot(e.getX() - s.getX(), e.getZ() - s.getZ()), e.getY() - s.getY());
            return plan(s, e, terrain, progress, samples, t0);
        }), progress, samples);
    }

    static RoutePlanner.Settings jj() {
        return new RoutePlanner.Settings(1_000_000, 0.1875, 3, 2, 8, 8, 1024, 32, 8, -61, 2021, 32, 600_000, 8, 1.2, 5, 0.6, 16, 10, Integer.getInteger("lht.bh", 48), Integer.getInteger("lht.bs", 128));
    }

    /** Mountain massif: ground rises from ~830 near the start to ~1950 at the end, with ridges and valleys. */
    static TerrainSampler.Column mountain(final int x, final int z, final AtomicLong samples, final BlockPos s, final BlockPos e) {
        samples.incrementAndGet();
        final double ux = e.getX() - s.getX(), uz = e.getZ() - s.getZ(), len = Math.hypot(ux, uz);
        final double t = Math.max(0, Math.min(1.2, ((x - s.getX()) * ux + (z - s.getZ()) * uz) / (len * len)));
        final double side = ((x - s.getX()) * uz - (z - s.getZ()) * ux) / len;
        final double peak = Math.max(0, 1 - Math.hypot((x - e.getX()) / 900.0, (z - e.getZ()) / 900.0));
        double y = 830 + 300 * t + 820 * Math.pow(peak, 1.3) + 60 * Math.sin(x / 97.0) * Math.cos(z / 131.0)
            + 40 * Math.sin((x + z) / 53.0) - 0.02 * Math.abs(side);
        // spin a little so it costs like a real sampler
        double acc = 0;
        for (int i = 0; i < 200; i++)
            acc += Math.sin(i * x * 1e-3 + z);
        if (acc == 1e99)
            y++;
        return new TerrainSampler.Column((int) y, TerrainSampler.NO_WATER, true);
    }

    /** World-gen noise of the registry's overworld (JJThunder's when its datapack is in the test world). */
    static TerrainSampler.NoiseSource overworldNoise(final net.minecraft.server.level.ServerLevel level) {
        final var reg = level.registryAccess();
        final var settings = reg.registryOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
            .getHolderOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);
        final var presets = reg.registryOrThrow(net.minecraft.core.registries.Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        final var biomes = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(presets.getHolderOrThrow(
            net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        final var gen = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomes, settings);
        final var random = net.minecraft.world.level.levelgen.RandomState.create(settings.value(),
            reg.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE), 1234567L);
        final var ns = settings.value().noiseSettings();
        return new TerrainSampler.NoiseSource(gen, random,
            net.minecraft.world.level.LevelHeightAccessor.create(ns.minY(), ns.height()));
    }

    /** A real JJThunder climb: from a low valley to one of the highest peaks about 2400 blocks away. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 15)
    public static void plansJJThunderClimb(final GameTestHelper h) {
        final TerrainSampler.NoiseSource noise = overworldNoise(h.getLevel());
        final int height = noise.heights().getHeight();
        if (height < 1024 || Boolean.getBoolean("lht.noJJ") || !on("climb")) {
            LOG.info("[LHT bench] no JJThunder datapack (height {}); skipping", height);
            h.succeed();
            return;
        }
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        final java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>("");
        final CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            final TerrainRouter.Terrain terrain = (x, z) -> {
                samples.incrementAndGet();
                return noise.sample(x, z);
            };
            // accuracy and speed of the fast sampler against the generator's own column fill
            final java.util.Random rnd = new java.util.Random(7);
            long fastNs = 0, slowNs = 0;
            int sumErr = 0, maxErr = 0, big = 0;
            final int n = 150;
            for (int i = 0; i < n; i++) {
                final int x = rnd.nextInt(8000) - 4000, z = rnd.nextInt(8000) - 4000;
                long a = System.nanoTime();
                final int fast = noise.sample(x, z).ground();
                long b = System.nanoTime();
                final int slow = noise.exactSurface(x, z);
                long c = System.nanoTime();
                fastNs += b - a;
                slowNs += c - b;
                final int err = Math.abs(fast - slow);
                sumErr += err;
                maxErr = Math.max(maxErr, err);
                if (err > 8) {
                    big++;
                    LOG.info("[LHT bench] sampler off by {} at {} {}: fast {} exact {}", err, x, z, fast, slow);
                }
            }
            LOG.info("[LHT bench] sampler: fast {} us, exact {} us per column; mean error {}, max {}, {} of {} off by more than 8",
                fastNs / 1000 / n, slowNs / 1000 / n, sumErr / (double) n, maxErr, big, n);
            BlockPos peak = null;
            for (int x = -6144; x <= 6144; x += 256)
                for (int z = -6144; z <= 6144; z += 256) {
                    final TerrainSampler.Column c = terrain.at(x, z);
                    if (!c.wet() && (peak == null || c.ground() > peak.getY()))
                        peak = new BlockPos(x, c.ground() + 1, z);
                }
            final long tScan = System.nanoTime();
            LOG.info("[LHT bench] scan {} samples in {} ms ({} us each), peak {}", samples.get(), (tScan - t0) / 1_000_000,
                (tScan - t0) / 1000 / Math.max(1, samples.get()), peak);
            BlockPos low = null;
            for (int a = 0; a < 64; a++) {
                final int x = peak.getX() + (int) (2400 * Math.cos(a * Math.PI / 32)), z = peak.getZ() + (int) (2400 * Math.sin(a * Math.PI / 32));
                if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000)
                    continue;
                final TerrainSampler.Column c = terrain.at(x, z);
                if (!c.wet() && (low == null || c.ground() < low.getY()))
                    low = new BlockPos(x, c.ground() + 1, z);
            }
            LOG.info("[LHT bench] JJThunder climb from {} to {} ({} up)", low, peak, peak.getY() - low.getY());
            for (int a = 0; a < 8; a++) {
                final StringBuilder sb = new StringBuilder();
                for (int r = 0; r <= 1024; r += 64) {
                    final int x = peak.getX() + (int) (r * Math.cos(a * Math.PI / 4)), z = peak.getZ() + (int) (r * Math.sin(a * Math.PI / 4));
                    sb.append(terrain.at(x, z).ground()).append(' ');
                }
                LOG.info("[LHT bench] profile {} deg: {}", a * 45, sb);
            }
            status.set("searching");
            return plan(low, peak, terrain, progress, samples, t0);
        });
        tick(h, f, progress, samples);
    }

    static String plan(final BlockPos s, final BlockPos e, final TerrainRouter.Terrain terrain, final PlanProgress progress,
                       final AtomicLong samples, final long t0) {
        final RoutePlanner.Settings st = jj();
        final StringBuilder why = new StringBuilder();
        final TerrainRouter.Path path = LongRouter.search(Anchor.free(s), Anchor.free(e), st, terrain, why, progress, POOL);
        final long tSearch = System.nanoTime();
        LOG.info("[LHT bench] search {} in {} ms, {} samples, progress {}: {}", path == null ? "failed" : "ok",
            (tSearch - t0) / 1_000_000, samples.get(), progress.fraction(), why);
        if (path == null)
            return "search: " + why;
        LOG.info("[LHT bench] path: {} legs, {} waypoints, {} expanded", path.legs().size(), path.waypoints().size(), path.expanded());
        if (path.end() != null)
            LOG.info("[LHT bench] summit out of reach: line ends at {} instead of {}", path.end().pos(), e);
        final RoutePlanner.Layout lay = RoutePlanner.fromPath(path, Anchor.free(s), path.end() != null ? path.end() : Anchor.free(e));
        final int n = lay.samples.size();
        final TerrainSampler.Column[] cols = new TerrainSampler.Column[n];
        for (int i = 0; i < n; i++)
            if (i % st.sampleSpacing() == 0 || i == n - 1)
                cols[i] = terrain.at(lay.samples.get(i)[0], lay.samples.get(i)[1]);
        final RoutePlanner.Result r = RoutePlanner.finish(lay, cols, st, TrackMaterial.ANDESITE, "create:track",
            "minecraft:stone_bricks", "minecraft:stone_bricks", Style.Light.NONE, Style.Mode.FULL);
        LOG.info("[LHT bench] materials {}", r.estimate());
        LOG.info("[LHT bench] finish in {} ms: length {}, bridge {}, tunnel {}, error {}, note {}",
            (System.nanoTime() - tSearch) / 1_000_000, r.length(), r.bridgeLength(), r.tunnelLength(), r.error(), r.note());
        if (r.error() == null) {
            // the whole route as the builder and holograms see it
            final long t1 = System.nanoTime();
            final Corridor c = Corridor.build(r.pieces(), TrackMaterial.ANDESITE, Corridor.Spec.fromConfig());
            LOG.info("[LHT bench] corridor: {} centerline points, {} chunks, {} pieces in {} ms; total {} s",
                c.centerline.size(), c.chunks.size(), r.pieces().size(), (System.nanoTime() - t1) / 1_000_000,
                (System.nanoTime() - t0) / 1_000_000_000);
            // the line never climbs on a bridge over dry land: check the finished profile
            int climbsOnBridge = 0, maxHigh = 0, runStart = -1, runMax = 0;
            double runY0 = 0;
            for (int i = 4; i < c.centerline.size(); i += 4) {
                final Corridor.Point a = c.centerline.get(i - 4), b = c.centerline.get(i);
                final TerrainSampler.Column col = terrain.at((int) Math.floor(b.x()), (int) Math.floor(b.z()));
                final int over = (int) b.y() - (col.ground() + 1);
                if (!col.wet() && over > 16) {
                    if (runStart < 0) {
                        runStart = i;
                        runMax = 0;
                        runY0 = b.y();
                    }
                    runMax = Math.max(runMax, over);
                } else if (runStart >= 0) {
                    if (runMax > 48)
                        LOG.info("[LHT bench] tall run at {} {} {}: {} blocks long, up to {} above ground, track rises {}",
                            (int) c.centerline.get(runStart).x(), (int) c.centerline.get(runStart).y(), (int) c.centerline.get(runStart).z(),
                            i - runStart, runMax, (int) (a.y() - runY0));
                    runStart = -1;
                }
                if (!col.wet()) {
                    maxHigh = Math.max(maxHigh, over);
                    if (over > 16 && Math.abs(b.y() - a.y()) > 0.2)
                        climbsOnBridge++;
                }
            }
            LOG.info("[LHT bench] profile: highest above dry ground {}, points climbing more than 16 above dry ground {}", maxHigh, climbsOnBridge);
        }
        return r.error() == null ? "" : "finish: " + r.error();
    }

    static void tick(final GameTestHelper h, final CompletableFuture<String> f, final PlanProgress progress, final AtomicLong samples) {
        final int[] ticks = {0};
        h.onEachTick(() -> {
            try {
                Thread.sleep(45); // keep ticks close to real time so the timeout means minutes
            } catch (final InterruptedException ignored) {}
            if (++ticks[0] % 200 == 0)
                LOG.info("[LHT bench] {} s: progress {} eta {} samples {}", ticks[0] / 20, progress.fraction(),
                    progress.etaSeconds(), samples.get());
            if (!f.isDone())
                return;
            final String err = f.join();
            if (!err.isEmpty())
                h.fail(err);
            h.succeed();
        });
    }

    /** A second 100,000-block JJThunder route, the other way. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 90)
    public static void plansJJThunderFar2(final GameTestHelper h) {
        final TerrainSampler.NoiseSource noise = jjNoise(h, "far2");
        if (noise == null) {
            h.succeed();
            return;
        }
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        final TerrainRouter.Terrain terrain = (x, z) -> {
            samples.incrementAndGet();
            return noise.sample(x, z);
        };
        tick(h, CompletableFuture.supplyAsync(() -> {
            final BlockPos s = ground(terrain, 1, 240), e = ground(terrain, 1 - 60000, 240 + 80000);
            LOG.info("[LHT bench] far route from {} to {} ({} across, {} up)", s, e,
                (int) Math.hypot(e.getX() - s.getX(), e.getZ() - s.getZ()), e.getY() - s.getY());
            return plan(s, e, terrain, progress, samples, t0);
        }), progress, samples);
    }

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 10)
    public static void plansBigClimb(final GameTestHelper h) {
        if (!on("big")) {
            h.succeed();
            return;
        }
        final BlockPos s = new BlockPos(3, 832, 230), e = new BlockPos(-2134, 1948, -943);
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        tick(h, CompletableFuture.supplyAsync(() -> plan(s, e, (x, z) -> mountain(x, z, samples, s, e), progress, samples, t0)),
            progress, samples);
    }
}
