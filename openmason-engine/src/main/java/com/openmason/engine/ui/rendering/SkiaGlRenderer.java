package com.openmason.engine.ui.rendering;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

/**
 * Paints with Skia into a {@link UiRenderTarget} on the current GL context. One renderer per GL
 * context: it owns the Skia {@link DirectContext} and a surface wrapping the target's
 * framebuffer, rebuilt only when the framebuffer, size or origin changes.
 *
 * <p><b>Frame order.</b> {@link #begin} captures GL state (RESTORE policy), resyncs Skia with
 * the real GL state and returns the canvas; draws follow; {@link #end} flushes and submits,
 * then restores the captured state or resets to the baseline with the target's framebuffer
 * bound. Neither clears: clearing is the caller's first draw.
 *
 * <p><b>Exceptions.</b> {@code end} must run even when drawing throws ({@code try/finally}),
 * so GL state is never left in Skia's hands. {@code begin} throws before touching GL when the
 * renderer is not initialized or a paint is already open.
 *
 * <p><b>Context loss.</b> After the GL context is destroyed, call {@link #abandon()} (Skia
 * must not free GPU objects through a dead context), then {@link #init()} on the new context.
 * All calls belong on the GL thread.
 */
public final class SkiaGlRenderer implements AutoCloseable {

    private DirectContext context;
    private BackendRenderTarget renderTarget;
    private Surface surface;
    private UiRenderTarget surfaceTarget;
    private UiRenderTarget painting;
    private GlStateSnapshot saved;

    /**
     * Creates the Skia context for the current GL context. Idempotent. Skia configures GL while
     * it sets up (it disabled a caller's depth test in testing), so the caller's state is put
     * back afterwards.
     */
    public void init() {
        if (context == null) {
            GlStateSnapshot state = GlStateSnapshot.capture();
            try {
                context = DirectContext.makeGL();
            } finally {
                state.restore();
            }
        }
    }

    public boolean isInitialized() {
        return context != null;
    }

    public boolean isPainting() {
        return painting != null;
    }

    public Canvas begin(UiRenderTarget target) {
        if (context == null) {
            throw new IllegalStateException("SkiaGlRenderer not initialized");
        }
        if (painting != null) {
            throw new IllegalStateException("begin() while a paint is open");
        }
        saved = target.policy() == GlStatePolicy.RESTORE ? GlStateSnapshot.capture() : null;
        if (!target.sameSurface(surfaceTarget)) {
            rebuildSurface(target);
        }
        painting = target;
        context.resetAll();
        return surface.getCanvas();
    }

    public void end() {
        if (painting == null) {
            return;
        }
        UiRenderTarget target = painting;
        painting = null;
        try {
            context.flushAndSubmit(surface);
            context.resetAll();
        } finally {
            if (saved != null) {
                saved.restore();
                saved = null;
            } else {
                GlBaseline.reset(target.framebufferId());
            }
        }
    }

    /** Drops the surface (e.g. the target's framebuffer is about to be deleted). */
    public void releaseSurface() {
        if (painting != null) {
            throw new IllegalStateException("releaseSurface() while painting");
        }
        if (surface != null) {
            GlStateSnapshot state = GlStateSnapshot.capture();
            try {
                disposeSurface();
            } finally {
                state.restore();
            }
        }
    }

    /** The GL context is gone: forget GPU objects without freeing them through it. */
    public void abandon() {
        painting = null;
        saved = null;
        if (context != null) {
            context.abandon();
        }
        surface = null;
        renderTarget = null;
        surfaceTarget = null;
        context = null;
    }

    /** Frees Skia's GPU objects through the current context; the caller's GL state survives. */
    @Override
    public void close() {
        if (context == null && surface == null) {
            return;
        }
        GlStateSnapshot state = GlStateSnapshot.capture();
        try {
            disposeSurface();
            if (context != null) {
                context.close();
                context = null;
            }
        } finally {
            state.restore();
        }
    }

    private void rebuildSurface(UiRenderTarget target) {
        disposeSurface();
        renderTarget = BackendRenderTarget.makeGL(target.width(), target.height(), /*samples*/ 0,
                /*stencilBits*/ 8, target.framebufferId(), FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.wrapBackendRenderTarget(context, renderTarget,
                target.origin() == UiRenderTarget.Origin.TOP_LEFT ? SurfaceOrigin.TOP_LEFT : SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888, ColorSpace.getSRGB());
        if (surface == null) {
            disposeSurface();
            throw new IllegalStateException("Skia cannot wrap framebuffer " + target.framebufferId()
                    + " (" + target.width() + "x" + target.height() + ")");
        }
        surfaceTarget = target;
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
        surfaceTarget = null;
    }
}
