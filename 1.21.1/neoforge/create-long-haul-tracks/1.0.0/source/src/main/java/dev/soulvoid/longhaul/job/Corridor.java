package dev.soulvoid.longhaul.job;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import dev.soulvoid.longhaul.route.Dir8;
import dev.soulvoid.longhaul.route.Piece;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Everything a route touches, grouped per chunk in route order: the corridor cells to clear, line, deck and pillar,
 * the straight track blocks, and the curves. Rebuilt from the pieces whenever a job is loaded.
 */
public final class Corridor {

    public static final float CLEAR_R = 2.5f;
    public static final float RING_R = 3.5f;
    public static final float DECK_R = 1.5f;

    /** One block column of the corridor. {@code layer} is the y of the track layer above it. */
    public static final class Cell {
        public final int x, z;
        public int layer;
        public float dist = Float.MAX_VALUE;
        public byte kind;
        public boolean pillar;

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

        ChunkWork(final ChunkPos pos) {
            this.pos = pos;
        }
    }

    /** Route centerline point, for holograms and maps. */
    public record Point(double x, double y, double z, byte kind) {}

    public final List<ChunkWork> chunks = new ArrayList<>();
    public final List<Point> centerline = new ArrayList<>();

    public static Corridor build(final List<Piece> pieces, final TrackMaterial material, final int pillarSpacing) {
        final Corridor c = new Corridor();
        final Map<Long, Cell> cells = new HashMap<>();
        final LinkedHashMap<Long, ChunkWork> order = new LinkedHashMap<>();
        final List<Piece.Curve> curves = new ArrayList<>();
        final Map<Piece.Curve, Integer> curveChunk = new HashMap<>();
        int step = 0;
        for (final Piece p : pieces) {
            if (p instanceof Piece.Straight s) {
                for (int i = 0; i < s.count(); i++) {
                    final BlockPos b = s.at(i);
                    final byte k = s.kinds()[i];
                    c.stamp(cells, order, b.getX() + .5, b.getY(), b.getZ() + .5, k,
                        k == Piece.BRIDGE && step % pillarSpacing == 0);
                    if (i + 1 < s.count())
                        c.stamp(cells, order, b.getX() + .5 + s.dir().dx * .5, b.getY(), b.getZ() + .5 + s.dir().dz * .5,
                            k, false);
                    c.centerline.add(new Point(b.getX() + .5, b.getY(), b.getZ() + .5, k));
                    work(order, new ChunkPos(b)).tracks.add(new TrackBlock(b, s.dir()));
                    step++;
                }
            } else if (p instanceof Piece.Curve cv) {
                final BezierConnection bc = cv.toBezier(material);
                final double len = bc.getLength();
                final int n = Math.max(2, (int) Math.ceil(len * 2));
                int maxIdx = 0;
                double travelled = 0;
                Vec3 last = null;
                for (int i = 0; i <= n; i++) {
                    final Vec3 v = bc.getPosition(i / (double) n);
                    if (last != null)
                        travelled += v.distanceTo(last);
                    final boolean pillar = cv.kind() == Piece.BRIDGE && last != null
                        && (int) (travelled / pillarSpacing) != (int) ((travelled - v.distanceTo(last)) / pillarSpacing);
                    last = v;
                    maxIdx = Math.max(maxIdx, c.stamp(cells, order, v.x, v.y, v.z, cv.kind(), pillar));
                    if (i % 2 == 0)
                        c.centerline.add(new Point(v.x, v.y, v.z, cv.kind()));
                }
                maxIdx = Math.max(maxIdx, indexOf(order, new ChunkPos(cv.a())));
                maxIdx = Math.max(maxIdx, indexOf(order, new ChunkPos(cv.b())));
                curves.add(cv);
                curveChunk.put(cv, maxIdx);
                step += (int) Math.round(len);
            }
        }
        for (final Cell cell : cells.values())
            order.get(ChunkPos.asLong(cell.x >> 4, cell.z >> 4)).cells.add(cell);
        c.chunks.addAll(order.values());
        for (final Piece.Curve cv : curves)
            c.chunks.get(curveChunk.get(cv)).curves.add(cv);
        return c;
    }

    private static ChunkWork work(final LinkedHashMap<Long, ChunkWork> order, final ChunkPos pos) {
        return order.computeIfAbsent(pos.toLong(), k -> new ChunkWork(pos));
    }

    private static int indexOf(final LinkedHashMap<Long, ChunkWork> order, final ChunkPos pos) {
        work(order, pos);
        int i = 0;
        for (final Long k : order.keySet()) {
            if (k == pos.toLong())
                return i;
            i++;
        }
        return i;
    }

    /** Marks every column within RING_R of a centerline point; returns the highest chunk order index touched. */
    private int stamp(final Map<Long, Cell> cells, final LinkedHashMap<Long, ChunkWork> order, final double px,
                      final double py, final double pz, final byte kind, final boolean pillar) {
        final int layer = (int) Math.floor(py + 1e-3);
        final int r = (int) Math.ceil(RING_R);
        int maxIdx = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                final int x = (int) Math.floor(px) + dx, z = (int) Math.floor(pz) + dz;
                final double ddx = x + .5 - px, ddz = z + .5 - pz;
                final float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                if (d > RING_R)
                    continue;
                final long key = BlockPos.asLong(x, 0, z);
                Cell cell = cells.get(key);
                if (cell == null) {
                    cell = new Cell(x, z);
                    cells.put(key, cell);
                    work(order, new ChunkPos(x >> 4, z >> 4));
                }
                if (d < cell.dist) {
                    cell.dist = d;
                    cell.layer = layer;
                    cell.kind = kind;
                }
                if (pillar && d < .75f)
                    cell.pillar = true;
            }
        final long ck = ChunkPos.asLong((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4);
        int i = 0;
        for (final Long k : order.keySet()) {
            if (k == ck) {
                maxIdx = i;
                break;
            }
            i++;
        }
        return maxIdx;
    }
}
