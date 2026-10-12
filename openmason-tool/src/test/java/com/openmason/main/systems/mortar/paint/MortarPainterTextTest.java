package com.openmason.main.systems.mortar.paint;

import com.openmason.main.systems.mortar.MortarRasterFixture;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import io.github.humbleui.skija.Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text layout in the painter: ellipsis truncation, word wrap, wrapped height and horizontal
 * anchoring. Uses the real JetBrains Mono font (monospaced, so widths are predictable) on a
 * CPU-raster canvas.
 */
class MortarPainterTextTest {

    private static final float SIZE = 12f;

    private MortarRasterFixture fx;
    private Font font;
    private float charW;

    @BeforeEach
    void setUp() {
        fx = new MortarRasterFixture(200, 60);
        font = fx.fonts.get(Weight.REGULAR, SIZE);
        charW = font.measureTextWidth("M");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    // ---- ellipsize -------------------------------------------------------

    @Test
    void textThatFitsIsLeftAlone() {
        String s = "hello";
        assertEquals(s, MortarPainter.ellipsize(s, font, font.measureTextWidth(s)));
        assertEquals(s, MortarPainter.ellipsize(s, font, 1000f));
    }

    @Test
    void overflowingTextKeepsTheLongestPrefixThatFitsWithAnEllipsis() {
        String s = "abcdefghijklmnopqrstuvwxyz";
        float max = charW * 10.5f; // room for 10 glyphs: 7 letters + "..."
        String fitted = MortarPainter.ellipsize(s, font, max);
        assertEquals("abcdefg...", fitted);
        assertTrue(font.measureTextWidth(fitted) <= max);

        // Maximal: one more character would not fit.
        String longer = s.substring(0, 8) + "...";
        assertTrue(font.measureTextWidth(longer) > max);
    }

    @Test
    void roomForOnlyTheEllipsisGivesJustTheEllipsis() {
        float ellipsisW = font.measureTextWidth("...");
        assertEquals("...", MortarPainter.ellipsize("abcdef", font, ellipsisW));
    }

    @Test
    void tooNarrowForTheEllipsisGivesNothing() {
        assertEquals("", MortarPainter.ellipsize("abcdef", font, charW * 2f));
        assertEquals("", MortarPainter.ellipsize("abcdef", font, 0f));
    }

    // ---- wrap ------------------------------------------------------------

    @Test
    void wordsWrapAtTheWidth() {
        float max = charW * 11.5f; // "alpha beta" (10) fits, "alpha beta gamma" (16) does not
        List<String> lines = MortarPainter.wrap("alpha beta gamma delta", font, max, 5);
        assertEquals(List.of("alpha beta", "gamma delta"), lines);
        for (String line : lines) {
            assertTrue(font.measureTextWidth(line) <= max, line);
        }
    }

    @Test
    void wrapStopsAtMaxLinesAndDropsTheRest() {
        List<String> lines = MortarPainter.wrap("one two three four five", font, charW * 5.5f, 2);
        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    void anOverlongWordGetsItsOwnLineRatherThanVanishing() {
        List<String> lines = MortarPainter.wrap("a supercalifragilistic b", font, charW * 4f, 5);
        assertEquals(List.of("a", "supercalifragilistic", "b"), lines);
    }

    @Test
    void runsOfWhitespaceCollapse() {
        List<String> lines = MortarPainter.wrap("  alpha \t  beta\n gamma  ", font, 1000f, 3);
        assertEquals(List.of("alpha beta gamma"), lines);
    }

    @Test
    void blankTextWrapsToNothing() {
        assertTrue(MortarPainter.wrap("   ", font, 100f, 3).isEmpty());
    }

    // ---- textWrapped / measureWidth --------------------------------------

    @Test
    void wrappedHeightIsLinesTimesLineHeight() {
        MortarPainter g = fx.painter;
        float lineH = SIZE * 1.35f;
        assertEquals(lineH, g.textWrapped("short", 0, 0, 1000f, Weight.REGULAR, SIZE, 0xFFFFFFFF, 3), 1e-4f);
        assertEquals(2 * lineH, g.textWrapped("one two three four five", 0, 0, charW * 5.5f,
                Weight.REGULAR, SIZE, 0xFFFFFFFF, 2), 1e-4f);
        assertEquals(0f, g.textWrapped("", 0, 0, 100f, Weight.REGULAR, SIZE, 0xFFFFFFFF, 3));
        assertEquals(0f, g.textWrapped(null, 0, 0, 100f, Weight.REGULAR, SIZE, 0xFFFFFFFF, 3));
        assertEquals(0f, g.textWrapped("text", 0, 0, 100f, Weight.REGULAR, SIZE, 0xFFFFFFFF, 0));
    }

    @Test
    void measureWidthOfNothingIsZero() {
        assertEquals(0f, fx.painter.measureWidth(null, Weight.REGULAR, SIZE));
        assertEquals(0f, fx.painter.measureWidth("", Weight.REGULAR, SIZE));
        assertEquals(font.measureTextWidth("abc"), fx.painter.measureWidth("abc", Weight.REGULAR, SIZE));
    }

    // ---- anchoring -------------------------------------------------------

    @Test
    void alignmentAnchorsTheSameRunAtLeftCentreOrRightEdge() {
        MortarPainter g = fx.painter;
        String s = "Mortar";
        float w = g.measureWidth(s, Weight.REGULAR, SIZE);

        g.text(s, 40f, 30f, MortarPainter.Align.LEFT, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        int[] left = fx.snapshot();
        assertFalse(isBlank(left), "the run was drawn");

        fx.clear();
        g.text(s, 40f + w / 2f, 30f, MortarPainter.Align.CENTER, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        assertArrayEquals(left, fx.snapshot(), "CENTER at x+w/2 == LEFT at x");

        fx.clear();
        g.text(s, 40f + w, 30f, MortarPainter.Align.RIGHT, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        assertArrayEquals(left, fx.snapshot(), "RIGHT at x+w == LEFT at x");
    }

    @Test
    void ellipsizedTextNeverPaintsPastItsWidth() {
        float maxW = charW * 8f;
        fx.painter.textEllipsized("a long label that overflows", 10f, 30f, maxW,
                Weight.REGULAR, SIZE, 0xFFFFFFFF);
        int limit = (int) Math.ceil(10f + maxW) + 1;
        for (int y = 0; y < 60; y++) {
            for (int x = limit; x < 200; x++) {
                assertEquals(MortarRasterFixture.CLEAR, fx.at(x, y), "painted at " + x + "," + y);
            }
        }
    }

    @Test
    void nullOrEmptyTextDrawsNothing() {
        fx.painter.text(null, 10, 30, MortarPainter.Align.LEFT, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        fx.painter.text("", 10, 30, MortarPainter.Align.LEFT, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        fx.painter.textEllipsized(null, 10, 30, 100, Weight.REGULAR, SIZE, 0xFFFFFFFF);
        assertTrue(isBlank(fx.snapshot()));
    }

    private static boolean isBlank(int[] pixels) {
        for (int p : pixels) {
            if (p != MortarRasterFixture.CLEAR) {
                return false;
            }
        }
        return true;
    }
}
