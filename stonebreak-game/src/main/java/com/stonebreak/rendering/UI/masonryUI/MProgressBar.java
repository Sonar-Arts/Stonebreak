package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * General-purpose pill progress bar: dark track, colored fill tracking a
 * [0,1] fraction, and an optional centered label ("Loading...", "42%").
 *
 * <p>Distinct from {@link MVitalBar}, which is a stat readout (side label +
 * right-aligned value/max text). This one is for loading screens, crafting
 * progress, furnace burn, download/upload style feedback — anywhere the bar
 * itself is the whole widget.
 */
public class MProgressBar extends MWidget {

    private float fraction;
    private String label = "";
    private boolean showPercent;
    private int fillColor = MStyle.SLIDER_FILL;
    private int trackColor = MStyle.SLIDER_TRACK;
    private float fontSize = MStyle.FONT_META;

    // ─────────────────────────────────────────────── Fluent config

    /** Progress in [0,1]; values outside are clamped. */
    public MProgressBar fraction(float f) {
        this.fraction = Math.min(1f, Math.max(0f, f));
        return this;
    }

    /** Convenience: {@code value/max} with a zero-safe denominator. */
    public MProgressBar progress(float value, float max) {
        return fraction(max > 0f ? value / max : 0f);
    }

    public MProgressBar label(String text) { this.label = text != null ? text : ""; return this; }

    /** Appends "NN%" to the label (or shows it alone when the label is empty). */
    public MProgressBar showPercent(boolean v) { this.showPercent = v; return this; }

    public MProgressBar fillColor(int c) { this.fillColor = c; return this; }
    public MProgressBar trackColor(int c) { this.trackColor = c; return this; }
    public MProgressBar fontSize(float v) { this.fontSize = v; return this; }

    public float fraction() { return fraction; }

    // Covariant returns keep fluent chains typed as MProgressBar.
    @Override public MProgressBar position(float x, float y) { super.position(x, y); return this; }
    @Override public MProgressBar size(float w, float h) { super.size(w, h); return this; }
    @Override public MProgressBar bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || width <= 0f || height <= 0f) return;

        float radius = height / 2f;
        MPainter.fillRoundedRect(canvas, x, y, width, height, radius, trackColor);
        if (fraction > 0f) {
            // Never draw the fill narrower than its own end caps — a sliver
            // fill at 1% would otherwise degenerate into a distorted blob.
            float fillW = Math.max(height, width * fraction);
            MPainter.fillRoundedRect(canvas, x, y, fillW, height, radius, fillColor);
        }
        MPainter.strokeRoundedRect(canvas, x, y, width, height, radius, MStyle.BUTTON_BORDER, 1f);

        String text = composeLabel();
        if (!text.isEmpty()) {
            Font font = fontFor(ui, fontSize);
            float baseline = y + height / 2f + fontSize * 0.35f * textScale();
            MPainter.drawCenteredStringWithShadow(canvas, text, x + width / 2f, baseline,
                    font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        }
    }

    private String composeLabel() {
        if (!showPercent) return label;
        String pct = Math.round(fraction * 100f) + "%";
        return label.isEmpty() ? pct : label + " " + pct;
    }
}
