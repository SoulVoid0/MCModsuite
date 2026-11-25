package dev.soulvoid.voxyatmo.clouds;

import java.util.Arrays;

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
 * <p>When the target needs more, the outermost ring is widened (default), or, with {@code allowCoarserLodRing}, a
 * ring of twice the outermost scale is appended after padding the radius to a multiple of the new scale (the ring
 * placement divides the current radius by the scale, so it must divide evenly to leave no gap).</p>
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

    public static int builtRadiusBlocks() {
        return builtRadiusChunks < 0 ? -1 : builtRadiusChunks * chunkBlocks();
    }

    static LevelOfDetailConfig extend(final LevelOfDetailConfig original, final int need) {
        final int have = radiusChunks(original);
        final LevelOfDetail[] lods = original.getLods();
        if (need <= have || lods == null || lods.length == 0)
            return original;

        final int extra = need - have;
        final LevelOfDetail last = lods[lods.length - 1];
        final int lastScale = Math.max(1, last.chunkScale());
        final LevelOfDetail[] out;

        if (Config.allowCoarserRing()) {
            final int scale = lastScale * 2;
            final int pad = (scale - have % scale) % scale;
            if (pad % lastScale == 0) {
                out = Arrays.copyOf(copy(lods), lods.length + 1);
                out[lods.length - 1] = new LevelOfDetail(lastScale, last.spread() + pad / lastScale);
                out[lods.length] = new LevelOfDetail(scale, Math.max(1, ceilDiv(extra - pad, scale)));
                return log(original, new LevelOfDetailConfig(original.getPrimaryChunkSpan(), out));
            }
        }

        out = copy(lods);
        out[lods.length - 1] = new LevelOfDetail(lastScale, last.spread() + ceilDiv(extra, lastScale));
        return log(original, new LevelOfDetailConfig(original.getPrimaryChunkSpan(), out));
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
            + "({} -> {} LOD rings) to reach the distant-terrain distance.",
            radiusChunks(from) * chunkBlocks(), radiusChunks(to) * chunkBlocks(), from.getLods().length, to.getLods().length);
        return to;
    }

    private CloudLodPlanner() {}
}
