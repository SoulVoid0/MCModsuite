package dev.soulvoid.longhaul.route;

import com.simibubi.create.content.trains.track.TrackShape;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** The eight directions Create track can run straight in. Ordered clockwise, 45 degrees apart. */
public enum Dir8 {
    E(1, 0), SE(1, 1), S(0, 1), SW(-1, 1), W(-1, 0), NW(-1, -1), N(0, -1), NE(1, -1);

    public final int dx;
    public final int dz;

    Dir8(final int dx, final int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public boolean diagonal() {
        return dx != 0 && dz != 0;
    }

    /** Horizontal length of one step. */
    public double len() {
        return diagonal() ? Math.sqrt(2) : 1;
    }

    public Dir8 opposite() {
        return values()[(ordinal() + 4) % 8];
    }

    /** Change of heading, in degrees (0..180), when going from this direction into {@code o}. */
    public int turnTo(final Dir8 o) {
        int d = Math.abs(ordinal() - o.ordinal());
        d = Math.min(d, 8 - d);
        return d * 45;
    }

    public Vec3 axis() {
        return new Vec3(dx, 0, dz);
    }

    public Vec3 unit() {
        return axis().normalize();
    }

    public BlockPos step(final BlockPos p, final int n) {
        return p.offset(dx * n, 0, dz * n);
    }

    public TrackShape shape() {
        if (dz == 0)
            return TrackShape.XO;
        if (dx == 0)
            return TrackShape.ZO;
        return dx * dz > 0 ? TrackShape.PD : TrackShape.ND;
    }

    /**
     * Blocks between a turn's corner point and the track block where the curve starts. Sized so Create's own
     * placement rules would accept the curve (45 degree turns need >= 3.25 blocks, 90 degree turns >= 7).
     */
    public int turnSteps(final int degrees) {
        if (degrees >= 90)
            return diagonal() ? 7 : 10;
        return diagonal() ? 5 : 7;
    }

    public static Dir8 of(final int dx, final int dz) {
        final int sx = Integer.signum(dx), sz = Integer.signum(dz);
        for (final Dir8 d : values())
            if (d.dx == sx && d.dz == sz)
                return d;
        throw new IllegalArgumentException("zero direction");
    }

    /** Both directions a flat straight track shape runs in, or null for shapes that are not plain straights. */
    public static Dir8[] ofShape(final TrackShape shape) {
        return switch (shape) {
            case XO -> new Dir8[] {E, W};
            case ZO -> new Dir8[] {S, N};
            case PD -> new Dir8[] {SE, NW};
            case ND -> new Dir8[] {SW, NE};
            default -> null;
        };
    }
}
