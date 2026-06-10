package com.openmason.main.systems.skija;

import static org.lwjgl.opengl.GL33.*;

/**
 * Restores OpenGL to a clean baseline after Skia painting. Skia mutates GL
 * state aggressively (shader program, VAO, buffers, blend, depth, scissor,
 * stencil, texture units) and {@code DirectContext.resetAll()} only clears
 * Skia's internal bookkeeping — actual GL bindings stay wherever Skia left
 * them. Without this restore, the ImGui GL3 backend and the 3D viewport
 * inherit stray bindings that cause silent rendering corruption.
 */
public final class SkijaGLStateGuard {

    private SkijaGLStateGuard() {
    }

    public static void restoreBaseline() {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDepthMask(true);
        glColorMask(true, true, true, true);
        glStencilMask(0xFF);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }
}
