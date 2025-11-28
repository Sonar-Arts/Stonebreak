package com.openmason.main.systems.menus.textureCreator.color;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

/**
 * Color utility functions for the texture creator.
 */
public class ColorUtils {

    private ColorUtils() {
        // Utility class - prevent instantiation
    }

    // ================================
    // HSV ↔ RGB Conversion
    // ================================

    /**
     * Convert HSV to RGB.
     */
    public static int[] hsvToRgb(float h, float s, float v) {
        // Normalize hue to 0-360 range
        h = h % 360.0f;
        if (h < 0) h += 360.0f;

        float c = v * s; // Chroma
        float x = c * (1 - Math.abs(((h / 60.0f) % 2) - 1));
        float m = v - c;

        float r1, g1, b1;

        if (h < 60) {
            r1 = c; g1 = x; b1 = 0;
        } else if (h < 120) {
            r1 = x; g1 = c; b1 = 0;
        } else if (h < 180) {
            r1 = 0; g1 = c; b1 = x;
        } else if (h < 240) {
            r1 = 0; g1 = x; b1 = c;
        } else if (h < 300) {
            r1 = x; g1 = 0; b1 = c;
        } else {
            r1 = c; g1 = 0; b1 = x;
        }

        int r = (int)((r1 + m) * 255);
        int g = (int)((g1 + m) * 255);
        int b = (int)((b1 + m) * 255);

        // Clamp to valid range
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        return new int[]{r, g, b};
    }

    /**
     * Convert RGB to HSV.
     * Standard algorithm from Wikipedia/computer graphics textbooks.
     *
     * @param r red (0-255)
     * @param g green (0-255)
     * @param b blue (0-255)
     * @return HSV array [h, s, v] where h is 0-360, s and v are 0-1
     */
    public static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;

        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        // Hue calculation
        float h = 0;
        if (delta > 0.00001f) {
            if (max == rf) {
                h = 60 * (((gf - bf) / delta) % 6);
            } else if (max == gf) {
                h = 60 * (((bf - rf) / delta) + 2);
            } else {
                h = 60 * (((rf - gf) / delta) + 4);
            }
        }

        if (h < 0) h += 360;

        // Saturation calculation
        float s = (max > 0.00001f) ? (delta / max) : 0;

        // Value

        return new float[]{h, s, max};
    }

    // ================================
    // Packed Color Conversion
    // ================================

    /**
     * Convert HSV + Alpha to packed RGBA color (PixelCanvas format).
     *
     * @param h hue (0-360)
     * @param s saturation (0-1)
     * @param v value (0-1)
     * @param a alpha (0-255)
     * @return packed RGBA color (0xAABBGGRR)
     */
    public static int hsvToPackedColor(float h, float s, float v, int a) {
        int[] rgb = hsvToRgb(h, s, v);
        return PixelCanvas.packRGBA(rgb[0], rgb[1], rgb[2], a);
    }

    /**
     * Extract HSV components from packed RGBA color.
     *
     * @param packedColor packed RGBA color (0xAABBGGRR)
     * @return float array [h, s, v, a] where h is 0-360, s/v are 0-1, a is 0-255
     */
    public static float[] packedColorToHsv(int packedColor) {
        int[] rgba = PixelCanvas.unpackRGBA(packedColor);
        float[] hsv = rgbToHsv(rgba[0], rgba[1], rgba[2]);
        return new float[]{hsv[0], hsv[1], hsv[2], rgba[3]};
    }

    // ================================
    // Hex String Formatting
    // ================================

    /**
     * Convert packed RGBA color to hex string.
     * Format: RRGGBBAA (8 hex digits, uppercase)
     *
     * @param packedColor packed RGBA color (0xAABBGGRR)
     * @return hex string (e.g. "FF8800FF")
     */
    public static String toHexString(int packedColor) {
        int[] rgba = PixelCanvas.unpackRGBA(packedColor);
        return String.format("%02X%02X%02X%02X", rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * Parse hex string to packed RGBA color.
     * Accepts formats: RRGGBB, RRGGBBAA, #RRGGBB, #RRGGBBAA
     *
     * @param hex hex color string
     * @return packed RGBA color (0xAABBGGRR), or 0xFF000000 if invalid
     */
    public static int fromHexString(String hex) {
        if (hex == null || hex.isEmpty()) {
            return 0xFF000000; // Default to opaque black
        }

        // Remove # prefix if present
        hex = hex.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        // Pad to 8 characters if needed
        if (hex.length() == 6) {
            hex = hex + "FF"; // Add full alpha
        } else if (hex.length() < 8) {
            // Invalid length, pad with FF
            hex = String.format("%-8s", hex).replace(' ', 'F');
        } else if (hex.length() > 8) {
            hex = hex.substring(0, 8);
        }

        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int a = Integer.parseInt(hex.substring(6, 8), 16);

            return PixelCanvas.packRGBA(r, g, b, a);
        } catch (NumberFormatException e) {
            return 0xFF000000; // Return opaque black on error
        }
    }

    // ================================
    // Color Manipulation Utilities
    // ================================

    /**
     * Clamp value to range [min, max].
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamp value to range [min, max].
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
