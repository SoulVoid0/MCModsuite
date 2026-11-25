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

    /** Legs from the start anchor, and the polyline (x, z, planned track y) the vertical profile follows. */
    public record Path(List<RoutePlanner.Leg> legs, List<double[]> waypoints, int expanded) {}

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
    private final double sx, sz, maxSum, dist, climb;
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
            final Path p = r.serpentine(why);
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
        // the search found nothing in its budget (a climb far longer than the distance on rough terrain): lay out
        // switchbacks instead, so a reachable end never fails as "too steep"
        final StringBuilder why = new StringBuilder();
        final Path p = r.serpentine(why);
        if (p != null) {
            err.setLength(0);
            return p;
        }
        err.append(' ').append(why);
        return null;
    }

    // ------------------------------------------------------------------------------- switchback fallback

    /** One lattice step of a laid-out line: its position after the step and its heading. */
    private record Step(int x, int z, int dir) {}

    /**
     * Lays out a climbing line by construction: an approach along the axis between the ends plus a block of
     * switchbacks (rows across the axis joined by 180 degree horseshoe curves, each made of four 45 degree turns,
     * rows three cells apart) long enough for the height difference at the grade. The switchbacks go next to the
     * start or the end, on either side of the axis, with as few or as many rows as fit; every candidate gets a
     * height profile that follows the ground as closely as the grade and the fixed ends allow, and the one cheapest
     * over the terrain (tunnels, bridges, embankments, as in the search) is used. Rows never cross each other or the
     * approach, so every column of the corridor carries one track.
     */
    Path serpentine(final StringBuilder err) {
        final double ax = ex - sx, az = ez - sz;
        final double dist = Math.max(1, Math.hypot(ax, az));
        Dir8 axis = Dir8.E;
        double bestDot = -2;
        for (final Dir8 d : Dir8.values()) {
            final double dot = (d.unit().x * ax + d.unit().z * az) / dist;
            if (dot > bestDot) {
                bestDot = dot;
                axis = d;
            }
        }
        final double need = Math.abs(yE - start.pos().getY()) / climbPerBlock * 1.25 + 4 * cell;
        Path best = null;
        double bestCost = INF;
        String lastErr = "No switchback layout fits between the points.";
        final BlockPos sp = start.pos();
        final List<Root> roots = new ArrayList<>();
        if (start.midTrack()) {
            for (final Dir8 d : start.dirs()) {
                final int k = d.turnSteps(45);
                roots.add(new Root(sp.getX() + d.dx * k, sp.getZ() + d.dz * k, d, new RoutePlanner.Leg(d, k), true));
            }
        } else if (start.dirs().isEmpty())
            roots.add(new Root(sp.getX(), sp.getZ(), axis, null, false));
        else
            for (final Dir8 d : start.dirs())
                roots.add(new Root(sp.getX(), sp.getZ(), d, null, false));
        final int[] rowCounts = {1, 3, 5, 7, 9, 13, 17, 25, 35};
        int tried = 0;
        for (final Root root : roots)
            for (final boolean atEnd : new boolean[] {true, false})
                for (final int side : new int[] {1, -1})
                    for (final int k : rowCounts) {
                        if (progress.cancelled())
                            return null;
                        progress.report(0.9 * (0.9 + 0.1 * (++tried) / (roots.size() * 2 * 2 * rowCounts.length)));
                        // shortest rows that give the length
                        List<Step> steps = null;
                        for (int m = 2; m <= 400; m = m < 12 ? m + 1 : m + Math.max(1, m / 6)) {
                            final List<Step> st2 = layout(root, axis, side, k, m, atEnd);
                            if (st2 == null)
                                break;
                            final Step last = st2.get(st2.size() - 1);
                            final double len = length(st2) + Math.hypot(ex - last.x, ez - last.z);
                            if (len > st.maxLength())
                                break;
                            if (len >= need) {
                                steps = st2;
                                break;
                            }
                        }
                        if (steps == null || crowded(steps))
                            continue;
                        final Step last = steps.get(steps.size() - 1);
                        final Anchor p = new Anchor(new BlockPos(last.x, 0, last.z), List.of(Dir8.values()[last.dir]), false, false);
                        final StringBuilder te = new StringBuilder();
                        final List<RoutePlanner.Leg> tail = RoutePlanner.legsBetween(p, end, st.maxLength(), te);
                        if (tail == null) {
                            lastErr = te.toString();
                            continue;
                        }
                        final List<RoutePlanner.Leg> legs = new ArrayList<>();
                        if (root.prefix != null)
                            legs.add(root.prefix);
                        for (final Step s : steps.subList(1, steps.size()))
                            legs.add(new RoutePlanner.Leg(Dir8.values()[s.dir], cell));
                        legs.addAll(tail);
                        final List<RoutePlanner.Leg> merged = RoutePlanner.merge(legs);
                        if (merged.isEmpty() || !RoutePlanner.valid(merged, st.maxLength()))
                            continue;
                        // points: start, the lattice steps, the tail's corners, the end
                        final List<double[]> pts = new ArrayList<>();
                        pts.add(new double[] {sp.getX(), sp.getZ(), 0});
                        for (final Step s : steps)
                            pts.add(new double[] {s.x, s.z, 0});
                        double x = last.x, z = last.z;
                        for (final RoutePlanner.Leg g : tail) {
                            x += g.dir().dx * g.steps() + g.sx();
                            z += g.dir().dz * g.steps() + g.sz();
                            pts.add(new double[] {x, z, 0});
                        }
                        pts.add(new double[] {ex, ez, 0});
                        final double cost = profile(pts);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new Path(merged, pts, 0);
                        }
                    }
        if (best == null)
            err.append(lastErr);
        return best;
    }

    /**
     * Steps of one switchback candidate, starting at the root: approach along the axis (before or after the
     * switchbacks), {@code rows} horseshoes with rows {@code m} cells long on {@code side} of the axis.
     */
    private List<Step> layout(final Root root, final Dir8 axis, final int side, final int rows, final int m,
                              final boolean atEnd) {
        final List<Step> out = new ArrayList<>();
        final int[] cur = {root.x, root.z, root.dir.ordinal(), root.mustTurn ? 1 : 0};
        out.add(new Step(cur[0], cur[1], cur[2]));
        final int a = axis.ordinal();
        final int out_ = (a + 2 * side + 8) & 7, in = (a - 2 * side + 8) & 7;
        // axial length of the switchback block: rows join with 3-cell horseshoes, plus a turn in and out
        final double axialCell = cell * axis.len();
        final double along = ((ex - sx) * axis.unit().x + (ez - sz) * axis.unit().z);
        final int block = 3 * rows + 4;
        final int approach = (int) Math.floor(along / axialCell) - block - 3;
        if (atEnd && approach < 0)
            return null; // no room for the block before the end: the start-side variant covers it
        if (atEnd && approach > 0) {
            steer(out, cur, a);
            straight(out, cur, approach);
        }
        // first row: turn out to the side, go out m cells
        steer(out, cur, out_);
        straight(out, cur, m);
        for (int r = 0; r < rows; r++) {
            final boolean outward = r % 2 == 0;
            // horseshoe toward the end: out -> a+side -> a -> a-side -> in (or the mirror)
            final int s1 = outward ? side : -side;
            turnTo(out, cur, (a + s1 + 8) & 7);
            turnTo(out, cur, a);
            turnTo(out, cur, (a - s1 + 8) & 7);
            turnTo(out, cur, outward ? in : out_);
            straight(out, cur, m + 1);
        }
        // rows is odd: the last row ran inward; head for the end along the axis
        steer(out, cur, a);
        if (!atEnd) {
            final double done = ((cur[0] - sx) * axis.unit().x + (cur[1] - sz) * axis.unit().z);
            final int rest = (int) Math.floor((along - done) / axialCell) - 3;
            if (rest > 0)
                straight(out, cur, rest);
        }
        return out;
    }

    /** One step turning at most 45 degrees toward {@code dir} (a forced first turn counts). */
    private void turnTo(final List<Step> out, final int[] cur, final int dir) {
        final int diff = (dir - cur[2] + 8) & 7;
        int t = diff == 0 ? 0 : diff <= 4 ? 1 : -1;
        if (cur[3] == 1 && t == 0)
            t = 1;
        cur[3] = 0;
        cur[2] = (cur[2] + t + 8) & 7;
        final Dir8 d = Dir8.values()[cur[2]];
        cur[0] += d.dx * cell;
        cur[1] += d.dz * cell;
        out.add(new Step(cur[0], cur[1], cur[2]));
    }

    private void steer(final List<Step> out, final int[] cur, final int dir) {
        for (int i = 0; i < 8 && (cur[2] != dir || cur[3] == 1); i++)
            turnTo(out, cur, dir);
    }

    private void straight(final List<Step> out, final int[] cur, final int n) {
        for (int i = 0; i < n; i++)
            turnTo(out, cur, cur[2]);
    }

    private double length(final List<Step> steps) {
        double l = 0;
        for (int i = 1; i < steps.size(); i++)
            l += cell * Dir8.values()[steps.get(i).dir].len();
        return l;
    }

    /** True when two steps that are not neighbours along the line come closer than two cells (rows touching). */
    private boolean crowded(final List<Step> steps) {
        final Map<Long, Integer> seen = new HashMap<>();
        final int g = cell * 2;
        for (int i = 0; i < steps.size(); i++) {
            final Step s = steps.get(i);
            final int gx = Math.floorDiv(s.x, g), gz = Math.floorDiv(s.z, g);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    final Integer j = seen.get(((long) (gx + dx) << 32) ^ (gz + dz & 0xFFFFFFFFL));
                    if (j != null && i - j > 6) {
                        final Step o = steps.get(j);
                        if (Math.abs(o.x - s.x) < g && Math.abs(o.z - s.z) < g)
                            return true;
                    }
                }
            seen.put(((long) gx << 32) ^ (gz & 0xFFFFFFFFL), i);
        }
        return false;
    }

    /**
     * Fills in heights (index 2) along the points: as close to the ground as possible while never steeper than the
     * search grade and still able to reach both fixed ends; returns the terrain cost of the line.
     */
    private double profile(final List<double[]> pts) {
        final int n = pts.size();
        final double[] arc = new double[n];
        for (int i = 1; i < n; i++)
            arc[i] = arc[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        final double total = arc[n - 1], g = climbPerBlock;
        final int yS = start.pos().getY();
        final double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            final double lo = Math.max(yS - g * arc[i], yE - g * (total - arc[i]));
            final double hi = Math.min(yS + g * arc[i], yE + g * (total - arc[i]));
            final double want = i == 0 ? yS : i == n - 1 ? yE : target(col((int) pts.get(i)[0], (int) pts.get(i)[1]));
            y[i] = Math.max(lo, Math.min(hi, want));
        }
        for (int i = 1; i < n; i++)
            y[i] = Math.max(y[i - 1] - g * (arc[i] - arc[i - 1]), Math.min(y[i - 1] + g * (arc[i] - arc[i - 1]), y[i]));
        for (int i = n - 2; i >= 0; i--)
            y[i] = Math.max(y[i + 1] - g * (arc[i + 1] - arc[i]), Math.min(y[i + 1] + g * (arc[i + 1] - arc[i]), y[i]));
        double cost = 0;
        for (int i = 0; i < n; i++) {
            y[i] = clampY((int) Math.round(y[i]));
            pts.get(i)[2] = y[i];
        }
        for (int i = 1; i < n; i++) {
            final double[] a = pts.get(i - 1), b = pts.get(i);
            final double len = arc[i] - arc[i - 1];
            final int k = Math.max(1, (int) Math.round(len / Math.max(4, cell / 4.0)));
            for (int j = 1; j <= k; j++) {
                final double t = j / (double) k;
                final double bc = blockCost(col((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t)),
                    a[2] + (b[2] - a[2]) * t);
                cost += (1 + (bc == INF ? 1000 : bc)) * len / k;
            }
        }
        return cost;
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
                double c = edgeCost(l.x, l.z, l.h, md, h2);
                if (c == INF)
                    continue;
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
                open.add(new Label(nx, nz, m, h2, t, g, g + weight * heuristic(nx, nz, h2), l, l.root));
            }
        }
    }

    /** Cost of one lattice move: length, weighted by what the terrain under the line demands. */
    private double edgeCost(final int x, final int z, final int h, final Dir8 d, final int h2) {
        final int q = cell / 4;
        final double seg = q * d.len();
        double c = 0;
        for (int k = 1; k <= 4; k++) {
            final double y = h + (h2 - h) * k / 4.0;
            final double b = blockCost(col(x + d.dx * q * k, z + d.dz * q * k), y);
            if (b == INF)
                return INF;
            c += (1 + b) * seg;
        }
        return c;
    }

    /** Extra cost per block of track at height {@code y} over this column. */
    private double blockCost(final TerrainSampler.Column c, final double y) {
        if (c.wet()) {
            final double floor = c.water() + 2 + st.waterClearance();
            if (y < floor - 1e-6)
                return INF;
            return st.waterBridgeCost() + 0.05 * (y - floor);
        }
        final double dev = y - (c.ground() + 1);
        if (dev > st.bridgeThreshold())
            return st.landBridgeCost() + 0.15 * dev;
        if (dev > 1)
            return 0.35 * dev;
        if (dev >= -1)
            return 0;
        final double depth = -dev;
        if (depth <= 4)
            return 0.3 * depth;
        return st.tunnelCost();
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
        if (Math.abs(yE - l.h) > climbPerBlock * tailLen + 1)
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
