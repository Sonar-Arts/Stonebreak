package com.stonebreak.ui.focusBattle.intro;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The encounter transition on a CPU raster surface: timeline, the SkSL path, and the null-still path. */
class EncounterSwirlTest {

    private static final int W = 160, H = 90;

    /** A still with four distinct quadrants, so any twist or zoom moves colour across the centre lines. */
    private static Image quadrants() {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(new ImageInfo(W, H, ColorType.RGBA_8888, ColorAlphaType.OPAQUE));
            Canvas canvas = new Canvas(bitmap);
            int[] colors = {0xFFC02020, 0xFF20C020, 0xFF2020C0, 0xFFC0C020};
            for (int i = 0; i < 4; i++) {
                try (Paint p = new Paint().setColor(colors[i])) {
                    canvas.drawRect(Rect.makeXYWH((i % 2) * W / 2f, (i / 2) * H / 2f, W / 2f, H / 2f), p);
                }
            }
            canvas.close();
            return Image.makeRasterFromBitmap(bitmap.setImmutable());
        }
    }

    private static int[] render(EncounterSwirl swirl) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.makeN32Premul(W, H));
            Canvas canvas = new Canvas(bitmap);
            canvas.clear(0xFF101010);
            swirl.paint(canvas, W, H);
            int[] out = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    out[y * W + x] = bitmap.getColor(x, y);
                }
            }
            canvas.close();
            return out;
        }
    }

    private static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) n++;
        }
        return n;
    }

    private static void advanceTo(EncounterSwirl swirl, float time) {
        while (swirl.time() < time - 1e-5f) {
            swirl.update(Math.min(1f / 60f, time - swirl.time()));
        }
    }

    @Test void idleUntilBegunAndFinishesFullyWhite() {
        try (EncounterSwirl swirl = new EncounterSwirl()) {
            assertFalse(swirl.active(), "nothing plays before begin()");
            swirl.begin(quadrants());
            assertTrue(swirl.active());
            advanceTo(swirl, EncounterSwirl.TOTAL_SECONDS);
            assertFalse(swirl.active());
            assertEquals(1f, swirl.whiteout(), 1e-6f);
            for (int pixel : render(swirl)) {
                assertEquals(0xFFFFFFFF, pixel, "the hand-over frame is pure white so the battle reveal can start from white");
            }
        }
    }

    @Test void startsAsTheUntouchedFieldFrameThenTwistsProgressively() {
        try (EncounterSwirl swirl = new EncounterSwirl(); EncounterSwirl reference = new EncounterSwirl()) {
            swirl.begin(quadrants());
            reference.begin(quadrants());
            // Just after the opening flash the twist has barely begun: the frame is still the field.
            advanceTo(swirl, EncounterSwirl.FLASH_SECONDS + 0.01f);
            int[] early = render(swirl);
            assertEquals(0xFFC02020, early[10 * W + 10], "top-left quadrant still in place");
            assertEquals(0xFFC0C020, early[(H - 10) * W + (W - 10)], "bottom-right quadrant still in place");

            advanceTo(swirl, 0.8f);
            int[] mid = render(swirl);
            advanceTo(swirl, 1.2f);
            int[] late = render(swirl);
            assertTrue(differing(early, mid) > W * H / 20, "the twist must visibly move the image");
            assertTrue(differing(mid, late) > W * H / 20, "and keep moving");
            assertFalse(swirl.usedFallback(), "the SkSL path must work on the CPU raster backend too");
            assertTrue(swirl.twistProgress() > 0.3f && swirl.twistProgress() < 1f);
        }
    }

    @Test void openingFlashIsADoublePulseThatEndsBeforeTheTwist() {
        try (EncounterSwirl swirl = new EncounterSwirl()) {
            swirl.begin(null);
            assertEquals(0f, swirl.flash(), 1e-6f);
            advanceTo(swirl, EncounterSwirl.FLASH_SECONDS * 0.25f);
            assertTrue(swirl.flash() > 0.7f, "first pulse peak");
            advanceTo(swirl, EncounterSwirl.FLASH_SECONDS * 0.5f);
            assertTrue(swirl.flash() < 0.1f, "dip between the pulses");
            advanceTo(swirl, EncounterSwirl.FLASH_SECONDS * 0.75f);
            assertTrue(swirl.flash() > 0.7f, "second pulse peak");
            advanceTo(swirl, EncounterSwirl.FLASH_SECONDS + 0.001f);
            assertEquals(0f, swirl.flash(), 1e-6f);
        }
    }

    @Test void aMissingStillPlaysTheSameTimingOverBlack() {
        try (EncounterSwirl swirl = new EncounterSwirl()) {
            swirl.begin(null);
            advanceTo(swirl, 0.6f);
            int[] mid = render(swirl);
            assertEquals(0xFF000000, mid[H / 2 * W + W / 2]);
            advanceTo(swirl, EncounterSwirl.TOTAL_SECONDS);
            assertEquals(0xFFFFFFFF, render(swirl)[0]);
        }
    }

    @Test void timeOnlyMovesForwardOnSaneSteps() {
        try (EncounterSwirl swirl = new EncounterSwirl()) {
            swirl.begin(null);
            swirl.update(Float.NaN);
            swirl.update(-1f);
            swirl.update(Float.POSITIVE_INFINITY);
            assertEquals(0f, swirl.time(), 1e-6f);
            swirl.update(100f);
            assertEquals(EncounterSwirl.TOTAL_SECONDS, swirl.time(), 1e-6f);
            swirl.begin(null);
            assertEquals(0f, swirl.time(), 1e-6f, "begin() restarts");
        }
    }
}
