package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade for the MasonryUI widget library.
 *
 * One instance per menu screen — owns the shared {@link MFonts} cache and
 * exposes the active {@link Canvas} during a frame. Widgets receive a
 * MasonryUI reference in their render calls so they never import Skija's
 * backend directly, keeping imports tidy and tests tractable.
 *
 * The class also collects "overlay" render callbacks (open dropdowns, popups)
 * so the caller doesn't need to track each dropdown by hand the way the old
 * NanoVG settings renderer did. Widgets push themselves onto the overlay
 * queue when they render in a stateful "open" configuration, and the screen
 * renderer flushes the queue via {@link #renderOverlays()} after all
 * foreground widgets have drawn.
 *
 * GL state bracketing lives in {@link SkijaUIBackend} / {@code SkiaContext};
 * MasonryUI does not re-implement it. Call {@link #beginFrame} to begin a
 * paint and {@link #endFrame} to flush — the backend's restore-to-defaults
 * logic covers the Skija↔NanoVG boundary already.
 */
public final class MasonryUI {

    private final SkijaUIBackend backend;
    private final MFonts fonts;
    private final List<Runnable> overlays = new ArrayList<>();

    private boolean frameActive;

    public MasonryUI(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
    }

    public boolean isAvailable() {
        return backend != null && backend.isAvailable();
    }

    public SkijaUIBackend backend() {
        return backend;
    }

    public MFonts fonts() {
        return fonts;
    }

    public Canvas canvas() {
        return backend != null ? backend.getCanvas() : null;
    }

    /**
     * Begin a paint frame. Idempotent guard against nested begins.
     * @return false if the backend isn't ready (caller should skip rendering)
     */
    public boolean beginFrame(int width, int height, float pixelRatio) {
        if (!isAvailable() || frameActive) return false;
        backend.beginFrame(width, height, pixelRatio);
        overlays.clear();
        frameActive = true;
        return true;
    }

    /**
     * Register a callback to draw after normal widgets — dropdowns, tooltips,
     * any content that must layer on top regardless of iteration order.
     */
    public void pushOverlay(Runnable overlay) {
        if (overlay != null) overlays.add(overlay);
    }

    /**
     * Flush overlay queue. Call once, just before endFrame().
     */
    public void renderOverlays() {
        for (Runnable overlay : overlays) overlay.run();
        overlays.clear();
    }

    public void endFrame() {
        if (!frameActive) return;
        backend.endFrame();
        frameActive = false;
    }

    public void dispose() {
        fonts.dispose();
    }
}
