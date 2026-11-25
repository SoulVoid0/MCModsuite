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
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
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

    /**
     * Snapshot of the generator, usable from a worker thread. For noise generators the surface is found by probing
     * the final density directly from above (32-block steps, then bisection; starting just above the last nearby
     * surface found on this thread) instead of filling the whole column from the build limit down the way
     * {@code getBaseHeight} does: on JJThunder's 2096-block-tall world that is many times faster.
     */
    public record NoiseSource(ChunkGenerator generator, RandomState random, LevelHeightAccessor heights,
                              DensityFunction density, int minY, int maxY, int seaLevel, ThreadLocal<int[]> hint) {
        public NoiseSource(final ChunkGenerator generator, final RandomState random, final LevelHeightAccessor heights) {
            this(generator, random, heights, densityOf(generator, random), lo(generator, heights), hi(generator, heights),
                generator.getSeaLevel(), ThreadLocal.withInitial(() -> new int[] {Integer.MIN_VALUE, 0, 0}));
        }

        public static NoiseSource of(final ServerLevel level) {
            return new NoiseSource(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight()));
        }

        private static DensityFunction densityOf(final ChunkGenerator g, final RandomState r) {
            return g instanceof NoiseBasedChunkGenerator && !Boolean.getBoolean("lht.slowNoise") ? r.router().finalDensity() : null;
        }

        private static int lo(final ChunkGenerator g, final LevelHeightAccessor h) {
            if (g instanceof NoiseBasedChunkGenerator n)
                return Math.max(h.getMinBuildHeight(), n.generatorSettings().value().noiseSettings().minY());
            return h.getMinBuildHeight();
        }

        private static int hi(final ChunkGenerator g, final LevelHeightAccessor h) {
            if (g instanceof NoiseBasedChunkGenerator n) {
                final var ns = n.generatorSettings().value().noiseSettings();
                return Math.min(h.getMaxBuildHeight() - 1, ns.minY() + ns.height() - 1);
            }
            return h.getMaxBuildHeight() - 1;
        }

        public Column sample(final int x, final int z) {
            final int floor;
            int water = NO_WATER;
            if (density != null) {
                floor = surface(x, z);
                if (floor < seaLevel - 1)
                    water = seaLevel - 1;
            } else {
                floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random) - 1;
                if (floor < generator.getSeaLevel()) {
                    final int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random) - 1;
                    if (surface > floor)
                        water = surface;
                }
            }
            final Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x),
                QuartPos.fromBlock(Math.max(floor, heights.getMinBuildHeight())), QuartPos.fromBlock(z), random.sampler());
            return new Column(floor, water, isMountain(biome));
        }

        /** The same answer as {@code getBaseHeight(OCEAN_FLOOR_WG) - 1} (the top solid block), from far fewer lookups. */
        public int exactSurface(final int x, final int z) {
            return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, random) - 1;
        }

        private boolean solid(final int x, final int y, final int z) {
            if (y < minY)
                return true;
            if (y > maxY)
                return false;
            return density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
        }

        /**
         * Top solid block of the column, found from above like the generator does (so caves and overhangs below the
         * surface never count): drop from open sky in 32-block steps until solid, then bisect. Open sky starts just
         * above the previous column's surface when that column was close, otherwise at the top of the world.
         */
        private int surface(final int x, final int z) {
            final int[] h = hint.get();
            final int coarse = 32;
            int y = maxY;
            if (h[0] != Integer.MIN_VALUE && Math.abs(h[1] - x) <= 96 && Math.abs(h[2] - z) <= 96) {
                final int y0 = h[0] + 160;
                if (y0 < maxY && !solid(x, y0, z) && !solid(x, y0 + 64, z) && !solid(x, y0 + 128, z))
                    y = y0;
            }
            while (y > minY && !solid(x, y - coarse, z))
                y -= coarse;
            final int ground;
            if (y <= minY) {
                ground = minY;
            } else {
                // air at y, solid at y - coarse: bisect for the boundary
                int air = y, solidY = y - coarse;
                while (air - solidY > 1) {
                    final int mid = (air + solidY) >> 1;
                    if (solid(x, mid, z))
                        solidY = mid;
                    else
                        air = mid;
                }
                ground = solidY;
            }
            h[0] = ground;
            h[1] = x;
            h[2] = z;
            return ground;
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
