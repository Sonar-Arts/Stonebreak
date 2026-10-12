package com.openmason.main.systems.uiEditor.canvas;

import com.openmason.engine.ui.runtime.UiRect;

/** An edge-form rectangle in frame pixels (left, top, right, bottom). */
public record Box(float l, float t, float r, float b) {

    public static Box of(UiRect r) {
        return new Box(r.x(), r.y(), r.right(), r.bottom());
    }

    public float width() {
        return r - l;
    }

    public float height() {
        return b - t;
    }

    public float cx() {
        return (l + r) / 2f;
    }

    public float cy() {
        return (t + b) / 2f;
    }

    public Box offset(float dx, float dy) {
        return new Box(l + dx, t + dy, r + dx, b + dy);
    }

    public boolean contains(float x, float y) {
        return x >= l && x <= r && y >= t && y <= b;
    }

    /** True when the boxes overlap or touch. */
    public boolean intersects(Box o) {
        return l <= o.r && o.l <= r && t <= o.b && o.t <= b;
    }

    /** Normalized box spanning two corner points (a marquee). */
    public static Box spanning(float x0, float y0, float x1, float y1) {
        return new Box(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
    }

    public Box union(Box o) {
        return o == null ? this : new Box(Math.min(l, o.l), Math.min(t, o.t), Math.max(r, o.r), Math.max(b, o.b));
    }
}
