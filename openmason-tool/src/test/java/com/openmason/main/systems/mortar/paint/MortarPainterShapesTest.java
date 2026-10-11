package com.openmason.main.systems.mortar.paint;

import com.openmason.main.systems.mortar.MortarRasterFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry contracts of the painter's shape primitives, read back from a CPU-raster canvas. */
class MortarPainterShapesTest {

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    private MortarRasterFixture fx;
    private MortarPainter g;

    @BeforeEach
    void setUp() {
        fx = new MortarRasterFixture(100, 80);
        g = fx.painter;
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void strokeIsInsetSoItStaysInsideTheRect() {
        g.strokeRoundRect(10, 10, 40, 30, 0f, 4f, RED);
        assertEquals(RED, fx.at(10, 25), "outer edge column is stroked");
        assertEquals(RED, fx.at(13, 25), "a 4 px stroke covers 4 columns");
        assertEquals(MortarRasterFixture.CLEAR, fx.at(14, 25), "interior stays empty");
        assertEquals(RED, fx.at(49, 25), "right edge column is stroked");
        assertPaintedOnlyWithin(10, 10, 50, 40);
    }

    @Test
    void fillRoundRectCoversItsInteriorExactly() {
        g.fillRoundRect(20, 20, 30, 20, 6f, BLUE);
        assertEquals(BLUE, fx.at(35, 30));
        assertEquals(BLUE, fx.at(20, 30), "straight edges are pixel-exact on integer coordinates");
        assertTrue(fx.alphaAt(20, 20) < 255, "corners are rounded");
        assertPaintedOnlyWithin(20, 20, 50, 40);
    }

    @Test
    void clipConfinesDrawingUntilPopped() {
        g.pushClip(10, 10, 20, 20, 0f);
        g.fillRect(0, 0, 100, 80, RED);
        g.popClip();
        assertPaintedOnlyWithin(10, 10, 30, 30);

        g.fillRect(60, 60, 10, 10, BLUE);
        assertEquals(BLUE, fx.at(65, 65), "after popClip drawing is unclipped again");
    }

    @Test
    void shadowWithoutBlurPaintsNothing() {
        g.dropShadow(10, 10, 40, 30, 4f, 3f, 0f, RED);
        g.dropShadow(10, 10, 40, 30, 4f, 3f, -2f, RED);
        assertPaintedOnlyWithin(0, 0, 0, 0);
    }

    @Test
    void shadowIsOffsetDownwardAndSoft() {
        g.dropShadow(20, 10, 40, 30, 4f, 6f, 2f, 0xFF000000);
        assertEquals(255, fx.alphaAt(40, 30), "centre of the offset rect is solid");
        assertTrue(fx.alphaAt(40, 44) > 0, "shadow reaches below the rect's own bottom (offset)");
        assertTrue(fx.alphaAt(40, 12) < fx.alphaAt(40, 20), "top edge is softened");
    }

    @Test
    void gradientRunsAlongTheRequestedAxis() {
        g.gradientRoundRect(0, 0, 100, 80, 0f, new int[]{RED, BLUE}, true);
        assertTrue(MortarRasterFixture.near(RED, fx.at(50, 0), 6), "top is the first stop");
        assertTrue(MortarRasterFixture.near(BLUE, fx.at(50, 79), 6), "bottom is the last stop");
        assertEquals(fx.at(0, 40), fx.at(99, 40), "vertical gradient is constant across a row");

        fx.clear();
        g.gradientRoundRect(0, 0, 100, 80, 0f, new int[]{RED, BLUE}, false);
        assertTrue(MortarRasterFixture.near(RED, fx.at(0, 40), 6), "left is the first stop");
        assertTrue(MortarRasterFixture.near(BLUE, fx.at(99, 40), 6), "right is the last stop");
        assertEquals(fx.at(50, 0), fx.at(50, 79), "horizontal gradient is constant down a column");
    }

    /** Every pixel outside [x0,x1)x[y0,y1) is still clear. */
    private void assertPaintedOnlyWithin(int x0, int y0, int x1, int y1) {
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 100; x++) {
                boolean inside = x >= x0 && x < x1 && y >= y0 && y < y1;
                if (!inside) {
                    assertEquals(MortarRasterFixture.CLEAR, fx.at(x, y), "painted outside at " + x + "," + y);
                }
            }
        }
    }
}
