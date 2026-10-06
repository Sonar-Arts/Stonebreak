package com.openmason.engine.ui.runtime;

/**
 * An axis-aligned rectangle in device pixels. Hit tests treat both edges as inside, matching
 * today's Masonry input (#283).
 */
public record UiRect(float x, float y, float width, float height) {

    public static final UiRect EMPTY = new UiRect(0, 0, 0, 0);

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    /** Inclusive on all four edges. */
    public boolean contains(float px, float py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    /** Smallest rect covering both; an empty side is ignored. */
    public UiRect union(UiRect o) {
        if (o == null || o.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return o;
        }
        if (o.x >= x && o.y >= y && o.right() <= right() && o.bottom() <= bottom()) {
            return this; // already covered: a per-frame repaint (a script canvas) allocates nothing
        }
        float nx = Math.min(x, o.x);
        float ny = Math.min(y, o.y);
        return new UiRect(nx, ny, Math.max(right(), o.right()) - nx, Math.max(bottom(), o.bottom()) - ny);
    }
}
