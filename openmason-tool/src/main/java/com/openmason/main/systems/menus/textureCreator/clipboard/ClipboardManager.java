package com.openmason.main.systems.menus.textureCreator.clipboard;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;

/**
 * Simple clipboard manager for copy/cut/paste operations.
 */
public class ClipboardManager {

    private static final Logger logger = LoggerFactory.getLogger(ClipboardManager.class);

    // Clipboard data
    private PixelCanvas clipboardCanvas = null;
    private int sourceX = 0;
    private int sourceY = 0;
    private SelectionRegion sourceSelection = null;

    /**
     * Copy pixels from canvas within selection bounds to clipboard.
     */
    public void copy(PixelCanvas canvas, SelectionRegion selection) {
        if (canvas == null) {
            logger.warn("Cannot copy: canvas is null");
            return;
        }

        if (selection == null || selection.isEmpty()) {
            logger.warn("Cannot copy: no selection");
            return;
        }

        Rectangle bounds = selection.getBounds();

        // Create clipboard canvas with selection bounds size
        clipboardCanvas = new PixelCanvas(bounds.width, bounds.height);

        // Copy pixels within selection
        int pixelsCopied = 0;
        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                int canvasX = bounds.x + x;
                int canvasY = bounds.y + y;

                if (canvas.isValidCoordinate(canvasX, canvasY) && selection.contains(canvasX, canvasY)) {
                    int color = canvas.getPixel(canvasX, canvasY);
                    clipboardCanvas.setPixel(x, y, color);
                    pixelsCopied++;
                }
            }
        }

        // Store source position
        this.sourceX = bounds.x;
        this.sourceY = bounds.y;
        this.sourceSelection = selection;

        logger.info("Copied {} pixels from selection at ({}, {}) size {}x{}",
                   pixelsCopied, bounds.x, bounds.y, bounds.width, bounds.height);
    }

    /**
     * Cut pixels from canvas (copy then clear to transparent).
     * Creates a copy of the selection, then clears the selected pixels.
     *
     * @param canvas source canvas to cut from
     * @param selection selection region defining what to cut
     * @param command command for undo/redo support (records cleared pixels)
     */
    public void cut(PixelCanvas canvas, SelectionRegion selection,
                   DrawCommand command) {
        // First copy to clipboard
        copy(canvas, selection);

        if (!hasData()) {
            logger.warn("Cut operation failed - no data copied");
            return;
        }

        // Then clear the selected pixels to transparent
        Rectangle bounds = selection.getBounds();
        int pixelsCleared = 0;

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (canvas.isValidCoordinate(x, y) && selection.contains(x, y)) {
                    int oldColor = canvas.getPixel(x, y);
                    int newColor = 0x00000000; // Transparent

                    if (oldColor != newColor) {
                        if (command != null) {
                            command.recordPixelChange(x, y, oldColor, newColor);
                        }
                        canvas.setPixel(x, y, newColor);
                        pixelsCleared++;
                    }
                }
            }
        }

        logger.info("Cut {} pixels - copied to clipboard and cleared", pixelsCleared);
    }

    /**
     * Get the clipboard canvas data.
     * @return clipboard canvas, or null if empty
     */
    public PixelCanvas getClipboardCanvas() {
        return clipboardCanvas;
    }

    /**
     * Get the source X position where clipboard was copied from.
     * Used for paste positioning at original location.
     * @return source X coordinate
     */
    public int getSourceX() {
        return sourceX;
    }

    /**
     * Get the source Y position where clipboard was copied from.
     * Used for paste positioning at original location.
     * @return source Y coordinate
     */
    public int getSourceY() {
        return sourceY;
    }

    /**
     * Check if clipboard has data.
     * @return true if clipboard contains data, false if empty
     */
    public boolean hasData() {
        return clipboardCanvas != null;
    }

    /**
     * Clear clipboard data.
     */
    public void clear() {
        clipboardCanvas = null;
        sourceSelection = null;
        sourceX = 0;
        sourceY = 0;
        logger.debug("Clipboard cleared");
    }
}
