package com.openmason.engine.ui.rendering;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Typeface;

/**
 * What Masonry draws through: a canvas for the frame in progress plus the shared typeface.
 * Implementations: {@link GpuMasonryBackend} (game window, editor preview FBO) and
 * {@link RasterMasonryBackend} (CPU reference path, headless tests, flicker-free editor
 * upload). Every widget paints through the same {@code MasonryUI} code whichever backend is
 * active, which is what makes their output comparable.
 *
 * <p><b>Frames nest.</b> {@link #beginFrame}/{@link #endFrame} pairs may nest (a screen that
 * draws a sub-screen); only the outermost pair opens and flushes the target.
 *
 * <p><b>Ownership.</b> The backend owns its typeface and target resources; screens own only
 * their {@code MasonryUI} font cache. Disposing one screen therefore never invalidates another.
 * {@link #dispose()} belongs on the render thread.
 */
public abstract class MasonryBackend implements AutoCloseable {

    /** Canvas of the frame in progress. */
    public abstract Canvas getCanvas();

    public abstract boolean isAvailable();

    /** Typeface for Masonry text, or {@code null} when none is loaded (text is then skipped). */
    public abstract Typeface typeface();

    public abstract void beginFrame(int width, int height, float pixelRatio);

    public abstract void endFrame();

    public abstract void dispose();

    @Override
    public final void close() {
        dispose();
    }
}
