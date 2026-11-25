package dev.soulvoid.longhaul.gametest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.route.Anchor;
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

    static RoutePlanner.Settings jj() {
        return new RoutePlanner.Settings(20000, 0.1875, 3, 2, 8, 8, 1024, 32, 8, -61, 2021, 32, 600_000, 8, 1.2, 5, 0.6, 16, 10);
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
        if (height < 1024 || Boolean.getBoolean("lht.noJJ")) {
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
            for (int x = -2048; x <= 2048; x += 128)
                for (int z = -2048; z <= 2048; z += 128) {
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
                final TerrainSampler.Column c = terrain.at(x, z);
                if (!c.wet() && (low == null || c.ground() < low.getY()))
                    low = new BlockPos(x, c.ground() + 1, z);
            }
            LOG.info("[LHT bench] JJThunder climb from {} to {} ({} up)", low, peak, peak.getY() - low.getY());
            status.set("searching");
            return plan(low, peak, terrain, progress, samples, t0);
        });
        tick(h, f, progress, samples);
    }

    static String plan(final BlockPos s, final BlockPos e, final TerrainRouter.Terrain terrain, final PlanProgress progress,
                       final AtomicLong samples, final long t0) {
        final RoutePlanner.Settings st = jj();
        final StringBuilder why = new StringBuilder();
        final TerrainRouter.Path path = TerrainRouter.search(Anchor.free(s), Anchor.free(e), st, terrain, why, progress);
        final long tSearch = System.nanoTime();
        LOG.info("[LHT bench] search {} in {} ms, {} samples, progress {}: {}", path == null ? "failed" : "ok",
            (tSearch - t0) / 1_000_000, samples.get(), progress.fraction(), why);
        if (path == null)
            return "search: " + why;
        final RoutePlanner.Layout lay = RoutePlanner.fromPath(path, Anchor.free(s), Anchor.free(e));
        final int n = lay.samples.size();
        final TerrainSampler.Column[] cols = new TerrainSampler.Column[n];
        for (int i = 0; i < n; i++)
            if (i % st.sampleSpacing() == 0 || i == n - 1)
                cols[i] = terrain.at(lay.samples.get(i)[0], lay.samples.get(i)[1]);
        final RoutePlanner.Result r = RoutePlanner.finish(lay, cols, st, TrackMaterial.ANDESITE, "create:track",
            "minecraft:stone_bricks", "minecraft:stone_bricks", Style.Light.NONE, Style.Mode.FULL);
        LOG.info("[LHT bench] finish in {} ms: length {}, bridge {}, tunnel {}, error {}, note {}",
            (System.nanoTime() - tSearch) / 1_000_000, r.length(), r.bridgeLength(), r.tunnelLength(), r.error(), r.note());
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

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 10)
    public static void plansBigClimb(final GameTestHelper h) {
        final BlockPos s = new BlockPos(3, 832, 230), e = new BlockPos(-2134, 1948, -943);
        final AtomicLong samples = new AtomicLong();
        final PlanProgress progress = new PlanProgress();
        final long t0 = System.nanoTime();
        tick(h, CompletableFuture.supplyAsync(() -> plan(s, e, (x, z) -> mountain(x, z, samples, s, e), progress, samples, t0)),
            progress, samples);
    }
}
