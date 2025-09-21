package com.stonebreak.ui.inventoryScreen.styling;

import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

/**
 * Modern visual theme constants for the inventory UI system.
 * Provides a cohesive, professional color palette and styling values.
 */
public final class InventoryTheme {

    private InventoryTheme() {
        // Utility class - prevent instantiation
    }

    // ==================== COLOR PALETTE ====================

    // Panel Colors (Modern dark theme with subtle blue accents)
    public static final class Panel {
        public static final ColorRGBA BACKGROUND_PRIMARY = new ColorRGBA(28, 32, 42, 245);      // Dark blue-gray base
        public static final ColorRGBA BACKGROUND_SECONDARY = new ColorRGBA(22, 25, 34, 235);    // Darker variant
        public static final ColorRGBA BORDER_PRIMARY = new ColorRGBA(64, 74, 96, 255);         // Subtle blue border
        public static final ColorRGBA BORDER_HIGHLIGHT = new ColorRGBA(88, 166, 255, 180);     // Bright blue accent
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 120);                    // Soft shadow
        public static final ColorRGBA HEADER_GRADIENT_START = new ColorRGBA(42, 48, 62, 255);  // Header gradient top
        public static final ColorRGBA HEADER_GRADIENT_END = new ColorRGBA(28, 32, 42, 255);    // Header gradient bottom
    }

    // Slot Colors (Refined with subtle depth)
    public static final class Slot {
        public static final ColorRGBA BACKGROUND = new ColorRGBA(45, 52, 68, 255);             // Slot background
        public static final ColorRGBA BORDER_NORMAL = new ColorRGBA(70, 80, 100, 255);        // Normal border
        public static final ColorRGBA BORDER_HOVER = new ColorRGBA(88, 166, 255, 200);        // Hover border
        public static final ColorRGBA BORDER_SELECTED = new ColorRGBA(255, 196, 64, 255);     // Selected border
        public static final ColorRGBA HIGHLIGHT_INNER = new ColorRGBA(88, 166, 255, 40);      // Inner glow
        public static final ColorRGBA SHADOW_INNER = new ColorRGBA(15, 18, 25, 180);          // Inner shadow for depth
    }

    // Button Colors (Modern interactive design)
    public static final class Button {
        public static final ColorRGBA BACKGROUND_NORMAL = new ColorRGBA(52, 62, 84, 255);     // Normal state
        public static final ColorRGBA BACKGROUND_HOVER = new ColorRGBA(74, 88, 118, 255);     // Hover state
        public static final ColorRGBA BACKGROUND_PRESSED = new ColorRGBA(42, 50, 68, 255);    // Pressed state
        public static final ColorRGBA BORDER_NORMAL = new ColorRGBA(88, 166, 255, 120);       // Normal border
        public static final ColorRGBA BORDER_HOVER = new ColorRGBA(88, 166, 255, 200);        // Hover border
        public static final ColorRGBA GRADIENT_TOP = new ColorRGBA(62, 74, 98, 255);          // Gradient top
        public static final ColorRGBA GRADIENT_BOTTOM = new ColorRGBA(42, 50, 68, 255);       // Gradient bottom
    }

    // Tooltip Colors (Clean and readable)
    public static final class Tooltip {
        public static final ColorRGBA BACKGROUND = new ColorRGBA(32, 38, 50, 250);            // Background
        public static final ColorRGBA BORDER = new ColorRGBA(88, 166, 255, 200);              // Border
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 140);                   // Drop shadow
        public static final ColorRGBA TEXT_PRIMARY = new ColorRGBA(255, 255, 255, 255);       // Primary text
        public static final ColorRGBA TEXT_SHADOW = new ColorRGBA(0, 0, 0, 180);              // Text shadow
        public static final ColorRGBA HIGHLIGHT_INNER = new ColorRGBA(88, 166, 255, 40);      // Inner highlight
    }

    // Text Colors (Clear hierarchy)
    public static final class Text {
        public static final ColorRGBA PRIMARY = new ColorRGBA(255, 255, 255, 255);            // Primary text
        public static final ColorRGBA SECONDARY = new ColorRGBA(200, 210, 225, 255);          // Secondary text
        public static final ColorRGBA ACCENT = new ColorRGBA(88, 166, 255, 255);              // Accent text
        public static final ColorRGBA SHADOW = new ColorRGBA(0, 0, 0, 160);                   // Text shadow
        public static final ColorRGBA COUNT = new ColorRGBA(255, 220, 64, 255);               // Item count text
        public static final ColorRGBA COUNT_SHADOW = new ColorRGBA(0, 0, 0, 200);             // Count shadow
    }

    // Crafting Specific Colors
    public static final class Crafting {
        public static final ColorRGBA ARROW_FILL = new ColorRGBA(88, 166, 255, 180);          // Arrow fill
        public static final ColorRGBA ARROW_BORDER = new ColorRGBA(255, 255, 255, 120);       // Arrow border
        public static final ColorRGBA OUTPUT_GLOW = new ColorRGBA(255, 196, 64, 60);          // Output slot glow
    }

    // ==================== MEASUREMENTS ====================

    public static final class Measurements {
        public static final float CORNER_RADIUS_LARGE = 8.0f;      // Large panels
        public static final float CORNER_RADIUS_MEDIUM = 6.0f;     // Medium elements
        public static final float CORNER_RADIUS_SMALL = 4.0f;      // Small elements
        public static final float BORDER_WIDTH_THICK = 2.0f;       // Thick borders
        public static final float BORDER_WIDTH_NORMAL = 1.5f;      // Normal borders
        public static final float BORDER_WIDTH_THIN = 1.0f;        // Thin borders
        public static final float SHADOW_OFFSET = 4.0f;            // Shadow offset
        public static final float SHADOW_BLUR = 8.0f;              // Shadow blur radius
        public static final float PADDING_LARGE = 16.0f;           // Large padding
        public static final float PADDING_MEDIUM = 12.0f;          // Medium padding
        public static final float PADDING_SMALL = 8.0f;            // Small padding
    }

    // ==================== ANIMATION VALUES ====================

    public static final class Animation {
        public static final float HOVER_TRANSITION_SPEED = 0.15f;  // Button hover transition
        public static final float FADE_IN_SPEED = 0.2f;            // Fade in animations
        public static final float HIGHLIGHT_PULSE_SPEED = 2.0f;    // Highlight pulse frequency
        public static final float GLOW_INTENSITY_MAX = 1.0f;       // Maximum glow intensity
        public static final float GLOW_INTENSITY_MIN = 0.3f;       // Minimum glow intensity
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
}