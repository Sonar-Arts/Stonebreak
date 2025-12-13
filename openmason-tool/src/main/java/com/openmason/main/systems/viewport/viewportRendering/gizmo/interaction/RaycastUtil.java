package com.openmason.main.systems.viewport.viewportRendering.gizmo.interaction;

import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Utility class for 3D raycasting and intersection tests.
 * Provides functions for ray-sphere, ray-line, ray-plane, and ray-circle intersection.
 */
public final class RaycastUtil {

    // Private constructor to prevent instantiation (utility class)
    private RaycastUtil() {
        throw new AssertionError("RaycastUtil is a utility class and should not be instantiated");
    }

    /**
     * Creates a ray from screen coordinates using camera matrices.
     */
    public static CoordinateSystem.Ray createRayFromScreen(float screenX, float screenY,
                                         int viewportWidth, int viewportHeight,
                                         Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        // Delegate to unified coordinate system
        return CoordinateSystem.createWorldRayFromScreen(
            screenX, screenY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix
        );
    }

    /**
     * Tests intersection between a ray and a sphere.
     *
     * @param ray The ray to test
     * @param sphereCenter Center of the sphere
     * @param sphereRadius Radius of the sphere
     * @return Distance along ray to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    public static float intersectRaySphere(CoordinateSystem.Ray ray, Vector3f sphereCenter, float sphereRadius) {
        if (ray == null || sphereCenter == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (sphereRadius <= 0.0f) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }

        // Vector from ray origin to sphere center
        Vector3f oc = new Vector3f(ray.origin()).sub(sphereCenter);

        // Quadratic equation coefficients: at^2 + bt + c = 0
        float a = ray.direction().dot(ray.direction()); // Should be 1.0 since normalized
        float b = 2.0f * oc.dot(ray.direction());
        float c = oc.dot(oc) - sphereRadius * sphereRadius;

        // Discriminant
        float discriminant = b * b - 4 * a * c;

        if (discriminant < 0.0f) {
            return Float.POSITIVE_INFINITY; // No intersection
        }

        // Two solutions (entry and exit points)
        float sqrtD = (float) Math.sqrt(discriminant);
        float t1 = (-b - sqrtD) / (2.0f * a);
        float t2 = (-b + sqrtD) / (2.0f * a);

        // Return closest positive intersection
        if (t1 > 0.0f) {
            return t1;
        } else if (t2 > 0.0f) {
            return t2;
        } else {
            return Float.POSITIVE_INFINITY; // Behind ray origin
        }
    }

    /**
     * Tests intersection between a ray and a plane.
     */
    public static float intersectRayPlane(CoordinateSystem.Ray ray, Vector3f planePoint, Vector3f planeNormal) {
        if (ray == null || planePoint == null || planeNormal == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Check if ray is parallel to plane
        float denom = planeNormal.dot(ray.direction());
        if (Math.abs(denom) < 0.0001f) {
            return Float.POSITIVE_INFINITY; // Parallel, no intersection
        }

        // Calculate intersection distance
        Vector3f p0l0 = new Vector3f(planePoint).sub(ray.origin());
        float t = p0l0.dot(planeNormal) / denom;

        if (t >= 0.0f) {
            return t;
        } else {
            return Float.POSITIVE_INFINITY; // Behind ray origin
        }
    }

    /**
     * Tests intersection between a ray and a circle (torus approximation).
     * Used for rotation gizmo interaction.
     */
    public static float intersectRayCircle(CoordinateSystem.Ray ray, Vector3f circleCenter, Vector3f circleNormal,
                                          float circleRadius, float thickness) {
        if (ray == null || circleCenter == null || circleNormal == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (circleRadius <= 0.0f || thickness <= 0.0f) {
            throw new IllegalArgumentException("Radius and thickness must be positive");
        }

        // First, intersect with the circle's plane
        float t = intersectRayPlane(ray, circleCenter, circleNormal);
        if (Float.isInfinite(t)) {
            return Float.POSITIVE_INFINITY; // No plane intersection
        }

        // Get intersection point on plane
        Vector3f intersectionPoint = new Vector3f(ray.origin()).add(
            ray.direction().x * t,
            ray.direction().y * t,
            ray.direction().z * t
        );

        // Check if point is within the circle's interaction band
        float distToCenter = intersectionPoint.distance(circleCenter);
        float minDist = circleRadius - thickness;
        float maxDist = circleRadius + thickness;

        if (distToCenter >= minDist && distToCenter <= maxDist) {
            return t;
        }

        return Float.POSITIVE_INFINITY;
    }

    /**
     * Gets the intersection point on a ray at a given distance.
     */
    public static Vector3f getPointOnRay(CoordinateSystem.Ray ray, float distance) {
        if (ray == null) {
            throw new IllegalArgumentException("Ray cannot be null");
        }

        return ray.getPoint(distance);
    }

    /**
     * Projects a screen-space delta (mouse movement) onto a world-space axis.
     */
    public static float projectScreenDeltaOntoAxis(Vector2f screenDelta, Vector3f axis,
                                                   Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                   int viewportWidth, int viewportHeight) {
        if (screenDelta == null || axis == null) {
            throw new IllegalArgumentException("Screen delta and axis cannot be null");
        }

        // Delegate to unified coordinate system with default sensitivity
        return CoordinateSystem.projectScreenDeltaOntoWorldAxis(
            screenDelta.x, screenDelta.y, axis,
            viewMatrix, projectionMatrix,
            viewportWidth, viewportHeight
        );
    }
}
