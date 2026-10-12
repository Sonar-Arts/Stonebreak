package com.openmason.engine.ui.l10n;

/**
 * Pseudo-localization for testing screens before translations exist: every ASCII letter gets
 * a fixed accented look-alike (catches hard-coded strings and missing glyphs), the text is
 * bracketed (catches truncation at either end) and padded with {@code ~} so it is about 40%
 * longer (catches layouts that only fit English). Digits, whitespace and every non-ASCII-letter
 * character are kept as they are. Deterministic.
 */
public final class PseudoLocalizer {

    private static final String LOWER = "àƀçðéƒĝĥîĵķĺɱñöþǫŕšţûṽŵẋýž";
    private static final String UPPER = "ÀƁÇÐÉƑĜĤÎĴĶĹṀÑÖÞǪŔŠŢÛṼŴẊÝŽ";
    private static final double EXPANSION = 0.4;

    private PseudoLocalizer() {
    }

    public static String transform(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length() * 2 + 2).append('[');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append(LOWER.charAt(c - 'a'));
            } else if (c >= 'A' && c <= 'Z') {
                out.append(UPPER.charAt(c - 'A'));
            } else {
                out.append(c);
            }
        }
        int length = text.codePointCount(0, text.length());
        int pad = Math.max(0, (int) Math.ceil(length * EXPANSION) - 2); // the brackets already add 2
        out.repeat('~', pad);
        return out.append(']').toString();
    }
}
