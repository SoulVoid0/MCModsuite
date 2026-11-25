package dev.soulvoid.longhaul.route;

import java.util.Locale;

/** How a route's structures look: which lights it gets and which structures are built at all. */
public final class Style {

    public enum Light {
        NONE(null), TORCH("minecraft:torch"), LANTERN("minecraft:lantern"), SOUL_LANTERN("minecraft:soul_lantern");

        /** Item consumed per light, or null. */
        public final String item;

        Light(final String item) {
            this.item = item;
        }

        public static Light parse(final String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (final RuntimeException e) {
                return NONE;
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Mode {
        /** Bridges, tunnel lining, embankments. */
        FULL(true, true),
        TUNNELS_ONLY(false, true),
        BRIDGES_ONLY(true, false),
        /** Only clears the way and lays track; the track floats over gaps. */
        CLEAR_ONLY(false, false);

        public final boolean bridges;
        public final boolean tunnels;

        Mode(final boolean bridges, final boolean tunnels) {
            this.bridges = bridges;
            this.tunnels = tunnels;
        }

        public static Mode parse(final String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (final RuntimeException e) {
                return FULL;
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Embankments and track beds are built in every mode except clear-only. */
        public boolean beds() {
            return this != CLEAR_ONLY;
        }
    }

    private Style() {}
}
