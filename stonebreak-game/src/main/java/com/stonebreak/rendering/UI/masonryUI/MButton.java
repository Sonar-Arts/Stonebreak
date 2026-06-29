package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Minecraft-style beveled button. Fluent builders, single-responsibility
 * click handler. Standard MasonryUI widget — no legacy renderer coupling.
 */
public class MButton extends MWidget {

    /** Optional vector arrow icon drawn beside the label (replaces font glyphs). */
    public enum Arrow { NONE, LEFT, RIGHT }

    protected String text;
    protected boolean enabled = true;
    protected float fontSize = MStyle.FONT_BUTTON;
    protected Arrow arrow = Arrow.NONE;
    private Runnable onClick;

    public MButton(String text) {
        this.text = text;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MButton onClick(Runnable action) { this.onClick = action; return this; }
    public MButton text(String value) { this.text = value; return this; }
    public MButton enabled(boolean v) { this.enabled = v; return this; }
    public MButton fontSize(float v) { this.fontSize = v; return this; }

    /** Draws a {@link MPainter#navArrow} icon on the leading/trailing side of the label. */
    public MButton arrow(Arrow side) { this.arrow = side; return this; }

    public String text() { return text; }
    public boolean enabled() { return enabled; }

    /**
     * Intrinsic width needed to render the label (and arrow icon, if any) with
     * comfortable horizontal padding. Lets callers size a button to its content
     * each frame instead of guessing a fixed width — measurement needs the live
     * font, so it can't be done at construction time.
     */
    public float preferredWidth(MasonryUI ui) {
        Font font = fontFor(ui, fontSize);
        float scale = textScale();
        boolean hasText = text != null && !text.isEmpty();
        float textW = hasText ? MPainter.measureWidth(font, text) : 0f;
        float arrowW = arrow == Arrow.NONE ? 0f : font.getSize();
        float gap = (hasText && arrow != Arrow.NONE) ? 7f * scale : 0f;
        return textW + arrowW + gap + 32f * scale; // 16px breathing room each side
    }

    // Covariant returns keep fluent chains typed as MButton so assignment works.
    @Override public MButton scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MButton position(float x, float y) { super.position(x, y); return this; }
    @Override public MButton size(float width, float height) { super.size(width, height); return this; }
    @Override public MButton bounds(float x, float y, float width, float height) {
        super.bounds(x, y, width, height); return this;
    }

    public void setText(String value) { this.text = value; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public void setOnClick(Runnable action) { this.onClick = action; }
    public Runnable getOnClick() { return onClick; }

    // ─────────────────────────────────────────────── Interaction

    /**
     * Fires the click callback regardless of hit test. Call only after
     * verifying the click hit this widget (or use {@link #handleClick}).
     */
    public void click() {
        if (enabled && onClick != null) onClick.run();
    }

    /**
     * Returns true if the click hit and fired the callback.
     */
    public boolean handleClick(float mouseX, float mouseY) {
        if (contains(mouseX, mouseY)) {
            click();
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        drawBody(canvas);
        drawLabel(canvas, fontFor(ui, fontSize));
    }

    protected void drawBody(Canvas canvas) {
        int fill = !enabled ? MStyle.BUTTON_FILL_DIS
                : (hovered || selected) ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, x, y, width, height, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    }

    protected void drawLabel(Canvas canvas, Font font) {
        boolean hasText = text != null && !text.isEmpty();
        if (!hasText && arrow == Arrow.NONE) return;
        int color = !enabled ? MStyle.TEXT_DISABLED
                : (hovered || selected) ? MStyle.TEXT_ACCENT
                : MStyle.TEXT_PRIMARY;
        float ty = y + height / 2f + 7f * textScale();

        if (arrow == Arrow.NONE) {
            float tx = x + width / 2f;
            MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, font, color, MStyle.TEXT_SHADOW);
            return;
        }

        // Lay out [arrow][gap][text] (or the mirror) as one centered group.
        float scale = textScale();
        float arrowSize = font.getSize();
        float gap = hasText ? 7f * scale : 0f;
        float textW = hasText ? MPainter.measureWidth(font, text) : 0f;
        float groupW = arrowSize + gap + textW;
        float startX = x + width / 2f - groupW / 2f;
        float arrowTop = y + height / 2f - arrowSize / 2f;

        if (arrow == Arrow.LEFT) {
            MPainter.navArrow(canvas, startX, arrowTop, arrowSize, arrowSize, true, color, MStyle.TEXT_SHADOW);
            if (hasText) {
                MPainter.drawStringWithShadow(canvas, text, startX + arrowSize + gap, ty,
                        font, color, MStyle.TEXT_SHADOW);
            }
        } else { // RIGHT
            if (hasText) {
                MPainter.drawStringWithShadow(canvas, text, startX, ty, font, color, MStyle.TEXT_SHADOW);
            }
            MPainter.navArrow(canvas, startX + textW + gap, arrowTop, arrowSize, arrowSize, false,
                    color, MStyle.TEXT_SHADOW);
        }
    }
}
