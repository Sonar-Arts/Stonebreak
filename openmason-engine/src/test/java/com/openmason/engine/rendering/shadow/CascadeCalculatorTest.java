package com.openmason.engine.rendering.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cascade fit is pure matrix math with three promises: every frustum slice lands inside its
 * light volume (or shadows pop at cascade boundaries), the fit is rotation-invariant (or the ortho
 * window breathes as the camera turns), and the window moves in whole texels (or shadow edges
 * shimmer as the camera pans). Each promise is pinned here directly, because a violation of any of
 * them shows up on screen as flicker that is miserable to bisect from the renderer.
 */
class CascadeCalculatorTest {

    /** Mirrors the calculator's private near plane for cascade 0. */
    private static final float CASCADE_NEAR = 0.3f;

    private final CascadeCalculator calculator = new CascadeCalculator();
    private final ShadowSettings settings = ShadowSettings.defaults();
    private final Vector3f sun = new Vector3f(0.4f, 0.8f, 0.2f).normalize();

    private static ShadowCascade[] freshCascades() {
        ShadowCascade[] cascades = new ShadowCascade[ShadowSettings.CASCADE_COUNT];
        for (int i = 0; i < cascades.length; i++) {
            cascades[i] = new ShadowCascade();
        }
        return cascades;
    }

    private static Matrix4f perspective() {
        return new Matrix4f().perspective((float) Math.toRadians(60.0), 16.0f / 9.0f, 0.1f, 1000.0f);
    }

    private static Matrix4f view(Vector3f eye, float yawDegrees) {
        float yaw = (float) Math.toRadians(yawDegrees);
        Vector3f forward = new Vector3f((float) Math.sin(yaw), 0, -(float) Math.cos(yaw));
        return new Matrix4f().lookAt(eye, new Vector3f(eye).add(forward), new Vector3f(0, 1, 0));
    }

    // ── The load-bearing promise: slices fit inside their volumes ────────────

    @Test
    void everySliceCornerLandsInsideItsCascadeVolume() {
        ShadowCascade[] cascades = freshCascades();
        Matrix4f proj = perspective();
        Matrix4f view = view(new Vector3f(120.5f, 71.2f, -48.9f), 37.0f);
        calculator.update(cascades, view, proj, sun, settings);

        Matrix4f cameraWorld = view.invert(new Matrix4f());
        float tanHalfFovY = 1.0f / proj.m11();
        float aspect = proj.m11() / proj.m00();

        // Snapping shifts the window by at most one texel; allow that plus float slack in NDC.
        float ndcSlack = 2.0f * (2.0f / settings.resolution());

        for (int i = 0; i < cascades.length; i++) {
            float near = i == 0 ? CASCADE_NEAR : settings.splitFar(i - 1);
            float far = settings.splitFar(i);
            for (float depth : new float[] {near, far}) {
                float halfH = depth * tanHalfFovY;
                float halfW = halfH * aspect;
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sy = -1; sy <= 1; sy += 2) {
                        Vector3f corner = new Vector3f(sx * halfW, sy * halfH, -depth);
                        cameraWorld.transformPosition(corner);
                        Vector4f clip = new Vector4f(corner, 1.0f).mul(cascades[i].lightViewProj);
                        // Orthographic: w stays 1, clip coords ARE the NDC.
                        assertTrue(Math.abs(clip.x) <= 1.0f + ndcSlack,
                                "cascade " + i + " lost a corner in X: " + clip.x);
                        assertTrue(Math.abs(clip.y) <= 1.0f + ndcSlack,
                                "cascade " + i + " lost a corner in Y: " + clip.y);
                        assertTrue(Math.abs(clip.z) <= 1.0f + 1e-3f,
                                "cascade " + i + " lost a corner in Z: " + clip.z);
                    }
                }
            }
        }
    }

    @Test
    void splitDistancesAscendAndEndAtTheShadowRange() {
        ShadowCascade[] cascades = freshCascades();
        calculator.update(cascades, view(new Vector3f(0, 70, 0), 0), perspective(), sun, settings);

        float previous = 0.0f;
        for (int i = 0; i < cascades.length; i++) {
            assertEquals(settings.splitFar(i), cascades[i].splitFar, 1e-5f);
            assertTrue(cascades[i].splitFar > previous, "splits must ascend");
            previous = cascades[i].splitFar;
        }
        assertEquals(settings.maxDistance(), previous, 1e-4f,
                "the last cascade must reach the full shadow distance");
    }

    // ── Rotation invariance: the reason spheres beat tight boxes ─────────────

    @Test
    void cascadeRadiusDoesNotChangeAsTheCameraTurns() {
        Vector3f eye = new Vector3f(50, 80, 50);
        ShadowCascade[] a = freshCascades();
        ShadowCascade[] b = freshCascades();
        calculator.update(a, view(eye, 0), perspective(), sun, settings);
        calculator.update(b, view(eye, 133), perspective(), sun, settings);

        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i].radius, b[i].radius, a[i].radius * 1e-5f,
                    "cascade " + i + " radius breathed with camera yaw");
        }
    }

    // ── Texel snapping: the anti-shimmer contract ────────────────────────────

    @Test
    void cameraPanMovesAStaticPointByWholeTexels() {
        Vector3f staticPoint = new Vector3f(10.0f, 68.0f, -8.0f);
        Vector3f eyeA = new Vector3f(0, 70, 0);
        Vector3f eyeB = new Vector3f(eyeA).add(0.37f, 0.0f, 0.53f); // deliberately sub-texel

        ShadowCascade[] a = freshCascades();
        ShadowCascade[] b = freshCascades();
        calculator.update(a, view(eyeA, 0), perspective(), sun, settings);
        calculator.update(b, view(eyeB, 0), perspective(), sun, settings);

        for (int i = 0; i < a.length; i++) {
            Vector4f pa = new Vector4f(staticPoint, 1.0f).mul(a[i].lightViewProj);
            Vector4f pb = new Vector4f(staticPoint, 1.0f).mul(b[i].lightViewProj);
            float dxTexels = (pb.x - pa.x) * 0.5f * settings.resolution();
            float dyTexels = (pb.y - pa.y) * 0.5f * settings.resolution();
            assertEquals(Math.round(dxTexels), dxTexels, 0.05f,
                    "cascade " + i + " X shifted by a fractional texel — edges will shimmer");
            assertEquals(Math.round(dyTexels), dyTexels, 0.05f,
                    "cascade " + i + " Y shifted by a fractional texel — edges will shimmer");
        }
    }

    // ── Staggered updates ────────────────────────────────────────────────────

    @Test
    void maskedCascadesKeepTheirPreviousMatrices() {
        ShadowCascade[] cascades = freshCascades();
        Matrix4f proj = perspective();
        calculator.update(cascades, view(new Vector3f(0, 70, 0), 0), proj, sun, settings);
        Matrix4f keptBefore = new Matrix4f(cascades[1].lightViewProj);
        Matrix4f updatedBefore = new Matrix4f(cascades[0].lightViewProj);

        calculator.update(cascades, view(new Vector3f(30, 70, 15), 45), proj, sun, settings,
                new boolean[] {true, false, true});

        assertEquals(keptBefore, cascades[1].lightViewProj,
                "a masked cascade must keep matrices consistent with its rendered depth");
        assertNotEquals(updatedBefore, cascades[0].lightViewProj,
                "an unmasked cascade must follow the camera");
    }

    // ── Degenerate sun ───────────────────────────────────────────────────────

    @Test
    void zenithSunStillProducesFiniteMatrices() {
        ShadowCascade[] cascades = freshCascades();
        calculator.update(cascades, view(new Vector3f(0, 70, 0), 0), perspective(),
                new Vector3f(0, 1, 0), settings);

        float[] buffer = new float[16];
        for (ShadowCascade c : cascades) {
            c.lightViewProj.get(buffer);
            for (float v : buffer) {
                assertTrue(Float.isFinite(v), "zenith sun produced a non-finite matrix element");
            }
        }
    }

    // ── Caster culling ───────────────────────────────────────────────────────

    @Test
    void casterCullAcceptsBoxesInTheSphereAndSweptTowardTheSun() {
        ShadowCascade cascade = new ShadowCascade();
        cascade.centerWorld.set(0, 64, 0);
        cascade.radius = 10.0f;

        assertTrue(cascade.intersectsXZ(-2, -2, 2, 2, 1, 0, 0),
                "a box at the cascade center must cast");
        assertFalse(cascade.intersectsXZ(50, -1, 60, 1, 1, 0, 0),
                "a box far outside the sphere must not cast without a sweep");
        assertTrue(cascade.intersectsXZ(50, -1, 60, 1, 1, 0, 100),
                "the same box swept toward the sun must cast — off-screen casters still matter");
        assertFalse(cascade.intersectsXZ(50, -1, 60, 1, -1, 0, 100),
                "sweeping away from the box must not accept it");
    }
}
