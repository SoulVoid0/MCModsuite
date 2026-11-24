package dev.soulvoid.longhaul.gametest;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;

import dev.soulvoid.longhaul.job.Job;
import dev.soulvoid.longhaul.job.JobManager;
import dev.soulvoid.longhaul.net.Net;
import dev.soulvoid.longhaul.route.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Builds real routes in a headless server: far outside any loaded area (so planning samples world-gen noise and the
 * builder has to ticket-load and generate every chunk), then a second route branching off the first one's middle.
 */
@GameTestHolder("longhaultracks")
@PrefixGameTestTemplate(false)
public final class RouteTests {

    private static final Logger LOG = LogUtils.getLogger();

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 20)
    public static void buildsRouteAndJunction(final GameTestHelper h) {
        final ServerLevel level = h.getLevel();
        final JobManager m = JobManager.get(level.getServer());
        final UUID owner1 = UUID.randomUUID(), owner2 = UUID.randomUUID();
        final BlockPos far = new BlockPos(Integer.getInteger("lht.x", 4000), 0, Integer.getInteger("lht.z", 4000));
        final int dx = Integer.getInteger("lht.dx", 900), dz = Integer.getInteger("lht.dz", 420);
        terrain(level, far);
        m.planFor(owner1, "gametest", level, far, true, far.offset(dx, 0, dz), true);
        final Object[] st = new Object[] {0, null, null, null, 0};
        h.onEachTick(() -> {
            final int stage = (int) st[0];
            switch (stage) {
                case 0, 4 -> {
                    final UUID owner = stage == 0 ? owner1 : owner2;
                    final Job j = find(m, owner);
                    if (j == null) {
                        final String msg = m.draftMessage(owner);
                        if (!msg.isEmpty() && !msg.startsWith("Planning"))
                            h.fail("planning failed: " + msg);
                        return;
                    }
                    LOG.info("[LHT test] planned {} blocks, {} bridge, {} tunnel, {} pieces, estimate {}, start {} end {} junction {}/{}",
                        j.length, j.bridgeLength, j.tunnelLength, j.pieces.size(), j.required, j.start, j.end,
                        j.startJunction, j.endJunction);
                    if (stage == 0)
                        st[1] = j.pieces;
                    st[2] = j.id;
                    m.admin(j.id, Net.Action.CONFIRM);
                    st[0] = stage + 1;
                }
                case 1, 5 -> {
                    final Job j = m.job((UUID) st[2]);
                    if (j == null)
                        h.fail("job vanished during survey");
                    if (j.state == Job.State.AWAITING_MATERIALS || j.state == Job.State.BUILDING) {
                        LOG.info("[LHT test] survey done: exact materials {}", j.required);
                        m.admin(j.id, Net.Action.FILL);
                        st[0] = stage + 1;
                    }
                }
                case 2, 6 -> {
                    final Job j = m.job((UUID) st[2]);
                    if (j != null) {
                        if (j.state == Job.State.NEEDS_MATERIALS)
                            h.fail("builder ran short: " + j.message);
                        return;
                    }
                    st[0] = stage + 1;
                }
                case 3 -> {
                    @SuppressWarnings("unchecked")
                    final List<Piece> pieces = (List<Piece>) st[1];
                    checkTracks(h, level, pieces);
                    logGraphs("after route 1");
                    // branch off the middle of the longest straight
                    Piece.Straight best = null;
                    for (final Piece p : pieces)
                        if (p instanceof Piece.Straight s && (best == null || s.count() > best.count()))
                            best = s;
                    final BlockPos junction = best.at(best.count() / 2);
                    st[3] = junction;
                    LOG.info("[LHT test] branching from {}", junction);
                    m.planFor(owner2, "gametest2", level, junction, false, junction.offset(-250, 0, 380), true);
                    st[0] = 4;
                }
                case 7 -> {
                    final BlockPos junction = (BlockPos) st[3];
                    if (!(level.getBlockEntity(junction) instanceof TrackBlockEntity te) || te.getConnections().isEmpty())
                        h.fail("junction block has no curve connection at " + junction);
                    logGraphs("after junction");
                    st[0] = 8;
                    h.succeed();
                }
                default -> {}
            }
        });
    }

    /**
     * The GameTest world is superflat (surface y=-61), so shape terrain across the route's band: a hill to tunnel
     * through, two hills with a valley between (bridge or cutting), and a lake to bridge.
     */
    private static void terrain(final ServerLevel level, final BlockPos o) {
        final var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        final var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = o.getZ() - 40; z <= o.getZ() + 460; z++) {
            for (int x = o.getX() + 150; x < o.getX() + 250; x++) {
                final int h = 30 - Math.abs(x - (o.getX() + 200)) / 2;
                for (int y = -60; y < -60 + h; y++)
                    level.setBlock(p.set(x, y, z), stone, 2);
            }
            for (final int cx : new int[] {o.getX() + 320, o.getX() + 400})
                for (int x = cx - 20; x < cx + 20; x++)
                    for (int y = -60; y < -36; y++)
                        level.setBlock(p.set(x, y, z), stone, 2);
            for (int x = o.getX() + 520; x < o.getX() + 570; x++)
                for (int y = -63; y <= -61; y++)
                    level.setBlock(p.set(x, y, z), water, 2);
        }
        LOG.info("[LHT test] terrain shaped");
    }

    private static Job find(final JobManager m, final UUID owner) {
        for (final Job j : m.jobs())
            if (j.owner.equals(owner))
                return j;
        return null;
    }

    private static void checkTracks(final GameTestHelper h, final ServerLevel level, final List<Piece> pieces) {
        int missing = 0, total = 0, curves = 0, curvesOk = 0;
        for (final Piece p : pieces) {
            if (p instanceof Piece.Straight s) {
                for (int i = 0; i < s.count(); i++) {
                    total++;
                    if (!(level.getBlockState(s.at(i)).getBlock() instanceof TrackBlock))
                        missing++;
                }
            } else if (p instanceof Piece.Curve c) {
                curves++;
                if (level.getBlockEntity(c.a()) instanceof TrackBlockEntity te && te.getConnections().containsKey(c.b()))
                    curvesOk++;
            }
        }
        LOG.info("[LHT test] track blocks {}/{} present, curves {}/{} connected", total - missing, total, curvesOk, curves);
        // one connected train graph from end to end
        java.util.Set<java.util.UUID> graphs = new java.util.LinkedHashSet<>();
        for (final Piece p : pieces)
            if (p instanceof Piece.Straight s) {
                final var loc = com.simibubi.create.content.trains.graph.TrackGraphHelper.getGraphLocationAt(level, s.at(0),
                    net.minecraft.core.Direction.AxisDirection.POSITIVE, s.dir().axis());
                graphs.add(loc == null ? new java.util.UUID(0, 0) : loc.graph.id);
            }
        LOG.info("[LHT test] graphs along route: {}", graphs);
        if (graphs.size() != 1 || graphs.contains(new java.util.UUID(0, 0)))
            h.fail("route is not one connected train graph: " + graphs);
        if (missing > 0 || curvesOk != curves)
            h.fail("route incomplete: " + missing + " track blocks missing, " + (curves - curvesOk) + " curves missing");
    }

    private static void logGraphs(final String when) {
        for (final TrackGraph g : Create.RAILWAYS.trackNetworks.values())
            LOG.info("[LHT test] {}: graph {} nodes={} ", when, g.id, g.getNodes().size());
    }

    private RouteTests() {}
}
