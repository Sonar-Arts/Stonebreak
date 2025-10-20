package com.openmason.ui.viewport.gizmo.geometry;

import org.joml.Vector3f;

import java.util.List;

/**
 * Generates geometry for rotation gizmo circles/arcs.
 * Each rotation gizmo consists of circular arcs around each axis.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only generates circle geometry
 * - DRY: Reuses GizmoGeometry utilities
 * - KISS: Simple circle construction
 */
public final class CircleGeometry {

    private static final int DEFAULT_SEGMENTS = 64; // Smoothness of circles
    private static final float DEFAULT_ARC_COVERAGE = 0.75f; // 3/4 of a circle

    // Private constructor to prevent instantiation (utility class)
    private CircleGeometry() {
        throw new AssertionError("CircleGeometry is a utility class and should not be instantiated");
    }

    /**
     * Generates vertex data for a rotation circle around a specified axis.
     *
     * @param center Center of the circle
     * @param normal Normal vector defining the rotation plane
     * @param radius Radius of the circle
     * @param color Color for the circle
     * @param arcCoverage Fraction of the circle to draw (0.0 to 1.0, 1.0 = full circle)
     * @return Vertex data for rendering as line strip
     */
    public static float[] createRotationCircle(Vector3f center, Vector3f normal, float radius,
                                               Vector3f color, float arcCoverage) {
        if (center == null || normal == null || color == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Radius must be positive");
        }
        if (arcCoverage <= 0.0f || arcCoverage > 1.0f) {
            throw new IllegalArgumentException("Arc coverage must be between 0.0 and 1.0");
        }

        // Calculate arc angles
        float startAngle = 0.0f;
        float endAngle = (float) (2.0 * Math.PI * arcCoverage);

        // Generate circle vertices
        List<Float> vertices = GizmoGeometry.createCircle(
            center,
            radius,
            normal,
            color,
            DEFAULT_SEGMENTS,
            startAngle,
            endAngle
        );

        return GizmoGeometry.toFloatArray(vertices);
    }

    /**
     * Generates vertex data for a full rotation circle (360 degrees).
     *
     * @param center Center of the circle
     * @param normal Normal vector defining the rotation plane
     * @param radius Radius of the circle
     * @param color Color for the circle
     * @return Vertex data for rendering as line strip
     */
    public static float[] createFullCircle(Vector3f center, Vector3f normal, float radius, Vector3f color) {
        return createRotationCircle(center, normal, radius, color, 1.0f);
    }

    /**
     * Generates vertex data for the three primary rotation circles (X, Y, Z axes).
     *
     * @param center Center point where all circles meet
     * @param radius Radius of each circle
     * @return Array of 3 float arrays, one for each axis [X_circle, Y_circle, Z_circle]
     */
    public static float[][] createAxisRotationCircles(Vector3f center, float radius) {
        if (center == null) {
            throw new IllegalArgumentException("Center cannot be null");
        }
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Radius must be positive");
        }

        // Define rotation plane normals (perpendicular to rotation axis)
        // X rotation: rotate around X axis → circle in YZ plane → normal = X
        // Y rotation: rotate around Y axis → circle in XZ plane → normal = Y
        // Z rotation: rotate around Z axis → circle in XY plane → normal = Z
        Vector3f[] normals = {
            new Vector3f(1, 0, 0), // X axis rotation (YZ plane circle)
            new Vector3f(0, 1, 0), // Y axis rotation (XZ plane circle)
            new Vector3f(0, 0, 1)  // Z axis rotation (XY plane circle)
        };

        Vector3f[] colors = {
            new Vector3f(1, 0, 0), // Red for X
            new Vector3f(0, 1, 0), // Green for Y
            new Vector3f(0, 0, 1)  // Blue for Z
        };

        float[][] circles = new float[3][];
        for (int i = 0; i < 3; i++) {
            circles[i] = createRotationCircle(
                center,
                normals[i],
                radius,
                colors[i],
                DEFAULT_ARC_COVERAGE
            );
        }

        return circles;
    }

    /**
     * Generates vertex data for a screen-space rotation circle.
     * This circle is always facing the camera and allows rotation in the view plane.
     *
     * @param center Center of the circle
     * @param cameraForward Camera forward vector (will be normalized)
     * @param radius Radius of the circle
     * @return Vertex data for the screen-space rotation circle
     */
    public static float[] createScreenSpaceCircle(Vector3f center, Vector3f cameraForward, float radius) {
        if (center == null || cameraForward == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (radius <= 0.0f) {
            throw new IllegalArgumentException("Radius must be positive");
        }

        // Screen-space circle is white/gray to distinguish from axis circles
        Vector3f color = new Vector3f(0.8f, 0.8f, 0.8f);

        return createFullCircle(center, cameraForward, radius, color);
    }

    /**
     * Gets the interaction radius for circle hit detection.
     * This is the thickness of the clickable band around the circle.
     *
     * @param circleRadius Radius of the circle
     * @return Thickness for hit detection
     */
    public static float getInteractionThickness(float circleRadius) {
        return circleRadius * 0.05f; // 5% of radius
    }

    /**
     * Checks if a point is near a circle (within interaction thickness).
     *
     * @param point Point to test
     * @param circleCenter Center of the circle
     * @param circleRadius Radius of the circle
     * @param thickness Interaction thickness
     * @return true if point is within the interaction band
     */
    public static boolean isPointNearCircle(Vector3f point, Vector3f circleCenter,
                                           float circleRadius, float thickness) {
        if (point == null || circleCenter == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Calculate distance from point to circle center
        float distToCenter = point.distance(circleCenter);

        // Check if distance is within the circle's interaction band
        float minDist = circleRadius - thickness;
        float maxDist = circleRadius + thickness;

        return distToCenter >= minDist && distToCenter <= maxDist;
    }

    /**
     * Projects a 3D point onto a circle plane and returns the angle.
     * Used for calculating rotation angle during drag operations.
     *
     * @param point The point to project
     * @param circleCenter Center of the circle
     * @param normal Normal vector of the circle plane
     * @return Angle in radians (0 to 2π)
     */
    public static float getAngleOnCircle(Vector3f point, Vector3f circleCenter, Vector3f normal) {
        if (point == null || circleCenter == null || normal == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Vector from center to point
        Vector3f toPoint = new Vector3f(point).sub(circleCenter);

        // Project onto circle plane (remove component along normal)
        Vector3f n = new Vector3f(normal).normalize();
        float dotProduct = toPoint.dot(n);
        Vector3f projected = new Vector3f(toPoint).sub(
            n.x * dotProduct,
            n.y * dotProduct,
            n.z * dotProduct
        );

        // Handle degenerate case (point on circle center)
        if (projected.lengthSquared() < 0.0001f) {
            return 0.0f;
        }

        projected.normalize();

        // Get tangent vectors for angle calculation
        Vector3f tangent1 = new Vector3f();
        Vector3f tangent2 = new Vector3f();

        // Use Frisvad's method (same as in GizmoGeometry)
        if (n.z < -0.9999999f) {
            tangent1.set(0.0f, -1.0f, 0.0f);
            tangent2.set(-1.0f, 0.0f, 0.0f);
        } else {
            float a = 1.0f / (1.0f + n.z);
            float b = -n.x * n.y * a;
            tangent1.set(1.0f - n.x * n.x * a, b, -n.x);
            tangent2.set(b, 1.0f - n.y * n.y * a, -n.y);
        }

        // Calculate angle using atan2
        float x = projected.dot(tangent1);
        float y = projected.dot(tangent2);
        float angle = (float) Math.atan2(y, x);

        // Normalize to [0, 2π]
        if (angle < 0) {
            angle += 2.0f * Math.PI;
        }

        return angle;
    }

    /**
     * Calculates the angular difference between two angles.
     * Returns the shortest angular distance.
     *
     * @param angle1 First angle in radians
     * @param angle2 Second angle in radians
     * @return Angular difference in radians (-π to π)
     */
    public static float getAngularDifference(float angle1, float angle2) {
        float diff = angle2 - angle1;

        // Normalize to [-π, π]
        while (diff > Math.PI) {
            diff -= 2.0f * Math.PI;
        }
        while (diff < -Math.PI) {
            diff += 2.0f * Math.PI;
        }

        return diff;
    }
}
