package dev.soulvoid.longhaul.job;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.route.Piece;
import dev.soulvoid.longhaul.route.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LanternBlock;
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
    private final String floorItem, wallItem, trackItem;
    private final BlockState floorState, wallState;
    private final Style.Mode mode;
    private final Style.Light light;
    /** Centerline point -> whether the route is enclosed by terrain there (inside a mountain). */
    private final Map<Integer, Boolean> enclosed = new HashMap<>();
    /** Track length counted by the last {@link #run}, in Create's track item units. */
    private long units;
    long lastUnits;

    Builder(final ServerLevel level, final Job job) {
        this.level = level;
        this.job = job;
        this.corridor = job.corridor();
        final Block floor = BuiltInRegistries.BLOCK.get(job.bridgeBlock);
        final Block wall = BuiltInRegistries.BLOCK.get(job.liningBlock);
        this.floorState = (floor == Blocks.AIR ? Blocks.STONE_BRICKS : floor).defaultBlockState();
        this.wallState = (wall == Blocks.AIR ? floorState.getBlock() : wall).defaultBlockState();
        this.floorItem = key(floorState.getBlock());
        this.wallItem = key(wallState.getBlock());
        this.trackItem = key(job.material().getBlock());
        this.mode = job.mode;
        this.light = job.light;
    }

    /** Track items owed for {@code u} more units after {@code base} units: one item per started block allowance. */
    static long trackItems(final long base, final long u) {
        final long n = Config.BLOCKS_PER_TRACK.get();
        return ceilDiv(base + u, n) - ceilDiv(base, n);
    }

    private static long ceilDiv(final long a, final long b) {
        return (a + b - 1) / b;
    }

    static String key(final Block b) {
        return BuiltInRegistries.ITEM.getKey(b.asItem()).toString();
    }

    // ------------------------------------------------------------------------------------------ tickets

    /** Chunk index -> server tick it was finished; kept ticketed for a while so Create's graph can settle. */
    private final java.util.ArrayDeque<int[]> done = new java.util.ArrayDeque<>();
    private static final int SETTLE_TICKS = 80;

    void ticketWindow(final int now) {
        final int n = corridor.chunks.size();
        for (int i = job.cursor; i < Math.min(n, job.cursor + PREFETCH); i++)
            ticket(corridor.chunks.get(i).pos);
        while (done.size() > KEEP_BEHIND || !done.isEmpty() && now - done.peekFirst()[1] >= SETTLE_TICKS) {
            final int[] d = done.peekFirst();
            if (now - d[1] < SETTLE_TICKS)
                break;
            done.pollFirst();
            if (d[0] < job.cursor || d[0] >= job.cursor + PREFETCH)
                unticket(corridor.chunks.get(d[0]).pos);
        }
    }

    void finished(final int idx, final int now) {
        done.addLast(new int[] {idx, now});
    }

    private void ticket(final ChunkPos p) {
        level.getChunkSource().addRegionTicket(TICKET, p, TICKET_RADIUS, p, true);
    }

    private void unticket(final ChunkPos p) {
        level.getChunkSource().removeRegionTicket(TICKET, p, TICKET_RADIUS, p, true);
    }

    void releaseAll() {
        done.clear();
        for (final Corridor.ChunkWork w : corridor.chunks)
            unticket(w.pos);
    }

    boolean ready(final ChunkPos p) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (level.getChunkSource().getChunkNow(p.x + dx, p.z + dz) == null)
                    return false;
        return true;
    }

    // --------------------------------------------------------------------------------------- one chunk

    /**
     * Counts what the chunk at {@code idx} still needs, with {@code baseUnits} of track already accounted for
     * before it. The chunk's own track length is left in {@link #lastUnits}.
     */
    Map<String, Long> survey(final int idx, final long baseUnits) {
        final Map<String, Long> m = new LinkedHashMap<>();
        units = 0;
        run(corridor.chunks.get(idx), true, (item, n) -> m.merge(item, n, Long::sum));
        lastUnits = units;
        final long t = trackItems(baseUnits, units);
        if (t > 0)
            m.merge(trackItem, t, Long::sum);
        return m;
    }

    /** Builds the chunk at {@code idx}; returns the track units laid. */
    long build(final int idx, final long baseUnits, final Ledger take) {
        units = 0;
        run(corridor.chunks.get(idx), false, take);
        final long t = trackItems(baseUnits, units);
        if (t > 0)
            take.add(trackItem, t);
        return units;
    }

    private void run(final Corridor.ChunkWork w, final boolean simulate, final Ledger ledger) {
        final int h = Config.TUNNEL_HEIGHT.get();
        final int threshold = Config.BRIDGE_THRESHOLD.get();
        final int pillarDepth = JobManager.pillarDepth(level);
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // decide enclosure on the untouched terrain, before this chunk is cleared
        if (mode.tunnels)
            for (final Corridor.Cell c : w.cells)
                if (c.kind == Piece.TUNNEL)
                    enclosed(c.point);
        // 1. clear the corridor
        if (!simulate)
            for (final Corridor.Cell c : w.cells)
                if (c.inside)
                    for (int y = c.layer; y < c.layer + h; y++)
                        clear(p.set(c.x, y, c.z));
        for (final Corridor.Cell c : w.cells) {
            // 2. tunnel ceiling and walls: only where the route is inside the terrain on top and both sides; fluids
            //    and falling blocks are kept out everywhere tunnels are built
            final boolean lining = mode.tunnels && c.kind == Piece.TUNNEL && enclosed(c.point);
            if (c.inside) {
                p.set(c.x, c.layer + h, c.z);
                if (mode.tunnels && needsSeal(p, lining, true))
                    place(p, wallState, wallItem, simulate, ledger);
            } else {
                if (mode.tunnels)
                    for (int y = c.layer; y < c.layer + h; y++)
                        if (needsSeal(p.set(c.x, y, c.z), lining, false))
                            place(p, wallState, wallItem, simulate, ledger);
                continue;
            }
            // 3. deck / track bed / embankment
            if (c.dist <= Corridor.DECK_R) {
                final boolean bridge = c.kind == Piece.BRIDGE;
                if (bridge ? mode.bridges : mode.beds()) {
                    p.set(c.x, c.layer - 1, c.z);
                    if (!sturdy(p))
                        place(p, floorState, floorItem, simulate, ledger);
                }
                if (c.kind == Piece.GROUND && mode.beds())
                    for (int d = 2; d <= threshold + 1; d++) {
                        p.set(c.x, c.layer - d, c.z);
                        if (sturdy(p))
                            break;
                        place(p, floorState, floorItem, simulate, ledger);
                    }
            }
            // 4. pillars under bridges
            if (c.pillar && c.kind == Piece.BRIDGE && mode.bridges)
                for (int d = 2; d <= pillarDepth + 1; d++) {
                    p.set(c.x, c.layer - d, c.z);
                    if (p.getY() < level.getMinBuildHeight() || sturdy(p))
                        break;
                    place(p, wallState, wallItem, simulate, ledger);
                }
        }
        // 5. straight track
        for (final Corridor.TrackBlock t : w.tracks)
            placeTrack(t, simulate);
        // 6. curves (turns, S-bends and slopes)
        for (final Piece.Curve cv : w.curves)
            placeCurve(cv, simulate);
        // 7. lights
        if (light != Style.Light.NONE)
            for (final int enc : w.lights)
                placeLight(enc < 0 ? -enc - 1 : enc, enc < 0 ? -1 : 1, h, simulate, ledger);
        // 8. register with Create's train graph now instead of waiting for scheduled ticks, which only run in
        //    entity-ticking chunks and could be lost if the chunk unloads first
        if (!simulate) {
            for (final Corridor.TrackBlock t : w.tracks)
                propagate(t.pos());
            for (final Piece.Curve cv : w.curves) {
                propagate(cv.a());
                propagate(cv.b());
            }
        }
    }

    /**
     * Whether the route at centerline point {@code pt} runs inside the terrain: solid rock above the clearance and
     * beyond the wall on both sides. Only then are tunnel walls and ceilings built; cuttings and half-open hillsides
     * are left as they are.
     */
    private boolean enclosed(final int pt) {
        final Boolean known = enclosed.get(pt);
        if (known != null)
            return known;
        final Corridor.Point q = corridor.centerline.get(pt);
        final int h = Config.TUNNEL_HEIGHT.get();
        final int layer = (int) Math.floor(q.y() + 1e-3);
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean top = false;
        for (int y = layer + h; y <= layer + h + 2 && !top; y++)
            top = sturdy(p.set(Mth.floor(q.x()), y, Mth.floor(q.z())));
        boolean result = top;
        final double off = corridor.spec.ringR() + .5;
        for (int side = -1; side <= 1 && result; side += 2) {
            final int x = Mth.floor(q.x() - q.hz() * off * side), z = Mth.floor(q.z() + q.hx() * off * side);
            int solid = 0;
            for (int y = layer + 1; y < layer + h; y++)
                if (sturdy(p.set(x, y, z)))
                    solid++;
            result = solid * 2 >= h - 1;
        }
        enclosed.put(pt, result);
        return result;
    }

    private void placeLight(final int pt, final int side, final int h, final boolean simulate, final Ledger ledger) {
        final Corridor.Point q = corridor.centerline.get(pt);
        final int layer = (int) Math.floor(q.y() + 1e-3);
        final double px = -q.hz() * side, pz = q.hx() * side;
        final Block lamp = switch (light) {
            case TORCH -> Blocks.TORCH;
            case LANTERN -> Blocks.LANTERN;
            case SOUL_LANTERN -> Blocks.SOUL_LANTERN;
            default -> Blocks.AIR;
        };
        if (q.kind() == Piece.TUNNEL) {
            if (!mode.tunnels || !enclosed(pt))
                return;
            if (light == Style.Light.TORCH) {
                final double off = corridor.spec.clearR() - .8;
                final BlockPos at = BlockPos.containing(q.x() + px * off, layer, q.z() + pz * off);
                if (!sturdy(at.below()))
                    place(at.below(), floorState, floorItem, simulate, ledger);
                lamp(at, lamp.defaultBlockState(), simulate, ledger);
            } else {
                final BlockPos at = BlockPos.containing(q.x(), layer + h - 1, q.z());
                if (!sturdy(at.above()))
                    place(at.above(), wallState, wallItem, simulate, ledger);
                lamp(at, lamp.defaultBlockState().setValue(LanternBlock.HANGING, true), simulate, ledger);
            }
        } else if (q.kind() == Piece.BRIDGE) {
            if (!mode.bridges)
                return;
            // a post one block outside the deck, so trains pass clear of it
            final double off = Corridor.DECK_R + 1;
            final BlockPos at = BlockPos.containing(q.x() + px * off, layer, q.z() + pz * off);
            if (!sturdy(at.below()))
                place(at.below(), floorState, floorItem, simulate, ledger);
            lamp(at, lamp.defaultBlockState(), simulate, ledger);
        }
    }

    private void lamp(final BlockPos at, final BlockState state, final boolean simulate, final Ledger ledger) {
        final BlockState s = level.getBlockState(at);
        if (s.is(state.getBlock()) || !(s.isAir() || s.canBeReplaced()))
            return;
        ledger.add(light.item, 1);
        if (!simulate)
            level.setBlock(at, state, Block.UPDATE_ALL);
    }

    private void propagate(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof TrackBlock)
            com.simibubi.create.content.trains.track.TrackPropagator.onRailAdded(level, p, s);
    }

    private void clear(final BlockPos p) {
        final BlockState s = level.getBlockState(p);
        if (s.isAir() || s.getBlock() instanceof ITrackBlock || s.getBlock() instanceof com.simibubi.create.content.trains.track.FakeTrackBlock
            || s.getDestroySpeed(level, p) < 0)
            return;
        if (s.hasBlockEntity())
            level.destroyBlock(p, true);
        else
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private boolean needsSeal(final BlockPos p, final boolean tunnel, final boolean ceiling) {
        final BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof ITrackBlock || s.getBlock() instanceof com.simibubi.create.content.trains.track.FakeTrackBlock)
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

    private void placeTrack(final Corridor.TrackBlock t, final boolean simulate) {
        final BlockState want = job.material().getBlock().defaultBlockState()
            .setValue(TrackBlock.SHAPE, t.dir().shape());
        final BlockState existing = level.getBlockState(t.pos());
        if (existing.getBlock() instanceof ITrackBlock itb) {
            final BlockState merged = itb.overlay(level, t.pos(), existing, want);
            if (!simulate && merged != existing)
                level.setBlock(t.pos(), merged, Block.UPDATE_ALL);
            return;
        }
        units++;
        if (!simulate)
            level.setBlock(t.pos(), ProperWaterloggedBlock.withWater(level, want, t.pos()), Block.UPDATE_ALL);
    }

    private void placeCurve(final Piece.Curve cv, final boolean simulate) {
        final BezierConnection bc = cv.toBezier(job.material());
        if (simulate) {
            if (!(level.getBlockEntity(cv.a()) instanceof TrackBlockEntity te && te.getConnections().containsKey(cv.b())))
                units += bc.getTrackItemCost();
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
        units += bc.getTrackItemCost();
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
