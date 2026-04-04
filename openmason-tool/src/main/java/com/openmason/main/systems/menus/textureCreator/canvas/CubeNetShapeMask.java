package com.openmason.main.systems.menus.textureCreator.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shape mask for the standard 64x48 cube net layout.
 *
 * <p>Defines six 16x16 face regions arranged in a cross pattern:
 * <pre>
 *          [TOP]
 *   [LEFT] [FRONT] [RIGHT] [BACK]
 *          [BOTTOM]
 * </pre>
 *
 * <p>Pixels outside these six faces are non-editable (the four corner
 * regions of the 64x48 canvas). This replaces the former
 * {@code CubeNetValidator} static utility, consolidating editability
 * into the unified {@link CanvasShapeMask} system enforced by
 * {@link PixelCanvas}.
 *
 * @see CanvasShapeMask
 * @see PixelCanvas
 */
public class CubeNetShapeMask implements CanvasShapeMask {

    private static final Logger logger = LoggerFactory.getLogger(CubeNetShapeMask.class);

    public static final int CUBE_NET_WIDTH = 64;
    public static final int CUBE_NET_HEIGHT = 48;
    private static final int FACE_SIZE = 16;

    private final boolean[] mask;

    // Face region definitions: (x, y, width, height)
    private static final int[][] FACE_REGIONS = {
        {16,  0, FACE_SIZE, FACE_SIZE},  // TOP
        { 0, 16, FACE_SIZE, FACE_SIZE},  // LEFT
        {16, 16, FACE_SIZE, FACE_SIZE},  // FRONT
        {32, 16, FACE_SIZE, FACE_SIZE},  // RIGHT
        {48, 16, FACE_SIZE, FACE_SIZE},  // BACK
        {16, 32, FACE_SIZE, FACE_SIZE},  // BOTTOM
    };

    public CubeNetShapeMask() {
        this.mask = new boolean[CUBE_NET_WIDTH * CUBE_NET_HEIGHT];
        rasterizeEditableRegions();
        logger.debug("Cube net shape mask created: {}x{}, {} editable pixels",
            CUBE_NET_WIDTH, CUBE_NET_HEIGHT, countEditablePixels());
    }

    /**
     * Create a cube net shape mask only if the given dimensions match
     * the standard 64x48 cube net layout.
     *
     * @param width  canvas width
     * @param height canvas height
     * @return a new mask if dimensions are 64x48, null otherwise
     */
    public static CubeNetShapeMask createIfApplicable(int width, int height) {
        if (width == CUBE_NET_WIDTH && height == CUBE_NET_HEIGHT) {
            return new CubeNetShapeMask();
        }
        return null;
    }

    @Override
    public boolean isEditable(int x, int y) {
        if (x < 0 || x >= CUBE_NET_WIDTH || y < 0 || y >= CUBE_NET_HEIGHT) {
            return false;
        }
        return mask[y * CUBE_NET_WIDTH + x];
    }

    @Override
    public int getWidth() {
        return CUBE_NET_WIDTH;
    }

    @Override
    public int getHeight() {
        return CUBE_NET_HEIGHT;
    }

    private void rasterizeEditableRegions() {
        for (int[] region : FACE_REGIONS) {
            int rx = region[0];
            int ry = region[1];
            int rw = region[2];
            int rh = region[3];

            for (int y = ry; y < ry + rh; y++) {
                for (int x = rx; x < rx + rw; x++) {
                    mask[y * CUBE_NET_WIDTH + x] = true;
                }
            }
        }
    }

    private int countEditablePixels() {
        int count = 0;
        for (boolean b : mask) {
            if (b) count++;
        }
        return count;
    }
}
