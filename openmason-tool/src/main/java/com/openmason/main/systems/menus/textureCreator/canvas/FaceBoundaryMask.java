package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * Backward-compatible wrapper around {@link PolygonShapeMask}.
 *
 * <p>Delegates all functionality to {@link PolygonShapeMask} which provides
 * the unified {@link CanvasShapeMask} interface. Existing code that
 * constructs {@code FaceBoundaryMask} will continue to work, but new code
 * should use {@link PolygonShapeMask} directly.
 *
 * @deprecated Use {@link PolygonShapeMask} directly.
 * @see PolygonShapeMask
 * @see CanvasShapeMask
 */
@Deprecated
public class FaceBoundaryMask extends PolygonShapeMask {

    /**
     * Create a face boundary mask from polygon vertices in canvas pixel coordinates.
     *
     * @param width          canvas width in pixels
     * @param height         canvas height in pixels
     * @param polygonXCoords X coordinates of polygon vertices in canvas space
     * @param polygonYCoords Y coordinates of polygon vertices in canvas space
     */
    public FaceBoundaryMask(int width, int height, float[] polygonXCoords, float[] polygonYCoords) {
        super(width, height, polygonXCoords, polygonYCoords);
    }

    /**
     * Create a mask from a UV region and 2D polygon coordinates.
     *
     * @see PolygonShapeMask#fromUVRegion
     */
    public static FaceBoundaryMask fromUVRegion(int canvasWidth, int canvasHeight,
                                                 float uvU0, float uvV0,
                                                 float uvU1, float uvV1,
                                                 float[] localXCoords, float[] localYCoords) {
        PolygonShapeMask polygon = PolygonShapeMask.fromUVRegion(
            canvasWidth, canvasHeight, uvU0, uvV0, uvU1, uvV1, localXCoords, localYCoords);
        return new FaceBoundaryMask(polygon.getWidth(), polygon.getHeight(),
            polygon.getPolygonXCoords(), polygon.getPolygonYCoords());
    }

    /**
     * Check if a pixel coordinate is inside the face boundary.
     *
     * @deprecated Use {@link #isEditable(int, int)} instead.
     */
    @Deprecated
    public boolean contains(int x, int y) {
        return isEditable(x, y);
    }
}
