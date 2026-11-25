package dev.soulvoid.voxyatmo.clouds;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import dev.nonamecrackers2.simpleclouds.common.world.CloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.ServerCloudManager;
import dev.nonamecrackers2.simpleclouds.common.world.SyncType;
import dev.soulvoid.voxyatmo.core.Config;
import dev.soulvoid.voxyatmo.distance.TargetDistance;

/**
 * Keeps Simple Clouds' cloud layer above the terrain. Simple Clouds puts clouds at a fixed Y (128 by default). In tall
 * worlds (for example JJThunder To The Max, up to Y 2032) and around high LOD terrain that layer sits inside the
 * mountains: from above, clouds only show in the valleys between ridges, which reads as stripes and slabs cut by
 * terrain. This samples the world generator's surface height (works for Voxy/SeedGen terrain that is not loaded) out to
 * the cloud distance around the players, and raises the cloud height to a percentile of that terrain plus a margin.
 * It never goes below the world's own cloud height, which is remembered per dimension. Integrated server only.
 */
public final class CloudAltitude {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int MAX_HEIGHT = 2048;
    private static final int RINGS = 6;
    private static final int ANGLES = 16;
    private static final int MIN_CHANGE = 24;
    /** Spacing of the dense grid sampled around the player, in blocks. */
    private static final int NEAR_STEP = 128;

    private static final Map<ResourceKey<Level>, State> STATES = new HashMap<>();

    private static final class State {
        double centerX = Double.NaN;
        double centerZ = Double.NaN;
        int reach;
        CompletableFuture<int[]> pending;
    }

    public static void register(final IEventBus bus) {
        bus.addListener(CloudAltitude::onServerTick);
        bus.addListener(CloudAltitude::onLevelUnload);
    }

    private static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level)
            STATES.remove(level.dimension());
    }

    private static void onServerTick(final ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        if (server.getTickCount() % CHECK_INTERVAL_TICKS != 0 || !Config.autoCloudHeight())
            return;
        for (final ServerLevel level : server.getAllLevels()) {
            try {
                tickLevel(level);
            } catch (final Throwable t) {
                LOGGER.warn("[Voxy Atmosphere Compat] cloud altitude check failed in {}", level.dimension().location(), t);
            }
        }
    }

    private static void tickLevel(final ServerLevel level) {
        final List<ServerPlayer> players = level.players();
        if (players.isEmpty())
            return;
        final State state = STATES.computeIfAbsent(level.dimension(), k -> new State());
        if (state.pending != null) {
            if (!state.pending.isDone())
                return;
            final int[] heights = state.pending.getNow(null);
            state.pending = null;
            if (heights != null && heights.length > 0)
                apply(level, heights);
            return;
        }
        final ServerPlayer player = players.get(0);
        final int reach = Math.max(1024, Math.min(sampleReach(), 16384));
        final double moved = Double.isNaN(state.centerX) ? Double.MAX_VALUE
            : Math.hypot(player.getX() - state.centerX, player.getZ() - state.centerZ);
        if (moved < reach / 4.0 && reach == state.reach)
            return;
        state.centerX = player.getX();
        state.centerZ = player.getZ();
        state.reach = reach;
        final ChunkGenerator generator = level.getChunkSource().getGenerator();
        final RandomState random = level.getChunkSource().randomState();
        final int cx = player.getBlockX();
        final int cz = player.getBlockZ();
        final int near = Config.cloudClearRadius();
        state.pending = CompletableFuture.supplyAsync(() -> sample(level, generator, random, cx, cz, reach, near),
            Util.backgroundExecutor());
    }

    private static int sampleReach() {
        final int clouds = TargetDistance.cloudBlocks();
        return clouds > 0 ? clouds : 4096;
    }

    private static int[] sample(final ServerLevel level, final ChunkGenerator generator, final RandomState random,
                                final int cx, final int cz, final int reach, final int near) {
        final int cells = near > 0 ? near / NEAR_STEP : 0;
        final int side = cells * 2 + 1;
        final int[] out = new int[2 + RINGS * ANGLES];
        int n = 2;
        // Slot 0: highest surface within the clear radius (dense grid), slot 1: the player's column.
        int nearMax = generator.getBaseHeight(cx, cz, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        out[1] = nearMax;
        for (int i = 0; i < side * side && cells > 0; i++) {
            final int dx = (i % side - cells) * NEAR_STEP;
            final int dz = (i / side - cells) * NEAR_STEP;
            if (dx * (long) dx + dz * (long) dz > near * (long) near)
                continue;
            nearMax = Math.max(nearMax, generator.getBaseHeight(cx + dx, cz + dz,
                Heightmap.Types.WORLD_SURFACE_WG, level, random));
        }
        out[0] = nearMax;
        for (int ring = 1; ring <= RINGS; ring++) {
            final double r = reach * ring / (double) RINGS;
            for (int a = 0; a < ANGLES; a++) {
                final double ang = (a + (ring % 2) * 0.5) * (Math.PI * 2.0 / ANGLES);
                final int x = cx + (int) Math.round(Math.cos(ang) * r);
                final int z = cz + (int) Math.round(Math.sin(ang) * r);
                out[n++] = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
            }
        }
        return out;
    }

    /**
     * @return the cloud height wanted for these terrain samples, unclamped: above every peak within the clear radius
     * (slot 0) and above the configured percentile of the whole sampled area, plus the margin.
     */
    public static int targetFor(final int[] heights) {
        final int[] sorted = Arrays.copyOfRange(heights, 1, heights.length);
        Arrays.sort(sorted);
        final int pct = Math.max(50, Math.min(100, Config.cloudHeightPercentile()));
        final int idx = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * pct / 100.0) - 1);
        final int top = Math.max(heights[0], sorted[Math.max(0, idx)]);
        return ((top + Config.cloudHeightMargin()) + 15) / 16 * 16;
    }

    private static void apply(final ServerLevel level, final int[] heights) {
        if (!(CloudManager.get(level) instanceof ServerCloudManager manager))
            return;
        final Data data = Data.get(level);
        final int current = manager.getCloudHeight();
        if (data.base < 0) {
            data.base = current;
            data.setDirty();
        } else if (data.lastApplied >= 0 && current != data.lastApplied) {
            // Someone (a command, another mod) changed the height since: treat that as the new base.
            data.base = current;
            data.setDirty();
        }
        int wanted = Math.max(data.base, targetFor(heights));
        wanted = Math.min(wanted, Math.min(MAX_HEIGHT, level.getMaxBuildHeight() + 256));
        if (Math.abs(wanted - current) < MIN_CHANGE && data.lastApplied >= 0)
            return;
        if (wanted == current) {
            data.lastApplied = current;
            data.setDirty();
            return;
        }
        manager.setCloudHeight(wanted);
        manager.queueSync(SyncType.MOVEMENT);
        data.lastApplied = wanted;
        data.setDirty();
        LOGGER.info("[Voxy Atmosphere Compat] {}: cloud height {} -> {} (highest nearby peak {}, terrain samples {}..{}, base {})",
            level.dimension().location(), current, wanted, heights[0],
            Arrays.stream(heights).min().orElse(0), Arrays.stream(heights).max().orElse(0), data.base);
    }

    /** Remembers the world's own cloud height so the auto height can come back down to it. */
    static final class Data extends SavedData {
        int base = -1;
        int lastApplied = -1;

        static Data get(final ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Data::new, Data::load, null), "voxyatmocompat_cloud_altitude");
        }

        static Data load(final CompoundTag tag, final HolderLookup.Provider provider) {
            final Data d = new Data();
            d.base = tag.contains("Base") ? tag.getInt("Base") : -1;
            d.lastApplied = tag.contains("LastApplied") ? tag.getInt("LastApplied") : -1;
            return d;
        }

        @Override
        public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider provider) {
            tag.putInt("Base", this.base);
            tag.putInt("LastApplied", this.lastApplied);
            return tag;
        }
    }

    public static int baseHeight(final ServerLevel level) {
        return Data.get(level).base;
    }

    private CloudAltitude() {}
}
