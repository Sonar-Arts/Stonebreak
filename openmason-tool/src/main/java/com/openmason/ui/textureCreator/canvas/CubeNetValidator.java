package com.openmason.ui.textureCreator.canvas;

/**
 * Validator for cube net editable regions.
 */
public class CubeNetValidator {

    // Cube net dimensions
    private static final int CUBE_NET_WIDTH = 64;
    private static final int CUBE_NET_HEIGHT = 48;

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
         * Simple rectangle helper class for bounds checking.
         */
        private record Rectangle(int x, int y, int width, int height) {

        boolean contains(int px, int py) {
                return px >= x && px < x + width && py >= y && py < y + height;
            }
        }
}
