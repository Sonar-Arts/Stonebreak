package com.stonebreak.rendering.UI.masonryUI;

/**
 * Base class for all MasonryUI widgets. Owns position, size, and the two
 * standard interaction flags (hovered/selected). Subclasses add their own
 * state and override {@link #render}.
 *
 * Keeping position on each widget (rather than passing it in per frame) means
 * hit-testing can live on the widget too — which lines up with how the old
 * Stonebreak settings menu was already wired and lets MouseHandler stay
 * widget-centric.
 */
public abstract class MWidget {

    protected float x, y, width, height;
    protected boolean hovered;
    protected boolean selected;

    /**
     * When true this widget renders its text at {@code baseSize * uiScale} so
     * labels track the surrounding scaled geometry. Opt-in (default false) so
     * widgets on screens that are not scale-aware keep their fixed text size.
     */
    protected boolean scaleText = false;

    public MWidget scaleText(boolean v) { this.scaleText = v; return this; }
    public boolean isScaleText() { return scaleText; }

    /** UI scale to apply to this widget's text/offsets, or 1.0 when not scale-aware. */
    protected float textScale() {
        return scaleText ? com.stonebreak.config.Settings.getInstance().getUiScale() : 1f;
    }

    /** Fetches a font at {@code baseSize}, scaled by the UI scale when {@link #scaleText} is set. */
    protected io.github.humbleui.skija.Font fontFor(MasonryUI ui, float baseSize) {
        return scaleText ? ui.fonts().getScaled(baseSize) : ui.fonts().get(baseSize);
    }

    // ─────────────────────────────────────────────── Layout

    public MWidget position(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public MWidget size(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public MWidget bounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public float x() { return x; }
    public float y() { return y; }
    public float width() { return width; }
    public float height() { return height; }

    // ─────────────────────────────────────────────── Hit-test

    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /** Convenience alias preserved from legacy call sites. */
    public boolean isMouseOver(float mouseX, float mouseY) {
        return contains(mouseX, mouseY);
    }

    public boolean updateHover(float mouseX, float mouseY) {
        hovered = contains(mouseX, mouseY);
        return hovered;
    }

    // ─────────────────────────────────────────────── State

    public boolean isHovered() { return hovered; }
    public void setHovered(boolean v) { this.hovered = v; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean v) { this.selected = v; }

    // ─────────────────────────────────────────────── Render

    public abstract void render(MasonryUI ui);
}
