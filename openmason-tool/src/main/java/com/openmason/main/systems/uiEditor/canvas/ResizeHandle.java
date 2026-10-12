package com.openmason.main.systems.uiEditor.canvas;

/**
 * The eight resize handles of a selection box. {@code dx}/{@code dy} say which edges a handle
 * moves: -1 the left/top edge, +1 the right/bottom edge, 0 neither.
 */
public enum ResizeHandle {
    NW(-1, -1), N(0, -1), NE(1, -1), E(1, 0), SE(1, 1), S(0, 1), SW(-1, 1), W(-1, 0);

    public final int dx;
    public final int dy;

    ResizeHandle(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    /** Handle centre for a box, in the box's coordinate space. */
    public float x(float left, float right) {
        return dx < 0 ? left : dx > 0 ? right : (left + right) / 2f;
    }

    public float y(float top, float bottom) {
        return dy < 0 ? top : dy > 0 ? bottom : (top + bottom) / 2f;
    }

    /** The handle within {@code radius} of {@code (px, py)} around a screen box, or null. */
    public static ResizeHandle hit(float left, float top, float right, float bottom, float px, float py, float radius) {
        ResizeHandle best = null;
        float bestD = radius * radius;
        for (ResizeHandle h : values()) {
            if ((h.dx == 0 && right - left < 3 * radius) || (h.dy == 0 && bottom - top < 3 * radius)) {
                continue; // edge-middle handles hide on tiny boxes so corners stay grabbable
            }
            float ddx = px - h.x(left, right);
            float ddy = py - h.y(top, bottom);
            float d = ddx * ddx + ddy * ddy;
            if (d <= bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }
}
