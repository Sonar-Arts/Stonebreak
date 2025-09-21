package com.stonebreak.ui.hotbar.styling;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

/**
 * Consistent visual theme for the hotbar UI system.
 * Maintains visual consistency with the inventory theme using neutral gray colors.
 */
public final class HotbarTheme {

    private HotbarTheme() {
        // Utility class - prevent instantiation
    }

    // ==================== COLOR PALETTE ====================

    // Hotbar Background Colors (Neutral gray theme)
    public static final class Background {
        public static final ColorRGBA PRIMARY = new ColorRGBA(40, 40, 40, 200);          // Main hotbar background
        public static final ColorRGBA BORDER = new ColorRGBA(80, 80, 80, 255);          // Hotbar border
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 120);             // Drop shadow
    }

    // Slot Colors (Consistent with inventory theme)
    public static final class Slot {
        public static final ColorRGBA BACKGROUND = new ColorRGBA(60, 60, 60, 180);      // Slot background
        public static final ColorRGBA BORDER_NORMAL = new ColorRGBA(100, 100, 100, 255); // Normal border
        public static final ColorRGBA BORDER_SELECTED = new ColorRGBA(255, 255, 255, 255); // Selected border
        public static final ColorRGBA HIGHLIGHT_INNER = new ColorRGBA(160, 160, 160, 40); // Inner glow
        public static final ColorRGBA SHADOW_INNER = new ColorRGBA(20, 20, 20, 180);    // Inner shadow for depth
    }

    // Tooltip Colors (Consistent with inventory theme)
    public static final class Tooltip {
        public static final ColorRGBA BACKGROUND = new ColorRGBA(30, 30, 30, 220);      // Background
        public static final ColorRGBA BORDER = new ColorRGBA(100, 100, 100, 255);      // Border
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 140);             // Drop shadow
        public static final ColorRGBA TEXT_PRIMARY = new ColorRGBA(255, 255, 255, 255); // Primary text
        public static final ColorRGBA TEXT_SHADOW = new ColorRGBA(0, 0, 0, 180);        // Text shadow
    }

    // Text Colors (Neutral gray hierarchy)
    public static final class Text {
        public static final ColorRGBA PRIMARY = new ColorRGBA(255, 255, 255, 255);      // Primary text
        public static final ColorRGBA SECONDARY = new ColorRGBA(200, 200, 200, 255);    // Secondary text
        public static final ColorRGBA ACCENT = new ColorRGBA(180, 180, 180, 255);       // Accent text
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 160);             // Text shadow
        public static final ColorRGBA COUNT = new ColorRGBA(255, 220, 64, 255);         // Item count text
        public static final ColorRGBA COUNT_SHADOW = new ColorRGBA(0, 0, 0, 200);       // Count shadow
    }

    // ==================== MEASUREMENTS ====================

    public static final class Measurements {
        public static final float CORNER_RADIUS_LARGE = 8.0f;      // Hotbar background
        public static final float CORNER_RADIUS_MEDIUM = 6.0f;     // Slot corners
        public static final float CORNER_RADIUS_SMALL = 4.0f;      // Tooltip corners
        public static final float BORDER_WIDTH_THICK = 2.0f;       // Selection borders
        public static final float BORDER_WIDTH_NORMAL = 1.5f;      // Normal borders
        public static final float BORDER_WIDTH_THIN = 1.0f;        // Thin borders
        public static final float SHADOW_OFFSET = 4.0f;            // Shadow offset
        public static final float SHADOW_BLUR = 8.0f;              // Shadow blur radius
        public static final float PADDING_LARGE = 16.0f;           // Large padding
        public static final float PADDING_MEDIUM = 12.0f;          // Medium padding
        public static final float PADDING_SMALL = 8.0f;            // Small padding
        public static final int HOTBAR_Y_OFFSET = 50;              // Distance from bottom
    }

    // ==================== ANIMATION VALUES ====================

    public static final class Animation {
        public static final float TOOLTIP_DISPLAY_DURATION = 1.5f; // Tooltip display time
        public static final float TOOLTIP_FADE_DURATION = 0.5f;    // Tooltip fade time
        public static final float SELECTION_TRANSITION_SPEED = 0.15f; // Selection animation
        public static final float HIGHLIGHT_PULSE_SPEED = 2.0f;    // Highlight pulse frequency
    }

    // ==================== TYPOGRAPHY ====================

    public static final class Typography {
        public static final String FONT_FAMILY = "sans";           // Font family
        public static final float FONT_SIZE_NORMAL = 14.0f;        // Normal text size
        public static final float FONT_SIZE_SMALL = 12.0f;         // Small text size
        public static final float FONT_SIZE_COUNT = 12.0f;         // Item count size
        public static final int TEXT_ALIGN_CENTER = 1;             // Text alignment constants
        public static final int TEXT_ALIGN_RIGHT = 2;
        public static final int TEXT_ALIGN_BOTTOM = 8;
        public static final int TEXT_ALIGN_MIDDLE = 4;
    }

    // ==================== HELPER METHODS ====================

    /**
     * Helper class for RGBA color values
     */
    public static final class ColorRGBA {
        public final int r, g, b, a;

        public ColorRGBA(int r, int g, int b, int a) {
            this.r = Math.max(0, Math.min(255, r));
            this.g = Math.max(0, Math.min(255, g));
            this.b = Math.max(0, Math.min(255, b));
            this.a = Math.max(0, Math.min(255, a));
        }

        /**
         * Creates an NVG color with the given memory stack
         */
        public NVGColor toNVG(MemoryStack stack) {
            return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, NVGColor.malloc(stack));
        }

        /**
         * Creates a modified color with different alpha
         */
        public ColorRGBA withAlpha(int alpha) {
            return new ColorRGBA(r, g, b, alpha);
        }

        /**
         * Creates a brighter version of this color
         */
        public ColorRGBA brighten(float factor) {
            return new ColorRGBA(
                Math.min(255, (int)(r * (1 + factor))),
                Math.min(255, (int)(g * (1 + factor))),
                Math.min(255, (int)(b * (1 + factor))),
                a
            );
        }

        /**
         * Creates a darker version of this color
         */
        public ColorRGBA darken(float factor) {
            return new ColorRGBA(
                Math.max(0, (int)(r * (1 - factor))),
                Math.max(0, (int)(g * (1 - factor))),
                Math.max(0, (int)(b * (1 - factor))),
                a
            );
        }
    }

    /**
     * Utility method to create gradients between two colors
     */
    public static NVGColor interpolateColor(ColorRGBA color1, ColorRGBA color2, float t, MemoryStack stack) {
        t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp t between 0 and 1
        int r = (int)(color1.r + (color2.r - color1.r) * t);
        int g = (int)(color1.g + (color2.g - color1.g) * t);
        int b = (int)(color1.b + (color2.b - color1.b) * t);
        int a = (int)(color1.a + (color2.a - color1.a) * t);
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, NVGColor.malloc(stack));
    }

    /**
     * Helper method to create NVGColor with proper byte casting.
     */
    public static NVGColor createNVGColor(int r, int g, int b, int a, MemoryStack stack) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, NVGColor.malloc(stack));
    }

    /**
     * Helper method to create NVGColor from ColorRGBA with alpha override.
     */
    public static NVGColor createNVGColor(ColorRGBA color, float alphaMultiplier, MemoryStack stack) {
        int alpha = (int)(color.a * alphaMultiplier);
        return createNVGColor(color.r, color.g, color.b, alpha, stack);
    }
}