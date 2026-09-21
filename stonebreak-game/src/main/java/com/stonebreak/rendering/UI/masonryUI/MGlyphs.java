package com.stonebreak.rendering.UI.masonryUI;

/**
 * ASCII stand-ins for the typographic code points the UI typeface has no glyph for.
 *
 * <p>The game UI is drawn in {@code /fonts/Minecraft.ttf}, whose cmap covers only
 * U+0020–U+007E, U+00A0–U+00A9, U+20AC and U+3000. Anything else resolves to glyph 0, and
 * this font's {@code .notdef} is a hollow rectangle — so the character comes out as a tofu
 * box, not as the thing it means. A "−" (U+2212 MINUS SIGN) decrement button reads as an
 * empty box, and every em dash in a class or background description is a box mid-sentence.
 *
 * <p>Rather than police every literal and every piece of authored prose, the MasonryUI text
 * seam ({@link MPainter}) folds these characters down to ASCII on the way to the canvas, so
 * drawing and measuring agree and the caller's string stays whatever it wants to be. This is
 * the text counterpart to {@link MSymbol}, which exists for the same reason on the icon side.
 *
 * <p>Characters with no mapping pass through untouched: a deliberate hole (an emoji in chat,
 * say) is not this class's business.
 */
public final class MGlyphs {

    private MGlyphs() {}

    /**
     * {@code text} with every unsupported typographic character replaced by its ASCII
     * stand-in. Returns the argument itself when there is nothing to do, which is the common
     * case — plain-ASCII strings cost one scan and allocate nothing.
     */
    public static String ascii(String text) {
        if (text == null || text.isEmpty()) return text;

        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80 && asciiFor(text.charAt(i)) != null) {
                first = i;
                break;
            }
        }
        if (first < 0) return text;

        StringBuilder out = new StringBuilder(text.length() + 8);
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement = c >= 0x80 ? asciiFor(c) : null;
            if (replacement != null) {
                out.append(replacement);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** True when {@code c} is one of the characters {@link #ascii} folds down. */
    public static boolean isSubstituted(char c) {
        return c >= 0x80 && asciiFor(c) != null;
    }

    /**
     * The ASCII stand-in for one unsupported character, or null to leave it alone.
     * Kept as a switch rather than a map: this runs on the render thread for every
     * non-ASCII label drawn.
     */
    private static String asciiFor(char c) {
        return switch (c) {
            // Dashes and minus signs — all of them read as a hyphen in a pixel font.
            case '\u2212', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2043' -> "-";
            case '\u2026' -> "...";                                     // horizontal ellipsis
            case '\u00B1' -> "+/-";
            case '\u00D7' -> "x";                                       // multiplication sign
            case '\u00F7' -> "/";                                       // division sign
            case '\u2044', '\u2215' -> "/";                             // fraction / division slash
            case '\u00B0' -> "deg";                                     // degree sign
            case '\u00B5', '\u03BC' -> "u";                             // micro sign, Greek mu
            case '\u2018', '\u2019', '\u201B', '\u2032' -> "'";         // single quotes, prime
            case '\u201C', '\u201D', '\u201E', '\u201F', '\u2033' -> "\""; // double quotes, double prime
            case '\u2022', '\u00B7', '\u2027' -> "*";                   // bullet, middle dot
            case '\u2192' -> "->";
            case '\u2190' -> "<-";
            case '\u2191' -> "^";
            case '\u2193' -> "v";
            case '\u2264' -> "<=";
            case '\u2265' -> ">=";
            case '\u2260' -> "!=";
            case '\u2248', '\u223C' -> "~";
            case '\u2009', '\u200A', '\u202F', '\u2007', '\u2008' -> " "; // thin/narrow spaces
            case '\u200B', '\u200C', '\u200D', '\uFEFF' -> "";          // zero-width joiners/marks
            default -> null;
        };
    }
}
