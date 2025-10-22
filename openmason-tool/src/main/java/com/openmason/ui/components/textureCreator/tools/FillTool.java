package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Fill tool - flood fills connected pixels.
 *
 * @author Open Mason Team
 */
public class FillTool implements DrawingTool {

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        int targetColor = canvas.getPixel(x, y);

        // Don't fill if already the target color
        if (targetColor == color) {
            return;
        }

        floodFill(canvas, x, y, targetColor, color, command);
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Fill tool doesn't respond to drag
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        // No cleanup needed
    }

    /**
     * Flood fill algorithm (4-connected).
     */
    private void floodFill(PixelCanvas canvas, int startX, int startY, int targetColor, int fillColor, DrawCommand command) {
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{startX, startY});

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];

            // Skip if out of bounds
            if (!canvas.isValidCoordinate(x, y)) {
                continue;
            }

            // Skip if not target color
            if (canvas.getPixel(x, y) != targetColor) {
                continue;
            }

            // Fill pixel with undo tracking
            int oldColor = canvas.getPixel(x, y);
            if (command != null) {
                command.recordPixelChange(x, y, oldColor, fillColor);
            }
            canvas.setPixel(x, y, fillColor);

            // Add neighbors to queue (4-connected)
            queue.offer(new int[]{x + 1, y});
            queue.offer(new int[]{x - 1, y});
            queue.offer(new int[]{x, y + 1});
            queue.offer(new int[]{x, y - 1});
        }
    }

    @Override
    public String getName() {
        return "Fill";
    }

    @Override
    public String getDescription() {
        return "Flood fill connected pixels";
    }
}
