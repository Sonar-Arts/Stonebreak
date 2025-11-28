package com.openmason.main.systems.menus.textureCreator.tools;

import com.openmason.main.systems.menus.textureCreator.SymmetryState;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.DrawCommand;

/**
 * Eraser tool - sets pixels to transparent with variable brush size.
 */
public class EraserTool implements DrawingTool {

    private static final int TRANSPARENT = 0x00000000;
    private int lastX = -1;
    private int lastY = -1;
    private int brushSize = 1; // Per-tool brush size memory
    private SymmetryState symmetryState; // Injected symmetry state

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        BrushDrawingHelper.drawBrushStrokeWithSymmetry(x, y, TRANSPARENT, canvas, command, brushSize,
            symmetryState, "EraserTool");
        lastX = x;
        lastY = y;
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Draw line of transparent pixels
        BrushDrawingHelper.drawBrushLineWithSymmetry(lastX, lastY, x, y, TRANSPARENT, canvas, command,
            brushSize, symmetryState, "EraserTool");
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
