package com.openmason.main.systems.menus.textureCreator.color;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Colour conversions behind the colour picker: HSV/RGB, packed colours and hex.
 */
class ColorUtilsTest {

    @Test
    void primariesAndGreysConvertToTheExpectedHsv() {
        assertArrayEquals(new float[]{0f, 1f, 1f}, ColorUtils.rgbToHsv(255, 0, 0), 1e-5f);
        assertArrayEquals(new float[]{120f, 1f, 1f}, ColorUtils.rgbToHsv(0, 255, 0), 1e-4f);
        assertArrayEquals(new float[]{240f, 1f, 1f}, ColorUtils.rgbToHsv(0, 0, 255), 1e-4f);
        assertArrayEquals(new float[]{300f, 1f, 1f}, ColorUtils.rgbToHsv(255, 0, 255), 1e-4f);
        assertArrayEquals(new float[]{0f, 0f, 0f}, ColorUtils.rgbToHsv(0, 0, 0), 1e-6f);
        assertArrayEquals(new float[]{0f, 0f, 1f}, ColorUtils.rgbToHsv(255, 255, 255), 1e-6f);
        float[] grey = ColorUtils.rgbToHsv(128, 128, 128);
        assertEquals(0f, grey[1], 1e-6f, "greys have no saturation");
    }

    @Test
    void hsvToRgbHitsEachHueSextant() {
        assertArrayEquals(new int[]{255, 0, 0}, ColorUtils.hsvToRgb(0f, 1f, 1f));
        assertArrayEquals(new int[]{255, 255, 0}, ColorUtils.hsvToRgb(60f, 1f, 1f));
        assertArrayEquals(new int[]{0, 255, 0}, ColorUtils.hsvToRgb(120f, 1f, 1f));
        assertArrayEquals(new int[]{0, 255, 255}, ColorUtils.hsvToRgb(180f, 1f, 1f));
        assertArrayEquals(new int[]{0, 0, 255}, ColorUtils.hsvToRgb(240f, 1f, 1f));
        assertArrayEquals(new int[]{255, 0, 255}, ColorUtils.hsvToRgb(300f, 1f, 1f));
        assertArrayEquals(new int[]{0, 0, 0}, ColorUtils.hsvToRgb(200f, 1f, 0f));
        assertArrayEquals(new int[]{255, 255, 255}, ColorUtils.hsvToRgb(123f, 0f, 1f));
    }

    @Test
    void hueWrapsAroundInBothDirections() {
        assertArrayEquals(ColorUtils.hsvToRgb(0f, 1f, 1f), ColorUtils.hsvToRgb(360f, 1f, 1f));
        assertArrayEquals(ColorUtils.hsvToRgb(300f, 0.5f, 0.8f), ColorUtils.hsvToRgb(-60f, 0.5f, 0.8f));
        assertArrayEquals(ColorUtils.hsvToRgb(30f, 1f, 1f), ColorUtils.hsvToRgb(750f, 1f, 1f));
    }

    @Test
    @Tag("regression")
    void everyRgbColourSurvivesAnHsvRoundTrip() {
        // The picker stores HSV canonically: a hex/eyedropper colour must paint back
        // exactly. Truncation used to shift ~2/3 of all colours by one level.
        for (int r = 0; r < 256; r++) {
            for (int g = 0; g < 256; g += 3) {
                for (int b = 0; b < 256; b += 5) {
                    float[] hsv = ColorUtils.rgbToHsv(r, g, b);
                    assertArrayEquals(new int[]{r, g, b}, ColorUtils.hsvToRgb(hsv[0], hsv[1], hsv[2]),
                            "rgb(" + r + "," + g + "," + b + ")");
                }
            }
        }
    }

    @Test
    @Tag("regression")
    void packedColourRoundTripsThroughHsvIncludingAlpha() {
        Random random = new Random(3);
        for (int i = 0; i < 5000; i++) {
            int packed = random.nextInt();
            float[] hsva = ColorUtils.packedColorToHsv(packed);
            assertEquals(packed, ColorUtils.hsvToPackedColor(hsva[0], hsva[1], hsva[2], (int) hsva[3]));
        }
    }

    @Test
    @Tag("regression")
    void hexRoundTripsEveryPackedColour() {
        Random random = new Random(4);
        for (int i = 0; i < 5000; i++) {
            int packed = random.nextInt();
            String hex = ColorUtils.toHexString(packed);
            assertEquals(8, hex.length());
            assertEquals(packed, ColorUtils.fromHexString(hex));
            assertEquals(packed, ColorUtils.fromHexString("#" + hex.toLowerCase()));
        }
    }

    @Test
    void hexStringIsRrGgBbAaUppercase() {
        assertEquals("FF8000C0", ColorUtils.toHexString(PixelCanvas.packRGBA(255, 128, 0, 0xC0)));
        assertEquals("00000000", ColorUtils.toHexString(0));
    }

    @Test
    void hexParsingAcceptsShortFormsAndFallsBackToOpaqueBlack() {
        assertEquals(PixelCanvas.packRGBA(0x12, 0x34, 0x56, 255), ColorUtils.fromHexString("123456"));
        assertEquals(PixelCanvas.packRGBA(0x12, 0x34, 0x56, 255), ColorUtils.fromHexString("  #123456 "));
        assertEquals(PixelCanvas.packRGBA(0x12, 0x34, 0x56, 0x78), ColorUtils.fromHexString("1234567890"),
                "extra digits are ignored");
        assertEquals(PixelCanvas.packRGBA(0xAB, 0xFF, 0xFF, 0xFF), ColorUtils.fromHexString("AB"),
                "short strings are padded with F");

        int opaqueBlack = 0xFF000000;
        assertEquals(opaqueBlack, ColorUtils.fromHexString(null));
        assertEquals(opaqueBlack, ColorUtils.fromHexString(""));
        assertEquals(opaqueBlack, ColorUtils.fromHexString("GG0000"));
        assertEquals(opaqueBlack, ColorUtils.fromHexString("#zzzzzzzz"));
    }

    @Test
    void clampHandlesBothNumericTypes() {
        assertEquals(0, ColorUtils.clamp(-5, 0, 255));
        assertEquals(255, ColorUtils.clamp(300, 0, 255));
        assertEquals(0.5f, ColorUtils.clamp(0.5f, 0f, 1f));
        assertEquals(1f, ColorUtils.clamp(7f, 0f, 1f));
    }
}
