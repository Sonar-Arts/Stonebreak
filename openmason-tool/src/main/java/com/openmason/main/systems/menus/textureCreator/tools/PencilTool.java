package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;

/**
 * Pencil tool - draws pixels with variable brush size.
 */
public class PencilTool implements DrawingTool {

    private int lastX = -1;
    private int lastY = -1;
    private int brushSize = 1; // Per-tool brush size memory
    private SymmetryState symmetryState; // Injected symmetry state

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        BrushDrawingHelper.drawBrushStrokeWithSymmetry(x, y, color, canvas, command, brushSize,
            symmetryState, "PencilTool");
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Draw line from last position to current (for smooth drawing)
        BrushDrawingHelper.drawBrushLineWithSymmetry(lastX, lastY, x, y, color, canvas, command,
            brushSize, symmetryState, "PencilTool");
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

    /**
     * Set symmetry state for this tool.
     * Called by the tool coordinator to inject symmetry state.
     *
     * @param symmetryState symmetry state instance
     */
    public void setSymmetryState(SymmetryState symmetryState) {
        this.symmetryState = symmetryState;
    }
}
