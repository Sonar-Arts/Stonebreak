package com.stonebreak.rendering.UI.backend.skija;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import static org.lwjgl.opengl.GL33.*;

/**
 * Owns the Skia {@link DirectContext} that wraps the running OpenGL context,
 * along with the {@link Surface} used to paint to the default framebuffer.
 *
 * Skia mutates GL state aggressively (shader, VAO, blend, depth, scissor,
 * texture units). Callers must invoke {@link #beginPaint()} before issuing
 * Skia draws and {@link #endPaint()} before LWJGL touches GL again.
 */
public final class SkiaContext {

    private DirectContext context;
    private BackendRenderTarget renderTarget;
    private Surface surface;

    private int width;
    private int height;

    public void init(int width, int height) {
        this.width = width;
        this.height = height;
        this.context = DirectContext.makeGL();
        rebuildSurface();
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        if (newWidth <= 0 || newHeight <= 0) return;
        this.width = newWidth;
        this.height = newHeight;
        if (context == null) return;
        rebuildSurface();
    }

    private void rebuildSurface() {
        disposeSurface();
        renderTarget = BackendRenderTarget.makeGL(
                width, height,
                /*samples*/ 0,
                /*stencilBits*/ 8,
                /*fbId*/ 0,
                FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.wrapBackendRenderTarget(
                context, renderTarget,
                SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888,
                ColorSpace.getSRGB());
    }

    /**
     * Resync Skia with current GL state and return the canvas for drawing.
     */
    public Canvas beginPaint() {
        if (context == null || surface == null) {
            throw new IllegalStateException("SkiaContext not initialized");
        }
        context.resetAll();
        return surface.getCanvas();
    }

    /**
     * Flush queued Skia work and resync GL so subsequent LWJGL calls see a
     * clean state.
     */
    public void endPaint() {
        if (context == null || surface == null) return;
        context.flushAndSubmit(surface);
        context.resetAll();
        restoreGLDefaults();
    }

    /**
     * Reset GL to a clean baseline that NanoVG and the game's 3D renderer can
     * trust. Skia's {@code resetAll()} only clears Skia's internal bookkeeping;
     * it leaves actual GL bindings wherever Skia last touched them, which
     * breaks NanoVG's dirt-texture pattern on the main menu after the player
     * visits the world-select screen.
     *
     * Why: if we don't do this, the first NanoVG frame after Skia inherits a
     * stray shader program, a VAO, an enabled scissor test, and a bound
     * texture in unit 0 — all of which cause silent rendering corruption.
     */
    private static void restoreGLDefaults() {
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

    public boolean isInitialized() {
        return context != null && surface != null;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void dispose() {
        disposeSurface();
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private void disposeSurface() {
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (renderTarget != null) {
            renderTarget.close();
            renderTarget = null;
        }
    }
}
