package dev.soulvoid.longhaul.job;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.route.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surveys and builds a route one chunk at a time. Chunks are loaded with a region ticket (generated if they never
 * existed), so a route works no matter where players are; a short window of finished chunks stays ticketed so
 * Create's track graph can propagate across chunk borders before they unload.
 */
public final class Builder {

    public static final TicketType<ChunkPos> TICKET =
        TicketType.create("longhaultracks_route", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_RADIUS = 2;
    private static final int KEEP_BEHIND = 6;
    private static final int PREFETCH = 3;

    /** Counts materials; in build mode also checks and takes them. */
    interface Ledger {
        void add(String item, long n);
    }

    private final ServerLevel level;
    private final Job job;
    private final Corridor corridor;
    private final String bridgeItem, liningItem, trackItem;
    private final BlockState bridgeState, liningState;

    Builder(final ServerLevel level, final Job job) {
        this.level = level;
        this.job = job;
        this.corridor = job.corridor(Config.PILLAR_SPACING.get());
        final Block bridge = BuiltInRegistries.BLOCK.get(job.bridgeBlock);
        final Block lining = BuiltInRegistries.BLOCK.get(job.liningBlock);
        this.bridgeState = (bridge == Blocks.AIR ? Blocks.STONE_BRICKS : bridge).defaultBlockState();
        this.liningState = (lining == Blocks.AIR ? bridgeState.getBlock() : lining).defaultBlockState();
        this.bridgeItem = key(bridgeState.getBlock());
        this.liningItem = key(liningState.getBlock());
        this.trackItem = key(job.material().getBlock());
    }

    static String key(final Block b) {
        return BuiltInRegistries.ITEM.getKey(b.asItem()).toString();
    }

    // ------------------------------------------------------------------------------------------ tickets

    void ticketWindow() {
        final int n = corridor.chunks.size();
        for (int i = Math.max(0, job.cursor - KEEP_BEHIND); i < Math.min(n, job.cursor + PREFETCH); i++) {
            final ChunkPos p = corridor.chunks.get(i).pos;
            level.getChunkSource().addRegionTicket(TICKET, p, TICKET_RADIUS, p, true);
        }
        final int drop = job.cursor - KEEP_BEHIND - 1;
        if (drop >= 0 && drop < n) {
            final ChunkPos p = corridor.chunks.get(drop).pos;
            level.getChunkSource().removeRegionTicket(TICKET, p, TICKET_RADIUS, p);
        }
    }

    void releaseAll() {
        for (final Corridor.ChunkWork w : corridor.chunks)
            level.getChunkSource().removeRegionTicket(TICKET, w.pos, TICKET_RADIUS, w.pos);
    }

    boolean ready(final ChunkPos p) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (level.getChunkSource().getChunkNow(p.x + dx, p.z + dz) == null)
                    return false;
        return true;
    }

    // --------------------------------------------------------------------------------------- one chunk

    /** Counts what the chunk at {@code idx} still needs. */
    Map<String, Long> survey(final int idx) {
        final Map<String, Long> m = new LinkedHashMap<>();
        run(corridor.chunks.get(idx), true, (item, n) -> m.merge(item, n, Long::sum));
        return m;
    }

    void build(final int idx, final Ledger take) {
        run(corridor.chunks.get(idx), false, take);
    }

    private void run(final Corridor.ChunkWork w, final boolean simulate, final Ledger ledger) {
        final int h = Config.TUNNEL_HEIGHT.get();
        final int threshold = Config.BRIDGE_THRESHOLD.get();
        final int pillarDepth = JobManager.pillarDepth(level);
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // 1. clear the corridor
        if (!simulate)
            for (final Corridor.Cell c : w.cells)
                if (c.dist <= Corridor.CLEAR_R)
                    for (int y = c.layer; y < c.layer + h; y++)
                        clear(p.set(c.x, y, c.z));
        for (final Corridor.Cell c : w.cells) {
            final boolean tunnel = c.kind == Piece.TUNNEL;
            // 2. ceiling and walls: seal fluids, falling blocks, and cave openings inside tunnels
            if (c.dist <= Corridor.CLEAR_R) {
                p.set(c.x, c.layer + h, c.z);
                if (needsSeal(p, tunnel, true))
                    place(p, liningState, liningItem, simulate, ledger);
            } else {
                for (int y = c.layer; y < c.layer + h; y++)
                    if (needsSeal(p.set(c.x, y, c.z), tunnel, false))
                        place(p, liningState, liningItem, simulate, ledger);
                continue;
            }
            // 3. bed / deck / embankment
            if (c.dist <= Corridor.DECK_R) {
                p.set(c.x, c.layer - 1, c.z);
                if (!sturdy(p))
                    place(p, c.kind == Piece.TUNNEL ? liningState : bridgeState,
                        c.kind == Piece.TUNNEL ? liningItem : bridgeItem, simulate, ledger);
                if (c.kind == Piece.GROUND)
                    for (int d = 2; d <= threshold + 1; d++) {
                        p.set(c.x, c.layer - d, c.z);
                        if (sturdy(p))
                            break;
                        place(p, bridgeState, bridgeItem, simulate, ledger);
                    }
            }
            // 4. pillars under bridges
            if (c.pillar && c.kind == Piece.BRIDGE)
                for (int d = 2; d <= pillarDepth + 1; d++) {
                    p.set(c.x, c.layer - d, c.z);
                    if (p.getY() < level.getMinBuildHeight() || sturdy(p))
                        break;
                    place(p, bridgeState, bridgeItem, simulate, ledger);
                }
        }
        // 5. straight track
        for (final Corridor.TrackBlock t : w.tracks)
            placeTrack(t, simulate, ledger);
        // 6. curves (turns, S-bends and slopes)
        for (final Piece.Curve cv : w.curves)
            placeCurve(cv, simulate, ledger);
    }

    private void clear(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        if (s.isAir() || s.getBlock() instanceof ITrackBlock || AllBlocks.FAKE_TRACK.has(s)
            || s.getDestroySpeed(level, p) < 0)
            return;
        if (s.hasBlockEntity())
            level.destroyBlock(p, true);
        else
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private boolean needsSeal(final BlockPos p, final boolean tunnel, final boolean ceiling) {
        final BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof ITrackBlock || AllBlocks.FAKE_TRACK.has(s))
            return false;
        if (s.getBlock() instanceof LiquidBlock)
            return true;
        if (ceiling && s.getBlock() instanceof FallingBlock)
            return true;
        return tunnel && (s.isAir() || s.canBeReplaced());
    }

    private boolean sturdy(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() && !s.isCollisionShapeFullBlock(level, p))
            return false;
        if (s.canBeReplaced() || s.is(BlockTags.LEAVES))
            return false;
        return s.isCollisionShapeFullBlock(level, p) || s.getBlock() instanceof ITrackBlock;
    }

    private void place(final BlockPos p, final BlockState state, final String item, final boolean simulate,
                       final Ledger ledger) {
        if (level.getBlockState(p).getDestroySpeed(level, p) < 0)
            return;
        ledger.add(item, 1);
        if (!simulate)
            level.setBlock(p, state, Block.UPDATE_ALL);
    }

    private void placeTrack(final Corridor.TrackBlock t, final boolean simulate, final Ledger ledger) {
        final BlockState want = job.material().getBlock().defaultBlockState()
            .setValue(TrackBlock.SHAPE, t.dir().shape());
        final BlockState existing = level.getBlockState(t.pos());
        if (existing.getBlock() instanceof ITrackBlock itb) {
            final BlockState merged = itb.overlay(level, t.pos(), existing, want);
            if (!simulate && merged != existing)
                level.setBlock(t.pos(), merged, Block.UPDATE_ALL);
            return;
        }
        ledger.add(trackItem, 1);
        if (!simulate)
            level.setBlock(t.pos(), ProperWaterloggedBlock.withWater(level, want, t.pos()), Block.UPDATE_ALL);
    }

    private void placeCurve(final Piece.Curve cv, final boolean simulate, final Ledger ledger) {
        final BezierConnection bc = cv.toBezier(job.material());
        if (simulate) {
            if (!(level.getBlockEntity(cv.a()) instanceof TrackBlockEntity te && te.getConnections().containsKey(cv.b())))
                ledger.add(trackItem, bc.getTrackItemCost());
            return;
        }
        level.getChunk(cv.a());
        level.getChunk(cv.b());
        if (!ensureTurnBlock(cv.a()) || !ensureTurnBlock(cv.b()))
            return;
        if (!(level.getBlockEntity(cv.a()) instanceof TrackBlockEntity te1)
            || !(level.getBlockEntity(cv.b()) instanceof TrackBlockEntity te2))
            return;
        if (te1.getConnections().containsKey(cv.b()))
            return;
        ledger.add(trackItem, bc.getTrackItemCost());
        te1.addConnection(bc);
        te2.addConnection(bc.secondary());
        te1.tilt.tryApplySmoothing();
        te2.tilt.tryApplySmoothing();
    }

    /** The end of a curve must be a track block with a block entity (Create's "turn" property). */
    private boolean ensureTurnBlock(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        if (!(s.getBlock() instanceof TrackBlock))
            return false;
        if (!s.getValue(TrackBlock.HAS_BE))
            level.setBlock(p, s.setValue(TrackBlock.HAS_BE, true), Block.UPDATE_ALL);
        return true;
    }

    static ResourceLocation id(final Block b) {
        return BuiltInRegistries.BLOCK.getKey(b);
    }
}
