package com.openmason.engine.ui.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBoundariesTest {

    static final String FAMILY = "👨‍👩‍👧"; // 👨‍👩‍👧
    static final String FLAGS = "🇺🇸🇫🇷"; // 🇺🇸🇫🇷
    static final String E_ACUTE = "é";
    static final String HANGUL = "각"; // L+V+T
    static final String THUMB = "👍🏽"; // 👍🏽

    @Test
    void theJdkBreakIteratorKeepsExtendedGraphemeClustersTogether() {
        assertEquals(1, TextBoundaries.count(FAMILY), "ZWJ sequence");
        assertEquals(2, TextBoundaries.count(FLAGS), "regional indicators pair up");
        assertEquals(1, TextBoundaries.count(E_ACUTE), "combining mark");
        assertEquals(1, TextBoundaries.count("\r\n"), "CRLF");
        assertEquals(1, TextBoundaries.count(HANGUL), "Hangul L+V+T");
        assertEquals(1, TextBoundaries.count(THUMB), "skin-tone modifier");
        assertEquals(0, TextBoundaries.count(""));
    }

    @Test
    void nextAndPreviousStepWholeClusters() {
        String s = "a" + FAMILY + "b" + FLAGS;
        int afterA = 1;
        int afterFamily = afterA + FAMILY.length();
        assertEquals(afterA, TextBoundaries.next(s, 0));
        assertEquals(afterFamily, TextBoundaries.next(s, afterA));
        assertEquals(afterFamily + 1 + 4, TextBoundaries.next(s, afterFamily + 1), "one flag = 4 chars");
        assertEquals(s.length(), TextBoundaries.next(s, s.length()));
        assertEquals(afterA, TextBoundaries.previous(s, afterFamily));
        assertEquals(0, TextBoundaries.previous(s, 0));
        assertEquals(afterFamily + 5, TextBoundaries.previous(s, s.length()));
    }

    @Test
    void snapNeverSplitsASurrogatePairOrCluster() {
        String s = "x" + THUMB + E_ACUTE;
        for (int i = -2; i <= s.length() + 2; i++) {
            int snapped = TextBoundaries.snap(s, i);
            assertTrue(TextBoundaries.isBoundary(s, snapped), "index " + i);
            assertTrue(snapped <= Math.max(0, Math.min(i, s.length())));
            assertFalse(snapped > 0 && snapped < s.length() && Character.isLowSurrogate(s.charAt(snapped)));
        }
        assertEquals(1, TextBoundaries.snap(s, 2), "inside the thumb → its start");
        assertFalse(TextBoundaries.isBoundary(s, 1 + THUMB.length() + 1), "between e and its accent");
    }

    @Test
    void offsetOfClusterCountsClustersNotChars() {
        String s = "ab" + FAMILY + "c";
        assertEquals(0, TextBoundaries.offsetOfCluster(s, 0));
        assertEquals(2, TextBoundaries.offsetOfCluster(s, 2));
        assertEquals(2 + FAMILY.length(), TextBoundaries.offsetOfCluster(s, 3));
        assertEquals(s.length(), TextBoundaries.offsetOfCluster(s, 99));
    }

    @Test
    void wordMovementSkipsSpacesAndPunctuation() {
        String s = "hello, world  foo";
        assertEquals(5, TextBoundaries.nextWord(s, 0));
        assertEquals(12, TextBoundaries.nextWord(s, 5), "skips \", \" then the word");
        assertEquals(17, TextBoundaries.nextWord(s, 12));
        assertEquals(17, TextBoundaries.nextWord(s, 17));
        assertEquals(14, TextBoundaries.previousWord(s, 17));
        assertEquals(7, TextBoundaries.previousWord(s, 14));
        assertEquals(7, TextBoundaries.previousWord(s, 9), "from inside a word: its start");
        assertEquals(0, TextBoundaries.previousWord(s, 7));
        assertEquals(0, TextBoundaries.previousWord(s, 0));
    }

    @Test
    void wordAtSelectsTheSegmentUnderTheIndex() {
        String s = "hello, world";
        assertArrayEquals(new int[] {0, 5}, TextBoundaries.wordAt(s, 2));
        assertArrayEquals(new int[] {7, 12}, TextBoundaries.wordAt(s, 7));
        assertArrayEquals(new int[] {7, 12}, TextBoundaries.wordAt(s, 12), "end of text → last segment");
        assertArrayEquals(new int[] {5, 6}, TextBoundaries.wordAt(s, 5), "punctuation run");
        assertArrayEquals(new int[] {0, 0}, TextBoundaries.wordAt("", 0));
    }

    @Test
    void lineBoundaries() {
        String s = "ab\ncde\n\nf";
        assertEquals(0, TextBoundaries.lineStart(s, 1));
        assertEquals(2, TextBoundaries.lineEnd(s, 1));
        assertEquals(3, TextBoundaries.lineStart(s, 3));
        assertEquals(6, TextBoundaries.lineEnd(s, 4));
        assertEquals(7, TextBoundaries.lineStart(s, 7));
        assertEquals(7, TextBoundaries.lineEnd(s, 7), "empty line");
        assertEquals(8, TextBoundaries.lineStart(s, 9));
        assertEquals(9, TextBoundaries.lineEnd(s, 9));
    }

    @Test
    void monospaceMeasureCountsClusters() {
        TextMeasure m = TextMeasure.monospace(10f);
        String line = "a" + FAMILY + "b";
        assertEquals(0f, m.advance(line, 0));
        assertEquals(10f, m.advance(line, 1));
        assertEquals(20f, m.advance(line, 1 + FAMILY.length()));
        assertEquals(1, m.indexAt(line, 12f));
        assertEquals(1 + FAMILY.length(), m.indexAt(line, 16f), "past the middle of the family → after it");
        assertEquals(line.length(), m.indexAt(line, 500f));
        assertEquals(0, m.indexAt(line, -5f));
    }
}
