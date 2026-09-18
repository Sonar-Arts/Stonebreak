package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MGauge}: the fill/track/outline geometry, the hold-then-ease trail timeline, the flash and
 * full-gauge blink clocks, scaling, degenerate inputs and determinism, over the headless raster rig.
 * The bar is aliased rects, so interior pixels can be asserted to the exact colour.
 */
class MGaugeTest {

    private static final int W = 256;
    private static final int H = 64;
    private static final int FILL = 0xFF3366CC;
    private static final int GLOW = 0xFFFFEE88;

    // Bare bar used by most tests: x 20..220, y 20..36.
    private static MGauge bar() {
        return new MGauge().bounds(20, 20, 200, 16).fillColor(FILL);
    }

    private static RasterUiFixture paint(MGauge gauge) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        gauge.render(fx.ui);
        return fx;
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    void theFillSpansExactlyTheFractionOfTheTrack() {
        RasterUiFixture fx = paint(bar().fraction(0.5f));

        // Below the top sheen (30% of 16px) and inside the 1px outline the fill is the pure colour.
        assertEquals(96 * 8, fx.countExactly(FILL, 22, 26, 118, 34), "left half is fill");
        assertEquals(0, fx.countExactly(FILL, 121, 20, 220, 36), "nothing right of the fraction");
        assertEquals(96 * 12, fx.countExactly(MStyle.GAUGE_TRACK, 122, 22, 218, 34), "right half is bare track");
        assertEquals(200, fx.countExactly(MStyle.GAUGE_OUTLINE, 20, 20, 220, 21), "1px outline along the top");
        assertEquals(0, fx.countPainted(0, 0, W, 19), "nothing above the bounds");
    }

    @Test
    void theFillCarriesATopSheen() {
        RasterUiFixture fx = paint(bar().fraction(1f));

        assertEquals(0, fx.countExactly(FILL, 22, 21, 218, 24), "the top band is lightened");
        assertEquals(196 * 8, fx.countExactly(FILL, 22, 26, 218, 34));
    }

    @Test
    void valueAndFractionAgree() {
        assertEquals(0.25f, new MGauge().value(45f, 180f).displayedFraction(), 1e-6f);
        assertEquals(1f, new MGauge().value(500f, 180f).displayedFraction(), 1e-6f, "clamped high");
        assertEquals(0f, new MGauge().value(-5f, 180f).displayedFraction(), 1e-6f, "clamped low");
        assertEquals(0.75f, new MGauge().fraction(0.75f).displayedFraction(), 1e-6f);
    }

    @Test
    void pillAndRectAreDifferentShapes() {
        RasterUiFixture rect = paint(bar().fraction(0.6f));
        RasterUiFixture pill = paint(bar().fraction(0.6f).shape(MGauge.Shape.PILL));

        assertTrue(rect.diff(pill, 20, 20, 220, 36) > 20);
        assertEquals(MStyle.GAUGE_OUTLINE, rect.bitmap.getColor(20, 20), "a rect's corner is outline");
        assertEquals(RasterUiFixture.BACKGROUND, pill.bitmap.getColor(20, 20), "a pill's corner is cut away");
    }

    @Test
    void theCaptionColumnIsReservedSoStackedGaugesAlign() {
        MGauge shortCaption = bar().caption("HP").captionWidth(48);
        MGauge longCaption = bar().caption("STAMINA").captionWidth(48);

        assertArrayEquals(new float[]{68f, 20f, 152f, 16f}, shortCaption.barRect(), 1e-4f);
        assertArrayEquals(shortCaption.barRect(), longCaption.barRect(), 1e-4f);

        RasterUiFixture fx = paint(shortCaption.fraction(0.5f));
        assertTrue(fx.countPainted(20, 20, 66, 36) > 15, "the caption draws in its column");
        assertEquals(0, fx.countExactly(MStyle.GAUGE_TRACK, 20, 20, 66, 36), "the bar keeps out of it");
        assertArrayEquals(shortCaption.barRect(), shortCaption.barRect(fx.ui), 1e-4f,
                "explicit widths need no font to resolve");
    }

    @Test
    void anUnsizedCaptionColumnIsMeasured() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MGauge gauge = bar().caption("Stamina");
        float[] measured = gauge.barRect(fx.ui);

        assertTrue(measured[0] > 40f, "the bar starts after the measured caption");
        assertEquals(220f, measured[0] + measured[2], 1e-3f, "and still ends at the right edge");
    }

    @Test
    void barHeightCapsAndCentresTheBar() {
        assertArrayEquals(new float[]{20f, 24f, 200f, 8f}, bar().barHeight(8).barRect(), 1e-4f);
        assertArrayEquals(new float[]{20f, 20f, 200f, 16f}, bar().barHeight(40).barRect(), 1e-4f,
                "never taller than the bounds");
    }

    // ── Value text ───────────────────────────────────────────────────────────

    @Test
    void valueTextFormats() {
        assertEquals("", bar().value(142, 180).resolvedValueText(), "none by default");
        assertEquals("142/180", bar().value(142, 180).valueAsFraction().resolvedValueText());
        assertEquals("0/180", bar().value(-3, 180).valueAsFraction().resolvedValueText());
        assertEquals("79%", bar().value(142, 180).valueAsPercent().resolvedValueText());
        assertEquals("50/100", bar().fraction(0.5f).valueAsFraction().resolvedValueText());
        assertEquals("READY", bar().valueText("READY").resolvedValueText());
        assertEquals("", bar().valueAsPercent().valueText(null).resolvedValueText(), "null removes it");
    }

    @Test
    void valueTextDrawsOverTheBarOrBesideIt() {
        RasterUiFixture bare = paint(bar().value(142, 180));
        RasterUiFixture over = paint(bar().value(142, 180).valueAsFraction());
        RasterUiFixture centred = paint(bar().value(142, 180).valueAsFraction()
                .valuePlacement(MGauge.ValuePlacement.OVER_CENTER));
        MGauge besideGauge = bar().value(142, 180).valueAsFraction()
                .valuePlacement(MGauge.ValuePlacement.BESIDE).valueWidth(60);
        RasterUiFixture beside = paint(besideGauge);

        assertTrue(bare.diff(over, 150, 20, 220, 36) > 20, "right-aligned over the bar");
        assertTrue(over.diff(centred, 20, 20, 220, 36) > 20, "centre placement moves it");
        assertArrayEquals(new float[]{20f, 20f, 140f, 16f}, besideGauge.barRect(), 1e-4f, "beside reserves a column");
        assertTrue(beside.countPainted(162, 20, 221, 38) > 20, "and the text lands in it");
        assertEquals(0, beside.countExactly(MStyle.GAUGE_TRACK, 162, 20, 222, 36));
    }

    // ── Colour ───────────────────────────────────────────────────────────────

    @Test
    void theRampColoursTheFillByLevel() {
        RasterUiFixture healthy = paint(bar().vitalRamp().fraction(1f));
        RasterUiFixture critical = paint(bar().vitalRamp().fraction(0.1f));
        RasterUiFixture custom = paint(bar().ramp(0xFF110000, 0xFF001100, 0xFF000011).fraction(1f));

        assertTrue(healthy.countExactly(MStyle.VITAL_OK, 22, 26, 218, 34) > 1000);
        assertTrue(critical.countExactly(MStyle.VITAL_CRIT, 22, 26, 39, 34) > 100);
        assertEquals(0, critical.countExactly(MStyle.VITAL_OK, 20, 20, 220, 36));
        assertTrue(custom.countExactly(0xFF000011, 22, 26, 218, 34) > 1000);
    }

    @Test
    void fillColorReplacesARamp() {
        RasterUiFixture fx = paint(bar().vitalRamp().fillColor(FILL).fraction(1f));
        assertTrue(fx.countExactly(FILL, 22, 26, 218, 34) > 1000);
    }

    // ── Ghost trail ──────────────────────────────────────────────────────────

    @Test
    void theTrailHoldsThenEasesDown() {
        MGauge g = bar().ghost(true).fraction(1f);
        assertEquals(-1f, g.ghostFraction(), "the first binding never trails");

        g.fraction(0.4f);
        assertEquals(1f, g.ghostFraction(), 1e-6f, "a drop leaves the trail at the old level");
        assertEquals(0.4f, g.displayedFraction(), 1e-6f, "while the fill moves at once");

        g.update(0.2f);
        assertEquals(1f, g.ghostFraction(), 1e-6f, "t=0.20: holding");
        g.update(0.15f);
        assertEquals(1f, g.ghostFraction(), 1e-4f, "t=0.35: the hold is just ending");
        g.update(0.25f);
        // Halfway through the 0.5 s ease, EaseOutCubic(0.5) = 0.875 of the way from 1.0 to 0.4.
        assertEquals(1f + (0.4f - 1f) * 0.875f, g.ghostFraction(), 2e-3f, "t=0.60: mid-ease");
        g.update(0.125f);
        float late = g.ghostFraction();
        assertTrue(late > 0.4f && late < 0.475f, "t=0.725: still easing, monotonically: " + late);
        g.update(0.125f);
        assertEquals(-1f, g.ghostFraction(), "t=0.85: the trail has caught up and is gone");
    }

    @Test
    void customTrailTimingIsHonoured() {
        MGauge g = bar().ghost(true).ghostTiming(1f, 2f).fraction(1f).fraction(0f);
        g.update(0.9f);
        assertEquals(1f, g.ghostFraction(), 1e-6f, "still inside the 1 s hold");
        g.update(0.1f + 1f);
        assertEquals(1f - 0.875f, g.ghostFraction(), 2e-3f, "halfway through the 2 s ease");
        g.update(1.01f);
        assertEquals(-1f, g.ghostFraction());
    }

    @Test
    void aSecondDropDuringTheHoldKeepsTheHeadAndRestartsTheHold() {
        MGauge g = bar().ghost(true).fraction(1f).fraction(0.6f);
        g.update(0.2f);
        g.fraction(0.3f);
        assertEquals(1f, g.ghostFraction(), 1e-6f, "the head stays where the first drop left it");

        g.update(0.3f);
        assertEquals(1f, g.ghostFraction(), 1e-6f, "0.5 s after the first drop it still holds: the hold restarted");
        g.update(0.05f);
        g.update(0.25f);
        assertEquals(1f + (0.3f - 1f) * 0.875f, g.ghostFraction(), 2e-3f, "and eases to the newest level");
    }

    @Test
    void aDropDuringTheEaseRestartsFromTheTrailsCurrentHead() {
        MGauge g = bar().ghost(true).fraction(1f).fraction(0.5f);
        g.update(0.35f);
        g.update(0.25f);
        float head = g.ghostFraction();
        assertTrue(head > 0.5f && head < 1f);

        g.fraction(0.2f);
        assertEquals(head, g.ghostFraction(), 1e-6f, "the head does not jump back up");
        g.update(0.3f);
        assertEquals(head, g.ghostFraction(), 1e-6f, "and it holds again");
    }

    @Test
    void aRiseSnapsTheTrailAway() {
        MGauge g = bar().ghost(true).fraction(1f).fraction(0.4f);
        g.update(0.1f);
        g.fraction(0.5f);
        assertEquals(-1f, g.ghostFraction(), "it only ever trails losses");

        g.update(1f);
        assertEquals(-1f, g.ghostFraction());
        g.fraction(0.5f);
        assertEquals(-1f, g.ghostFraction(), "an unchanged level starts nothing");
    }

    @Test
    void aLargeSingleStepFinishesTheTrail() {
        MGauge g = bar().ghost(true).fraction(1f).fraction(0.4f);
        g.update(10f);
        assertEquals(-1f, g.ghostFraction());

        MGauge carried = bar().ghost(true).fraction(1f).fraction(0.4f);
        carried.update(0.6f);
        assertEquals(1f + (0.4f - 1f) * 0.875f, carried.ghostFraction(), 2e-3f,
                "one 0.6 s step lands where 0.35 + 0.25 does: the remainder carries into the ease");
    }

    @Test
    void theTrailIsOffUnlessAskedFor() {
        MGauge g = bar().fraction(1f).fraction(0.4f);
        assertEquals(-1f, g.ghostFraction());

        MGauge disabledLater = bar().ghost(true).fraction(1f).fraction(0.4f).ghost(false);
        assertEquals(-1f, disabledLater.ghostFraction(), "turning it off drops a running trail");
    }

    @Test
    void theTrailPaintsBetweenTheFillAndItsHead() {
        MGauge g = bar().ghost(true).fraction(1f).fraction(0.4f);
        RasterUiFixture trailing = paint(g);
        assertEquals(116 * 12, trailing.countExactly(MStyle.GAUGE_GHOST, 102, 22, 218, 34),
                "pale trail from the fill's end to the old level");
        assertEquals(0, trailing.countExactly(MStyle.GAUGE_GHOST, 20, 20, 99, 36), "never over the fill");

        g.update(5f);
        RasterUiFixture settled = paint(g);
        assertEquals(0, settled.countExactly(MStyle.GAUGE_GHOST, 0, 0, W, H));
        assertEquals(0, settled.diff(paint(bar().fraction(0.4f)), 0, 0, W, H), "back to a plain gauge");
    }

    @Test
    void thePillTrailIsVisibleToo() {
        MGauge g = bar().shape(MGauge.Shape.PILL).ghost(true).fraction(0.9f).fraction(0.3f);
        assertTrue(paint(g).countExactly(MStyle.GAUGE_GHOST, 90, 22, 190, 34) > 1000);
    }

    // ── Flash ────────────────────────────────────────────────────────────────

    @Test
    void theFlashDecaysOverItsDuration() {
        MGauge g = bar().fraction(0.5f).flash(0xFFFFFFFF);
        assertEquals(0f, g.flashStrength(), "idle until pulsed");

        g.pulseFlash();
        assertEquals(1f, g.flashStrength(), 1e-6f);
        g.update(0.15f);
        assertEquals(0.5f, g.flashStrength(), 1e-4f, "halfway through 0.3 s");
        g.update(0.15f);
        assertEquals(0f, g.flashStrength(), 1e-6f);
        g.update(100f);
        assertEquals(0f, g.flashStrength());

        g.flashSeconds(1f).pulseFlash();
        g.update(0.75f);
        assertEquals(0.25f, g.flashStrength(), 1e-4f, "a custom duration");
    }

    @Test
    void theFlashCoversTheWholeBarThenLeavesNoTrace() {
        MGauge g = bar().fraction(0.5f).flash(0xFFFF2020).pulseFlash();
        RasterUiFixture plain = paint(bar().fraction(0.5f));
        RasterUiFixture flashing = paint(g);

        assertTrue(plain.diff(flashing, 22, 22, 118, 34) > 1000, "over the fill");
        assertTrue(plain.diff(flashing, 122, 22, 218, 34) > 1000, "and over the empty track");

        g.update(0.1f);
        RasterUiFixture fading = paint(g);
        assertTrue(flashing.diff(fading, 22, 22, 218, 34) > 1000, "it fades frame to frame");

        g.update(0.3f);
        assertEquals(0, plain.diff(paint(g), 0, 0, W, H));
    }

    // ── Full glow + shimmer ──────────────────────────────────────────────────

    @Test
    void theGlowOnlyShowsAtOneHundredPercent() {
        MGauge almost = bar().fullGlow(GLOW).fraction(0.99f);
        MGauge full = bar().fullGlow(GLOW).fraction(1f);

        assertEquals(0f, almost.glowStrength());
        assertFalse(almost.isFull());
        assertEquals(0, paint(almost).diff(paint(bar().fraction(0.99f)), 0, 0, W, H),
                "below full the glow colour changes nothing");

        assertTrue(full.isFull());
        assertEquals(1f, full.glowStrength(), 1e-6f, "it starts at its brightest");
        RasterUiFixture glowing = paint(full);
        assertTrue(glowing.diff(paint(bar().fraction(1f)), 0, 0, W, H) > 200);
        assertTrue(glowing.countPainted(20, 19, 220, 20) > 100, "an outline just outside the bar");
        assertEquals(0, paint(bar().fraction(1f)).countPainted(20, 19, 220, 20));
    }

    @Test
    void theGlowBlinksThroughUpdate() {
        MGauge g = bar().fullGlow(GLOW).fraction(1f);
        RasterUiFixture bright = paint(g);

        g.update((float) (Math.PI / 5.0));   // half a blink at 5 rad/s
        assertEquals(0.4f, g.glowStrength(), 1e-3f, "the dim end of the blink");
        assertTrue(bright.diff(paint(g), 0, 0, W, H) > 200);

        g.update(1000f);
        float strength = g.glowStrength();
        assertTrue(strength >= 0.4f - 1e-4f && strength <= 1f + 1e-4f, "a huge step stays in range: " + strength);

        g.fraction(0.5f);
        g.update(0.1f);
        g.fraction(1f);
        assertEquals(1f, g.glowStrength(), 1e-6f, "refilling restarts the blink at its brightest");
    }

    @Test
    void theShimmerSweepsAcrossTheFill() {
        MGauge g = bar().fraction(1f).shimmer(true);
        assertEquals(-1f, bar().shimmerPhase(), "off by default");
        assertEquals(0f, g.shimmerPhase(), 1e-6f);

        g.update(0.5f);
        assertEquals(0.35f, g.shimmerPhase(), 1e-4f, "0.7 sweeps per second");
        RasterUiFixture early = paint(g);
        g.update(0.5f);
        RasterUiFixture later = paint(g);

        assertTrue(early.diff(paint(bar().fraction(1f)), 22, 22, 218, 34) > 50, "the band is visible");
        assertTrue(early.diff(later, 22, 22, 218, 34) > 50, "and it moves");

        g.update(100f);
        assertTrue(g.shimmerPhase() >= 0f && g.shimmerPhase() < 1f, "the phase wraps");
        assertEquals(0, paint(g.shimmer(false)).diff(paint(bar().fraction(1f)), 0, 0, W, H));
    }

    @Test
    void theShimmerStaysInsideTheFill() {
        MGauge g = bar().fraction(0.5f).shimmer(true);
        RasterUiFixture plain = paint(bar().fraction(0.5f));
        for (int i = 0; i < 40; i++) {
            g.update(0.05f);
            assertEquals(0, plain.diff(paint(g), 120, 20, 220, 36), "step " + i + ": never over the empty track");
        }
    }

    // ── Rebinding ────────────────────────────────────────────────────────────

    @Test
    void snapToAndResetDropEveryEffect() {
        MGauge g = bar().ghost(true).flash(0xFFFFFFFF).fraction(1f).fraction(0.4f).pulseFlash();
        g.snapTo(0.2f);
        assertEquals(0.2f, g.displayedFraction(), 1e-6f);
        assertEquals(-1f, g.ghostFraction());
        assertEquals(0f, g.flashStrength());

        g.snapTo(90f, 180f);
        assertEquals(0.5f, g.displayedFraction(), 1e-6f);
        assertEquals(-1f, g.ghostFraction(), "a snap down is not a drop");

        g.fraction(0.1f);
        assertEquals(0.5f, g.ghostFraction(), 1e-6f, "later drops trail again");

        g.reset();
        assertEquals(0f, g.displayedFraction());
        assertEquals(-1f, g.ghostFraction());
        g.fraction(0.8f);
        g.reset().fraction(0.1f);
        assertEquals(-1f, g.ghostFraction(), "after a reset the first value is a binding, not a drop");
    }

    // ── Scale ────────────────────────────────────────────────────────────────

    @Test
    void scaleTwoDoublesEveryMetric() {
        MGauge one = new MGauge().bounds(10, 8, 230, 40).caption("HP").captionWidth(30).barHeight(8)
                .valueAsPercent().valuePlacement(MGauge.ValuePlacement.BESIDE).valueWidth(25).scale(1f);
        MGauge two = new MGauge().bounds(10, 8, 230, 40).caption("HP").captionWidth(30).barHeight(8)
                .valueAsPercent().valuePlacement(MGauge.ValuePlacement.BESIDE).valueWidth(25).scale(2f);

        assertArrayEquals(new float[]{40f, 24f, 175f, 8f}, one.barRect(), 1e-4f);
        assertArrayEquals(new float[]{70f, 20f, 120f, 16f}, two.barRect(), 1e-4f);

        int small = paint(one.fraction(0.5f)).countPainted(10, 8, 40, 48);
        int large = paint(two.fraction(0.5f)).countPainted(10, 8, 70, 48);
        assertTrue(large > small * 2, "the caption text scales too: " + small + " -> " + large);
    }

    @Test
    void theOutlineScalesWithTheGauge() {
        RasterUiFixture fx = paint(bar().scale(2f).fraction(0f));
        assertEquals(200 * 2, fx.countExactly(MStyle.GAUGE_OUTLINE, 20, 20, 220, 22), "2px at scale 2");
    }

    // ── Degenerate inputs ────────────────────────────────────────────────────

    @Test
    void degenerateValuesReadAsEmpty() {
        assertEquals(0f, bar().value(10f, 0f).displayedFraction(), "max 0");
        assertEquals(0f, bar().value(10f, -4f).displayedFraction(), "negative max");
        assertEquals(0f, bar().value(Float.NaN, 10f).displayedFraction(), "NaN value");
        assertEquals(0f, bar().value(5f, Float.NaN).displayedFraction(), "NaN max");
        assertEquals(0f, bar().fraction(Float.NaN).displayedFraction(), "NaN fraction");
        assertEquals(0f, bar().fraction(Float.NEGATIVE_INFINITY).displayedFraction());
        assertEquals("0/0", bar().value(Float.NaN, Float.NaN).valueAsFraction().resolvedValueText());
    }

    @Test
    void degenerateBoundsPaintNothingAndNeverThrow() {
        float[][] bounds = {
                {20, 20, 0, 16}, {20, 20, 200, 0}, {20, 20, -50, 16}, {20, 20, 200, -3},
                {Float.NaN, 20, 200, 16}, {20, 20, Float.NaN, 16}, {20, 20, Float.POSITIVE_INFINITY, 16}};
        for (float[] b : bounds) {
            MGauge g = new MGauge().bounds(b[0], b[1], b[2], b[3]).caption("HP").valueAsPercent()
                    .ghost(true).fullGlow(GLOW).shimmer(true).fraction(1f).fraction(0.5f).pulseFlash();
            RasterUiFixture fx = new RasterUiFixture(W, H);
            assertDoesNotThrow(() -> g.render(fx.ui));
            assertEquals(0, fx.countPainted(0, 0, W, H));
            assertEquals(0f, g.barRect()[2], "no bar to overlay");
        }
    }

    @Test
    void oddCallsAreSafe() {
        MGauge g = bar().caption(null).valueText(null).shape(null).valuePlacement(null)
                .ghostTiming(Float.NaN, -1f).flashSeconds(Float.NaN).captionWidth(Float.NaN).barHeight(-4f)
                .ghost(true).fraction(1f).fraction(0.2f);
        assertDoesNotThrow(() -> {
            g.update(Float.NaN);
            g.update(-5f);
            g.update(Float.POSITIVE_INFINITY);
            g.update(0f);
            g.render(null);
            g.render(new RasterUiFixture(W, H).ui);
        });
        assertEquals(-1f, g.ghostFraction(), "zero-length timing ends the trail on the first update");
        assertTrue(Float.isFinite(g.displayedFraction()));

        MGauge tiny = new MGauge().bounds(20, 20, 0.4f, 0.4f).fraction(1f).shape(MGauge.Shape.PILL);
        assertDoesNotThrow(() -> tiny.render(new RasterUiFixture(W, H).ui));
    }

    @Test
    void aCaptionWiderThanTheBoundsLeavesNoBar() {
        MGauge g = new MGauge().bounds(20, 20, 30, 16).captionWidth(60).caption("HP").fraction(1f);
        assertEquals(0f, g.barRect()[2]);
        assertDoesNotThrow(() -> g.render(new RasterUiFixture(W, H).ui));
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    void paintingNeverAdvancesState() {
        MGauge g = bar().ghost(true).fullGlow(GLOW).shimmer(true).flash(0xFFFFFFFF)
                .fraction(1f).fraction(0.5f).pulseFlash();
        g.update(0.4f);
        float ghost = g.ghostFraction(), flash = g.flashStrength(), shimmer = g.shimmerPhase();

        RasterUiFixture first = paint(g);
        RasterUiFixture second = paint(g);

        assertEquals(0, first.diff(second, 0, 0, W, H));
        assertEquals(ghost, g.ghostFraction());
        assertEquals(flash, g.flashStrength());
        assertEquals(shimmer, g.shimmerPhase());
    }

    @Test
    void twoGaugesFedTheSameCallsPaintIdentically() {
        MGauge a = scripted();
        MGauge b = scripted();
        float[] steps = {0.016f, 0.2f, 0.033f, 0.1f, 0.25f, 0.05f, 1.5f};
        float level = 0.9f;
        for (int i = 0; i < steps.length; i++) {
            level = (i % 3 == 2) ? Math.min(1f, level + 0.3f) : level * 0.7f;
            for (MGauge g : new MGauge[]{a, b}) {
                g.value(level * 180f, 180f);
                if (i == 1 || i == 4) g.pulseFlash();
                g.update(steps[i]);
            }
            assertEquals(a.ghostFraction(), b.ghostFraction(), "step " + i);
            assertEquals(a.flashStrength(), b.flashStrength(), "step " + i);
            assertEquals(0, paint(a).diff(paint(b), 0, 0, W, H), "step " + i);
        }
    }

    private static MGauge scripted() {
        return new MGauge().bounds(12, 12, 230, 24).caption("VIT").captionWidth(36).vitalRamp()
                .valueAsFraction().ghost(true).flash(0xFFFFFFFF).fullGlow(GLOW).shimmer(true)
                .value(180f, 180f);
    }
}
