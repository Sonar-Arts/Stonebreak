package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MChipRow}: packing from either edge, dropping what does not fit, never overflowing. */
class MChipRowTest {

    private static final int W = 360;
    private static final int H = 64;
    private static final int[] COLORS = {0xFF8FD8FF, 0xFF5EC46A, 0xFFE5484D, 0xFFFFCC55};

    private static List<MBadge> chips(String... labels) {
        List<MBadge> out = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) out.add(new MBadge(labels[i]).outlined(COLORS[i % COLORS.length]));
        return out;
    }

    private static MChipRow row(MChipRow.Align align) {
        return new MChipRow().chips(chips("Chilled", "Haste", "Guard", "Surge")).align(align)
                .scale(1f).bounds(20, 20, 320, 24);
    }

    @Test
    void rightAlignedChipsPackFromTheRightEdgeInListOrder() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        List<float[]> rects = row(MChipRow.Align.RIGHT).chipRects(fx.ui);
        assertEquals(4, rects.size());
        assertEquals(340f, rects.get(0)[0] + rects.get(0)[2], 0.001f, "the first chip touches the right edge");
        for (int i = 1; i < rects.size(); i++) {
            float[] prev = rects.get(i - 1), cur = rects.get(i);
            assertEquals(prev[0] - 5f, cur[0] + cur[2], 0.001f, "5px gap, growing leftwards");
            assertEquals(20f, cur[3]);
            assertEquals(22f, cur[1], "20px chips centred in the 24px row");
        }
    }

    @Test
    void leftAlignedChipsPackFromTheLeftEdge() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        MChipRow row = row(MChipRow.Align.LEFT).gap(8f);
        List<float[]> rects = row.chipRects(fx.ui);
        assertEquals(4, rects.size());
        assertEquals(20f, rects.get(0)[0]);
        for (int i = 1; i < rects.size(); i++) {
            assertEquals(rects.get(i - 1)[0] + rects.get(i - 1)[2] + 8f, rects.get(i)[0], 0.001f);
        }
        assertEquals(rects.get(3)[0] + rects.get(3)[2], row.contentEdge(fx.ui), 0.001f);
    }

    @Test
    void chipsThatDoNotFitAreDroppedFromTheTailNeverOverflowing() {
        RasterUiFixture probe = new RasterUiFixture(W, H);
        List<float[]> all = row(MChipRow.Align.RIGHT).chipRects(probe.ui);
        // A limit that cuts through the third chip: two survive.
        float limit = all.get(2)[0] + 3f;
        for (MChipRow.Align align : MChipRow.Align.values()) {
            MChipRow row = row(align);
            float cut = align == MChipRow.Align.RIGHT ? limit : 20f + (340f - limit);
            row.limit(cut);
            RasterUiFixture fx = new RasterUiFixture(W, H);
            List<float[]> rects = row.chipRects(fx.ui);
            assertEquals(2, rects.size(), align + ": the first two chips, in priority order");
            assertEquals(2, row.visibleCount(fx.ui));
            row.render(fx.ui);
            if (align == MChipRow.Align.RIGHT) {
                assertEquals(0, fx.countPainted(0, 0, (int) Math.floor(cut), H), "nothing crosses minX");
                assertEquals(rects.get(1)[0], row.contentEdge(fx.ui));
            } else {
                assertEquals(0, fx.countPainted((int) Math.ceil(cut) + 1, 0, W, H), "nothing crosses the limit");
            }
        }
    }

    @Test
    void minXIsTheLimitAndALimitOutsideTheRowIsClampedToIt() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        assertEquals(row(MChipRow.Align.RIGHT).limit(200f).visibleCount(fx.ui),
                row(MChipRow.Align.RIGHT).minX(200f).visibleCount(fx.ui));
        MChipRow narrow = row(MChipRow.Align.RIGHT).bounds(200, 20, 100, 24).limit(-500f);
        for (float[] r : narrow.chipRects(fx.ui)) assertTrue(r[0] >= 200f, "never left of the row itself");
        assertTrue(narrow.visibleCount(fx.ui) < 4);
        assertEquals(0, row(MChipRow.Align.RIGHT).limit(339f).visibleCount(fx.ui), "no room: no chips");
        assertEquals(340f, row(MChipRow.Align.RIGHT).limit(339f).contentEdge(fx.ui), "and the edge stays put");
    }

    @Test
    void renderPaintsExactlyTheChipsThatFit() {
        MChipRow row = row(MChipRow.Align.RIGHT);
        RasterUiFixture fx = new RasterUiFixture(W, H);
        row.render(fx.ui);
        List<float[]> rects = row.chipRects(fx.ui);
        for (float[] r : rects) {
            assertTrue(fx.countPainted((int) r[0], (int) r[1], (int) (r[0] + r[2]), (int) (r[1] + r[3])) > 200);
        }
        float left = rects.get(3)[0];
        assertEquals(0, fx.countPainted(0, 0, (int) Math.floor(left), H), "nothing left of the last chip");

        // The same chips drawn by hand at those rects give the same picture.
        RasterUiFixture byHand = new RasterUiFixture(W, H);
        List<MBadge> manual = chips("Chilled", "Haste", "Guard", "Surge");
        for (int i = 0; i < manual.size(); i++) {
            float[] r = rects.get(i);
            manual.get(i).scale(1f).bounds(r[0], r[1], r[2], r[3]).render(byHand.ui);
        }
        assertEquals(0, fx.diff(byHand, 0, 0, W, H));
    }

    @Test
    void scaleScalesChipsGapsAndHeights() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        List<float[]> one = new MChipRow().chips(chips("Chilled", "Haste")).scale(1f).bounds(0, 0, 360, 60).chipRects(fx.ui);
        List<float[]> two = new MChipRow().chips(chips("Chilled", "Haste")).scale(2f).bounds(0, 0, 360, 60).chipRects(fx.ui);
        assertEquals(20f, one.get(0)[3]);
        assertEquals(40f, two.get(0)[3]);
        assertTrue(two.get(0)[2] > one.get(0)[2] * 1.7f, "chip text scaled with the row");
        assertEquals(5f, one.get(0)[0] - (one.get(1)[0] + one.get(1)[2]), 0.001f);
        assertEquals(10f, two.get(0)[0] - (two.get(1)[0] + two.get(1)[2]), 0.001f);
        // A row shorter than the design chip height shrinks the chips to fit it.
        assertEquals(12f, new MChipRow().chips(chips("A")).scale(1f).bounds(0, 0, 100, 12).chipRects(fx.ui).get(0)[3]);
    }

    @Test
    void layoutIsRepeatableAndDeterministic() {
        RasterUiFixture a = new RasterUiFixture(W, H), b = new RasterUiFixture(W, H);
        MChipRow row = row(MChipRow.Align.RIGHT).limit(150f);
        row.render(a.ui);
        row.render(a.ui);
        row(MChipRow.Align.RIGHT).limit(150f).render(b.ui);
        row(MChipRow.Align.RIGHT).limit(150f).render(b.ui);
        assertEquals(0, a.diff(b, 0, 0, W, H));
    }

    @Test
    void degenerateInputIsSafe() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        List<MBadge> withNull = new ArrayList<>(chips("A"));
        withNull.add(null);
        assertEquals(1, new MChipRow().chips(withNull).chips().size());
        assertDoesNotThrow(() -> new MChipRow().chips(null).bounds(0, 0, 100, 20).render(fx.ui));
        assertDoesNotThrow(() -> new MChipRow().bounds(0, 0, 100, 20).render(fx.ui));
        assertDoesNotThrow(() -> row(MChipRow.Align.RIGHT).render(null));
        float[][] bad = {{0, 0, 0, 0}, {0, 0, -10, 20}, {0, 0, 100, -1}, {Float.NaN, 0, 100, 20}, {0, 0, Float.NaN, 20},
                {0, 0, Float.POSITIVE_INFINITY, 20}};
        for (float[] r : bad) {
            MChipRow row = row(MChipRow.Align.RIGHT).bounds(r[0], r[1], r[2], r[3]);
            assertDoesNotThrow(() -> row.render(fx.ui));
            assertEquals(0, row.visibleCount(fx.ui));
        }
        assertEquals(0, fx.countPainted(0, 0, W, H));
        assertEquals(4, row(MChipRow.Align.RIGHT).limit(Float.NaN).gap(Float.NaN).chipHeight(-3f).align(null)
                .visibleCount(fx.ui), "bad settings fall back to the defaults");
    }
}
