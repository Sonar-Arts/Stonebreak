package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.function.Consumer;

/**
 * Horizontal slider with optional label. Position denotes the CENTER of the
 * track (matches legacy Settings-menu positioning); the 3x-tall hit box makes
 * grabbing the track forgiving without visual bloat.
 */
public final class MSlider extends MWidget {

    private String label;
    private float min, max, value;
    private boolean dragging;
    private Consumer<Float> onChange;
    private float trackHeight = 20f;

    public MSlider(String label, float min, float max, float initial) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.value = clamp(initial);
        this.height = trackHeight;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MSlider trackHeight(float h) {
        this.trackHeight = h;
        this.height = h;
        return this;
    }
    public MSlider onChange(Consumer<Float> consumer) { this.onChange = consumer; return this; }

    @Override public MSlider position(float x, float y) { super.position(x, y); return this; }
    @Override public MSlider size(float w, float h) { super.size(w, h); return this; }
    @Override public MSlider bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    public void setOnChange(Consumer<Float> consumer) { this.onChange = consumer; }
    public void setLabel(String label) { this.label = label; }

    public float value() { return value; }
    public void setValue(float v) {
        float clamped = clamp(v);
        if (clamped != value) {
            value = clamped;
            if (onChange != null) onChange.accept(value);
        }
    }
    public float min() { return min; }
    public float max() { return max; }
    public void setRange(float min, float max) {
        this.min = min;
        this.max = max;
        setValue(value);
    }
    public float normalized() {
        if (max == min) return 0f;
        return (value - min) / (max - min);
    }

    public boolean isDragging() { return dragging; }

    // ─────────────────────────────────────────────── Hit-test (expanded)

    /**
     * Position is the track CENTER. Returns true for a 3× tall hit area so
     * clicks near but not exactly on the track still grab it.
     */
    @Override
    public boolean contains(float px, float py) {
        float left = x - width / 2f;
        float top = y - height * 3f / 2f;
        return px >= left && px <= left + width && py >= top && py <= top + height * 3f;
    }

    @Override
    public boolean isMouseOver(float mouseX, float mouseY) { return contains(mouseX, mouseY); }

    // ─────────────────────────────────────────────── Interaction

    public boolean handleClick(float mouseX, float mouseY) {
        if (contains(mouseX, mouseY)) {
            dragging = true;
            updateFromMouseX(mouseX);
            return true;
        }
        return false;
    }

    public void handleDrag(float mouseX) {
        if (dragging) updateFromMouseX(mouseX);
    }

    public void stopDragging() { dragging = false; }

    public void adjustValue(float step) { setValue(value + step); }

    private void updateFromMouseX(float mouseX) {
        float left = x - width / 2f;
        float normalized = Math.max(0f, Math.min(1f, (mouseX - left) / width));
        setValue(min + normalized * (max - min));
    }

    private float clamp(float v) { return Math.max(min, Math.min(max, v)); }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        Font labelFont = ui.fonts().get(MStyle.FONT_META);
        if (label != null && !label.isEmpty()) {
            String display = String.format("%s: %d%%", label, Math.round(normalized() * 100f));
            MPainter.drawCenteredStringWithShadow(canvas, display, x, y - 14f, labelFont,
                    MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
        }

        float left = x - width / 2f;
        float top = y - height / 2f;

        MPainter.fillRoundedRect(canvas, left, top, width, height, 3f, MStyle.SLIDER_TRACK);
        float fillW = Math.max(0f, Math.min(width, width * normalized()));
        if (fillW > 0f) {
            MPainter.fillRoundedRect(canvas, left, top, fillW, height, 3f, MStyle.SLIDER_FILL);
        }

        float thumbX = left + fillW - 4f;
        MPainter.fillRect(canvas, thumbX, top - 3f, 8f, height + 6f, MStyle.SLIDER_THUMB);
        MPainter.strokeRect(canvas, thumbX, top - 3f, 8f, height + 6f, MStyle.SLIDER_THUMB_EDGE, 1.5f);
    }
}
