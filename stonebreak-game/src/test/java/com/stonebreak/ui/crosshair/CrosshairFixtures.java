package com.stonebreak.ui.crosshair;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.components.MCrosshairRenderer;
import com.stonebreak.rendering.UI.components.MCrosshairRenderer.CrosshairStyle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crosshair configurations of the #301 fidelity cases: every style with and without its outline,
 * fractional sizes, colours and opacity; and the degenerate arms of a gap wider than the size (the
 * legacy renderer threw on them until it learned to skip them). Not a test class.
 */
public final class CrosshairFixtures {

    private CrosshairFixtures() {
    }

    public static final List<String> VARIANTS = List.of(
        "cross", "cross-plain", "dot", "dot-plain", "circle", "circle-plain", "square", "square-plain",
        "t", "t-plain", "plusdot", "plusdot-plain", "custom", "custom-circle",
        // gaps wider than the size: arms of no length, drawn by neither renderer (legacy used to throw)
        "wide-gap", "t-wide");

    /** A renderer configured as {@code variant}, drawing through {@code backend} (null: data only). */
    public static MCrosshairRenderer crosshair(String variant, SkijaUIBackend backend) {
        MCrosshairRenderer c = new MCrosshairRenderer(backend);
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

    /**
     * Where the legacy renderer draws, as {@code [x, y, w, h]} by the document's element names: the
     * arms' fill rects, the ring's and the dot's bounds (centre ± radius, or the square). The geometry
     * oracle of the gate, mirroring {@code MCrosshairRenderer}'s draw methods.
     */
    public static Map<String, float[]> legacyRects(MCrosshairRenderer c, int windowWidth, int windowHeight) {
        Map<String, float[]> out = new LinkedHashMap<>();
        float cx = windowWidth / 2f;
        float cy = windowHeight / 2f;
        float size = c.getSize();
        float half = size / 2f;
        float hg = c.getGap() / 2f;
        float t = c.getThickness();
        CrosshairStyle style = c.getStyle();
        if (style == CrosshairStyle.SIMPLE_CROSS || style == CrosshairStyle.PLUS_DOT) {
            float arm = half - hg;
            out.put("arm1", new float[]{cx - half, cy - t / 2f, arm, t});
            out.put("arm2", new float[]{cx + hg, cy - t / 2f, arm, t});
            out.put("arm3", new float[]{cx - t / 2f, cy - half, t, arm});
            out.put("arm4", new float[]{cx - t / 2f, cy + hg, t, arm});
        } else if (style == CrosshairStyle.T_SHAPE) {
            out.put("arm1", new float[]{cx - half, cy - hg - t, size, t});
            out.put("arm2", new float[]{cx - t / 2f, cy + hg, t, half - hg});
        }
        if (style == CrosshairStyle.DOT || style == CrosshairStyle.PLUS_DOT) {
            float r = size / 4f;
            if (c.hasOutline()) { // drawOutlinedCircle: the ring, then the disc
                out.put("ring", new float[]{cx - r, cy - r, 2 * r, 2 * r});
            }
            out.put("dot", new float[]{cx - r, cy - r, 2 * r, 2 * r});
        } else if (style == CrosshairStyle.CIRCLE) {
            out.put("ring", new float[]{cx - half, cy - half, size, size});
        } else if (style == CrosshairStyle.SQUARE) {
            out.put("ring", new float[]{cx - half, cy - half, size, size});
        }
        out.values().removeIf(r -> r[2] <= 0f || r[3] <= 0f); // swallowed by the gap: not drawn
        return out;
    }
}
