package dev.soulvoid.voxyatmo.api;

import dev.soulvoid.voxyatmo.distance.DistanceRequests;
import dev.soulvoid.voxyatmo.distance.SeedgenDistance;
import dev.soulvoid.voxyatmo.distance.TargetDistance;

/**
 * Stable public API of Voxy Atmosphere Compat, for other mods (for example the Create trains mod).
 *
 * <p>Contract: every method here is static, never throws, and keeps its exact signature in all future versions.
 * New methods may be added; none are removed or changed. Call it by reflection when this mod is optional, and check
 * {@link #apiVersion()} before using methods added after version 1.
 *
 * <p>API version 1 (mod 1.1.0): every method below.
 */
public final class VoxyAtmoApi {

    /** Current API version. Increases only when methods are added. */
    public static final int API_VERSION = 1;

    /** @return the API version this jar provides (1 for mod 1.1.0). */
    public static int apiVersion() {
        return API_VERSION;
    }

    /**
     * @return how far distant terrain reaches in blocks: Voxy SeedGen's generation reach, else Voxy's LOD render
     *         distance; -1 when neither is known.
     */
    public static int distantTerrainBlocks() {
        try {
            return TargetDistance.terrainBlocks();
        } catch (final Throwable t) {
            return -1;
        }
    }

    /** @return the cloud render distance this mod is enforcing in blocks, or -1 when it leaves Simple Clouds alone. */
    public static int cloudDistanceBlocks() {
        try {
            return TargetDistance.cloudBlocks();
        } catch (final Throwable t) {
            return -1;
        }
    }

    /** @return Voxy SeedGen's generation distance in chunks, or -1 when SeedGen is absent or unreadable. */
    public static int seedgenGenerationChunks() {
        try {
            final int chunks = SeedgenDistance.chunks();
            return chunks > 0 ? chunks : -1;
        } catch (final Throwable t) {
            return -1;
        }
    }

    /**
     * Ask for clouds (and the distant-terrain distance this mod reports) to reach at least {@code blocks}. The largest
     * active request wins; the user's {@code maxCloudDistanceBlocks} cap still applies. Calling again with the same
     * owner replaces that owner's request.
     *
     * @param ownerId a stable id for the caller, normally its mod id
     * @param blocks  the distance in blocks; 0 or less clears the request
     */
    public static void requestDistance(final String ownerId, final int blocks) {
        try {
            DistanceRequests.set(ownerId, blocks);
        } catch (final Throwable ignored) {
        }
    }

    /** Remove the request made by {@code ownerId}, if any. */
    public static void clearDistance(final String ownerId) {
        try {
            DistanceRequests.clear(ownerId);
        } catch (final Throwable ignored) {
        }
    }

    private VoxyAtmoApi() {}
}
