package com.openmason.ui.viewport.gizmo.interaction;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Utility class for 3D raycasting and intersection tests.
 * Provides functions for ray-sphere, ray-line, ray-plane, and ray-circle intersection.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only handles geometric intersection calculations
 * - KISS: Uses straightforward mathematical formulas
 * - SAFE: Validates all inputs and handles edge cases
 */
public final class RaycastUtil {

    // Private constructor to prevent instantiation (utility class)
    private RaycastUtil() {
        throw new AssertionError("RaycastUtil is a utility class and should not be instantiated");
    }

    /**
     * Represents a 3D ray with origin and direction.
     */
    public static class Ray {
        public final Vector3f origin;
        public final Vector3f direction; // Normalized

        public Ray(Vector3f origin, Vector3f direction) {
            if (origin == null || direction == null) {
                throw new IllegalArgumentException("Ray parameters cannot be null");
            }
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction).normalize();
        }
    }

    /**
     * Creates a ray from screen coordinates using camera matrices.
     *
     * @param screenX Screen X coordinate (pixels)
     * @param screenY Screen Y coordinate (pixels)
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return Ray in world space
     */
    public static Ray createRayFromScreen(float screenX, float screenY,
                                         int viewportWidth, int viewportHeight,
                                         Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (viewMatrix == null || projectionMatrix == null) {
            throw new IllegalArgumentException("Matrices cannot be null");
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }

        // Convert screen coordinates to normalized device coordinates (NDC)
        // Screen: (0,0) top-left, (width,height) bottom-right
        // NDC: (-1,-1) bottom-left, (1,1) top-right
        float ndcX = (2.0f * screenX) / viewportWidth - 1.0f;
        float ndcY = 1.0f - (2.0f * screenY) / viewportHeight; // Flip Y

        // Create ray in clip space (at near and far planes)
        Vector4f rayClipNear = new Vector4f(ndcX, ndcY, -1.0f, 1.0f); // Near plane
        Vector4f rayClipFar = new Vector4f(ndcX, ndcY, 1.0f, 1.0f);   // Far plane

        // Transform to view space
        Matrix4f invProjection = new Matrix4f(projectionMatrix).invert();
        Vector4f rayViewNear = invProjection.transform(rayClipNear);
        Vector4f rayViewFar = invProjection.transform(rayClipFar);

        // Perspective divide
        rayViewNear.div(rayViewNear.w);
        rayViewFar.div(rayViewFar.w);

        // Transform to world space
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        Vector4f rayWorldNear = invView.transform(rayViewNear);
        Vector4f rayWorldFar = invView.transform(rayViewFar);

        // Create ray
        Vector3f origin = new Vector3f(rayWorldNear.x, rayWorldNear.y, rayWorldNear.z);
        Vector3f target = new Vector3f(rayWorldFar.x, rayWorldFar.y, rayWorldFar.z);
        Vector3f direction = new Vector3f(target).sub(origin).normalize();

        return new Ray(origin, direction);
    }

    /**
     * Tests intersection between a ray and a sphere.
     *
     * @param ray The ray to test
     * @param sphereCenter Center of the sphere
     * @param sphereRadius Radius of the sphere
     * @return Distance along ray to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    public static float intersectRaySphere(Ray ray, Vector3f sphereCenter, float sphereRadius) {
        if (ray == null || sphereCenter == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (sphereRadius <= 0.0f) {
            throw new IllegalArgumentException("Sphere radius must be positive");
        }

        // Vector from ray origin to sphere center
        Vector3f oc = new Vector3f(ray.origin).sub(sphereCenter);

        // Quadratic equation coefficients: at^2 + bt + c = 0
        float a = ray.direction.dot(ray.direction); // Should be 1.0 since normalized
        float b = 2.0f * oc.dot(ray.direction);
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
     * Tests intersection between a ray and a line segment.
     * Returns the closest distance between the ray and the line.
     *
     * @param ray The ray to test
     * @param lineStart Start of the line segment
     * @param lineEnd End of the line segment
     * @param threshold Maximum distance to consider a hit
     * @return Distance along ray to closest point, or Float.POSITIVE_INFINITY if too far
     */
    public static float intersectRayLine(Ray ray, Vector3f lineStart, Vector3f lineEnd, float threshold) {
        if (ray == null || lineStart == null || lineEnd == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }
        if (threshold <= 0.0f) {
            throw new IllegalArgumentException("Threshold must be positive");
        }

        // Line direction
        Vector3f lineDir = new Vector3f(lineEnd).sub(lineStart);
        float lineLength = lineDir.length();
        if (lineLength < 0.0001f) {
            // Degenerate line, treat as point
            return intersectRaySphere(ray, lineStart, threshold);
        }
        lineDir.normalize();

        // Vector from line start to ray origin
        Vector3f w0 = new Vector3f(ray.origin).sub(lineStart);

        // Compute closest points on both lines
        float a = ray.direction.dot(ray.direction); // 1.0
        float b = ray.direction.dot(lineDir);
        float c = lineDir.dot(lineDir); // 1.0
        float d = ray.direction.dot(w0);
        float e = lineDir.dot(w0);

        float denom = a * c - b * b; // 1.0 - cos^2(angle) = sin^2(angle)

        float sc, tc;
        if (denom < 0.0001f) {
            // Lines are parallel
            sc = 0.0f;
            tc = (b > c ? d / b : e / c);
        } else {
            sc = (b * e - c * d) / denom;
            tc = (a * e - b * d) / denom;
        }

        // Clamp tc to line segment bounds [0, lineLength]
        tc = Math.max(0.0f, Math.min(lineLength, tc));

        // Get closest points
        Vector3f pointOnRay = new Vector3f(ray.origin).add(
            ray.direction.x * sc,
            ray.direction.y * sc,
            ray.direction.z * sc
        );

        Vector3f pointOnLine = new Vector3f(lineStart).add(
            lineDir.x * tc,
            lineDir.y * tc,
            lineDir.z * tc
        );

        // Check distance
        float distance = pointOnRay.distance(pointOnLine);
        if (distance <= threshold && sc > 0.0f) {
            return sc; // Distance along ray
        }

        return Float.POSITIVE_INFINITY;
    }

    /**
     * Tests intersection between a ray and a plane.
     *
     * @param ray The ray to test
     * @param planePoint A point on the plane
     * @param planeNormal Normal vector of the plane (must be normalized)
     * @return Distance along ray to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    public static float intersectRayPlane(Ray ray, Vector3f planePoint, Vector3f planeNormal) {
        if (ray == null || planePoint == null || planeNormal == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Check if ray is parallel to plane
        float denom = planeNormal.dot(ray.direction);
        if (Math.abs(denom) < 0.0001f) {
            return Float.POSITIVE_INFINITY; // Parallel, no intersection
        }

        // Calculate intersection distance
        Vector3f p0l0 = new Vector3f(planePoint).sub(ray.origin);
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
     *
     * @param ray The ray to test
     * @param circleCenter Center of the circle
     * @param circleNormal Normal vector defining the circle plane
     * @param circleRadius Radius of the circle
     * @param thickness Thickness of the interaction band
     * @return Distance along ray to intersection, or Float.POSITIVE_INFINITY if no hit
     */
    public static float intersectRayCircle(Ray ray, Vector3f circleCenter, Vector3f circleNormal,
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
        Vector3f intersectionPoint = new Vector3f(ray.origin).add(
            ray.direction.x * t,
            ray.direction.y * t,
            ray.direction.z * t
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
     *
     * @param ray The ray
     * @param distance Distance along the ray
     * @return The point at that distance
     */
    public static Vector3f getPointOnRay(Ray ray, float distance) {
        if (ray == null) {
            throw new IllegalArgumentException("Ray cannot be null");
        }

        return new Vector3f(ray.origin).add(
            ray.direction.x * distance,
            ray.direction.y * distance,
            ray.direction.z * distance
        );
    }

    /**
     * Projects a screen-space delta (mouse movement) onto a world-space axis.
     *
     * <p>This method handles coordinate system conversion internally:
     * - Input: Mouse delta in ImGui screen space (Y=0 at top, Y increases downward)
     * - Output: Projected movement along the world-space axis in world units
     * - The world-space axis is projected to clip space and compared with screen delta
     *
     * @param screenDelta Mouse movement in screen space (pixels, ImGui coordinates)
     * @param axis World-space axis to project onto (must be normalized)
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @param viewportWidth Viewport width
     * @param viewportHeight Viewport height
     * @return Projected movement along the axis in world units
     */
    public static float projectScreenDeltaOntoAxis(Vector2f screenDelta, Vector3f axis,
                                                   Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                   int viewportWidth, int viewportHeight) {
        if (screenDelta == null || axis == null || viewMatrix == null || projectionMatrix == null) {
            throw new IllegalArgumentException("Parameters cannot be null");
        }

        // Project axis to screen space
        Vector3f axisScreenDir = projectWorldDirectionToScreen(axis, viewMatrix, projectionMatrix);

        // Calculate dot product (projection of mouse movement onto axis screen direction)
        float screenLength = screenDelta.length();
        if (screenLength < 0.001f) {
            return 0.0f; // No movement
        }

        Vector2f screenDeltaNorm = new Vector2f(screenDelta).normalize();
        Vector2f axisScreen2D = new Vector2f(axisScreenDir.x, axisScreenDir.y);

        if (axisScreen2D.lengthSquared() < 0.001f) {
            return 0.0f; // Axis perpendicular to screen
        }

        axisScreen2D.normalize();

        // Dot product gives us the projection
        float projection = screenDeltaNorm.dot(axisScreen2D);

        // Correct sign based on view-space depth: if axis points toward camera (positive Z in view space),
        // the visual direction is reversed, so we need to flip the sign
        // Transform axis to view space to check its Z component
        Vector3f axisViewSpace = new Vector3f(axis).normalize();
        viewMatrix.transformDirection(axisViewSpace);

        // In view space, camera looks down -Z, so:
        // - If axis.z > 0: axis points toward camera (opposite of camera view direction) → flip sign
        // - If axis.z < 0: axis points away from camera (same as camera view direction) → normal
        float signCorrection = (axisViewSpace.z > 0) ? 1.0f : -1.0f;

        // Scale by screen movement length and viewport size
        float worldScale = 0.01f; // Sensitivity factor
        return signCorrection * projection * screenLength * worldScale;
    }

    /**
     * Projects a world-space direction vector to screen space.
     *
     * @param worldDirection World-space direction (will be normalized)
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return Screen-space direction (XY = screen, Z = depth)
     */
    private static Vector3f projectWorldDirectionToScreen(Vector3f worldDirection,
                                                           Matrix4f viewMatrix,
                                                           Matrix4f projectionMatrix) {
        // Transform direction to view space
        Vector3f viewDir = new Vector3f(worldDirection).normalize();
        viewMatrix.transformDirection(viewDir);

        // Transform to clip space
        Vector4f clipDir = new Vector4f(viewDir, 0.0f);
        projectionMatrix.transform(clipDir);

        // Return clip-space direction (Y-up convention)
        // The coordinate system conversion happens implicitly during dot product calculation
        if (Math.abs(clipDir.w) > 0.0001f) {
            return new Vector3f(clipDir.x / clipDir.w, clipDir.y / clipDir.w, clipDir.z / clipDir.w);
        }

        return new Vector3f(clipDir.x, clipDir.y, clipDir.z);
    }
}
