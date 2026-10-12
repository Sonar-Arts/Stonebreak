package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues;

/**
 * Where a {@code TextField}'s text sits inside its rect (#288), shared by the painter and
 * pointer mapping. Padding comes from {@code padding-*} when styled, else the legacy
 * {@code MTextField} insets (10 px sides, 6 px top for multi-line). A single line is centred
 * vertically; lines of a multi-line field stack from the top.
 *
 * @param left       x where the text starts (before horizontal scrolling)
 * @param top        y of the first line's top
 * @param width      visible text width
 * @param height     visible text height
 * @param lineHeight device pixels per line
 * @param ascent     line top → baseline
 */
public record TextFieldGeometry(float left, float top, float width, float height, float lineHeight, float ascent) {

    static final float PAD_X = 10f;
    static final float PAD_Y = 6f;

    public static TextFieldGeometry of(UiElement el, TextLineMetrics m, float scale, boolean multiline) {
        UiRect r = el.rect();
        ComputedStyle s = el.computedStyle();
        float padL = pad(s, "padding-left", PAD_X * scale, scale);
        float padR = pad(s, "padding-right", PAD_X * scale, scale);
        float padT = pad(s, "padding-top", PAD_Y * scale, scale);
        float padB = pad(s, "padding-bottom", PAD_Y * scale, scale);
        float width = Math.max(0, r.width() - padL - padR);
        float top = multiline ? r.y() + padT : r.y() + (r.height() - m.lineHeight()) / 2f;
        // -sb-baseline (#299): a single line's baseline at an explicit distance from the top
        com.openmason.engine.ui.runtime.style.StyleValues.Length b = s.length("-sb-baseline");
        if (!multiline && b.isFixed()) {
            top = r.y() + b.px(scale, 0) - m.ascent();
        } else if (!multiline && b.kind() == com.openmason.engine.ui.runtime.style.StyleValues.Length.Kind.PERCENT) {
            top = r.y() + r.height() * b.value() / 100f - m.ascent();
        }
        float height = multiline ? Math.max(0, r.height() - padT - padB) : m.lineHeight();
        return new TextFieldGeometry(r.x() + padL, top, width, height, m.lineHeight(), m.ascent());
    }

    /** The line index under device-pixel {@code y} (clamped to [0, lines-1]). */
    public int lineAt(float y, int lines) {
        int i = (int) Math.floor((y - top) / lineHeight);
        return Math.clamp(i, 0, Math.max(0, lines - 1));
    }

    public float baseline(int line) {
        return top + line * lineHeight + ascent;
    }

    private static float pad(ComputedStyle s, String property, float fallback, float scale) {
        if (s.get(property) == null) {
            return fallback;
        }
        return s.length(property).px(scale, fallback);
    }
}
