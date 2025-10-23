package com.openmason.ui.components.textureCreator.commands;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;

import java.util.HashMap;
import java.util.Map;

/**
 * Command for translating (moving) a rectangular selection.
 * Implements cut-and-paste behavior: original location becomes transparent.
 *
 * SOLID: Single responsibility - handles selection translation only
 * Command Pattern: Supports undo/redo
 *
 * @author Open Mason Team
 */
public class TranslateSelectionCommand implements Command {

    private final PixelCanvas canvas;
    private final RectangularSelection originalSelection;
    private final int deltaX;
    private final int deltaY;

    // Pixel backup for undo (sparse storage)
    private final Map<Integer, Integer> originalPixels; // Pixels from original selection area
    private final Map<Integer, Integer> destinationOriginalPixels; // Pixels that were at destination

    /**
     * Create a translate selection command.
     *
     * @param canvas The pixel canvas to modify
     * @param originalSelection The original selection bounds
     * @param deltaX Horizontal translation distance
     * @param deltaY Vertical translation distance
     */
    public TranslateSelectionCommand(PixelCanvas canvas, RectangularSelection originalSelection,
                                     int deltaX, int deltaY) {
        this.canvas = canvas;
        this.originalSelection = originalSelection;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.originalPixels = new HashMap<>();
        this.destinationOriginalPixels = new HashMap<>();

        // Backup original pixels
        backupPixels();
    }

    /**
     * Backup pixels from both source and destination regions.
     */
    private void backupPixels() {
        int x1 = originalSelection.getX1();
        int y1 = originalSelection.getY1();
        int x2 = originalSelection.getX2();
        int y2 = originalSelection.getY2();

        // Backup source pixels (will be cleared to transparent)
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (canvas.isValidCoordinate(x, y)) {
                    int key = y * canvas.getWidth() + x;
                    originalPixels.put(key, canvas.getPixel(x, y));
                }
            }
        }

        // Backup destination pixels (will be overwritten)
        int destX1 = x1 + deltaX;
        int destY1 = y1 + deltaY;
        int destX2 = x2 + deltaX;
        int destY2 = y2 + deltaY;

        for (int y = destY1; y <= destY2; y++) {
            for (int x = destX1; x <= destX2; x++) {
                if (canvas.isValidCoordinate(x, y)) {
                    int key = y * canvas.getWidth() + x;
                    // Only backup if not already in original pixels (avoid double-backup for overlap)
                    if (!originalPixels.containsKey(key)) {
                        destinationOriginalPixels.put(key, canvas.getPixel(x, y));
                    }
                }
            }
        }
    }

    @Override
    public void execute() {
        // Temporarily disable selection constraints for this operation
        canvas.setActiveSelection(null);

        try {
            int x1 = originalSelection.getX1();
            int y1 = originalSelection.getY1();
            int x2 = originalSelection.getX2();
            int y2 = originalSelection.getY2();

            // Step 1: Clear original location to transparent (cut behavior)
            // Do this first, using saved pixels to paste later
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    if (canvas.isValidCoordinate(x, y)) {
                        canvas.setPixel(x, y, 0x00000000); // Transparent
                    }
                }
            }

            // Step 2: Paste saved pixels to destination
            // This happens after clearing, so overlap doesn't matter
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    int destX = x + deltaX;
                    int destY = y + deltaY;

                    if (canvas.isValidCoordinate(destX, destY)) {
                        int key = y * canvas.getWidth() + x;
                        Integer pixelColor = originalPixels.get(key);
                        if (pixelColor != null) {
                            canvas.setPixel(destX, destY, pixelColor);
                        }
                    }
                }
            }
        } finally {
            // Re-enable selection constraints
            // (Will be set by CanvasPanel)
        }
    }

    @Override
    public void undo() {
        // Temporarily disable selection constraints
        canvas.setActiveSelection(null);

        try {
            // Restore original source pixels
            for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
                int key = entry.getKey();
                int x = key % canvas.getWidth();
                int y = key / canvas.getWidth();
                canvas.setPixel(x, y, entry.getValue());
            }

            // Restore original destination pixels
            for (Map.Entry<Integer, Integer> entry : destinationOriginalPixels.entrySet()) {
                int key = entry.getKey();
                int x = key % canvas.getWidth();
                int y = key / canvas.getWidth();
                canvas.setPixel(x, y, entry.getValue());
            }
        } finally {
            // Re-enable selection constraints
            // (Will be set by CanvasPanel)
        }
    }

    @Override
    public String getDescription() {
        return String.format("Translate selection by (%d, %d)", deltaX, deltaY);
    }

    /**
     * Check if translation actually moved pixels.
     * @return true if translation occurred
     */
    public boolean hasChanges() {
        return deltaX != 0 || deltaY != 0;
    }
}
