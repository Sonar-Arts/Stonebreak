package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * A keycap and what it does: {@code [SPACE] Confirm}, {@code [E] Talk}, {@code [ESC] Skip}. The
 * keycap is a small {@link MButton}-style stone surface ({@code MStyle.BUTTON_*} tokens), so a
 * prompt reads as a piece of the same UI as the buttons it stands in for.
 *
 * <p>The hint is laid out inside its bounds: vertically centred, horizontally per {@link #align}.
 * Size it with {@link #preferredWidth} / {@link #preferredHeight}, the {@link MBadge} way. Set
 * {@link #outlined} when the hint is drawn straight over the scene with no frame behind it: the
 * label then uses {@link MPainter#drawTextOutlined} instead of the ordinary shadow.
 */
public class MKeyHint extends MWidget {

    private static final float CAP_PAD_X = 6f;
    private static final float CAP_PAD_Y = 4f;
    private static final float GAP = 6f;

    private String key = "";
    private String label = "";
    private float alpha = 1f;
    private boolean outlined;
    private boolean pressed;
    private float fontSize = MStyle.FONT_META;
    private int labelColor = MStyle.TEXT_PRIMARY;
    private MPainter.Align align = MPainter.Align.LEFT;

    public MKeyHint() {}

    public MKeyHint(String key, String label) {
        key(key);
        label(label);
    }

    // ─────────────────────────────────────────────── Fluent config

    public MKeyHint key(String value) { this.key = value != null ? value : ""; return this; }
    public MKeyHint label(String value) { this.label = value != null ? value : ""; return this; }

    /** Whole-hint opacity 0..1, for fade in/out. */
    public MKeyHint alpha(float value) {
        this.alpha = Float.isNaN(value) ? 0f : MColor.clamp01(value);
        return this;
    }

    /** True when drawn straight over the scene: the label gets a dark outline instead of a shadow. */
    public MKeyHint outlined(boolean v) { this.outlined = v; return this; }

    /** Draws the keycap in its highlighted state (the key is down, or the hint is the focused one). */
    public MKeyHint pressed(boolean v) { this.pressed = v; return this; }

    public MKeyHint fontSize(float v) { this.fontSize = v > 0f ? v : MStyle.FONT_META; return this; }
    public MKeyHint labelColor(int c) { this.labelColor = c; return this; }
    public MKeyHint align(MPainter.Align value) { this.align = value != null ? value : MPainter.Align.LEFT; return this; }

    @Override public MKeyHint scale(float scale) { super.scale(scale); return this; }
    @Override public MKeyHint scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MKeyHint position(float x, float y) { super.position(x, y); return this; }
    @Override public MKeyHint size(float w, float h) { super.size(w, h); return this; }
    @Override public MKeyHint bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    public String key() { return key; }
    public String label() { return label; }
    public float alpha() { return alpha; }

    // ─────────────────────────────────────────────── Geometry

    /** Keycap + gap + label at the current font and scale. */
    public float preferredWidth(MasonryUI ui) {
        if (ui == null) return 0f;
        Font font = fontFor(ui, fontSize);
        if (font == null) return 0f;
        float capW = capWidth(font);
        float labelW = MPainter.measureWidth(font, label);
        float gap = capW > 0f && labelW > 0f ? GAP * textScale() : 0f;
        return capW + gap + labelW;
    }

    /** Keycap height at the current font and scale. */
    public float preferredHeight() {
        float s = textScale();
        return fontSize * s + 2f * CAP_PAD_Y * s;
    }

    /** {@code {x, y, w, h}} of the keycap as drawn; zeros when there is no key or no usable bounds. */
    public float[] capRect(MasonryUI ui) {
        if (ui == null || key.isEmpty() || !boundsUsable()) return new float[4];
        Font font = fontFor(ui, fontSize);
        if (font == null) return new float[4];
        float capW = capWidth(font);
        float capH = Math.min(preferredHeight(), height);
        return new float[]{startX(preferredWidth(ui)), y + (height - capH) / 2f, capW, capH};
    }

    private float capWidth(Font font) {
        if (key.isEmpty()) return 0f;
        float s = textScale();
        // Never narrower than tall, so a one-letter key is a square cap.
        return Math.max(preferredHeight(), MPainter.measureWidth(font, key) + 2f * CAP_PAD_X * s);
    }

    private float startX(float total) {
        return switch (align) {
            case LEFT -> x;
            case CENTER -> x + (width - total) / 2f;
            case RIGHT -> x + width - total;
        };
    }

    private boolean boundsUsable() {
        return finite(x) && finite(y) && finite(width) && finite(height) && width > 0f && height > 0f;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null) return;
        Canvas canvas = ui.canvas();
        if (canvas == null || alpha <= 0f || !boundsUsable()) return;
        Font font = fontFor(ui, fontSize);
        if (font == null) return;
        float s = textScale();
        float cy = y + height / 2f;
        float baseline = MPainter.baselineFor(cy, font.getSize());
        float pen = startX(preferredWidth(ui));

        if (!key.isEmpty()) {
            float[] cap = capRect(ui);
            int fill = pressed ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, cap[0], cap[1], cap[2], cap[3],
                    Math.min(MStyle.BUTTON_RADIUS * s, Math.min(cap[2], cap[3]) / 2f),
                    MColor.fade(fill, alpha), MColor.fade(MStyle.BUTTON_BORDER, alpha),
                    MColor.fade(MStyle.BUTTON_HIGHLIGHT, alpha), MColor.fade(MStyle.BUTTON_SHADOW, alpha),
                    MColor.fade(MStyle.BUTTON_DROP_SHADOW, alpha),
                    MColor.fade(MStyle.BUTTON_NOISE_DARK, alpha), MColor.fade(MStyle.BUTTON_NOISE_LIGHT, alpha));
            int keyColor = pressed ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            MPainter.drawText(canvas, key, cap[0] + cap[2] / 2f, baseline, font,
                    MColor.fade(keyColor, alpha), MPainter.Align.CENTER);
            pen += cap[2] + (label.isEmpty() ? 0f : GAP * s);
        }

        if (!label.isEmpty()) {
            int color = MColor.fade(labelColor, alpha);
            if (outlined) {
                MPainter.drawTextOutlined(canvas, label, pen, baseline, font, color, MPainter.Align.LEFT,
                        Math.max(2f, 2.5f * s));
            } else {
                MPainter.drawText(canvas, label, pen, baseline, font, color, MPainter.Align.LEFT);
            }
        }
    }
}
