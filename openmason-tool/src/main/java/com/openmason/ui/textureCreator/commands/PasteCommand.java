package com.openmason.ui.textureCreator.commands;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.selection.SelectionRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Command for pasting clipboard content to canvas.
 *
 * Records all pixel changes for undo/redo support.
 * Follows Command pattern for integration with command history.
 *
 * @author Open Mason Team
 */
public class PasteCommand implements Command {

    private static final Logger logger = LoggerFactory.getLogger(PasteCommand.class);

    private final PixelCanvas targetCanvas;
    private final PixelCanvas clipboardCanvas;
    private final int pasteX;
    private final int pasteY;
    private final SelectionRegion pastedSelection;
    private final boolean skipTransparentPixels;

    // Pixel changes: key = encoded coordinate (y * width + x), value = [oldColor, newColor]
    private final Map<Integer, int[]> pixelChanges = new HashMap<>();

    private boolean executed = false;

    /**
     * Create paste command.
     *
     * @param targetCanvas canvas to paste onto
     * @param clipboardCanvas clipboard data to paste
     * @param pasteX X position to paste at
     * @param pasteY Y position to paste at
     * @param pastedSelection selection region after paste (for selection update)
     * @param skipTransparentPixels if true, fully transparent pixels won't overwrite existing pixels
     */
    public PasteCommand(PixelCanvas targetCanvas, PixelCanvas clipboardCanvas,
                       int pasteX, int pasteY, SelectionRegion pastedSelection,
                       boolean skipTransparentPixels) {
        this.targetCanvas = targetCanvas;
        this.clipboardCanvas = clipboardCanvas;
        this.pasteX = pasteX;
        this.pasteY = pasteY;
        this.pastedSelection = pastedSelection;
        this.skipTransparentPixels = skipTransparentPixels;
    }

    @Override
    public void execute() {
        if (executed) {
            // Re-execute: apply recorded changes
            redo();
            return;
        }

        // First execution: paste and record changes
        int pixelsPasted = 0;

        for (int y = 0; y < clipboardCanvas.getHeight(); y++) {
            for (int x = 0; x < clipboardCanvas.getWidth(); x++) {
                int targetX = pasteX + x;
                int targetY = pasteY + y;

                if (targetCanvas.isValidCoordinate(targetX, targetY)) {
                    int oldColor = targetCanvas.getPixel(targetX, targetY);
                    int newColor = clipboardCanvas.getPixel(x, y);

                    // Check transparency based on preference
                    // If skipTransparentPixels is true, only paste non-transparent pixels (preserve transparency)
                    // If skipTransparentPixels is false, paste all pixels including transparent ones
                    int[] rgba = PixelCanvas.unpackRGBA(newColor);
                    if (!skipTransparentPixels || rgba[3] > 0) { // Skip transparent pixels if preference enabled
                        // Record change
                        int key = targetY * targetCanvas.getWidth() + targetX;
                        pixelChanges.put(key, new int[]{oldColor, newColor});

                        // Apply change
                        targetCanvas.setPixel(targetX, targetY, newColor);
                        pixelsPasted++;
                    }
                }
            }
        }

        executed = true;
        logger.info("Pasted {} pixels at ({}, {})", pixelsPasted, pasteX, pasteY);
    }

    @Override
    public void undo() {
        if (!executed) {
            logger.warn("Cannot undo paste - not yet executed");
            return;
        }

        // Restore old colors
        for (Map.Entry<Integer, int[]> entry : pixelChanges.entrySet()) {
            int key = entry.getKey();
            int[] colors = entry.getValue();
            int oldColor = colors[0];

            int x = key % targetCanvas.getWidth();
            int y = key / targetCanvas.getWidth();

            targetCanvas.setPixel(x, y, oldColor);
        }

        logger.debug("Undid paste operation");
    }

    /**
     * Redo the paste operation (internal helper for execute).
     * Re-executes by reapplying recorded changes.
     */
    private void redo() {
        if (!executed) {
            logger.warn("Cannot redo paste - not yet executed");
            return;
        }

        // Reapply new colors
        for (Map.Entry<Integer, int[]> entry : pixelChanges.entrySet()) {
            int key = entry.getKey();
            int[] colors = entry.getValue();
            int newColor = colors[1];

            int x = key % targetCanvas.getWidth();
            int y = key / targetCanvas.getWidth();

            targetCanvas.setPixel(x, y, newColor);
        }

        logger.debug("Redid paste operation");
    }

    @Override
    public String getDescription() {
        return String.format("Paste at (%d, %d)", pasteX, pasteY);
    }

    /**
     * Check if this paste made any changes.
     * @return true if pixels were changed
     */
    public boolean hasChanges() {
        return !pixelChanges.isEmpty();
    }

    /**
     * Get the selection region after paste.
     * Used to update the active selection to the pasted region.
     * @return pasted selection region
     */
    public SelectionRegion getPastedSelection() {
        return pastedSelection;
    }
}
