package com.openmason.engine.ui.text;

/**
 * Horizontal caret geometry of one line of text, supplied by whoever shapes and paints it
 * (the Masonry font in game and tool). Used for click-to-caret and for keeping a goal x when
 * the caret moves between lines.
 */
public interface TextMeasure {

    /** X of the caret placed before UTF-16 {@code index} within {@code line}. */
    float advance(String line, int index);

    /** The grapheme boundary of {@code line} nearest to {@code x}. */
    int indexAt(String line, float x);

    /** Every grapheme cluster is {@code cellWidth} wide (tests and the no-font fallback). */
    static TextMeasure monospace(float cellWidth) {
        return new TextMeasure() {
            @Override
            public float advance(String line, int index) {
                return TextBoundaries.count(line.substring(0, TextBoundaries.snap(line, index))) * cellWidth;
            }

            @Override
            public int indexAt(String line, float x) {
                int at = 0;
                float left = 0;
                while (at < line.length()) {
                    int next = TextBoundaries.next(line, at);
                    float right = left + cellWidth;
                    if (x < (left + right) / 2f) {
                        return at;
                    }
                    at = next;
                    left = right;
                }
                return line.length();
            }
        };
    }
}
