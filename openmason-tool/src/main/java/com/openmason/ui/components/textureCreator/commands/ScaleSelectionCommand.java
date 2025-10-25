package com.openmason.ui.components.textureCreator.commands;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;

import java.util.HashMap;
import java.util.Map;

/**
 * Command for scaling a rectangular selection.
 * Uses bilinear interpolation for smooth scaling.
 * <p>
 * SOLID: Single responsibility - handles selection scaling only
 * Command Pattern: Supports undo/redo
 * KISS: Straightforward scaling algorithm without over-engineering
 *
 * @author Open Mason Team
 */
public class ScaleSelectionCommand implements Command {

    private final PixelCanvas canvas;
    private final RectangularSelection originalSelection;
    private final RectangularSelection scaledSelection;

    // Pixel backup for undo (sparse storage)
    private final Map<Integer, Integer> affectedPixels; // All pixels that will be modified

    /**
     * Create a scale selection command.
     *
     * @param canvas The pixel canvas to modify
     * @param originalSelection The original selection bounds
     * @param scaledSelection The new scaled selection bounds
     */
    public ScaleSelectionCommand(PixelCanvas canvas, RectangularSelection originalSelection,
                                RectangularSelection scaledSelection) {
        this.canvas = canvas;
        this.originalSelection = originalSelection;
        this.scaledSelection = scaledSelection;
        this.affectedPixels = new HashMap<>();

        // Backup affected pixels
        backupPixels();
    }

    /**
     * Backup pixels from both source and destination regions.
     */
    private void backupPixels() {
        // Backup source pixels
        backupRegion(originalSelection.getX1(), originalSelection.getY1(),
                    originalSelection.getX2(), originalSelection.getY2());

        // Backup destination pixels (in case they don't overlap)
        backupRegion(scaledSelection.getX1(), scaledSelection.getY1(),
                    scaledSelection.getX2(), scaledSelection.getY2());
    }

    /**
     * Backup a rectangular region of pixels
     */
    private void backupRegion(int x1, int y1, int x2, int y2) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (canvas.isValidCoordinate(x, y)) {
                    int key = y * canvas.getWidth() + x;
                    if (!affectedPixels.containsKey(key)) {
                        affectedPixels.put(key, canvas.getPixel(x, y));
                    }
                }
            }
        }
    }

    @Override
    public void execute() {
        // Temporarily disable selection constraints
        canvas.setActiveSelection(null);

        try {
            // Extract original pixels
            int origX1 = originalSelection.getX1();
            int origY1 = originalSelection.getY1();
            int origX2 = originalSelection.getX2();
            int origY2 = originalSelection.getY2();
            int origWidth = origX2 - origX1 + 1;
            int origHeight = origY2 - origY1 + 1;

            // Create buffer for original pixels
            int[][] originalPixels = new int[origHeight][origWidth];
            for (int y = 0; y < origHeight; y++) {
                for (int x = 0; x < origWidth; x++) {
                    int canvasX = origX1 + x;
                    int canvasY = origY1 + y;
                    if (canvas.isValidCoordinate(canvasX, canvasY)) {
                        originalPixels[y][x] = canvas.getPixel(canvasX, canvasY);
                    }
                }
            }

            // Clear original region to transparent
            for (int y = origY1; y <= origY2; y++) {
                for (int x = origX1; x <= origX2; x++) {
                    if (canvas.isValidCoordinate(x, y)) {
                        canvas.setPixel(x, y, 0x00000000);
                    }
                }
            }

            // Scale and draw to new region
            int newX1 = scaledSelection.getX1();
            int newY1 = scaledSelection.getY1();
            int newX2 = scaledSelection.getX2();
            int newY2 = scaledSelection.getY2();
            int newWidth = newX2 - newX1 + 1;
            int newHeight = newY2 - newY1 + 1;

            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    // Map to original coordinates using bilinear interpolation
                    double srcX = (x / (double) (newWidth - 1)) * (origWidth - 1);
                    double srcY = (y / (double) (newHeight - 1)) * (origHeight - 1);

                    int color = sampleBilinear(originalPixels, srcX, srcY, origWidth, origHeight);

                    int canvasX = newX1 + x;
                    int canvasY = newY1 + y;
                    if (canvas.isValidCoordinate(canvasX, canvasY)) {
                        canvas.setPixel(canvasX, canvasY, color);
                    }
                }
            }
        } finally {
            // Selection constraints will be re-enabled by CanvasPanel
        }
    }

    /**
     * Samples a color from the source using bilinear interpolation
     */
    private int sampleBilinear(int[][] pixels, double x, double y, int width, int height) {
        // Get integer coordinates
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);

        // Get fractional parts
        double fx = x - x0;
        double fy = y - y0;

        // Clamp coordinates
        x0 = Math.max(0, Math.min(x0, width - 1));
        y0 = Math.max(0, Math.min(y0, height - 1));

        // Get four surrounding pixels
        int c00 = pixels[y0][x0];
        int c10 = pixels[y0][x1];
        int c01 = pixels[y1][x0];
        int c11 = pixels[y1][x1];

        // Interpolate each channel
        int a = interpolateChannel(c00, c10, c01, c11, fx, fy, 24);
        int r = interpolateChannel(c00, c10, c01, c11, fx, fy, 16);
        int g = interpolateChannel(c00, c10, c01, c11, fx, fy, 8);
        int b = interpolateChannel(c00, c10, c01, c11, fx, fy, 0);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Interpolates a single color channel
     */
    private int interpolateChannel(int c00, int c10, int c01, int c11, double fx, double fy, int shift) {
        int v00 = (c00 >> shift) & 0xFF;
        int v10 = (c10 >> shift) & 0xFF;
        int v01 = (c01 >> shift) & 0xFF;
        int v11 = (c11 >> shift) & 0xFF;

        double v0 = v00 * (1 - fx) + v10 * fx;
        double v1 = v01 * (1 - fx) + v11 * fx;
        double v = v0 * (1 - fy) + v1 * fy;

        return (int) Math.round(v);
    }

    @Override
    public void undo() {
        // Temporarily disable selection constraints
        canvas.setActiveSelection(null);

        try {
            // Restore all affected pixels
            for (Map.Entry<Integer, Integer> entry : affectedPixels.entrySet()) {
                int key = entry.getKey();
                int x = key % canvas.getWidth();
                int y = key / canvas.getWidth();
                canvas.setPixel(x, y, entry.getValue());
            }
        } finally {
            // Selection constraints will be re-enabled by CanvasPanel
        }
    }

    @Override
    public String getDescription() {
        return String.format("Scale selection from %dx%d to %dx%d",
            originalSelection.getX2() - originalSelection.getX1() + 1,
            originalSelection.getY2() - originalSelection.getY1() + 1,
            scaledSelection.getX2() - scaledSelection.getX1() + 1,
            scaledSelection.getY2() - scaledSelection.getY1() + 1);
    }

    /**
     * Check if scaling actually changed the size.
     * @return true if scaling occurred
     */
    public boolean hasChanges() {
        return !originalSelection.equals(scaledSelection);
    }
}
