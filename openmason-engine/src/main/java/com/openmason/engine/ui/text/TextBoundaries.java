package com.openmason.engine.ui.text;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * Caret-safe positions in UTF-16 text (#288). A caret only ever sits on an <b>extended grapheme
 * cluster</b> boundary (UAX #29), so moving, deleting or truncating never splits a surrogate
 * pair, a ZWJ emoji sequence, a flag, a combining sequence, a Hangul syllable or CRLF.
 *
 * <p>Clusters come from {@link BreakIterator#getCharacterInstance(Locale)}, which implements
 * extended grapheme clusters since JDK 20 (verified on JDK 25 by {@code TextBoundariesTest}).
 * Words come from the word instance: a "word" segment is one holding a letter or digit;
 * whitespace and punctuation segments are skipped by word movement.
 *
 * <p>Iterators are per thread and re-targeted per call; every method takes the text it works on.
 */
public final class TextBoundaries {

    private static final ThreadLocal<BreakIterator> CHARS =
        ThreadLocal.withInitial(() -> BreakIterator.getCharacterInstance(Locale.ROOT));
    private static final ThreadLocal<BreakIterator> WORDS =
        ThreadLocal.withInitial(() -> BreakIterator.getWordInstance(Locale.ROOT));

    private TextBoundaries() {
    }

    private static BreakIterator chars(String s) {
        BreakIterator b = CHARS.get();
        b.setText(s);
        return b;
    }

    private static BreakIterator words(String s) {
        BreakIterator b = WORDS.get();
        b.setText(s);
        return b;
    }

    /** The next cluster boundary after {@code index}; {@code s.length()} at the end. */
    public static int next(String s, int index) {
        if (index >= s.length()) {
            return s.length();
        }
        int n = chars(s).following(Math.max(index, 0));
        return n == BreakIterator.DONE ? s.length() : n;
    }

    /** The previous cluster boundary before {@code index}; 0 at the start. */
    public static int previous(String s, int index) {
        if (index <= 0) {
            return 0;
        }
        int p = chars(s).preceding(Math.min(index, s.length()));
        return p == BreakIterator.DONE ? 0 : p;
    }

    public static boolean isBoundary(String s, int index) {
        if (index < 0 || index > s.length()) {
            return false;
        }
        if (index == 0 || index == s.length()) {
            return true;
        }
        return chars(s).isBoundary(index);
    }

    /** Clamps to {@code [0, length]}, then moves back to the nearest boundary at or before it. */
    public static int snap(String s, int index) {
        int i = Math.clamp(index, 0, s.length());
        return isBoundary(s, i) ? i : previous(s, i);
    }

    /** Number of grapheme clusters (user-perceived characters). */
    public static int count(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        BreakIterator b = chars(s);
        int n = 0;
        b.first();
        while (b.next() != BreakIterator.DONE) {
            n++;
        }
        return n;
    }

    /** UTF-16 index after the first {@code clusters} clusters, clamped to {@code [0, length]}. */
    public static int offsetOfCluster(String s, int clusters) {
        if (clusters <= 0) {
            return 0;
        }
        BreakIterator b = chars(s);
        b.first();
        int at = 0;
        for (int i = 0; i < clusters; i++) {
            int n = b.next();
            if (n == BreakIterator.DONE) {
                return s.length();
            }
            at = n;
        }
        return at;
    }

    /** Ctrl+Right: skips non-word segments, then returns the end of the next word. */
    public static int nextWord(String s, int index) {
        int start = Math.clamp(index, 0, s.length());
        BreakIterator b = words(s);
        while (start < s.length()) {
            int end = b.following(start);
            if (end == BreakIterator.DONE) {
                end = s.length();
            }
            if (isWord(s, start, end)) {
                return end;
            }
            start = end;
        }
        return s.length();
    }

    /** Ctrl+Left: skips non-word segments backwards, then returns the start of the previous word. */
    public static int previousWord(String s, int index) {
        int end = Math.clamp(index, 0, s.length());
        BreakIterator b = words(s);
        while (end > 0) {
            int start = b.preceding(end);
            if (start == BreakIterator.DONE) {
                start = 0;
            }
            if (isWord(s, start, end)) {
                return start;
            }
            end = start;
        }
        return 0;
    }

    /**
     * {@code {start, end}} of the word, or run of non-word characters, containing {@code index}
     * (double-click selection). At the end of the text the last segment is returned.
     */
    public static int[] wordAt(String s, int index) {
        if (s.isEmpty()) {
            return new int[] {0, 0};
        }
        int i = Math.clamp(index, 0, s.length());
        BreakIterator b = words(s);
        int start;
        int end;
        if (i == s.length()) {
            start = b.preceding(i);
            end = s.length();
        } else {
            start = b.isBoundary(i) ? i : b.preceding(i);
            end = b.following(i);
        }
        if (start == BreakIterator.DONE) {
            start = 0;
        }
        if (end == BreakIterator.DONE) {
            end = s.length();
        }
        return new int[] {snap(s, start), snap(s, end)};
    }

    /** Index of the first character of the line holding {@code index} ({@code '\n'} separated). */
    public static int lineStart(String s, int index) {
        int i = Math.clamp(index, 0, s.length());
        return s.lastIndexOf('\n', i - 1) + 1;
    }

    /** Index of the line's terminating {@code '\n'} (excluded), or the text's length. */
    public static int lineEnd(String s, int index) {
        int i = Math.clamp(index, 0, s.length());
        int nl = s.indexOf('\n', i);
        return nl < 0 ? s.length() : nl;
    }

    private static boolean isWord(String s, int start, int end) {
        for (int i = start; i < end; ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
