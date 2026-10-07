package com.openmason.engine.ui.rendering;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The GL baseline Stonebreak's renderers rely on after UI painting
 * ({@link GlStatePolicy#RESET_TO_BASELINE}). Skia's {@code resetAll()} only clears Skia's
 * bookkeeping; it leaves real bindings wherever Skia last touched them.
 *
 * <p>Why each step exists: the first NanoVG frame after Skia used to inherit a stray program, a
 * VAO, an enabled scissor test and a texture on unit 0, which corrupted the main menu after
 * world select. Skia (GL 3.3+) also samples through <em>sampler objects</em> and leaves them
 * bound; a leftover CLAMP_TO_EDGE sampler on the block-array unit overrides the texture's
 * GL_REPEAT, so every greedy-merged terrain quad smeared its edge texels and stayed that way
 * after the UI closed. So samplers and 2D bindings are cleared on every unit Skia may use.
 */
public final class GlBaseline {

    /** Texture units Skia may have touched; the game's renderers use 0..8. */
    static final int RESET_TEXTURE_UNITS = 16;

    private static final boolean SAMPLER_DEBUG = Boolean.getBoolean("stonebreak.skia.debug");

    private GlBaseline() {
    }

    /**
     * Resets to the baseline with {@code framebufferId} bound and the viewport covering its
     * {@code width x height} pixels (Skia sets the viewport to whatever surface it drew last).
     */
    public static void reset(int framebufferId, int width, int height) {
        reset(framebufferId);
        if (width > 0 && height > 0) {
            glViewport(0, 0, width, height);
        }
    }

    /** Resets to the baseline with {@code framebufferId} bound (0 for the game window); viewport untouched. */
    public static void reset(int framebufferId) {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebufferId);
        for (int unit = RESET_TEXTURE_UNITS - 1; unit >= 0; unit--) {
            glActiveTexture(GL_TEXTURE0 + unit);
            if (SAMPLER_DEBUG) {
                int sampler = glGetInteger(GL_SAMPLER_BINDING);
                int tex2d = glGetInteger(GL_TEXTURE_BINDING_2D);
                if (sampler != 0 || tex2d != 0) {
                    System.out.println("[skia] unit " + unit + " after paint: sampler=" + sampler
                            + " tex2d=" + tex2d);
                }
            }
            glBindSampler(unit, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);
        glColorMask(true, true, true, true);
        glStencilMask(0xFF);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glDisable(GL_COLOR_LOGIC_OP);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_ADD);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBlendColor(0, 0, 0, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    }
}
