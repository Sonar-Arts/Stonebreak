package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text rendering of pixel buffers: glyph grid, legend, stats, and the round trip back. */
@Tag("unit")
class PixelTextCodecTest {

    private static final int RED = PixelTextCodec.pack(255, 0, 0, 255);
    private static final int RED_ISH = PixelTextCodec.pack(250, 4, 3, 255);
    private static final int BLUE = PixelTextCodec.pack(0, 0, 255, 255);
    private static final int CLEAR = 0;

    /** 4x3: a red bar on top, a blue dot, one lonely blue orphan in the corner. */
    private static int[] sample() {
        return new int[]{
                RED, RED, RED, RED,
                CLEAR, BLUE, BLUE, CLEAR,
                CLEAR, CLEAR, CLEAR, BLUE,
        };
    }

    @Test
    void gridLegendAndCountsMostFrequentFirst() {
        PixelTextCodec.Result r = PixelTextCodec.describe(sample(), 4, 0, 0, 4, 3,
                PixelTextCodec.Options.defaults());

        assertEquals(List.of("AAAA", ".BB.", "...B"), r.rows());
        assertEquals(2, r.legendSize());
        assertEquals(2, r.distinctColors());
        assertEquals(7, r.opaquePixels());
        assertEquals(5, r.transparentPixels());

        PixelTextCodec.LegendEntry a = r.legend().get(0);
        assertEquals("A", a.glyph());
        assertEquals("#ff0000", a.hex());
        assertEquals("red", a.name());
        assertEquals(4, a.count());
        assertEquals(57.1, a.percent(), 0.01);

        PixelTextCodec.LegendEntry b = r.legend().get(1);
        assertEquals("B", b.glyph());
        assertEquals("blue", b.name());
        assertEquals(3, b.count());
    }

    @Test
    void boundsOrphansAndSymmetry() {
        PixelTextCodec.Result r = PixelTextCodec.describe(sample(), 4, 0, 0, 4, 3,
                PixelTextCodec.Options.defaults());

        assertEquals(new PixelTextCodec.Bounds(0, 0, 4, 3), r.opaqueBounds());
        assertEquals(1, r.orphanPixels());
        assertArrayEquals(new int[]{3, 2}, r.orphanSample());
        // Rows 0 and 1 mirror left-right; row 2's lone pixel does not.
        assertTrue(r.symmetry().horizontal() < 1.0);
        assertTrue(r.symmetry().horizontal() > 0.5);
    }

    @Test
    void rulersAndRleCarryAbsoluteCoordinates() {
        PixelTextCodec.Options opt = new PixelTextCodec.Options(0, PixelTextCodec.MAX_COLORS, 8, true, true);
        // Sub-rectangle starting at (1,1) of the 4x3 buffer.
        PixelTextCodec.Result r = PixelTextCodec.describe(sample(), 4, 1, 1, 3, 2, opt);

        assertEquals(List.of("AA.", "..A"), r.rows());
        assertEquals(new PixelTextCodec.Bounds(1, 1, 3, 2), r.opaqueBounds());
        assertEquals(List.of("y1: A@1-2", "y2: A@3"), r.rle());
        assertTrue(r.grid().startsWith("  123\n1|AA.\n2|..A\n"), r.grid());
    }

    @Test
    void toleranceMergesNearShadesAndReportsMergeCount() {
        int[] px = {RED, RED_ISH, RED_ISH, BLUE};
        PixelTextCodec.Result strict = PixelTextCodec.describe(px, 4, 0, 0, 4, 1,
                PixelTextCodec.Options.defaults());
        assertEquals(3, strict.legendSize());

        PixelTextCodec.Result merged = PixelTextCodec.describe(px, 4, 0, 0, 4, 1,
                new PixelTextCodec.Options(16, PixelTextCodec.MAX_COLORS, 8, false, false));
        assertEquals(2, merged.legendSize());
        assertEquals(3, merged.distinctColors(), "distinct count is pre-merge");
        assertEquals(List.of("AAAB"), merged.rows());
        assertEquals(1, merged.legend().get(0).merged());
        assertEquals(3, merged.legend().get(0).count());
    }

    @Test
    void maxColorsCapsGlyphsByMergingToNearest() {
        int[] px = {RED, RED_ISH, BLUE, BLUE};
        PixelTextCodec.Result r = PixelTextCodec.describe(px, 4, 0, 0, 4, 1,
                new PixelTextCodec.Options(0, 2, 8, false, false));
        assertEquals(2, r.legendSize());
        // RED_ISH is the least frequent; it must land on RED, not BLUE.
        assertEquals(List.of("BBAA").get(0).length(), r.rows().get(0).length());
        char redGlyph = r.rows().get(0).charAt(0);
        assertEquals(redGlyph, r.rows().get(0).charAt(1));
    }

    @Test
    void alphaThresholdAndTranslucentNaming() {
        int faint = PixelTextCodec.pack(0, 255, 0, 4);
        int half = PixelTextCodec.pack(0, 255, 0, 128);
        PixelTextCodec.Result r = PixelTextCodec.describe(new int[]{faint, half}, 2, 0, 0, 2, 1,
                PixelTextCodec.Options.defaults());
        assertEquals(List.of(".A"), r.rows());
        assertEquals("translucent green", r.legend().get(0).name());
        assertEquals("#00ff0080", r.legend().get(0).hex());
    }

    @Test
    void hexRowsAreExactAndIgnoreTolerance() {
        int half = PixelTextCodec.pack(0, 255, 0, 128);
        int[] px = {RED, RED_ISH, CLEAR, half};
        PixelTextCodec.Options opt = new PixelTextCodec.Options(16, PixelTextCodec.MAX_COLORS, 8, false, false, true);
        PixelTextCodec.Result r = PixelTextCodec.describe(px, 2, 0, 0, 2, 2, opt);
        assertEquals(List.of("AA", ".B"), r.rows(), "tolerance merges the glyph grid");
        assertEquals(List.of("y0|ff0000 fa0403", "y1|...... 00ff0080"), r.hexRows());
        assertNull(PixelTextCodec.describe(px, 2, 0, 0, 2, 2, PixelTextCodec.Options.defaults()).hexRows());
    }

    @Test
    void emptyRegionHasNoBoundsAndPerfectSymmetry() {
        PixelTextCodec.Result r = PixelTextCodec.describe(new int[4], 2, 0, 0, 2, 2,
                PixelTextCodec.Options.defaults());
        assertNull(r.opaqueBounds());
        assertEquals(0, r.legendSize());
        assertEquals(1.0, r.symmetry().horizontal());
        assertEquals(1.0, r.symmetry().vertical());
    }

    @Test
    void colorNamesAreRoughButSensible() {
        assertEquals("white", PixelTextCodec.colorName(255, 255, 255, 255));
        assertEquals("black", PixelTextCodec.colorName(0, 0, 0, 255));
        assertEquals("gray", PixelTextCodec.colorName(128, 128, 128, 255));
        assertEquals("dark gray", PixelTextCodec.colorName(40, 40, 40, 255));
        assertEquals("brown", PixelTextCodec.colorName(120, 80, 40, 255));
        assertEquals("light blue", PixelTextCodec.colorName(170, 200, 255, 255));
        assertEquals("dark green", PixelTextCodec.colorName(0, 80, 0, 255));
    }

    @Test
    void parseGridRoundTripsThroughLegend() {
        Map<Character, Integer> legend = PixelTextCodec.legendFrom(Map.of(
                "A", "#ff0000", "B", "0,0,255", "C", "#00ff0080"));
        int[] writes = PixelTextCodec.parseGrid(List.of("A.B", " C"), legend, 10, 20, false);
        // (10,20)=A, (12,20)=B, (11,21)=C; '.' and ' ' skipped.
        assertArrayEquals(new int[]{
                10, 20, 255, 0, 0, 255,
                12, 20, 0, 0, 255, 255,
                11, 21, 0, 255, 0, 128,
        }, writes);
    }

    @Test
    void parseGridClearDotsWritesTransparent() {
        int[] writes = PixelTextCodec.parseGrid(List.of("."), Map.of(), 0, 0, true);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, writes);
    }

    @Test
    void parseGridRejectsUnknownGlyphAndBadLegend() {
        assertThrows(IllegalArgumentException.class,
                () -> PixelTextCodec.parseGrid(List.of("Z"), Map.of(), 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PixelTextCodec.legendFrom(Map.of(".", "#000000")));
        assertThrows(IllegalArgumentException.class,
                () -> PixelTextCodec.legendFrom(Map.of("AB", "#000000")));
        assertThrows(IllegalArgumentException.class, () -> PixelTextCodec.parseColor("#12"));
    }

    @Test
    void describeRgbaAcceptsFlatRegionReads() {
        int[] rgba = {255, 0, 0, 255, 0, 0, 0, 0};
        PixelTextCodec.Result r = PixelTextCodec.describeRgba(rgba, 0, 0, 2, 1,
                PixelTextCodec.Options.defaults());
        assertEquals(List.of("A."), r.rows());
        assertNotNull(r.grid());
    }

    @Test
    void describeReflectsLegendOrderInGrid() {
        // Alphabet must never hand out '.' or ' '.
        assertEquals(-1, PixelTextCodec.GLYPHS.indexOf('.'));
        assertEquals(-1, PixelTextCodec.GLYPHS.indexOf(' '));
        assertEquals(PixelTextCodec.GLYPHS.length(), PixelTextCodec.GLYPHS.chars().distinct().count());
    }
}
