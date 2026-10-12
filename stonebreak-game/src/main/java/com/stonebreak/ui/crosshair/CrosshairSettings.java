package com.stonebreak.ui.crosshair;

/**
 * The applied crosshair configuration: six styles plus size, thickness, gap, opacity, colour and
 * outline. {@code CrosshairConfigurator} (start-up) and the settings menu (Apply) write it; the
 * {@code stonebreak:crosshair} contract publishes it to the crosshair document, which draws it.
 */
public class CrosshairSettings {

    // ── Crosshair styles ─────────────────────────────────────────────────
    public enum CrosshairStyle {
        SIMPLE_CROSS,    // Traditional + shape
        DOT,            // Single center dot
        CIRCLE,         // Hollow circle
        SQUARE,         // Hollow square
        T_SHAPE,        // T-shaped crosshair
        PLUS_DOT        // + with center dot
    }

    // ── Configuration ────────────────────────────────────────────────────
    private CrosshairStyle style       = CrosshairStyle.SIMPLE_CROSS;
    private float          size        = 16.0f;
    private float          thickness   = 2.0f;
    private float          gap         = 4.0f;
    private float          opacity     = 1.0f;
    private float[]        color       = {1.0f, 1.0f, 1.0f};  // RGB white
    private boolean        outline     = true;
    private float          outlineThickness = 1.0f;
    private float[]        outlineColor = {0.0f, 0.0f, 0.0f}; // RGB black

    /** Convert RGBA floats (0-1) to an ARGB int. */
    private static int rgbaToArgb(float r, float g, float b, float a) {
        int ir = Math.round(r * 255) & 0xFF;
        int ig = Math.round(g * 255) & 0xFF;
        int ib = Math.round(b * 255) & 0xFF;
        int ia = Math.round(a * 255) & 0xFF;
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    // ─────────────────────────────────────────────── Configuration setters

    public void setStyle(CrosshairStyle style)       { this.style = style; }
    public void setSize(float size)                  { this.size = Math.max(4f, size); }
    public void setThickness(float thickness)        { this.thickness = Math.max(1f, thickness); }
    public void setGap(float gap)                    { this.gap = Math.max(0f, gap); }
    public void setOpacity(float opacity)            { this.opacity = Math.max(0f, Math.min(1f, opacity)); }
    public void setColor(float r, float g, float b) {
        this.color[0] = Math.max(0f, Math.min(1f, r));
        this.color[1] = Math.max(0f, Math.min(1f, g));
        this.color[2] = Math.max(0f, Math.min(1f, b));
    }
    public void setOutline(boolean outline)          { this.outline = outline; }
    public void setOutlineThickness(float thickness) { this.outlineThickness = Math.max(0.5f, thickness); }
    public void setOutlineColor(float r, float g, float b) {
        this.outlineColor[0] = Math.max(0f, Math.min(1f, r));
        this.outlineColor[1] = Math.max(0f, Math.min(1f, g));
        this.outlineColor[2] = Math.max(0f, Math.min(1f, b));
    }

    // ─────────────────────────────────────────────── Getters

    /** The fill colour as drawn: the RGB colour at the crosshair's opacity (ARGB). */
    public int fillArgb() {
        return rgbaToArgb(color[0], color[1], color[2], opacity);
    }

    /** The outline (edge) colour as drawn, at the crosshair's opacity (ARGB). */
    public int edgeArgb() {
        return rgbaToArgb(outlineColor[0], outlineColor[1], outlineColor[2], opacity);
    }

    public CrosshairStyle getStyle()           { return style; }
    public float getSize()                     { return size; }
    public float getThickness()                { return thickness; }
    public float getGap()                      { return gap; }
    public float getOpacity()                  { return opacity; }
    public float[] getColor()                  { return color.clone(); }
    public boolean hasOutline()                { return outline; }
    public float getOutlineThickness()         { return outlineThickness; }
    public float[] getOutlineColor()           { return outlineColor.clone(); }
}
