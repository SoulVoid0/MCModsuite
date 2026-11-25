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
    private final int ex, ez, yE;
    private final double sx, sz, maxSum;
    private double weight = WEIGHTS[0];
    private boolean exhausted;

    private TerrainRouter(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final Terrain terrain) {
        this.start = s;
        this.end = e;
        this.st = st;
        this.terrain = terrain;
        this.cell = Math.max(8, st.cell() / 4 * 4);
        dh[0] = Math.max(1, (int) Math.floor(0.7 * st.grade() * cell));
        dh[1] = Math.max(1, (int) Math.floor(0.7 * st.grade() * cell * Math.sqrt(2)));
        this.climbPerBlock = dh[0] / (double) cell;
        this.ex = e.pos().getX();
        this.ez = e.pos().getZ();
        this.yE = e.pos().getY();
        this.sx = s.pos().getX();
        this.sz = s.pos().getZ();
        final double d = Math.hypot(ex - sx, ez - sz);
        final double climb = Math.abs(yE - s.pos().getY()) / climbPerBlock;
        this.maxSum = Math.min(st.maxLength(), Math.max(Math.max(d * 1.5, d + 1536), climb * 1.4 + 768));
    }

    /** Searches a route; null (with {@code err} filled) when none was found within the search budget. */
    public static Path search(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final Terrain terrain,
                              final StringBuilder err) {
        final TerrainRouter r = new TerrainRouter(s, e, st, terrain);
        for (final double w : WEIGHTS) {
            r.weight = w;
            r.exhausted = false;
            err.setLength(0);
            final Path p = r.run(err);
            if (p != null || !r.exhausted)
                return p;
        }
        return null;
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
            if (++expanded > st.maxNodes()) {
                exhausted = true;
                err.append("The route search gave up after ").append(st.maxNodes())
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

    private static long key(final Label l) {
        return key(l.x, l.z, l.dir, l.h);
    }

    private static long key(final int x, final int z, final int dir, final int h) {
        return ((x + OFF) << 38) ^ ((z + OFF) << 12) ^ ((long) dir << 9) ^ (Math.floorDiv(h, 8) & 0x1FF);
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
