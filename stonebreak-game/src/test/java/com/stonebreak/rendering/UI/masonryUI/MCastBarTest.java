package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MCastBar}: the window is a linear map of the timeline, stays findable whether or not the
 * fill has covered it, reports {@code inWindow} on inclusive edges, and pulses only through
 * {@code update}. Everything inherited from {@link MGauge} is covered by {@link MGaugeTest}.
 */
class MCastBarTest {

    private static final int W = 256;
    private static final int H = 72;
    private static final int START = 0xFF2040C0;
    private static final int END = 0xFFC02020;
    private static final int MARK = MStyle.TEXT_ACCENT;   // the default window colour

    // No labels: bounds 20,10 200x40, bar inset by the 3px marker margin -> {20, 13, 200, 34}.
    private static MCastBar cast() {
        return new MCastBar().bounds(20, 10, 200, 40).fillColor(START);
    }

    private static RasterUiFixture paint(MGauge widget) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        widget.render(fx.ui);
        return fx;
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    void theBarKeepsRoomForTheBracketAndTheLabelRow() {
        assertArrayEquals(new float[]{20f, 13f, 200f, 34f}, cast().barRect(), 1e-4f,
                "2px overshoot + 1px backing clear above and below");
        assertArrayEquals(new float[]{20f, 31f, 200f, 16f}, cast().label("Charging").barRect(), 1e-4f,
                "an 18px label row on top");
        assertArrayEquals(cast().label("Charging").barRect(), cast().windowLabel("NOW").barRect(), 1e-4f,
                "either label reserves the same row");
    }

    @Test
    void theWindowMapsLinearlyOntoTheTimeline() {
        float total = 4f;
        float[][] windows = {{0f, 1f}, {1f, 2f}, {0.5f, 3.25f}, {3f, 4f}, {0f, 4f}, {2f, 2f}};
        for (float[] w : windows) {
            float[] span = cast().timeline(0f, total).window(w[0], w[1]).windowSpan();
            assertEquals(20f + 200f * w[0] / total, span[0], 1e-3f, "start of " + w[0] + ".." + w[1]);
            assertEquals(20f + 200f * w[1] / total, span[1], 1e-3f, "end of " + w[0] + ".." + w[1]);
        }
        // The span belongs to the timeline, not to the progress along it.
        for (float elapsed = 0f; elapsed <= 4f; elapsed += 0.5f) {
            assertArrayEquals(new float[]{70f, 120f}, cast().timeline(elapsed, total).window(1f, 2f).windowSpan(), 1e-3f);
        }
    }

    @Test
    void theWindowIsOrderedAndClampedToTheBar() {
        assertArrayEquals(new float[]{70f, 120f}, cast().timeline(0, 4).window(2f, 1f).windowSpan(), 1e-3f,
                "either order");
        assertArrayEquals(new float[]{170f, 220f}, cast().timeline(0, 4).window(3f, 9f).windowSpan(), 1e-3f);
        assertArrayEquals(new float[]{20f, 45f}, cast().timeline(0, 4).window(-2f, 0.5f).windowSpan(), 1e-3f);
        assertArrayEquals(new float[]{20f, 20f}, cast().timeline(0, 4).windowSpan(), 1e-3f, "no window: empty span");
        assertArrayEquals(new float[]{20f, 20f}, cast().timeline(0, 4).window(1, 2).clearWindow().windowSpan(), 1e-3f);
    }

    @Test
    void theSpanFollowsTheCaptionColumn() {
        MCastBar bar = cast().captionWidth(40).timeline(0, 4).window(1, 2);
        assertArrayEquals(new float[]{60f + 160f * 0.25f, 60f + 160f * 0.5f}, bar.windowSpan(), 1e-3f);
        assertArrayEquals(bar.windowSpan(), bar.windowSpan(new RasterUiFixture(W, H).ui), 1e-3f);
    }

    @Test
    void aVeryShortWindowIsStillDrawnAtAFindableWidth() {
        MCastBar sliver = cast().timeline(0, 4).window(2f, 2.001f);
        float[] span = sliver.windowSpan();
        float[] marker = sliver.markerSpan();

        assertTrue(span[1] - span[0] < 0.1f, "the true span stays honest");
        assertEquals(4f, marker[1] - marker[0], 1e-3f);
        assertEquals(span[0], marker[0], 1e-3f, "and the marker starts where the window does");

        float[] atTheEnd = cast().timeline(0, 4).window(4f, 4f).markerSpan();
        assertArrayEquals(new float[]{216f, 220f}, atTheEnd, 1e-3f, "kept inside the bar");

        assertArrayEquals(new float[]{70f, 120f}, cast().timeline(0, 4).window(1, 2).markerSpan(), 1e-3f,
                "a roomy window is drawn as it is");
    }

    // ── inWindow ─────────────────────────────────────────────────────────────

    @Test
    void inWindowIncludesBothEdges() {
        MCastBar bar = cast().window(1f, 2f);
        float[] inside = {1f, 1.0001f, 1.5f, 2f};
        float[] outside = {0f, 0.9999f, 2.0001f, 4f, -1f};
        for (float t : inside) assertTrue(bar.timeline(t, 4f).inWindow(), "t=" + t);
        for (float t : outside) assertFalse(bar.timeline(t, 4f).inWindow(), "t=" + t);
    }

    @Test
    void inWindowNeedsAWindowATimelineAndAnUninterruptedRun() {
        assertFalse(cast().timeline(1.5f, 4f).inWindow(), "no window set");
        assertFalse(cast().window(1f, 2f).inWindow(), "no timeline yet");
        assertFalse(cast().window(0f, 2f).timeline(0f, 0f).inWindow(), "zero-length timeline");
        assertFalse(cast().window(1f, 2f).timeline(1.5f, 4f).interrupted(true).inWindow());
        assertFalse(cast().window(1f, 2f).timeline(1.5f, 4f).clearWindow().inWindow());
        assertTrue(cast().window(1f, 2f).timeline(1.5f, 4f).interrupted(true).interrupted(false).inWindow());
    }

    @Test
    void theTimelineDrivesTheFill() {
        assertEquals(0.25f, cast().timeline(1f, 4f).displayedFraction(), 1e-6f);
        assertEquals(1f, cast().timeline(9f, 4f).displayedFraction(), 1e-6f);
        assertEquals(0f, cast().timeline(-1f, 4f).displayedFraction(), 1e-6f);
        assertEquals(1.5f, cast().timeline(1.5f, 4f).elapsed(), 1e-6f);
        assertEquals(4f, cast().timeline(1.5f, 4f).total(), 1e-6f);
    }

    // ── Visibility ───────────────────────────────────────────────────────────

    @Test
    void theWindowIsVisibleBeforeTheFillReachesItAndOnceItIsCovered() {
        float[] moments = {0f, 0.5f, 1.5f, 4f};   // untouched, approaching, inside, fully covered
        for (float elapsed : moments) {
            RasterUiFixture marked = paint(cast().timeline(elapsed, 4f).window(1f, 2f));
            RasterUiFixture plain = paint(cast().timeline(elapsed, 4f));

            assertTrue(marked.diff(plain, 73, 16, 117, 44) > 1000, "t=" + elapsed + ": the zone is tinted");
            assertTrue(marked.countExactly(MARK, 68, 10, 124, 50) > 150, "t=" + elapsed + ": opaque bracket");
            assertTrue(marked.countExactly(MARK, 68, 10, 124, 13) > 40, "t=" + elapsed + ": it overshoots the bar's top");
            assertEquals(0, marked.diff(plain, 130, 0, W, H), "t=" + elapsed + ": nothing changes away from it");
        }
    }

    @Test
    void aCoveredWindowStillStandsOutFromTheFillAroundIt() {
        RasterUiFixture fx = paint(cast().timeline(4f, 4f).window(1f, 2f));
        assertNotEquals(fx.bitmap.getColor(150, 38), fx.bitmap.getColor(95, 38), "zone vs. plain fill");
        assertEquals(START, fx.bitmap.getColor(150, 38));
    }

    @Test
    void theMarkerNeverPaintsOutsideTheBounds() {
        float[][] windows = {{0f, 0.5f}, {3.5f, 4f}, {0f, 4f}, {4f, 4f}};
        for (float[] w : windows) {
            RasterUiFixture fx = paint(cast().timeline(2f, 4f).window(w[0], w[1]).pulse(true));
            int inside = fx.countPainted(20, 10, 220, 50);
            assertEquals(inside, fx.countPainted(0, 0, W, H), "window " + w[0] + ".." + w[1]);
        }
    }

    @Test
    void beingInsideTheWindowBrightensIt() {
        RasterUiFixture before = paint(cast().timeline(0.9f, 4f).window(1f, 2f));
        RasterUiFixture during = paint(cast().timeline(1.0f, 4f).window(1f, 2f));
        // Ahead of the fill in both frames, so only the window's own emphasis can differ.
        assertTrue(before.diff(during, 75, 16, 117, 44) > 1000);
    }

    @Test
    void windowColorRecoloursTheMarker() {
        int teal = 0xFF20C0B0;
        RasterUiFixture fx = paint(cast().timeline(0f, 4f).window(1f, 2f).windowColor(teal));
        assertTrue(fx.countExactly(teal, 68, 10, 124, 50) > 150);
        assertEquals(0, fx.countExactly(MARK, 0, 0, W, H));
    }

    // ── Pulse ────────────────────────────────────────────────────────────────

    @Test
    void thePulseAdvancesOnlyThroughUpdate() {
        MCastBar bar = cast().timeline(0f, 4f).window(1f, 2f);
        assertEquals(0f, bar.pulseAmount(), "off by default");
        bar.update(1f);
        assertEquals(0f, bar.pulseAmount());

        bar.pulse(true);
        assertEquals(0.5f, bar.pulseAmount(), 1e-6f, "starts mid-swing");
        RasterUiFixture mid = paint(bar);
        assertEquals(0.5f, bar.pulseAmount(), 1e-6f, "painting does not move it");

        bar.update((float) (Math.PI / 24.0));   // a quarter turn at 12 rad/s
        assertEquals(1f, bar.pulseAmount(), 1e-4f);
        assertTrue(mid.diff(paint(bar), 73, 16, 117, 44) > 1000, "the zone brightens with the pulse");

        bar.update((float) (Math.PI / 12.0));
        assertEquals(0f, bar.pulseAmount(), 1e-4f);

        bar.update(12345f);
        assertTrue(bar.pulseAmount() >= 0f && bar.pulseAmount() <= 1f, "a huge step stays in range");
        bar.update(Float.NaN);
        bar.update(-3f);
        assertTrue(Float.isFinite(bar.pulseAmount()));

        bar.pulse(false);
        assertEquals(0f, bar.pulseAmount());
        assertEquals(0, paint(bar).diff(paint(cast().timeline(0f, 4f).window(1f, 2f)), 0, 0, W, H));
    }

    @Test
    void snapToRestartsThePulse() {
        MCastBar bar = cast().pulse(true);
        bar.update(0.1f);
        assertNotEquals(0.5f, bar.pulseAmount());
        bar.snapTo(0f);
        assertEquals(0.5f, bar.pulseAmount(), 1e-6f);
    }

    // ── Fill colour + interrupted ────────────────────────────────────────────

    @Test
    void theFillRampShiftsAlongTheProgress() {
        RasterUiFixture half = paint(cast().fillRamp(START, END).timeline(2f, 4f));
        RasterUiFixture done = paint(cast().fillRamp(START, END).timeline(4f, 4f));

        assertEquals(96 * 18, half.countExactly(MColor.lerp(START, END, 0.5f), 22, 26, 118, 44));
        assertEquals(196 * 18, done.countExactly(END, 22, 26, 218, 44));
        assertTrue(paint(cast().fillRamp(START, END).fillColor(START).timeline(4f, 4f))
                .countExactly(START, 22, 26, 218, 44) > 3000, "a later fillColor replaces the ramp");
    }

    @Test
    void anInterruptedBarGoesGrey() {
        RasterUiFixture live = paint(cast().fillRamp(START, END).timeline(2f, 4f).window(3f, 3.5f));
        RasterUiFixture cut = paint(cast().fillRamp(START, END).timeline(2f, 4f).window(3f, 3.5f).interrupted(true));

        assertEquals(96 * 18, cut.countExactly(MStyle.TEXT_DISABLED, 22, 26, 118, 44), "grey fill");
        assertEquals(0, cut.countExactly(MARK, 0, 0, W, H), "the marker loses its colour");
        assertTrue(cut.countExactly(MStyle.TEXT_DISABLED, 168, 10, 198, 50) > 100, "but keeps its place");
        assertTrue(live.diff(cut, 20, 10, 220, 50) > 1500);
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    void theLabelAndTheWindowCaptionSitAboveTheBar() {
        RasterUiFixture none = paint(cast().windowLabel("").label("").timeline(1f, 4f).window(3f, 3.5f));
        RasterUiFixture named = paint(cast().label("Glacial Lance").timeline(1f, 4f).window(3f, 3.5f));
        RasterUiFixture both = paint(cast().label("Glacial Lance").windowLabel("NOW").timeline(1f, 4f).window(3f, 3.5f));

        assertTrue(named.countPainted(20, 10, 160, 27) > 60, "the name draws in the label row");
        assertTrue(none.diff(named, 20, 10, 220, 50) > 200);
        assertEquals(0, named.countExactly(MARK, 20, 10, 222, 27), "no caption yet");
        // Window 3..3.5 of 4 spans x 170..195: the caption centres on it, clear of the name.
        assertTrue(both.countExactly(MARK, 150, 10, 222, 27) > 10, "the caption takes the window colour");
        assertEquals(0, both.countExactly(MARK, 20, 10, 150, 27), "and sits over the window, not at the left");
    }

    @Test
    void aWindowCaptionWithoutAWindowIsNotDrawn() {
        RasterUiFixture fx = paint(cast().windowLabel("NOW").timeline(1f, 4f));
        assertEquals(0, fx.countPainted(20, 10, 222, 27), "the row is reserved but empty");
        assertTrue(fx.countPainted(20, 31, 220, 47) > 3000, "the bar is still there");
    }

    @Test
    void theCaptionIsKeptInsideTheBarAtEitherEnd() {
        RasterUiFixture left = paint(cast().windowLabel("REACT").timeline(0f, 4f).window(0f, 0.1f));
        RasterUiFixture right = paint(cast().windowLabel("REACT").timeline(0f, 4f).window(3.9f, 4f));

        assertTrue(left.countExactly(MARK, 20, 10, 80, 27) > 10);
        assertEquals(0, left.countPainted(0, 10, 20, 27), "never left of the bar");
        assertTrue(right.countExactly(MARK, 160, 10, 220, 27) > 10);
        assertEquals(0, right.countPainted(223, 10, W, 27), "never right of it (2px text shadow aside)");
    }

    // ── Scale ────────────────────────────────────────────────────────────────

    @Test
    void scaleTwoDoublesEveryMetric() {
        MCastBar two = new MCastBar().bounds(20, 4, 200, 64).scale(2f).timeline(0f, 4f).window(2f, 2.001f);
        assertArrayEquals(new float[]{20f, 10f, 200f, 52f}, two.barRect(), 1e-4f, "6px marker margin");
        assertEquals(8f, two.markerSpan()[1] - two.markerSpan()[0], 1e-3f, "8px minimum marker");

        MCastBar labelled = new MCastBar().bounds(20, 0, 200, 72).scale(2f).label("Charging");
        assertArrayEquals(new float[]{20f, 42f, 200f, 24f}, labelled.barRect(), 1e-4f, "36px label row + 6px margin");

        MCastBar one = new MCastBar().bounds(20, 4, 200, 64).scale(1f).timeline(0f, 4f).window(1f, 2f);
        MCastBar big = new MCastBar().bounds(20, 4, 200, 64).scale(2f).timeline(0f, 4f).window(1f, 2f);
        int thin = paint(one).countExactly(MARK, 68, 0, 124, H);
        int thick = paint(big).countExactly(MARK, 68, 0, 124, H);
        assertTrue(thick > thin * 1.6f, "a thicker bracket: " + thin + " -> " + thick);
    }

    // ── Degenerate inputs ────────────────────────────────────────────────────

    @Test
    void degenerateTimelinesAreEmptyAndWindowless() {
        float[][] timelines = {{1f, 0f}, {1f, -4f}, {1f, Float.NaN}, {Float.NaN, Float.NaN},
                {1f, Float.POSITIVE_INFINITY}};
        for (float[] t : timelines) {
            MCastBar bar = cast().window(0f, 2f).windowLabel("NOW").label("X").pulse(true).timeline(t[0], t[1]);
            assertEquals(0f, bar.displayedFraction(), "timeline " + t[0] + "/" + t[1]);
            assertFalse(bar.hasWindow());
            assertFalse(bar.inWindow());
            assertArrayEquals(new float[]{20f, 20f}, bar.windowSpan(), 1e-4f);
            RasterUiFixture fx = new RasterUiFixture(W, H);
            assertDoesNotThrow(() -> bar.render(fx.ui));
            assertEquals(0, fx.countExactly(MARK, 0, 0, W, H), "no marker without a timeline");
        }
        assertEquals(0f, cast().timeline(Float.NaN, 4f).displayedFraction(), "NaN elapsed reads as the start");
    }

    @Test
    void aNonFiniteWindowIsNoWindow() {
        assertFalse(cast().timeline(1f, 4f).window(Float.NaN, 2f).hasWindow());
        assertFalse(cast().timeline(1f, 4f).window(1f, Float.POSITIVE_INFINITY).hasWindow());
        assertFalse(cast().timeline(1f, 4f).window(1f, 2f).window(Float.NaN, Float.NaN).hasWindow(),
                "and it replaces an earlier one");
    }

    @Test
    void degenerateBoundsPaintNothingAndNeverThrow() {
        float[][] bounds = {{20, 10, 0, 40}, {20, 10, 200, 0}, {20, 10, -5, -5}, {20, 10, 200, 4},
                {Float.NaN, 10, 200, 40}, {20, 10, 200, Float.NaN}};
        for (float[] b : bounds) {
            MCastBar bar = new MCastBar().bounds(b[0], b[1], b[2], b[3]).label(null).windowLabel(null)
                    .label("Charging").windowLabel("NOW").timeline(1.5f, 4f).window(1f, 2f).pulse(true);
            RasterUiFixture fx = new RasterUiFixture(W, H);
            assertDoesNotThrow(() -> bar.render(fx.ui));
            assertDoesNotThrow(() -> bar.render(null));
            assertEquals(0f, bar.barRect()[2], "no bar");
            float[] span = bar.windowSpan();
            assertEquals(0f, span[1] - span[0], "so no span");
            assertEquals(0, fx.countExactly(MARK, 0, 0, W, H));
        }
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    void twoBarsFedTheSameCallsPaintIdentically() {
        MCastBar a = scripted();
        MCastBar b = scripted();
        float elapsed = 0f;
        float[] steps = {0.016f, 0.4f, 0.033f, 0.75f, 0.2f, 2f, 0.05f};
        for (int i = 0; i < steps.length; i++) {
            elapsed += steps[i];
            for (MCastBar bar : new MCastBar[]{a, b}) {
                bar.timeline(elapsed, 3f).pulse(i >= 2);
                if (i == 3) bar.pulseFlash();
                bar.update(steps[i]);
            }
            assertEquals(a.pulseAmount(), b.pulseAmount(), "step " + i);
            assertEquals(a.inWindow(), b.inWindow(), "step " + i);
            assertEquals(0, paint(a).diff(paint(b), 0, 0, W, H), "step " + i);
        }
    }

    private static MCastBar scripted() {
        return new MCastBar().bounds(12, 6, 230, 56).caption("CAST").captionWidth(44).label("Glacial Lance")
                .windowLabel("NOW").fillRamp(START, END).window(1.6f, 2.1f).valueAsPercent()
                .flash(0xFFFFFFFF).timeline(0f, 3f);
    }
}
