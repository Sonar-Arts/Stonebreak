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
    private static final boolean SAMPLER_DEBUG = Boolean.getBoolean("stonebreak.skia.debug");
    /** Texture units Skia may have touched; the game's renderers use 0..7. */
    private static final int RESET_TEXTURE_UNITS = 16;

    private static void restoreGLDefaults() {
        glUseProgram(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        // Skia (GL 3.3+) samples through SAMPLER OBJECTS and leaves them bound
        // on every unit it used. A sampler object overrides the bound
        // texture's own parameters, so a leftover Skia sampler (CLAMP_TO_EDGE)
        // on the world's block-array unit silently turns GL_REPEAT into clamp:
        // every greedy-merged terrain quad then smears its edge texels across
        // the whole face, and it stays that way after the UI closes. Unbind
        // them all, and clear the 2D/array bindings Skia left on those units.
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
