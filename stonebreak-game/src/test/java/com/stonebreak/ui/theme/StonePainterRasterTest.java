package com.stonebreak.ui.theme;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ImageInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real pixels of Stonebreak's own Masonry shapes ({@link StonePainter}, moved out of the engine in #384). */
class StonePainterRasterTest {

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
    void theCraftingArrowStaysInsideItsBox() {
        clear();
        StonePainter.craftingArrow(canvas, 16, 24, 32, 16, RED);

        assertTrue(countPainted(16, 24, 48, 40) > 20, "the glyph paints a visible arrow");
        assertEquals(0, countPainted(0, 0, SIZE, 22), "nothing bleeds above the box");
        assertEquals(0, countPainted(0, 42, SIZE, SIZE), "nothing bleeds below it");
    }

    @Test
    void theCraftingArrowHeadPointsRightAsASingleTip() {
        clear();
        StonePainter.craftingArrow(canvas, 16, 24, 32, 16, RED);

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
        float[] corner = StonePainter.craftingArrowPlacement(100f, 156f, 100f, 40f, 20f);
        assertEquals(118f, corner[0], 0.001f, "horizontally centred in the 56px gap");
        assertEquals(110f, corner[1], 0.001f, "vertically centred on the output slot");
    }
}
