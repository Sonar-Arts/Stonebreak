package com.openmason.ui.textureCreator.canvas;

/**
 * Validator for cube net editable regions.
 *
 * Determines which pixels can be edited in a 64x48 cube net texture.
 * Only the 6 face regions are editable; corner and empty regions are not.
 *
 * Cube Net Layout (64x48):
 * <pre>
 * Column: 0      1       2       3
 * Row 0:  [X]   [TOP]   [X]     [X]
 * Row 1:  [LEFT][FRONT][RIGHT] [BACK]
 * Row 2:  [X]   [BOTTOM][X]     [X]
 *
 * X = Non-editable (transparent)
 * </pre>
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only validates pixel editability
 * - Open/Closed: Extensible for other canvas types
 *
 * Design principles:
 * - KISS: Simple coordinate checking
 * - DRY: Centralized validation logic
 * - YAGNI: No complex masking, just bounds checking
 *
 * @author Open Mason Team
 */
public class CubeNetValidator {

    // Cube net dimensions
    private static final int CUBE_NET_WIDTH = 64;
    private static final int CUBE_NET_HEIGHT = 48;
    private static final int FACE_SIZE = 16;

    // Editable face regions (x, y, width, height)
    private static final Rectangle[] EDITABLE_FACES = {
        new Rectangle(16, 0, 16, 16),   // TOP
        new Rectangle(0, 16, 16, 16),   // LEFT
        new Rectangle(16, 16, 16, 16),  // FRONT
        new Rectangle(32, 16, 16, 16),  // RIGHT
        new Rectangle(48, 16, 16, 16),  // BACK
        new Rectangle(16, 32, 16, 16)   // BOTTOM
    };

    /**
     * Check if a pixel coordinate is in an editable cube net face region.
     *
     * For non-cube-net canvases (not 64x48), all pixels are editable.
     * For cube net canvases, only the 6 face regions are editable.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @param canvasWidth canvas width in pixels
     * @param canvasHeight canvas height in pixels
     * @return true if pixel is in editable region, false otherwise
     */
    public static boolean isEditablePixel(int x, int y, int canvasWidth, int canvasHeight) {
        // Only apply restriction for 64x48 cube net canvases
        if (canvasWidth != CUBE_NET_WIDTH || canvasHeight != CUBE_NET_HEIGHT) {
            return true; // Allow all pixels for non-cube-net canvases
        }

        // Check if pixel is within any editable face
        for (Rectangle face : EDITABLE_FACES) {
            if (face.contains(x, y)) {
                return true;
            }
        }

        return false; // Pixel is in non-editable region
    }

    /**
     * Check if a canvas is a cube net canvas (64x48).
     *
     * @param canvasWidth canvas width
     * @param canvasHeight canvas height
     * @return true if canvas is cube net format
     */
    public static boolean isCubeNetCanvas(int canvasWidth, int canvasHeight) {
        return canvasWidth == CUBE_NET_WIDTH && canvasHeight == CUBE_NET_HEIGHT;
    }

    /**
     * Simple rectangle helper class for bounds checking.
     */
    private static class Rectangle {
        final int x, y, width, height;

        Rectangle(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
}
