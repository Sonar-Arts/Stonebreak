package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;

/**
 * Eraser tool - sets pixels to transparent.
 *
 * @author Open Mason Team
 */
public class EraserTool implements DrawingTool {

    private static final int TRANSPARENT = 0x00000000;
    private int lastX = -1;
    private int lastY = -1;

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        setPixelWithUndo(x, y, TRANSPARENT, canvas, command);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Draw line of transparent pixels
        drawLine(lastX, lastY, x, y, canvas, command);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        lastX = -1;
        lastY = -1;
    }

    private void drawLine(int x0, int y0, int x1, int y1, PixelCanvas canvas, DrawCommand command) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            setPixelWithUndo(x0, y0, TRANSPARENT, canvas, command);

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
     * Set pixel and record change for undo.
     */
    private void setPixelWithUndo(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (!canvas.isValidCoordinate(x, y)) {
            return;
        }

        int oldColor = canvas.getPixel(x, y);
        if (command != null) {
            command.recordPixelChange(x, y, oldColor, color);
        }
        canvas.setPixel(x, y, color);
    }

    @Override
    public String getName() {
        return "Eraser";
    }

    @Override
    public String getDescription() {
        return "Erase pixels to transparent";
    }
}
