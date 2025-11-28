package com.openmason.main.systems.viewport.gizmo.geometry;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates geometry for translation gizmo arrows.
 * Each arrow consists of a line shaft and a cone tip.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only generates arrow geometry
 * - DRY: Reuses GizmoGeometry utilities
 * - KISS: Simple arrow construction
 */
public final class ArrowGeometry {

    // Arrow dimensions (relative units, will be scaled by gizmo size)
    private static final float SHAFT_LENGTH = 0.8f;
    private static final float TIP_RADIUS = 0.05f;
    private static final int TIP_SEGMENTS = 12; // Smoothness of cone tip

    // Private constructor to prevent instantiation (utility class)
    private ArrowGeometry() {
        throw new AssertionError("ArrowGeometry is a utility class and should not be instantiated");
    }

    /**
     * Generates vertex data for a translation arrow along a specified axis.
     *
     * @param origin Starting point of the arrow
     * @param direction Direction vector (will be normalized)
     * @param length Total length of the arrow
     * @param color Color for the arrow
     * @return Vertex data for rendering (position + color interleaved)
     */
    public static float[] createArrow(Vector3f origin, Vector3f direction, float length, Vector3f color) {
        if (origin == null || direction == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (length <= 0.0f) {
            throw new IllegalArgumentException("Length must be positive");
        }

        // Normalize direction
        Vector3f dir = new Vector3f(direction).normalize();

        // Calculate key points along the arrow
        float shaftLen = length * SHAFT_LENGTH;

        Vector3f shaftEnd = new Vector3f(origin).add(
            dir.x * shaftLen,
            dir.y * shaftLen,
            dir.z * shaftLen
        );

        // Tip base is at shaft end
        Vector3f tipEnd = new Vector3f(origin).add(
            dir.x * length,
            dir.y * length,
            dir.z * length
        );

        // Generate geometry
        List<Float> vertices = new ArrayList<>();

        // 1. Generate shaft line
        float[] shaftLine = GizmoGeometry.createLine(origin, shaftEnd, color);
        for (float v : shaftLine) {
            vertices.add(v);
        }

        // 2. Generate cone tip
        List<Float> cone = GizmoGeometry.createCone(
                shaftEnd,
            tipEnd,
            length * TIP_RADIUS,
            color,
            TIP_SEGMENTS
        );
        vertices.addAll(cone);

        return GizmoGeometry.toFloatArray(vertices);
    }

    /**
     * Generates vertex data for the three primary axis arrows (X, Y, Z).
     *
     * @param origin Center point where all arrows meet
     * @param length Length of each arrow
     * @return Array of 3 float arrays, one for each axis [X_arrow, Y_arrow, Z_arrow]
     */
    public static float[][] createAxisArrows(Vector3f origin, float length) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin cannot be null");
        }
        if (length <= 0.0f) {
            throw new IllegalArgumentException("Length must be positive");
        }

        // Define axis directions and colors (RGB for XYZ)
        Vector3f[] directions = {
            new Vector3f(1, 0, 0), // X axis
            new Vector3f(0, 1, 0), // Y axis
            new Vector3f(0, 0, 1)  // Z axis
        };

        Vector3f[] colors = {
            new Vector3f(1, 0, 0), // Red for X
            new Vector3f(0, 1, 0), // Green for Y
            new Vector3f(0, 0, 1)  // Blue for Z
        };

        float[][] arrows = new float[3][];
        for (int i = 0; i < 3; i++) {
            arrows[i] = createArrow(origin, directions[i], length, colors[i]);
        }

        return arrows;
    }

    /**
     * Generates vertex data for a plane handle (small square) between two axes.
     * Used for translation in a plane (e.g., XY plane).
     *
     * @param origin Center point
     * @param axis1 First axis direction (will be normalized)
     * @param axis2 Second axis direction (will be normalized)
     * @param size Size of the plane handle square
     * @param offset Offset from origin along both axes
     * @param color Color for the plane handle
     * @return Vertex data for the plane square
     */
    public static float[] createPlaneHandle(Vector3f origin, Vector3f axis1, Vector3f axis2,
                                           float size, float offset, Vector3f color) {
        if (origin == null || axis1 == null || axis2 == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (size <= 0.0f || offset <= 0.0f) {
            throw new IllegalArgumentException("Size and offset must be positive");
        }

        // Normalize axes
        Vector3f a1 = new Vector3f(axis1).normalize();
        Vector3f a2 = new Vector3f(axis2).normalize();

        // Calculate the 4 corners of the plane square
        // Start at offset position, then create square in the plane
        Vector3f start = new Vector3f(origin)
            .add(a1.x * offset, a1.y * offset, a1.z * offset)
            .add(a2.x * offset, a2.y * offset, a2.z * offset);

        Vector3f corner2 = new Vector3f(start).add(a1.x * size, a1.y * size, a1.z * size);
        Vector3f corner3 = new Vector3f(corner2).add(a2.x * size, a2.y * size, a2.z * size);
        Vector3f corner4 = new Vector3f(start).add(a2.x * size, a2.y * size, a2.z * size);

        // Create quad (2 triangles)
        return GizmoGeometry.createQuad(start, corner2, corner3, corner4, color);
    }

    /**
     * Generates vertex data for all three plane handles (XY, XZ, YZ).
     *
     * @param origin Center point
     * @param size Size of each plane handle
     * @param offset Offset from origin
     * @return Array of 3 float arrays, one for each plane [XY, XZ, YZ]
     */
    public static float[][] createPlaneHandles(Vector3f origin, float size, float offset) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin cannot be null");
        }
        if (size <= 0.0f || offset <= 0.0f) {
            throw new IllegalArgumentException("Size and offset must be positive");
        }

        // Define plane axes and colors
        Vector3f[][] planeAxes = {
            {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0)}, // XY plane
            {new Vector3f(1, 0, 0), new Vector3f(0, 0, 1)}, // XZ plane
            {new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)}  // YZ plane
        };

        Vector3f[] planeColors = {
            new Vector3f(1, 1, 0), // Yellow for XY
            new Vector3f(1, 0, 1), // Magenta for XZ
            new Vector3f(0, 1, 1)  // Cyan for YZ
        };

        float[][] planes = new float[3][];
        for (int i = 0; i < 3; i++) {
            planes[i] = createPlaneHandle(
                origin,
                planeAxes[i][0],
                planeAxes[i][1],
                size,
                offset,
                planeColors[i]
            );
        }

        return planes;
    }

    /**
     * Gets the interaction radius for arrow hit detection.
     * This is the clickable area around the arrow.
     *
     * @param arrowLength Length of the arrow
     * @return Radius for hit detection
     */
    public static float getInteractionRadius(float arrowLength) {
        return arrowLength * 0.3f; // 30% of arrow length for easier clicking (including tip)
    }

    /**
     * Gets the center point of an arrow for interaction purposes.
     *
     * @param origin Arrow origin
     * @param direction Arrow direction (will be normalized)
     * @param length Arrow length
     * @return Center point along the arrow shaft (positioned towards the tip for intuitive grabbing)
     */
    public static Vector3f getArrowCenter(Vector3f origin, Vector3f direction, float length) {
        if (origin == null || direction == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (length <= 0.0f) {
            throw new IllegalArgumentException("Length must be positive");
        }

        Vector3f dir = new Vector3f(direction).normalize();
        float centerDist = length * 0.75f; // Positioned at 75% to include the arrow tip in interaction area

        return new Vector3f(origin).add(
            dir.x * centerDist,
            dir.y * centerDist,
            dir.z * centerDist
        );
    }

    /**
     * Gets the center point of a plane handle for interaction purposes.
     *
     * @param origin Origin point
     * @param axis1 First axis direction
     * @param axis2 Second axis direction
     * @param size Size of plane handle
     * @param offset Offset from origin
     * @return Center point of the plane handle
     */
    public static Vector3f getPlaneHandleCenter(Vector3f origin, Vector3f axis1, Vector3f axis2,
                                                float size, float offset) {
        if (origin == null || axis1 == null || axis2 == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        Vector3f a1 = new Vector3f(axis1).normalize();
        Vector3f a2 = new Vector3f(axis2).normalize();

        // Center is at offset + size/2 along both axes
        float centerOffset = offset + size * 0.5f;

        return new Vector3f(origin)
            .add(a1.x * centerOffset, a1.y * centerOffset, a1.z * centerOffset)
            .add(a2.x * centerOffset, a2.y * centerOffset, a2.z * centerOffset);
    }
}
