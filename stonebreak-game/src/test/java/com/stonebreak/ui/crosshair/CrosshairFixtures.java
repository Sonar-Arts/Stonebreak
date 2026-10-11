package com.stonebreak.ui.crosshair;

import com.stonebreak.ui.crosshair.CrosshairSettings.CrosshairStyle;

import java.util.List;

/**
 * Crosshair configurations of the #301 fidelity cases: every style with and without its outline,
 * fractional sizes, colours and opacity; and the degenerate arms of a gap wider than the size. Not a
 * test class.
 */
public final class CrosshairFixtures {

    private CrosshairFixtures() {
    }

    public static final List<String> VARIANTS = List.of(
        "cross", "cross-plain", "dot", "dot-plain", "circle", "circle-plain", "square", "square-plain",
        "t", "t-plain", "plusdot", "plusdot-plain", "custom", "custom-circle",
        // gaps wider than the size: arms of no length, not drawn
        "wide-gap", "t-wide");

    /** The configuration {@code variant}. */
    public static CrosshairSettings crosshair(String variant) {
        CrosshairSettings c = new CrosshairSettings();
        boolean plain = variant.endsWith("-plain");
        c.setOutline(!plain);
        switch (plain ? variant.substring(0, variant.length() - 6) : variant) {
            case "cross" -> c.setStyle(CrosshairStyle.SIMPLE_CROSS);
            case "dot" -> c.setStyle(CrosshairStyle.DOT);
            case "circle" -> c.setStyle(CrosshairStyle.CIRCLE);
            case "square" -> c.setStyle(CrosshairStyle.SQUARE);
            case "t" -> c.setStyle(CrosshairStyle.T_SHAPE);
            case "plusdot" -> c.setStyle(CrosshairStyle.PLUS_DOT);
            case "custom" -> {
                c.setStyle(CrosshairStyle.PLUS_DOT);
                c.setSize(37f);
                c.setThickness(3f);
                c.setGap(7f);
                c.setOpacity(0.6f);
                c.setColor(0.9f, 0.2f, 0.35f);
                c.setOutlineThickness(1.5f);
            }
            case "custom-circle" -> {
                c.setStyle(CrosshairStyle.CIRCLE);
                c.setSize(23f);
                c.setThickness(2.5f);
                c.setOpacity(0.8f);
                c.setColor(0.1f, 0.85f, 0.3f);
                c.setOutlineColor(0.2f, 0.1f, 0.4f);
            }
            case "wide-gap" -> { // arms of 3 - 8 = -5 px
                c.setStyle(CrosshairStyle.SIMPLE_CROSS);
                c.setSize(6f);
                c.setGap(16f);
            }
            case "t-wide" -> {
                c.setStyle(CrosshairStyle.T_SHAPE);
                c.setSize(10f);
                c.setGap(14f);
            }
            default -> throw new IllegalArgumentException("crosshair variant " + variant);
        }
        return c;
    }
}
