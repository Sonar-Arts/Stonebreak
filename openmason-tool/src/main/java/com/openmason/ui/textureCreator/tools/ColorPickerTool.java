package com.openmason.ui.textureCreator.tools;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.textureCreator.commands.DrawCommand;

/**
 * Color picker tool (eyedropper) - samples pixel color.
 *
 * Note: This tool needs to communicate picked color back to the application.
 * The actual color setting is handled by the controller.
 *
 * @author Open Mason Team
 */
public class ColorPickerTool implements DrawingTool {

    private int pickedColor = 0xFF000000; // Black by default

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        pickedColor = canvas.getPixel(x, y);
        // Color picker doesn't modify canvas, so no undo needed
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        pickedColor = canvas.getPixel(x, y);
        // Color picker doesn't modify canvas, so no undo needed
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // No cleanup needed
    }

    /**
     * Get the last picked color.
     * @return picked color (RGBA packed)
     */
    public int getPickedColor() {
        return pickedColor;
    }

    @Override
    public String getName() {
        return "Color Picker";
    }

    @Override
    public String getDescription() {
        return "Sample color from canvas";
    }
}
