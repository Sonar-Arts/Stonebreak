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

    // Optional chip styling; none of it set = the original solid pill, pixel for pixel.
    private boolean outlined;
    private int outlineColor;
    private String trailing = "";
    private int dotColor;

    public MBadge(String text) {
        text(text);
    }

    // ─────────────────────────────────────────────── Fluent config

    public MBadge text(String value) { this.text = value != null ? value : ""; return this; }
    public MBadge fillColor(int c) { this.fillColor = c; return this; }
    public MBadge textColor(int c) { this.textColor = c; return this; }
    public MBadge fontSize(float v) { this.fontSize = v; return this; }

    /**
     * Status-chip look: dark {@link MStyle#DROPDOWN_FILL} fill, a 1px outline and shadowed label in
     * {@code color}, instead of the solid pill. For chips that sit on HUD frames, where a row of
     * solid gold pills would shout.
     */
    public MBadge outlined(int color) { this.outlined = true; this.outlineColor = color; return this; }

    /** A second, dimmer text after the label: a timer ("12s"), a count. Null/empty = none. */
    public MBadge trailing(String value) { this.trailing = value != null ? value : ""; return this; }

    /** Small leading status dot; a fully transparent colour (0) = none. */
    public MBadge dot(int color) { this.dotColor = color; return this; }

    public String text() { return text; }
    public String trailing() { return trailing; }
    public boolean isOutlined() { return outlined; }

    // Covariant returns keep fluent chains typed as MBadge.
    @Override public MBadge scale(float scale) { super.scale(scale); return this; }
    @Override public MBadge scaleText(boolean v) { super.scaleText(v); return this; }
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
        return Math.max(height, contentWidth(fontFor(ui, fontSize)) + height * 0.6f);
    }

    // ─────────────────────────────────────────────── Chip content layout

    private boolean hasDot() { return (dotColor & 0xFF000000) != 0; }
    private boolean plain() { return !outlined && trailing.isEmpty() && !hasDot(); }

    private float dotSize() { return Math.min(height * 0.32f, 8f * textScale()); }
    private float dotGap() { return text.isEmpty() && trailing.isEmpty() ? 0f : 4f * textScale(); }
    private float trailingGap() { return text.isEmpty() ? 0f : 5f * textScale(); }

    /** Width of [dot][label][trailing] with their gaps; just the label width for a plain badge. */
    private float contentWidth(Font font) {
        float w = MPainter.measureWidth(font, text);
        if (hasDot()) w += dotSize() + dotGap();
        if (!trailing.isEmpty()) w += trailingGap() + MPainter.measureWidth(font, trailing);
        return w;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || width <= 0f || height <= 0f) return;

        if (!plain()) {
            renderChip(ui, canvas);
            return;
        }

        float radius = height / 2f;
        MPainter.fillRoundedRect(canvas, x, y, width, height, radius, fillColor);
        MPainter.strokeRoundedRect(canvas, x, y, width, height, radius, MStyle.BUTTON_BORDER, 1f);

        if (!text.isEmpty()) {
            Font font = fontFor(ui, fontSize);
            float baseline = y + height / 2f + fontSize * 0.35f * textScale();
            MPainter.drawCenteredString(canvas, text, x + width / 2f, baseline, font, textColor);
        }
    }

    /** The styled variants: content is laid out left to right and centred as one group. */
    private void renderChip(MasonryUI ui, Canvas canvas) {
        float s = textScale();
        float radius = height / 2f;
        if (outlined) {
            MPainter.fillRoundedRect(canvas, x, y, width, height, radius, MStyle.DROPDOWN_FILL);
            MPainter.strokeRoundedRect(canvas, x, y, width, height, radius, outlineColor, Math.max(1f, s));
        } else {
            MPainter.fillRoundedRect(canvas, x, y, width, height, radius, fillColor);
            MPainter.strokeRoundedRect(canvas, x, y, width, height, radius, MStyle.BUTTON_BORDER, 1f);
        }

        Font font = fontFor(ui, fontSize);
        float cx = x + (width - contentWidth(font)) / 2f;
        float cy = y + height / 2f;
        if (hasDot()) {
            float d = dotSize();
            MPainter.fillCircle(canvas, cx + d / 2f, cy, d / 2f, dotColor);
            cx += d + dotGap();
        }
        if (font == null) return;
        float baseline = cy + fontSize * 0.35f * s;
        int labelColor = outlined ? outlineColor : textColor;
        if (!text.isEmpty()) {
            drawChipText(canvas, text, cx, baseline, font, labelColor);
            cx += MPainter.measureWidth(font, text);
        }
        if (!trailing.isEmpty()) {
            int dim = outlined ? MStyle.TEXT_SECONDARY : MColor.fade(textColor, 0.65f);
            drawChipText(canvas, trailing, cx + trailingGap(), baseline, font, dim);
        }
    }

    /** Light text on the dark outlined chip is shadowed (house rule); dark ink on a solid pill is not. */
    private void drawChipText(Canvas canvas, String value, float left, float baseline, Font font, int color) {
        if (outlined) MPainter.drawText(canvas, value, left, baseline, font, color, MPainter.Align.LEFT);
        else MPainter.drawString(canvas, value, left, baseline, font, color);
    }
}
