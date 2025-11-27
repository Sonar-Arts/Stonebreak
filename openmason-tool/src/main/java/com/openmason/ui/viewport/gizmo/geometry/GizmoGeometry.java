package com.openmason.ui.viewport.gizmo.geometry;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Base utilities for generating gizmo geometry data.
 * Provides common functions for creating vertex data for various shapes.
 */
public final class GizmoGeometry {

    // Private constructor to prevent instantiation (utility class)
    private GizmoGeometry() {
        throw new AssertionError("GizmoGeometry is a utility class and should not be instantiated");
    }

    /**
     * Generates vertices for a line segment.
     */
    public static float[] createLine(Vector3f start, Vector3f end, Vector3f color) {
        if (start == null || end == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        return new float[] {
            // Vertex 1
            start.x, start.y, start.z,  // Position
            color.x, color.y, color.z,  // Color
            // Vertex 2
            end.x, end.y, end.z,        // Position
            color.x, color.y, color.z   // Color
        };
    }

    /**
     * Creates an orthonormal basis for a circle given its normal vector.
     * Uses the "Frisvad" method for numerical stability.
     */
    private static void createCircleBasis(Vector3f normal, Vector3f tangent1, Vector3f tangent2) {
        // Normalize the normal vector
        Vector3f n = new Vector3f(normal).normalize();

        // Use Frisvad's method for robust tangent generation
        if (n.z < -0.9999999f) {
            // Handle degenerate case when normal points down
            tangent1.set(0.0f, -1.0f, 0.0f);
            tangent2.set(-1.0f, 0.0f, 0.0f);
        } else {
            float a = 1.0f / (1.0f + n.z);
            float b = -n.x * n.y * a;
            tangent1.set(1.0f - n.x * n.x * a, b, -n.x);
            tangent2.set(b, 1.0f - n.y * n.y * a, -n.y);
        }
    }

    /**
     * Generates vertices for a quadrilateral (two triangles).
     */
    public static float[] createQuad(Vector3f v1, Vector3f v2, Vector3f v3, Vector3f v4, Vector3f color) {
        if (v1 == null || v2 == null || v3 == null || v4 == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Create two triangles: v1-v2-v3 and v1-v3-v4
        return new float[] {
            // Triangle 1
            v1.x, v1.y, v1.z, color.x, color.y, color.z,
            v2.x, v2.y, v2.z, color.x, color.y, color.z,
            v3.x, v3.y, v3.z, color.x, color.y, color.z,
            // Triangle 2
            v1.x, v1.y, v1.z, color.x, color.y, color.z,
            v3.x, v3.y, v3.z, color.x, color.y, color.z,
            v4.x, v4.y, v4.z, color.x, color.y, color.z
        };
    }

    /**
     * Generates vertices for a cone (used for arrow tips).
     */
    public static List<Float> createCone(Vector3f base, Vector3f tip, float baseRadius,
                                         Vector3f color, int segments) {
        if (base == null || tip == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (baseRadius <= 0.0f) {
            throw new IllegalArgumentException("Base radius must be positive");
        }
        if (segments < 3) {
            throw new IllegalArgumentException("Segments must be at least 3");
        }

        List<Float> vertices = new ArrayList<>();

        // Compute cone axis and perpendicular vectors
        Vector3f axis = new Vector3f(tip).sub(base).normalize();
        Vector3f tangent1 = new Vector3f();
        Vector3f tangent2 = new Vector3f();
        createCircleBasis(axis, tangent1, tangent2);

        // Generate base circle vertices
        List<Vector3f> basePoints = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            float angle = (float) (2.0 * Math.PI * i / segments);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            Vector3f point = new Vector3f(base)
                .add(tangent1.x * baseRadius * cos, tangent1.y * baseRadius * cos, tangent1.z * baseRadius * cos)
                .add(tangent2.x * baseRadius * sin, tangent2.y * baseRadius * sin, tangent2.z * baseRadius * sin);
            basePoints.add(point);
        }

        // Generate triangles from tip to base perimeter
        for (int i = 0; i < segments; i++) {
            Vector3f p1 = basePoints.get(i);
            Vector3f p2 = basePoints.get((i + 1) % segments);

            // Triangle: tip - p1 - p2
            addVertex(vertices, tip, color);
            addVertex(vertices, p1, color);
            addVertex(vertices, p2, color);
        }

        // Generate base cap (fan triangulation from center)
        for (int i = 0; i < segments; i++) {
            Vector3f p1 = basePoints.get(i);
            Vector3f p2 = basePoints.get((i + 1) % segments);

            // Triangle: base - p2 - p1 (reversed winding for correct normal)
            addVertex(vertices, base, color);
            addVertex(vertices, p2, color);
            addVertex(vertices, p1, color);
        }

        return vertices;
    }

    /**
     * Generates vertices for a box.
     *
     * @param center Center of the box
     * @param size Half-extents of the box (width/2, height/2, depth/2)
     * @param color Color for all vertices
     * @return List of vertex data for triangles (12 triangles = 6 faces * 2 triangles)
     */
    public static List<Float> createBox(Vector3f center, Vector3f size, Vector3f color) {
        if (center == null || size == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        List<Float> vertices = new ArrayList<>();

        // Define 8 corners of the box
        Vector3f[] corners = createBoxCorners(center, size);

        // Define 6 faces (each face = 2 triangles)
        int[][] faces = {
            {0, 1, 2, 3}, // Back face
            {4, 7, 6, 5}, // Front face
            {0, 4, 5, 1}, // Bottom face
            {3, 2, 6, 7}, // Top face
            {0, 3, 7, 4}, // Left face
            {1, 5, 6, 2}  // Right face
        };

        for (int[] face : faces) {
            // Triangle 1: 0-1-2
            addVertex(vertices, corners[face[0]], color);
            addVertex(vertices, corners[face[1]], color);
            addVertex(vertices, corners[face[2]], color);
            // Triangle 2: 0-2-3
            addVertex(vertices, corners[face[0]], color);
            addVertex(vertices, corners[face[2]], color);
            addVertex(vertices, corners[face[3]], color);
        }

        return vertices;
    }

    /**
     * Creates the 8 corner vertices of a box.
     */
    private static Vector3f[] createBoxCorners(Vector3f center, Vector3f size) {
        float x = center.x, y = center.y, z = center.z;
        float sx = size.x, sy = size.y, sz = size.z;

        return new Vector3f[] {
            new Vector3f(x - sx, y - sy, z - sz), // 0: left-bottom-back
            new Vector3f(x + sx, y - sy, z - sz), // 1: right-bottom-back
            new Vector3f(x + sx, y + sy, z - sz), // 2: right-top-back
            new Vector3f(x - sx, y + sy, z - sz), // 3: left-top-back
            new Vector3f(x - sx, y - sy, z + sz), // 4: left-bottom-front
            new Vector3f(x + sx, y - sy, z + sz), // 5: right-bottom-front
            new Vector3f(x + sx, y + sy, z + sz), // 6: right-top-front
            new Vector3f(x - sx, y + sy, z + sz)  // 7: left-top-front
        };
    }

    /**
     * Helper method to add a vertex to the vertex list.
     */
    private static void addVertex(List<Float> vertices, Vector3f position, Vector3f color) {
        vertices.add(position.x);
        vertices.add(position.y);
        vertices.add(position.z);
        vertices.add(color.x);
        vertices.add(color.y);
        vertices.add(color.z);
    }

    /**
     * Converts a list of floats to a float array.
     */
    public static float[] toFloatArray(List<Float> list) {
        if (list == null) {
            throw new IllegalArgumentException("List cannot be null");
        }

        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
