package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FaceModalMath} — inset/extrude preview geometry, face basis
 * (centroid + Newell normal), pixels-per-unit probing, and the amount sign
 * conventions of the modal inset (I) and extrude (E) tools.
 *
 * <p>Screen-space cases use identity camera matrices with a 200×100 viewport:
 * model (x, y, z) projects to screen ((x + 1) * 100, (1 − y) * 50), so the
 * origin lands at (100, 50) and one world unit along +X covers 100 pixels.
 */
class FaceModalMathTest {

    private static final float EPS = 1e-4f;
    private static final int VIEWPORT_W = 200;
    private static final int VIEWPORT_H = 100;

    /** Unit quad in the XY plane centered at the origin, CCW viewed from +Z. */
    private static Vector3f[] xyQuad() {
        return new Vector3f[] {
            new Vector3f(-0.5f, -0.5f, 0f),
            new Vector3f(0.5f, -0.5f, 0f),
            new Vector3f(0.5f, 0.5f, 0f),
            new Vector3f(-0.5f, 0.5f, 0f)
        };
    }

    // ── Face basis ────────────────────────────────────────────────────────

    @Test
    void centroidAveragesCorners() {
        Vector3f c = FaceModalMath.centroid(xyQuad());
        assertNotNull(c);
        assertEquals(0f, c.x, EPS);
        assertEquals(0f, c.y, EPS);
        assertEquals(0f, c.z, EPS);
    }

    @Test
    void centroidOfEmptyLoopIsNull() {
        assertNull(FaceModalMath.centroid(null));
        assertNull(FaceModalMath.centroid(new Vector3f[0]));
    }

    @Test
    void newellNormalOfCcwXyQuadIsPlusZ() {
        Vector3f n = FaceModalMath.newellNormal(xyQuad());
        assertEquals(0f, n.x, EPS);
        assertEquals(0f, n.y, EPS);
        assertEquals(1f, n.z, EPS);
    }

    @Test
    void newellNormalRespectsWinding() {
        Vector3f[] quad = xyQuad();
        // Reverse the loop → normal flips to −Z
        Vector3f[] reversed = {quad[3], quad[2], quad[1], quad[0]};
        Vector3f n = FaceModalMath.newellNormal(reversed);
        assertEquals(-1f, n.z, EPS);
    }

    @Test
    void newellNormalOfDegenerateLoopIsZero() {
        Vector3f[] collinear = {
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), new Vector3f(2, 0, 0)
        };
        Vector3f n = FaceModalMath.newellNormal(collinear);
        assertEquals(0f, n.length(), EPS);
    }

    // ── Inset preview (corner→centroid shrink) ────────────────────────────

    @Test
    void insetPreviewShrinksCornersTowardCentroid() {
        float amount = 0.1f;
        float[] segments = FaceModalMath.insetPreviewSegments(xyQuad(), amount);

        // 4 inner-polygon edges + 4 spokes = 8 segments = 48 floats
        assertEquals(48, segments.length);

        // Spokes come after the inner polygon: spoke 0 is loop[0] → inner[0]
        assertEquals(-0.5f, segments[24], EPS);
        assertEquals(-0.5f, segments[25], EPS);
        assertEquals(0f, segments[26], EPS);

        // inner[0]: corner (−0.5,−0.5,0) moved 0.1 toward centroid (0,0,0)
        float step = 0.1f / (float) Math.sqrt(2.0);
        assertEquals(-0.5f + step, segments[27], EPS);
        assertEquals(-0.5f + step, segments[28], EPS);
        assertEquals(0f, segments[29], EPS);

        // Inner polygon edge 0 starts at inner[0]
        assertEquals(-0.5f + step, segments[0], EPS);
        assertEquals(-0.5f + step, segments[1], EPS);
    }

    @Test
    void insetPreviewClampsAtCentroid() {
        // Amount far larger than the corner→centroid distance → inner corner == centroid
        float[] segments = FaceModalMath.insetPreviewSegments(xyQuad(), 10f);
        // inner[0] (end of spoke 0)
        assertEquals(0f, segments[27], EPS);
        assertEquals(0f, segments[28], EPS);
        assertEquals(0f, segments[29], EPS);
    }

    @Test
    void insetPreviewOfDegenerateLoopIsEmpty() {
        assertEquals(0, FaceModalMath.insetPreviewSegments(null, 0.1f).length);
        assertEquals(0, FaceModalMath.insetPreviewSegments(
            new Vector3f[] {new Vector3f(), new Vector3f(1, 0, 0)}, 0.1f).length);
    }

    // ── Extrude preview (offset polygon + struts) ─────────────────────────

    @Test
    void extrudePreviewOffsetsAlongNormal() {
        float[] segments = FaceModalMath.extrudePreviewSegments(
            xyQuad(), new Vector3f(0, 0, 1), 0.5f);

        // 4 offset-polygon edges + 4 struts = 8 segments = 48 floats
        assertEquals(48, segments.length);

        // Strut 0 is loop[0] → loop[0] + normal·0.5
        assertEquals(-0.5f, segments[24], EPS);
        assertEquals(-0.5f, segments[25], EPS);
        assertEquals(0f, segments[26], EPS);
        assertEquals(-0.5f, segments[27], EPS);
        assertEquals(-0.5f, segments[28], EPS);
        assertEquals(0.5f, segments[29], EPS);

        // Offset polygon edge 0 starts at the moved corner (z = 0.5)
        assertEquals(0.5f, segments[2], EPS);
    }

    @Test
    void extrudePreviewSupportsNegativeDistance() {
        float[] segments = FaceModalMath.extrudePreviewSegments(
            xyQuad(), new Vector3f(0, 0, 1), -0.25f);
        // Moved corner z = −0.25
        assertEquals(-0.25f, segments[2], EPS);
    }

    // ── Pixels-per-unit + screen direction ────────────────────────────────

    @Test
    void pixelsPerUnitMeasuresProjectedScale() {
        Matrix4f identity = new Matrix4f();
        float ppu = FaceModalMath.pixelsPerUnit(
            new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), identity, VIEWPORT_W, VIEWPORT_H);
        assertEquals(100f, ppu, EPS);
    }

    @Test
    void pixelsPerUnitIsZeroForViewAlignedDirection() {
        Matrix4f identity = new Matrix4f();
        // +Z is view-aligned under an identity MVP — projects to the same pixel
        float ppu = FaceModalMath.pixelsPerUnit(
            new Vector3f(0, 0, 0), new Vector3f(0, 0, 1), identity, VIEWPORT_W, VIEWPORT_H);
        assertTrue(ppu <= FaceModalMath.MIN_PIXELS_PER_UNIT);
    }

    @Test
    void screenDirectionPointsUpForPlusY() {
        Matrix4f identity = new Matrix4f();
        Vector2f dir = FaceModalMath.screenDirection(
            new Vector3f(0, 0, 0), new Vector3f(0, 1, 0), identity, VIEWPORT_W, VIEWPORT_H);
        assertNotNull(dir);
        // Screen Y grows downward → model +Y is screen −Y
        assertEquals(0f, dir.x, EPS);
        assertEquals(-1f, dir.y, EPS);
    }

    @Test
    void screenDirectionIsNullWhenDegenerate() {
        Matrix4f identity = new Matrix4f();
        assertNull(FaceModalMath.screenDirection(
            new Vector3f(0, 0, 0), new Vector3f(0, 0, 1), identity, VIEWPORT_W, VIEWPORT_H));
    }

    // ── Amount conventions ────────────────────────────────────────────────

    @Test
    void insetAmountGrowsTowardCentroid() {
        Vector2f centroidScreen = new Vector2f(100, 50);
        // Started 50px away, now 30px away, at 10 px per world unit → 2 units inset
        assertEquals(2f, FaceModalMath.insetAmount(50f, 100f, 80f, centroidScreen, 10f), EPS);
    }

    @Test
    void insetAmountClampsToZeroWhenMovingAway() {
        Vector2f centroidScreen = new Vector2f(100, 50);
        // Moving away from the centroid never produces a negative inset
        assertEquals(0f, FaceModalMath.insetAmount(50f, 100f, 120f, centroidScreen, 10f), EPS);
    }

    @Test
    void insetAmountIsZeroForDegeneratePpu() {
        assertEquals(0f, FaceModalMath.insetAmount(50f, 100f, 80f, new Vector2f(100, 50), 0f), EPS);
    }

    @Test
    void extrudeAmountFollowsScreenDirection() {
        Vector2f screenDir = new Vector2f(0, -1); // normal projects "up" on screen
        // Mouse moved 20px up (Y −20) at 10 px/unit → +2 units along the normal
        assertEquals(2f, FaceModalMath.extrudeAmount(100f, 50f, 100f, 30f, screenDir, 10f), EPS);
        // Mouse moved 20px down → −2 units (signed)
        assertEquals(-2f, FaceModalMath.extrudeAmount(100f, 50f, 100f, 70f, screenDir, 10f), EPS);
    }

    @Test
    void extrudeAmountFallsBackToVerticalDelta() {
        // No screen direction (view-aligned normal): mouse up = positive extrude
        assertEquals(2f, FaceModalMath.extrudeAmount(100f, 50f, 100f, 30f, null, 10f), EPS);
        assertEquals(-2f, FaceModalMath.extrudeAmount(100f, 50f, 100f, 70f, null, 10f), EPS);
    }

    @Test
    void extrudeAmountIsZeroForDegeneratePpu() {
        assertEquals(0f, FaceModalMath.extrudeAmount(100f, 50f, 100f, 30f, null, 0f), EPS);
    }
}
