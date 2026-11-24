package dev.soulvoid.longhaul.route;

import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.TrackMaterial;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** One buildable piece of a route: a run of straight track blocks, or a Create bezier between two track blocks. */
public sealed interface Piece permits Piece.Straight, Piece.Curve {

    byte GROUND = 0;
    byte BRIDGE = 1;
    byte TUNNEL = 2;

    CompoundTag save();

    static Piece load(final CompoundTag t) {
        if (t.getString("T").equals("C"))
            return new Curve(BlockPos.of(t.getLong("A")), Dir8.values()[t.getByte("DA")], BlockPos.of(t.getLong("B")),
                Dir8.values()[t.getByte("DB")], t.getByte("K"));
        return new Straight(BlockPos.of(t.getLong("A")), Dir8.values()[t.getByte("D")], t.getInt("N"), t.getByteArray("K"));
    }

    /** {@code count} flat track blocks from {@code start} along {@code dir}; {@code kinds[i]} is GROUND/BRIDGE/TUNNEL. */
    record Straight(BlockPos start, Dir8 dir, int count, byte[] kinds) implements Piece {
        public BlockPos at(final int i) {
            return dir.step(start, i);
        }

        @Override
        public CompoundTag save() {
            final CompoundTag t = new CompoundTag();
            t.putString("T", "S");
            t.putLong("A", start.asLong());
            t.putByte("D", (byte) dir.ordinal());
            t.putInt("N", count);
            t.putByteArray("K", kinds);
            return t;
        }
    }

    /**
     * A Create curve from track block {@code a} to track block {@code b}. {@code dirA} is the heading leaving
     * {@code a} into the curve, {@code dirB} the heading leaving {@code b} into the curve. Used for turns, S-bends
     * and slopes alike.
     */
    record Curve(BlockPos a, Dir8 dirA, BlockPos b, Dir8 dirB, byte kind) implements Piece {

        public BezierConnection toBezier(final TrackMaterial material) {
            return new BezierConnection(Couple.create(a, b), Couple.create(curveStart(a, dirA), curveStart(b, dirB)),
                Couple.create(dirA.unit(), dirB.unit()), Couple.create(UP, UP), true, false, material);
        }

        /** Same as TrackBlock#getCurveStart for a flat track. */
        public static Vec3 curveStart(final BlockPos pos, final Dir8 dir) {
            return Vec3.atCenterOf(pos).add(0, -.5, 0).add(dir.axis().scale(.5));
        }

        @Override
        public CompoundTag save() {
            final CompoundTag t = new CompoundTag();
            t.putString("T", "C");
            t.putLong("A", a.asLong());
            t.putByte("DA", (byte) dirA.ordinal());
            t.putLong("B", b.asLong());
            t.putByte("DB", (byte) dirB.ordinal());
            t.putByte("K", kind);
            return t;
        }
    }

    Vec3 UP = new Vec3(0, 1, 0);
}
