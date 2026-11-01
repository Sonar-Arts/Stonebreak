package com.openmason.engine.ui.rendering;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Typeface;

/**
 * Masonry on the GPU through Skia, drawing into a {@link UiRenderTarget}: the game window or an
 * offscreen framebuffer. The target is set by the host each frame or once
 * ({@link #setTarget}); {@link #beginFrame(int, int, float)} keeps the current target and
 * adopts the new size, which is how the game window follows resizes.
 *
 * <p>Not final: Stonebreak's {@code SkijaUIBackend} adds its menu assets on top, and headless
 * tests substitute a raster canvas by overriding {@link #getCanvas()}/{@link #isAvailable()}.
 */
public class GpuMasonryBackend extends MasonryBackend {

    private final SkiaGlRenderer renderer = new SkiaGlRenderer();
    private UiRenderTarget target;
    private Typeface typeface;
    private int frameDepth;
    private Canvas currentCanvas;

    /** Creates the Skia context on the current GL context and sets the initial target. */
    public void initialize(UiRenderTarget initialTarget) {
        renderer.init();
        this.target = initialTarget;
    }

    /** Draw the next frames into {@code newTarget}. Not allowed while a frame is open. */
    public void setTarget(UiRenderTarget newTarget) {
        if (frameDepth > 0) {
            throw new IllegalStateException("setTarget() inside a frame");
        }
        this.target = newTarget;
    }

    public UiRenderTarget target() {
        return target;
    }

    /** Takes ownership of {@code face}; the previous one is closed. */
    protected void setTypeface(Typeface face) {
        if (typeface != null && typeface != face) {
            typeface.close();
        }
        typeface = face;
    }

    @Override
    public Typeface typeface() {
        return typeface;
    }

    @Override
    public void beginFrame(int width, int height, float pixelRatio) {
        if (!renderer.isInitialized() || target == null) {
            return;
        }
        if (frameDepth == 0 && width > 0 && height > 0
                && (width != target.width() || height != target.height() || pixelRatio != target.pixelRatio())) {
            target = target.withSize(width, height, pixelRatio);
        }
        beginFrame();
    }

    /** Opens a frame on the current target. */
    public void beginFrame() {
        if (!renderer.isInitialized() || target == null) {
            return;
        }
        frameDepth++;
        if (frameDepth == 1) {
            try {
                currentCanvas = renderer.begin(target);
            } catch (RuntimeException e) {
                frameDepth = 0;
                throw e;
            }
        }
    }

    @Override
    public void endFrame() {
        if (frameDepth == 0) {
            return;
        }
        frameDepth--;
        if (frameDepth == 0) {
            currentCanvas = null;
            renderer.end();
        }
    }

    /** Resize only: the next frame paints at this size. */
    public void resize(int width, int height) {
        if (target != null && width > 0 && height > 0 && frameDepth == 0) {
            target = target.withSize(width, height, target.pixelRatio());
        }
    }

    @Override
    public boolean isAvailable() {
        return renderer.isInitialized();
    }

    @Override
    public Canvas getCanvas() {
        if (frameDepth == 0 || currentCanvas == null) {
            throw new IllegalStateException("No active Skija frame");
        }
        return currentCanvas;
    }

    /** The target's framebuffer is about to be deleted or recreated. */
    public void releaseTargetSurface() {
        renderer.releaseSurface();
    }

    /** The GL context was lost; call {@link #initialize} again on the new one. */
    public void abandonContext() {
        frameDepth = 0;
        currentCanvas = null;
        renderer.abandon();
    }

    @Override
    public void dispose() {
        frameDepth = 0;
        currentCanvas = null;
        setTypeface(null);
        renderer.close();
    }
}
