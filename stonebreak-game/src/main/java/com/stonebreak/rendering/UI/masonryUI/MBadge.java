package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Small rounded pill with a short text — stack counts on tabs, "NEW"
 * markers, unread indicators, status chips.
 *
 * <p>Defaults to the accent-gold fill with dark text so it pops against the
 * stone surfaces; both colors are fluent for semantic variants (a red error
 * chip, a muted count). {@link #preferredWidth} sizes the pill to its text
 * and never lets it get narrower than it is tall, so one-character badges
 * stay perfect circles.
 */
public class MBadge extends MWidget {

    private static final int DEFAULT_TEXT = 0xFF2B2317;

    private String text = "";
    private int fillColor = MStyle.TEXT_ACCENT;
    private int textColor = DEFAULT_TEXT;
    private float fontSize = 12f;

    public MBadge(String text) {
        text(text);
    }

    // ─────────────────────────────────────────────── Fluent config

    public MBadge text(String value) { this.text = value != null ? value : ""; return this; }
    public MBadge fillColor(int c) { this.fillColor = c; return this; }
    public MBadge textColor(int c) { this.textColor = c; return this; }
    public MBadge fontSize(float v) { this.fontSize = v; return this; }

    public String text() { return text; }

    // Covariant returns keep fluent chains typed as MBadge.
    @Override public MBadge position(float x, float y) { super.position(x, y); return this; }
    @Override public MBadge size(float w, float h) { super.size(w, h); return this; }
    @Override public MBadge bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    /**
     * Intrinsic pill width for the current text at the current height —
     * text plus side padding, floored at the height (circular minimum).
     */
    public float preferredWidth(MasonryUI ui) {
        float textW = MPainter.measureWidth(fontFor(ui, fontSize), text);
        return Math.max(height, textW + height * 0.6f);
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || width <= 0f || height <= 0f) return;

        float radius = height / 2f;
        MPainter.fillRoundedRect(canvas, x, y, width, height, radius, fillColor);
        MPainter.strokeRoundedRect(canvas, x, y, width, height, radius, MStyle.BUTTON_BORDER, 1f);

        if (!text.isEmpty()) {
            Font font = fontFor(ui, fontSize);
            float baseline = y + height / 2f + fontSize * 0.35f * textScale();
            MPainter.drawCenteredString(canvas, text, x + width / 2f, baseline, font, textColor);
        }
    }
}
