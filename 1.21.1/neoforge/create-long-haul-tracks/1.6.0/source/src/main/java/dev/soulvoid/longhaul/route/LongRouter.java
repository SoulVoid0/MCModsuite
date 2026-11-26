package dev.soulvoid.longhaul.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import net.minecraft.core.BlockPos;

/**
 * Plans routes of any length (100,000 blocks and more) in two stages, the way a railway is surveyed: first a coarse
 * corridor over the whole distance (a least-cost path over a grid of terrain heights a few hundred blocks apart,
 * which prefers valleys, gentle slopes and passes, and charges a climb steeper than the grade for the winding line
 * it will need), then the detailed {@link TerrainRouter} search in sections of a couple of thousand blocks between
 * waypoints on that corridor, all sections at once on the worker threads. A section that fails is retried with its
 * waypoint moved along the corridor, then without it. Short routes go straight to the detailed search.
 */
public final class LongRouter {

    /** One section's waypoint: a dry, fairly level spot on the corridor, and the heading the line passes it with. */
    private record Waypoint(BlockPos pos, Dir8 dir, int node, int tries) {}

    private final Anchor start, end;
    private final RoutePlanner.Settings st;
    private final TerrainRouter.Terrain terrain;
    private final PlanProgress progress;
    private final Executor exec;
    private final int cell, section, grid;
    private final double climbPerBlock;
    private final Map<Long, TerrainSampler.Column> cache = new ConcurrentHashMap<>();

    private LongRouter(final Anchor s, final Anchor e, final RoutePlanner.Settings st, final TerrainRouter.Terrain terrain,
                       final PlanProgress progress, final Executor exec) {
        this.start = s;
        this.end = e;
        this.st = st;
        this.terrain = terrain;
        this.progress = progress;
        this.exec = exec;
        this.cell = Math.max(8, st.cell() / 4 * 4);
        this.section = cell * SECTION_CELLS;
        final double d = Math.hypot(e.pos().getX() - s.pos().getX(), e.pos().getZ() - s.pos().getZ());
        // about 400 grid steps across the distance, at least two search cells
        this.grid = cell * Math.max(2, (int) Math.round(d / 400 / cell));
        this.climbPerBlock = Math.max(0.01, 0.7 * st.grade());
    }

    /** Sections are this many search cells long (2,560 blocks on JJThunder, 1,280 elsewhere). */
    private static final int SECTION_CELLS = 80;

    /** Searches a route of any length; null with {@code err} filled when none was found. */
    public static TerrainRouter.Path search(final Anchor s, final Anchor e, final RoutePlanner.Settings st,
                                            final TerrainRouter.Terrain terrain, final StringBuilder err,
                                            final PlanProgress progress, final Executor exec) {
        final LongRouter r = new LongRouter(s, e, st, terrain, progress, exec);
        final double d = Math.hypot(e.pos().getX() - s.pos().getX(), e.pos().getZ() - s.pos().getZ());
        if (d <= 1.5 * r.section && !Boolean.getBoolean("lht.forceSections"))
            return TerrainRouter.search(s, e, st, terrain, err, progress);
        return r.run(err);
    }

    private TerrainSampler.Column col(final int x, final int z) {
        final long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        TerrainSampler.Column c = cache.get(k);
        if (c == null) {
            c = terrain.at(x, z);
            cache.put(k, c);
        }
        return c;
    }

    /** Height a line over this column should run at: on the ground, or clear of water. */
    private int level(final TerrainSampler.Column c) {
        int t = c.ground() + 1;
        if (c.wet())
            t = Math.max(t, c.water() + 2 + st.waterClearance());
        return t;
    }

    // ------------------------------------------------------------------------------------ coarse corridor

    private final class Node implements Comparable<Node> {
        final int i, j;
        final double g, f;
        final Node parent;

        Node(final int i, final int j, final double g, final double f, final Node parent) {
            this.i = i;
            this.j = j;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        int x() {
            return start.pos().getX() + i * grid;
        }

        int z() {
            return start.pos().getZ() + j * grid;
        }

        @Override
        public int compareTo(final Node o) {
            return Double.compare(f, o.f);
        }
    }

    private int coarseY(final int x, final int z) {
        return level(col(x, z));
    }

    /**
     * Cost of a coarse step: its length, or the length of line its climb needs at the grade when that is longer
     * (the winding a climb needs, with extra for the turns), plus ground that rises or falls away from the straight
     * line between the two points (a ridge to tunnel or a valley the line must go down into), plus water.
     */
    private double coarseCost(final int x1, final int z1, final int y1, final int x2, final int z2, final int y2) {
        final double len = Math.hypot(x2 - x1, z2 - z1);
        final double need = Math.abs(y2 - y1) / climbPerBlock;
        double c = Math.max(len, need) + 0.6 * Math.max(0, need - len);
        double rough = 0;
        boolean wet = false;
        for (int k = 1; k <= 3; k += 2) {
            final double t = k / 4.0;
            final int x = (int) Math.round(x1 + (x2 - x1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
            final TerrainSampler.Column cc = col(x, z);
            wet |= cc.wet();
            final double dev = level(cc) - (y1 + (y2 - y1) * t);
            rough = Math.max(rough, Math.abs(dev));
        }
        if (rough > 12)
            c += len * Math.min(3, (rough - 12) / 48);
        if (wet)
            c += len * 0.5 * st.waterBridgeCost();
        return c;
    }

    private double coarseH(final int x, final int z, final int y) {
        final int ex = end.pos().getX(), ez = end.pos().getZ();
        return Math.max(Math.hypot(ex - x, ez - z), Math.abs(end.pos().getY() - y) / climbPerBlock);
    }

    /** The corridor as a list of (x, z, y) points from the start to the end, or null. */
    private List<int[]> corridor(final StringBuilder err) {
        final int sx = start.pos().getX(), sz = start.pos().getZ(), ex = end.pos().getX(), ez = end.pos().getZ();
        final double d = Math.hypot(ex - sx, ez - sz);
        final int ei = Math.round((ex - sx) / (float) grid), ej = Math.round((ez - sz) / (float) grid);
        final double maxSum = d * 1.8 + 8 * grid;
        final double[] weights = {1.5, 3, 6};
        final int budget = 400_000;
        for (final double w : weights) {
            final PriorityQueue<Node> open = new PriorityQueue<>();
            final Map<Long, Double> best = new HashMap<>();
            open.add(new Node(0, 0, 0, w * coarseH(sx, sz, start.pos().getY()), null));
            best.put(0L, 0.0);
            int expanded = 0;
            final double h0 = coarseH(sx, sz, start.pos().getY());
            while (!open.isEmpty()) {
                final Node n = open.poll();
                final Double b = best.get(key(n.i, n.j));
                if (b != null && b < n.g - 1e-9)
                    continue;
                if ((++expanded & 255) == 0) {
                    if (progress.cancelled()) {
                        err.append("Planning cancelled.");
                        return null;
                    }
                    final double left = coarseH(n.x(), n.z(), nodeY(n));
                    progress.report(0.12 * Math.max(1 - left / Math.max(1, h0), expanded / (double) budget));
                }
                if (expanded > budget)
                    break;
                if (n.i == ei && n.j == ej) {
                    final List<int[]> out = new ArrayList<>();
                    for (Node c = n; c != null; c = c.parent)
                        out.add(0, new int[] {c.x(), c.z(), nodeY(c)});
                    out.set(0, new int[] {sx, sz, start.pos().getY()});
                    out.set(out.size() - 1, new int[] {ex, ez, end.pos().getY()});
                    return out;
                }
                final int x1 = n.x(), z1 = n.z(), y1 = nodeY(n);
                prefetch(x1, z1);
                for (final Dir8 dir : Dir8.values()) {
                    final int i = n.i + dir.dx, j = n.j + dir.dz;
                    final int x2 = sx + i * grid, z2 = sz + j * grid;
                    if (Math.hypot(x2 - sx, z2 - sz) + Math.hypot(x2 - ex, z2 - ez) > maxSum)
                        continue;
                    final int y2 = i == ei && j == ej ? end.pos().getY() : coarseY(x2, z2);
                    final double g = n.g + coarseCost(x1, z1, y1, x2, z2, y2);
                    final long k = key(i, j);
                    final Double ob = best.get(k);
                    if (ob != null && ob <= g)
                        continue;
                    best.put(k, g);
                    open.add(new Node(i, j, g, g + w * coarseH(x2, z2, y2), n));
                }
            }
        }
        err.append("No corridor found between the points within the search budget.");
        return null;
    }

    /** Samples the columns a node's eight coarse steps will need, on all worker threads at once. */
    private void prefetch(final int x1, final int z1) {
        final List<long[]> need = new ArrayList<>();
        for (final Dir8 dir : Dir8.values()) {
            final int x2 = x1 + dir.dx * grid, z2 = z1 + dir.dz * grid;
            for (int k = 1; k <= 4; k += k == 1 ? 2 : 1) {
                final int x = (int) Math.round(x1 + (x2 - x1) * k / 4.0), z = (int) Math.round(z1 + (z2 - z1) * k / 4.0);
                if (!cache.containsKey(((long) x << 32) ^ (z & 0xFFFFFFFFL)))
                    need.add(new long[] {x, z});
            }
        }
        if (need.size() < 4) {
            for (final long[] p : need)
                col((int) p[0], (int) p[1]);
            return;
        }
        final List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (final long[] p : need)
            fs.add(CompletableFuture.runAsync(() -> col((int) p[0], (int) p[1]), exec));
        CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();
    }

    private int nodeY(final Node n) {
        return n.i == 0 && n.j == 0 ? start.pos().getY() : coarseY(n.x(), n.z());
    }

    private static long key(final int i, final int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------------------------ sections

    /** How suitable a corridor point is as a waypoint (lower is better), or infinity when unusable. */
    private double waypointScore(final int x, final int z, final Dir8 d) {
        final TerrainSampler.Column c = col(x, z);
        if (c.wet())
            return Double.POSITIVE_INFINITY;
        final int y = c.ground();
        double worst = 0;
        // the line passes straight through: the ground along and across it should be close to level
        for (final int k : new int[] {-2, -1, 1, 2}) {
            final TerrainSampler.Column a = col(x + d.dx * cell * k / 2, z + d.dz * cell * k / 2);
            if (a.wet())
                return Double.POSITIVE_INFINITY;
            worst = Math.max(worst, Math.abs(a.ground() - y) / (Math.abs(k) * cell / 2.0 * d.len()));
        }
        double side = 0;
        for (final int k : new int[] {-1, 1}) {
            final TerrainSampler.Column a = col(x - d.dz * 8 * k, z + d.dx * 8 * k);
            side = Math.max(side, Math.abs(a.ground() - y) / 8.0);
        }
        return worst / Math.max(0.01, st.grade()) + 0.5 * side;
    }

    private Dir8 heading(final List<int[]> cor, final int i) {
        final int[] a = cor.get(Math.max(0, i - 1)), b = cor.get(Math.min(cor.size() - 1, i + 1));
        final double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
        final int o = Math.floorMod((int) Math.round(ang / (Math.PI / 4)), 8);
        return Dir8.values()[o];
    }

    /** The best waypoint within {@code reach} corridor points of point {@code i}, skipping {@code avoid}. */
    private Waypoint waypointNear(final List<int[]> cor, final int i, final int reach, final int avoid) {
        Waypoint best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int k = Math.max(1, i - reach); k <= Math.min(cor.size() - 2, i + reach); k++) {
            if (k == avoid)
                continue;
            final Dir8 d = heading(cor, k);
            final int[] p = cor.get(k);
            final double sc = waypointScore(p[0], p[1], d) + 0.15 * Math.abs(k - i);
            if (sc < bestScore) {
                bestScore = sc;
                best = new Waypoint(new BlockPos(p[0], col(p[0], p[1]).ground() + 1, p[1]), d, k, 0);
            }
        }
        return best;
    }

    private TerrainRouter.Path run(final StringBuilder err) {
        final long t0 = System.nanoTime();
        final List<int[]> cor = corridor(err);
        if (cor == null)
            return null;
        // corridor arc length, then a waypoint about every section length
        final double[] arc = new double[cor.size()];
        for (int i = 1; i < cor.size(); i++)
            arc[i] = arc[i - 1] + Math.hypot(cor.get(i)[0] - cor.get(i - 1)[0], cor.get(i)[1] - cor.get(i - 1)[1]);
        final double total = arc[arc.length - 1];
        final int parts = Math.max(1, (int) Math.round(total / section));
        final List<Waypoint> wps = new ArrayList<>();
        final int reach = Math.max(1, (int) (section * 0.15 / grid));
        for (int p = 1; p < parts; p++) {
            final double at = total * p / parts;
            int i = 1;
            while (i < arc.length - 1 && arc[i] < at)
                i++;
            final Waypoint w = waypointNear(cor, i, reach, -1);
            if (w != null && (wps.isEmpty() || w.node > wps.get(wps.size() - 1).node))
                wps.add(w);
        }
        if (DEBUG)
            com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] corridor {} points ({} grid, {} blocks) in {} ms, {} sections, {} samples",
                cor.size(), grid, (int) total, (System.nanoTime() - t0) / 1_000_000, wps.size() + 1, cache.size());
        progress.report(0.12);

        // sections between consecutive waypoints, all searched at once; a failed one is retried with its far
        // waypoint moved along the corridor, then without it (joined to the next section)
        final Map<String, TerrainRouter.Path> done = new HashMap<>();
        final Map<String, String> failed = new HashMap<>();
        for (int round = 0; round < 20; round++) {
            final int n = wps.size() + 1;
            final List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < n; i++)
                if (!done.containsKey(sectionKey(wps, i)))
                    todo.add(i);
            if (todo.isEmpty()) {
                final List<TerrainRouter.Path> paths = new ArrayList<>();
                for (int i = 0; i < n; i++)
                    paths.add(done.get(sectionKey(wps, i)));
                final StringBuilder jerr = new StringBuilder();
                final TerrainRouter.Path joined = join(paths, jerr);
                if (joined != null)
                    return joined;
                if (badJoin <= 0) {
                    err.append(jerr);
                    return null;
                }
                // two sections meet where the curves do not fit: search across that waypoint in one go instead
                if (DEBUG)
                    com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] sections {} and {} do not join; dropping waypoint {}",
                        badJoin - 1, badJoin, wps.get(badJoin - 1).pos);
                wps.remove(badJoin - 1);
                continue;
            }
            final double[] frac = new double[n];
            final double[] share = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                share[i] = done.containsKey(sectionKey(wps, i)) ? 0 : sectionLength(wps, i);
                sum += share[i];
            }
            final double base = Math.max(0.12, progress.fraction()), span = Math.max(0, 0.88 - base);
            final double sumF = sum;
            final List<CompletableFuture<Void>> fs = new ArrayList<>();
            for (final int i : todo) {
                final Anchor a = i == 0 ? start : new Anchor(wps.get(i - 1).pos, List.of(wps.get(i - 1).dir), false, false);
                final Anchor b = i == n - 1 ? end : new Anchor(wps.get(i).pos, List.of(wps.get(i).dir.opposite()), false, false);
                final String key = sectionKey(wps, i);
                final PlanProgress part = progress.part(f -> {
                    synchronized (frac) {
                        frac[i] = Math.max(frac[i], Math.min(1, f / 0.9));
                        double t = 0;
                        for (int k = 0; k < n; k++)
                            t += frac[k] * share[k];
                        progress.report(base + span * t / Math.max(1, sumF));
                    }
                });
                final boolean last = i == n - 1;
                fs.add(CompletableFuture.runAsync(() -> {
                    final StringBuilder why = new StringBuilder();
                    final TerrainRouter.Path p = TerrainRouter.search(a, b, st, terrain, why, part, i > 0, !last);
                    synchronized (done) {
                        if (p != null && (p.end() == null || last))
                            done.put(key, p);
                        else
                            failed.put(key, why.toString().trim());
                        if (DEBUG && p == null)
                            com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] section {} failed: {}", key, why);
                    }
                }, exec));
            }
            CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).join();
            if (progress.cancelled()) {
                err.append("Planning cancelled.");
                return null;
            }
            if (DEBUG)
                com.mojang.logging.LogUtils.getLogger().info("[LHT dbg] round {}: {} sections searched, {} failed, {} s",
                    round, todo.size(), todo.stream().filter(i -> !done.containsKey(sectionKey(wps, i))).count(),
                    (System.nanoTime() - t0) / 1_000_000_000);
            // fix failed sections: move or drop the waypoint at their far end (or near end, for the last section)
            boolean changed = false;
            final List<Integer> bad = new ArrayList<>();
            for (int i = 0; i < n; i++)
                if (!done.containsKey(sectionKey(wps, i)))
                    bad.add(i);
            // the waypoints at both ends of a failed section are suspect (a line may be unable to leave one
            // straight ahead as well as to reach one): move each forward, then back, then drop it
            final java.util.Set<Integer> touched = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
            for (final int i : bad) {
                if (i < wps.size())
                    touched.add(i);
                if (i > 0)
                    touched.add(i - 1);
            }
            for (final int w : touched) {
                final Waypoint old = wps.get(w);
                final Waypoint near = old.tries < 2 ? waypointNear(cor, old.node + (old.tries == 0 ? reach : -reach), reach, old.node) : null;
                final int lo = w > 0 ? wps.get(w - 1).node : 0, hi = w + 1 < wps.size() ? wps.get(w + 1).node : cor.size() - 1;
                if (near != null && near.node > lo && near.node < hi && !near.pos.equals(old.pos))
                    wps.set(w, new Waypoint(near.pos, near.dir, near.node, old.tries + 1));
                else
                    wps.remove(w);
                changed = true;
            }
            if (!changed)
                break;
        }
        final int n = wps.size() + 1;
        final List<TerrainRouter.Path> paths = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final TerrainRouter.Path p = done.get(sectionKey(wps, i));
            if (p == null) {
                final String why = failed.getOrDefault(sectionKey(wps, i), "");
                err.append("No route found for the part of the line from ")
                    .append(i == 0 ? start.pos().toShortString() : wps.get(i - 1).pos.toShortString()).append(" to ")
                    .append(i == n - 1 ? end.pos().toShortString() : wps.get(i).pos.toShortString()).append(". ").append(why);
                return null;
            }
            paths.add(p);
        }
        return join(paths, err);
    }

    private static String sectionKey(final List<Waypoint> wps, final int i) {
        final String a = i == 0 ? "S" : wps.get(i - 1).pos.toShortString() + wps.get(i - 1).dir;
        final String b = i == wps.size() ? "E" : wps.get(i).pos.toShortString() + wps.get(i).dir;
        return a + ">" + b;
    }

    private double sectionLength(final List<Waypoint> wps, final int i) {
        final BlockPos a = i == 0 ? start.pos() : wps.get(i - 1).pos, b = i == wps.size() ? end.pos() : wps.get(i).pos;
        return Math.max(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()), Math.abs(b.getY() - a.getY()) / climbPerBlock);
    }

    /** Section whose join to the one before it failed, from the last {@link #join}; 0 when none. */
    private int badJoin;

    /** Joins the sections' legs and polylines into one path. */
    private TerrainRouter.Path join(final List<TerrainRouter.Path> paths, final StringBuilder err) {
        final List<RoutePlanner.Leg> legs = new ArrayList<>();
        final List<double[]> wp = new ArrayList<>();
        int expanded = 0;
        badJoin = 0;
        for (int pi = 0; pi < paths.size(); pi++) {
            final TerrainRouter.Path p = paths.get(pi);
            final List<RoutePlanner.Leg> pl = RoutePlanner.merge(p.legs());
            if (!legs.isEmpty() && !pl.isEmpty()) {
                final RoutePlanner.Leg a = legs.get(legs.size() - 1), b = pl.get(0);
                if (a.dir() == b.dir()) {
                    if (a.shifted() && b.shifted()) {
                        badJoin = pi;
                        err.append("Two sections could not be joined.");
                        return null;
                    }
                    legs.set(legs.size() - 1, new RoutePlanner.Leg(a.dir(), a.steps() + b.steps(), a.sx() + b.sx(),
                        a.sz() + b.sz(), Math.max(a.sm(), b.sm())));
                    legs.addAll(pl.subList(1, pl.size()));
                } else
                    legs.addAll(pl);
            } else
                legs.addAll(pl);
            if (pi > 0 && !RoutePlanner.valid(legs, Integer.MAX_VALUE)) {
                badJoin = pi;
                err.append("Two sections could not be joined.");
                return null;
            }
            final List<double[]> w = p.waypoints();
            wp.addAll(wp.isEmpty() ? w : w.subList(1, w.size()));
            expanded += p.expanded();
        }
        if (!RoutePlanner.valid(legs, st.maxLength())) {
            int len = 0;
            for (final RoutePlanner.Leg l : legs)
                len += l.steps();
            err.append(len > st.maxLength() ? "The route (" + len + " blocks) is longer than the " + st.maxLength() + " block limit."
                : "The sections of the route could not be joined into a buildable line.");
            return null;
        }
        return new TerrainRouter.Path(legs, wp, expanded, paths.get(paths.size() - 1).end());
    }

    private static final boolean DEBUG = Boolean.getBoolean("lht.debug");
}
