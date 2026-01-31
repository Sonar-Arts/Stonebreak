package com.openmason.main.systems.viewport.viewportRendering.gizmo.geometry;

import org.joml.Vector3f;

/**
 * Generates geometry for rotation gizmo circles/arcs.
 */
public final class CircleGeometry {

    private static final int DEFAULT_SEGMENTS = 64; // Smoothness of circles
    private static final float DEFAULT_ARC_COVERAGE = 0.75f; // 3/4 of a circle
    private static final float THICK_LINE_WIDTH = 0.08f; // Thickness for thick circle rendering

    // Private constructor to prevent instantiation (utility class)
    private CircleGeometry() {
        throw new AssertionError("CircleGeometry is a utility class and should not be instantiated");
    }

    /**
     * Generates thick circle geometry as triangles for rendering with proper thickness.
     * This avoids OpenGL's glLineWidth limitations by rendering circles as triangle strips.
     */
    public static float[] createThickCircle(Vector3f center, Vector3f normal, float radius,
                                           float thickness, Vector3f color, float arcCoverage) {
        if (center == null || normal == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (radius <= 0.0f || thickness <= 0.0f) {
            throw new IllegalArgumentException("Radius and thickness must be positive");
        }
        if (arcCoverage <= 0.0f || arcCoverage > 1.0f) {
            throw new IllegalArgumentException("Arc coverage must be between 0.0 and 1.0");
        }

        // Calculate arc angles
        float startAngle = 0.0f;
        float endAngle = (float) (2.0 * Math.PI * arcCoverage);
        int segments = (int) (DEFAULT_SEGMENTS * arcCoverage);

        // Generate tangent vectors for the circle plane
        Vector3f n = new Vector3f(normal).normalize();
        Vector3f tangent1 = new Vector3f();
        Vector3f tangent2 = new Vector3f();

        // Use Frisvad's method to get perpendicular vectors
        if (n.z < -0.9999999f) {
            tangent1.set(0.0f, -1.0f, 0.0f);
            tangent2.set(-1.0f, 0.0f, 0.0f);
        } else {
            float a = 1.0f / (1.0f + n.z);
            float b = -n.x * n.y * a;
            tangent1.set(1.0f - n.x * n.x * a, b, -n.x);
            tangent2.set(b, 1.0f - n.y * n.y * a, -n.y);
        }

        // Generate vertices for triangle strip (inner and outer edges)
        java.util.ArrayList<Float> vertices = new java.util.ArrayList<>();

        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            float angle = startAngle + t * (endAngle - startAngle);

            float cosAngle = (float) Math.cos(angle);
            float sinAngle = (float) Math.sin(angle);

            // Calculate point on circle
            Vector3f circlePoint = new Vector3f()
                .set(tangent1).mul(cosAngle)
                .add(new Vector3f(tangent2).mul(sinAngle))
                .mul(radius)
                .add(center);

            // Calculate perpendicular vector for thickness
            Vector3f perpendicular = new Vector3f()
                .set(tangent1).mul(cosAngle)
                .add(new Vector3f(tangent2).mul(sinAngle))
                .normalize()
                .mul(thickness * 0.5f);

            // Inner vertex (closer to center)
            Vector3f innerPoint = new Vector3f(circlePoint).sub(perpendicular);
            vertices.add(innerPoint.x);
            vertices.add(innerPoint.y);
            vertices.add(innerPoint.z);
            vertices.add(color.x);
            vertices.add(color.y);
            vertices.add(color.z);

            // Outer vertex (farther from center)
            Vector3f outerPoint = new Vector3f(circlePoint).add(perpendicular);
            vertices.add(outerPoint.x);
            vertices.add(outerPoint.y);
            vertices.add(outerPoint.z);
            vertices.add(color.x);
            vertices.add(color.y);
            vertices.add(color.z);
        }

        return GizmoGeometry.toFloatArray(vertices);
    }

    /**
     * Generates vertex data for the three primary rotation circles as thick triangles.
     *
     * @param center Center point where all circles meet
     * @param radius Radius of each circle
     * @return Array of 3 float arrays, one for each axis [X_circle, Y_circle, Z_circle]
     */
    public static float[][] createThickAxisRotationCircles(Vector3f center, float radius) {
        if (center == null) {
            throw new IllegalArgumentException("Center cannot be null");
        }
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Radius must be positive");
        }

        // Define rotation plane normals
        Vector3f[] normals = {
            new Vector3f(1, 0, 0), // X axis rotation
            new Vector3f(0, 1, 0), // Y axis rotation
            new Vector3f(0, 0, 1)  // Z axis rotation
        };

        Vector3f[] colors = {
            new Vector3f(1, 0, 0), // Red for X
            new Vector3f(0, 1, 0), // Green for Y
            new Vector3f(0, 0, 1)  // Blue for Z
        };

        float[][] circles = new float[3][];
        for (int i = 0; i < 3; i++) {
            circles[i] = createThickCircle(
                center,
                normals[i],
                radius,
                THICK_LINE_WIDTH,
                colors[i],
                DEFAULT_ARC_COVERAGE
            );
        }

        return circles;
    }
}
