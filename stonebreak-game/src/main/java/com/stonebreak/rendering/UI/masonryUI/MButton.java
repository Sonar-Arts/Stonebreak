package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Minecraft-style beveled button. Fluent builders, single-responsibility
 * click handler. Standard MasonryUI widget — no legacy renderer coupling.
 */
public class MButton extends MWidget {

    protected String text;
    protected boolean enabled = true;
    protected float fontSize = MStyle.FONT_BUTTON;
    private Runnable onClick;

    public MButton(String text) {
        this.text = text;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MButton onClick(Runnable action) { this.onClick = action; return this; }
    public MButton text(String value) { this.text = value; return this; }
    public MButton enabled(boolean v) { this.enabled = v; return this; }
    public MButton fontSize(float v) { this.fontSize = v; return this; }

    public String text() { return text; }
    public boolean enabled() { return enabled; }

    // Covariant returns keep fluent chains typed as MButton so assignment works.
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
        drawLabel(canvas, ui.fonts().get(fontSize));
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
        if (text == null || text.isEmpty()) return;
        int color = !enabled ? MStyle.TEXT_DISABLED
                : (hovered || selected) ? MStyle.TEXT_ACCENT
                : MStyle.TEXT_PRIMARY;
        float tx = x + width / 2f;
        float ty = y + height / 2f + 7f;
        MPainter.drawCenteredStringWithShadow(canvas, text, tx, ty, font, color, MStyle.TEXT_SHADOW);
    }
}
