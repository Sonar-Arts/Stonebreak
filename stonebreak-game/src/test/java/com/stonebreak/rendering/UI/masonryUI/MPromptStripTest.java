package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.masonryUI.MPromptStrip.State;
import com.stonebreak.rendering.UI.masonryUI.MPromptStrip.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MPromptStrip}: cell states, the draining timer, the stamp-pop and flash timelines, the
 * slide, and the geometry callers hang their own effects on.
 */
class MPromptStripTest {

    private static final int W = 640;
    private static final int H = 200;

    private static List<Step> arrows() {
        return List.of(new Step(MSymbol.ARROW_UP, "W"), new Step(MSymbol.ARROW_LEFT, "A"),
                new Step(MSymbol.ARROW_DOWN, "S"), new Step(MSymbol.ARROW_RIGHT, "D"));
    }

    /** At rest at (20, 100), 600 x 76, with room above for the flash word. */
    private static MPromptStrip strip() {
        return new MPromptStrip().steps(arrows()).scale(1f).bounds(20, 100, 600, 76);
    }

    private static RasterUiFixture paint(MPromptStrip strip) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        strip.render(fx.ui);
        return fx;
    }

    private static int[] box(float[] r, int grow) {
        return new int[]{Math.max(0, (int) Math.floor(r[0]) - grow), Math.max(0, (int) Math.floor(r[1]) - grow),
                Math.min(W, (int) Math.ceil(r[0] + r[2]) + grow), Math.min(H, (int) Math.ceil(r[1] + r[3]) + grow)};
    }

    private static int diffIn(RasterUiFixture a, RasterUiFixture b, float[] rect, int grow) {
        int[] r = box(rect, grow);
        return a.diff(b, r[0], r[1], r[2], r[3]);
    }

    // ── State model ──────────────────────────────────────────────────────────

    @Test
    void statesFollowCurrentUntilACellIsResolved() {
        MPromptStrip strip = strip().current(1);
        assertEquals(State.UPCOMING, strip.stateOf(0));
        assertEquals(State.CURRENT, strip.stateOf(1));
        strip.markResolved(0, State.RESOLVED_BEST).markResolved(1, State.RESOLVED_FAIL).current(2);
        assertEquals(State.RESOLVED_BEST, strip.stateOf(0));
        assertEquals(State.RESOLVED_FAIL, strip.stateOf(1), "a result outranks the cursor");
        assertEquals(State.CURRENT, strip.stateOf(2));
        assertEquals(1, strip.count(State.RESOLVED_BEST));
        assertEquals(1, strip.count(State.UPCOMING));

        strip.markResolved(1, State.UPCOMING);
        assertEquals(State.UPCOMING, strip.stateOf(1), "a non-result state clears the result");
        strip.markResolved(0, null);
        assertEquals(State.UPCOMING, strip.stateOf(0));
        strip.markResolved(3, State.RESOLVED_OK).resetResults();
        assertEquals(0, strip.count(State.RESOLVED_OK));
        strip.markResolved(2, State.RESOLVED_OK).steps(arrows());
        assertEquals(0, strip.count(State.RESOLVED_OK), "new steps start clean");
    }

    @Test
    void outOfRangeIndicesAreIgnored() {
        MPromptStrip strip = strip().current(42).upNext(-9);
        assertDoesNotThrow(() -> strip.markResolved(-1, State.RESOLVED_OK).markResolved(4, State.RESOLVED_OK));
        assertEquals(State.UPCOMING, strip.stateOf(-1));
        assertEquals(State.UPCOMING, strip.stateOf(4));
        assertEquals(1f, strip.stampPop(99));
        assertArrayEquals(new float[4], strip.cellRect(-1));
        assertArrayEquals(new float[4], strip.cellRect(4));
        assertArrayEquals(new float[4], strip.timerRect(4));
        assertEquals(0, paint(strip).diff(paint(strip()), 0, 0, W, H), "no such current cell: all upcoming");
    }

    // ── Cell looks ───────────────────────────────────────────────────────────

    @Test
    void everyStateLooksDifferentFromEveryOther() {
        List<RasterUiFixture> looks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        looks.add(paint(strip()));
        names.add("UPCOMING");
        looks.add(paint(strip().upNext(1)));
        names.add("UP NEXT");
        looks.add(paint(strip().current(1)));
        names.add("CURRENT");
        for (State s : new State[]{State.RESOLVED_BEST, State.RESOLVED_OK, State.RESOLVED_FAIL}) {
            MPromptStrip strip = strip().markResolved(1, s);
            strip.update(1f);   // settled: compare the stamps, not the pop
            looks.add(paint(strip));
            names.add(s.name());
        }
        float[] cell = strip().cellRect(1);
        for (int i = 0; i < looks.size(); i++) {
            for (int j = i + 1; j < looks.size(); j++) {
                assertTrue(diffIn(looks.get(i), looks.get(j), cell, 0) > 100, names.get(i) + " vs " + names.get(j));
            }
        }
    }

    @Test
    void stampsUseTheAccentThePrimaryAndTheErrorTokens() {
        float[] cell = strip().cellRect(0);
        int[] b = box(cell, 0);
        MPromptStrip best = strip().markResolved(0, State.RESOLVED_BEST);
        MPromptStrip ok = strip().markResolved(0, State.RESOLVED_OK);
        MPromptStrip fail = strip().markResolved(0, State.RESOLVED_FAIL);
        best.update(1f);
        ok.update(1f);
        fail.update(1f);
        assertTrue(paint(best).countExactly(MStyle.TEXT_ACCENT, b[0], b[1], b[2], b[3]) > 800, "gold stamp");
        assertTrue(paint(ok).countExactly(MStyle.TEXT_PRIMARY, b[0], b[1], b[2], b[3]) > 800, "pale stamp");
        RasterUiFixture failed = paint(fail);
        assertTrue(failed.countExactly(MStyle.TEXT_ERROR, b[0], b[1], b[2], b[3]) > 100, "red glyph and edge");
        assertEquals(0, failed.countExactly(MStyle.TEXT_ACCENT, b[0], b[1], b[2], b[3]));
    }

    @Test
    void theCurrentCellIsEnlargedAndOnlyItHasATimer() {
        MPromptStrip strip = strip().current(2);
        RasterUiFixture idle = paint(strip());
        RasterUiFixture live = paint(strip);
        float[] cell = strip.cellRect(2);
        int[] ring = box(cell, 4);
        int[] inner = box(cell, 0);
        int outside = live.diff(idle, ring[0], ring[1], ring[2], ring[3]) - live.diff(idle, inner[0], inner[1], inner[2], inner[3]);
        assertTrue(outside > 100, "the current cell paints beyond its rest rect");
        assertTrue(diffIn(live, idle, strip.timerRect(2), 0) > 200, "timer underline under the current cell");
        assertEquals(0, diffIn(live, idle, strip.timerRect(0), 0), "and under no other");
    }

    @Test
    void theTimerDrainsAndItsColourRamps() {
        float[] track = strip().current(0).timerRect(0);
        int[] b = box(track, 0);
        RasterUiFixture full = paint(strip().current(0).timer(1f));
        RasterUiFixture half = paint(strip().current(0).timer(0.5f));
        RasterUiFixture low = paint(strip().current(0).timer(0.1f));
        RasterUiFixture empty = paint(strip().current(0).timer(0f));

        int fullGreen = full.countExactly(MStyle.VITAL_OK, b[0], b[1], b[2], b[3]);
        assertTrue(fullGreen > 120, "full: the high colour across the track");
        int halfWarn = half.countExactly(MStyle.VITAL_WARN, b[0], b[1], b[2], b[3]);
        assertTrue(halfWarn > 60 && halfWarn < fullGreen * 0.7f, "half: the mid colour over about half of it");
        assertEquals(0, half.countExactly(MStyle.VITAL_OK, b[0], b[1], b[2], b[3]));
        assertEquals(0, empty.diff(paint(strip().current(0).timer(-4f)), 0, 0, W, H), "clamped below");
        assertEquals(0, empty.diff(paint(strip().current(0).timer(Float.NaN)), 0, 0, W, H), "NaN reads as empty");
        assertEquals(0, full.diff(paint(strip().current(0).timer(3f)), 0, 0, W, H), "clamped above");
        assertTrue(empty.countExactly(MStyle.GAUGE_TRACK, b[0], b[1], b[2], b[3]) > 100, "empty: bare track");
        assertTrue(low.diff(half, b[0], b[1], b[2], b[3]) > 50);

        RasterUiFixture custom = paint(strip().current(0).timer(1f).timerColors(0xFF101010, 0xFF202020, 0xFF3366FF));
        assertTrue(custom.countExactly(0xFF3366FF, b[0], b[1], b[2], b[3]) > 120, "the ramp colours are the caller's");
    }

    @Test
    void captionAndCounterFillTheSidesAndNarrowTheCells() {
        MPromptStrip bare = strip();
        MPromptStrip labelled = strip().caption("FOCUS COMBO").counter("3 HITS");
        RasterUiFixture a = paint(bare), b = paint(labelled);
        assertTrue(a.diff(b, 28, 100, 120, 176) > 60, "caption on the left");
        assertTrue(a.diff(b, 520, 100, 612, 176) > 60, "counter on the right");
        assertTrue(b.countExactly(MStyle.TEXT_ACCENT, 28, 100, 120, 176) > 40, "the caption is header gold");
        assertTrue(b.countExactly(MStyle.TEXT_PRIMARY, 520, 100, 612, 176) > 20, "the count is primary");
        assertTrue(b.countExactly(MStyle.TEXT_SECONDARY, 520, 100, 612, 176) > 20, "its unit is secondary");
        assertTrue(b.diff(paint(strip().caption("FOCUS COMBO").counter("4 HITS")), 520, 100, 612, 176) > 5);

        // Twelve steps: with the sides reserved, the cells must shrink further to stay between them.
        List<Step> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(new Step(MSymbol.ARROW_UP, ""));
        MPromptStrip wide = new MPromptStrip().steps(many).scale(1f).bounds(20, 100, 600, 76);
        MPromptStrip sided = new MPromptStrip().steps(many).caption("ORDER").scale(1f).bounds(20, 100, 600, 76);
        assertTrue(sided.cellRect(0)[2] < wide.cellRect(0)[2]);
        assertTrue(sided.cellRect(0)[0] >= 20f + 8f + 92f - 0.001f, "clear of the caption area");
        float[] last = sided.cellRect(11);
        assertTrue(last[0] + last[2] <= 620f - 8f - 92f + 0.001f, "and of the counter area");
    }

    // ── Timelines ────────────────────────────────────────────────────────────

    @Test
    void aResolvedCellPopsThenSettles() {
        MPromptStrip strip = strip().markResolved(1, State.RESOLVED_BEST);
        assertEquals(1.3f, strip.stampPop(1), 0.0001f, "lands big");
        RasterUiFixture landed = paint(strip);
        strip.update(MPromptStrip.STAMP_SECONDS / 2f);
        float mid = strip.stampPop(1);
        assertTrue(mid > 1f && mid < 1.3f, "settling: " + mid);
        strip.update(MPromptStrip.STAMP_SECONDS);
        assertEquals(1f, strip.stampPop(1), 0.0001f, "settled");
        RasterUiFixture settled = paint(strip);
        assertTrue(diffIn(landed, settled, strip.cellRect(1), 8) > 100, "the pop is visible");
        strip.update(500f);
        assertEquals(1f, strip.stampPop(1), 0.0001f, "and stays settled after a huge step");
        assertEquals(0, settled.diff(paint(strip), 0, 0, W, H));
        assertEquals(1f, strip.stampPop(0), "an unresolved cell never pops");
    }

    @Test
    void theCurrentCellPulsesWithUpdateButNothingElseMoves() {
        MPromptStrip a = strip().current(1);
        MPromptStrip b = strip().current(1);
        b.update(0.1f);
        assertTrue(diffIn(paint(a), paint(b), a.cellRect(1), 6) > 20, "the pulse");
        MPromptStrip idleA = strip(), idleB = strip();
        idleB.update(0.1f);
        assertEquals(0, paint(idleA).diff(paint(idleB), 0, 0, W, H), "a strip with no current cell is still");
    }

    @Test
    void theFlashWordPopsHoldsFadesAndEnds() {
        MPromptStrip strip = strip().flashText("FLAWLESS!", 0f);
        RasterUiFixture none = paint(strip());
        float[] band = strip.flashRect();
        assertTrue(band[1] + band[3] <= 100f, "the word sits above the strip");

        strip.update(0.1f);
        RasterUiFixture early = paint(strip);
        assertTrue(diffIn(early, none, band, 0) > 300, "the word is up");
        assertTrue(early.diff(none, 20, 100, 620, 176) > 5000, "and the strip is washed in the flash colour");

        strip.update(0.4f);
        RasterUiFixture held = paint(strip);
        assertTrue(diffIn(early, held, band, 0) > 50, "the pop settles");
        assertEquals(0.5f, strip.flashAge(), 0.0001f);

        strip.update(0.6f);
        RasterUiFixture fading = paint(strip);
        assertTrue(diffIn(held, fading, band, 0) > 50, "then it fades");

        strip.update(0.2f);
        assertEquals(-1f, strip.flashAge(), "over after its duration");
        assertEquals(0, paint(strip).diff(none, 0, 0, W, H), "and leaves nothing behind");

        MPromptStrip big = strip().flashText("FLAWLESS!", 0f);
        big.update(1.0e6f);
        assertEquals(-1f, big.flashAge(), "one huge step ends it too");
    }

    @Test
    void theFlashIsTheCallersWordColourAndLength() {
        MPromptStrip strip = strip().flashText("PERFECT", 0.3f).flashColor(0xFF33DDAA).flashDuration(2f);
        assertEquals(0.3f, strip.flashAge(), "the caller may start it part-way");
        RasterUiFixture fx = paint(strip);
        assertTrue(fx.countExactly(0xFF33DDAA, 0, 0, W, 100) > 100, "in the caller's colour");
        strip.update(1.5f);
        assertTrue(strip.flashAge() > 0f, "still running inside a 2s duration");
        strip.update(0.3f);
        assertEquals(-1f, strip.flashAge());

        assertEquals(-1f, strip().flashText(null, 0f).flashAge());
        assertEquals(-1f, strip().flashText("", 0f).flashAge());
        assertEquals(-1f, strip().flashText("X", -1f).flashAge());
        assertEquals(-1f, strip().flashText("X", Float.NaN).flashAge());
        assertEquals(-1f, strip().flashText("X", 0f).flashText(null, 0f).flashAge(), "null clears a running flash");
    }

    @Test
    void theStripSlidesUpFromBelowItsBounds() {
        assertEquals(0, paint(strip().slide(0f)).countPainted(0, 0, W, H), "slide 0: nothing drawn");
        MPromptStrip rest = strip().slide(1f);
        assertEquals(0f, rest.slideOffset());
        assertEquals(0, paint(rest).diff(paint(strip()), 0, 0, W, H), "slide 1 is the default resting place");

        MPromptStrip half = strip().slide(0.5f);
        float offset = half.slideOffset();
        assertTrue(offset > 0f && offset < 76f + 12f, "part-way: " + offset);
        assertEquals(rest.cellRect(0)[1] + offset, half.cellRect(0)[1], 0.001f, "cells move with the frame");
        assertEquals(rest.cellRect(0)[0], half.cellRect(0)[0]);
        RasterUiFixture fx = paint(half);
        assertEquals(0, fx.countPainted(0, 0, W, (int) Math.floor(100f + offset) - 1), "nothing above the slid frame");

        assertTrue(strip().slide(0.25f).slideOffset() > offset, "monotonic");
        assertEquals(300f, strip().slide(0f).slideDistance(300f).slideOffset(), "the caller may give the travel");
        assertEquals(0f, strip().slide(Float.NaN).slide());
        assertEquals(1f, strip().slide(5f).slide());
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    @Test
    void cellsAreSquareCentredAndInsideTheFrame() {
        float[][] frames = {{20, 100, 600, 76}, {0, 0, 300, 50}, {10, 10, 200, 30}, {5, 5, 90, 90}};
        float[] scales = {0.75f, 1f, 1.5f};
        for (float[] f : frames) {
            for (float s : scales) {
                MPromptStrip strip = new MPromptStrip().steps(arrows()).scale(s).bounds(f[0], f[1], f[2], f[3]);
                float[] first = strip.cellRect(0), last = strip.cellRect(3);
                assertEquals(first[2], first[3], "square");
                assertEquals(first[0] - f[0], (f[0] + f[2]) - (last[0] + last[2]), 0.01f, "row centred");
                assertTrue(first[2] <= 44f * s + 0.001f);
                for (int i = 1; i < 4; i++) {
                    assertTrue(strip.cellRect(i)[0] >= strip.cellRect(i - 1)[0] + strip.cellRect(i - 1)[2], "no overlap");
                }
            }
        }
    }

    @Test
    void whatIsDrawnIsWhereCellRectSaysItIs() {
        MPromptStrip strip = strip().markResolved(2, State.RESOLVED_BEST);
        strip.update(1f);
        RasterUiFixture fx = paint(strip);
        int[] b = box(strip.cellRect(2), 0);
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, b[0], b[1], b[2], b[3]) > 800);
        assertEquals(0, fx.countExactly(MStyle.TEXT_ACCENT, 0, 0, b[0] - 1, H), "and nowhere to its left");
        assertEquals(0, fx.countExactly(MStyle.TEXT_ACCENT, b[2] + 1, 0, W, H), "or right");
    }

    @Test
    void scaleScalesCellsAndText() {
        MPromptStrip one = new MPromptStrip().steps(arrows()).scale(1f).bounds(0, 0, 640, 200);
        MPromptStrip two = new MPromptStrip().steps(arrows()).scale(2f).bounds(0, 0, 640, 200);
        assertEquals(44f, one.cellRect(0)[2]);
        assertEquals(88f, two.cellRect(0)[2]);
        assertEquals(one.timerRect(0)[3] * 2f, two.timerRect(0)[3]);
        one.markResolved(0, State.RESOLVED_OK).update(1f);
        two.markResolved(0, State.RESOLVED_OK).update(1f);
        assertTrue(paint(two).countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H)
                > paint(one).countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H) * 3);
    }

    @Test
    void aStepMayBeAGlyphAKeyOrBoth() {
        List<Step> mixed = List.of(new Step(MSymbol.ARROW_UP, null), new Step(null, "Q"), new Step(MSymbol.STAR, "E"),
                new Step(null, null));
        MPromptStrip strip = new MPromptStrip().steps(mixed).scale(1f).bounds(20, 100, 600, 76).upNext(0);
        RasterUiFixture fx = paint(strip);
        RasterUiFixture blank = paint(new MPromptStrip().steps(List.of(new Step(null, ""), new Step(null, ""),
                new Step(null, ""), new Step(null, ""))).scale(1f).bounds(20, 100, 600, 76));
        assertTrue(diffIn(fx, blank, strip.cellRect(0), 0) > 50, "glyph only");
        assertTrue(diffIn(fx, blank, strip.cellRect(1), 0) > 20, "key only, centred");
        assertTrue(diffIn(fx, blank, strip.cellRect(2), 0) > 50, "both");
        assertEquals(0, diffIn(fx, blank, strip.cellRect(3), 0), "neither: an empty cell");
        assertEquals("", mixed.get(0).key(), "a null key is normalised");
    }

    // ── Determinism and degenerate input ─────────────────────────────────────

    @Test
    void twoInstancesFedTheSameUpdatesPaintIdentically() {
        MPromptStrip a = strip().caption("CHAIN").counter("2 HITS");
        MPromptStrip b = strip().caption("CHAIN").counter("2 HITS");
        float[] dts = {0.016f, 0.02f, 0.05f, 0.3f};
        for (MPromptStrip s : List.of(a, b)) {
            s.current(0).timer(0.8f);
            s.update(dts[0]);
            s.markResolved(0, State.RESOLVED_BEST).current(1).timer(0.4f);
            s.update(dts[1]);
            s.markResolved(1, State.RESOLVED_FAIL).current(2).flashText("NICE", 0f);
            s.update(dts[2]);
            s.update(dts[3]);
        }
        RasterUiFixture first = paint(a);
        assertEquals(0, first.diff(paint(b), 0, 0, W, H));
        assertEquals(0, first.diff(paint(a), 0, 0, W, H), "painting advances nothing");
    }

    @Test
    void badTimeStepsAreIgnored() {
        MPromptStrip strip = strip().current(1).markResolved(0, State.RESOLVED_OK).flashText("GO", 0.2f);
        RasterUiFixture before = paint(strip);
        strip.update(Float.NaN);
        strip.update(-1f);
        strip.update(0f);
        strip.update(Float.POSITIVE_INFINITY);
        assertEquals(0, before.diff(paint(strip), 0, 0, W, H));
        assertEquals(0.2f, strip.flashAge());
    }

    @Test
    void degenerateInputIsSafe() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        float[][] bad = {{0, 0, 0, 0}, {10, 10, -5, 40}, {10, 10, 100, 0}, {Float.NaN, 0, 100, 50},
                {0, 0, 100, Float.NaN}, {0, 0, Float.POSITIVE_INFINITY, 50}};
        for (float[] r : bad) {
            MPromptStrip strip = new MPromptStrip().steps(arrows()).current(0).caption("A").counter("1 B")
                    .flashText("X", 0.1f).scale(1f).bounds(r[0], r[1], r[2], r[3]);
            assertDoesNotThrow(() -> strip.render(fx.ui));
            assertArrayEquals(new float[4], strip.cellRect(0));
        }
        assertEquals(0, fx.countPainted(0, 0, W, H));

        assertDoesNotThrow(() -> strip().render(null));
        assertDoesNotThrow(() -> new MPromptStrip().steps(null).current(0).scale(1f).bounds(20, 100, 600, 76).render(fx.ui));
        assertTrue(fx.countPainted(20, 100, 620, 176) > 30000, "no steps: just the frame");

        List<Step> withNull = new ArrayList<>(arrows());
        withNull.add(null);
        assertEquals(4, new MPromptStrip().steps(withNull).steps().size());

        // Far too many steps for the width: cells bottom out instead of going negative.
        List<Step> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add(new Step(MSymbol.ARROW_UP, "W"));
        MPromptStrip crowded = new MPromptStrip().steps(many).current(3).scale(1f).bounds(20, 100, 200, 40);
        RasterUiFixture tight = new RasterUiFixture(W, H);
        assertDoesNotThrow(() -> crowded.render(tight.ui));
        assertTrue(crowded.cellRect(0)[2] >= 1f);
        assertTrue(crowded.cellRect(0)[0] >= 20f, "the crowded row still starts inside the frame");
        assertTrue(crowded.cellRect(59)[0] + crowded.cellRect(59)[2] <= 220.001f, "and ends inside it");
        assertEquals(0, tight.countPainted(0, 0, 19, H), "nothing leaks out of the frame");

        assertDoesNotThrow(() -> strip().caption(null).counter(null).caption("  ").counter("\n").render(new RasterUiFixture(W, H).ui));
    }
}
