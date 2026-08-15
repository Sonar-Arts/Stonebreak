package com.openmason.engine.rendering.viewer.math;

import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the two intersection primitives added for scene picking:
 * ray-vs-AABB (broad phase) and ray-vs-triangle (optional refinement).
 *
 * <p>Both are pure math over JOML — no OpenGL, no viewport.
 *
 * <p>Deliberately flat rather than {@code @Nested}: the test harness maps classes to
 * systems by name, and a nested class reports as {@code Outer$Inner}, which does not
 * match the registry's {@code *Test} pattern and lands in an "unmapped" bucket.
 */
class RaycastUtilIntersectionTest {

    private static final float EPS = 1e-4f;

    private static final Vector3f BOX_MIN = new Vector3f(-1, -1, -1);
    private static final Vector3f BOX_MAX = new Vector3f(1, 1, 1);

    // Unit triangle in the z=0 plane.
    private static final Vector3f V0 = new Vector3f(0, 0, 0);
    private static final Vector3f V1 = new Vector3f(1, 0, 0);
    private static final Vector3f V2 = new Vector3f(0, 1, 0);

    private static CoordinateSystem.Ray ray(float ox, float oy, float oz,
                                            float dx, float dy, float dz) {
        return new CoordinateSystem.Ray(new Vector3f(ox, oy, oz), new Vector3f(dx, dy, dz));
    }

    // ===================== ray vs AABB =====================

    @Test
    @DisplayName("AABB: head-on hit returns the entry distance")
    void aabbHeadOnHit() {
        float t = RaycastUtil.intersectRayAABB(ray(0, 0, -5, 0, 0, 1), BOX_MIN, BOX_MAX);
        assertEquals(4.0f, t, EPS, "entry face of the unit box sits 4 units along the ray");
    }

    @Test
    @DisplayName("AABB: a miss to the side reports no intersection")
    void aabbMissesToTheSide() {
        float t = RaycastUtil.intersectRayAABB(ray(5, 0, -5, 0, 0, 1), BOX_MIN, BOX_MAX);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("AABB: origin inside the box returns the exit distance, not a miss")
    void aabbOriginInsideReturnsExit() {
        // Picking must still hit a box the camera is standing inside.
        float t = RaycastUtil.intersectRayAABB(ray(0, 0, 0, 0, 0, 1), BOX_MIN, BOX_MAX);
        assertEquals(1.0f, t, EPS);
    }

    @Test
    @DisplayName("AABB: a box entirely behind the ray is a miss")
    void aabbBehindOriginIsMiss() {
        float t = RaycastUtil.intersectRayAABB(ray(0, 0, 5, 0, 0, 1), BOX_MIN, BOX_MAX);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("AABB: axis-parallel ray inside a slab still hits (zero direction component)")
    void aabbAxisParallelRayInsideSlab() {
        // dir.y == 0 makes the Y slab bounds +/-Infinity; the slab test must not
        // degenerate into a false miss.
        float t = RaycastUtil.intersectRayAABB(ray(-5, 0.5f, 0, 1, 0, 0), BOX_MIN, BOX_MAX);
        assertEquals(4.0f, t, EPS);
    }

    @Test
    @DisplayName("AABB: axis-parallel ray outside a slab misses")
    void aabbAxisParallelRayOutsideSlab() {
        float t = RaycastUtil.intersectRayAABB(ray(-5, 3.0f, 0, 1, 0, 0), BOX_MIN, BOX_MAX);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("AABB: grazing a face with zero direction on that axis is a clean miss, not NaN")
    void aabbDegenerateGrazeIsNotNaN() {
        // The ray grazes the box's top face travelling along +X: dir.y == 0 and
        // (max.y - origin.y) == 0, so that slab bound computes 0 * Infinity = NaN.
        // Without the explicit guard the NaN would poison the min/max chain.
        float t = RaycastUtil.intersectRayAABB(ray(-5, 1.0f, 0, 1, 0, 0), BOX_MIN, BOX_MAX);
        assertFalse(Float.isNaN(t), "NaN must never escape the slab test");
        assertEquals(Float.POSITIVE_INFINITY, t, "a degenerate graze is reported as a miss");
    }

    @Test
    @DisplayName("AABB: a flat (zero-thickness) box is still hittable")
    void aabbZeroThicknessBox() {
        Vector3f flatMin = new Vector3f(-1, 0, -1);
        Vector3f flatMax = new Vector3f(1, 0, 1);
        float t = RaycastUtil.intersectRayAABB(ray(0, 5, 0, 0, -1, 0), flatMin, flatMax);
        assertEquals(5.0f, t, EPS);
    }

    // ===================== ray vs triangle =====================

    @Test
    @DisplayName("Triangle: hit through the interior")
    void triangleInteriorHit() {
        float t = RaycastUtil.intersectRayTriangle(ray(0.25f, 0.25f, -3, 0, 0, 1), V0, V1, V2);
        assertEquals(3.0f, t, EPS);
    }

    @Test
    @DisplayName("Triangle: miss outside the triangle but inside its plane")
    void triangleMissOutside() {
        float t = RaycastUtil.intersectRayTriangle(ray(0.9f, 0.9f, -3, 0, 0, 1), V0, V1, V2);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("Triangle: backfaces are hit — several shipped assets have mixed winding")
    void triangleBackfaceIsHit() {
        float t = RaycastUtil.intersectRayTriangle(ray(0.25f, 0.25f, 3, 0, 0, -1), V0, V1, V2);
        assertEquals(3.0f, t, EPS, "double-sided by design");
    }

    @Test
    @DisplayName("Triangle: a triangle behind the ray origin is a miss")
    void triangleBehindOriginIsMiss() {
        float t = RaycastUtil.intersectRayTriangle(ray(0.25f, 0.25f, -3, 0, 0, -1), V0, V1, V2);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("Triangle: ray parallel to the triangle plane is a miss")
    void triangleParallelRayIsMiss() {
        float t = RaycastUtil.intersectRayTriangle(ray(0.25f, 0.25f, -3, 1, 0, 0), V0, V1, V2);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("Triangle: a degenerate zero-area triangle is a miss, not a divide-by-zero")
    void triangleDegenerateIsMiss() {
        Vector3f collinear = new Vector3f(2, 0, 0);
        float t = RaycastUtil.intersectRayTriangle(ray(0.25f, 0.0f, -3, 0, 0, 1), V0, V1, collinear);
        assertEquals(Float.POSITIVE_INFINITY, t);
    }

    @Test
    @DisplayName("Triangle: a hit on a vertex is reported")
    void triangleVertexHit() {
        float t = RaycastUtil.intersectRayTriangle(ray(0, 0, -3, 0, 0, 1), V0, V1, V2);
        assertEquals(3.0f, t, EPS);
    }
}
