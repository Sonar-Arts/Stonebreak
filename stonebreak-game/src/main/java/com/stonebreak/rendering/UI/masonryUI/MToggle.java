package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Boolean toggle widget. Visually matches {@link MButton} but shows an
 * on/off indicator square on the left side and paints the label in
 * accent/primary depending on state.
 *
 * Usage:
 * <pre>
 * MToggle t = new MToggle("Wireframes");
 * t.bounds(x, y, 200, 24);
 * t.handleClick(mx, my);
 * t.render(ui);
 * </pre>
 */
public class MToggle extends MWidget {

    private static final int CHECK_OFF = 0xFF6B6B6B;
    private static final int CHECK_ON  = MStyle.TEXT_ACCENT;
    private static final float CHECK_SIZE = 14f;
    private static final float CHECK_PAD  = 6f;

    protected String text;
    protected boolean checked;
    protected boolean enabled = true;
    protected float fontSize = 15f;
    private Runnable onToggle;

    public MToggle(String text) {
        this.text = text;
    }

    public MToggle(String text, boolean checked) {
        this.text = text;
        this.checked = checked;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MToggle onToggle(Runnable callback) { this.onToggle = callback; return this; }
    public MToggle text(String value) { this.text = value; return this; }
    public MToggle checked(boolean v) { this.checked = v; return this; }
    public MToggle enabled(boolean v) { this.enabled = v; return this; }
    public MToggle fontSize(float v) { this.fontSize = v; return this; }

    public String text() { return text; }
    public boolean isChecked() { return checked; }
    public boolean enabled() { return enabled; }

    @Override public MToggle position(float x, float y) { super.position(x, y); return this; }
    @Override public MToggle size(float w, float h) { super.size(w, h); return this; }
    @Override public MToggle bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    public void setText(String value) { this.text = value; }
    public void setChecked(boolean v) { this.checked = v; }
    public void setOnToggle(Runnable callback) { this.onToggle = callback; }
    public Runnable getOnToggle() { return onToggle; }

    // ─────────────────────────────────────────────── Interaction

    public void toggle() {
        if (!enabled) return;
        checked = !checked;
        if (onToggle != null) onToggle.run();
    }

    public boolean handleClick(float mouseX, float mouseY) {
        if (contains(mouseX, mouseY)) {
            toggle();
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        Font font = ui.fonts().get(fontSize);

        // Draw the stone panel background
        int bgFill = !enabled ? MStyle.BUTTON_FILL_DIS
                : (hovered || selected) ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, x, y, width, height, MStyle.BUTTON_RADIUS,
                bgFill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

        // Check-box square
        float checkX = x + CHECK_PAD;
        float checkY = y + (height - CHECK_SIZE) / 2f;
        int checkColor = checked ? CHECK_ON : CHECK_OFF;
        MPainter.fillRect(canvas, checkX, checkY, CHECK_SIZE, CHECK_SIZE, checkColor);
        // Thin border around check-box
        MPainter.strokeRect(canvas, checkX, checkY, CHECK_SIZE, CHECK_SIZE, MStyle.BUTTON_BORDER, 1f);

        // Inner check fill (slightly lighter solid when on)
        if (checked) {
            MPainter.fillRect(canvas, checkX + 2f, checkY + 2f, CHECK_SIZE - 4f, CHECK_SIZE - 4f, 0xB4FFFFFF);
        }

        // Label text
        if (text != null && !text.isEmpty()) {
            float labelX = checkX + CHECK_SIZE + CHECK_PAD;
            float labelY = y + height / 2f + fontSize * 0.4f;
            int color = !enabled ? MStyle.TEXT_DISABLED
                    : checked ? MStyle.TEXT_ACCENT
                    : MStyle.TEXT_PRIMARY;
            MPainter.drawStringWithShadow(canvas, text, labelX, labelY, font, color, MStyle.TEXT_SHADOW);
        }
    }
}
