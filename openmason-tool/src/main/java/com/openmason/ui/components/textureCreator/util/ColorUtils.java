package com.openmason.ui.components.textureCreator.util;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;

/**
 * Color utility functions.
 *
 * Provides color conversion, manipulation, and common color constants.
 * Follows DRY principle - centralized color operations.
 *
 * @author Open Mason Team
 */
public class ColorUtils {

    // Common colors (RGBA packed)
    public static final int TRANSPARENT = 0x00000000;
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFF0000FF;
    public static final int GREEN = 0xFF00FF00;
    public static final int BLUE = 0xFFFF0000;
    public static final int YELLOW = 0xFF00FFFF;
    public static final int CYAN = 0xFFFFFF00;
    public static final int MAGENTA = 0xFFFF00FF;

    /**
     * Convert RGBA components to hex string.
     *
     * @param r red (0-255)
     * @param g green (0-255)
     * @param b blue (0-255)
     * @param a alpha (0-255)
     * @return hex string (RRGGBBAA)
     */
    public static String toHexString(int r, int g, int b, int a) {
        return String.format("%02X%02X%02X%02X", r, g, b, a);
    }

    /**
     * Convert packed color to hex string.
     *
     * @param color packed RGBA color
     * @return hex string (RRGGBBAA)
     */
    public static String toHexString(int color) {
        int[] rgba = PixelCanvas.unpackRGBA(color);
        return toHexString(rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * Parse hex string to packed color.
     *
     * @param hex hex string (RRGGBB or RRGGBBAA)
     * @return packed RGBA color
     */
    public static int fromHexString(String hex) {
        hex = hex.trim().toUpperCase();

        // Remove # prefix if present
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }

        // Pad with FF if alpha not specified
        if (hex.length() == 6) {
            hex += "FF";
        }

        if (hex.length() != 8) {
            throw new IllegalArgumentException("Invalid hex color format: " + hex);
        }

        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int a = Integer.parseInt(hex.substring(6, 8), 16);

            return PixelCanvas.packRGBA(r, g, b, a);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex color: " + hex, e);
        }
    }

    /**
     * Interpolate between two colors.
     *
     * @param color1 first color
     * @param color2 second color
     * @param t interpolation factor (0.0 to 1.0)
     * @return interpolated color
     */
    public static int lerp(int color1, int color2, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp to [0, 1]

        int[] rgba1 = PixelCanvas.unpackRGBA(color1);
        int[] rgba2 = PixelCanvas.unpackRGBA(color2);

        int r = (int) (rgba1[0] + (rgba2[0] - rgba1[0]) * t);
        int g = (int) (rgba1[1] + (rgba2[1] - rgba1[1]) * t);
        int b = (int) (rgba1[2] + (rgba2[2] - rgba1[2]) * t);
        int a = (int) (rgba1[3] + (rgba2[3] - rgba1[3]) * t);

        return PixelCanvas.packRGBA(r, g, b, a);
    }

    /**
     * Get luminance (perceived brightness) of a color.
     *
     * @param color packed RGBA color
     * @return luminance value (0.0 to 1.0)
     */
    public static float getLuminance(int color) {
        int[] rgba = PixelCanvas.unpackRGBA(color);

        // Use standard luminance formula
        float r = rgba[0] / 255.0f;
        float g = rgba[1] / 255.0f;
        float b = rgba[2] / 255.0f;

        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    /**
     * Check if a color is considered "light" (luminance > 0.5).
     *
     * @param color packed RGBA color
     * @return true if light color
     */
    public static boolean isLightColor(int color) {
        return getLuminance(color) > 0.5f;
    }

    /**
     * Create a color with modified alpha.
     *
     * @param color original color
     * @param newAlpha new alpha value (0-255)
     * @return color with new alpha
     */
    public static int withAlpha(int color, int newAlpha) {
        int[] rgba = PixelCanvas.unpackRGBA(color);
        return PixelCanvas.packRGBA(rgba[0], rgba[1], rgba[2], newAlpha);
    }

    /**
     * Invert a color.
     *
     * @param color color to invert
     * @return inverted color (preserves alpha)
     */
    public static int invert(int color) {
        int[] rgba = PixelCanvas.unpackRGBA(color);
        return PixelCanvas.packRGBA(255 - rgba[0], 255 - rgba[1], 255 - rgba[2], rgba[3]);
    }

    /**
     * Create grayscale version of a color.
     *
     * @param color color to convert
     * @return grayscale color
     */
    public static int toGrayscale(int color) {
        int[] rgba = PixelCanvas.unpackRGBA(color);
        int gray = (int) ((rgba[0] + rgba[1] + rgba[2]) / 3.0f);
        return PixelCanvas.packRGBA(gray, gray, gray, rgba[3]);
    }
}
