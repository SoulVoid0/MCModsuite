package dev.soulvoid.voxyatmo.clouds;


import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetail;
import dev.nonamecrackers2.simpleclouds.client.mesh.lod.LevelOfDetailConfig;
import dev.nonamecrackers2.simpleclouds.common.cloud.SimpleCloudsConstants;

import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.distance.TargetDistance;

/**
 * Grows Simple Clouds' cloud mesh area so it reaches the target distance.
 *
 * <p>The area is a square of cloud chunks: a primary block of {@code primaryChunkSpan} chunks, then LOD rings, each
 * {@code spread} chunks deep at {@code chunkScale}x size. Its radius in cloud chunks is
 * {@code effectiveChunkSpan / 2 = primary/2 + sum(scale * spread)}, and one cloud chunk is
 * {@code CHUNK_SIZE * CLOUD_SCALE} blocks (256). The HIGH preset is 4 + 2*4 + 4*3 + 8*2 = 40 chunks, about 10240
 * blocks; Simple Clouds fades clouds out over the last 10% of that radius. {@code getDepthFar} (both Simple Clouds'
 * and voxy-forged's overrides) follow the radius, so the projection far plane grows with it.</p>
 *
 * <p>When the target needs more, the outermost ring is made coarser (see {@link #extend}), and {@code CloudMeshBufferMixin}
 * sizes the mesh buffer to the chunk count so no chunk gets less than Simple Clouds' own share.</p>
 */
public final class CloudLodPlanner {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Simple Clouds starts fading at 90% of the area radius; size the area so the target sits before the fade. */
    private static final double FADE_START = 0.9;
    /** Quantise the target so small distance changes do not rebuild the mesh generator. */
    private static final int QUANTUM_BLOCKS = 1024;

    private static volatile int builtRadiusChunks = -1;
    private static volatile LevelOfDetailConfig lastOriginal;

    public static int chunkBlocks() {
        return SimpleCloudsConstants.CHUNK_SIZE * SimpleCloudsConstants.CLOUD_SCALE;
    }

    public static int radiusChunks(final LevelOfDetailConfig cfg) {
        return cfg.getEffectiveChunkSpan() / 2;
    }

    /** @return the radius (in cloud chunks) the area must have to show clouds out to the current target, or -1. */
    public static int wantedRadiusChunks() {
        if (!Config.growCloudArea())
            return -1;
        final int target = TargetDistance.cloudBlocks();
        if (target <= 0)
            return -1;
        final int quantised = ((target + QUANTUM_BLOCKS - 1) / QUANTUM_BLOCKS) * QUANTUM_BLOCKS;
        return (int) Math.ceil(quantised / FADE_START / chunkBlocks());
    }

    /** Called with the config Simple Clouds is about to build its mesh generator from. */
    public static LevelOfDetailConfig plan(final LevelOfDetailConfig original) {
        if (original == null)
            return null;
        lastOriginal = original;
        final LevelOfDetailConfig result = extend(original, wantedRadiusChunks());
        if (result != original)
            log(original, result);
        builtRadiusChunks = radiusChunks(result);
        return result;
    }

    /** @return whether the mesh generator should be rebuilt because the wanted radius moved past what was built. */
    public static boolean needsRebuild() {
        final LevelOfDetailConfig original = lastOriginal;
        if (original == null || builtRadiusChunks < 0)
            return false;
        final int expected = radiusChunks(extend(original, wantedRadiusChunks()));
        return expected != builtRadiusChunks;
    }

    /** Mesh chunks in Simple Clouds' HIGH preset; its 48 MB buffers are sized for this many. */
    public static final int HIGH_PRESET_CHUNKS = 364;
    /** Most the mesh buffers are grown, as a multiple of Simple Clouds' size (48 MB each). */
    public static final double MAX_BUFFER_SCALE = 2.5;

    /** @return the factor for Simple Clouds' mesh buffer sizes so each of {@code chunks} keeps the HIGH preset share. */
    public static double bufferScale(final int chunks) {
        if (!Config.coarserRings() || chunks <= HIGH_PRESET_CHUNKS)
            return 1.0;
        return Math.min(MAX_BUFFER_SCALE, chunks / (double) HIGH_PRESET_CHUNKS);
    }

    public static int builtRadiusBlocks() {
        return builtRadiusChunks < 0 ? -1 : builtRadiusChunks * chunkBlocks();
    }

    /** Outer ring scales tried, finest first (one cloud cell is 8 x scale blocks). */
    private static final int[] OUTER_SCALES = { 16, 32, 64 };
    /** Use the finest outer scale whose layout stays within this many mesh chunks (about 2x the HIGH preset). */
    private static final int CHUNK_BUDGET = 764;

    /**
     * Grows the area to {@code need} chunks of radius.
     *
     * <p>Default: keep the ring count (Simple Clouds' region shader allows at most 3 rings: 16 x 16 x levels local
     * invocations, limit 1024) and make the outermost ring coarser (16x, 32x or 64x) instead of wider, after widening
     * the ring before it so the radius divides evenly. Every chunk gets the same share of Simple Clouds' mesh buffer
     * whatever its scale, so the chunk count is what matters: widening the 8x ring to reach 32k blocks took 364 chunks
     * to 1560 (64k: 5448) and cut each chunk's share to a quarter (a fifteenth), so dense clouds overflowed it and lost
     * faces (holes, see-through slats). This keeps it at 720 (732).</p>
     *
     * <p>Legacy ({@code coarserRingsForFarClouds = false}): widen the outermost ring, as 1.0.0 to 1.4.0 did.</p>
     */
    static LevelOfDetailConfig extend(final LevelOfDetailConfig original, final int need) {
        final int have = radiusChunks(original);
        final LevelOfDetail[] lods = original.getLods();
        if (need <= have || lods == null || lods.length == 0)
            return original;

        if (Config.coarserRings() && lods.length >= 2) {
            LevelOfDetail[] best = null;
            int bestCount = Integer.MAX_VALUE;
            final int n = lods.length;
            final int midScale = Math.max(1, lods[n - 2].chunkScale());
            int innerRadius = original.getPrimaryChunkSpan() / 2;
            for (int i = 0; i < n - 2; i++)
                innerRadius += lods[i].chunkScale() * lods[i].spread();
            for (final int outer : OUTER_SCALES) {
                if (outer <= midScale || outer % midScale != 0)
                    continue;
                int midSpread = lods[n - 2].spread();
                while ((innerRadius + midSpread * midScale) % outer != 0)
                    midSpread++;
                final int midRadius = innerRadius + midSpread * midScale;
                final int outerSpread = Math.max(1, ceilDiv(need - midRadius, outer));
                final LevelOfDetail[] out = copy(lods);
                out[n - 2] = new LevelOfDetail(midScale, midSpread);
                out[n - 1] = new LevelOfDetail(outer, outerSpread);
                final int count = chunkCount(original.getPrimaryChunkSpan(), out);
                if (count <= CHUNK_BUDGET) {
                    best = out;
                    break;
                }
                if (count < bestCount) {
                    bestCount = count;
                    best = out;
                }
            }
            if (best != null)
                return new LevelOfDetailConfig(original.getPrimaryChunkSpan(), best);
        }

        final LevelOfDetail[] out = copy(lods);
        final LevelOfDetail last = lods[lods.length - 1];
        final int lastScale = Math.max(1, last.chunkScale());
        out[lods.length - 1] = new LevelOfDetail(lastScale, last.spread() + ceilDiv(need - have, lastScale));
        return new LevelOfDetailConfig(original.getPrimaryChunkSpan(), out);
    }

    /** Same count as {@code LevelOfDetailConfig.prepareChunks}, without building the chunk list. */
    static int chunkCount(final int primarySpan, final LevelOfDetail[] lods) {
        int radius = primarySpan / 2;
        int count = 0;
        for (int r = 0; r <= radius; r++)
            count += ring(r);
        for (final LevelOfDetail l : lods) {
            for (int d = 1; d <= l.spread(); d++)
                count += ring(radius / l.chunkScale() + d);
            radius += l.spread() * l.chunkScale();
        }
        return count;
    }

    private static int ring(final int r) {
        return 4 * r + 2 * Math.max(0, 2 * r - 2);
    }

    private static LevelOfDetail[] copy(final LevelOfDetail[] lods) {
        final LevelOfDetail[] c = new LevelOfDetail[lods.length];
        for (int i = 0; i < lods.length; i++)
            c[i] = new LevelOfDetail(lods[i].chunkScale(), lods[i].spread());
        return c;
    }

    private static int ceilDiv(final int a, final int b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }

    private static LevelOfDetailConfig log(final LevelOfDetailConfig from, final LevelOfDetailConfig to) {
        LOGGER.info("[Voxy Atmosphere Compat] Simple Clouds cloud area grown from {} to {} blocks radius "
            + "({} -> {} LOD rings, {} -> {} mesh chunks) to reach the distant-terrain distance.",
            radiusChunks(from) * chunkBlocks(), radiusChunks(to) * chunkBlocks(), from.getLods().length, to.getLods().length,
            from.getPreparedChunks().size(), to.getPreparedChunks().size());
        return to;
    }

    private CloudLodPlanner() {}
}
