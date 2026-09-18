package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MPipRow}: slot geometry and alignment, lit vs. empty pips, the three silhouettes, the pop
 * and spend clocks, scaling, degenerate inputs and determinism, over the headless raster rig.
 */
class MPipRowTest {

    private static final int W = 256;
    private static final int H = 48;
    private static final int LIT = 0xFF40D890;
    private static final int EMPTY = 0xFF303848;

    // 5 pips of 12px with 4px gaps (76px wide) in a 200x20 box at (10,10): pips sit at y 14..26.
    private static MPipRow row() {
        return new MPipRow().bounds(10, 10, 200, 20).count(5).color(LIT).emptyColor(EMPTY);
    }

    private static RasterUiFixture paint(MPipRow row) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        row.render(fx.ui);
        return fx;
    }

    private static int countIn(RasterUiFixture fx, int color, float[] r) {
        return fx.countExactly(color, (int) r[0], (int) r[1], (int) (r[0] + r[2]), (int) (r[1] + r[3]));
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    void pipsAreLaidOutOnOnePitchAndCentredVertically() {
        MPipRow row = row();
        assertEquals(76f, row.preferredWidth(), 1e-4f);
        assertEquals(12f, row.preferredHeight(), 1e-4f);
        assertEquals(12f, row.pipSide(), 1e-4f);
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(new float[]{10f + i * 16f, 14f, 12f, 12f}, row.pipRect(i), 1e-4f, "pip " + i);
        }
    }

    @Test
    void alignmentPlacesTheRowWithinTheBounds() {
        assertEquals(10f, row().align(MPipRow.Align.LEFT).pipRect(0)[0], 1e-4f);
        assertEquals(72f, row().align(MPipRow.Align.CENTER).pipRect(0)[0], 1e-4f, "(200 - 76) / 2 in");
        assertEquals(134f, row().align(MPipRow.Align.RIGHT).pipRect(0)[0], 1e-4f);
        assertEquals(210f, row().align(MPipRow.Align.RIGHT).pipRect(4)[0] + 12f, 1e-4f, "flush with the right edge");
        assertEquals(10f, row().align(null).pipRect(0)[0], 1e-4f, "null falls back to LEFT");

        RasterUiFixture right = paint(row().align(MPipRow.Align.RIGHT).filled(5));
        assertEquals(0, right.countPainted(0, 0, 133, H), "nothing left of the right-aligned row");
        assertTrue(right.countPainted(134, 14, 210, 26) > 300);
    }

    @Test
    void pipsShrinkEvenlyToFitTheBounds() {
        MPipRow narrow = row().size(46, 20);
        assertEquals(6f, narrow.pipSide(), 1e-4f, "(46 - 4 gaps of 4) / 5");
        assertEquals(10f + 4 * 10f + 6f, narrow.pipRect(4)[0] + narrow.pipRect(4)[2], 1e-3f, "the last pip ends in bounds");

        MPipRow flat = row().size(200, 8);
        assertArrayEquals(new float[]{10f, 10f, 8f, 8f}, flat.pipRect(0), 1e-4f, "height caps the side");

        assertEquals(0f, row().size(12, 20).pipSide(), "gaps alone overflow: nothing to draw");
        assertEquals(76f, narrow.preferredWidth(), 1e-4f, "the wish is unchanged by the squeeze");
    }

    @Test
    void sizeAndGapAreConfigurable() {
        MPipRow row = row().pipSize(8).gap(2);
        assertEquals(5 * 8f + 4 * 2f, row.preferredWidth(), 1e-4f);
        assertArrayEquals(new float[]{10f + 2 * 10f, 16f, 8f, 8f}, row.pipRect(2), 1e-4f);
    }

    // ── Lit vs. empty, shapes ────────────────────────────────────────────────

    @Test
    void litAndEmptyPipsDiffer() {
        MPipRow row = row().shape(MPipRow.Shape.RECT).filled(3);
        RasterUiFixture fx = paint(row);
        for (int i = 0; i < 5; i++) {
            float[] r = row.pipRect(i);
            boolean lit = i < 3;
            assertTrue(countIn(fx, lit ? LIT : EMPTY, r) > 40, "pip " + i + " shows its own colour");
            assertEquals(0, countIn(fx, lit ? EMPTY : LIT, r), "pip " + i + " shows none of the other");
        }
        assertEquals(0, fx.countPainted(86, 0, W, H), "nothing past the fifth pip");
        assertEquals(3, row.filled());
    }

    @Test
    void everyPipCarriesADarkOnePixelOutline() {
        RasterUiFixture fx = paint(row().shape(MPipRow.Shape.RECT).filled(2));
        for (int i = 0; i < 5; i++) {
            int x0 = 10 + i * 16;
            // A 12x12 square with a 10x10 inside: the ring is 44 pixels, lit or not.
            assertEquals(44, fx.countExactly(MStyle.GAUGE_OUTLINE, x0, 14, x0 + 12, 26), "pip " + i);
        }
    }

    @Test
    void theEmptyColourDefaultsToTheGaugeTrack() {
        MPipRow row = new MPipRow().bounds(10, 10, 200, 20).count(3).shape(MPipRow.Shape.RECT);
        assertEquals(100, countIn(paint(row), MStyle.GAUGE_TRACK, row.pipRect(1)));
    }

    @Test
    void theThreeShapesAreDistinct() {
        RasterUiFixture diamond = paint(row().filled(3).shape(MPipRow.Shape.DIAMOND));
        RasterUiFixture rect = paint(row().filled(3).shape(MPipRow.Shape.RECT));
        RasterUiFixture circle = paint(row().filled(3).shape(MPipRow.Shape.CIRCLE));
        RasterUiFixture fallback = paint(row().filled(3).shape(null));

        assertTrue(diamond.diff(rect, 0, 0, W, H) > 100);
        assertTrue(diamond.diff(circle, 0, 0, W, H) > 50);
        assertTrue(rect.diff(circle, 0, 0, W, H) > 50);
        assertEquals(0, diamond.diff(fallback, 0, 0, W, H), "null falls back to DIAMOND");

        // A square reaches its corners, a circle and a diamond do not.
        assertEquals(MStyle.GAUGE_OUTLINE, rect.bitmap.getColor(10, 14));
        assertEquals(RasterUiFixture.BACKGROUND, circle.bitmap.getColor(10, 14));
        assertEquals(RasterUiFixture.BACKGROUND, diamond.bitmap.getColor(10, 14));
        for (RasterUiFixture fx : new RasterUiFixture[]{diamond, rect, circle}) {
            assertTrue(fx.countExactly(LIT, 10, 14, 22, 26) > 8, "each shape shows the lit colour");
            assertTrue(fx.countExactly(EMPTY, 74, 14, 86, 26) > 8, "and the empty one");
        }
    }

    @Test
    void filledIsClampedToTheCount() {
        assertEquals(5, row().filled(99).filled());
        assertEquals(0, row().filled(-4).filled());
        assertEquals(0, paint(row().filled(99)).diff(paint(row().filled(5)), 0, 0, W, H));
        assertEquals(2, row().filled(4).count(2).filled(), "a shrinking row re-clamps");
    }

    // ── Pop ──────────────────────────────────────────────────────────────────

    @Test
    void thePopTimelineEasesOut() {
        MPipRow row = row().filled(3);
        assertEquals(0f, row.popAmount(), "idle until asked");

        row.popLast();
        assertEquals(1f, row.popAmount(), 1e-6f);
        row.update(0.15f);
        // Halfway: 1 - EaseOutCubic(0.5) = 0.125, squared for a snappier settle.
        assertEquals(0.125f * 0.125f, row.popAmount(), 1e-4f);
        row.update(0.15f);
        assertEquals(0f, row.popAmount(), 1e-6f);

        row.popLast();
        row.update(50f);
        assertEquals(0f, row.popAmount(), "one large step finishes it");
    }

    @Test
    void thePopGrowsTheNewestPipOnly() {
        MPipRow resting = row().shape(MPipRow.Shape.RECT).filled(3);
        MPipRow popping = row().shape(MPipRow.Shape.RECT).filled(3).popLast();
        RasterUiFixture rest = paint(resting);
        RasterUiFixture pop = paint(popping);

        // Pip 2 rests at x 42..54, y 14..26; at full pop it grows 35% (2.1px a side).
        assertEquals(0, rest.countPainted(42, 12, 54, 14), "at rest nothing above the pip");
        assertTrue(pop.countPainted(42, 12, 54, 14) > 15, "the pop spills past its slot");
        assertEquals(0, rest.diff(pop, 10, 10, 38, 30), "the older pips hold still");
        assertEquals(0, rest.diff(pop, 57, 10, 210, 30), "and so do the empty ones");

        popping.update(MPipRow.POP_SECONDS);
        assertEquals(0, rest.diff(paint(popping), 0, 0, W, H), "it settles back exactly");
    }

    @Test
    void aPopWithNothingLitIsHarmless() {
        MPipRow row = row().filled(0).popLast();
        assertEquals(0, paint(row).diff(paint(row().filled(0)), 0, 0, W, H));
    }

    // ── Spend ────────────────────────────────────────────────────────────────

    @Test
    void theSpendTimelineEasesOut() {
        MPipRow row = row().filled(2);
        assertEquals(0f, row.spendAmount());

        row.spend();
        assertEquals(1f, row.spendAmount(), 1e-6f);
        row.update(0.2f);
        assertEquals(0.125f, row.spendAmount(), 1e-4f, "halfway through 0.4 s");
        row.update(0.2f);
        assertEquals(0f, row.spendAmount(), 1e-6f);

        row.spend();
        row.update(Float.MAX_VALUE);
        assertEquals(0f, row.spendAmount());
        row.spend(0);
        assertEquals(0f, row.spendAmount(), "spending nothing starts nothing");
    }

    @Test
    void theSpendFlashLandsOnThePipJustEmptied() {
        MPipRow plain = row().shape(MPipRow.Shape.RECT).filled(2);
        MPipRow spent = row().shape(MPipRow.Shape.RECT).filled(3).filled(2).spend();
        RasterUiFixture rest = paint(plain);
        RasterUiFixture flash = paint(spent);

        assertTrue(rest.diff(flash, 42, 14, 54, 26) > 80, "pip 2, the one just emptied, flashes");
        assertEquals(0, rest.diff(flash, 10, 10, 38, 30), "the lit pips do not");
        assertEquals(0, rest.diff(flash, 58, 10, 210, 30), "nor do the pips that were already empty");

        spent.update(0.1f);
        assertTrue(flash.diff(paint(spent), 40, 12, 56, 28) > 40, "it fades and spreads frame to frame");
        spent.update(MPipRow.SPEND_SECONDS);
        assertEquals(0, rest.diff(paint(spent), 0, 0, W, H), "and leaves no trace");
    }

    @Test
    void spendingSeveralFlashesEachOfThem() {
        MPipRow plain = row().shape(MPipRow.Shape.RECT).filled(1);
        RasterUiFixture rest = paint(plain);
        RasterUiFixture flash = paint(row().shape(MPipRow.Shape.RECT).filled(4).filled(1).spend(3));

        for (int i = 1; i <= 3; i++) {
            int x0 = 10 + i * 16;
            assertTrue(rest.diff(flash, x0, 14, x0 + 12, 26) > 80, "pip " + i);
        }
        assertEquals(0, rest.diff(flash, 72, 10, 210, 30), "pip 4 was never lit");

        RasterUiFixture overshoot = paint(row().shape(MPipRow.Shape.RECT).filled(1).spend(99));
        assertEquals(0, overshoot.countPainted(86, 0, W, H), "a count past the row is ignored");
    }

    @Test
    void theSpendFlashColourIsAParameter() {
        int red = 0xFFFF2020;
        RasterUiFixture fx = paint(row().shape(MPipRow.Shape.RECT).filled(2).spendFlashColor(red).spend());
        assertTrue(fx.countExactly(red, 42, 14, 54, 26) > 80, "at full strength the flash is the pure colour");
    }

    @Test
    void autoAnimateFiresTheEffectsFromFilled() {
        MPipRow row = row().autoAnimate(true).filled(2);
        assertEquals(0f, row.popAmount(), "the first binding never animates");
        assertEquals(0f, row.spendAmount());

        row.filled(3);
        assertEquals(1f, row.popAmount(), 1e-6f, "a gain pops");
        assertEquals(0f, row.spendAmount());

        row.update(1f);
        row.filled(1);
        assertEquals(1f, row.spendAmount(), 1e-6f, "a loss flashes");
        assertEquals(0f, row.popAmount());
        RasterUiFixture flash = paint(row);
        RasterUiFixture rest = paint(row().filled(1));
        assertTrue(rest.diff(flash, 26, 10, 38, 30) > 20, "pip 1");
        assertTrue(rest.diff(flash, 42, 10, 54, 30) > 20, "and pip 2: both were spent");

        row.update(1f);
        row.filled(1);
        assertEquals(0f, row.spendAmount(), "no change, no effect");

        row.reset().filled(4);
        assertEquals(0f, row.popAmount(), "after reset the next value is a binding again");

        MPipRow manual = row().filled(2).filled(4).filled(1);
        assertEquals(0f, manual.popAmount() + manual.spendAmount(), "off by default");
    }

    // ── Scale ────────────────────────────────────────────────────────────────

    @Test
    void scaleTwoDoublesEveryMetric() {
        MPipRow two = new MPipRow().bounds(10, 4, 240, 40).count(5).scale(2f).shape(MPipRow.Shape.RECT).filled(5);
        assertEquals(152f, two.preferredWidth(), 1e-4f);
        assertEquals(24f, two.preferredHeight(), 1e-4f);
        assertArrayEquals(new float[]{10f + 32f, 12f, 24f, 24f}, two.pipRect(1), 1e-4f, "24px pips on a 32px pitch");

        RasterUiFixture fx = paint(two);
        assertEquals(24 * 24 - 20 * 20, fx.countExactly(MStyle.GAUGE_OUTLINE, 10, 12, 34, 36), "a 2px outline");
    }

    // ── Degenerate inputs ────────────────────────────────────────────────────

    @Test
    void anEmptyRowIsNothing() {
        for (int n : new int[]{0, -3}) {
            MPipRow row = row().count(n).filled(2).popLast().spend();
            assertEquals(0, row.count());
            assertEquals(0f, row.preferredWidth());
            assertEquals(0f, row.pipSide());
            assertArrayEquals(new float[4], row.pipRect(0), 0f);
            assertEquals(0, paint(row).countPainted(0, 0, W, H));
        }
    }

    @Test
    void outOfRangeIndicesGiveAnEmptyRect() {
        assertArrayEquals(new float[4], row().pipRect(-1), 0f);
        assertArrayEquals(new float[4], row().pipRect(5), 0f);
        assertArrayEquals(new float[4], row().pipRect(Integer.MAX_VALUE), 0f);
    }

    @Test
    void degenerateBoundsPaintNothingAndNeverThrow() {
        float[][] bounds = {{10, 10, 0, 20}, {10, 10, 200, 0}, {10, 10, -40, 20}, {10, 10, 200, -1},
                {Float.NaN, 10, 200, 20}, {10, 10, Float.NaN, 20}, {10, 10, Float.POSITIVE_INFINITY, 20}};
        for (float[] b : bounds) {
            for (MPipRow.Shape shape : MPipRow.Shape.values()) {
                MPipRow row = new MPipRow().bounds(b[0], b[1], b[2], b[3]).count(4).filled(2).shape(shape)
                        .popLast().spend();
                RasterUiFixture fx = new RasterUiFixture(W, H);
                assertDoesNotThrow(() -> row.render(fx.ui));
                assertEquals(0, fx.countPainted(0, 0, W, H));
                assertArrayEquals(new float[4], row.pipRect(0), 0f);
            }
        }
    }

    @Test
    void oddCallsAreSafe() {
        MPipRow row = row().pipSize(Float.NaN).gap(-5f).filled(3).popLast().spend(2);
        assertDoesNotThrow(() -> {
            row.update(Float.NaN);
            row.update(-1f);
            row.update(Float.POSITIVE_INFINITY);
            row.render(null);
            row.render(new RasterUiFixture(W, H).ui);
        });
        assertEquals(1f, row.popAmount(), 1e-6f, "bad steps count as no time at all");
        assertEquals(0f, row.pipSide(), "a NaN size reads as zero: nothing to draw");

        MPipRow tiny = row().size(100, 1.5f).filled(5);
        for (MPipRow.Shape shape : MPipRow.Shape.values()) {
            assertDoesNotThrow(() -> tiny.shape(shape).render(new RasterUiFixture(W, H).ui));
        }
        MPipRow crowded = row().count(100_000).filled(50_000);
        assertEquals(0f, crowded.pipSide());
        assertDoesNotThrow(() -> crowded.render(new RasterUiFixture(W, H).ui));
    }

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    void paintingNeverAdvancesState() {
        MPipRow row = row().filled(3).popLast().spend();
        row.update(0.05f);
        float pop = row.popAmount(), spend = row.spendAmount();

        assertEquals(0, paint(row).diff(paint(row), 0, 0, W, H));
        assertEquals(pop, row.popAmount());
        assertEquals(spend, row.spendAmount());
    }

    @Test
    void twoRowsFedTheSameCallsPaintIdentically() {
        for (MPipRow.Shape shape : MPipRow.Shape.values()) {
            MPipRow a = row().shape(shape).autoAnimate(true).align(MPipRow.Align.CENTER);
            MPipRow b = row().shape(shape).autoAnimate(true).align(MPipRow.Align.CENTER);
            int[] levels = {1, 2, 2, 4, 3, 0, 5};
            float[] steps = {0.016f, 0.05f, 0.1f, 0.033f, 0.12f, 0.25f, 0.4f};
            for (int i = 0; i < levels.length; i++) {
                for (MPipRow row : new MPipRow[]{a, b}) {
                    row.filled(levels[i]);
                    row.update(steps[i]);
                }
                assertEquals(a.popAmount(), b.popAmount(), shape + " step " + i);
                assertEquals(a.spendAmount(), b.spendAmount(), shape + " step " + i);
                assertEquals(0, paint(a).diff(paint(b), 0, 0, W, H), shape + " step " + i);
            }
        }
    }
}
