package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Boolean toggle widget. Visually matches {@link MButton} but shows an
 * on/off indicator square on the left side and paints the label in
 * accent/primary depending on state.
 *
 * Extends MButton to reuse the stone-surface body; only the checkbox
 * overlay and label layout are toggle-specific.
 *
 * Usage:
 * <pre>
 * MToggle t = new MToggle("Wireframes");
 * t.bounds(x, y, 200, 24);
 * t.handleClick(mx, my);
 * t.render(ui);
 * </pre>
 */
public class MToggle extends MButton {

    private static final int CHECK_OFF        = 0xFF6B6B6B;
    private static final int CHECK_ON         = MStyle.TEXT_ACCENT;
    private static final int CHECK_INNER_FILL = 0xB4FFFFFF;
    private static final float CHECK_SIZE     = 14f;
    private static final float CHECK_PAD      = 6f;

    protected boolean checked;
    private Runnable onToggle;

    public MToggle(String text) {
        super(text);
        fontSize = 15f;
    }

    public MToggle(String text, boolean checked) {
        super(text);
        fontSize = 15f;
        this.checked = checked;
    }

    // ─────────────────────────────────────────────── Fluent config (toggle-specific)

    public MToggle onToggle(Runnable callback) { this.onToggle = callback; return this; }
    public MToggle checked(boolean v) { this.checked = v; return this; }

    public boolean isChecked() { return checked; }
    public void setChecked(boolean v) { this.checked = v; }
    public void setOnToggle(Runnable callback) { this.onToggle = callback; }
    public Runnable getOnToggle() { return onToggle; }

    // Covariant returns keep fluent chains typed as MToggle.
    @Override public MToggle position(float x, float y) { super.position(x, y); return this; }
    @Override public MToggle size(float w, float h) { super.size(w, h); return this; }
    @Override public MToggle bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

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

        // Reuse inherited stone-surface body
        drawBody(canvas);

        // Check-box square
        float checkX = x + CHECK_PAD;
        float checkY = y + (height - CHECK_SIZE) / 2f;
        int checkColor = checked ? CHECK_ON : CHECK_OFF;
        MPainter.fillRect(canvas, checkX, checkY, CHECK_SIZE, CHECK_SIZE, checkColor);
        MPainter.strokeRect(canvas, checkX, checkY, CHECK_SIZE, CHECK_SIZE, MStyle.BUTTON_BORDER, 1f);

        // Inner check fill (slightly lighter solid when on)
        if (checked) {
            MPainter.fillRect(canvas, checkX + 2f, checkY + 2f, CHECK_SIZE - 4f, CHECK_SIZE - 4f, CHECK_INNER_FILL);
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
