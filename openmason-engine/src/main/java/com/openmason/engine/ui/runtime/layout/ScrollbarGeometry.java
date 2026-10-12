package com.openmason.engine.ui.runtime.layout;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;

/**
 * Track and thumb rects of a scroll container's scrollbars (#287 painting, #288 dragging), so
 * the painter draws exactly what pointer input grabs. Bars are 4 logical px thick along the
 * right and bottom edges; a thumb is at least twice as long as it is thick.
 *
 * @param track the full bar
 * @param thumb the draggable part
 */
public record ScrollbarGeometry(UiRect track, UiRect thumb) {

    public static final float THICKNESS = 4f;

    /** The vertical bar, or null when the content fits. */
    public static ScrollbarGeometry vertical(UiElement el, float scale) {
        if (el.maxScrollY() <= 0) {
            return null;
        }
        UiRect r = el.rect();
        float t = THICKNESS * scale;
        float content = r.height() + el.maxScrollY();
        float h = Math.max(t * 2, r.height() * r.height() / content);
        float y = r.y() + (r.height() - h) * (el.scrollY() / el.maxScrollY());
        return new ScrollbarGeometry(new UiRect(r.right() - t, r.y(), t, r.height()), new UiRect(r.right() - t, y, t, h));
    }

    /** The horizontal bar, or null when the content fits. */
    public static ScrollbarGeometry horizontal(UiElement el, float scale) {
        if (el.maxScrollX() <= 0) {
            return null;
        }
        UiRect r = el.rect();
        float t = THICKNESS * scale;
        float content = r.width() + el.maxScrollX();
        float w = Math.max(t * 2, r.width() * r.width() / content);
        float x = r.x() + (r.width() - w) * (el.scrollX() / el.maxScrollX());
        return new ScrollbarGeometry(new UiRect(r.x(), r.bottom() - t, r.width(), t), new UiRect(x, r.bottom() - t, w, t));
    }

    /** Scroll offset that puts the thumb's top (left) at {@code thumbStart} along the track. */
    public float offsetFor(float thumbStart, boolean verticalBar, float maxScroll) {
        float travel = verticalBar ? track.height() - thumb.height() : track.width() - thumb.width();
        if (travel <= 0) {
            return 0;
        }
        float along = thumbStart - (verticalBar ? track.y() : track.x());
        return Math.clamp(along / travel, 0, 1) * maxScroll;
    }
}
