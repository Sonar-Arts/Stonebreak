package com.openmason.main.systems.menus.textureCreator.tools.paste;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.FloatingPixelLayer;

/**
 * Floating preview layer for paste operations.
 */
public class PastePreviewLayer extends FloatingPixelLayer {

    private final PixelCanvas clipboardCanvas;
    private final int pasteX;
    private final int pasteY;

    /**
     * Create paste preview layer.
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
}
