package com.openmason.ui.components.textureCreator.commands;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;

import java.util.HashMap;
import java.util.Map;

/**
 * Command for rotating a rectangular selection around its center.
 * Uses bilinear interpolation for smooth rotation.
 * <p>
 * SOLID: Single responsibility - handles selection rotation only
 * Command Pattern: Supports undo/redo
 * KISS: Straightforward rotation algorithm
 * YAGNI: Center rotation only (no movable pivot)
 *
 * @author Open Mason Team
 */
public class RotateSelectionCommand implements Command {

    private final PixelCanvas canvas;
    private final RectangularSelection selection;
    private final double rotationDegrees;

    // Pixel backup for undo
    private final Map<Integer, Integer> affectedPixels;

    /**
     * Create a rotate selection command.
     *
     * @param canvas The pixel canvas to modify
     * @param selection The selection bounds
     * @param rotationDegrees Rotation angle in degrees (positive = clockwise)
     */
    public RotateSelectionCommand(PixelCanvas canvas, RectangularSelection selection,
                                  double rotationDegrees) {
        this.canvas = canvas;
        this.selection = selection;
        this.rotationDegrees = rotationDegrees;
        this.affectedPixels = new HashMap<>();

        // Backup affected pixels
        backupPixels();
    }

    /**
     * Backup pixels in the selection region.
     */
    private void backupPixels() {
        int x1 = selection.getX1();
        int y1 = selection.getY1();
        int x2 = selection.getX2();
        int y2 = selection.getY2();

        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                if (canvas.isValidCoordinate(x, y)) {
                    int key = y * canvas.getWidth() + x;
                    affectedPixels.put(key, canvas.getPixel(x, y));
                }
            }
        }
    }

    @Override
    public void execute() {
        // Temporarily disable selection constraints
        canvas.setActiveSelection(null);

        try {
            int x1 = selection.getX1();
            int y1 = selection.getY1();
            int x2 = selection.getX2();
            int y2 = selection.getY2();
            int width = x2 - x1 + 1;
            int height = y2 - y1 + 1;

            // Extract original pixels
            int[][] originalPixels = new int[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int canvasX = x1 + x;
                    int canvasY = y1 + y;
                    if (canvas.isValidCoordinate(canvasX, canvasY)) {
                        originalPixels[y][x] = canvas.getPixel(canvasX, canvasY);
                    }
                }
            }

            // Calculate center point
            double centerX = width / 2.0;
            double centerY = height / 2.0;

            // Convert rotation to radians
            double angleRad = Math.toRadians(rotationDegrees);
            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);

            // Clear selection area to transparent
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    if (canvas.isValidCoordinate(x, y)) {
                        canvas.setPixel(x, y, 0x00000000);
                    }
                }
            }

            // Rotate and draw pixels
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Translate to center
                    double dx = x - centerX;
                    double dy = y - centerY;

                    // Rotate backwards to find source pixel
                    double srcX = (dx * cos + dy * sin) + centerX;
                    double srcY = (-dx * sin + dy * cos) + centerY;

                    // Sample with bilinear interpolation
                    int color = sampleBilinear(originalPixels, srcX, srcY, width, height);

                    // Only set pixel if it has some alpha (not fully transparent)
                    if ((color >>> 24) > 0) {
                        int canvasX = x1 + x;
                        int canvasY = y1 + y;
                        if (canvas.isValidCoordinate(canvasX, canvasY)) {
                            canvas.setPixel(canvasX, canvasY, color);
                        }
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
        // Check bounds
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0x00000000; // Transparent
        }

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
        return String.format("Rotate selection by %.1f°", rotationDegrees);
    }

    /**
     * Check if rotation actually rotated pixels.
     * @return true if rotation occurred
     */
    public boolean hasChanges() {
        // Normalize rotation to -360 to 360
        double normalizedRotation = rotationDegrees % 360;
        // Check if rotation is significant (more than 0.1 degrees)
        return Math.abs(normalizedRotation) > 0.1;
    }
}
