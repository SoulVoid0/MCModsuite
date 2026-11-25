package dev.soulvoid.longhaul.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.soulvoid.longhaul.Config;
import dev.soulvoid.longhaul.LongHaulTracks;
import dev.soulvoid.longhaul.net.Net;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The route hologram: a colour-coded line along the whole route (green on the ground, cyan on bridges, orange in
 * tunnels) drawn as far as the player can see terrain (Voxy LOD distance when present), rails, sleepers, pillars
 * and tunnel profiles close by, and a floating bill of materials at both ends.
 */
public final class HologramRenderer {

    static final String[] STATES = {"Preview", "Surveying", "Awaiting materials", "Building", "Needs materials", "Complete"};

    public static boolean visible() {
        final var p = Minecraft.getInstance().player;
        if (p == null)
            return false;
        return Config.ALWAYS_SHOW_HOLOGRAMS.get() || p.getMainHandItem().is(LongHaulTracks.SURVEYOR.get())
            || p.getOffhandItem().is(LongHaulTracks.SURVEYOR.get());
    }

    public static void render(final RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !visible())
            return;
        final Minecraft mc = Minecraft.getInstance();
        final Camera cam = e.getCamera();
        final Vec3 c = cam.getPosition();
        final PoseStack ps = e.getPoseStack();
        final MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        final VertexConsumer lines = buf.getBuffer(RenderType.lines());
        final double far = DistantTerrain.viewBlocks() + 64.0;
        final double near = Config.HOLOGRAM_DETAIL_RANGE.get();
        ps.pushPose();
        ps.translate(-c.x, -c.y, -c.z);
        final PoseStack.Pose pose = ps.last();
        for (final ClientRoutes.Route r : ClientRoutes.routes()) {
            if (!r.inCurrentDimension() || r.kinds.length < 2)
                continue;
            final int n = r.kinds.length;
            final float built = r.status != null && r.status.state() >= 3 ? r.status.progress() : 0;
            final int stride = 1;
            for (int i = 0; i + stride < n; i += stride) {
                final float x0 = r.xyz[i * 3], y0 = r.xyz[i * 3 + 1] + .1f, z0 = r.xyz[i * 3 + 2];
                final float x1 = r.xyz[(i + stride) * 3], y1 = r.xyz[(i + stride) * 3 + 1] + .1f, z1 = r.xyz[(i + stride) * 3 + 2];
                final double d = Math.sqrt((x0 - c.x) * (x0 - c.x) + (z0 - c.z) * (z0 - c.z));
                if (d > far)
                    continue;
                final int col = color(r.kinds[i], i < built * n);
                line(lines, pose, x0, y0, z0, x1, y1, z1, col);
                if (d > near)
                    continue;
                // rails and sleepers
                float px = -(z1 - z0), pz = x1 - x0;
                final float len = (float) Math.sqrt(px * px + pz * pz);
                if (len < 1e-4)
                    continue;
                px /= len;
                pz /= len;
                line(lines, pose, x0 + px * .7f, y0, z0 + pz * .7f, x1 + px * .7f, y1, z1 + pz * .7f, col);
                line(lines, pose, x0 - px * .7f, y0, z0 - pz * .7f, x1 - px * .7f, y1, z1 - pz * .7f, col);
                line(lines, pose, x0 + px * 1.1f, y0, z0 + pz * 1.1f, x0 - px * 1.1f, y0, z0 - pz * 1.1f, col);
                if (r.kinds[i] == 1 && i % 8 == 0)
                    line(lines, pose, x0, y0 - 1, z0, x0, y0 - 24, z0, 0xFF55CCFF);
                if (r.kinds[i] == 2 && i % 4 == 0) {
                    final float h = tunnelHeight();
                    final float w = 2.5f;
                    line(lines, pose, x0 + px * w, y0, z0 + pz * w, x0 + px * w, y0 + h, z0 + pz * w, col);
                    line(lines, pose, x0 - px * w, y0, z0 - pz * w, x0 - px * w, y0 + h, z0 - pz * w, col);
                    line(lines, pose, x0 + px * w, y0 + h, z0 + pz * w, x0 - px * w, y0 + h, z0 - pz * w, col);
                }
            }
        }
        // the player's own unplanned points
        final Net.Draft dr = ClientRoutes.draft;
        if (dr != null && mc.level != null && mc.level.dimension().location().toString().equals(dr.dim())) {
            if (dr.start() != null)
                beacon(lines, pose, dr.start(), 0xFFFFDD33);
            if (dr.end() != null)
                beacon(lines, pose, dr.end(), 0xFFFF5544);
        }
        ps.popPose();
        buf.endBatch(RenderType.lines());

        // info panels
        for (final ClientRoutes.Route r : ClientRoutes.routes()) {
            if (!r.inCurrentDimension() || r.status == null)
                continue;
            for (final BlockPos p : new BlockPos[] {r.status.start(), r.status.end()}) {
                final Vec3 at = Vec3.atBottomCenterOf(p).add(0, 4, 0);
                if (at.distanceTo(c) > 128)
                    continue;
                panel(ps, buf, cam, at, lines(r.status), mc.font);
            }
        }
        buf.endBatch();
    }

    static List<Component> lines(final Net.Status s) {
        final List<Component> out = new ArrayList<>();
        out.add(Component.literal(STATES[Math.min(s.state(), STATES.length - 1)] + " - " + s.length() + " blocks ("
            + s.bridgeLength() + " bridge, " + s.tunnelLength() + " tunnel)"));
        if (s.state() == 1 || s.state() == 3)
            out.add(Component.literal((int) (s.progress() * 100) + "%"));
        out.add(Component.translatable("longhaultracks.gui.mode").append(": ")
            .append(Component.translatable("longhaultracks.mode." + s.mode())).append(", ")
            .append(Component.translatable("longhaultracks.gui.light")).append(": ")
            .append(Component.translatable("longhaultracks.light." + s.light())));
        out.add(Component.literal(s.exact() ? "Materials:" : "Materials (estimate until surveyed):"));
        for (final Net.MatLine m : s.materials())
            out.add(Component.literal("  " + itemName(m.item()) + ": " + m.supplied() + " / " + m.required()));
        if (!s.message().isEmpty())
            out.add(Component.literal(s.message()));
        return out;
    }

    static String itemName(final String id) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? id : BuiltInRegistries.ITEM.get(rl).getDescription().getString();
    }

    private static void panel(final PoseStack ps, final MultiBufferSource buf, final Camera cam, final Vec3 at,
                              final List<Component> text, final Font font) {
        final Vec3 c = cam.getPosition();
        ps.pushPose();
        ps.translate(at.x - c.x, at.y - c.y, at.z - c.z);
        ps.mulPose(cam.rotation());
        final float s = 0.025f * (float) Math.max(1, at.distanceTo(c) / 16);
        ps.scale(s, -s, s);
        int y = -text.size() * 10;
        for (final Component line : text) {
            final float x = -font.width(line) / 2f;
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, ps.last().pose(), buf, Font.DisplayMode.SEE_THROUGH,
                0x80000000, LightTexture.FULL_BRIGHT);
            y += 10;
        }
        ps.popPose();
    }

    private static void beacon(final VertexConsumer v, final PoseStack.Pose pose, final BlockPos p, final int col) {
        final float x = p.getX() + .5f, y = p.getY(), z = p.getZ() + .5f;
        line(v, pose, x, y, z, x, y + 96, z, col);
        line(v, pose, x - .5f, y + .05f, z - .5f, x + .5f, y + .05f, z + .5f, col);
        line(v, pose, x - .5f, y + .05f, z + .5f, x + .5f, y + .05f, z - .5f, col);
    }

    static float tunnelHeight() {
        try {
            return Config.TUNNEL_HEIGHT.get();
        } catch (final IllegalStateException e) {
            return 8;
        }
    }

    static int color(final byte kind, final boolean built) {
        final int c = switch (kind) {
            case 1 -> 0x55CCFF;
            case 2 -> 0xFFA040;
            default -> 0x66FF66;
        };
        return (built ? 0x60000000 : 0xFF000000) | c;
    }

    private static void line(final VertexConsumer v, final PoseStack.Pose pose, final float x0, final float y0,
                             final float z0, final float x1, final float y1, final float z1, final int argb) {
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        final float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-5)
            return;
        nx /= l;
        ny /= l;
        nz /= l;
        final int a = argb >>> 24, r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
        v.addVertex(pose, x0, y0, z0).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
        v.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
    }

    private HologramRenderer() {}
}
