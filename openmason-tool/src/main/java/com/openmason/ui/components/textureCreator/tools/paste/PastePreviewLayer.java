package com.openmason.ui.components.textureCreator.tools.paste;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.layers.FloatingPixelLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Floating preview layer for paste operations.
 *
 * <p>Shows clipboard content at specified position before committing to canvas.
 * Extends {@link FloatingPixelLayer} for non-destructive preview generation.</p>
 *
 * <p>This implementation handles simple position-based compositing without transformation.</p>
 *
 * @author Open Mason Team
 */
public class PastePreviewLayer extends FloatingPixelLayer {

    private static final Logger logger = LoggerFactory.getLogger(PastePreviewLayer.class);

    private final PixelCanvas clipboardCanvas;
    private final int pasteX;
    private final int pasteY;

    /**
     * Create paste preview layer.
     *
     * @param clipboardCanvas clipboard content to preview
     * @param pasteX X position to paste at
     * @param pasteY Y position to paste at
     */
    public PastePreviewLayer(PixelCanvas clipboardCanvas, int pasteX, int pasteY) {
        if (clipboardCanvas == null) {
            throw new IllegalArgumentException("Clipboard canvas cannot be null");
        }

        this.clipboardCanvas = clipboardCanvas;
        this.pasteX = pasteX;
        this.pasteY = pasteY;
    }

    /**
     * Get the floating pixel color at the specified canvas coordinate.
     *
     * <p>Returns the clipboard pixel color if the canvas coordinate maps to a position
     * within the clipboard bounds, otherwise returns null.</p>
     *
     * @param canvasX canvas X coordinate
     * @param canvasY canvas Y coordinate
     * @return clipboard pixel color, or null if no clipboard pixel at this position
     */
    @Override
    protected Integer getFloatingPixelAt(int canvasX, int canvasY) {
        // Calculate clipboard coordinate
        int clipboardX = canvasX - pasteX;
        int clipboardY = canvasY - pasteY;

        // Check if position is within clipboard bounds
        if (clipboardX < 0 || clipboardX >= clipboardCanvas.getWidth() ||
            clipboardY < 0 || clipboardY >= clipboardCanvas.getHeight()) {
            return null;  // No clipboard pixel at this canvas position
        }

        // Return clipboard pixel color
        int color = clipboardCanvas.getPixel(clipboardX, clipboardY);

        // Return null for fully transparent pixels (optimization)
        if (isTransparent(color)) {
            return null;
        }

        return color;
    }

    /**
     * Get paste X position.
     * @return X coordinate
     */
    public int getPasteX() {
        return pasteX;
    }

    /**
     * Get paste Y position.
     * @return Y coordinate
     */
    public int getPasteY() {
        return pasteY;
    }

    /**
     * Get clipboard canvas.
     * @return clipboard content
     */
    public PixelCanvas getClipboardCanvas() {
        return clipboardCanvas;
    }
}
