package com.openmason.ui.textureCreator.commands;

import com.openmason.ui.textureCreator.canvas.PixelCanvas;

import java.util.HashMap;
import java.util.Map;

/**
 * Command for drawing operations (pencil, eraser, fill, etc.).
 */
public class DrawCommand implements Command {

    private final PixelCanvas canvas;
    private final Map<Integer, Integer> changedPixels; // key: (y * width + x), value: old color
    private final Map<Integer, Integer> newPixels;      // key: (y * width + x), value: new color
    private final String description;

    /**
     * Create a draw command.
     *
     * @param canvas target canvas
     * @param description command description
     */
    public DrawCommand(PixelCanvas canvas, String description) {
        this.canvas = canvas;
        this.changedPixels = new HashMap<>();
        this.newPixels = new HashMap<>();
        this.description = description;
    }

    /**
     * Record a pixel change.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @param oldColor previous color
     * @param newColor new color
     */
    public void recordPixelChange(int x, int y, int oldColor, int newColor) {
        int key = y * canvas.getWidth() + x;

        // Only record if not already recorded (preserve original state)
        if (!changedPixels.containsKey(key)) {
            changedPixels.put(key, oldColor);
        }

        // Always update new color
        newPixels.put(key, newColor);
    }

    @Override
    public void execute() {
        // Apply new pixel values
        for (Map.Entry<Integer, Integer> entry : newPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            canvas.setPixel(x, y, entry.getValue());
        }
    }

    @Override
    public void undo() {
        // Restore old pixel values
        for (Map.Entry<Integer, Integer> entry : changedPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            canvas.setPixel(x, y, entry.getValue());
        }
    }

    @Override
    public String getDescription() {
        return description + " (" + changedPixels.size() + " pixels)";
    }

    /**
     * Check if any pixels were changed.
     * @return true if pixels were modified
     */
    public boolean hasChanges() {
        return !changedPixels.isEmpty();
    }
}
