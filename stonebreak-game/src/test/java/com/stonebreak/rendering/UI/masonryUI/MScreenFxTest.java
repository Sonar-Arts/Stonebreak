package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MScreenFx}: the vignette contract (nothing inside the inner radius, capped at the corners,
 * composite cap for a stack), letterbox geometry, and the simple washes. The curve is pinned as a
 * pure function and then again in pixels, so the painter cannot drift from it.
 */
class MScreenFxTest {

    private static final int W = 200;
    private static final int H = 120;
    private static final int WHITE = 0xFFFFFFFF;
    private static final float EPS = 1.0e-4f;

    private static int red(int argb) { return (argb >> 16) & 0xFF; }

    /** Red channel of {@code alpha} white over the fixture's background. */
    private static float whiteOver(float alpha) {
        int bg = red(RasterUiFixture.BACKGROUND);
        return bg + alpha * (255 - bg);
    }

    // ── Vignette curve ───────────────────────────────────────────────────────

    @Test
    void theCurveIsExactlyZeroInsideTheInnerRadius() {
        for (float inner : new float[]{0f, 0.3f, 0.5f, 0.82f, 1.2f}) {
            for (float r = 0f; r <= inner; r += 0.01f) {
                assertEquals(0f, MScreenFx.vignetteAlpha(r, 0.4f, inner), 0f, "r=" + r + " inner=" + inner);
            }
            assertEquals(0f, MScreenFx.vignetteAlpha(inner, 0.4f, inner), 0f);
            assertTrue(MScreenFx.vignetteAlpha(inner + 0.1f, 0.4f, inner) > 0f, "and rises just outside it");
        }
    }

    @Test
    void theCurveRisesSmoothlyToThePeakAtTheCorners() {
        float previous = 0f;
        for (float r = 0f; r <= MScreenFx.CORNER_RADIUS + 0.2f; r += 0.005f) {
            float a = MScreenFx.vignetteAlpha(r, 0.4f, 0.5f);
            assertTrue(a >= previous - EPS, "monotonic at r=" + r);
            assertTrue(a - previous < 0.02f, "no step at r=" + r);
            previous = a;
        }
        assertEquals(0.4f, MScreenFx.vignetteAlpha(MScreenFx.CORNER_RADIUS, 0.4f, 0.5f), EPS);
    }

    @Test
    void thePeakIsHardCapped() {
        assertEquals(MScreenFx.VIGNETTE_MAX_ALPHA, MScreenFx.vignetteAlpha(MScreenFx.CORNER_RADIUS, 1f, 0.5f), EPS);
        assertEquals(MScreenFx.VIGNETTE_MAX_ALPHA, MScreenFx.vignetteAlpha(5f, 40f, 0f), EPS);
        for (float r = 0f; r < 3f; r += 0.05f) {
            assertTrue(MScreenFx.vignetteAlpha(r, 9f, 0.1f) <= MScreenFx.VIGNETTE_MAX_ALPHA + EPS);
        }
    }

    @Test
    void theCurveSurvivesNonsense() {
        assertEquals(0f, MScreenFx.vignetteAlpha(Float.NaN, 0.4f, 0.5f));
        assertEquals(0f, MScreenFx.vignetteAlpha(1.2f, Float.NaN, 0.5f));
        assertEquals(0f, MScreenFx.vignetteAlpha(1.2f, -3f, 0.5f));
        assertTrue(MScreenFx.vignetteAlpha(1.4f, 0.4f, Float.NaN) > 0f, "a NaN inner radius falls back to 0.5");
        assertEquals(0f, MScreenFx.vignetteAlpha(1.0f, 0.4f, 99f), 0f, "an absurd inner radius still leaves a ramp to the corner");
        assertTrue(MScreenFx.vignetteAlpha(MScreenFx.CORNER_RADIUS, 0.4f, 99f) > 0.39f);
    }

    @Test
    void normalisedRadiusIsZeroCentreOneMidEdgeRootTwoCorner() {
        assertEquals(0f, MScreenFx.normalisedRadius(W / 2f, H / 2f, W, H), EPS);
        assertEquals(1f, MScreenFx.normalisedRadius(W, H / 2f, W, H), EPS);
        assertEquals(1f, MScreenFx.normalisedRadius(W / 2f, 0f, W, H), EPS);
        assertEquals(MScreenFx.CORNER_RADIUS, MScreenFx.normalisedRadius(0f, 0f, W, H), EPS);
        assertEquals(0f, MScreenFx.normalisedRadius(5f, 5f, 0f, H));
    }

    // ── Vignette pixels ──────────────────────────────────────────────────────

    @Test
    void thePaintedVignetteLeavesTheCentreUntouchedAndIsCappedInTheCorners() {
        for (float inner : new float[]{0.5f, 0.82f}) {
            RasterUiFixture fx = new RasterUiFixture(W, H);
            MScreenFx.vignette(fx.canvas, W, H, WHITE, 1f, inner);   // asks for far more than the cap

            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    float r = MScreenFx.normalisedRadius(x + 0.5f, y + 0.5f, W, H);
                    int pixel = fx.bitmap.getColor(x, y);
                    if (r <= inner - 0.02f) {
                        assertEquals(RasterUiFixture.BACKGROUND, pixel, "untouched inside the inner radius at " + x + "," + y);
                    }
                    assertTrue(red(pixel) <= whiteOver(MScreenFx.VIGNETTE_MAX_ALPHA) + 2f, "capped at " + x + "," + y);
                }
            }
            for (int[] corner : new int[][]{{0, 0}, {W - 1, 0}, {0, H - 1}, {W - 1, H - 1}}) {
                float got = red(fx.bitmap.getColor(corner[0], corner[1]));
                assertEquals(whiteOver(MScreenFx.VIGNETTE_MAX_ALPHA), got, 3f, "the corners carry the (capped) peak");
            }
        }
    }

    @Test
    void thePaintedVignetteFollowsTheCurve() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MScreenFx.vignette(fx.canvas, W, H, WHITE, 0.4f, 0.5f);
        for (int x = 0; x < W; x += 7) {
            float r = MScreenFx.normalisedRadius(x + 0.5f, 10.5f, W, H);
            assertEquals(whiteOver(MScreenFx.vignetteAlpha(r, 0.4f, 0.5f)), red(fx.bitmap.getColor(x, 10)), 4f, "x=" + x);
        }
    }

    @Test
    void aFaintVignetteOrDegenerateInputPaintsNothing() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> {
            MScreenFx.vignette(fx.canvas, W, H, WHITE, 0f, 0.5f);
            MScreenFx.vignette(fx.canvas, W, H, WHITE, Float.NaN, 0.5f);
            MScreenFx.vignette(fx.canvas, 0, H, WHITE, 0.4f, 0.5f);
            MScreenFx.vignette(fx.canvas, W, Float.NaN, WHITE, 0.4f, 0.5f);
            MScreenFx.vignette(null, W, H, WHITE, 0.4f, 0.5f);
            MScreenFx.stackedVignette(fx.canvas, W, H, null, new float[]{0.4f}, null);
            MScreenFx.stackedVignette(fx.canvas, W, H, new int[0], new float[0], new float[0]);
            MScreenFx.stackedVignette(null, W, H, new int[]{WHITE}, new float[]{0.4f}, null);
        });
        assertEquals(0, fx.countPainted(0, 0, W, H));
    }

    // ── Composite cap ────────────────────────────────────────────────────────

    @Test
    void capStackLeavesAQuietStackAlone() {
        assertArrayEquals(new float[]{0.1f, 0.2f}, MScreenFx.capStack(0.1f, 0.2f), EPS);
        assertArrayEquals(new float[]{0f, 0.3f}, MScreenFx.capStack(-1f, 0.3f), EPS);
        assertArrayEquals(new float[]{0f, 0.3f}, MScreenFx.capStack(Float.NaN, 0.3f), EPS);
        assertEquals(0, MScreenFx.capStack((float[]) null).length);
        assertEquals(0, MScreenFx.capStack().length);
    }

    @Test
    void capStackScalesALoudStackUnderTheCapKeepingItsProportions() {
        float[][] stacks = {{0.44f, 0.42f, 0.30f}, {0.45f, 0.45f}, {1f, 1f, 1f, 1f}, {0.4f, 0.1f}, {0.45f, 0.01f}};
        for (float[] stack : stacks) {
            float[] capped = MScreenFx.capStack(stack);
            float composite = MScreenFx.compositeAlpha(capped);
            assertTrue(composite <= MScreenFx.VIGNETTE_MAX_ALPHA + EPS, "composite " + composite);
            assertTrue(composite >= MScreenFx.VIGNETTE_MAX_ALPHA - 0.01f, "scaled no further than needed: " + composite);
            float a0 = Math.min(stack[0], MScreenFx.VIGNETTE_MAX_ALPHA), a1 = Math.min(stack[1], MScreenFx.VIGNETTE_MAX_ALPHA);
            assertEquals(a0 / a1, capped[0] / capped[1], 1.0e-3f, "one common factor");
        }
        assertEquals(0.75f, MScreenFx.compositeAlpha(0.5f, 0.5f), EPS);
        assertEquals(0f, MScreenFx.compositeAlpha((float[]) null));
    }

    @Test
    void aPaintedStackRespectsTheCapWhereAPlainPileWouldNot() {
        RasterUiFixture stacked = new RasterUiFixture(W, H);
        RasterUiFixture piled = new RasterUiFixture(W, H);
        int[] colors = {WHITE, WHITE, WHITE};
        float[] peaks = {0.44f, 0.42f, 0.30f};
        float[] inner = {0.5f, 0.5f, 0.82f};
        MScreenFx.stackedVignette(stacked.canvas, W, H, colors, peaks, inner);
        for (int i = 0; i < 3; i++) MScreenFx.vignette(piled.canvas, W, H, colors[i], peaks[i], inner[i]);

        float cap = whiteOver(MScreenFx.VIGNETTE_MAX_ALPHA);
        assertTrue(red(piled.bitmap.getColor(0, 0)) > cap + 20f, "three separate vignettes pile past the cap");
        for (int y = 0; y < H; y += 3) {
            for (int x = 0; x < W; x += 3) {
                assertTrue(red(stacked.bitmap.getColor(x, y)) <= cap + 3f, "stack capped at " + x + "," + y);
            }
        }
        assertEquals(cap, red(stacked.bitmap.getColor(0, 0)), 4f);
        assertEquals(RasterUiFixture.BACKGROUND, stacked.bitmap.getColor(W / 2, H / 2));
    }

    // ── Letterbox ────────────────────────────────────────────────────────────

    @Test
    void letterboxRectsAtRestHalfwayAndFullyIn() {
        float fraction = 0.125f;   // 15 px of 120
        float[] amounts = {0f, 0.5f, 1f};
        float[] heights = {0f, 8f, 15f};   // round(7.5) = 8
        for (int i = 0; i < amounts.length; i++) {
            float[] top = MScreenFx.letterboxTopRect(W, H, amounts[i], fraction);
            float[] bottom = MScreenFx.letterboxBottomRect(W, H, amounts[i], fraction);
            assertArrayEquals(new float[]{0f, 0f, W, heights[i]}, top, EPS);
            assertArrayEquals(new float[]{0f, H - heights[i], W, heights[i]}, bottom, EPS);
            assertEquals(W * heights[i], top[2] * top[3], EPS, "area");
            assertEquals(top[2] * top[3], bottom[2] * bottom[3], EPS, "both bars are the same size");

            RasterUiFixture fx = new RasterUiFixture(W, H);
            MScreenFx.letterbox(fx.canvas, W, H, amounts[i], fraction);
            int black = 0xFF000000;
            assertEquals((int) (2 * W * heights[i]), fx.countExactly(black, 0, 0, W, H), "painted area at " + amounts[i]);
            assertEquals((int) (W * heights[i]), fx.countExactly(black, 0, 0, W, (int) heights[i]), "top bar");
            assertEquals(0, fx.countPainted(0, (int) heights[i], W, H - (int) heights[i]), "the picture between the bars is untouched");
        }
    }

    @Test
    void letterboxClampsItsInputs() {
        assertEquals(MScreenFx.letterboxHeight(H, 1f, 0.11f), MScreenFx.letterboxHeight(H, 7f, 0.11f), 0f);
        assertEquals(0f, MScreenFx.letterboxHeight(H, -1f, 0.11f), 0f);
        assertEquals(0f, MScreenFx.letterboxHeight(H, Float.NaN, 0.11f), 0f);
        assertEquals(0f, MScreenFx.letterboxHeight(H, 1f, Float.NaN), 0f);
        assertEquals(0f, MScreenFx.letterboxHeight(0f, 1f, 0.11f), 0f);
        assertEquals(H / 2f, MScreenFx.letterboxHeight(H, 1f, 3f), 0f, "two bars can never cover more than the window");
        assertEquals(Math.round(H * MScreenFx.LETTERBOX_FRACTION), MScreenFx.letterboxHeight(H, 1f, MScreenFx.LETTERBOX_FRACTION), 0f);

        RasterUiFixture tinted = new RasterUiFixture(W, H);
        MScreenFx.letterbox(tinted.canvas, W, H, 1f, 0.125f, MStyle.PANEL_BORDER);
        assertEquals(2 * W * 15, tinted.countExactly(MStyle.PANEL_BORDER, 0, 0, W, H), "the bar colour can be supplied");
        assertDoesNotThrow(() -> MScreenFx.letterbox(null, W, H, 1f, 0.1f));
    }

    // ── Flash, scrim, ring ───────────────────────────────────────────────────

    @Test
    void flashWashesTheWholeScreenByItsAlpha() {
        RasterUiFixture none = new RasterUiFixture(W, H);
        RasterUiFixture half = new RasterUiFixture(W, H);
        RasterUiFixture full = new RasterUiFixture(W, H);
        MScreenFx.flash(none.canvas, W, H, WHITE, 0f);
        MScreenFx.flash(none.canvas, W, H, WHITE, Float.NaN);
        MScreenFx.flash(none.canvas, W, H, 0x00FFFFFF, 1f);
        MScreenFx.flash(half.canvas, W, H, WHITE, 0.5f);
        MScreenFx.flash(full.canvas, W, H, WHITE, 3f);

        assertEquals(0, none.countPainted(0, 0, W, H));
        assertEquals(W * H, full.countExactly(WHITE, 0, 0, W, H), "alpha is clamped to 1");
        assertEquals(W * H, half.countPainted(0, 0, W, H));
        assertEquals(whiteOver(0.5f), red(half.bitmap.getColor(W / 2, H / 2)), 2f);
    }

    @Test
    void scrimIsTheDeepOverlayTokenScaledByAlpha() {
        RasterUiFixture white = new RasterUiFixture(W, H);
        RasterUiFixture half = new RasterUiFixture(W, H);
        white.canvas.clear(WHITE);
        half.canvas.clear(WHITE);
        MScreenFx.scrim(white.canvas, W, H, 1f);
        MScreenFx.scrim(half.canvas, W, H, 0.5f);

        float deep = ((MStyle.OVERLAY_DEEP >>> 24) & 0xFF) / 255f;
        assertEquals(255f * (1f - deep), red(white.bitmap.getColor(10, 10)), 2f);
        assertEquals(255f * (1f - deep * 0.5f), red(half.bitmap.getColor(10, 10)), 2f);
    }

    @Test
    void theRingIsAStrokeNotADisc() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MScreenFx.expandingRing(fx.canvas, 100f, 60f, 40f, MStyle.TEXT_ACCENT, 1f, 4f);
        assertEquals(0, fx.countPainted(80, 40, 120, 80), "hollow in the middle");
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, 138, 55, 142, 65) > 10, "solid on the circle");
        assertEquals(0, fx.countPainted(0, 0, 50, H), "nothing beyond the stroke");

        RasterUiFixture wider = new RasterUiFixture(W, H);
        MScreenFx.expandingRing(wider.canvas, 100f, 60f, 50f, MStyle.TEXT_ACCENT, 0.5f, 4f);
        assertTrue(fx.diff(wider, 0, 0, W, H) > 200, "a later frame of the ring is a different picture");
        assertEquals(0, wider.countExactly(MStyle.TEXT_ACCENT, 0, 0, W, H), "and fainter");

        RasterUiFixture none = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> {
            MScreenFx.expandingRing(none.canvas, 100f, 60f, 0f, WHITE, 1f, 4f);
            MScreenFx.expandingRing(none.canvas, 100f, 60f, 40f, WHITE, 0f, 4f);
            MScreenFx.expandingRing(none.canvas, 100f, 60f, 40f, WHITE, 1f, 0f);
            MScreenFx.expandingRing(none.canvas, Float.NaN, 60f, 40f, WHITE, 1f, 4f);
            MScreenFx.expandingRing(null, 100f, 60f, 40f, WHITE, 1f, 4f);
        });
        assertEquals(0, none.countPainted(0, 0, W, H));
    }
}
