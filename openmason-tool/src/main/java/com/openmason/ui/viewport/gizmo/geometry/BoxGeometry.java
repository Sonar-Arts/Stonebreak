package com.openmason.ui.viewport.gizmo.geometry;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates geometry for scale gizmo box handles.
 * Each scale gizmo consists of small boxes at the end of each axis and a center cube.
 */
public final class BoxGeometry {

    private static final float LINE_LENGTH = 1.0f; // Length of axis lines to box center (ensures attachment)

    // Private constructor to prevent instantiation (utility class)
    private BoxGeometry() {
        throw new AssertionError("BoxGeometry is a utility class and should not be instantiated");
    }

    /**
     * Generates vertex data for a scale handle (line + box) along a specified axis.
     */
    public static float[] createScaleHandle(Vector3f origin, Vector3f direction, float length,
                                           float boxSize, Vector3f color) {
        if (origin == null || direction == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (length <= 0.0f || boxSize <= 0.0f) {
            throw new IllegalArgumentException("Length and box size must be positive");
        }

        // Normalize direction
        Vector3f dir = new Vector3f(direction).normalize();

        // Calculate line end position (where box starts)
        float lineLen = length * LINE_LENGTH;
        Vector3f lineEnd = new Vector3f(origin).add(
            dir.x * lineLen,
            dir.y * lineLen,
            dir.z * lineLen
        );

        // Calculate box center position
        Vector3f boxCenter = new Vector3f(origin).add(
            dir.x * length,
            dir.y * length,
            dir.z * length
        );

        List<Float> vertices = new ArrayList<>();

        // 1. Generate line from origin to box
        float[] line = GizmoGeometry.createLine(origin, lineEnd, color);
        for (float v : line) {
            vertices.add(v);
        }

        // 2. Generate box at end
        Vector3f halfSize = new Vector3f(boxSize * 0.5f);
        List<Float> box = GizmoGeometry.createBox(boxCenter, halfSize, color);
        vertices.addAll(box);

        return GizmoGeometry.toFloatArray(vertices);
    }

    /**
     * Generates vertex data for the three primary axis scale handles (X, Y, Z).
     *
     * @param origin Center point where all handles meet
     * @param length Length to each box handle
     * @param boxSize Size of each box handle
     * @return Array of 3 float arrays, one for each axis [X_handle, Y_handle, Z_handle]
     */
    public static float[][] createAxisScaleHandles(Vector3f origin, float length, float boxSize) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin cannot be null");
        }
        if (length <= 0.0f || boxSize <= 0.0f) {
            throw new IllegalArgumentException("Length and box size must be positive");
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

        float[][] handles = new float[3][];
        for (int i = 0; i < 3; i++) {
            handles[i] = createScaleHandle(origin, directions[i], length, boxSize, colors[i]);
        }

        return handles;
    }

    /**
     * Generates vertex data for the center uniform scale box.
     * This box allows scaling uniformly in all directions.
     *
     * @param origin Center point
     * @param boxSize Size of the center box
     * @return Vertex data for the center box
     */
    public static float[] createCenterScaleBox(Vector3f origin, float boxSize) {
        if (origin == null) {
            throw new IllegalArgumentException("Origin cannot be null");
        }
        if (boxSize <= 0.0f) {
            throw new IllegalArgumentException("Box size must be positive");
        }

        // Center box is white/gray to distinguish from axis boxes
        Vector3f color = new Vector3f(0.9f, 0.9f, 0.9f);

        Vector3f halfSize = new Vector3f(boxSize * 0.5f);
        List<Float> box = GizmoGeometry.createBox(origin, halfSize, color);

        return GizmoGeometry.toFloatArray(box);
    }

    /**
     * Gets the center position of a scale handle box.
     */
    public static Vector3f getHandleCenter(Vector3f origin, Vector3f direction, float length) {
        if (origin == null || direction == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (length <= 0.0f) {
            throw new IllegalArgumentException("Length must be positive");
        }

        Vector3f dir = new Vector3f(direction).normalize();

        return new Vector3f(origin).add(
            dir.x * length,
            dir.y * length,
            dir.z * length
        );
    }

    /**
     * Gets the interaction radius for box handle hit detection.
     *
     * @param boxSize Size of the box
     * @return Radius for hit detection (diagonal of box / 2)
     */
    public static float getInteractionRadius(float boxSize) {
        // Use box diagonal for sphere-based hit detection
        return boxSize * 0.866f; // sqrt(3)/2 ≈ 0.866
    }
}
