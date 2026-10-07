package com.openmason.engine.ui.runtime;

import com.openmason.engine.ui.text.TextMeasure;

/**
 * How one line of an element's text is measured (#288): caret advances, the line height and the
 * ascent (top of line → baseline), all in device pixels. The painter and pointer input read the
 * same instance, so a click lands on the character that was drawn there.
 */
public record TextLineMetrics(TextMeasure measure, float lineHeight, float ascent) {

    /** Without a font: half-em cells, 1.25 line height (tests, headless hosts). */
    public static TextLineMetrics approximate(float fontPx) {
        return new TextLineMetrics(TextMeasure.monospace(fontPx * 0.5f), fontPx * 1.25f, fontPx);
    }
}
