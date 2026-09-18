package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.masonryUI.MMenuList.Adornment;
import com.stonebreak.rendering.UI.masonryUI.MMenuList.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MMenuList}: navigation and hit-testing as pure logic, then the visible states over the
 * headless raster rig. The geometry tests pin the promise the screens rely on: what is drawn is
 * what is hit, at any size and scale, and a short frame compresses rows instead of spilling them.
 */
class MMenuListTest {

    private static final int W = 320;
    private static final int H = 260;
    private static final int JADE = 0xFF5EC4A0;

    private static List<Row> fourRows() {
        return List.of(new Row("Strike"), new Row("Arts", true, Adornment.CHEVRON),
                new Row("Guard"), new Row("Rest", false, Adornment.count("x0")));
    }

    private static MMenuList list() {
        return new MMenuList().rows(fourRows()).scale(1f).bounds(10, 10, 250, 146);
    }

    private static RasterUiFixture paint(MMenuList list) {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        list.render(fx.ui);
        return fx;
    }

    private static int[] box(float[] r) {
        return new int[]{(int) Math.floor(r[0]), (int) Math.floor(r[1]),
                (int) Math.ceil(r[0] + r[2]), (int) Math.ceil(r[1] + r[3])};
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    @Test
    void theCursorWrapsInBothDirections() {
        MMenuList list = list();
        assertEquals(0, list.cursor());
        assertEquals(3, list.moveCursor(-1), "up from the first row lands on the last");
        assertEquals(0, list.moveCursor(1), "down from the last row lands on the first");
        assertEquals(2, list.moveCursor(2));
        assertEquals(1, list.moveCursor(-5), "a multi-row step wraps too");
        assertEquals(1, list.moveCursor(8), "whole laps come back to the same row");
        assertEquals("Arts", list.selectedRow().label());
    }

    @Test
    void theCursorClampsToTheRowsItIsGiven() {
        MMenuList list = list().cursor(99);
        assertEquals(3, list.cursor());
        assertEquals(0, list.cursor(-7).cursor());
        list.cursor(3).rows(List.of(new Row("Only")));
        assertEquals(0, list.cursor(), "shrinking the list pulls the cursor back in");
    }

    @Test
    void anEmptyListHasNoCursorAndNavigatesNowhere() {
        MMenuList list = new MMenuList().bounds(0, 0, 200, 100);
        assertEquals(-1, list.cursor());
        assertEquals(-1, list.moveCursor(1));
        assertNull(list.selectedRow());
        assertEquals(-1, list.rowAt(50, 50));
        assertFalse(list.setCursorFromPointer(50, 50));
    }

    @Test
    void nullRowsAndNullFieldsAreNormalised() {
        List<Row> withNull = new ArrayList<>();
        withNull.add(new Row(null, true, null));
        withNull.add(null);
        MMenuList list = new MMenuList().rows(withNull);
        assertEquals(1, list.rowCount());
        assertEquals("", list.rows().get(0).label());
        assertEquals(Adornment.NONE, list.rows().get(0).adornment());
        assertEquals(0, new MMenuList().rows(null).rowCount());
    }

    // ── Geometry / hit-testing ───────────────────────────────────────────────

    @Test
    void rowAtAgreesWithRowRectAtSeveralSizesAndScales() {
        float[][] frames = {{10, 10, 250, 146}, {0, 0, 120, 60}, {33.5f, 7.25f, 400, 300}, {5, 5, 90, 40}};
        float[] scales = {0.5f, 1f, 1.5f, 2.25f};
        for (float[] f : frames) {
            for (float s : scales) {
                MMenuList list = new MMenuList().rows(fourRows()).scale(s).bounds(f[0], f[1], f[2], f[3]);
                for (int i = 0; i < list.rowCount(); i++) {
                    float[] r = list.rowRect(i);
                    if (r[3] <= 0f) continue;
                    String at = "row " + i + " frame " + f[2] + "x" + f[3] + " scale " + s;
                    assertEquals(i, list.rowAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f), "centre of " + at);
                    assertEquals(i, list.rowAt(r[0], r[1]), "top-left of " + at);
                    assertEquals(-1, list.rowAt(r[0] - 0.5f, r[1] + r[3] / 2f), "left of " + at);
                    assertEquals(-1, list.rowAt(r[0] + r[2] + 0.5f, r[1] + r[3] / 2f), "right of " + at);
                }
                float[] first = list.rowRect(0);
                assertEquals(-1, list.rowAt(first[0] + 1f, first[1] - 0.5f), "above the first row");
            }
        }
    }

    @Test
    void thePointerMovesTheCursorOnlyOntoARow() {
        MMenuList list = list();
        float[] r2 = list.rowRect(2);
        assertTrue(list.setCursorFromPointer(r2[0] + 5f, r2[1] + 5f));
        assertEquals(2, list.cursor());
        assertFalse(list.setCursorFromPointer(-50f, -50f), "outside: no row");
        assertFalse(list.setCursorFromPointer(Float.NaN, Float.NaN));
        assertEquals(2, list.cursor(), "a miss leaves the cursor where it was");

        list.active(false);
        float[] r0 = list.rowRect(0);
        assertFalse(list.setCursorFromPointer(r0[0] + 5f, r0[1] + 5f), "a static list ignores the pointer");
        assertEquals(2, list.cursor());
    }

    @Test
    void rowsKeepTheirDesignHeightWhenTheFrameHasRoom() {
        MMenuList list = list();
        assertEquals(30f, list.rowHeight());
        assertEquals(list.preferredHeight(), 146f, "4 rows x 30 + 3 gaps x 2 + 2 x 10 padding");
        assertEquals(60f, new MMenuList().rows(fourRows()).scale(2f).bounds(0, 0, 500, 400).rowHeight());
    }

    @Test
    void aShortFrameCompressesRowsAndKeepsTheLastOneInside() {
        List<Row> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add(new Row("Row " + i));
        float[] heights = {300f, 180f, 120f, 70f, 41f, 30f, 12f};
        float[] scales = {1f, 1.5f};
        for (float h : heights) {
            for (float s : scales) {
                MMenuList list = new MMenuList().rows(nine).scale(s).bounds(10, 10, 250, h);
                float[] last = list.rowRect(8);
                assertTrue(last[1] + last[3] <= 10f + h + 0.001f,
                        "last row ends at " + (last[1] + last[3]) + " in a frame ending at " + (10f + h));
                assertTrue(list.rowHeight() <= 30f * s);
                for (int i = 1; i < 9; i++) {
                    assertTrue(list.rowRect(i)[1] >= list.rowRect(i - 1)[1] + list.rowRect(i - 1)[3],
                            "rows never overlap");
                }
            }
        }
        assertTrue(new MMenuList().rows(nine).scale(1f).bounds(10, 10, 250, 120).rowHeight() < 30f);
    }

    @Test
    void scaleScalesTheGeometry() {
        MMenuList one = new MMenuList().rows(fourRows()).scale(1f).bounds(0, 0, 600, 600);
        MMenuList two = new MMenuList().rows(fourRows()).scale(2f).bounds(0, 0, 600, 600);
        assertEquals(one.rowRect(0)[3] * 2f, two.rowRect(0)[3]);
        assertEquals(one.rowRect(3)[1] * 2f, two.rowRect(3)[1]);
        assertEquals(one.preferredHeight() * 2f, two.preferredHeight());
    }

    @Test
    void aSubmenuAnchorsBesideItsOpenerRow() {
        MMenuList list = list();
        float[] anchor = list.anchorRightOf(1);
        assertEquals(10f + 250f + 6f, anchor[0]);
        MMenuList sub = new MMenuList().rows(List.of(new Row("Flurry"), new Row("Stun")))
                .scale(1f).bounds(anchor[0], anchor[1], 200, 82);
        assertEquals(list.rowRect(1)[1], sub.rowRect(0)[1], "the submenu's first row is level with the opener");
        assertArrayEquals(new float[]{266f, 10f}, list.anchorRightOf(42), "no such row: the frame's top");
    }

    // ── Visible states ───────────────────────────────────────────────────────

    @Test
    void theSelectedRowShowsTheFillTheGoldBarAndTheCursor() {
        MMenuList list = list().cursor(2);
        RasterUiFixture fx = paint(list);
        int[] b = box(list.rowRect(2));
        assertTrue(fx.countExactly(MStyle.ROW_CURRENT, b[0], b[1], b[2], b[3]) > 1000, "row fill");
        assertTrue(fx.countExactly(MStyle.TEXT_ACCENT, b[0], b[1], b[0] + 4, b[3]) > 40, "3px gold bar on the left");
        assertTrue(fx.countExactly(MStyle.TEXT_PRIMARY, b[0] + 4, b[1], b[0] + 40, b[3]) > 60, "hand cursor in the gutter");
        int[] other = box(list.rowRect(0));
        assertEquals(0, fx.countExactly(MStyle.ROW_CURRENT, other[0], other[1], other[2], other[3]));
    }

    @Test
    void aStaticListShowsNoCursorAndDiffersFromAnActiveOne() {
        MMenuList activeList = list().cursor(1);
        MMenuList staticList = list().cursor(1).active(false);
        RasterUiFixture on = paint(activeList);
        RasterUiFixture off = paint(staticList);
        int[] b = box(activeList.rowRect(1));
        assertTrue(on.diff(off, b[0], b[1], b[2], b[3]) > 1000);
        assertEquals(0, off.countExactly(MStyle.ROW_CURRENT, 0, 0, W, H), "no selection fill");
        assertEquals(0, off.countExactly(MStyle.TEXT_ACCENT, 0, 0, W, H), "no gold bar");
        // With no cursor, the row under it paints exactly like an unselected row.
        RasterUiFixture elsewhere = paint(list().cursor(3).active(false));
        assertEquals(0, off.diff(elsewhere, 0, 0, W, H), "a static list does not show where the cursor is");
    }

    @Test
    void aStaticListDoesNotAnimate() {
        MMenuList a = list().active(false).rowGlow(0, MStyle.TEXT_ACCENT, 0.7f);
        MMenuList b = list().active(false).rowGlow(0, MStyle.TEXT_ACCENT, 0.7f);
        a.update(0.13f);
        b.update(0.13f);
        b.update(0.41f);
        assertEquals(0, paint(a).diff(paint(b), 0, 0, W, H), "the glow holds steady while static");

        MMenuList liveA = list().rowGlow(0, MStyle.TEXT_ACCENT, 0.7f);
        MMenuList liveB = list().rowGlow(0, MStyle.TEXT_ACCENT, 0.7f);
        liveB.update(0.41f);
        assertTrue(paint(liveA).diff(paint(liveB), 0, 0, W, H) > 0, "and pulses (with the bobbing cursor) while live");
    }

    @Test
    void theVeilDimsTheFrame() {
        RasterUiFixture clear = paint(list().active(false).veil(0f));
        RasterUiFixture veiled = paint(list().active(false).veil(1f));
        RasterUiFixture half = paint(list().active(false).veil(0.5f));
        assertTrue(clear.diff(veiled, 10, 10, 260, 156) > 20000, "the veil covers the whole frame");
        assertTrue(half.diff(veiled, 10, 10, 260, 156) > 20000, "and its amount matters");
        assertEquals(0, clear.diff(veiled, 0, 0, W, 8), "nothing outside the frame");
        assertEquals(1f, new MMenuList().veil(7f).veil());
        assertEquals(0f, new MMenuList().veil(Float.NaN).veil());
    }

    @Test
    void aHeldRowIsMarkedDifferentlyFromBothSelectedAndPlain() {
        MMenuList heldList = list().cursor(1).held(1);
        RasterUiFixture held = paint(heldList);
        RasterUiFixture selected = paint(list().cursor(1));
        RasterUiFixture plain = paint(list().cursor(3));
        int[] b = box(heldList.rowRect(1));
        assertTrue(held.diff(selected, b[0], b[1], b[2], b[3]) > 1000);
        assertTrue(held.diff(plain, b[0], b[1], b[2], b[3]) > 1000);
        assertEquals(0, held.countExactly(MStyle.ROW_CURRENT, 0, 0, W, H),
                "while a submenu is open the live cursor is in it, not here");
        assertEquals(-1, list().held(99).held(), "an out-of-range held row is no held row");

        // The held hand is still: two phases paint identically.
        MMenuList moved = list().cursor(1).held(1);
        moved.update(0.2f);
        assertEquals(0, held.diff(paint(moved), 0, 0, W, H));
    }

    @Test
    void theCursorBobsWithUpdate() {
        MMenuList a = list();
        MMenuList b = list();
        b.update(0.2f);
        int[] r = box(a.rowRect(0));
        assertTrue(paint(a).diff(paint(b), r[0], r[1], r[0] + 44, r[3]) > 0, "the hand moved");
        assertEquals(0, paint(a).diff(paint(b), r[0] + 44, r[1], r[2], r[3]), "and only the hand");
    }

    @Test
    void everyAdornmentKindPaintsOnTheRight() {
        Adornment[] kinds = {Adornment.CHEVRON, Adornment.chevron(JADE), Adornment.tag("READY", MStyle.TEXT_ACCENT),
                Adornment.pips(3, JADE, true), Adornment.pips(3, JADE, false), Adornment.count("x3")};
        RasterUiFixture bare = paint(new MMenuList().rows(List.of(new Row("Row"), new Row("Other")))
                .scale(1f).cursor(1).bounds(10, 10, 250, 82));
        List<RasterUiFixture> painted = new ArrayList<>();
        for (Adornment kind : kinds) {
            MMenuList list = new MMenuList().rows(List.of(new Row("Row", true, kind), new Row("Other")))
                    .scale(1f).cursor(1).bounds(10, 10, 250, 82);
            RasterUiFixture fx = paint(list);
            int[] b = box(list.rowRect(0));
            assertTrue(fx.diff(bare, b[2] - 80, b[1], b[2], b[3]) > 20, kind + " paints at the right of its row");
            assertEquals(0, fx.diff(bare, b[0], b[1], b[0] + 100, b[3]), kind + " leaves the label alone");
            painted.add(fx);
        }
        for (int i = 0; i < painted.size(); i++) {
            for (int j = i + 1; j < painted.size(); j++) {
                assertTrue(painted.get(i).diff(painted.get(j), 0, 0, W, H) > 0,
                        kinds[i] + " and " + kinds[j] + " look different");
            }
        }
    }

    @Test
    void pipsUseTheCallersColourUnlessUnaffordable() {
        MMenuList can = new MMenuList().rows(List.of(new Row("Art", true, Adornment.pips(2, JADE, true))))
                .scale(1f).active(false).bounds(10, 10, 250, 50);
        MMenuList cannot = new MMenuList().rows(List.of(new Row("Art", true, Adornment.pips(2, JADE, false))))
                .scale(1f).active(false).bounds(10, 10, 250, 50);
        assertTrue(paint(can).countExactly(JADE, 0, 0, W, H) > 30);
        assertEquals(0, paint(cannot).countExactly(JADE, 0, 0, W, H));
        assertTrue(paint(cannot).countExactly(MStyle.TEXT_DISABLED, 0, 0, W, H) > 30);
    }

    @Test
    void aDisabledRowIsDimmed() {
        List<Row> enabled = List.of(new Row("Meditate", true, Adornment.count("x2")));
        List<Row> disabled = List.of(new Row("Meditate", false, Adornment.count("x2")));
        MMenuList on = new MMenuList().rows(enabled).scale(1f).active(false).bounds(10, 10, 250, 50);
        MMenuList off = new MMenuList().rows(disabled).scale(1f).active(false).bounds(10, 10, 250, 50);
        RasterUiFixture a = paint(on), b = paint(off);
        assertTrue(a.diff(b, 0, 0, W, H) > 50);
        assertTrue(a.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H) > 50);
        assertEquals(0, b.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H), "label and count both dim");
        assertTrue(b.countExactly(MStyle.TEXT_DISABLED, 0, 0, W, H) > 50);
    }

    @Test
    void aGlowingRowIsLitInTheCallersColour() {
        MMenuList plain = list().active(false);
        MMenuList glowing = list().active(false).rowGlow(2, MStyle.TEXT_ACCENT, 0.7f);
        RasterUiFixture a = paint(plain), b = paint(glowing);
        int[] r = box(plain.rowRect(2));
        assertTrue(a.diff(b, r[0], r[1], r[2], r[3]) > 1000);
        int[] other = box(plain.rowRect(0));
        assertEquals(0, a.diff(b, other[0], other[1], other[2], other[3]), "only that row");
        assertEquals(0, a.diff(paint(list().active(false).rowGlow(2, MStyle.TEXT_ACCENT, 0f)), 0, 0, W, H),
                "zero intensity removes the glow");
        assertTrue(b.diff(paint(list().active(false).rowGlow(2, MStyle.TEXT_ACCENT, 0.2f)), 0, 0, W, H) > 0,
                "intensity matters");
    }

    @Test
    void theAccentAndTheFrameAreOptional() {
        RasterUiFixture framed = paint(list().active(false));
        RasterUiFixture accented = paint(list().active(false).accent(JADE));
        RasterUiFixture frameless = paint(list().active(false).frame(false));
        assertTrue(framed.diff(accented, 10, 10, 260, 18) > 100, "accent hairline along the top");
        assertTrue(framed.diff(frameless, 0, 0, W, H) > 10000);
        assertTrue(frameless.countPainted(0, 0, W, H) > 100, "the rows still draw without a frame");
        assertEquals(0, paint(list().active(false).frame(false).veil(1f)).diff(frameless, 0, 0, W, H),
                "a frameless list has no frame to veil");
    }

    @Test
    void nineMixedRowsNeedNoWidgetChange() {
        List<Row> rows = List.of(
                new Row("Strike"),
                new Row("Arts", true, Adornment.CHEVRON),
                new Row("Focus", true, Adornment.tag("READY", MStyle.TEXT_ACCENT)),
                new Row("Meditate", true, Adornment.count("x2")),
                new Row("Flurry", true, Adornment.pips(2, JADE, true)),
                new Row("Stunning Strike", false, Adornment.pips(3, JADE, false)),
                new Row("Buy potions", true, Adornment.count("12g")),
                new Row("A very long dialogue choice that has to shrink to fit", true, Adornment.tag("NEW", JADE)),
                new Row("Leave", false));
        MMenuList list = new MMenuList().rows(rows).scale(1f).cursor(7).rowGlow(2, MStyle.TEXT_ACCENT, 0.7f)
                .bounds(10, 10, 280, 240);
        RasterUiFixture fx = paint(list);
        RasterUiFixture empty = paint(new MMenuList().scale(1f).bounds(10, 10, 280, 240));
        for (int i = 0; i < rows.size(); i++) {
            float[] r = list.rowRect(i);
            assertTrue(r[3] > 0f && r[1] + r[3] <= 250f, "row " + i + " is inside the frame");
            int[] b = box(r);
            assertTrue(fx.diff(empty, b[0], b[1], b[2], b[3]) > 30, "row " + i + " paints");
            assertEquals(i, list.rowAt(r[0] + 3f, r[1] + 3f));
        }
        assertEquals(rows.get(7), list.selectedRow());
    }

    // ── Scale, determinism, degenerate input ─────────────────────────────────

    @Test
    void scaleScalesThePaintedRows() {
        MMenuList small = new MMenuList().rows(fourRows()).scale(1f).bounds(0, 0, 300, 250);
        MMenuList big = new MMenuList().rows(fourRows()).scale(1.5f).bounds(0, 0, 300, 250);
        RasterUiFixture a = paint(small), b = paint(big);
        int[] r = box(big.rowRect(0));
        assertTrue(b.countExactly(MStyle.ROW_CURRENT, r[0], r[1], r[2], r[3])
                > a.countExactly(MStyle.ROW_CURRENT, 0, 0, W, H) * 1.2f, "the selected row grew");
        assertTrue(b.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H) > a.countExactly(MStyle.TEXT_PRIMARY, 0, 0, W, H),
                "and so did the text");
    }

    @Test
    void twoInstancesFedTheSameUpdatesPaintIdentically() {
        MMenuList a = list().cursor(1).rowGlow(2, MStyle.TEXT_ACCENT, 0.8f);
        MMenuList b = list().cursor(1).rowGlow(2, MStyle.TEXT_ACCENT, 0.8f);
        float[] steps = {0.016f, 0.033f, 0.25f, 1.7f, 0.001f};
        for (float dt : steps) {
            a.update(dt);
            b.update(dt);
        }
        assertEquals(0, paint(a).diff(paint(b), 0, 0, W, H));
        RasterUiFixture once = paint(a);
        assertEquals(0, once.diff(paint(a), 0, 0, W, H), "painting does not advance anything");
    }

    @Test
    void hugeAndBadTimeStepsAreSafe() {
        MMenuList a = list();
        MMenuList untouched = list();
        a.update(Float.NaN);
        a.update(-3f);
        a.update(Float.POSITIVE_INFINITY);
        a.update(0f);
        assertEquals(0, paint(a).diff(paint(untouched), 0, 0, W, H), "bad steps are ignored");
        a.update(1.0e9f);
        a.update(3600f);
        assertDoesNotThrow(() -> paint(a));
        int[] r = box(a.rowRect(0));
        assertTrue(paint(a).countExactly(MStyle.TEXT_PRIMARY, r[0], r[1], r[0] + 44, r[3]) > 60,
                "the cursor is still in its gutter after an hour");
    }

    @Test
    void degenerateBoundsDrawNothingAndNeverThrow() {
        float[][] bad = {{0, 0, 0, 0}, {10, 10, -5, 40}, {10, 10, 40, -5}, {Float.NaN, 0, 100, 100},
                {0, 0, Float.NaN, 100}, {0, 0, 100, Float.POSITIVE_INFINITY}};
        for (float[] b : bad) {
            MMenuList list = new MMenuList().rows(fourRows()).scale(1f).bounds(b[0], b[1], b[2], b[3]);
            RasterUiFixture fx = new RasterUiFixture(W, H);
            assertDoesNotThrow(() -> list.render(fx.ui));
            assertEquals(0, fx.countPainted(0, 0, W, H));
            assertEquals(-1, list.rowAt(5, 5));
            assertArrayEquals(new float[4], list.rowRect(0));
            assertNotNull(list.anchorRightOf(0));
        }
        assertDoesNotThrow(() -> new MMenuList().rows(fourRows()).bounds(0, 0, 100, 100).render(null));
        assertArrayEquals(new float[4], list().rowRect(-1));
        assertArrayEquals(new float[4], list().rowRect(4));

        // Tiny frames: rows may vanish, nothing throws, nothing escapes the frame.
        MMenuList tiny = new MMenuList().rows(fourRows()).scale(1f).bounds(100, 100, 6, 6);
        RasterUiFixture fx = paint(tiny);
        assertEquals(0, fx.countPainted(0, 0, 99, H));
        assertEquals(0, fx.countPainted(0, 0, W, 99));
    }

    @Test
    void anEmptyListPaintsJustItsFrame() {
        RasterUiFixture fx = paint(new MMenuList().scale(1f).bounds(10, 10, 250, 146));
        assertTrue(fx.countPainted(10, 10, 260, 156) > 30000);
        assertEquals(0, fx.countExactly(MStyle.ROW_CURRENT, 0, 0, W, H));
    }
}
