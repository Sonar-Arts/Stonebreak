package com.openmason.main.systems.scripting.doc;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;

/**
 * The texture editor's canvas as a script target — the seam behind the
 * {@code om.canvas} / {@code canvas_*} command domain.
 *
 * <p>Live-only: {@code ScriptingService} constructs one per run when the
 * texture editor is open; otherwise the command layer raises a teaching error.
 */
public interface CanvasSurface {

    /** The editor's layer stack. */
    LayerManager layers();

    /** The active layer's canvas, or null when there is none. */
    PixelCanvas activeCanvas();

    /**
     * Called by the command layer before EVERY mutation — the live surface
     * captures its before-snapshot lazily on the first call, so untouched
     * runs cost nothing and failed runs can roll the whole stack back.
     */
    default void beginMutation() {
    }

    /** Invalidate the editor preview after a mutation. */
    void notifyModified();

    /** Flatten visible layers and write a PNG; returns success. */
    boolean exportPng(String absolutePath);
}
