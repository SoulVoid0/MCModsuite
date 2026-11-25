package dev.soulvoid.longhaul.route;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * One end of a route.
 *
 * @param pos       the track block position (existing track block, or where the new end block goes)
 * @param dirs      headings the route may leave this end with; empty = any
 * @param onTrack   the end is an existing Create track block, so the route joins it (junction or extension)
 * @param midTrack  the existing track continues on both sides, so the route must branch off with a curve
 */
public record Anchor(BlockPos pos, List<Dir8> dirs, boolean onTrack, boolean midTrack) {
    public static Anchor free(final BlockPos pos) {
        return new Anchor(pos, List.of(), false, false);
    }
}
