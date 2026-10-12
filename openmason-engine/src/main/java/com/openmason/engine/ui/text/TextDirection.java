package com.openmason.engine.ui.text;

import java.text.Bidi;

/**
 * Paragraph direction for caret movement (#288).
 *
 * <p>Caret indices are always <b>logical</b> (positions in the string). Arrow keys follow the
 * paragraph's base direction: in a right-to-left paragraph the Left arrow moves forward. Fully
 * visual caret movement inside mixed-direction runs needs shaped glyph runs, which the Masonry
 * text path does not expose yet; that limit is recorded in the input spec (ui-input.md).
 */
public final class TextDirection {

    private TextDirection() {
    }

    /** Base direction from the first strong character; empty or all-neutral text is left-to-right. */
    public static boolean isRightToLeft(String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) {
            return false;
        }
        return !new Bidi(paragraph, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
    }

    /** True when the Left arrow should move the logical caret forward (an RTL base). */
    public static boolean leftArrowMovesForward(String paragraph) {
        return isRightToLeft(paragraph);
    }
}
