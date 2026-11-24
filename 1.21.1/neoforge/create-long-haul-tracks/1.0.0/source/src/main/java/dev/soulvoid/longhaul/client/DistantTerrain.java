package dev.soulvoid.longhaul.client;

import java.lang.reflect.Method;

import dev.soulvoid.longhaul.ModIds;
import net.minecraft.client.Minecraft;

/**
 * How far the player can actually see terrain. With Voxy (LOD terrain) and Voxy Atmosphere Compat this is far past the
 * vanilla render distance, so the surveyor's long-range pick and the hologram reach that far too.
 *
 * <p>Voxy Atmosphere Compat is optional and used only by reflection, so any version works: 1.1.0+ exposes the stable
 * {@code dev.soulvoid.voxyatmo.api.VoxyAtmoApi}; 1.0.0 is read through {@code TargetDistance}. While a route hologram
 * is on screen this also asks 1.1.0+ (via {@code requestDistance}) to keep distant terrain drawn at least as far as
 * the route reaches.</p>
 */
public final class DistantTerrain {

    private static final String OWNER = "longhaultracks";
    private static Method distance;
    private static Method request;
    private static Method clear;
    private static boolean resolved;
    private static int requested = -1;

    public static int viewBlocks() {
        final Minecraft mc = Minecraft.getInstance();
        final int vanilla = mc.options.getEffectiveRenderDistance() * 16;
        return Math.max(vanilla, bestLod());
    }

    /** Distance distant terrain is drawn at, or -1. */
    public static int lodBlocks() {
        resolve();
        if (distance == null)
            return -1;
        try {
            return ((Number) distance.invoke(null)).intValue();
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            distance = null;
            return -1;
        }
    }

    /** Ask the compat mod to keep distant terrain out to {@code blocks}; <= 0 withdraws the request. */
    public static void request(final int blocks) {
        resolve();
        if (blocks == requested)
            return;
        requested = blocks;
        try {
            if (blocks > 0 && request != null)
                request.invoke(null, OWNER, blocks);
            else if (blocks <= 0 && clear != null)
                clear.invoke(null, OWNER);
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            request = clear = null;
        }
    }

    private static void resolve() {
        if (resolved)
            return;
        resolved = true;
        if (ModIds.isLoaded(ModIds.VOXY_ATMO_COMPAT)) {
            try {
                final Class<?> api = Class.forName("dev.soulvoid.voxyatmo.api.VoxyAtmoApi");
                for (final String name : new String[] {"distantTerrainBlocks", "terrainDistanceBlocks"}) {
                    try {
                        distance = api.getMethod(name);
                        break;
                    } catch (final NoSuchMethodException ignored) {
                        // try the next name
                    }
                }
                try {
                    request = api.getMethod("requestDistance", String.class, int.class);
                    clear = api.getMethod("clearDistance", String.class);
                } catch (final NoSuchMethodException ignored) {
                    // older API: no requests
                }
            } catch (final ClassNotFoundException | LinkageError e) {
                // 1.0.0: no API class
            }
            if (distance == null) {
                try {
                    distance = Class.forName("dev.soulvoid.voxyatmo.distance.TargetDistance").getMethod("terrainBlocks");
                } catch (final ReflectiveOperationException | LinkageError ignored) {
                    // unknown layout
                }
            }
        }
        if (distance == null && ModIds.isLoaded(ModIds.VOXY)) {
            try {
                final Class<?> cfg = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                final Object inst = cfg.getField("CONFIG").get(null);
                final Method m = cfg.getMethod("getLodRenderDistanceBlocks");
                voxyConfig = inst;
                voxyMethod = m;
            } catch (final ReflectiveOperationException | LinkageError ignored) {
                // not the voxy-forged layout
            }
        }
    }

    private static Object voxyConfig;
    private static Method voxyMethod;

    public static int voxyOnly() {
        if (voxyMethod == null)
            return -1;
        try {
            return ((Number) voxyMethod.invoke(voxyConfig)).intValue();
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            voxyMethod = null;
            return -1;
        }
    }

    public static int bestLod() {
        return Math.max(lodBlocks(), voxyOnly());
    }

    private DistantTerrain() {}
}
