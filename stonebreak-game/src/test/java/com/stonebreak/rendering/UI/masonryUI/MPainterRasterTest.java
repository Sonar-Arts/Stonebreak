package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real pixels, no OpenGL: Skija rasterizes to a CPU bitmap, so MasonryUI's painting layer runs
 * headlessly end to end — draw with {@link MPainter}, then read the pixels back. This is the
 * proof-of-concept for testing MasonryUI rendering at all; anti-aliased edges blend, so probes
 * sit well inside or well outside shapes rather than on their boundaries.
 */
class MPainterRasterTest {

    private static final int SIZE = 64;
    private static final int BACKGROUND = 0xFF101010;
    private static final int RED = 0xFFFF0000;

    private final Bitmap bitmap = newBitmap();
    private final Canvas canvas = new Canvas(bitmap);

    private static Bitmap newBitmap() {
        Bitmap bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(SIZE, SIZE));
        return bitmap;
    }

    private int countPainted(int x0, int y0, int x1, int y1) {
        int painted = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (bitmap.getColor(x, y) != BACKGROUND) {
                    painted++;
                }
            }
        }
        return painted;
    }

    private void clear() {
        canvas.clear(BACKGROUND);
    }

    @Test
    void fillRectPaintsExactlyItsRectangle() {
        clear();
        MPainter.fillRect(canvas, 10, 10, 20, 20, RED);

        assertEquals(RED, bitmap.getColor(20, 20), "the interior takes the fill color");
        assertEquals(BACKGROUND, bitmap.getColor(5, 5), "outside stays untouched");
        assertEquals(BACKGROUND, bitmap.getColor(35, 20), "the fill stops at its right edge");
    }

    @Test
    void roundedCornersActuallyRound() {
        clear();
        MPainter.fillRoundedRect(canvas, 10, 10, 40, 40, 12, RED);

        assertEquals(RED, bitmap.getColor(30, 30), "the body is solid");
        assertEquals(BACKGROUND, bitmap.getColor(11, 11),
                "the corner pixel sits outside the radius and must stay background");
    }

    @Test
    void strokeRectPaintsTheEdgeAndLeavesTheInteriorOpen() {
        clear();
        MPainter.strokeRect(canvas, 10, 10, 40, 40, RED, 2);

        assertTrue(countPainted(8, 28, 13, 33) > 0, "the left edge carries the stroke");
        assertEquals(BACKGROUND, bitmap.getColor(30, 30), "a stroke must not fill");
    }

    @Test
    void theCraftingArrowStaysInsideItsBox() {
        clear();
        MPainter.craftingArrow(canvas, 16, 24, 32, 16, RED);

        assertTrue(countPainted(16, 24, 48, 40) > 20, "the glyph paints a visible arrow");
        assertEquals(0, countPainted(0, 0, SIZE, 22), "nothing bleeds above the box");
        assertEquals(0, countPainted(0, 42, SIZE, SIZE), "nothing bleeds below it");
    }

    @Test
    void theCraftingArrowHeadPointsRightAsASingleTip() {
        clear();
        MPainter.craftingArrow(canvas, 16, 24, 32, 16, RED);

        assertEquals(RED, bitmap.getColor(24, 32), "the shaft carries the fill colour");
        assertTrue(bitmap.getColor(47, 32) != BACKGROUND,
            "the arrow tip must touch the right edge (anti-aliased near the vertex)");
        assertEquals(BACKGROUND, bitmap.getColor(47, 29),
            "the head must taper above the tip (a triangle, not a diamond)");
        assertEquals(BACKGROUND, bitmap.getColor(47, 35),
            "the head must taper below the tip (a triangle, not a diamond)");
    }

    @Test
    void craftingArrowPlacementCentresBetweenGridAndOutputSlot() {
        float[] corner = MPainter.craftingArrowPlacement(100f, 156f, 100f, 40f, 20f);
        assertEquals(118f, corner[0], 0.001f, "horizontally centred in the 56px gap");
        assertEquals(110f, corner[1], 0.001f, "vertically centred on the output slot");
    }

    @Test
    void aPanelRendersOpaquelyWithinItsBounds() {
        clear();
        MPainter.panel(canvas, 8, 8, 48, 48);

        assertTrue(countPainted(16, 16, 48, 48) > 500,
                "the stone panel must actually cover its area");
        assertEquals(0, countPainted(0, 0, SIZE, 7), "and stay inside its bounds");
    }

    @Test
    void textRendersHeadlesslyWithTheGamesOwnTypeface() throws Exception {
        clear();
        Typeface typeface;
        try (InputStream in = MPainterRasterTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            assertNotNull(in, "the game font the Skija backend loads must exist");
            typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
        assertNotNull(typeface, "the bundled font must decode");

        try (Font font = new Font(typeface, 20f)) {
            MPainter.drawString(canvas, "SB", 8, 40, font, RED);
        }

        assertTrue(countPainted(4, 16, 60, 48) > 15,
                "glyphs must leave visible pixels — headless text rendering works");
    }

    @Test
    void measureWidthGrowsWithTheText() throws Exception {
        Typeface typeface;
        try (InputStream in = MPainterRasterTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            assertNotNull(in);
            typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
        try (Font font = new Font(typeface, 16f)) {
            float shortText = MPainter.measureWidth(font, "hi");
            float longText = MPainter.measureWidth(font, "a considerably longer label");
            assertTrue(shortText > 0);
            assertTrue(longText > shortText, "layout code sizes buttons off this measurement");
        }
    }
}
