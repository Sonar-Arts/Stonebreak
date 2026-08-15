package com.openmason.engine.rendering.viewer.math;

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
     * Tests intersection between a ray and an axis-aligned bounding box (slab method).
     *
     * <p>Returns the nearest non-negative hit distance, so a ray whose origin is
     * <em>inside</em> the box returns the exit distance rather than missing. This
     * mirrors {@link #intersectRaySphere} and is what picking wants: an object the
     * camera is inside of is still under the cursor.
     *
     * <p>Axis-parallel rays are handled by IEEE division — a zero direction component
     * yields ±Infinity slab bounds, which compare correctly. The one degenerate case
     * is an origin exactly on a slab plane with zero direction on that axis (0/0 =
     * NaN); the explicit NaN guard treats it as a miss rather than letting NaN
     * propagate silently through the min/max chain.
     *
     * @param ray the ray to test
     * @param boxMin minimum corner
     * @param boxMax maximum corner
     * @return distance along the ray to the intersection, or
     *         {@link Float#POSITIVE_INFINITY} if there is none
     */
    public static float intersectRayAABB(CoordinateSystem.Ray ray, Vector3f boxMin, Vector3f boxMax) {
        if (ray == null || boxMin == null || boxMax == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        Vector3f o = ray.origin();
        Vector3f d = ray.direction();

        float tMin = Float.NEGATIVE_INFINITY;
        float tMax = Float.POSITIVE_INFINITY;

        for (int axis = 0; axis < 3; axis++) {
            float origin = componentOf(o, axis);
            float dir = componentOf(d, axis);
            float slabLow = componentOf(boxMin, axis);
            float slabHigh = componentOf(boxMax, axis);

            float inv = 1.0f / dir;
            float t1 = (slabLow - origin) * inv;
            float t2 = (slabHigh - origin) * inv;

            if (Float.isNaN(t1) || Float.isNaN(t2)) {
                return Float.POSITIVE_INFINITY;
            }
            if (t1 > t2) {
                float swap = t1;
                t1 = t2;
                t2 = swap;
            }

            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);

            if (tMin > tMax) {
                return Float.POSITIVE_INFINITY;
            }
        }

        if (tMax < 0.0f) {
            return Float.POSITIVE_INFINITY; // box is entirely behind the ray
        }
        return tMin >= 0.0f ? tMin : tMax;
    }

    /**
     * Tests intersection between a ray and a triangle (Möller–Trumbore).
     *
     * <p><b>Double-sided by design.</b> Several shipped assets have mixed triangle
     * winding, so culling backfaces here would make parts of a model unpickable
     * depending on how it happened to be authored.
     *
     * @return distance along the ray to the intersection, or
     *         {@link Float#POSITIVE_INFINITY} if there is none (including a
     *         degenerate triangle or a ray parallel to its plane)
     */
    public static float intersectRayTriangle(CoordinateSystem.Ray ray,
                                             Vector3f v0, Vector3f v1, Vector3f v2) {
        if (ray == null || v0 == null || v1 == null || v2 == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        final float epsilon = 1e-7f;

        Vector3f edge1 = new Vector3f(v1).sub(v0);
        Vector3f edge2 = new Vector3f(v2).sub(v0);
        Vector3f pvec = new Vector3f(ray.direction()).cross(edge2);
        float det = edge1.dot(pvec);

        // |det| near zero: ray parallel to the triangle plane, or the triangle is
        // degenerate (zero area). Both are misses.
        if (Math.abs(det) < epsilon) {
            return Float.POSITIVE_INFINITY;
        }

        float invDet = 1.0f / det;

        Vector3f tvec = new Vector3f(ray.origin()).sub(v0);
        float u = tvec.dot(pvec) * invDet;
        if (u < 0.0f || u > 1.0f) {
            return Float.POSITIVE_INFINITY;
        }

        Vector3f qvec = new Vector3f(tvec).cross(edge1);
        float v = ray.direction().dot(qvec) * invDet;
        if (v < 0.0f || u + v > 1.0f) {
            return Float.POSITIVE_INFINITY;
        }

        float t = edge2.dot(qvec) * invDet;
        return t >= 0.0f ? t : Float.POSITIVE_INFINITY;
    }

    /** Component accessor so the slab loop can iterate axes without branching per axis. */
    private static float componentOf(Vector3f v, int axis) {
        return switch (axis) {
            case 0 -> v.x;
            case 1 -> v.y;
            default -> v.z;
        };
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
