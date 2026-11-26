package dev.soulvoid.longhaul.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.soulvoid.longhaul.net.Net;
import net.minecraft.client.Minecraft;

/** Client copy of every route and of this player's own selection. */
public final class ClientRoutes {

    public static final class Route {
        public final UUID id;
        public String dim = "";
        public float[] xyz = new float[0];
        public byte[] kinds = new byte[0];
        public Net.Status status;

        Route(final UUID id) {
            this.id = id;
        }

        public boolean inCurrentDimension() {
            final var level = Minecraft.getInstance().level;
            return level != null && level.dimension().location().toString().equals(dim);
        }
    }

    private static final Map<UUID, Route> ROUTES = new LinkedHashMap<>();
    public static Net.Draft draft;

    public static Collection<Route> routes() {
        return ROUTES.values();
    }

    public static Route get(final UUID id) {
        return ROUTES.get(id);
    }

    public static void onGeometry(final Net.Geometry g) {
        final Route r = ROUTES.computeIfAbsent(g.id(), Route::new);
        r.dim = g.dim();
        r.kinds = g.kinds();
        r.xyz = new float[g.xyz().length];
        for (int i = 0; i < r.xyz.length; i++)
            r.xyz[i] = g.xyz()[i] / 8f;
    }

    public static void onStatus(final Net.Status s) {
        final Route r = ROUTES.computeIfAbsent(s.id(), Route::new);
        r.status = s;
        if (r.dim.isEmpty())
            r.dim = s.dim();
    }

    public static void onRemove(final Net.Remove r) {
        ROUTES.remove(r.id());
    }

    public static void onDraft(final Net.Draft d) {
        draft = d;
    }

    /** Routes this player owns, newest last. */
    public static java.util.List<Route> mine() {
        final var me = Minecraft.getInstance().player;
        return ROUTES.values().stream()
            .filter(r -> r.status != null && me != null && r.status.owner().equals(me.getUUID())).toList();
    }

    public static void clear() {
        ROUTES.clear();
        draft = null;
    }

    private ClientRoutes() {}
}
