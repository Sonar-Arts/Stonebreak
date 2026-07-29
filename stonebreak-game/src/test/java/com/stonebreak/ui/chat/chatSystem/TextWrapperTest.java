package com.stonebreak.ui.chat.chatSystem;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Guards the text-wrapping invariant: every returned line must be at or below {@code maxLength},
 * and every non-whitespace character of the input must appear in a returned line in the same
 * relative order (no text is silently dropped).
 *
 * <p>Regression: a wrapping algorithm that drops a tail fragment when the last word lands
 * exactly on the boundary, or silently truncates a single word longer than the limit.
 */
class TextWrapperTest {

    @Test
    void defaultConstructorUsesMaxLength60() {
        TextWrapper wrapper = new TextWrapper();
        assertEquals(60, wrapper.getMaxLength(), "default max length must be 60");
    }

    @Test
    void customConstructorSetsMaxLength() {
        TextWrapper wrapper = new TextWrapper(25);
        assertEquals(25, wrapper.getMaxLength());
    }

    @Test
    void nullInputReturnsEmptyArray() {
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText(null);
        assertEquals(0, lines.length, "null input should produce zero lines");
    }

    @Test
    void emptyInputReturnsEmptyArray() {
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText("");
        assertEquals(0, lines.length, "empty input should produce zero lines");
    }

    @Test
    void blankInputShorterThanLimitReturnsSingleLine() {
        // "   ".length() == 3, which is <= maxLength 40, so it bypasses wrapping
        // and returns the entire string as one line.
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText("   ");
        assertEquals(1, lines.length,
            "whitespace-only text shorter than limit is returned as a single line");
        assertEquals("   ", lines[0]);
    }

    @Test
    void blankInputExceedingLimitReturnsEmptyArray() {
        // "   ".length() == 3 > maxLength 2, so it enters the wrapping path.
        // "   ".split(" ") produces an empty array (all tokens are empty),
        // so no words are processed and the result is empty.
        TextWrapper wrapper = new TextWrapper(2);
        String[] lines = wrapper.wrapText("   ");
        assertEquals(0, lines.length,
            "whitespace-only text exceeding the limit produces no lines — split yields no words");
    }

    @Test
    void textShorterThanLimitReturnsSingleLine() {
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText("hello world");
        assertEquals(1, lines.length, "short text must stay on one line");
        assertEquals("hello world", lines[0]);
    }

    @Test
    void textEqualToLimitReturnsSingleLine() {
        String exact = "1234567890123456789012345678901234567890"; // 40 chars
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText(exact);
        assertEquals(1, lines.length, "text exactly at the limit must stay on one line");
        assertEquals(exact, lines[0]);
    }

    @Test
    void everyReturnedLineRespectsMaxLength() {
        TextWrapper wrapper = new TextWrapper(10);
        String[] lines = wrapper.wrapText("a b c d e f g h i j k l m n o p q r s t");
        for (int i = 0; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 10,
                "line " + i + " ('" + lines[i] + "') must not exceed maxLength 10");
        }
    }

    @Test
    void longSingleWordIsSplitAcrossLines() {
        TextWrapper wrapper = new TextWrapper(5);
        String longWord = "abcdefghij"; // 10 chars, longer than limit 5
        String[] lines = wrapper.wrapText(longWord);

        assertEquals(2, lines.length, "a word twice the limit should split into two lines");
        assertEquals("abcde", lines[0]);
        assertEquals("fghij", lines[1]);
        assertTrue(lines[0].length() <= 5);
        assertTrue(lines[1].length() <= 5);
    }

    @Test
    void singleWordLongerThanLimitIsNotSilentlyDropped() {
        TextWrapper wrapper = new TextWrapper(5);
        String word = "toolong"; // 7 chars
        String[] lines = wrapper.wrapText(word);

        assertEquals(2, lines.length,
            "a word longer than the limit must be emitted (split), not dropped");
        String combined = String.join("", lines);
        assertEquals(word, combined, "splitting must not lose characters");
    }

    @Test
    void concatenatingLinesPreservesAllNonWhitespaceCharacters() {
        TextWrapper wrapper = new TextWrapper(10);
        String input = "one two three four five six seven eight nine ten";
        String[] lines = wrapper.wrapText(input);

        // Strip all whitespace and rejoin — every non-space char must survive in order.
        String inputNoSpaces = input.replace(" ", "");
        String linesNoSpaces = String.join("", lines).replace(" ", "");
        assertEquals(inputNoSpaces, linesNoSpaces,
            "concatenating the returned lines and stripping spaces must reproduce every "
                + "non-whitespace character of the original input in order");
    }

    @Test
    void multiWordTextThatFitsOnOneLineDoesNotWrap() {
        TextWrapper wrapper = new TextWrapper(40);
        String[] lines = wrapper.wrapText("a b c d");
        assertEquals(1, lines.length);
        assertEquals("a b c d", lines[0]);
    }

    @Test
    void multiWordTextWrapsToMultipleLines() {
        TextWrapper wrapper = new TextWrapper(10);
        String[] lines = wrapper.wrapText("a b c d e f g h i j");
        assertTrue(lines.length > 1, "many words must wrap to at least two lines");
        for (int i = 0; i < lines.length; i++) {
            assertTrue(lines[i].length() <= 10,
                "line " + i + " must not exceed maxLength");
        }
    }

    @Test
    void doubleSpaceBetweenWordsIsHandledWithoutThrowing() {
        // "a  b".split(" ") produces ["a", "", "b"] — the empty string between the
        // double spaces is a zero-length word. The algorithm must handle it gracefully.
        TextWrapper wrapper = new TextWrapper(5);
        String[] lines = wrapper.wrapText("a  b");
        // The exact output depends on how the empty token is handled, but it must not throw
        // and must preserve the non-whitespace characters.
        String linesNoSpaces = String.join("", lines).replace(" ", "");
        assertEquals("ab", linesNoSpaces,
            "double-space input must not lose non-whitespace characters");
    }
}