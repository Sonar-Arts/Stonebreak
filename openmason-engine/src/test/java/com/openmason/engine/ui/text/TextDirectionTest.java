package com.openmason.engine.ui.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDirectionTest {

    @Test
    void baseDirectionComesFromTheFirstStrongCharacter() {
        assertTrue(TextDirection.isRightToLeft("שלום"), "Hebrew");
        assertTrue(TextDirection.isRightToLeft("مرحبا world"), "Arabic first");
        assertTrue(TextDirection.isRightToLeft("123 שלום"), "digits are not strong");
        assertFalse(TextDirection.isRightToLeft("hello שלום"));
        assertFalse(TextDirection.isRightToLeft(""));
        assertFalse(TextDirection.isRightToLeft("123 !?"), "all neutral → left-to-right");
        assertFalse(TextDirection.isRightToLeft(null));
    }

    @Test
    void theLeftArrowMovesForwardInAnRtlParagraph() {
        assertTrue(TextDirection.leftArrowMovesForward("שלום"));
        assertFalse(TextDirection.leftArrowMovesForward("hello"));
    }
}
