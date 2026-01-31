package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.canvas.CubeNetValidator;
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
     * Draw line preview (saves original pixels for restoration).
     */
    private void drawLinePreview(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int currentX = x0;
        int currentY = y0;

        while (true) {
            // Check if pixel is in editable region for cube net canvases
            if (CubeNetValidator.isEditablePixel(currentX, currentY, canvas.getWidth(), canvas.getHeight())) {
                // Save original pixel before drawing preview
                saveOriginalPixel(currentX, currentY, canvas);
                canvas.setPixel(currentX, currentY, color);
            }

            if (currentX == x1 && currentY == y1) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                currentX += sx;
            }
            if (e2 < dx) {
                err += dx;
                currentY += sy;
            }
        }
    }

    /**
     * Draw final line using Bresenham's algorithm with undo support.
     */
    private void drawLine(int x0, int y0, int x1, int y1, int color, PixelCanvas canvas, DrawCommand command) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            setPixelWithUndo(x0, y0, color, canvas, command);

            if (x0 == x1 && y0 == y1) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
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
     * Set pixel and record change for undo.
     */
    private void setPixelWithUndo(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        // Check if pixel is in editable region for cube net canvases
        if (!CubeNetValidator.isEditablePixel(x, y, canvas.getWidth(), canvas.getHeight())) {
            return; // Don't draw in non-editable regions
        }

        int oldColor = canvas.getPixel(x, y);
        if (command != null) {
            command.recordPixelChange(x, y, oldColor, color);
        }
        canvas.setPixel(x, y, color);
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
