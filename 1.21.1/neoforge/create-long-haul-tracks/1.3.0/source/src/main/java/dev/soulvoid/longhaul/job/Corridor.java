package dev.soulvoid.longhaul.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.route.Dir8;
import dev.soulvoid.longhaul.route.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Everything a route touches, grouped per chunk in route order: the corridor cells to clear, line, deck and pillar,
 * the straight track blocks, the curves and the lights. Rebuilt from the pieces whenever a job is loaded.
 */
public final class Corridor {

    /** Half-width of the deck / track bed. */
    public static final float DECK_R = 1.5f;

    /**
     * @param clearR       half-width cleared on straights
     * @param curveR       half-width cleared on curves
     */
    public record Spec(int pillarSpacing, float clearR, float curveR, int lightSpacing) {
        public static Spec fromConfig() {
            final float r = Config.TUNNEL_WIDTH.get() / 2f;
            return new Spec(Config.PILLAR_SPACING.get(), r, r + Config.CURVE_EXTRA_WIDTH.get() / 2f,
                Config.LIGHT_SPACING.get());
        }

        public float ringR() {
            return Math.max(clearR, curveR) + 1;
        }
    }

    /** One block column of the corridor. {@code layer} is the y of the track layer above it. */
    public static final class Cell {
        public final int x, z;
        public int layer;
        public float dist = Float.MAX_VALUE;
        public byte kind;
        /** Inside the cleared width (otherwise part of the wall ring around it). */
        public boolean inside;
        public boolean pillar;
        /** Index of the nearest centerline point. */
        public int point;

        Cell(final int x, final int z) {
            this.x = x;
            this.z = z;
        }
    }

    public record TrackBlock(BlockPos pos, Dir8 dir) {}

    public static final class ChunkWork {
        public final ChunkPos pos;
        public final List<Cell> cells = new ArrayList<>();
        public final List<TrackBlock> tracks = new ArrayList<>();
        public final List<Piece.Curve> curves = new ArrayList<>();
        /** Centerline point indices that get a light; negative ({@code -i - 1}) for lights on the other side. */
        public final List<Integer> lights = new ArrayList<>();

        ChunkWork(final ChunkPos pos) {
            this.pos = pos;
        }
    }

    /** Route centerline point (track bottom), with its horizontal heading, for holograms, maps and the builder. */
    public record Point(double x, double y, double z, byte kind, float hx, float hz) {}

    public final Spec spec;
    public final List<ChunkWork> chunks = new ArrayList<>();
    public final List<Point> centerline = new ArrayList<>();

    private final Map<Long, Cell> cells = new HashMap<>();
    private final LinkedHashMap<Long, ChunkWork> order = new LinkedHashMap<>();
    private final Map<Long, Integer> orderIdx = new HashMap<>();
    private double travelled;
    private int lastPillar = Integer.MIN_VALUE;
    private double structure;
    private byte structureKind = -1;
    private int lightCount;

    private Corridor(final Spec spec) {
        this.spec = spec;
    }

    public static Corridor build(final List<Piece> pieces, final TrackMaterial material, final Spec spec) {
        final Corridor c = new Corridor(spec);
        final List<Piece.Curve> curves = new ArrayList<>();
        final Map<Piece.Curve, Integer> curveChunk = new HashMap<>();
        for (final Piece p : pieces) {
            if (p instanceof Piece.Straight s) {
                final float hx = (float) s.dir().unit().x, hz = (float) s.dir().unit().z;
                for (int i = 0; i < s.count(); i++) {
                    final BlockPos b = s.at(i);
                    final byte k = s.kinds()[i];
                    final int pt = c.point(b.getX() + .5, b.getY(), b.getZ() + .5, k, hx, hz, i == 0 ? 0 : s.dir().len());
                    c.stamp(b.getX() + .5, b.getY(), b.getZ() + .5, k, pt, spec.clearR);
                    if (i + 1 < s.count())
                        c.stamp(b.getX() + .5 + s.dir().dx * .5, b.getY(), b.getZ() + .5 + s.dir().dz * .5, k, pt, spec.clearR);
                    c.work(new ChunkPos(b)).tracks.add(new TrackBlock(b, s.dir()));
                }
            } else if (p instanceof Piece.Curve cv) {
                final BezierConnection bc = cv.toBezier(material);
                final double len = bc.getLength();
                final int n = Math.max(2, (int) Math.ceil(len * 2));
                final boolean turn = cv.dirA() != cv.dirB().opposite();
                final float r = turn ? spec.curveR : spec.clearR;
                int maxIdx = 0;
                int pt = -1;
                Vec3 last = null;
                for (int i = 0; i <= n; i++) {
                    final Vec3 v = bc.getPosition(i / (double) n);
                    if (i % 2 == 0 || pt < 0) {
                        final Vec3 a = bc.getPosition(Math.max(0, i - 1) / (double) n);
                        final Vec3 b = bc.getPosition(Math.min(n, i + 1) / (double) n);
                        final double hx = b.x - a.x, hz = b.z - a.z, hl = Math.max(1e-6, Math.hypot(hx, hz));
                        final double step = last == null ? 0 : v.distanceTo(last);
                        pt = c.point(v.x, v.y, v.z, cv.kind(), (float) (hx / hl), (float) (hz / hl), step);
                        last = v;
                    }
                    maxIdx = Math.max(maxIdx, c.stamp(v.x, v.y, v.z, cv.kind(), pt, r));
                }
                maxIdx = Math.max(maxIdx, c.indexOf(new ChunkPos(cv.a())));
                maxIdx = Math.max(maxIdx, c.indexOf(new ChunkPos(cv.b())));
                curves.add(cv);
                curveChunk.put(cv, maxIdx);
            }
        }
        for (final Cell cell : c.cells.values())
            c.order.get(ChunkPos.asLong(cell.x >> 4, cell.z >> 4)).cells.add(cell);
        c.chunks.addAll(c.order.values());
        for (final Piece.Curve cv : curves)
            c.chunks.get(curveChunk.get(cv)).curves.add(cv);
        return c;
    }

    /** Adds a centerline point; places pillars and lights by distance travelled along the route. */
    private int point(final double x, final double y, final double z, final byte kind, final float hx, final float hz,
                      final double step) {
        final int idx = centerline.size();
        centerline.add(new Point(x, y, z, kind, hx, hz));
        travelled += step;
        if (kind == Piece.BRIDGE) {
            final int slot = (int) Math.floor(travelled / spec.pillarSpacing);
            if (slot != lastPillar) {
                lastPillar = slot;
                pillarAt(x, z);
            }
        }
        if (kind == Piece.BRIDGE || kind == Piece.TUNNEL) {
            if (kind != structureKind) {
                structureKind = kind;
                structure = spec.lightSpacing / 2.0;
            }
            structure += step;
            if (structure >= spec.lightSpacing) {
                structure = 0;
                work(new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)).lights.add(lightCount++ % 2 == 0 ? idx : -idx - 1);
            }
        } else
            structureKind = -1;
        return idx;
    }

    private void pillarAt(final double x, final double z) {
        final int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        final long key = BlockPos.asLong(bx, 0, bz);
        Cell cell = cells.get(key);
        if (cell == null) {
            cell = new Cell(bx, bz);
            cells.put(key, cell);
            work(new ChunkPos(bx >> 4, bz >> 4));
        }
        cell.pillar = true;
    }

    private ChunkWork work(final ChunkPos pos) {
        return order.computeIfAbsent(pos.toLong(), k -> {
            orderIdx.put(k, order.size());
            return new ChunkWork(pos);
        });
    }

    private int indexOf(final ChunkPos pos) {
        work(pos);
        return orderIdx.get(pos.toLong());
    }

    /** Marks every column within the ring of a centerline point; returns the chunk order index of the point. */
    private int stamp(final double px, final double py, final double pz, final byte kind, final int point,
                      final float clearR) {
        final int layer = (int) Math.floor(py + 1e-3);
        final float ring = spec.ringR();
        final int r = (int) Math.ceil(ring);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                final int x = (int) Math.floor(px) + dx, z = (int) Math.floor(pz) + dz;
                final double ddx = x + .5 - px, ddz = z + .5 - pz;
                final float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                if (d > ring)
                    continue;
                final long key = BlockPos.asLong(x, 0, z);
                Cell cell = cells.get(key);
                if (cell == null) {
                    cell = new Cell(x, z);
                    cells.put(key, cell);
                    work(new ChunkPos(x >> 4, z >> 4));
                }
                if (d <= clearR)
                    cell.inside = true;
                if (d < cell.dist) {
                    cell.dist = d;
                    cell.layer = layer;
                    cell.kind = kind;
                    cell.point = point;
                }
            }
        return indexOf(new ChunkPos((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4));
    }
}
