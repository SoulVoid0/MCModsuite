package dev.soulvoid.longhaul.route;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Terrain heights along a route. Loaded chunks are read from the real world (on the server thread); anything else
 * is sampled straight from the dimension's noise generator, which works for chunks that were never generated and is
 * safe off-thread. With JJThunder To The Max installed that generator is JJThunder's own density functions, so the
 * planner "sees" its 2000-block mountains, canyons and rivers before a single chunk exists.
 */
public final class TerrainSampler {

    public static final int NO_WATER = Integer.MIN_VALUE;

    /** @param ground y of the top solid terrain block; @param water y of the top fluid block above it, or NO_WATER */
    public record Column(int ground, int water, boolean mountain) {
        public boolean wet() {
            return water != NO_WATER;
        }
    }

    /** Snapshot of the generator, usable from a worker thread. */
    public record NoiseSource(ChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        public static NoiseSource of(final ServerLevel level) {
            return new NoiseSource(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight()));
        }

        public Column sample(final int x, final int z) {
            final int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random) - 1;
            int water = NO_WATER;
            if (floor < generator.getSeaLevel()) {
                final int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random) - 1;
                if (surface > floor)
                    water = surface;
            }
            final Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x),
                QuartPos.fromBlock(Math.max(floor, heights.getMinBuildHeight())), QuartPos.fromBlock(z), random.sampler());
            return new Column(floor, water, isMountain(biome));
        }
    }

    public static boolean isMountain(final Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
    }

    /** Reads a loaded chunk column: skips air, plants, leaves, logs and fluids down to real terrain. Server thread only. */
    public static Column fromChunk(final ServerLevel level, final LevelChunk chunk, final int x, final int z) {
        final int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int water = NO_WATER;
        int ground = level.getMinBuildHeight();
        for (int y = top; y >= level.getMinBuildHeight(); y--) {
            final BlockState s = chunk.getBlockState(p.set(x, y, z));
            if (!s.getFluidState().isEmpty() && !s.isCollisionShapeFullBlock(level, p)) {
                if (water == NO_WATER)
                    water = y;
                continue;
            }
            if (!isTerrain(level, p, s))
                continue;
            ground = y;
            break;
        }
        final Holder<Biome> biome = level.getBiome(p.set(x, ground, z));
        return new Column(ground, water, isMountain(biome));
    }

    /** Solid natural ground: not air, foliage, wood or replaceable plants. */
    public static boolean isTerrain(final ServerLevel level, final BlockPos p, final BlockState s) {
        if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS))
            return false;
        return s.isCollisionShapeFullBlock(level, p);
    }

    private TerrainSampler() {}
}
