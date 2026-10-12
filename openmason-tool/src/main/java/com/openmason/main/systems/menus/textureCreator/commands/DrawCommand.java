package com.openmason.main.systems.menus.textureCreator.commands;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

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
    private boolean replaying; // true once undone: later writes bypass selection/mask

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
        // The first run is the edit itself and respects the selection and mask;
        // a redo replays exactly the pixels the edit changed.
        for (Map.Entry<Integer, Integer> entry : newPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            if (replaying) {
                canvas.restorePixel(x, y, entry.getValue());
            } else {
                canvas.setPixel(x, y, entry.getValue());
            }
        }
    }

    @Override
    public void undo() {
        if (!replaying) {
            dropChangesThatNeverLanded();
            replaying = true;
        }
        // Restore old pixel values whatever is selected now: undo must not be
        // clipped by a selection made after the edit.
        for (Map.Entry<Integer, Integer> entry : changedPixels.entrySet()) {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            canvas.restorePixel(x, y, entry.getValue());
        }
    }

    /**
     * Forget recorded changes the canvas rejected (outside the selection or
     * shape mask at edit time). Tools record before they write, so a rejected
     * write is still in the maps; replaying it unconstrained would paint pixels
     * the edit never touched. History is linear, so right before the first undo
     * the canvas holds exactly this command's result.
     */
    private void dropChangesThatNeverLanded() {
        newPixels.entrySet().removeIf(entry -> {
            int key = entry.getKey();
            int x = key % canvas.getWidth();
            int y = key / canvas.getWidth();
            boolean landed = canvas.getPixel(x, y) == entry.getValue();
            if (!landed) {
                changedPixels.remove(key);
            }
            return !landed;
        });
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
