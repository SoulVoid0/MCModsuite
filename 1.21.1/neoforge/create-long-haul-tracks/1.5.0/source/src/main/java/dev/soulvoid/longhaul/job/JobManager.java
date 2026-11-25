package dev.soulvoid.longhaul.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import com.simibubi.create.content.trains.track.TrackShape;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.compat.JJThunderCompat;
import dev.soulvoid.longhaul.net.Net;
import dev.soulvoid.longhaul.route.Anchor;
import dev.soulvoid.longhaul.route.Dir8;
import dev.soulvoid.longhaul.route.PlanProgress;
import dev.soulvoid.longhaul.route.RoutePlanner;
import dev.soulvoid.longhaul.route.Style;
import dev.soulvoid.longhaul.route.TerrainRouter;
import dev.soulvoid.longhaul.route.TerrainSampler;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns every route job and every player's point selection; drives planning, surveying and building. */
public final class JobManager extends SavedData {

    private static final String NAME = "longhaultracks_jobs";

    /** A player's selection before it becomes a job. */
    static final class Draft {
        ResourceKey<Level> dim = Level.OVERWORLD;
        BlockPos start, end;
        boolean startSurface, endSurface;
        ResourceLocation bridge = BuiltInRegistries.BLOCK.getKey(Blocks.STONE_BRICKS);
        ResourceLocation lining = BuiltInRegistries.BLOCK.getKey(Blocks.STONE_BRICKS);
        ResourceLocation track = TrackMaterial.ANDESITE.id;
        Style.Light light = Style.Light.NONE;
        Style.Mode mode = Style.Mode.FULL;
        String message = "";
        boolean planning;
        UUID preview;
        /** Progress and cancel flag of the plan running for this draft, if any. */
        transient PlanProgress progress;
    }

    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, Map<String, Long>> refunds = new HashMap<>();
    private final transient Map<UUID, Builder> builders = new HashMap<>();
    private final transient Map<UUID, Integer> waits = new HashMap<>();
    private MinecraftServer server;
    private int tick;

    public static JobManager get(final MinecraftServer server) {
        final JobManager m = server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(JobManager::new, JobManager::load, null), NAME);
        m.server = server;
        return m;
    }

    // ----------------------------------------------------------------------------------------- persistence

    private static JobManager load(final CompoundTag t, final HolderLookup.Provider lookup) {
        final JobManager m = new JobManager();
        for (final Tag j : t.getList("Jobs", Tag.TAG_COMPOUND)) {
            try {
                final Job job = Job.load((CompoundTag) j);
                m.jobs.put(job.id, job);
            } catch (final RuntimeException ignored) {
                // skip a corrupt job rather than losing all of them
            }
        }
        for (final Tag r : t.getList("Refunds", Tag.TAG_COMPOUND)) {
            final CompoundTag c = (CompoundTag) r;
            final Map<String, Long> items = new LinkedHashMap<>();
            final CompoundTag it = c.getCompound("Items");
            for (final String k : it.getAllKeys())
                items.put(k, it.getLong(k));
            m.refunds.put(c.getUUID("Player"), items);
        }
        for (final Tag d : t.getList("Drafts", Tag.TAG_COMPOUND)) {
            final CompoundTag c = (CompoundTag) d;
            final Draft dr = new Draft();
            dr.dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.getString("Dim")));
            if (c.contains("Start")) dr.start = BlockPos.of(c.getLong("Start"));
            if (c.contains("End")) dr.end = BlockPos.of(c.getLong("End"));
            dr.startSurface = c.getBoolean("SS");
            dr.endSurface = c.getBoolean("ES");
            dr.bridge = ResourceLocation.parse(c.getString("Bridge"));
            dr.lining = ResourceLocation.parse(c.getString("Lining"));
            dr.track = ResourceLocation.parse(c.getString("Track"));
            dr.light = Style.Light.parse(c.getString("Light"));
            dr.mode = Style.Mode.parse(c.getString("Mode"));
            if (c.hasUUID("Preview")) dr.preview = c.getUUID("Preview");
            m.drafts.put(c.getUUID("Player"), dr);
        }
        return m;
    }

    @Override
    public CompoundTag save(final CompoundTag t, final HolderLookup.Provider lookup) {
        final ListTag js = new ListTag();
        for (final Job j : jobs.values())
            js.add(j.save());
        t.put("Jobs", js);
        final ListTag rs = new ListTag();
        refunds.forEach((p, items) -> {
            final CompoundTag c = new CompoundTag();
            c.putUUID("Player", p);
            final CompoundTag it = new CompoundTag();
            items.forEach(it::putLong);
            c.put("Items", it);
            rs.add(c);
        });
        t.put("Refunds", rs);
        final ListTag ds = new ListTag();
        drafts.forEach((p, d) -> {
            final CompoundTag c = new CompoundTag();
            c.putUUID("Player", p);
            c.putString("Dim", d.dim.location().toString());
            if (d.start != null) c.putLong("Start", d.start.asLong());
            if (d.end != null) c.putLong("End", d.end.asLong());
            c.putBoolean("SS", d.startSurface);
            c.putBoolean("ES", d.endSurface);
            c.putString("Bridge", d.bridge.toString());
            c.putString("Lining", d.lining.toString());
            c.putString("Track", d.track.toString());
            c.putString("Light", d.light.key());
            c.putString("Mode", d.mode.key());
            if (d.preview != null) c.putUUID("Preview", d.preview);
            ds.add(c);
        });
        t.put("Drafts", ds);
        return t;
    }

    public List<Job> jobs() {
        return new ArrayList<>(jobs.values());
    }

    public Job job(final UUID id) {
        return id == null ? null : jobs.get(id);
    }

    /** Last planning message for an owner (errors included), or "". */
    public String draftMessage(final UUID owner) {
        final Draft d = drafts.get(owner);
        return d == null || d.message == null ? "" : d.message;
    }

    Draft draft(final ServerPlayer p) {
        return drafts.computeIfAbsent(p.getUUID(), k -> new Draft());
    }

    // ------------------------------------------------------------------------------------------- picking

    /** An exact block: a track block is joined, anything else gets the track placed on top of it. */
    public void pickBlock(final ServerPlayer player, final BlockPos clicked) {
        final ServerLevel level = player.serverLevel();
        BlockPos pos = clicked;
        if (level.isLoaded(clicked)) {
            final BlockState s = level.getBlockState(clicked);
            if (!(s.getBlock() instanceof ITrackBlock))
                pos = s.canBeReplaced() ? clicked : clicked.above();
        } else
            pos = clicked.above();
        setPoint(player, level, pos, false, null);
    }

    public void mapPick(final ServerPlayer player, final int x, final int z, final boolean start) {
        setPoint(player, player.serverLevel(), new BlockPos(x, player.serverLevel().getMinBuildHeight(), z), true, start);
    }

    /** Ray-marches the generator's terrain along the player's view: works for chunks nobody has loaded. */
    public void farPick(final ServerPlayer player, final Vec3 origin, final Vec3 dirIn) {
        final ServerLevel level = player.serverLevel();
        final Vec3 dir = dirIn.normalize();
        if (!Double.isFinite(dir.x) || origin.distanceToSqr(player.getEyePosition()) > 64)
            return;
        final TerrainSampler.NoiseSource noise = TerrainSampler.NoiseSource.of(level);
        final int range = Config.FAR_PICK_RANGE.get();
        final int top = level.getMaxBuildHeight();
        player.displayClientMessage(Component.translatable("longhaultracks.msg.far_pick"), true);
        CompletableFuture.supplyAsync(() -> {
            double prevT = 0;
            double step = 4;
            for (double t = 4; t <= range; t += step) {
                final Vec3 p = origin.add(dir.scale(t));
                step = 4;
                if (p.y > top)
                    continue;
                final TerrainSampler.Column c = noise.sample((int) Math.floor(p.x), (int) Math.floor(p.z));
                final int surface = c.wet() ? c.water() : c.ground();
                // far above the ground the ray can take longer strides (refined below once it is under the surface)
                step = Math.max(4, Math.min(48, (p.y - surface) * 0.5));
                if (p.y <= surface + 1) {
                    for (double u = prevT; u <= t; u += 1) {
                        final Vec3 q = origin.add(dir.scale(u));
                        final TerrainSampler.Column cq = noise.sample((int) Math.floor(q.x), (int) Math.floor(q.z));
                        if (q.y <= (cq.wet() ? cq.water() : cq.ground()) + 1)
                            return BlockPos.containing(q);
                    }
                    return BlockPos.containing(p);
                }
                prevT = t;
            }
            return null;
        }, Util.backgroundExecutor()).thenAcceptAsync(hit -> {
            if (hit == null) {
                player.displayClientMessage(Component.translatable("longhaultracks.msg.far_pick_none", range), true);
                return;
            }
            setPoint(player, level, hit, true, null);
        }, server);
    }

    /**
     * @param surface the y is resolved to the real surface once the chunk is loaded
     * @param start   true/false forces which end; null alternates start, end, start...
     */
    public void setPoint(final ServerPlayer player, final ServerLevel level, final BlockPos pos, final boolean surface,
                         final Boolean start) {
        final Draft d = draft(player);
        if (d.planning) {
            player.displayClientMessage(Component.translatable("longhaultracks.msg.busy"), true);
            return;
        }
        if (!d.dim.equals(level.dimension())) {
            d.start = d.end = null;
            d.dim = level.dimension();
        }
        final boolean asStart = start != null ? start : (d.start == null || d.end != null);
        if (asStart) {
            d.start = pos;
            d.startSurface = surface;
            if (start == null)
                d.end = null;
        } else {
            d.end = pos;
            d.endSurface = surface;
        }
        d.message = "";
        player.displayClientMessage(Component.translatable(asStart ? "longhaultracks.msg.start_set" : "longhaultracks.msg.end_set",
            pos.getX(), surface ? "~" : String.valueOf(pos.getY()), pos.getZ()), true);
        setDirty();
        syncDraft(player.getUUID());
        if (d.start != null && d.end != null)
            plan(player);
    }

    // ------------------------------------------------------------------------------------------ planning

    public void plan(final ServerPlayer player) {
        plan(player.getUUID(), player.getGameProfile().getName());
    }

    public void plan(final UUID owner, final String name) {
        final Draft d = drafts.computeIfAbsent(owner, k -> new Draft());
        final ServerLevel level = server.getLevel(d.dim);
        if (level == null || d.start == null || d.end == null || d.planning)
            return;
        final double dist = Math.sqrt(d.start.distSqr(d.end));
        if (dist > Config.MAX_ROUTE_LENGTH.get()) {
            fail(owner, d, Component.translatable("longhaultracks.msg.too_long", (int) dist, Config.MAX_ROUTE_LENGTH.get()).getString());
            return;
        }
        d.planning = true;
        final PlanProgress progress = new PlanProgress();
        d.progress = progress;
        d.message = Component.translatable("longhaultracks.msg.planning").getString();
        syncDraft(owner);
        final CompletableFuture<?> a = level.getChunkSource()
            .getChunkFuture(d.start.getX() >> 4, d.start.getZ() >> 4, ChunkStatus.FULL, true);
        final CompletableFuture<?> b = level.getChunkSource()
            .getChunkFuture(d.end.getX() >> 4, d.end.getZ() >> 4, ChunkStatus.FULL, true);
        CompletableFuture.allOf(a, b).orTimeout(120, TimeUnit.SECONDS).whenCompleteAsync((v, err) -> {
            if (progress.cancelled() || d.progress != progress)
                return;
            if (err != null) {
                d.planning = false;
                fail(owner, d, "Could not load the end chunks: " + err.getMessage());
                return;
            }
            try {
                planLoaded(owner, name, level, d, progress);
            } catch (final RuntimeException e) {
                d.planning = false;
                fail(owner, d, "Planning failed: " + e);
            }
        }, server);
    }

    private void planLoaded(final UUID owner, final String name, final ServerLevel level, final Draft d,
                            final PlanProgress progress) {
        if (d.startSurface)
            d.start = surface(level, d.start);
        if (d.endSurface)
            d.end = surface(level, d.end);
        d.startSurface = d.endSurface = false;
        final StringBuilder err = new StringBuilder();
        final Anchor s = anchor(level, d.start, err);
        final Anchor e = err.isEmpty() ? anchor(level, d.end, err) : null;
        if (!err.isEmpty()) {
            d.planning = false;
            fail(owner, d, err.toString());
            return;
        }
        final RoutePlanner.Settings st = settings(level);
        // real terrain where chunks are loaded (copied now, on the server thread), world-gen noise everywhere else
        final Map<Long, TerrainSampler.Column[]> loaded = snapshot(level, s.pos(), e.pos());
        final TerrainSampler.NoiseSource noise = TerrainSampler.NoiseSource.of(level);
        final TerrainRouter.Terrain terrain = (x, z) -> {
            final TerrainSampler.Column[] c = loaded.get(ChunkPos.asLong(x >> 4, z >> 4));
            return c != null ? c[((z & 15) >> 1) * 8 + ((x & 15) >> 1)] : noise.sample(x, z);
        };
        final TrackMaterial material = TrackMaterial.ALL.getOrDefault(d.track, TrackMaterial.ANDESITE);
        final String trackItem = Builder.key(material.getBlock());
        final String floorItem = Builder.key(blockOr(d.bridge, Blocks.STONE_BRICKS));
        final String wallItem = Builder.key(blockOr(d.lining, blockOr(d.bridge, Blocks.STONE_BRICKS)));
        final Style.Light light = d.light;
        final Style.Mode mode = d.mode;
        final BlockPos startPos = d.start, endPos = d.end;
        final boolean sj = s.onTrack(), ej = e.onTrack();
        final int maxLength = Config.MAX_ROUTE_LENGTH.get();
        final java.util.concurrent.atomic.AtomicReference<BlockPos> endUsed = new java.util.concurrent.atomic.AtomicReference<>(endPos);
        CompletableFuture.supplyAsync(() -> {
            final StringBuilder why = new StringBuilder();
            final TerrainRouter.Path path = TerrainRouter.search(s, e, st, terrain, why, progress);
            if (progress.cancelled())
                return RoutePlanner.Result.fail("cancelled");
            final RoutePlanner.Layout lay;
            if (path != null) {
                lay = RoutePlanner.fromPath(path, s, path.end() != null ? path.end() : e);
                if (path.end() != null) {
                    final BlockPos q = path.end().pos();
                    endUsed.set(q);
                    lay.note = "The end (" + endPos.getX() + " " + endPos.getY() + " " + endPos.getZ() + ") is on a summit too steep to reach at the grade without a viaduct, so the line ends just below it at "
                        + q.getX() + " " + q.getY() + " " + q.getZ() + " (" + Math.round(Math.hypot(q.getX() - endPos.getX(), q.getZ() - endPos.getZ())) + " blocks away, "
                        + Math.abs(endPos.getY() - q.getY()) + " lower).";
                }
                com.mojang.logging.LogUtils.getLogger().info("[Long Haul Tracks] route search: {} steps, {} legs, {} blocks",
                    path.expanded(), path.legs().size(), lay.length());
            } else {
                final StringBuilder err2 = new StringBuilder();
                lay = RoutePlanner.layout(s, e, maxLength, err2);
                if (lay == null)
                    return RoutePlanner.Result.fail(why + " " + err2);
                lay.note = why + " Using a direct line instead.";
            }
            final int n = lay.samples.size();
            final TerrainSampler.Column[] cols = new TerrainSampler.Column[n];
            for (int i = 0; i < n; i++)
                if (i % st.sampleSpacing() == 0 || i == n - 1) {
                    if (progress.cancelled())
                        return RoutePlanner.Result.fail("cancelled");
                    final int[] xz = lay.samples.get(i);
                    cols[i] = terrain.at(xz[0], xz[1]);
                    if (i % 256 == 0)
                        progress.report(0.9 + 0.09 * i / n);
                }
            final RoutePlanner.Result r = RoutePlanner.finish(lay, cols, st, material, trackItem, floorItem, wallItem, light, mode);
            if (r.error() != null && !lay.note.isEmpty())
                return RoutePlanner.Result.fail(path != null ? r.error() : lay.note + " " + r.error());
            return lay.note.isEmpty() ? r : r.withNote(lay.note);
        }, Util.backgroundExecutor()).whenCompleteAsync((res, ex) -> {
            if (progress.cancelled() || d.progress != progress)
                return; // cancelled, or superseded by a newer plan
            d.planning = false;
            d.progress = null;
            if (ex != null || res == null) {
                com.mojang.logging.LogUtils.getLogger().error("[Long Haul Tracks] planning failed", ex);
                fail(owner, d, "Planning failed: " + ex);
                return;
            }
            if (res.error() != null) {
                fail(owner, d, res.error());
                return;
            }
            if (d.preview != null) {
                final Job old = jobs.get(d.preview);
                if (old != null && old.state == Job.State.PREVIEW)
                    remove(old);
            }
            final Job j = new Job(UUID.randomUUID());
            j.owner = owner;
            j.ownerName = name;
            j.dim = level.dimension();
            j.start = startPos;
            j.end = endUsed.get();
            j.startJunction = sj;
            j.endJunction = ej && endUsed.get().equals(endPos);
            j.trackMaterial = material.id;
            j.bridgeBlock = d.bridge;
            j.liningBlock = d.lining;
            j.light = light;
            j.mode = mode;
            j.pieces = res.pieces();
            j.length = res.length();
            j.bridgeLength = res.bridgeLength();
            j.tunnelLength = res.tunnelLength();
            j.required = new LinkedHashMap<>(res.estimate());
            j.message = Component.translatable("longhaultracks.msg.preview").getString();
            jobs.put(j.id, j);
            d.preview = j.id;
            d.message = "";
            setDirty();
            sendGeometry(j, null);
            sendStatus(j);
            syncDraft(owner);
            msg(owner, Component.translatable("longhaultracks.msg.planned", j.length, j.bridgeLength,
                j.tunnelLength).withStyle(ChatFormatting.GREEN), false);
            if (!res.note().isEmpty())
                msg(owner, Component.literal(res.note()).withStyle(ChatFormatting.YELLOW), false);
        }, server);
    }

    private static Block blockOr(final ResourceLocation id, final Block fallback) {
        final Block b = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
        return b == Blocks.AIR ? fallback : b;
    }

    /**
     * Terrain columns (every other block) of the loaded chunks around the route, so the off-thread search sees
     * builds, structures and edits the world generator does not know about.
     */
    private static Map<Long, TerrainSampler.Column[]> snapshot(final ServerLevel level, final BlockPos a, final BlockPos b) {
        final Map<Long, TerrainSampler.Column[]> out = new HashMap<>();
        final double d = Math.sqrt(a.distSqr(b));
        final int pad = (int) Math.min(4096, Math.max(1024, d * 0.6));
        final int x0 = (Math.min(a.getX(), b.getX()) - pad) >> 4, x1 = (Math.max(a.getX(), b.getX()) + pad) >> 4;
        final int z0 = (Math.min(a.getZ(), b.getZ()) - pad) >> 4, z1 = (Math.max(a.getZ(), b.getZ()) + pad) >> 4;
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) {
                final LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null)
                    continue;
                final TerrainSampler.Column[] cols = new TerrainSampler.Column[64];
                for (int i = 0; i < 64; i++)
                    cols[i] = TerrainSampler.fromChunk(level, chunk, (cx << 4) + (i & 7) * 2, (cz << 4) + (i >> 3) * 2);
                out.put(ChunkPos.asLong(cx, cz), cols);
            }
        return out;
    }

    private static BlockPos surface(final ServerLevel level, final BlockPos p) {
        final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new BlockPos(p.getX(), y, p.getZ());
    }

    /** Works out how a route may leave a point: free, extending a dead end, or branching off a line. */
    static Anchor anchor(final ServerLevel level, final BlockPos pos, final StringBuilder err) {
        final BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof TrackBlock)) {
            if (s.getBlock() instanceof ITrackBlock)
                err.append("That track piece (station, crossing or similar) cannot be joined; pick a plain straight track block.");
            return Anchor.free(pos);
        }
        final TrackShape shape = s.getValue(TrackBlock.SHAPE);
        final Dir8[] dirs = Dir8.ofShape(shape);
        if (dirs == null) {
            err.append("Only flat, straight track can be joined (not a crossing or slope). Pick a straight track block.");
            return null;
        }
        if (level.getBlockEntity(pos) instanceof TrackBlockEntity te && te.isTilted()) {
            err.append("That track block sits on a slope; pick a flat one.");
            return null;
        }
        final boolean[] used = new boolean[2];
        for (int i = 0; i < 2; i++)
            for (int dy = -1; dy <= 1; dy++)
                if (level.getBlockState(pos.offset(dirs[i].dx, dy, dirs[i].dz)).getBlock() instanceof ITrackBlock)
                    used[i] = true;
        if (level.getBlockEntity(pos) instanceof TrackBlockEntity te)
            for (final BezierConnection bc : te.getConnections().values()) {
                final Vec3 ax = bc.axes.getFirst();
                for (int i = 0; i < 2; i++)
                    if (ax.x * dirs[i].dx + ax.z * dirs[i].dz > 0)
                        used[i] = true;
            }
        if (used[0] && used[1])
            return new Anchor(pos, List.of(dirs[0], dirs[1]), true, true);
        if (used[0])
            return new Anchor(pos, List.of(dirs[1]), true, false);
        if (used[1])
            return new Anchor(pos, List.of(dirs[0]), true, false);
        return new Anchor(pos, List.of(dirs[0], dirs[1]), true, false);
    }

    static RoutePlanner.Settings settings(final ServerLevel level) {
        final JJThunderCompat.Profile pr = JJThunderCompat.profile(level);
        return new RoutePlanner.Settings(Config.MAX_ROUTE_LENGTH.get(), Config.MAX_GRADE.get(),
            Config.BRIDGE_THRESHOLD.get(), Config.WATER_CLEARANCE.get(), Config.TUNNEL_HEIGHT.get(),
            Config.PILLAR_SPACING.get(), pillarDepth(level), Math.max(Config.SMOOTHING_RADIUS.get(), pr.minSmoothing()),
            Math.max(Config.SAMPLE_SPACING.get(), pr.minSampleSpacing()), level.getMinBuildHeight() + 3,
            level.getMaxBuildHeight() - Config.TUNNEL_HEIGHT.get() - 3, Math.max(Config.SEARCH_CELL.get(), pr.minCell()),
            (int) Math.min(20_000_000, Config.SEARCH_MAX_STEPS.get() * pr.stepsMul()), Config.TURN_COST.get(),
            Config.TUNNEL_COST.get() * pr.tunnelMul(), Config.LAND_BRIDGE_COST.get(), Config.WATER_BRIDGE_COST.get(),
            Config.BLOCKS_PER_TRACK.get(), Config.LIGHT_SPACING.get(), Config.MAX_BRIDGE_HEIGHT.get(), Config.MAX_BRIDGE_SPAN.get());
    }

    static int pillarDepth(final ServerLevel level) {
        return Math.max(Config.MAX_PILLAR_DEPTH.get(), JJThunderCompat.profile(level).minPillarDepth());
    }

    private void fail(final UUID owner, final Draft d, final String msg) {
        d.message = msg;
        syncDraft(owner);
        msg(owner, Component.literal(msg).withStyle(ChatFormatting.RED), false);
    }

    private void msg(final UUID owner, final Component c, final boolean actionBar) {
        final ServerPlayer p = server.getPlayerList().getPlayer(owner);
        if (p != null)
            p.displayClientMessage(c, actionBar);
        else
            com.mojang.logging.LogUtils.getLogger().info("[Long Haul Tracks] {}", c.getString());
    }

    /** Plans a route for any owner (console, command blocks): {@code surface} ends get their y from the terrain. */
    public void planFor(final UUID owner, final String name, final ServerLevel level, final BlockPos a, final boolean surfA,
                        final BlockPos b, final boolean surfB) {
        final Draft d = drafts.computeIfAbsent(owner, k -> new Draft());
        d.dim = level.dimension();
        d.start = a;
        d.startSurface = surfA;
        d.end = b;
        d.endSurface = surfB;
        d.planning = false;
        setDirty();
        plan(owner, name);
    }

    /** Stops the plan running for {@code owner}; any preview from before it stays. False if none was running. */
    public boolean cancelPlan(final UUID owner) {
        final Draft d = drafts.get(owner);
        if (d == null || !d.planning)
            return false;
        if (d.progress != null)
            d.progress.cancel.set(true);
        d.progress = null;
        d.planning = false;
        d.message = Component.translatable("longhaultracks.msg.plan_cancelled").getString();
        syncDraft(owner);
        return true;
    }

    /** Whether a plan is running for {@code owner}. */
    public boolean planning(final UUID owner) {
        final Draft d = drafts.get(owner);
        return d != null && d.planning;
    }

    /** Sets the light and structure style an owner's next plans use (console owners, tests). */
    public void style(final UUID owner, final Style.Light light, final Style.Mode mode) {
        final Draft d = drafts.computeIfAbsent(owner, k -> new Draft());
        d.light = light;
        d.mode = mode;
        setDirty();
    }

    /** Operator controls without a player: confirm a preview or fill a job's materials. */
    public boolean admin(final UUID id, final Net.Action action) {
        final Job j = jobs.get(id);
        if (j == null)
            return false;
        if (action == Net.Action.CONFIRM && j.state == Job.State.PREVIEW) {
            j.cursor = 0;
            j.surveyCounts.clear();
            j.surveyUnits = 0;
            j.state = Config.SURVEY_BEFORE_BUILD.get() ? Job.State.SURVEYING : Job.State.AWAITING_MATERIALS;
            j.message = Component.translatable(j.state == Job.State.SURVEYING ? "longhaultracks.msg.surveying"
                : "longhaultracks.msg.awaiting").getString();
            final Draft d = drafts.get(j.owner);
            if (d != null && j.id.equals(d.preview))
                d.preview = null;
        } else if (action == Net.Action.FILL) {
            for (final String k : j.required.keySet())
                j.supplied.put(k, Math.max(j.supplied.getOrDefault(k, 0L), j.required.get(k)));
            j.supplied.put("longhaultracks:free", 1L);
        } else if (action == Net.Action.CANCEL) {
            refund(j.owner, j.supplied);
            remove(j);
            return true;
        } else
            return false;
        afterSupply(j);
        return true;
    }

    // ------------------------------------------------------------------------------------------- actions

    public void action(final ServerPlayer player, final UUID id, final Net.Action action, final String arg) {
        final Draft d = draft(player);
        final Job j = id != null ? jobs.get(id) : (d.preview != null ? jobs.get(d.preview) : null);
        switch (action) {
            case SET_BRIDGE, SET_LINING -> {
                final ResourceLocation rl = ResourceLocation.tryParse(arg);
                if (rl == null || !validStructureBlock(BuiltInRegistries.BLOCK.get(rl))) {
                    player.displayClientMessage(Component.translatable("longhaultracks.msg.bad_block", arg), true);
                    return;
                }
                if (action == Net.Action.SET_BRIDGE)
                    d.bridge = rl;
                else
                    d.lining = rl;
                setDirty();
                syncDraft(player.getUUID());
                if (j != null && j.state == Job.State.PREVIEW && d.start != null && d.end != null)
                    plan(player);
            }
            case SET_LIGHT, SET_MODE -> {
                if (action == Net.Action.SET_LIGHT)
                    d.light = Style.Light.parse(arg);
                else
                    d.mode = Style.Mode.parse(arg);
                setDirty();
                syncDraft(player.getUUID());
                if (j != null && j.state == Job.State.PREVIEW && d.start != null && d.end != null)
                    plan(player);
            }
            case SET_TRACK -> {
                final ResourceLocation rl = ResourceLocation.tryParse(arg);
                if (rl != null && TrackMaterial.ALL.containsKey(rl)) {
                    d.track = rl;
                    setDirty();
                    syncDraft(player.getUUID());
                    if (j != null && j.state == Job.State.PREVIEW && d.start != null && d.end != null)
                        plan(player);
                }
            }
            case CLEAR_POINTS -> {
                d.start = d.end = null;
                d.message = "";
                if (j != null && j.state == Job.State.PREVIEW)
                    remove(j);
                d.preview = null;
                setDirty();
                syncDraft(player.getUUID());
            }
            case REPLAN -> plan(player);
            case CANCEL_PLAN -> {
                if (cancelPlan(player.getUUID()))
                    player.displayClientMessage(Component.translatable("longhaultracks.msg.plan_cancelled"), true);
            }
            case CONFIRM -> {
                if (j == null || j.state != Job.State.PREVIEW || !canEdit(player, j))
                    return;
                j.cursor = 0;
                j.surveyCounts.clear();
                j.surveyUnits = 0;
                if (Config.SURVEY_BEFORE_BUILD.get()) {
                    j.state = Job.State.SURVEYING;
                    j.message = Component.translatable("longhaultracks.msg.surveying").getString();
                } else {
                    j.state = Job.State.AWAITING_MATERIALS;
                    j.message = Component.translatable("longhaultracks.msg.awaiting").getString();
                }
                if (d.preview != null && d.preview.equals(j.id)) {
                    d.preview = null;
                    d.start = d.end = null;
                }
                setDirty();
                syncDraft(player.getUUID());
                sendStatus(j);
            }
            case CANCEL -> {
                if (j == null || !canEdit(player, j))
                    return;
                refund(j.owner, j.supplied);
                if (d.preview != null && d.preview.equals(j.id))
                    d.preview = null;
                remove(j);
                giveRefunds(player);
            }
            case DEPOSIT -> {
                if (j == null || j.state == Job.State.COMPLETE)
                    return;
                final long moved = deposit(player, j);
                player.displayClientMessage(Component.translatable("longhaultracks.msg.deposited", moved), true);
                afterSupply(j);
            }
            case FILL -> {
                if (j == null || !(player.isCreative() && Config.CREATIVE_IS_FREE.get() || player.hasPermissions(2)))
                    return;
                for (final String k : j.required.keySet())
                    j.supplied.put(k, Math.max(j.supplied.getOrDefault(k, 0L), j.required.get(k)));
                j.supplied.put("longhaultracks:free", 1L);
                afterSupply(j);
            }
        }
    }

    private void afterSupply(final Job j) {
        if (j.state == Job.State.AWAITING_MATERIALS && j.fullySupplied() || j.state == Job.State.NEEDS_MATERIALS && j.fullySupplied()) {
            j.state = Job.State.BUILDING;
            j.message = Component.translatable("longhaultracks.msg.building").getString();
        }
        setDirty();
        sendStatus(j);
    }

    static boolean canEdit(final ServerPlayer p, final Job j) {
        return p.getUUID().equals(j.owner) || p.hasPermissions(2);
    }

    /** Full, solid, no block entity, does not fall, not banned from Create's track paving. */
    public static boolean validStructureBlock(final Block b) {
        if (b == null || b == Blocks.AIR || b instanceof EntityBlock || b instanceof FallingBlock || b instanceof ITrackBlock)
            return false;
        final BlockState s = b.defaultBlockState();
        if (!s.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
            return false;
        return !AllTags.AllItemTags.INVALID_FOR_TRACK_PAVING.matches(new ItemStack(b.asItem()));
    }

    /** Moves what the job still lacks out of the player's inventory, including the contents of shulker boxes. */
    private long deposit(final ServerPlayer player, final Job j) {
        long moved = 0;
        for (final String key : new ArrayList<>(j.required.keySet())) {
            long need = j.missing(key);
            if (need <= 0)
                continue;
            final Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(key));
            final var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
                final ItemStack st = inv.getItem(i);
                if (st.is(item)) {
                    final int take = (int) Math.min(need, st.getCount());
                    st.shrink(take);
                    need -= take;
                    moved += take;
                    j.supplied.merge(key, (long) take, Long::sum);
                    continue;
                }
                final ItemContainerContents box = st.get(DataComponents.CONTAINER);
                if (box == null)
                    continue;
                final List<ItemStack> contents = new ArrayList<>();
                box.stream().forEach(x -> contents.add(x.copy()));
                boolean changed = false;
                for (final ItemStack x : contents) {
                    if (need <= 0 || !x.is(item))
                        continue;
                    final int take = (int) Math.min(need, x.getCount());
                    x.shrink(take);
                    need -= take;
                    moved += take;
                    changed = true;
                    j.supplied.merge(key, (long) take, Long::sum);
                }
                if (changed)
                    st.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            }
        }
        player.getInventory().setChanged();
        return moved;
    }

    private void refund(final UUID player, final Map<String, Long> items) {
        final Map<String, Long> r = refunds.computeIfAbsent(player, k -> new LinkedHashMap<>());
        items.forEach((k, v) -> {
            if (v > 0 && !k.equals("longhaultracks:free"))
                r.merge(k, v, Long::sum);
        });
        setDirty();
    }

    /** Hands leftover materials back: into the inventory, the rest dropped at the player's feet. */
    public void giveRefunds(final ServerPlayer player) {
        final Map<String, Long> r = refunds.remove(player.getUUID());
        if (r == null || r.isEmpty())
            return;
        if (r.values().stream().mapToLong(Long::longValue).sum() > 64L * 36 * 4) {
            // far more than an inventory: keep it queued and hand out a few stacks at a time on each call
            refunds.put(player.getUUID(), r);
        }
        final Map<String, Long> left = new LinkedHashMap<>();
        int stacks = 0;
        for (final var e : r.entrySet()) {
            final Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getKey()));
            long n = e.getValue();
            while (n > 0 && stacks < 64) {
                final int c = (int) Math.min(n, item.getDefaultMaxStackSize());
                final ItemStack st = new ItemStack(item, c);
                if (!player.getInventory().add(st))
                    player.drop(st, false);
                n -= c;
                stacks++;
            }
            if (n > 0)
                left.put(e.getKey(), n);
        }
        if (left.isEmpty())
            refunds.remove(player.getUUID());
        else
            refunds.put(player.getUUID(), left);
        setDirty();
    }

    private void remove(final Job j) {
        final Builder b = builders.remove(j.id);
        if (b != null)
            b.releaseAll();
        jobs.remove(j.id);
        setDirty();
        PacketDistributor.sendToAllPlayers(new Net.Remove(j.id));
    }

    // ---------------------------------------------------------------------------------------------- tick

    private final transient List<Object[]> pendingRelease = new ArrayList<>();

    public void tick() {
        tick(false);
    }

    /** @param gamePaused single player is paused (planner open): the whole tick is free for our work */
    public void tick(final boolean gamePaused) {
        tick++;
        pendingRelease.removeIf(o -> {
            if ((int) o[1] > tick)
                return false;
            ((Builder) o[0]).releaseAll();
            return true;
        });
        updatePause();
        final boolean fast = pausedByUs || gamePaused;
        final long deadline = System.nanoTime() + (fast ? Config.PAUSED_MILLIS_PER_TICK.get() : Config.MILLIS_PER_TICK.get()) * 1_000_000L;
        int chunks = fast ? Config.PAUSED_CHUNKS_PER_TICK.get() : Config.CHUNKS_PER_TICK.get();
        if (tick % 10 == 0)
            drafts.forEach((owner, d) -> {
                if (d.planning)
                    syncDraft(owner);
            });
        for (final Job j : new ArrayList<>(jobs.values())) {
            if (j.state != Job.State.SURVEYING && j.state != Job.State.BUILDING)
                continue;
            final ServerLevel level = server.getLevel(j.dim);
            if (level == null)
                continue;
            final Builder b = builders.computeIfAbsent(j.id, k -> new Builder(level, j));
            while (chunks > 0 && System.nanoTime() < deadline && (j.state == Job.State.SURVEYING || j.state == Job.State.BUILDING)) {
                if (!step(level, j, b))
                    break;
                chunks--;
            }
            if (tick - j.lastSync >= 10) {
                j.lastSync = tick;
                j.measureRate();
                sendStatus(j);
            }
        }
    }

    // ----------------------------------------------------------------------------------- pause while planning

    /** Players with the planner screen open. */
    private final transient java.util.Set<UUID> plannerOpen = new java.util.HashSet<>();
    /** True while the world is frozen because a planner is open (so we only ever unfreeze our own freeze). */
    private transient boolean pausedByUs;

    public void plannerOpen(final ServerPlayer p, final boolean open) {
        if (open)
            plannerOpen.add(p.getUUID());
        else
            plannerOpen.remove(p.getUUID());
        updatePause();
    }

    public void onLogout(final ServerPlayer p) {
        plannerOpen.remove(p.getUUID());
        updatePause();
    }

    /**
     * Freezes the world (like {@code /tick freeze}: no mobs, redstone, crops or block ticks; chunk loading and
     * our building carry on) while a planner is open, and gives planning and building the freed tick time. Only
     * when it affects nobody else: single player, a LAN world with just the host online, or a server that allows
     * it in the config.
     */
    private void updatePause() {
        plannerOpen.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        // single player (not opened to LAN) pauses for real on the client (PlannerScreen.isPauseScreen); the tick
        // freeze is for a LAN world with only the host online, and for servers that allow it
        final boolean allowed = Config.PAUSE_WHILE_PLANNING.get() && (!server.isDedicatedServer() && server.isPublished()
            && server.getPlayerCount() <= 1 || server.isDedicatedServer() && Config.PAUSE_ON_SERVERS.get());
        final boolean want = allowed && !plannerOpen.isEmpty();
        final var trm = server.tickRateManager();
        if (want && !pausedByUs) {
            if (trm.isFrozen())
                return; // already frozen by someone else: leave it alone
            trm.setFrozen(true);
            pausedByUs = true;
        } else if (!want && pausedByUs) {
            pausedByUs = false;
            if (trm.isFrozen())
                trm.setFrozen(false);
        }
    }

    /** @return true when a chunk was processed (so another may follow this tick) */
    private boolean step(final ServerLevel level, final Job j, final Builder b) {
        final int n = j.chunkCount();
        if (j.cursor >= n) {
            finishPhase(level, j, b);
            return false;
        }
        b.ticketWindow(tick);
        final ChunkPos pos = j.corridor.chunks.get(j.cursor).pos;
        if (!b.ready(pos)) {
            // never load synchronously here: on slow world generation (JJThunder) that froze the whole game; the
            // chunks ahead are ticketed and generate in parallel, so just wait for them
            final int w = waits.merge(j.id, 1, Integer::sum);
            if (w == 20 || w % 200 == 0) {
                j.message = Component.translatable("longhaultracks.msg.generating", b.loadedAhead(), Config.PREFETCH_CHUNKS.get()).getString();
                if (w % 1200 == 0)
                    com.mojang.logging.LogUtils.getLogger().info("[Long Haul Tracks] still waiting for chunk {} of route {} ({} s)",
                        pos, j.id, w / 20);
            }
            return false;
        }
        if (waits.remove(j.id) != null)
            j.message = Component.translatable(j.state == Job.State.SURVEYING ? "longhaultracks.msg.surveying"
                : "longhaultracks.msg.building").getString();
        if (j.state == Job.State.SURVEYING) {
            b.survey(j.cursor, j.surveyUnits).forEach((k, v) -> j.surveyCounts.merge(k, v, Long::sum));
            j.surveyUnits += b.lastUnits;
            j.cursor++;
            setDirty();
            return true;
        }
        final Map<String, Long> need = b.survey(j.cursor, j.trackUnits);
        final boolean free = j.supplied.containsKey("longhaultracks:free");
        boolean short_ = false;
        for (final var e : need.entrySet()) {
            final long have = j.supplied.getOrDefault(e.getKey(), 0L);
            if (have < e.getValue()) {
                if (free) {
                    j.supplied.put(e.getKey(), e.getValue());
                    continue;
                }
                short_ = true;
                j.required.merge(e.getKey(), e.getValue() - have, (a, c) -> Math.max(a, have + c));
            }
        }
        if (short_) {
            j.state = Job.State.NEEDS_MATERIALS;
            j.message = Component.translatable("longhaultracks.msg.needs_more").getString();
            sendStatus(j);
            setDirty();
            return false;
        }
        j.trackUnits += b.build(j.cursor, j.trackUnits, (item, c) -> {
            j.supplied.merge(item, -c, Long::sum);
            j.required.merge(item, -c, (a, x) -> Math.max(0, a + x));
        });
        b.finished(j.cursor, tick);
        j.cursor++;
        setDirty();
        return true;
    }

    private void finishPhase(final ServerLevel level, final Job j, final Builder b) {
        if (j.state == Job.State.SURVEYING) {
            j.required = new LinkedHashMap<>(j.surveyCounts);
            j.required.values().removeIf(v -> v <= 0);
            j.exact = true;
            j.cursor = 0;
            b.releaseAll();
            j.state = j.fullySupplied() ? Job.State.BUILDING : Job.State.AWAITING_MATERIALS;
            j.message = Component.translatable(j.state == Job.State.BUILDING ? "longhaultracks.msg.building"
                : "longhaultracks.msg.awaiting").getString();
            notifyOwner(j, Component.translatable("longhaultracks.msg.survey_done", j.length).withStyle(ChatFormatting.AQUA));
        } else {
            j.state = Job.State.COMPLETE;
            j.message = Component.translatable("longhaultracks.msg.complete").getString();
            refund(j.owner, j.supplied);
            builders.remove(j.id);
            // keep the last window ticketed a little longer so Create's graph settles, then release
            pendingRelease.add(new Object[] {b, tick + 100});
            notifyOwner(j, Component.translatable("longhaultracks.msg.complete_chat", j.length, j.start.toShortString(),
                j.end.toShortString()).withStyle(ChatFormatting.GREEN));
            final ServerPlayer owner = server.getPlayerList().getPlayer(j.owner);
            if (owner != null)
                giveRefunds(owner);
            jobs.remove(j.id);
            PacketDistributor.sendToAllPlayers(new Net.Remove(j.id));
        }
        setDirty();
        sendStatus(j);
    }

    private void notifyOwner(final Job j, final Component c) {
        final ServerPlayer p = server.getPlayerList().getPlayer(j.owner);
        if (p != null)
            p.sendSystemMessage(c);
    }

    // ---------------------------------------------------------------------------------------------- sync

    public void onLogin(final ServerPlayer player) {
        for (final Job j : jobs.values()) {
            sendGeometry(j, player);
            PacketDistributor.sendToPlayer(player, status(j));
        }
        final Draft d = drafts.get(player.getUUID());
        if (d != null) {
            d.planning = false;
            syncDraft(player.getUUID());
        }
        giveRefunds(player);
    }

    void syncDraft(final UUID owner) {
        final ServerPlayer player = server.getPlayerList().getPlayer(owner);
        final Draft d = drafts.get(owner);
        if (player == null || d == null)
            return;
        PacketDistributor.sendToPlayer(player, new Net.Draft(d.dim.location().toString(), d.start, d.end,
            d.bridge.toString(), d.lining.toString(), d.track.toString(), d.light.key(), d.mode.key(),
            d.message == null ? "" : d.message, d.planning, d.planning && d.progress != null ? d.progress.fraction() : 0,
            d.planning && d.progress != null ? d.progress.etaSeconds() : -1));
    }

    private void sendGeometry(final Job j, final ServerPlayer only) {
        final Corridor c = j.corridor();
        final int n = c.centerline.size();
        final int[] xyz = new int[n * 3];
        final byte[] kinds = new byte[n];
        for (int i = 0; i < n; i++) {
            final Corridor.Point p = c.centerline.get(i);
            xyz[i * 3] = (int) Math.round(p.x() * 8);
            xyz[i * 3 + 1] = (int) Math.round(p.y() * 8);
            xyz[i * 3 + 2] = (int) Math.round(p.z() * 8);
            kinds[i] = p.kind();
        }
        final Net.Geometry g = new Net.Geometry(j.id, j.dim.location().toString(), xyz, kinds);
        if (only != null)
            PacketDistributor.sendToPlayer(only, g);
        else
            PacketDistributor.sendToAllPlayers(g);
    }

    void sendStatus(final Job j) {
        PacketDistributor.sendToAllPlayers(status(j));
    }

    private Net.Status status(final Job j) {
        final List<Net.MatLine> mats = new ArrayList<>();
        final Map<String, Long> base = j.state == Job.State.SURVEYING ? j.required : j.required;
        for (final var e : base.entrySet())
            mats.add(new Net.MatLine(e.getKey(), e.getValue(), j.supplied.getOrDefault(e.getKey(), 0L)));
        final int n = j.chunkCount();
        final float progress = n == 0 ? 0 : j.cursor / (float) n;
        return new Net.Status(j.id, j.owner, j.ownerName, j.dim.location().toString(), j.state.ordinal(), j.message,
            progress, j.start, j.end, j.startJunction, j.endJunction, j.length, j.bridgeLength, j.tunnelLength,
            j.bridgeBlock.toString(), j.liningBlock.toString(), j.material().id.toString(), j.light.key(), j.mode.key(),
            j.exact, mats, j.etaSeconds());
    }

    /** Releases every ticket on shutdown; jobs re-ticket their window when ticking resumes. */
    public void shutdown() {
        builders.values().forEach(Builder::releaseAll);
        builders.clear();
        plannerOpen.clear();
        if (pausedByUs && server.tickRateManager().isFrozen())
            server.tickRateManager().setFrozen(false);
        pausedByUs = false;
        pendingRelease.forEach(o -> ((Builder) o[0]).releaseAll());
        pendingRelease.clear();
    }

    /** True if {@code item} is a block item usable as bridge or lining material. */
    public static boolean validStructureItem(final ItemStack st) {
        return st.getItem() instanceof BlockItem bi && validStructureBlock(bi.getBlock());
    }
}
