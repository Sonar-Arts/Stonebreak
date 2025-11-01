package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;

/**
 * Eraser tool - sets pixels to transparent with variable brush size.
 * Supports brush sizes from 1 pixel to 20 pixels diameter.
 *
 * @author Open Mason Team
 */
public class EraserTool implements DrawingTool {

    private static final int TRANSPARENT = 0x00000000;
    private int lastX = -1;
    private int lastY = -1;
    private int brushSize = 1; // Per-tool brush size memory

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        BrushDrawingHelper.drawBrushStroke(x, y, TRANSPARENT, canvas, command, brushSize);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Draw line of transparent pixels
        BrushDrawingHelper.drawBrushLine(lastX, lastY, x, y, TRANSPARENT, canvas, command, brushSize);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        lastX = -1;
        lastY = -1;
    }

    @Override
    public String getName() {
        return "Eraser";
    }

    @Override
    public String getDescription() {
        return "Erase pixels to transparent with variable brush size";
    }

    @Override
    public boolean supportsBrushSize() {
        return true;
    }

    @Override
    public int getBrushSize() {
        return brushSize;
    }

    @Override
    public void setBrushSize(int size) {
        this.brushSize = Math.max(1, Math.min(20, size)); // Clamp to 1-20
    }
}
