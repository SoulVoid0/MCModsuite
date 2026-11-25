package dev.soulvoid.longhaul.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;

/**
 * Finds a route the way a railway surveyor would: a weighted A* search over a coarse lattice of track headings and
 * heights, where every move is costed against the terrain it crosses. Running on the ground is cheap; embankments
 * and shallow cuttings cost a little; tunnels and land bridges cost a lot; open water may only be bridged with
 * clearance; every heading change costs extra, and turning back and forth costs double. The result follows valleys
 * and contours, bends around hills instead of tunnelling through them, and when the two ends are further apart in
 * height than the grade allows over the straight distance it lengthens the line itself, so it climbs mountainsides
 * in switchbacks or loops around a hill (a spiral) instead of failing as "too steep".
 *
 * <p>Moves are whole lattice cells in one of the 8 track directions, turning at most 45 degrees per cell, so the
 * result is always a layout Create can lay (straights joined by 45 degree curves, which may climb too). Off-thread
 * safe: terrain comes in through {@link Terrain}.</p>
 */
public final class TerrainRouter {

    /** Terrain column at an exact block x/z. Called from the planning thread only. */
    public interface Terrain {
        TerrainSampler.Column at(int x, int z);
    }

    /**
     * Legs from the start anchor, and the polyline (x, z, planned track y) the vertical profile follows.
     * {@code end} is null, or a replacement end when the real one (a needle-sharp summit) cannot be reached at the
     * grade without a viaduct: the line then stops at the highest point it can reach next to it.
     */
    public record Path(List<RoutePlanner.Leg> legs, List<double[]> waypoints, int expanded, Anchor end) {
        public Path(final List<RoutePlanner.Leg> legs, final List<double[]> waypoints, final int expanded) {
            this(legs, waypoints, expanded, null);
        }
    }

    private static final double INF = Double.POSITIVE_INFINITY;
    /** Search greediness: the first pass stays close to the cheapest route, the retry trades cost for speed. */
    private static final double[] WEIGHTS = {1.15, 2, 5};
    private static final double[] CLIMB_WEIGHTS = {5, 12};
    private static final boolean DEBUG = Boolean.getBoolean("lht.debug");
    private static final long OFF = 1L << 25;

    private record Root(int x, int z, Dir8 dir, RoutePlanner.Leg prefix, boolean mustTurn) {}

    private static final class Label implements Comparable<Label> {
        final int x, z, dir, h, turn;
        final double g, f;
        final Label parent;
        final Root root;
        /** Length of dry-land bridge the line ends on (for the span cap). */
        double span;

        Label(final int x, final int z, final int dir, final int h, final int turn, final double g, final double f,
              final Label parent, final Root root) {
            this.x = x;
            this.z = z;
            this.dir = dir;
            this.h = h;
            this.turn = turn;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.root = root;
        }

        boolean first() {
            return parent == null;
        }

        @Override
        public int compareTo(final Label o) {
            return Double.compare(f, o.f);
        }
    }

    private final Anchor start, end;
    private final RoutePlanner.Settings st;
    private final Terrain terrain;
    private final Map<Long, TerrainSampler.Column> cache = new HashMap<>();
    private final int cell;
    private final int[] dh = new int[2];
    private final double climbPerBlock;
    private final int band;
    private final int ex, ez, yE;
    private final double sx, sz, maxSum, dist, climb, devS, devE;
    private double weight = WEIGHTS[0];
    private double[] weights = WEIGHTS;
    private PlanProgress progress;
    private int pass;
    private boolean exhausted;
    private double dbgBest = INF;
    private Label dbgL, dbgTop;
    private int dbgFinish;

    private TerrainRouter(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final Terrain terrain) {
        this.start = s;
        this.end = e;
        this.st = st;
        this.terrain = terrain;
        this.cell = Math.max(8, st.cell() / 4 * 4);
        dh[0] = Math.max(1, (int) Math.floor(0.7 * st.grade() * cell));
        dh[1] = Math.max(1, (int) Math.floor(0.7 * st.grade() * cell * Math.sqrt(2)));
        this.climbPerBlock = dh[0] / (double) cell;
        this.band = Math.max(2, dh[0]);
        this.ex = e.pos().getX();
        this.ez = e.pos().getZ();
        this.yE = e.pos().getY();
        this.sx = s.pos().getX();
        this.sz = s.pos().getZ();
        final double d = Math.hypot(ex - sx, ez - sz);
        final double climb = Math.abs(yE - s.pos().getY()) / climbPerBlock;
        this.dist = d;
        this.climb = climb;
        final TerrainSampler.Column cs = terrain.at(s.pos().getX(), s.pos().getZ()), ce = terrain.at(ex, ez);
        this.devS = Math.max(0, s.pos().getY() - (cs.ground() + 1));
        this.devE = Math.max(0, yE - (ce.ground() + 1));
        this.maxSum = Math.min(st.maxLength(), Math.max(Math.max(d * 1.5, d + 1536), climb * 1.4 + 768));
    }

    /** Searches a route; null (with {@code err} filled) when none was found within the search budget. */
    public static Path search(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final Terrain terrain,
                              final StringBuilder err) {
        return search(s, e, st, terrain, err, new PlanProgress());
    }

    /** As above, reporting progress (the search is 90% of a plan) and stopping when {@code progress} is cancelled. */
    public static Path search(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final Terrain terrain,
                              final StringBuilder err, final PlanProgress progress) {
        final TerrainRouter r = new TerrainRouter(s, e, st, terrain);
        r.progress = progress;
        // a climb much longer than the distance (a mountain top) can only be solved by a long winding line, which
        // the near-optimal pass would spend its whole budget on: go greedier straight away
        final double[] weights = r.climbLength() > 1.2 * Math.max(64, r.distance()) ? CLIMB_WEIGHTS : WEIGHTS;
        if (Boolean.getBoolean("lht.forceSerpentine") || "true".equals(System.getProperty("lht.serp"))) {
            final StringBuilder why = new StringBuilder();
            final Path p = r.climb(why);
            err.append(why);
            return p;
        }
        r.weights = weights;
        for (int i = 0; i < weights.length; i++) {
            final double w = weights[i];
            r.pass = i;
            r.weight = w;
            r.exhausted = false;
            err.setLength(0);
            final Path p = r.run(err);
            if (p != null || progress.cancelled())
                return p;
            if (!r.exhausted)
                break;
        }
        // the search found nothing in its budget (a climb far longer than the distance on rough terrain): climb
        // with a beam search that hugs the mountain instead, so a reachable end does not fail as "too steep"
        final StringBuilder why = new StringBuilder();
        final Path p = r.climb(why);
        if (p != null) {
            err.setLength(0);
            return p;
        }
        err.append(' ').append(why);
        return null;
    }

    // ------------------------------------------------------------------------------------ climb fallback

    /** Beam width of the climb fallback: how many partial lines are kept per step. */
    private static final int BEAM = 160;

    /**
     * Climbs like a mountain railway when the search ran out of steps: a beam search that advances every kept line
     * one lattice cell at a time and keeps the {@code BEAM} most promising (cost so far plus a weighted estimate of
     * the distance and climb left). The track hugs the terrain: it follows the ground within the grade, cuts into
     * slopes or tunnels through rock when the ground rises faster, and may only bridge short, low gaps (the same
     * caps as the search). Climbing a mountain this way winds around it on its flanks (a spiral or a series of
     * loops), since that is where the ground rises at the grade. A line may not come back within two cells of
     * itself, so the corridor never overlaps.
     */
    Path climb(final StringBuilder err) {
        final BlockPos sp = start.pos();
        final List<Label> beam = new ArrayList<>();
        if (start.midTrack()) {
            for (final Dir8 d : start.dirs()) {
                final int k = d.turnSteps(45);
                final Root r = new Root(sp.getX() + d.dx * k, sp.getZ() + d.dz * k, d, new RoutePlanner.Leg(d, k), true);
                beam.add(new Label(r.x, r.z, d.ordinal(), sp.getY(), 0, 0, 0, null, r));
            }
        } else {
            final List<Dir8> ds = start.dirs().isEmpty() ? List.of(Dir8.values()) : start.dirs();
            for (final Dir8 d : ds) {
                final Root r = new Root(sp.getX(), sp.getZ(), d, null, false);
                beam.add(new Label(r.x, r.z, d.ordinal(), sp.getY(), 0, 0, 0, null, r));
            }
        }
        final double reach = 3.2 * cell + 8;
        final double w = 3;
        final int maxDepth = (int) Math.min(4000, st.maxLength() / (double) cell);
        final double needSteps = Math.max(climb, dist) / cell;
        int expanded = 0;
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        Label bestNear = null;
        double bestScore = INF;
        // every depth's ranked candidates, so a beam that runs into a dead end (a lone peak, a ridge with cliffs on
        // both sides) can back up and continue with the next-best candidates of an earlier step
        final List<List<Label>> ranked = new ArrayList<>();
        final List<Integer> used = new ArrayList<>();
        int depth = 0, backtracks = 0;
        while (depth < maxDepth) {
            if (progress.cancelled()) {
                err.append("Planning cancelled.");
                return null;
            }
            if (beam.isEmpty()) {
                // back up: the latest depth that still has unused candidates
                int d = depth - 1;
                while (d >= 0 && used.get(d) >= ranked.get(d).size())
                    d--;
                if (d < 0 || ++backtracks > 4000)
                    break;
                depth = d;
                final List<Label> r = ranked.get(d);
                int u = used.get(d);
                while (u < r.size() && beam.size() < BEAM)
                    beam.add(r.get(u++));
                used.set(d, u);
                while (ranked.size() > d + 1) {
                    ranked.remove(ranked.size() - 1);
                    used.remove(used.size() - 1);
                }
                depth = d + 1;
                continue;
            }
            progress.report(0.9 * (0.6 + 0.4 * Math.min(1, depth / Math.max(1, 1.3 * needSteps))));
            final Map<Long, Label> next = new HashMap<>();
            for (final Label l : beam) {
                if (Math.hypot(ex - l.x, ez - l.z) <= reach && !(l.first() && l.root.mustTurn)) {
                    final Path p = finish(l, expanded);
                    if (p != null)
                        return p;
                }
                expanded++;
                for (final Label c : children(l, w)) {
                    final boolean near = DEBUG && Math.hypot(ex - c.x, ez - c.z) < 600;
                    if (crowded(c)) {
                        if (near)
                            why[4]++;
                        continue;
                    }
                    final long k = key(c);
                    if (seen.contains(k)) {
                        if (near)
                            why[5]++;
                        continue;
                    }
                    if (near)
                        why[6]++;
                    if (near && c.h > why[7])
                        why[7] = c.h;
                    final Label o = next.get(k);
                    if (o == null || c.f < o.f)
                        next.put(k, c);
                }
            }
            final List<Label> all = new ArrayList<>(next.values());
            all.sort(null);
            if (DEBUG && (depth % 25 == 0 || all.isEmpty()))
                com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] climb depth {}: {} children, {} backtracks; best {}",
                    depth, all.size(), backtracks, all.isEmpty() ? "-" : all.get(0).x + " " + all.get(0).z + " y " + all.get(0).h
                        + " dE " + (int) Math.hypot(ex - all.get(0).x, ez - all.get(0).z) + " g " + (int) all.get(0).g);
            for (final Label l : all) {
                seen.add(key(l));
                // closest the climb got to the end: highest, then nearest, within reach of it
                final double dE = Math.hypot(ex - l.x, ez - l.z);
                if (dE <= 2 * END_ZONE && !(l.first() && l.root.mustTurn)) {
                    final double sc = Math.abs(yE - l.h) + 0.25 * dE;
                    if (sc < bestScore) {
                        bestScore = sc;
                        bestNear = l;
                    }
                }
            }
            beam.clear();
            int u = 0;
            while (u < all.size() && beam.size() < BEAM)
                beam.add(all.get(u++));
            ranked.add(all);
            used.add(u);
            depth++;
        }
        if (bestNear != null && Math.abs(yE - bestNear.h) <= Math.max(24, 0.25 * Math.abs(yE - start.pos().getY()))) {
            // the end itself is out of reach (a needle-sharp summit): stop at the closest point the climb reached
            final Path p = truncated(bestNear, expanded);
            if (p != null)
                return p;
        }
        if (DEBUG)
            com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] climb refusals near end: tall {} slope-on-bridge {} span {} crowded {} seen {} ok {} highest {}",
                why[1], why[2], why[3], why[4], why[5], why[6], why[7]);
        err.append("No route that stays on or in the ground (no bridge over ").append(st.maxBridgeHeight())
            .append(" blocks high or ").append(st.maxBridgeSpan()).append(" long) climbs to the end within ")
            .append(st.maxLength()).append(" blocks.");
        return null;
    }

    /** The line up to {@code l}, ending there instead of at the real end. */
    private Path truncated(final Label l, final int expanded) {
        final List<Label> chain = new ArrayList<>();
        for (Label c = l; c != null; c = c.parent)
            chain.add(c);
        Collections.reverse(chain);
        if (chain.size() < 2)
            return null;
        final List<RoutePlanner.Leg> legs = new ArrayList<>();
        if (l.root.prefix != null)
            legs.add(l.root.prefix);
        for (int i = 1; i < chain.size(); i++)
            legs.add(new RoutePlanner.Leg(Dir8.values()[chain.get(i).dir], cell));
        final List<RoutePlanner.Leg> merged = RoutePlanner.merge(legs);
        if (merged.isEmpty() || !RoutePlanner.valid(merged, st.maxLength()))
            return null;
        final List<double[]> wp = new ArrayList<>();
        final BlockPos sp = start.pos();
        wp.add(new double[] {sp.getX(), sp.getZ(), sp.getY()});
        for (final Label c : chain)
            wp.add(new double[] {c.x, c.z, c.h});
        return new Path(merged, wp, expanded, Anchor.free(new BlockPos(l.x, l.h, l.z)));
    }

    /** The moves of a label (as in the search), with weight {@code w} on the estimate. */
    private List<Label> children(final Label l, final double w) {
        final List<Label> out = new ArrayList<>(6);
        for (int t = -1; t <= 1; t++) {
            if (l.first() && l.root.mustTurn && t == 0)
                continue;
            final int m = (l.dir + t + 8) & 7;
            final Dir8 md = Dir8.values()[m];
            final int nx = l.x + md.dx * cell, nz = l.z + md.dz * cell;
            if (!inBounds(nx, nz))
                continue;
            final int lim = dh[md.diagonal() ? 1 : 0];
            final int tgt = target(col(nx, nz));
            final int[] opts = {clampY(Math.max(l.h - lim, Math.min(l.h + lim, tgt))), clampY(l.h),
                clampY(l.h + Integer.signum(yE - l.h) * lim)};
            for (int oi = 0; oi < opts.length; oi++) {
                final int h2 = opts[oi];
                if (oi > 0 && (h2 == opts[0] || oi == 2 && h2 == opts[1]))
                    continue;
                whyCode = 0;
                double c = edgeCost(l.x, l.z, l.h, md, h2, l.span);
                if (c == INF) {
                    if (DEBUG && Math.hypot(ex - l.x, ez - l.z) < 600)
                        why[whyCode]++;
                    continue;
                }
                if (t != 0)
                    c += st.turnCost() * (l.turn == -t ? 2 : 1);
                final double g = l.g + c;
                // the climb estimate alone would let the line wander off climbing any hill: also pull toward the end
                final Label n = new Label(nx, nz, m, h2, t, g, g + w * (heuristic(nx, nz, h2) + 0.3 * Math.hypot(ex - nx, ez - nz)), l, l.root);
                n.span = spanOut;
                out.add(n);
            }
        }
        return out;
    }

    /** True when the label comes within two cells of an earlier part of its own line (not its last few steps). */
    private boolean crowded(final Label c) {
        final int lim = 2 * cell - 1;
        int i = 0;
        for (Label p = c.parent; p != null; p = p.parent, i++) {
            if (i < 6)
                continue;
            if (Math.abs(p.x - c.x) <= lim && Math.abs(p.z - c.z) <= lim)
                return true;
        }
        return false;
    }

    double distance() {
        return dist;
    }

    /** Track length the height difference needs at the search's grade. */
    double climbLength() {
        return climb;
    }

    /** The last pass may run twice as long: it is the last chance before falling back to a direct line. */
    private int budget() {
        return pass == weights.length - 1 ? st.maxNodes() * 2 : st.maxNodes();
    }

    private TerrainSampler.Column col(final int x, final int z) {
        return cache.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL), k -> terrain.at(x, z));
    }

    private Path run(final StringBuilder err) {
        final List<Root> roots = new ArrayList<>();
        final BlockPos sp = start.pos();
        if (start.midTrack()) {
            for (final Dir8 d : start.dirs()) {
                final int k = d.turnSteps(45);
                roots.add(new Root(sp.getX() + d.dx * k, sp.getZ() + d.dz * k, d, new RoutePlanner.Leg(d, k), true));
            }
        } else {
            final List<Dir8> ds = start.dirs().isEmpty() ? List.of(Dir8.values()) : start.dirs();
            for (final Dir8 d : ds)
                roots.add(new Root(sp.getX(), sp.getZ(), d, null, false));
        }
        final PriorityQueue<Label> open = new PriorityQueue<>();
        final Map<Long, Double> best = new HashMap<>();
        for (final Root r : roots) {
            open.add(new Label(r.x, r.z, r.dir.ordinal(), sp.getY(), 0, 0, weight * heuristic(r.x, r.z, sp.getY()), null, r));
        }
        final double reach = 3.2 * cell + 8;
        int expanded = 0;
        while (!open.isEmpty()) {
            final Label l = open.poll();
            final Double b = best.get(key(l));
            if (b != null && b < l.g - 1e-9)
                continue;
            if ((expanded & 1023) == 0) {
                if (progress.cancelled()) {
                    err.append("Planning cancelled.");
                    return null;
                }
                // estimated share of the route done by the best label so far, and of the step budget used;
                // later (greedier) passes start from where the earlier ones stopped
                final double h = heuristic(l.x, l.z, l.h);
                final double done = l.g / Math.max(1, l.g + h);
                final double budget = expanded / (double) budget();
                final double passBase = pass / (double) weights.length;
                progress.report(0.9 * (passBase + (1 - passBase) * Math.max(done * 0.85, budget)));
            }
            if (DEBUG) {
                final double hh = heuristic(l.x, l.z, l.h);
                if (hh < dbgBest) {
                    dbgBest = hh;
                    dbgL = l;
                }
                if (dbgTop == null || l.h > dbgTop.h)
                    dbgTop = l;
                dbgFinish += Math.hypot(ex - l.x, ez - l.z) <= 3.2 * cell + 8 ? 1 : 0;
            }
            if (++expanded > budget()) {
                if (dbgL != null)
                    com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] pass {} best h {} at {} {} y {} g {} dE {}",
                        pass, dbgBest, dbgL.x, dbgL.z, dbgL.h, dbgL.g, Math.hypot(ex - dbgL.x, ez - dbgL.z));
                if (dbgTop != null)
                    com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] top label y {} at {} {} dE {} g {}; {} labels within reach; terrain there {}",
                        dbgTop.h, dbgTop.x, dbgTop.z, Math.hypot(ex - dbgTop.x, ez - dbgTop.z), dbgTop.g, dbgFinish,
                        col(dbgTop.x, dbgTop.z));
                dbgBest = INF;
                dbgL = null;
                dbgTop = null;
                dbgFinish = 0;
                exhausted = true;
                err.append("The route search gave up after ").append(budget())
                    .append(" steps (very rough terrain or a very long route).");
                return null;
            }
            final double dE = Math.hypot(ex - l.x, ez - l.z);
            if (dE <= reach && !(l.first() && l.root.mustTurn)) {
                final Path p = finish(l, expanded);
                if (p != null)
                    return p;
            }
            expand(l, open, best);
        }
        err.append("No route found: every way between the points is blocked (open water too low to bridge, or the world height limit).");
        return null;
    }

    private long key(final Label l) {
        return key(l.x, l.z, l.dir, l.h);
    }

    /**
     * State key: lattice position relative to the start (18 bits each), heading, and height band. The band is one
     * move's climb, so a label that climbed is never merged with (and dropped for) a cheaper one that did not.
     */
    private long key(final int x, final int z, final int dir, final int h) {
        final long rx = (long) (x - (int) sx) + (1 << 17), rz = (long) (z - (int) sz) + (1 << 17);
        return (rx & 0x3FFFF) << 33 | (rz & 0x3FFFF) << 15 | (long) dir << 12 | Math.floorDiv(h, band) & 0xFFF;
    }

    private double heuristic(final int x, final int z, final int h) {
        final double dx = Math.abs(ex - x), dz = Math.abs(ez - z);
        final double oct = Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
        final double climb = Math.abs(yE - h) / climbPerBlock;
        return Math.max(oct, climb);
    }

    private boolean inBounds(final int x, final int z) {
        return Math.hypot(x - sx, z - sz) + Math.hypot(x - ex, z - ez) <= maxSum;
    }

    private int target(final TerrainSampler.Column c) {
        int t = c.ground() + 1;
        if (c.wet())
            t = Math.max(t, c.water() + 2 + st.waterClearance());
        return t;
    }

    private int clampY(final int y) {
        return Math.max(st.minY(), Math.min(st.maxY(), y));
    }

    private void expand(final Label l, final PriorityQueue<Label> open, final Map<Long, Double> best) {
        for (int t = -1; t <= 1; t++) {
            if (l.first() && (l.root.mustTurn ? t == 0 : t != 0))
                continue;
            final int m = (l.dir + t + 8) & 7;
            final Dir8 md = Dir8.values()[m];
            final int nx = l.x + md.dx * cell, nz = l.z + md.dz * cell;
            if (!inBounds(nx, nz))
                continue;
            final int lim = dh[md.diagonal() ? 1 : 0];
            final int tgt = target(col(nx, nz));
            final int[] opts = new int[] {
                clampY(Math.max(l.h - lim, Math.min(l.h + lim, tgt))),
                clampY(l.h),
                clampY(l.h + Integer.signum(yE - l.h) * lim)
            };
            for (int oi = 0; oi < opts.length; oi++) {
                final int h2 = opts[oi];
                if (oi > 0 && (h2 == opts[0] || oi == 2 && h2 == opts[1]))
                    continue;
                double c = edgeCost(l.x, l.z, l.h, md, h2, l.span);
                if (c == INF)
                    continue;
                final double span = spanOut;
                if (t != 0) {
                    c += st.turnCost();
                    if (l.turn == -t)
                        c += st.turnCost(); // zig-zag
                }
                final double g = l.g + c;
                final long k = key(nx, nz, m, h2);
                final Double b = best.get(k);
                if (b != null && b <= g)
                    continue;
                best.put(k, g);
                final Label n = new Label(nx, nz, m, h2, t, g, g + weight * heuristic(nx, nz, h2), l, l.root);
                n.span = span;
                open.add(n);
            }
        }
    }

    /** Dry-bridge length at the end of the last {@link #edgeCost} move. */
    private double spanOut;

    /**
     * Cost of one lattice move: length, weighted by what the terrain under the line demands. Infinite when it
     * would bridge dry land higher than {@code maxBridgeHeight} or longer than {@code maxBridgeSpan}.
     */
    private double edgeCost(final int x, final int z, final int h, final Dir8 d, final int h2, final double spanIn) {
        final int q = cell / 4;
        final double seg = q * d.len();
        double c = 0, span = spanIn;
        for (int k = 1; k <= 4; k++) {
            final double y = h + (h2 - h) * k / 4.0;
            final int px = x + d.dx * q * k, pz = z + d.dz * q * k;
            final TerrainSampler.Column col = col(px, pz);
            allowAt(px, pz);
            final double b = blockCost(col, y);
            if (b == INF)
                return INF;
            if (!col.wet() && y - (col.ground() + 1) > st.bridgeThreshold()) {
                // a bridge over dry land crosses a gap level: it is never the way up (or down)
                if (Math.abs(h2 - h) > 1 && !nearEnd && y - (col.ground() + 1) > LEVEL_BRIDGE) {
                    whyCode = 2;
                    return INF;
                }
                span += seg;
                if (span > st.maxBridgeSpan() + allowedHeight - st.maxBridgeHeight()) {
                    whyCode = 3;
                    return INF;
                }
            } else
                span = 0;
            c += (1 + b) * seg;
        }
        spanOut = span;
        return c;
    }

    /** Bridge height allowed at the column being costed: the cap, plus a ramp down from an end that sits high. */
    private double allowedHeight;

    private boolean nearEnd;

    /**
     * The cap, plus a short approach near either end: an end on a summit or a ridge can only be reached on a
     * last stretch that stands above the falling ground around it (and an end that sits high, on a structure,
     * needs a ramp down). Within {@code END_ZONE} blocks of an end the allowance grows toward the end.
     */
    private void allowAt(final int x, final int z) {
        final double ds = Math.hypot(x - sx, z - sz), de = Math.hypot(x - ex, z - ez);
        allowedHeight = st.maxBridgeHeight() + Math.max(0, devS - 0.5 * ds) + Math.max(0, devE - 0.5 * de)
            + Math.max(0, 0.25 * (END_ZONE - ds)) + Math.max(0, 0.25 * (END_ZONE - de));
        nearEnd = ds < END_ZONE || de < END_ZONE;
    }

    private static final double END_ZONE = 160;
    private static final double DEEP_COST = Double.parseDouble(System.getProperty("lht.deep", "0.04"));
    /** Above this height over dry ground a bridge must be level (small dips may be crossed on the grade). */
    private static final double LEVEL_BRIDGE = 12;
    /** Debug: why moves near the end were refused. */
    final long[] why = new long[8];
    private int whyCode;

    /** Extra cost per block of track at height {@code y} over this column. */
    private double blockCost(final TerrainSampler.Column c, final double y) {
        if (c.wet()) {
            final double floor = c.water() + 2 + st.waterClearance();
            if (y < floor - 1e-6)
                return INF;
            return st.waterBridgeCost() + 0.05 * (y - floor);
        }
        final double dev = y - (c.ground() + 1);
        if (dev > allowedHeight) {
            whyCode = 1;
            return INF; // never climb on (or stand on) a tall bridge over dry land
        }
        if (dev > st.bridgeThreshold())
            return st.landBridgeCost() + 0.15 * dev;
        if (dev > 1)
            return 0.35 * dev;
        if (dev >= -1)
            return 0;
        final double depth = -dev;
        if (depth <= 4)
            return 0.3 * depth;
        // a tunnel just under the surface is a tunnel; one far below it means the line dived into the mountain
        // instead of winding up its flank (and arrives at the top far too low): cover beyond 24 blocks costs more
        return st.tunnelCost() + DEEP_COST * Math.max(0, depth - 24);
    }

    /** Tries to finish from {@code l} straight to the end anchor with Create-legal legs. */
    private Path finish(final Label l, final int expanded) {
        final Anchor p = new Anchor(new BlockPos(l.x, l.h, l.z), List.of(Dir8.values()[l.dir]), false, false);
        final List<RoutePlanner.Leg> tail = RoutePlanner.legsBetween(p, end, st.maxLength(), new StringBuilder());
        if (tail == null)
            return null;
        double tailLen = 0;
        for (final RoutePlanner.Leg g : tail)
            tailLen += g.steps() * g.dir().len() + Math.hypot(g.sx(), g.sz());
        if (Math.abs(yE - l.h) > 0.9 * st.grade() * tailLen + 1)
            return null;
        final List<Label> chain = new ArrayList<>();
        for (Label c = l; c != null; c = c.parent)
            chain.add(c);
        Collections.reverse(chain);
        final List<RoutePlanner.Leg> legs = new ArrayList<>();
        if (l.root.prefix != null)
            legs.add(l.root.prefix);
        for (int i = 1; i < chain.size(); i++)
            legs.add(new RoutePlanner.Leg(Dir8.values()[chain.get(i).dir], cell));
        legs.addAll(tail);
        final List<RoutePlanner.Leg> merged = RoutePlanner.merge(legs);
        if (merged.isEmpty() || !RoutePlanner.valid(merged, st.maxLength()))
            return null;
        final List<double[]> wp = new ArrayList<>();
        final BlockPos sp = start.pos();
        wp.add(new double[] {sp.getX(), sp.getZ(), sp.getY()});
        for (final Label c : chain)
            wp.add(new double[] {c.x, c.z, c.h});
        double x = l.x, z = l.z, t = 0;
        for (final RoutePlanner.Leg g : tail) {
            final double nx = x + g.dir().dx * g.steps() + g.sx(), nz = z + g.dir().dz * g.steps() + g.sz();
            t += Math.hypot(nx - x, nz - z);
            wp.add(new double[] {nx, nz, l.h + (yE - l.h) * (tailLen <= 0 ? 1 : Math.min(1, t / tailLen))});
            x = nx;
            z = nz;
        }
        wp.add(new double[] {ex, ez, yE});
        return new Path(merged, wp, expanded);
    }
}
