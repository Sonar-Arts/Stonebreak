package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.canvas.CoverageBlender;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;

import java.util.HashMap;
import java.util.Map;

/**
 * Line tool - draws straight lines with real-time preview.
 */
public class LineTool implements DrawingTool {

    private int startX = -1;
    private int startY = -1;
    private int endX = -1;
    private int endY = -1;
    private int lastPreviewEndX = -1;
    private int lastPreviewEndY = -1;

    // Store original canvas state for preview restoration
    private final Map<Integer, Integer> originalPixels = new HashMap<>();
    private PixelCanvas previewCanvas = null;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        startX = x;
        startY = y;
        endX = x;
        endY = y;
        lastPreviewEndX = x;
        lastPreviewEndY = y;
        previewCanvas = canvas;
        originalPixels.clear();

        // Save original pixels along the initial line (just the starting point)
        saveOriginalPixel(x, y, canvas);

        // Draw initial point as preview (without command)
        canvas.setPixel(x, y, color);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Update end position
        endX = x;
        endY = y;

        // Only update preview if position changed
        if (endX != lastPreviewEndX || endY != lastPreviewEndY) {
            // Restore original pixels from previous preview
            restoreOriginalPixels(canvas);

            // Draw new preview line (without command for undo)
            drawLinePreview(startX, startY, endX, endY, color, canvas);

            lastPreviewEndX = endX;
            lastPreviewEndY = endY;
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // Restore original canvas state before drawing final line
        restoreOriginalPixels(canvas);

        // Draw the final line with command for undo support
        if (startX != -1 && startY != -1 && endX != -1 && endY != -1) {
            drawLine(startX, startY, endX, endY, color, canvas, command);
        }
        reset();
    }


    /**
     * Draw line preview using Wu's algorithm (saves original pixels for restoration).
     */
    private void drawLinePreview(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas) {
        drawWuLine(x0, y0, x1, y1, color, canvas, null, true);
    }

    /**
     * Draw final line using Wu's anti-aliased line algorithm with undo support.
     *
     * <p>Wu's algorithm produces smooth lines by computing fractional coverage
     * for the two pixels straddling the ideal line at each step along the
     * major axis. This gives sub-pixel precision without traditional anti-aliasing.
     */
    private void drawLine(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas, DrawCommand command) {
        drawWuLine(x0, y0, x1, y1, color, canvas, command, false);
    }

    /**
     * Wu's anti-aliased line algorithm.
     * Draws a smooth line between two points using coverage-based pixel blending.
     */
    private void drawWuLine(int x0, int y0, int x1, int y1, int color,
                            PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        // Handle single point
        if (x0 == x1 && y0 == y1) {
            if (isPreview) {
                setPixelPreview(x0, y0, color, 1.0f, canvas);
            } else {
                setPixelWithCoverage(x0, y0, color, 1.0f, canvas, command);
            }
            return;
        }

        boolean steep = Math.abs(y1 - y0) > Math.abs(x1 - x0);

        // If steep, transpose coordinates (work along Y axis instead)
        int ax = steep ? y0 : x0;
        int ay = steep ? x0 : y0;
        int bx = steep ? y1 : x1;
        int by = steep ? x1 : y1;

        // Ensure we draw left to right
        if (ax > bx) {
            int tmp;
            tmp = ax; ax = bx; bx = tmp;
            tmp = ay; ay = by; by = tmp;
        }

        float dx = bx - ax;
        float dy = by - ay;
        float gradient = (dx == 0.0f) ? 1.0f : dy / dx;

        // Draw start endpoint
        float xEnd = ax;
        float yEnd = ay;
        if (isPreview) {
            plotWuPixel(steep, (int) xEnd, (int) yEnd, 1.0f, color, canvas, null, true);
        } else {
            plotWuPixel(steep, (int) xEnd, (int) yEnd, 1.0f, color, canvas, command, false);
        }

        float yIntersect = yEnd + gradient;

        // Draw end endpoint
        xEnd = bx;
        yEnd = by;
        if (isPreview) {
            plotWuPixel(steep, (int) xEnd, (int) yEnd, 1.0f, color, canvas, null, true);
        } else {
            plotWuPixel(steep, (int) xEnd, (int) yEnd, 1.0f, color, canvas, command, false);
        }

        // Draw intermediate points
        int prevYBase = ay;
        for (int x = ax + 1; x < bx; x++) {
            float frac = yIntersect - (float) Math.floor(yIntersect);
            int yBase = (int) Math.floor(yIntersect);

            // Fill diagonal gap: when yBase shifts, the previous and current
            // primary pixels share only a corner, not an edge. Plot the pixel
            // at (x-1, yBase) to bridge the gap with full coverage.
            if (yBase != prevYBase) {
                if (isPreview) {
                    plotWuPixel(steep, x - 1, yBase, 1.0f, color, canvas, null, true);
                } else {
                    plotWuPixel(steep, x - 1, yBase, 1.0f, color, canvas, command, false);
                }
            }

            // Two pixels straddle the ideal line, with complementary coverage
            if (isPreview) {
                plotWuPixel(steep, x, yBase, 1.0f - frac, color, canvas, null, true);
                plotWuPixel(steep, x, yBase + 1, frac, color, canvas, null, true);
            } else {
                plotWuPixel(steep, x, yBase, 1.0f - frac, color, canvas, command, false);
                plotWuPixel(steep, x, yBase + 1, frac, color, canvas, command, false);
            }

            prevYBase = yBase;
            yIntersect += gradient;
        }
    }

    /**
     * Plot a single pixel for Wu's algorithm, handling coordinate transposition.
     */
    private void plotWuPixel(boolean steep, int x, int y, float coverage,
                             int color, PixelCanvas canvas, DrawCommand command, boolean isPreview) {
        int px = steep ? y : x;
        int py = steep ? x : y;

        if (coverage <= 0.01f) {
            return; // Skip nearly invisible pixels
        }

        if (isPreview) {
            setPixelPreview(px, py, color, coverage, canvas);
        } else {
            setPixelWithCoverage(px, py, color, coverage, canvas, command);
        }
    }

    /**
     * Save original pixel value before drawing preview.
     */
    private void saveOriginalPixel(int x, int y, PixelCanvas canvas) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        int key = y * canvas.getWidth() + x;
        if (!originalPixels.containsKey(key)) {
            originalPixels.put(key, canvas.getPixel(x, y));
        }
    }

    /**
     * Restore all original pixels (clear preview).
     */
    private void restoreOriginalPixels(PixelCanvas canvas) {
        for (Map.Entry<Integer, Integer> entry : originalPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            canvas.setPixel(x, y, entry.getValue());
        }
        originalPixels.clear();
    }

    /**
     * Set pixel with coverage-based blending, combining mask and tool coverage.
     */
    private void setPixelWithCoverage(int x, int y, int color, float coverage,
                                       PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        float maskCoverage = canvas.getMaskCoverage(x, y);
        float finalCoverage = coverage * maskCoverage;
        if (finalCoverage <= 0.0f) {
            return;
        }

        int oldColor = canvas.getPixel(x, y);
        int blended;
        if (finalCoverage >= 1.0f) {
            blended = color;
        } else {
            blended = CoverageBlender.blendWithCoverage(color, oldColor, finalCoverage);
        }

        if (blended == oldColor) {
            return;
        }

        if (command != null) {
            command.recordPixelChange(x, y, oldColor, blended);
        }
        canvas.setPixel(x, y, blended);
    }

    /**
     * Set pixel preview with coverage-based blending.
     */
    private void setPixelPreview(int x, int y, int color, float coverage, PixelCanvas canvas) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        float maskCoverage = canvas.getMaskCoverage(x, y);
        float finalCoverage = coverage * maskCoverage;
        if (finalCoverage <= 0.0f) {
            return;
        }

        saveOriginalPixel(x, y, canvas);

        int existingColor = canvas.getPixel(x, y);
        int blended;
        if (finalCoverage >= 1.0f) {
            blended = color;
        } else {
            blended = CoverageBlender.blendWithCoverage(color, existingColor, finalCoverage);
        }
        canvas.setPixel(x, y, blended);
    }

    @Override
    public void reset() {
        startX = -1;
        startY = -1;
        endX = -1;
        endY = -1;
        lastPreviewEndX = -1;
        lastPreviewEndY = -1;
        originalPixels.clear();
        previewCanvas = null;
    }

    @Override
    public String getName() {
        return "Line";
    }

    @Override
    public String getDescription() {
        return "Draw straight lines";
    }
}
