package dev.soulvoid.longhaul.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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
import dev.soulvoid.longhaul.route.Style;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Builds real routes in a headless server, far outside any loaded area (so planning samples world-gen noise and the
 * builder has to ticket-load and generate every chunk):
 * <ol>
 * <li>a climb from the plain to the summit of a cone-shaped mountain far steeper than the grade, which must be
 *     solved by lengthening the line (switchbacks or a spiral), built in clear-only mode;</li>
 * <li>a long route across shaped terrain (a hill, two hills with a valley, a lake) too wide to go around, full
 *     structures with lanterns;</li>
 * <li>a branch off the middle of that route (junction), with torches.</li>
 * </ol>
 */
@GameTestHolder("longhaultracks")
@PrefixGameTestTemplate(false)
public final class RouteTests {

    private static final Logger LOG = LogUtils.getLogger();

    private record Spec(String name, Style.Light light, Style.Mode mode, Runnable prepare, Supplier<BlockPos[]> ends,
                        boolean surfaceStart) {}

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 30)
    public static void buildsRoutes(final GameTestHelper h) {
        final ServerLevel level = h.getLevel();
        final JobManager m = JobManager.get(level.getServer());
        final BlockPos far = new BlockPos(Integer.getInteger("lht.x", 4000), 0, Integer.getInteger("lht.z", 4000));
        final int dx = Integer.getInteger("lht.dx", 900), dz = Integer.getInteger("lht.dz", 420);
        final BlockPos peak = far.offset(300, 0, 1600);
        final Object[] junction = new Object[1];
        final List<Piece> firstPieces = new ArrayList<>();
        final List<Spec> all = List.of(
            new Spec("climb", Style.Light.NONE, Style.Mode.CLEAR_ONLY, () -> cone(level, peak, 60, 0.6),
                () -> new BlockPos[] {peak.offset(-260, 0, 0), peak}, true),
            new Spec("route", Style.Light.LANTERN, Style.Mode.FULL, () -> terrain(level, far),
                () -> new BlockPos[] {far, far.offset(dx, 0, dz)}, true),
            new Spec("junction", Style.Light.TORCH, Style.Mode.FULL, () -> {}, () -> {
                Piece.Straight best = null;
                for (final Piece p : firstPieces)
                    if (p instanceof Piece.Straight s && (best == null || s.count() > best.count()))
                        best = s;
                final BlockPos j = best.at(best.count() / 2);
                junction[0] = j;
                LOG.info("[LHT test] branching from {}", j);
                return new BlockPos[] {j, j.offset(-250, 0, 380)};
            }, false));
        final String only = System.getProperty("lht.only", "");
        final List<Spec> specs = all.stream().filter(sp -> only.isEmpty() || only.contains(sp.name)).toList();
        final int[] idx = {0};
        final int[] etaSeen = {-1};
        final int[] stage = {0};
        final UUID[] owner = {null};
        final UUID[] jobId = {null};
        final List<Piece>[] pieces = new List[1];
        h.onEachTick(() -> {
            if (idx[0] >= specs.size())
                return;
            final Spec sp = specs.get(idx[0]);
            switch (stage[0]) {
                case 0 -> {
                    sp.prepare.run();
                    owner[0] = UUID.randomUUID();
                    m.style(owner[0], sp.light, sp.mode);
                    final BlockPos[] e = sp.ends.get();
                    m.planFor(owner[0], "gametest-" + sp.name, level, e[0], sp.surfaceStart, e[1], true);
                    stage[0] = 1;
                }
                case 1 -> {
                    final Job j = find(m, owner[0]);
                    if (j == null) {
                        final String msg = m.draftMessage(owner[0]);
                        if (!msg.isEmpty() && !msg.startsWith("Planning"))
                            h.fail(sp.name + ": planning failed: " + msg);
                        return;
                    }
                    LOG.info("[LHT test] {}: planned {} blocks, {} bridge, {} tunnel, {} pieces, estimate {}, start {} end {} junction {}/{}",
                        sp.name, j.length, j.bridgeLength, j.tunnelLength, j.pieces.size(), j.required, j.start, j.end,
                        j.startJunction, j.endJunction);
                    final long tracks = j.required.getOrDefault("create:track", 0L);
                    if (tracks > j.length / 12 + 4)
                        h.fail(sp.name + ": too many tracks estimated: " + tracks + " for " + j.length + " blocks");
                    pieces[0] = j.pieces;
                    jobId[0] = j.id;
                    m.admin(j.id, Net.Action.CONFIRM);
                    stage[0] = 2;
                }
                case 2 -> {
                    final Job j = m.job(jobId[0]);
                    if (j == null)
                        h.fail(sp.name + ": job vanished during survey");
                    if (j.state == Job.State.AWAITING_MATERIALS || j.state == Job.State.BUILDING) {
                        LOG.info("[LHT test] {}: survey done: exact materials {}", sp.name, j.required);
                        final long tracks = j.required.getOrDefault("create:track", 0L);
                        if (tracks > j.length / 12 + 4)
                            h.fail(sp.name + ": too many tracks surveyed: " + tracks + " for " + j.length + " blocks");
                        if (sp.mode == Style.Mode.CLEAR_ONLY && j.required.keySet().stream().anyMatch(k -> !k.equals("create:track")))
                            h.fail(sp.name + ": clear-only route asks for structure blocks: " + j.required);
                        m.admin(j.id, Net.Action.FILL);
                        stage[0] = 3;
                    }
                }
                case 3 -> {
                    final Job j = m.job(jobId[0]);
                    if (j != null) {
                        if (j.state == Job.State.NEEDS_MATERIALS)
                            h.fail(sp.name + ": builder ran short: " + j.message);
                        if (j.etaSeconds() >= 0 && etaSeen[0] < 0) {
                            etaSeen[0] = j.etaSeconds();
                            LOG.info("[LHT test] {}: building, {}% done, ETA {} s", sp.name, 100 * j.cursor / Math.max(1, j.chunkCount()), etaSeen[0]);
                        }
                        return;
                    }
                    if (etaSeen[0] < 0 && sp.name.equals("route"))
                        h.fail(sp.name + ": no build ETA was ever available");
                    etaSeen[0] = -1;
                    checkTracks(h, level, sp.name, pieces[0]);
                    logGraphs("after " + sp.name);
                    if (sp.name.equals("route"))
                        firstPieces.addAll(pieces[0]);
                    if (sp.name.equals("junction")) {
                        final BlockPos jp = (BlockPos) junction[0];
                        if (!(level.getBlockEntity(jp) instanceof TrackBlockEntity te) || te.getConnections().isEmpty())
                            h.fail("junction block has no curve connection at " + jp);
                    }
                    if (sp.name.equals("climb")) {
                        int top = Integer.MIN_VALUE;
                        for (final Piece p : pieces[0])
                            if (p instanceof Piece.Straight s)
                                for (int i = 0; i < s.count(); i++)
                                    top = Math.max(top, s.at(i).getY());
                        LOG.info("[LHT test] climb: highest track y {} (summit surface y {})", top,
                            level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, peak.getX(), peak.getZ()));
                    }
                    idx[0]++;
                    stage[0] = 0;
                    if (idx[0] >= specs.size())
                        h.succeed();
                }
                default -> {}
            }
        });
    }

    /** A plan cancelled straight away leaves no job behind and frees the planner for the next plan. */
    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 2)
    public static void cancelsPlanning(final GameTestHelper h) {
        final ServerLevel level = h.getLevel();
        final JobManager m = JobManager.get(level.getServer());
        final UUID owner = UUID.randomUUID();
        final BlockPos a = new BlockPos(-20000, 0, -20000);
        m.planFor(owner, "gametest-cancel", level, a, true, a.offset(9000, 0, 6000), true);
        final int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            if (t[0] == 1) {
                if (!m.planning(owner))
                    h.fail("plan did not start");
                if (!m.cancelPlan(owner))
                    h.fail("cancelPlan returned false");
            } else if (t[0] == 200) {
                if (m.planning(owner) || find(m, owner) != null)
                    h.fail("cancelled plan still running or produced a job");
                if (!m.draftMessage(owner).contains("cancelled"))
                    h.fail("unexpected message: " + m.draftMessage(owner));
                LOG.info("[LHT test] cancel: ok ({})", m.draftMessage(owner));
                h.succeed();
            }
        });
    }

    /**
     * The GameTest world is superflat (surface y=-61), so shape terrain across the route's band: a hill, two hills
     * with a valley between, and a lake.
     */
    private static void terrain(final ServerLevel level, final BlockPos o) {
        final var stone = Blocks.STONE.defaultBlockState();
        final var water = Blocks.WATER.defaultBlockState();
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = o.getZ() - 500; z <= o.getZ() + 900; z++) {
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

    /** A cone of stone {@code height} blocks tall whose sides fall {@code slope} blocks per block. */
    private static void cone(final ServerLevel level, final BlockPos c, final int height, final double slope) {
        final var stone = Blocks.STONE.defaultBlockState();
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        final int r = (int) Math.ceil(height / slope);
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                final int h = (int) Math.round(height - Math.sqrt(x * x + z * z) * slope);
                for (int y = -60; y < -60 + h; y++)
                    level.setBlock(p.set(c.getX() + x, y, c.getZ() + z), stone, 2);
            }
        LOG.info("[LHT test] cone shaped at {}", c);
    }

    private static Job find(final JobManager m, final UUID owner) {
        for (final Job j : m.jobs())
            if (j.owner.equals(owner))
                return j;
        return null;
    }

    private static void checkTracks(final GameTestHelper h, final ServerLevel level, final String name, final List<Piece> pieces) {
        int missing = 0, total = 0, curves = 0, curvesOk = 0, blocked = 0;
        for (final Piece p : pieces) {
            if (p instanceof Piece.Straight s) {
                for (int i = 0; i < s.count(); i++) {
                    total++;
                    if (!(level.getBlockState(s.at(i)).getBlock() instanceof TrackBlock))
                        missing++;
                    // head room: nothing solid in the 6 blocks over the rails (8 high minus the hanging-light row)
                    for (int dy = 1; dy <= 6; dy++)
                        if (!level.getBlockState(s.at(i).above(dy)).getCollisionShape(level, s.at(i).above(dy)).isEmpty()) {
                            blocked++;
                            break;
                        }
                }
            } else if (p instanceof Piece.Curve c) {
                curves++;
                if (level.getBlockEntity(c.a()) instanceof TrackBlockEntity te && te.getConnections().containsKey(c.b()))
                    curvesOk++;
            }
        }
        LOG.info("[LHT test] {}: track blocks {}/{} present, curves {}/{} connected, {} without head room", name,
            total - missing, total, curvesOk, curves, blocked);
        if (blocked > 0)
            h.fail(name + ": " + blocked + " track blocks have something solid within 6 blocks above them");
        final java.util.Set<UUID> graphs = new java.util.LinkedHashSet<>();
        for (final Piece p : pieces)
            if (p instanceof Piece.Straight s) {
                final var loc = com.simibubi.create.content.trains.graph.TrackGraphHelper.getGraphLocationAt(level, s.at(0),
                    net.minecraft.core.Direction.AxisDirection.POSITIVE, s.dir().axis());
                graphs.add(loc == null ? new UUID(0, 0) : loc.graph.id);
            }
        LOG.info("[LHT test] {}: graphs along route: {}", name, graphs);
        if (graphs.size() != 1 || graphs.contains(new UUID(0, 0)))
            h.fail(name + ": route is not one connected train graph: " + graphs);
        if (missing > 0 || curvesOk != curves)
            h.fail(name + ": route incomplete: " + missing + " track blocks missing, " + (curves - curvesOk) + " curves missing");
    }

    private static void logGraphs(final String when) {
        for (final TrackGraph g : Create.RAILWAYS.trackNetworks.values())
            LOG.info("[LHT test] {}: graph {} nodes={} ", when, g.id, g.getNodes().size());
    }

    private RouteTests() {}
}
