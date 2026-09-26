package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ASCII fold that keeps unsupported characters out of UI text as tofu boxes, checked against
 * the rule ({@link MGlyphs#ascii}), against real pixels, and against the bundled typeface the
 * whole thing exists for — a font swap that covers these code points, or one that loses an ASCII
 * stand-in, fails a test here instead of quietly changing a screen.
 */
class MGlyphsTest {

    private static final int SIZE = 48;
    private static final int BACKGROUND = 0xFF101010;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Typeface TYPEFACE = loadGameTypeface();

    private final Bitmap bitmap = newBitmap();
    private final Canvas canvas = new Canvas(bitmap);

    @Test
    void plainAsciiPassesThroughUntouched() {
        String text = "AP Remaining: 4";
        assertSame(text, MGlyphs.ascii(text), "an ASCII string is returned as-is, not copied");
    }

    @Test
    void minusSignBecomesAHyphen() {
        assertEquals("-", MGlyphs.ascii("\u2212"));
        assertEquals("-2", MGlyphs.ascii("\u22122"), "the ability-score bonus badge");
    }

    @Test
    void dashesAndEllipsisFoldDown() {
        assertEquals("Steel and coin - that's your trade.",
                MGlyphs.ascii("Steel and coin \u2014 that's your trade."));
        assertEquals("+3 more...", MGlyphs.ascii("+3 more\u2026"));
    }

    @Test
    void unmappedCharactersAreLeftAlone() {
        assertEquals("ok \u2603", MGlyphs.ascii("ok \u2603"), "a deliberate symbol is not our business");
        assertEquals("", MGlyphs.ascii(""));
        assertNull(MGlyphs.ascii(null));
    }

    @Test
    void everySubstitutedCharacterIsMissingFromTheGameFontAndItsStandInIsNot() {
        Font font = new Font(TYPEFACE, 12f);
        int substituted = 0;
        for (int cp = 0x80; cp < 0xFFFF; cp++) {
            char c = (char) cp;
            if (!MGlyphs.isSubstituted(c)) continue;
            substituted++;
            assertEquals(0, font.getUTF32Glyph(cp),
                    "U+%04X is in the font after all — drop it from the fold".formatted(cp));
            for (char stand : MGlyphs.ascii(String.valueOf(c)).toCharArray()) {
                assertNotEquals(0, font.getUTF32Glyph(stand),
                        "stand-in '%c' for U+%04X is itself missing".formatted(stand, cp));
            }
        }
        assertTrue(substituted > 10, "the fold table went missing");
    }

    @Test
    void theMinusSignPaintsAsAHyphenInsteadOfATofuBox() {
        Font font = new Font(TYPEFACE, 24f);

        int[] rawMinus = raster(c -> {
            try (Paint paint = new Paint().setColor(WHITE)) {
                c.drawString("\u2212", 8f, 32f, font, paint);
            }
        });
        int[] hyphen = raster(c -> MPainter.drawString(c, "-", 8f, 32f, font, WHITE));
        assertFalse(sameRaster(rawMinus, hyphen),
                "the bug: drawn straight, U+2212 hits glyph 0 and paints the font's .notdef box");

        int[] foldedMinus = raster(c -> MPainter.drawString(c, "\u2212", 8f, 32f, font, WHITE));
        assertTrue(sameRaster(foldedMinus, hyphen),
                "through MPainter it paints exactly the hyphen — a real dash, same pixels");
        assertTrue(inkOf(foldedMinus) > 0, "and it is not blank");
    }

    @Test
    void measuringAgreesWithWhatIsDrawn() {
        Font font = new Font(TYPEFACE, 16f);
        assertEquals(MPainter.measureWidth(font, "-2"), MPainter.measureWidth(font, "\u22122"), 0.001f,
                "centred labels rely on the measured width being the drawn width");
    }

    /** Pixels of one draw over a cleared bitmap, row-major, for exact comparison. */
    private int[] raster(Consumer<Canvas> draw) {
        canvas.clear(BACKGROUND);
        draw.accept(canvas);
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[y * SIZE + x] = bitmap.getColor(x, y);
            }
        }
        return pixels;
    }

    private static boolean sameRaster(int[] a, int[] b) {
        return Arrays.equals(a, b);
    }

    private static int inkOf(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) {
            if (pixel != BACKGROUND) count++;
        }
        return count;
    }

    private static Bitmap newBitmap() {
        Bitmap bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(SIZE, SIZE));
        return bitmap;
    }

    private static Typeface loadGameTypeface() {
        try (InputStream in = MGlyphsTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            if (in == null) {
                throw new IllegalStateException("bundled game font missing: /fonts/Minecraft.ttf");
            }
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("could not load the game typeface", e);
        }
    }
}
