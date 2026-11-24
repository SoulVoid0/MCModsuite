package dev.soulvoid.longhaul.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.simibubi.create.content.trains.track.TrackMaterial;

import net.minecraft.core.BlockPos;

/**
 * Plans a route between two anchors: an octilinear path Create can lay (straights in 8 directions, 45/90 degree
 * curves, S-bends for small offsets), then a vertical profile that follows the ground within a grade limit, cutting
 * tunnels through rises and bridging dips, then the list of buildable pieces and a material estimate.
 *
 * <p>Pure geometry: given the terrain columns it touches no world state, so the expensive part runs off-thread.</p>
 */
public final class RoutePlanner {

    /** Tunables, captured on the server thread. */
    public record Settings(int maxLength, double grade, int bridgeThreshold, int waterClearance, int tunnelHeight,
                           int pillarSpacing, int maxPillarDepth, double bridgePreference, double mountainBridgePreference,
                           int smoothingRadius, int sampleSpacing, int minY, int maxY) {}

    /** A straight leg of the octilinear path. {@code sm > 0}: an S-bend of {@code sm} steps shifts it by (sx, sz). */
    public record Leg(Dir8 dir, int steps, int sx, int sz, int sm) {
        Leg(final Dir8 dir, final int steps) {
            this(dir, steps, 0, 0, 0);
        }

        boolean shifted() {
            return sm > 0;
        }
    }

    /** First stage result: the 2D path and its centerline samples, before terrain is known. */
    public static final class Layout {
        public final List<Leg> legs;
        final List<Run> runs = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        /** Centerline sample positions (x, z) in order, and their arc length. */
        public final List<int[]> samples = new ArrayList<>();
        final List<Double> arc = new ArrayList<>();
        final Anchor start;
        final Anchor end;

        Layout(final List<Leg> legs, final Anchor start, final Anchor end) {
            this.legs = legs;
            this.start = start;
            this.end = end;
        }

        public int length() {
            int n = 0;
            for (final Leg l : legs)
                n += l.steps;
            return n;
        }
    }

    static final class Run {
        int x, z, n;
        Dir8 dir;
        int[] sample;
        int y0, y1;
    }

    static final class Link {
        int x1, z1, x2, z2, s1, s2, y;
        Dir8 a1, a2;
    }

    /** Final result. */
    public record Result(List<Piece> pieces, int length, int bridgeLength, int tunnelLength,
                         Map<String, Long> estimate, String error) {
        static Result fail(final String error) {
            return new Result(List.of(), 0, 0, 0, Map.of(), error);
        }
    }

    // ------------------------------------------------------------------------------------------------ path

    /** Picks the cheapest valid octilinear path between the anchors, or returns null with {@code err} filled in. */
    public static Layout layout(final Anchor s, final Anchor e, final int maxLength, final StringBuilder err) {
        if (s.pos().getX() == e.pos().getX() && s.pos().getZ() == e.pos().getZ()) {
            err.append("Start and end are the same column.");
            return null;
        }
        final List<Dir8> sDirs = s.dirs().isEmpty() ? nullList() : s.dirs();
        final List<Dir8> eDirs = e.dirs().isEmpty() ? nullList() : e.dirs();
        List<Leg> best = null;
        double bestCost = Double.MAX_VALUE;

        for (final Dir8 ds : sDirs)
            for (final Dir8 de : eDirs)
                for (final int ts : turnOptions(ds, s))
                    for (final int te : turnOptions(de, e))
                        for (final int xs : extOptions(ds, s, ts))
                            for (final int xe : extOptions(de, e, te)) {
                                final int ls = ds == null ? 0 : (ts == 0 ? xs : ds.turnSteps(ts) + xs);
                                final int le = de == null ? 0 : (te == 0 ? xe : de.turnSteps(te) + xe);
                                final int px = s.pos().getX() + (ds == null ? 0 : ds.dx * ls);
                                final int pz = s.pos().getZ() + (ds == null ? 0 : ds.dz * ls);
                                final int qx = e.pos().getX() + (de == null ? 0 : de.dx * le);
                                final int qz = e.pos().getZ() + (de == null ? 0 : de.dz * le);
                                for (final boolean diagFirst : new boolean[] {true, false}) {
                                    final List<Leg> legs = new ArrayList<>();
                                    if (ls > 0)
                                        legs.add(new Leg(ds, ls));
                                    legs.addAll(middle(qx - px, qz - pz, diagFirst));
                                    if (le > 0)
                                        legs.add(new Leg(de.opposite(), le));
                                    List<Leg> merged = merge(legs);
                                    if (merged.isEmpty())
                                        continue;
                                    if (!stubsHold(merged, ds, ls, ts, de, le, te))
                                        continue;
                                    merged = fixShort(merged, ds != null && ts > 0, de != null && te > 0);
                                    if (merged == null || !valid(merged, maxLength))
                                        continue;
                                    if (ds != null && merged.get(0).dir != ds)
                                        continue;
                                    if (de != null && merged.get(merged.size() - 1).dir != de.opposite())
                                        continue;
                                    final double c = cost(merged);
                                    if (c < bestCost) {
                                        bestCost = c;
                                        best = merged;
                                    }
                                }
                            }
        if (best == null) {
            int len = Math.abs(e.pos().getX() - s.pos().getX()) + Math.abs(e.pos().getZ() - s.pos().getZ());
            err.append(len > maxLength ? "Route is longer than the " + maxLength + " block limit."
                : "No buildable track layout: the points are too close for the curves Create needs, or the joined track points the wrong way. Move a point further away.");
            return null;
        }
        final Layout layout = new Layout(best, s, e);
        buildRuns(layout);
        return layout;
    }

    private static List<Dir8> nullList() {
        final List<Dir8> l = new ArrayList<>();
        l.add(null);
        return l;
    }

    private static int[] turnOptions(final Dir8 d, final Anchor a) {
        if (d == null)
            return new int[] {0};
        return a.midTrack() ? new int[] {45, 90} : new int[] {0, 45, 90};
    }

    private static int[] extOptions(final Dir8 d, final Anchor a, final int turn) {
        if (d == null || a.midTrack())
            return new int[] {0};
        return turn == 0 ? new int[] {0, 8, 24} : new int[] {0, 8};
    }

    private static List<Leg> middle(final int dx, final int dz, final boolean diagFirst) {
        final int ax = Math.abs(dx), az = Math.abs(dz);
        final int d = Math.min(ax, az), o = Math.max(ax, az) - d;
        final List<Leg> out = new ArrayList<>();
        final Leg diag = d > 0 ? new Leg(Dir8.of(dx, dz), d) : null;
        final Leg orth = o > 0 ? new Leg(ax > az ? Dir8.of(dx, 0) : Dir8.of(0, dz), o) : null;
        if (diagFirst) {
            if (diag != null) out.add(diag);
            if (orth != null) out.add(orth);
        } else {
            if (orth != null) out.add(orth);
            if (diag != null) out.add(diag);
        }
        return out;
    }

    private static List<Leg> merge(final List<Leg> legs) {
        final List<Leg> out = new ArrayList<>();
        for (final Leg l : legs) {
            if (l.steps <= 0)
                continue;
            if (!out.isEmpty()) {
                final Leg last = out.get(out.size() - 1);
                if (last.dir == l.dir && !last.shifted() && !l.shifted()) {
                    out.set(out.size() - 1, new Leg(l.dir, last.steps + l.steps));
                    continue;
                }
            }
            out.add(l);
        }
        return out;
    }

    /** The anchor stubs must survive merging, with the heading change they were sized for. */
    private static boolean stubsHold(final List<Leg> legs, final Dir8 ds, final int ls, final int ts, final Dir8 de,
                                     final int le, final int te) {
        if (ds != null && ts > 0) {
            if (legs.size() < 2 || legs.get(0).dir != ds || legs.get(0).steps != ls || ds.turnTo(legs.get(1).dir) != ts)
                return false;
        }
        if (de != null && te > 0) {
            final int n = legs.size();
            if (n < 2 || legs.get(n - 1).dir != de.opposite() || legs.get(n - 1).steps != le
                || legs.get(n - 2).dir.turnTo(de.opposite()) != te)
                return false;
        }
        return true;
    }

    private static int kOut(final List<Leg> legs, final int i) {
        return i == 0 ? 0 : legs.get(i).dir.turnSteps(legs.get(i - 1).dir.turnTo(legs.get(i).dir));
    }

    private static int kIn(final List<Leg> legs, final int i) {
        return i == legs.size() - 1 ? 0 : legs.get(i).dir.turnSteps(legs.get(i).dir.turnTo(legs.get(i + 1).dir));
    }

    /** Smallest run of straight blocks this leg leaves; < 1 means the curves do not fit. */
    private static int minRun(final List<Leg> legs, final int i) {
        final Leg l = legs.get(i);
        final int ko = kOut(legs, i), ki = kIn(legs, i);
        if (!l.shifted())
            return l.steps - ko - ki + 1;
        final int free = l.steps - ko - ki - l.sm;
        return free < 0 ? free : Math.min(free / 2 + 1, free - free / 2 + 1);
    }

    /** Folds legs too short for their curves into an S-bend on the neighbouring leg(s). */
    private static List<Leg> fixShort(List<Leg> legs, final boolean lockFirst, final boolean lockLast) {
        for (int guard = 0; guard < 6; guard++) {
            int bad = -1;
            for (int i = 0; i < legs.size(); i++)
                if (minRun(legs, i) < 1) {
                    bad = i;
                    break;
                }
            if (bad < 0)
                return legs;
            if ((bad == 0 && lockFirst) || (bad == legs.size() - 1 && lockLast) || legs.get(bad).shifted())
                return null;
            final Leg p = bad > 0 ? legs.get(bad - 1) : null;
            final Leg q = bad < legs.size() - 1 ? legs.get(bad + 1) : null;
            if ((p != null && p.shifted()) || (q != null && q.shifted()))
                return null;
            if ((p != null && bad - 1 == 0 && lockFirst) || (q != null && bad + 1 == legs.size() - 1 && lockLast))
                return null;
            final Dir8 d;
            if (p != null && q != null) {
                if (p.dir != q.dir)
                    return null;
                d = p.dir;
            } else if (p != null)
                d = p.dir;
            else if (q != null)
                d = q.dir;
            else
                return null;
            final Leg b = legs.get(bad);
            final int wx = b.dir.dx * b.steps, wz = b.dir.dz * b.steps;
            final double ux = d.dx / d.len(), uz = d.dz / d.len();
            final double along = wx * ux + wz * uz;
            final double lateral = Math.abs(wx * uz - wz * ux);
            final double need = Math.max(4, 2 * lateral + 2);
            final int m = Math.max(1, (int) Math.ceil((need - along) / d.len()));
            final int steps = (p != null ? p.steps : 0) + (q != null ? q.steps : 0);
            final List<Leg> out = new ArrayList<>();
            for (int i = 0; i < legs.size(); i++) {
                if (i == bad - 1 || i == bad + 1)
                    continue;
                if (i == bad)
                    out.add(new Leg(d, steps, wx, wz, m));
                else
                    out.add(legs.get(i));
            }
            legs = out;
        }
        return null;
    }

    private static boolean valid(final List<Leg> legs, final int maxLength) {
        int total = 0;
        for (int i = 0; i < legs.size(); i++) {
            total += legs.get(i).steps;
            if (i > 0) {
                final int t = legs.get(i - 1).dir.turnTo(legs.get(i).dir);
                if (t == 0 || t > 90)
                    return false;
            }
            if (minRun(legs, i) < 1)
                return false;
        }
        return total <= maxLength;
    }

    private static double cost(final List<Leg> legs) {
        double c = 0;
        for (final Leg l : legs)
            c += l.steps * l.dir.len() + (l.shifted() ? 3 : 0);
        return c + 6 * (legs.size() - 1);
    }

    /** Turns the legs into straight runs joined by curve links, and lays the centerline samples. */
    private static void buildRuns(final Layout lay) {
        final List<Leg> legs = lay.legs;
        int x = lay.start.pos().getX(), z = lay.start.pos().getZ();
        Link pending = null;
        for (int i = 0; i < legs.size(); i++) {
            final Leg l = legs.get(i);
            final Dir8 d = l.dir;
            final int ko = kOut(legs, i), ki = kIn(legs, i);
            if (!l.shifted()) {
                final Run r = run(x + d.dx * ko, z + d.dz * ko, d, l.steps - ko - ki + 1);
                pending = attach(lay, pending, r);
            } else {
                final int free = l.steps - ko - ki - l.sm;
                final int a1 = ko + free / 2;
                final Run r1 = run(x + d.dx * ko, z + d.dz * ko, d, a1 - ko + 1);
                pending = attach(lay, pending, r1);
                final int bx = x + d.dx * (a1 + l.sm) + l.sx, bz = z + d.dz * (a1 + l.sm) + l.sz;
                final Link s = new Link();
                s.a1 = d;
                s.a2 = d.opposite();
                pending = s;
                final Run r2 = run(bx, bz, d, l.steps - ki - (a1 + l.sm) + 1);
                pending = attach(lay, pending, r2);
            }
            if (i < legs.size() - 1) {
                final Link t = new Link();
                t.a1 = d;
                t.a2 = legs.get(i + 1).dir.opposite();
                pending = t;
            }
            x += d.dx * l.steps + l.sx;
            z += d.dz * l.steps + l.sz;
        }
        // centerline samples
        double s = 0;
        int lastX = Integer.MIN_VALUE, lastZ = 0;
        for (int i = 0; i < lay.runs.size(); i++) {
            final Run r = lay.runs.get(i);
            r.sample = new int[r.n];
            for (int k = 0; k < r.n; k++) {
                final int bx = r.x + r.dir.dx * k, bz = r.z + r.dir.dz * k;
                if (lastX != Integer.MIN_VALUE)
                    s += Math.sqrt((bx - lastX) * (bx - lastX) + (bz - lastZ) * (bz - lastZ));
                r.sample[k] = addSample(lay, bx, bz, s);
                lastX = bx;
                lastZ = bz;
            }
            if (i < lay.links.size()) {
                final Link ln = lay.links.get(i);
                ln.s1 = lay.samples.size() - 1;
                final int steps = Math.max(Math.abs(ln.x2 - ln.x1), Math.abs(ln.z2 - ln.z1));
                for (int k = 1; k < steps; k++) {
                    final int bx = ln.x1 + Math.round((ln.x2 - ln.x1) * (float) k / steps);
                    final int bz = ln.z1 + Math.round((ln.z2 - ln.z1) * (float) k / steps);
                    s += Math.sqrt((bx - lastX) * (bx - lastX) + (bz - lastZ) * (bz - lastZ));
                    addSample(lay, bx, bz, s);
                    lastX = bx;
                    lastZ = bz;
                }
                ln.s2 = lay.samples.size(); // index of the next run's first sample
            }
        }
    }

    private static int addSample(final Layout lay, final int x, final int z, final double s) {
        lay.samples.add(new int[] {x, z});
        lay.arc.add(s);
        return lay.samples.size() - 1;
    }

    private static Run run(final int x, final int z, final Dir8 d, final int n) {
        final Run r = new Run();
        r.x = x;
        r.z = z;
        r.dir = d;
        r.n = n;
        return r;
    }

    private static Link attach(final Layout lay, final Link pending, final Run r) {
        if (pending != null) {
            final Run prev = lay.runs.get(lay.runs.size() - 1);
            pending.x1 = prev.x + prev.dir.dx * (prev.n - 1);
            pending.z1 = prev.z + prev.dir.dz * (prev.n - 1);
            pending.x2 = r.x;
            pending.z2 = r.z;
            lay.links.add(pending);
        }
        lay.runs.add(r);
        return null;
    }

    // --------------------------------------------------------------------------------------------- profile

    /**
     * Fits heights to the layout and emits pieces. {@code cols[i]} is the terrain under centerline sample i (only
     * every {@code sampleSpacing}-th entry plus the ends need to be real; nulls are interpolated).
     */
    public static Result finish(final Layout lay, final TerrainSampler.Column[] colsIn, final Settings st,
                                final TrackMaterial material, final String trackItem, final String bridgeItem,
                                final String liningItem) {
        final int n = lay.samples.size();
        final TerrainSampler.Column[] cols = interpolate(colsIn);
        final double[] arc = new double[n];
        for (int i = 0; i < n; i++)
            arc[i] = lay.arc.get(i);
        final int yS = lay.start.pos().getY(), yE = lay.end.pos().getY();
        final double g = st.grade() * 0.9; // slack for integer rounding
        final double total = arc[n - 1];
        if (Math.abs(yE - yS) > g * total)
            return Result.fail("Too steep: the ends are " + Math.abs(yE - yS) + " blocks apart in height, which needs at least "
                + (int) Math.ceil(Math.abs(yE - yS) / g) + " blocks of route at the configured grade.");

        // target: one block above ground, or above water with clearance
        final double[] t = new double[n];
        final double[] w = new double[n];
        final double[] floorMin = new double[n];
        for (int i = 0; i < n; i++) {
            final TerrainSampler.Column c = cols[i];
            final double land = c.ground() + 1;
            if (c.wet()) {
                floorMin[i] = c.water() + 2 + st.waterClearance();
                t[i] = Math.max(land, floorMin[i]);
            } else {
                floorMin[i] = Double.NEGATIVE_INFINITY;
                t[i] = land;
            }
            w[i] = c.mountain() ? st.mountainBridgePreference() : st.bridgePreference();
        }
        final double[] ts = smooth(t, arc, st.smoothingRadius());
        final double[] ws = smooth(w, arc, Math.max(16, st.smoothingRadius() * 2));
        // Lipschitz envelopes: U never rises above the ground (tunnels), L never dips below it (bridges)
        final double[] u = ts.clone();
        final double[] l = ts.clone();
        for (int i = 1; i < n; i++) {
            u[i] = Math.min(u[i], u[i - 1] + g * (arc[i] - arc[i - 1]));
            l[i] = Math.max(l[i], l[i - 1] - g * (arc[i] - arc[i - 1]));
        }
        for (int i = n - 2; i >= 0; i--) {
            u[i] = Math.min(u[i], u[i + 1] + g * (arc[i + 1] - arc[i]));
            l[i] = Math.max(l[i], l[i + 1] - g * (arc[i + 1] - arc[i]));
        }
        final double[] y = new double[n];
        for (int i = 0; i < n; i++)
            y[i] = u[i] + ws[i] * (l[i] - u[i]);
        // keep bridges above water, then restore the grade
        for (int i = 0; i < n; i++)
            y[i] = Math.max(y[i], floorMin[i]);
        for (int i = 1; i < n; i++)
            y[i] = Math.max(y[i], y[i - 1] - g * (arc[i] - arc[i - 1]));
        for (int i = n - 2; i >= 0; i--)
            y[i] = Math.max(y[i], y[i + 1] - g * (arc[i + 1] - arc[i]));
        // fixed ends, world limits
        for (int i = 0; i < n; i++) {
            final double lo = Math.max(yS - g * arc[i], yE - g * (total - arc[i]));
            final double hi = Math.min(yS + g * arc[i], yE + g * (total - arc[i]));
            y[i] = clamp(y[i], lo, hi);
            y[i] = clamp(y[i], st.minY(), st.maxY());
        }

        // ---- integer heights: links (flat curves), then runs
        final List<Run> runs = lay.runs;
        final List<Link> links = lay.links;
        final int nodes = runs.size() + 1;
        final int[] cap = new int[runs.size()];
        for (int i = 0; i < runs.size(); i++)
            cap[i] = capacity(runs.get(i), st.grade());
        final int[] want = new int[nodes];
        want[0] = yS;
        want[nodes - 1] = yE;
        for (int j = 0; j < links.size(); j++) {
            final Link ln = links.get(j);
            want[j + 1] = (int) Math.round((y[ln.s1] + y[Math.min(ln.s2, n - 1)]) / 2);
        }
        final int[] nodeY = fit(want, cap);
        if (nodeY == null)
            return Result.fail("Too steep: the curves leave too little straight track to climb the height difference. Pick ends at closer heights.");
        for (int j = 0; j < links.size(); j++)
            links.get(j).y = nodeY[j + 1];

        final List<Piece> pieces = new ArrayList<>();
        final Set<Long> trackBlocks = new HashSet<>();
        long curveTracks = 0;
        int bridgeLen = 0, tunnelLen = 0;
        double deck = 0, pillars = 0, lining = 0;
        for (int i = 0; i < runs.size(); i++) {
            final Run r = runs.get(i);
            r.y0 = nodeY[i];
            r.y1 = nodeY[i + 1];
            final int[] seg = segments(r);
            final int[] by = new int[seg.length];
            final int[] bcap = new int[seg.length - 1];
            for (int k = 0; k < seg.length; k++)
                by[k] = (int) Math.round(y[r.sample[seg[k]]]);
            by[0] = r.y0;
            by[seg.length - 1] = r.y1;
            for (int k = 0; k + 1 < seg.length; k++)
                bcap[k] = maxRise(seg[k + 1] - seg[k], r.dir, st.grade());
            final int[] fy = fit(by, bcap);
            if (fy == null)
                return Result.fail("Internal: run heights do not fit (" + r.y0 + " -> " + r.y1 + ").");
            // per-block kinds
            final byte[] kinds = new byte[r.n];
            for (int k = 0; k < r.n; k++) {
                final int seg0 = segmentOf(seg, k);
                final double yy = fy[seg0] + (fy[seg0 + 1] - fy[seg0]) * (seg[seg0 + 1] == seg[seg0] ? 0
                    : (k - seg[seg0]) / (double) (seg[seg0 + 1] - seg[seg0]));
                kinds[k] = kind(cols[r.sample[k]], yy, st);
                final double gap = yy - 1 - cols[r.sample[k]].ground();
                switch (kinds[k]) {
                    case Piece.BRIDGE -> {
                        bridgeLen++;
                        deck += 3;
                        if (k % st.pillarSpacing() == 0)
                            pillars += Math.min(Math.max(0, gap - 1), st.maxPillarDepth());
                    }
                    case Piece.TUNNEL -> {
                        tunnelLen++;
                        lining += (2.0 * st.tunnelHeight() + 3) * 0.15;
                    }
                    default -> {
                        if (gap > 0)
                            deck += 3 * gap;
                    }
                }
            }
            // emit: maximal flat stretches as straights, slopes as curves
            int k = 0;
            while (k + 1 < seg.length) {
                if (fy[k] == fy[k + 1]) {
                    int e = k + 1;
                    while (e + 1 < seg.length && fy[e] == fy[e + 1])
                        e++;
                    pieces.add(straight(r, seg[k], seg[e] - seg[k] + 1, fy[k], kinds, trackBlocks));
                    k = e;
                } else {
                    pieces.add(straight(r, seg[k], 1, fy[k], kinds, trackBlocks));
                    pieces.add(straight(r, seg[k + 1], 1, fy[k + 1], kinds, trackBlocks));
                    final BlockPos a = new BlockPos(r.x + r.dir.dx * seg[k], fy[k], r.z + r.dir.dz * seg[k]);
                    final BlockPos b = new BlockPos(r.x + r.dir.dx * seg[k + 1], fy[k + 1], r.z + r.dir.dz * seg[k + 1]);
                    final Piece.Curve c = new Piece.Curve(a, r.dir, b, r.dir.opposite(), kinds[(seg[k] + seg[k + 1]) / 2]);
                    pieces.add(c);
                    curveTracks += c.toBezier(material).getTrackItemCost();
                    k++;
                }
            }
            if (seg.length == 1)
                pieces.add(straight(r, 0, 1, r.y0, kinds, trackBlocks));
            if (i < links.size()) {
                final Link ln = links.get(i);
                final Run next = runs.get(i + 1);
                final BlockPos a = new BlockPos(ln.x1, ln.y, ln.z1);
                final BlockPos b = new BlockPos(ln.x2, ln.y, ln.z2);
                final int mid = Math.min(n - 1, (ln.s1 + ln.s2) / 2);
                final Piece.Curve c = new Piece.Curve(a, ln.a1, b, ln.a2, kind(cols[mid], ln.y, st));
                pieces.add(c);
                curveTracks += c.toBezier(material).getTrackItemCost();
                if (c.kind() == Piece.BRIDGE)
                    deck += 3 * (ln.s2 - ln.s1);
                if (next == null)
                    break;
            }
        }
        final Map<String, Long> est = new LinkedHashMap<>();
        est.merge(trackItem, (long) trackBlocks.size() + curveTracks, Long::sum);
        est.merge(bridgeItem, Math.round(deck + pillars), Long::sum);
        if (lining > 0)
            est.merge(liningItem, Math.round(lining), Long::sum);
        est.values().removeIf(v -> v <= 0);
        return new Result(pieces, lay.length(), bridgeLen, tunnelLen, est, null);
    }

    private static Piece.Straight straight(final Run r, final int from, final int count, final int y,
                                           final byte[] runKinds, final Set<Long> trackBlocks) {
        final byte[] k = new byte[count];
        for (int i = 0; i < count; i++) {
            k[i] = runKinds[from + i];
            trackBlocks.add(BlockPos.asLong(r.x + r.dir.dx * (from + i), y, r.z + r.dir.dz * (from + i)));
        }
        return new Piece.Straight(new BlockPos(r.x + r.dir.dx * from, y, r.z + r.dir.dz * from), r.dir, count, k);
    }

    static byte kind(final TerrainSampler.Column c, final double trackY, final Settings st) {
        final double bed = trackY - 1;
        if (c.wet() && bed > c.water())
            return Piece.BRIDGE;
        if (bed - c.ground() > st.bridgeThreshold())
            return Piece.BRIDGE;
        if (c.ground() - bed >= 2)
            return Piece.TUNNEL;
        return Piece.GROUND;
    }

    /** Segment boundaries (block offsets) of a run: ~16 blocks (12 diagonal), never a sliver under 6. */
    static int[] segments(final Run r) {
        final int h = r.n - 1;
        final int seg = r.dir.diagonal() ? 12 : 16;
        final List<Integer> b = new ArrayList<>();
        b.add(0);
        if (h > 0) {
            int k = h / seg, rem = h % seg;
            for (int i = 1; i <= k; i++)
                b.add(i * seg);
            if (rem > 0) {
                if (rem < 6 && k > 0)
                    b.set(b.size() - 1, h);
                else
                    b.add(h);
            }
        }
        return b.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int segmentOf(final int[] seg, final int k) {
        for (int i = 0; i + 1 < seg.length; i++)
            if (k <= seg[i + 1])
                return i;
        return Math.max(0, seg.length - 2);
    }

    static int capacity(final Run r, final double grade) {
        final int[] seg = segments(r);
        int c = 0;
        for (int i = 0; i + 1 < seg.length; i++)
            c += maxRise(seg[i + 1] - seg[i], r.dir, grade);
        return c;
    }

    /**
     * Largest rise a single sloped curve over {@code steps} blocks may have: within the grade, and accepted by
     * Create's own rule (horizontal length >= max(4h or 3h for h >= 4, 6)).
     */
    static int maxRise(final int steps, final Dir8 d, final double grade) {
        final double h = steps * d.len();
        int best = 0;
        for (int dy = 1; dy <= 16; dy++) {
            final double need = Math.max(dy < 4 ? dy * 4 : dy * 3, 6);
            if (need <= h && dy <= Math.floor(h * grade) && h <= 34)
                best = dy;
        }
        return best;
    }

    /**
     * Integer heights for a chain of nodes with fixed ends and a per-step rise limit, staying as close to
     * {@code want} as the limits allow. Null when the ends cannot be reached.
     */
    static int[] fit(final int[] want, final int[] cap) {
        final int n = want.length;
        final int[] out = new int[n];
        final long[] fromStart = new long[n];
        final long[] fromEnd = new long[n];
        for (int i = 1; i < n; i++)
            fromStart[i] = fromStart[i - 1] + cap[i - 1];
        for (int i = n - 2; i >= 0; i--)
            fromEnd[i] = fromEnd[i + 1] + cap[i];
        if (Math.abs(want[n - 1] - want[0]) > fromStart[n - 1])
            return null;
        out[0] = want[0];
        for (int i = 1; i < n; i++) {
            long lo = Math.max(want[0] - fromStart[i], want[n - 1] - fromEnd[i]);
            long hi = Math.min(want[0] + fromStart[i], want[n - 1] + fromEnd[i]);
            lo = Math.max(lo, out[i - 1] - cap[i - 1]);
            hi = Math.min(hi, out[i - 1] + cap[i - 1]);
            if (lo > hi)
                return null;
            out[i] = (int) Math.max(lo, Math.min(hi, i == n - 1 ? want[n - 1] : want[i]));
        }
        return out[n - 1] == want[n - 1] ? out : null;
    }

    private static TerrainSampler.Column[] interpolate(final TerrainSampler.Column[] in) {
        final TerrainSampler.Column[] out = in.clone();
        int prev = -1;
        for (int i = 0; i < out.length; i++) {
            if (in[i] == null)
                continue;
            if (prev >= 0 && i - prev > 1) {
                final TerrainSampler.Column a = in[prev], b = in[i];
                for (int k = prev + 1; k < i; k++) {
                    final double f = (k - prev) / (double) (i - prev);
                    final int ground = (int) Math.round(a.ground() + (b.ground() - a.ground()) * f);
                    final int water = a.wet() && b.wet() ? (int) Math.round(a.water() + (b.water() - a.water()) * f)
                        : (f < .5 ? a.water() : b.water());
                    out[k] = new TerrainSampler.Column(ground,
                        water != TerrainSampler.NO_WATER && water <= ground ? TerrainSampler.NO_WATER : water,
                        f < .5 ? a.mountain() : b.mountain());
                }
            }
            prev = i;
        }
        return out;
    }

    private static double[] smooth(final double[] v, final double[] arc, final double radius) {
        final int n = v.length;
        if (radius <= 0)
            return v.clone();
        final double[] out = new double[n];
        final double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++)
            prefix[i + 1] = prefix[i] + v[i];
        int lo = 0, hi = 0;
        for (int i = 0; i < n; i++) {
            while (arc[i] - arc[lo] > radius)
                lo++;
            while (hi + 1 < n && arc[hi + 1] - arc[i] <= radius)
                hi++;
            out[i] = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
        }
        return out;
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return lo > hi ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, v));
    }

    private RoutePlanner() {}
}
