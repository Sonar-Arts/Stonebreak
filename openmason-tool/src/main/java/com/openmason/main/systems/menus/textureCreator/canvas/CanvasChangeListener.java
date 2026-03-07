package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * Observer interface for receiving notifications when a {@link PixelCanvas} is modified.
 *
 * <p>Listeners receive a dirty rectangle indicating which region of the canvas changed.
 * This enables efficient partial updates (e.g. GPU texture sub-image uploads) rather
 * than re-uploading the entire canvas each frame.
 */
public interface CanvasChangeListener {

    /**
     * Called when pixels within the canvas have been modified.
     *
     * @param canvas      the canvas that was modified
     * @param dirtyX      left edge of the dirty region (pixels)
     * @param dirtyY      top edge of the dirty region (pixels)
     * @param dirtyWidth  width of the dirty region (pixels)
     * @param dirtyHeight height of the dirty region (pixels)
     */
    void onCanvasChanged(PixelCanvas canvas, int dirtyX, int dirtyY, int dirtyWidth, int dirtyHeight);
}
