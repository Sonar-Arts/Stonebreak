package com.openmason.main.systems.skija;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * RAII Skia surface backed by an offscreen OpenGL FBO whose color attachment
 * is a GL texture suitable for {@code ImGui.image()} composition.
 *
 * Allocation is rounded up in {@link #SIZE_STEP}px steps so dock-resize drags
 * don't rebuild the surface every frame; the logical size is tracked
 * separately and painting clips to it.
 *
 * Usage per frame:
 * <pre>
 *   surface.ensureSize(w, h);
 *   Canvas c = surface.beginPaint();   // clears to transparent
 *   ... skia draws ...
 *   surface.endPaint();                // flush + GL baseline restore
 *   ImGui.image(surface.getTextureId(), w, h);
 * </pre>
 */
public final class SkijaOffscreenSurface implements AutoCloseable {

    private static final int SIZE_STEP = 32;
    private static final int STENCIL_BITS = 8;

    private final SkijaContext context;

    private int textureId = -1;
    private int fboId = -1;
    private int rboId = -1;
    private BackendRenderTarget renderTarget;
    private Surface surface;

    /** Allocated (rounded-up) dimensions. */
    private int allocWidth;
    private int allocHeight;

    /** Logical dimensions requested by the caller. */
    private int width;
    private int height;

    private boolean painting = false;

    public SkijaOffscreenSurface(SkijaContext context) {
        if (context == null || !context.isAlive()) {
            throw new IllegalArgumentException("SkijaContext must be initialized");
        }
        this.context = context;
    }

    /**
     * Ensure the surface can hold {@code w x h} pixels, reallocating only when
     * the rounded allocation size changes.
     */
    public void ensureSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        this.width = w;
        this.height = h;

        int neededW = roundUp(w);
        int neededH = roundUp(h);
        if (surface != null && neededW == allocWidth && neededH == allocHeight) {
            return;
        }
        rebuild(neededW, neededH);
    }

    private static int roundUp(int v) {
        return ((v + SIZE_STEP - 1) / SIZE_STEP) * SIZE_STEP;
    }

    private void rebuild(int w, int h) {
        disposeGpuResources();

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glBindTexture(GL_TEXTURE_2D, 0);

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureId, 0);

        // Skia clip paths require a stencil buffer
        rboId = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, rboId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboId);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            disposeGpuResources();
            throw new IllegalStateException("Skija offscreen FBO incomplete: 0x" + Integer.toHexString(status));
        }

        renderTarget = BackendRenderTarget.makeGL(
                w, h,
                /*samples*/ 0,
                STENCIL_BITS,
                fboId,
                FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.wrapBackendRenderTarget(
                context.get(), renderTarget,
                SurfaceOrigin.TOP_LEFT,
                ColorType.RGBA_8888,
                ColorSpace.getSRGB());

        allocWidth = w;
        allocHeight = h;
    }

    /**
     * Resync Skia with current GL state, clear to transparent, and return the
     * canvas clipped to the logical size.
     */
    public Canvas beginPaint() {
        if (surface == null) {
            throw new IllegalStateException("ensureSize() must be called before beginPaint()");
        }
        if (painting) {
            throw new IllegalStateException("beginPaint() called twice without endPaint()");
        }
        painting = true;
        context.get().resetAll();
        Canvas canvas = surface.getCanvas();
        canvas.save();
        canvas.clipRect(io.github.humbleui.types.Rect.makeWH(width, height));
        canvas.clear(0x00000000);
        return canvas;
    }

    /**
     * Flush queued Skia work into the FBO and restore GL to a clean baseline
     * so ImGui and other LWJGL consumers see expected state.
     */
    public void endPaint() {
        if (!painting) {
            return;
        }
        painting = false;
        surface.getCanvas().restore();
        context.get().flushAndSubmit(surface);
        context.get().resetAll();
        SkijaGLStateGuard.restoreBaseline();
    }

    public int getTextureId() {
        return textureId;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Allocated texture width — needed to compute UV extent for ImGui.image. */
    public int getAllocatedWidth() {
        return allocWidth;
    }

    /** Allocated texture height — needed to compute UV extent for ImGui.image. */
    public int getAllocatedHeight() {
        return allocHeight;
    }

    @Override
    public void close() {
        disposeGpuResources();
    }

    private void disposeGpuResources() {
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (renderTarget != null) {
            renderTarget.close();
            renderTarget = null;
        }
        if (rboId != -1) {
            glDeleteRenderbuffers(rboId);
            rboId = -1;
        }
        if (fboId != -1) {
            glDeleteFramebuffers(fboId);
            fboId = -1;
        }
        if (textureId != -1) {
            glDeleteTextures(textureId);
            textureId = -1;
        }
        allocWidth = 0;
        allocHeight = 0;
    }
}
