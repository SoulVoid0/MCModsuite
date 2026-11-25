package dev.soulvoid.voxyatmo.clouds;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;

import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;

/**
 * With an Iris shaderpack that renders Voxy's LODs itself ({@code excludeLodsFromVanillaDepth}, e.g. Complementary),
 * Voxy keeps LOD depth in its own framebuffer and never writes it into Minecraft's depth buffer. Simple Clouds copies
 * only Minecraft's depth before drawing clouds, so it cannot see LOD terrain and draws clouds over distant mountains.
 * Voxy's Iris pipeline reports its LOD depth texture and matrices here each frame, and they are merged into Simple
 * Clouds' cloud depth (using Voxy's own depth re-projection shader) right after its depth copy.
 */
public final class IrisLodDepth {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static int depthTex;
    private static final Matrix4f INV_LOD_MVP = new Matrix4f();
    private static final Matrix4f TARGET = new Matrix4f();
    private static long reportedAt;
    private static FullscreenBlit blit;
    private static boolean failed;
    private static boolean loggedReport;
    private static boolean loggedMerge;

    /** Called by Voxy's Iris pipeline at the end of its frame. */
    public static void report(final int tex, final Matrix4f lodMvp, final Matrix4f vanillaProjection, final Matrix4f modelView) {
        depthTex = tex;
        INV_LOD_MVP.set(lodMvp).invert();
        TARGET.set(vanillaProjection).mul(modelView);
        reportedAt = System.nanoTime();
        if (!loggedReport) {
            loggedReport = true;
            LOGGER.info("[Voxy Atmosphere Compat] shaderpack LOD depth found (Voxy Iris pipeline); clouds will be occluded by it.");
        }
    }

    /** Merges the LOD depth into the currently bound framebuffer's depth (nearest wins). */
    public static void mergeIntoBoundTarget() {
        // Only a fresh report (this or the previous frame; pipeline order differs between shaderpacks).
        if (failed || depthTex <= 0 || System.nanoTime() - reportedAt > 250_000_000L)
            return;
        try {
            if (blit == null)
                blit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag");
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(false, false, false, false);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            blit.bind();
            GL45.glBindTextureUnit(0, depthTex);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(1, false, INV_LOD_MVP.get(stack.mallocFloat(16)));
                GL20.glUniformMatrix4fv(2, false, TARGET.get(stack.mallocFloat(16)));
            }
            blit.blit();
            if (!loggedMerge) {
                loggedMerge = true;
                LOGGER.info("[Voxy Atmosphere Compat] merged LOD depth into the cloud depth.");
            }
            GL45.glBindTextureUnit(0, 0);
            GL30.glBindVertexArray(0);
            GlStateManager._glUseProgram(0);
            final ShaderInstance any = GameRenderer.getPositionShader();
            if (any != null)
                any.clear();
            GlStateManager._activeTexture(GL30.GL_TEXTURE0);
            GlStateManager._bindTexture(0);
            RenderSystem.colorMask(true, true, true, true);
        } catch (final Throwable t) {
            failed = true;
            RenderSystem.colorMask(true, true, true, true);
            LOGGER.error("[Voxy Atmosphere Compat] could not merge Voxy LOD depth for clouds; disabled", t);
        }
    }

    private IrisLodDepth() {}
}
