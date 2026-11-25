package dev.soulvoid.voxyatmo.distance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Distance requests made by other mods through {@link dev.soulvoid.voxyatmo.api.VoxyAtmoApi}. */
public final class DistanceRequests {

    private static final Map<String, Integer> REQUESTS = new ConcurrentHashMap<>();

    public static void set(final String owner, final int blocks) {
        if (owner == null)
            return;
        if (blocks <= 0)
            REQUESTS.remove(owner);
        else
            REQUESTS.put(owner, blocks);
    }

    public static void clear(final String owner) {
        if (owner != null)
            REQUESTS.remove(owner);
    }

    /** @return the largest active request in blocks, or 0. */
    public static int maxBlocks() {
        int max = 0;
        for (final int v : REQUESTS.values())
            max = Math.max(max, v);
        return max;
    }

    public static Map<String, Integer> snapshot() {
        return Map.copyOf(REQUESTS);
    }

    private DistanceRequests() {}
}
