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
