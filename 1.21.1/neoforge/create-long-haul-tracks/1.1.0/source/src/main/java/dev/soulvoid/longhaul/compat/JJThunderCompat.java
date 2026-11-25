package dev.soulvoid.longhaul.compat;

import java.util.Locale;

import dev.soulvoid.longhaul.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * JJThunder To The Max is a world-gen datapack (no mod jar on 1.21.1): it replaces the overworld's noise settings
 * with mountains up to y~2000 (world height 2096), deep canyons, wide rivers and the "underlands" cave layer.
 *
 * <p>The planner already samples terrain through the dimension's own noise router, so JJThunder's shapes are what it
 * plans against, including in chunks that were never generated. On top of that, when the pack is active the route
 * profile switches to settings that suit that terrain: the route search uses a coarser grid and a larger budget (the
 * mountains are kilometres wide), tunnels are made cheaper relative to detours, pillars may reach far deeper, and
 * terrain is sampled more coarsely because every column is expensive to evaluate at 2096 blocks tall. Tunnels
 * crossing the underlands caves are sealed by the builder's lining pass.</p>
 */
public final class JJThunderCompat {

    /**
     * @param tunnelMul      scales the tunnel cost (JJThunder mountains are too big to go around every time)
     * @param minCell        smallest route search grid
     * @param stepsMul       scales the search budget
     */
    public record Profile(String name, double tunnelMul, int minPillarDepth, int minSampleSpacing, int minSmoothing,
                          int minCell, double stepsMul) {}

    public static final Profile VANILLA = new Profile("vanilla", 1, 0, 0, 0, 0, 1);
    public static final Profile JJTHUNDER = new Profile("JJThunder To The Max", 0.6, 1024, 8, 32, 32, 2);

    /** @return whether the JJThunder To The Max datapack is enabled on this server. */
    public static boolean present(final MinecraftServer server) {
        for (final String id : server.getPackRepository().getSelectedIds()) {
            final String s = id.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
            if (s.contains("jjthunder") || s.contains("tothemax"))
                return true;
        }
        return false;
    }

    /** Profile for a level: JJThunder when the pack is on, or when the overworld is unusually tall (pack renamed). */
    public static Profile profile(final ServerLevel level) {
        if (!Config.JJTHUNDER_PROFILE.get())
            return VANILLA;
        if (present(level.getServer()) || level.getHeight() >= 1024)
            return JJTHUNDER;
        return VANILLA;
    }

    private JJThunderCompat() {}
}
