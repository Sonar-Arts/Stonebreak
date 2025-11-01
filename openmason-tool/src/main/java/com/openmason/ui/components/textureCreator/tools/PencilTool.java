package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;

/**
 * Pencil tool - draws pixels with variable brush size.
 * Supports brush sizes from 1 pixel to 20 pixels diameter.
 *
 * @author Open Mason Team
 */
public class PencilTool implements DrawingTool {

    private int lastX = -1;
    private int lastY = -1;
    private int brushSize = 1; // Per-tool brush size memory

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        BrushDrawingHelper.drawBrushStroke(x, y, color, canvas, command, brushSize);
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Draw line from last position to current (for smooth drawing)
        BrushDrawingHelper.drawBrushLine(lastX, lastY, x, y, color, canvas, command, brushSize);
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
        return "Pencil";
    }

    @Override
    public String getDescription() {
        return "Draw pixels with variable brush size";
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
