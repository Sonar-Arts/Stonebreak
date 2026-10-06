package com.openmason.main.systems.uiEditor.canvas;

import com.openmason.engine.format.omui.UiValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns canvas drags into authored style. Geometry is measured in frame (device) pixels; style
 * is authored in logical pixels, so every delta is divided by the document's scale (UI scale x
 * pixel ratio). Values are rounded to whole logical pixels, the way an author would type them.
 */
public final class ResizeMath {

    /** Smallest size a drag can produce, in logical pixels. */
    public static final float MIN_SIZE = 1f;

    private ResizeMath() {
    }

    /** {@code start} with the handle's edges moved by {@code (dx, dy)}; edges never cross. */
    public static Box resized(Box start, ResizeHandle h, float dx, float dy) {
        float l = start.l();
        float t = start.t();
        float r = start.r();
        float b = start.b();
        if (h.dx < 0) {
            l = Math.min(r - 1, l + dx);
        } else if (h.dx > 0) {
            r = Math.max(l + 1, r + dx);
        }
        if (h.dy < 0) {
            t = Math.min(b - 1, t + dy);
        } else if (h.dy > 0) {
            b = Math.max(t + 1, b + dy);
        }
        return new Box(l, t, r, b);
    }

    /**
     * Style declarations for resizing an element from {@code start} to {@code end} (frame px).
     *
     * @param absolute   the element is {@code position: absolute}; moving its left/top edge then
     *                   moves it ({@code left}/{@code top}) instead of only growing it
     * @param offsetLeft its current {@code left} in logical px (absolute only; from the rect when unset)
     * @param offsetTop  its current {@code top} in logical px
     */
    public static Map<String, UiValue> resizeStyle(Box start, Box end, ResizeHandle h, float scale, boolean absolute,
                                                   float offsetLeft, float offsetTop) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        if (h.dx != 0) {
            out.put("width", px(Math.max(MIN_SIZE, end.width() / scale)));
            if (absolute && h.dx < 0) {
                out.put("left", px(offsetLeft + (end.l() - start.l()) / scale));
            }
        }
        if (h.dy != 0) {
            out.put("height", px(Math.max(MIN_SIZE, end.height() / scale)));
            if (absolute && h.dy < 0) {
                out.put("top", px(offsetTop + (end.t() - start.t()) / scale));
            }
        }
        return out;
    }

    /** {@code left}/{@code top} for moving an absolute element by a frame-pixel delta. */
    public static Map<String, UiValue> moveStyle(float offsetLeft, float offsetTop, float dx, float dy, float scale) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        out.put("left", px(offsetLeft + dx / scale));
        out.put("top", px(offsetTop + dy / scale));
        return out;
    }

    /** A logical length rounded to a whole pixel, as a style number. */
    public static UiValue px(float logical) {
        return UiValue.of(Math.round(logical));
    }
}
