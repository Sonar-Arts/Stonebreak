package com.openmason.engine.ui.text;

import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * What a text field accepts (#288).
 *
 * <p>Typed and pasted text is <b>filtered</b> per code point ({@link #filter}); disallowed code
 * points are dropped, never replaced. {@code '\n'} is allowed exactly when the field is
 * multiline, whatever {@link #allowed} says. Without an explicit filter every code point except
 * ISO control characters is accepted (so {@code '\t'} is dropped).
 *
 * <p>Line ends: a multiline field normalizes {@code "\r\n"} and {@code "\r"} to {@code "\n"}.
 * A single-line field <b>removes</b> them, so pasting several lines joins them with nothing in
 * between ({@code "a\nb"} → {@code "ab"}); this matches today's chat paste, which drops every
 * character outside its range.
 *
 * <p>{@link #pattern} is commit-time validation (a full match), never a typing filter: an
 * intermediate value such as {@code "-"} on the way to {@code "-5"} must stay typeable.
 *
 * @param maxLength length limit in grapheme clusters; negative means unlimited
 * @param allowed   per-code-point filter, or {@code null} for the default (no ISO controls)
 * @param multiline accepts and keeps newlines
 * @param readOnly  selection and copy only; every edit is a no-op
 * @param pattern   commit validation, or {@code null} for always valid
 */
public record TextInputRules(int maxLength, IntPredicate allowed, boolean multiline, boolean readOnly,
                             Pattern pattern) {

    /** Printable ASCII, 32..126: today's chat input rule. */
    public static final IntPredicate ASCII_PRINTABLE = cp -> cp >= 32 && cp <= 126;

    /** ASCII digits only. */
    public static final IntPredicate DIGITS = cp -> cp >= '0' && cp <= '9';

    public static TextInputRules singleLine() {
        return new TextInputRules(-1, null, false, false, null);
    }

    public static TextInputRules multiLine() {
        return new TextInputRules(-1, null, true, false, null);
    }

    public TextInputRules withMaxLength(int max) {
        return new TextInputRules(max, allowed, multiline, readOnly, pattern);
    }

    public TextInputRules withAllowed(IntPredicate filter) {
        return new TextInputRules(maxLength, filter, multiline, readOnly, pattern);
    }

    public TextInputRules withPattern(Pattern p) {
        return new TextInputRules(maxLength, allowed, multiline, readOnly, p);
    }

    public TextInputRules withReadOnly(boolean ro) {
        return new TextInputRules(maxLength, allowed, multiline, ro, pattern);
    }

    public TextInputRules withMultiline(boolean ml) {
        return new TextInputRules(maxLength, allowed, ml, readOnly, pattern);
    }

    /** True when {@code codePoint} may be typed or pasted. */
    public boolean accepts(int codePoint) {
        if (codePoint == '\n') {
            return multiline;
        }
        return allowed != null ? allowed.test(codePoint) : !Character.isISOControl(codePoint);
    }

    /** Normalizes line ends (see class doc) without filtering anything else. */
    public String normalizeLineEnds(String text) {
        if (text.indexOf('\r') < 0 && (multiline || text.indexOf('\n') < 0)) {
            return text;
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return multiline ? unified : unified.replace("\n", "");
    }

    /** Line ends normalized, then every disallowed code point dropped. */
    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = normalizeLineEnds(text);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (accepts(cp)) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** Commit validation: no pattern, or a full match. */
    public boolean valid(String text) {
        return pattern == null || pattern.matcher(text).matches();
    }
}
