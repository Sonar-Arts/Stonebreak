package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.masonryUI.MWorldMarker.Anchor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MWorldMarker} against a camera whose numbers can be done by hand: 90° vertical field of view,
 * square aspect, standing at z = 5 looking at the origin. At the origin's depth the view is 10 blocks
 * tall and wide, so NDC = world / 5 and one block covers a tenth of the window's height.
 */
class MWorldMarkerTest {

    private static final int W = 800;
    private static final int H = 600;
    private static final float EPS = 0.01f;

    private static Matrix4f camera() {
        return new Matrix4f()
                .perspective((float) Math.toRadians(90.0), 1f, 0.1f, 100f)
                .lookAt(0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f);
    }

    // ── Projection ───────────────────────────────────────────────────────────

    @Test
    void thePointTheCameraLooksAtLandsInTheCentre() {
        Anchor a = MWorldMarker.project(camera(), new Vector3f(0f, 0f, 0f), W, H);
        assertTrue(a.onScreen());
        assertTrue(a.inFront());
        assertEquals(W / 2f, a.x(), EPS);
        assertEquals(H / 2f, a.y(), EPS);
        assertEquals(H / 10f, a.pixelsPerBlock(), EPS, "10 blocks fill the height at this depth");
    }

    @Test
    void offsetsMapToPixelsWithYPointingDown() {
        Anchor right = MWorldMarker.project(camera(), new Vector3f(2.5f, 0f, 0f), W, H);
        assertEquals(0.75f * W, right.x(), EPS);
        assertEquals(H / 2f, right.y(), EPS);

        Anchor up = MWorldMarker.project(camera(), new Vector3f(0f, 2.5f, 0f), W, H);
        assertEquals(W / 2f, up.x(), EPS);
        assertEquals(0.25f * H, up.y(), EPS, "world up is screen up: smaller y");

        Anchor nearer = MWorldMarker.project(camera(), new Vector3f(0f, 0f, 2.5f), W, H);
        assertEquals(H / 5f, nearer.pixelsPerBlock(), EPS, "half the distance, twice the size");
    }

    @Test
    void behindTheCameraIsNothing() {
        Anchor behind = MWorldMarker.project(camera(), new Vector3f(0f, 0f, 10f), W, H);
        assertSame(Anchor.NONE, behind);
        assertFalse(behind.onScreen());
        assertFalse(behind.inFront());
        assertSame(Anchor.NONE, MWorldMarker.project(camera(), new Vector3f(1f, 1f, 5f), W, H), "in the camera's own plane");
    }

    @Test
    void outsideTheViewIsOffScreenButKeepsItsDirection() {
        Anchor far = MWorldMarker.project(camera(), new Vector3f(10f, 0f, 0f), W, H);
        assertFalse(far.onScreen());
        assertTrue(far.inFront());
        assertEquals(1.5f * W, far.x(), EPS, "NDC 2: a window-width-and-a-half to the right");

        Anchor above = MWorldMarker.project(camera(), new Vector3f(0f, 7.5f, 0f), W, H);
        assertFalse(above.onScreen());
        assertEquals(-0.25f * H, above.y(), EPS);

        assertTrue(MWorldMarker.project(camera(), new Vector3f(10f, 0f, 0f), W, H, 1.5f).onScreen(),
                "a margin widens what counts as visible");
        assertTrue(MWorldMarker.project(camera(), new Vector3f(4.9f, -4.9f, 0f), W, H).onScreen(), "just inside the corner");
        assertFalse(MWorldMarker.project(camera(), new Vector3f(5.1f, 0f, 0f), W, H).onScreen(), "just outside the edge");
    }

    @Test
    void missingInputsAreNothing() {
        assertSame(Anchor.NONE, MWorldMarker.project(null, new Vector3f(), W, H));
        assertSame(Anchor.NONE, MWorldMarker.project(camera(), null, W, H));
        assertSame(Anchor.NONE, MWorldMarker.project(camera(), new Vector3f(), 0, H));
        assertSame(Anchor.NONE, MWorldMarker.project(camera(), new Vector3f(), W, -4));
        assertSame(Anchor.NONE, MWorldMarker.project(camera(), new Vector3f(Float.NaN, 0f, 0f), W, H));
        assertSame(Anchor.NONE, MWorldMarker.project(new Matrix4f().zero(), new Vector3f(), W, H));
    }

    @Test
    void projectionIsAPureFunction() {
        Vector3f point = new Vector3f(1.25f, -0.5f, 1f);
        Matrix4f cam = camera();
        Matrix4f before = new Matrix4f(cam);
        Anchor first = MWorldMarker.project(cam, point, W, H);
        assertEquals(first, MWorldMarker.project(cam, point, W, H));
        assertEquals(before, cam, "the matrix is not touched");
        assertEquals(new Vector3f(1.25f, -0.5f, 1f), point, "nor the point");
    }

    // ── Band, fallback, radius ───────────────────────────────────────────────

    @Test
    void clampToBandSlidesTheAnchorInsideAndKeepsItsFlags() {
        Anchor inside = new Anchor(400f, 300f, 60f, true);
        assertEquals(inside, MWorldMarker.clampToBand(inside, 40f, 100f, 760f, 500f));

        Anchor high = MWorldMarker.clampToBand(new Anchor(400f, 20f, 60f, true), 40f, 100f, 760f, 500f);
        assertEquals(new Anchor(400f, 100f, 60f, true), high);

        Anchor offRight = MWorldMarker.clampToBand(new Anchor(1200f, 900f, 30f, false), 40f, 100f, 760f, 500f);
        assertEquals(new Anchor(760f, 500f, 30f, false), offRight, "an edge indicator pins to the nearest corner");

        assertEquals(new Anchor(40f, 100f, 0f, true),
                MWorldMarker.clampToBand(new Anchor(Float.NaN, 300f, 0f, true), 40f, 100f, 10f, 100f),
                "NaN and an inverted range collapse onto the minimum");
        assertSame(Anchor.NONE, MWorldMarker.clampToBand(null, 0f, 0f, 1f, 1f));
    }

    @Test
    void withinBandTreatsAnAnchorUnderTheChromeAsOffScreen() {
        Anchor visible = new Anchor(400f, 300f, 60f, true);
        assertSame(visible, MWorldMarker.withinBand(visible, 0f, 100f, W, 500f));

        Anchor hidden = MWorldMarker.withinBand(new Anchor(400f, 40f, 60f, true), 0f, 100f, W, 500f);
        assertFalse(hidden.onScreen());
        assertEquals(40f, hidden.y(), 0f, "it is marked, not moved");
        assertSame(Anchor.NONE, MWorldMarker.withinBand(Anchor.NONE, 0f, 0f, W, H));
        assertSame(Anchor.NONE, MWorldMarker.withinBand(null, 0f, 0f, W, H));
    }

    @Test
    void orFallbackPinsOnlyWhatIsOffScreen() {
        Anchor visible = new Anchor(400f, 300f, 60f, true);
        assertSame(visible, MWorldMarker.orFallback(visible, 620f, 48f));

        for (Anchor lost : new Anchor[]{Anchor.NONE, null, new Anchor(1200f, 300f, 60f, false)}) {
            Anchor pinned = MWorldMarker.orFallback(lost, 620f, 48f);
            assertEquals(620f, pinned.x(), 0f);
            assertEquals(48f, pinned.y(), 0f);
            assertFalse(pinned.onScreen(), "still reported as not on screen, so the caller knows it is pinned");
            assertEquals(0f, pinned.pixelsPerBlock(), 0f);
        }
        assertEquals(new Anchor(0f, 0f, 0f, false), MWorldMarker.orFallback(null, Float.NaN, Float.NEGATIVE_INFINITY));
    }

    @Test
    void theWholeChainPinsAHiddenTargetToItsPlate() {
        // Visible in the window, but the top 100 px belong to the HUD: the cursor goes to the plate.
        Anchor raw = MWorldMarker.project(camera(), new Vector3f(0f, 4f, 0f), W, H);
        assertTrue(raw.onScreen());
        Anchor placed = MWorldMarker.orFallback(MWorldMarker.withinBand(raw, 0f, 100f, W, 500f), 620f, 48f);
        assertEquals(new Anchor(620f, 48f, 0f, false), placed);
        assertEquals(24f, MWorldMarker.radiusFor(placed, 1.2f, 24f, 160f), 0f, "a pinned marker takes its minimum size");
    }

    @Test
    void radiusForScalesWithDepthWithinItsLimits() {
        Anchor a = new Anchor(0f, 0f, 60f, true);
        assertEquals(72f, MWorldMarker.radiusFor(a, 1.2f, 24f, 160f), EPS);
        assertEquals(24f, MWorldMarker.radiusFor(a, 0.1f, 24f, 160f), EPS);
        assertEquals(160f, MWorldMarker.radiusFor(a, 50f, 24f, 160f), EPS);
        assertEquals(24f, MWorldMarker.radiusFor(a, 50f, 24f, 10f), EPS, "max below min: min wins");
        assertEquals(24f, MWorldMarker.radiusFor(null, 1f, 24f, 160f), EPS);
        assertEquals(24f, MWorldMarker.radiusFor(a, Float.NaN, 24f, 160f), EPS);
        assertEquals(0f, MWorldMarker.radiusFor(a, 1f, Float.NaN, 0f), EPS);
        assertEquals(60f, MWorldMarker.radiusFor(a, 1f, -5f, Float.NaN), EPS, "no usable max means no max");
    }
}
