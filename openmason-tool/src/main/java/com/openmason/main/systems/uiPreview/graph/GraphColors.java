package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.ui.graph.PortType;
import imgui.ImGui;

/**
 * Pin and node-header hues of the graph canvas. Pin type hues and category hues are fixed
 * mid-tone constants chosen to read on both the Light and Dark themes; everything else (canvas,
 * node bodies, text, outlines) is derived from the live ImGui style or {@code ThemeColors} at
 * the call site.
 */
final class GraphColors {

    private GraphColors() {
    }

    /** Packed draw-list color from 0..1 channels. */
    static int rgba(float r, float g, float b, float a) {
        return ImGui.colorConvertFloat4ToU32(r, g, b, a);
    }

    /** The pin hue of a port type (exec pins use the theme's text color instead). */
    static int pin(PortType t, float alpha) {
        return switch (t) {
            case BOOL -> rgba(0.86f, 0.30f, 0.32f, alpha);
            case INT -> rgba(0.20f, 0.72f, 0.74f, alpha);
            case NUMBER -> rgba(0.38f, 0.74f, 0.30f, alpha);
            case STRING -> rgba(0.82f, 0.38f, 0.78f, alpha);
            case COLOR -> rgba(0.94f, 0.58f, 0.18f, alpha);
            case ASSET -> rgba(0.88f, 0.76f, 0.20f, alpha);
            case LIST -> rgba(0.58f, 0.46f, 0.88f, alpha);
            case OBJECT -> rgba(0.30f, 0.56f, 0.92f, alpha);
            case ANY, EXEC -> rgba(0.60f, 0.62f, 0.66f, alpha);
        };
    }

    /** A saturated mid-tone header color for a category (stable per name). */
    static int category(String category, boolean event) {
        float h = Math.floorMod(category.hashCode() * 2654435761L, 360L) / 360f;
        float[] rgb = hsv(h, event ? 0.62f : 0.45f, event ? 0.62f : 0.50f);
        return rgba(rgb[0], rgb[1], rgb[2], 1f);
    }

    /** Header for a node of an unknown kind or a pure node: neutral variants of the above. */
    static int neutral() {
        return rgba(0.40f, 0.42f, 0.46f, 1f);
    }

    /** Text drawn over a header: fixed light, since headers are always mid-dark. */
    static int headerText() {
        return rgba(0.97f, 0.97f, 0.98f, 1f);
    }

    /** {@code #RRGGBB(AA)} to a packed color with an alpha scale; 0 when it does not parse. */
    static int parseHex(String hex, float alphaScale) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            return 0;
        }
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        int a = hex.length() == 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255;
        return rgba(r / 255f, g / 255f, b / 255f, a / 255f * alphaScale);
    }

    private static float[] hsv(float h, float s, float v) {
        float r = 0;
        float g = 0;
        float b = 0;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return new float[]{r, g, b};
    }
}
