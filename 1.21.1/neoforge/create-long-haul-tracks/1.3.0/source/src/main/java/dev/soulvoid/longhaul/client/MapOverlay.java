package dev.soulvoid.longhaul.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.ModIds;
import dev.soulvoid.longhaul.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Draws routes on Xaero's World Map, in the same map space Xaero Train Map draws Create's trains in, and remembers the
 * block under the cursor so the "set start/end at map cursor" keys work there. Called from the GuiMap mixin, which is
 * only applied when Xaero's World Map is installed.
 */
public final class MapOverlay {

    public static boolean open;
    public static int mouseX, mouseZ;

    public static void render(final Screen screen, final GuiGraphics g, final double mapScale, final double camX,
                              final double camZ, final int mouseBlockX, final int mouseBlockZ) {
        open = true;
        mouseX = mouseBlockX;
        mouseZ = mouseBlockZ;
        if (!shown())
            return;
        final Minecraft mc = Minecraft.getInstance();
        final double guiScale = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
        final double scale = mapScale / guiScale;
        final PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);
        pose.scale((float) scale, (float) scale, 1);
        pose.translate(-camX, -camZ, 0);
        final Matrix4f m = pose.last().pose();
        final VertexConsumer v = g.bufferSource().getBuffer(RenderType.gui());
        final float w = (float) Math.max(1.5, 3 / scale);
        final double minX = camX - screen.width / 2.0 / scale - 8, maxX = camX + screen.width / 2.0 / scale + 8;
        final double minZ = camZ - screen.height / 2.0 / scale - 8, maxZ = camZ + screen.height / 2.0 / scale + 8;
        for (final ClientRoutes.Route r : ClientRoutes.routes()) {
            if (!r.inCurrentDimension())
                continue;
            final int n = r.kinds.length;
            final int stride = Math.max(1, (int) (1 / scale));
            final float built = r.status != null && r.status.state() >= 3 ? r.status.progress() : 0;
            for (int i = 0; i + stride < n; i += stride) {
                final float x0 = r.xyz[i * 3], z0 = r.xyz[i * 3 + 2];
                final float x1 = r.xyz[(i + stride) * 3], z1 = r.xyz[(i + stride) * 3 + 2];
                if (Math.max(x0, x1) < minX || Math.min(x0, x1) > maxX || Math.max(z0, z1) < minZ || Math.min(z0, z1) > maxZ)
                    continue;
                quad(v, m, x0, z0, x1, z1, w, HologramRenderer.color(r.kinds[i], i < built * n));
            }
        }
        final Net.Draft d = ClientRoutes.draft;
        if (d != null && mc.level != null && mc.level.dimension().location().toString().equals(d.dim())) {
            if (d.start() != null)
                marker(v, m, d.start(), w * 3, 0xFFFFDD33);
            if (d.end() != null)
                marker(v, m, d.end(), w * 3, 0xFFFF5544);
        }
        g.flush();
        pose.popPose();
        g.drawString(mc.font, Component.translatable("longhaultracks.map.hint",
            ClientEvents.MAP_START.getTranslatedKeyMessage(), ClientEvents.MAP_END.getTranslatedKeyMessage()), 4,
            screen.height - 12, 0xFFFFFFFF);
    }

    /** Respects Create's own train-map toggle when Xaero Train Map is installed, so both overlays switch together. */
    private static boolean shown() {
        if (!Config.SHOW_ON_MAP.get())
            return false;
        if (ModIds.isLoaded(ModIds.XAERO_TRAIN_MAP)) {
            try {
                return com.simibubi.create.infrastructure.config.AllConfigs.client().showTrainMapOverlay.get();
            } catch (final RuntimeException | LinkageError e) {
                return true;
            }
        }
        return true;
    }

    private static void marker(final VertexConsumer v, final Matrix4f m, final BlockPos p, final float s, final int c) {
        final float x = p.getX() + .5f, z = p.getZ() + .5f;
        v.addVertex(m, x - s, z - s, 0).setColor(c);
        v.addVertex(m, x - s, z + s, 0).setColor(c);
        v.addVertex(m, x + s, z + s, 0).setColor(c);
        v.addVertex(m, x + s, z - s, 0).setColor(c);
    }

    private static void quad(final VertexConsumer v, final Matrix4f m, final float x0, final float z0, final float x1,
                             final float z1, final float w, final int c) {
        float px = -(z1 - z0), pz = x1 - x0;
        final float l = (float) Math.sqrt(px * px + pz * pz);
        if (l < 1e-4)
            return;
        px = px / l * w / 2;
        pz = pz / l * w / 2;
        v.addVertex(m, x0 + px, z0 + pz, 0).setColor(c);
        v.addVertex(m, x0 - px, z0 - pz, 0).setColor(c);
        v.addVertex(m, x1 - px, z1 - pz, 0).setColor(c);
        v.addVertex(m, x1 + px, z1 + pz, 0).setColor(c);
    }

    private MapOverlay() {}
}
