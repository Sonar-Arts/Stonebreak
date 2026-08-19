package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Section divider: a centered label flanked by thin horizontal rules, the
 * standard way settings and inventory panels group related widgets. An
 * empty label degrades to a plain full-width divider rule.
 *
 * <p>The rule is drawn as a 1px shadow line under a 1px highlight line —
 * the same engraved two-tone treatment the stone bevels use, so dividers
 * look carved into the panel rather than painted on it.
 */
public class MSectionHeader extends MWidget {

    private static final int RULE_SHADOW = 0x66000000;
    private static final int RULE_HIGHLIGHT = 0x2EFFFFFF;
    private static final float LABEL_GAP = 10f;

    private String label = "";
    private float fontSize = MStyle.FONT_META;
    private int textColor = MStyle.TEXT_SECONDARY;

    public MSectionHeader(String label) {
        label(label);
    }

    /** Plain divider rule with no label. */
    public MSectionHeader() {
        this("");
    }

    // ─────────────────────────────────────────────── Fluent config

    public MSectionHeader label(String value) { this.label = value != null ? value : ""; return this; }
    public MSectionHeader fontSize(float v) { this.fontSize = v; return this; }
    public MSectionHeader textColor(int c) { this.textColor = c; return this; }

    public String label() { return label; }

    // Covariant returns keep fluent chains typed as MSectionHeader.
    @Override public MSectionHeader position(float x, float y) { super.position(x, y); return this; }
    @Override public MSectionHeader size(float w, float h) { super.size(w, h); return this; }
    @Override public MSectionHeader bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || width <= 0f || height <= 0f) return;

        float midY = y + height / 2f;
        if (label.isEmpty()) {
            drawRule(canvas, x, x + width, midY);
            return;
        }

        Font font = fontFor(ui, fontSize);
        float scale = textScale();
        float textW = MPainter.measureWidth(font, label);
        float gap = LABEL_GAP * scale;
        float cx = x + width / 2f;
        float baseline = midY + fontSize * 0.35f * scale;

        drawRule(canvas, x, cx - textW / 2f - gap, midY);
        drawRule(canvas, cx + textW / 2f + gap, x + width, midY);
        MPainter.drawCenteredStringWithShadow(canvas, label, cx, baseline,
                font, textColor, MStyle.TEXT_SHADOW);
    }

    private static void drawRule(Canvas canvas, float x0, float x1, float midY) {
        if (x1 - x0 < 2f) return;
        MPainter.fillRect(canvas, x0, midY - 1f, x1 - x0, 1f, RULE_SHADOW);
        MPainter.fillRect(canvas, x0, midY, x1 - x0, 1f, RULE_HIGHLIGHT);
    }
}
