package com.openmason.engine.ui.l10n;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A compiled ICU MessageFormat pattern (the subset UI text needs). Immutable and thread-safe.
 *
 * <p>Syntax:
 * <ul>
 *   <li>{@code {name}}: the argument; numbers are formatted for the locale (an integral
 *       double shows no fraction), anything else is {@code String.valueOf};</li>
 *   <li>{@code {name, number}}, {@code {name, number, integer}}, {@code {name, number, percent}};</li>
 *   <li>{@code {name, plural, [offset:N] =0 {…} one {…} other {…}}}: explicit {@code =N}
 *       matches compare the raw value and win; keywords use the CLDR category of
 *       {@code value − offset}; inside, {@code #} is that offset value formatted for the
 *       locale. {@code other} is required;</li>
 *   <li>{@code {name, select, a {…} other {…}}}: string match, {@code other} required.</li>
 * </ul>
 * Sub-messages nest. Apostrophes quote the ICU way: {@code ''} is one apostrophe, and an
 * apostrophe before {@code {}, {@code }} or {@code #} starts quoted literal text up to the next
 * single apostrophe; any other apostrophe is literal. A missing argument renders as
 * {@code {name}} rather than failing, so a stale catalog never crashes a screen.
 */
public final class MessagePattern {

    private sealed interface Part permits Text, Pound, Arg, Plural, Select {
    }

    private record Text(String text) implements Part {
    }

    private record Pound() implements Part {
    }

    private enum NumberStyle { NONE, PLAIN, INTEGER, PERCENT }

    private record Arg(String name, NumberStyle style) implements Part {
    }

    private record Plural(String name, BigDecimal offset, Map<BigDecimal, List<Part>> exact,
                          Map<PluralCategory, List<Part>> keywords) implements Part {
    }

    private record Select(String name, Map<String, List<Part>> cases) implements Part {
    }

    private final String source;
    private final List<Part> parts;
    private final Set<String> argumentNames;

    private MessagePattern(String source, List<Part> parts, Set<String> argumentNames) {
        this.source = source;
        this.parts = parts;
        this.argumentNames = Collections.unmodifiableSet(argumentNames);
    }

    /** @throws MessageFormatException when the pattern does not parse */
    public static MessagePattern compile(String pattern) {
        if (pattern == null) {
            throw new MessageFormatException("pattern is null", 0);
        }
        Parser p = new Parser(pattern);
        List<Part> parts = p.message(false);
        if (p.pos < pattern.length()) {
            throw new MessageFormatException("unmatched '}'", p.pos);
        }
        return new MessagePattern(pattern, parts, p.names);
    }

    /** The pattern this was compiled from. */
    public String source() {
        return source;
    }

    /** Every argument the pattern refers to, in first-use order. */
    public Set<String> argumentNames() {
        return argumentNames;
    }

    public String format(Map<String, Object> args, Locale locale) {
        StringBuilder out = new StringBuilder();
        Locale loc = locale == null ? Locale.ROOT : locale;
        render(parts, args == null ? Map.of() : args, loc, null, out);
        return out.toString();
    }

    // ── formatting ──────────────────────────────────────────────────────────

    private static void render(List<Part> parts, Map<String, Object> args, Locale locale, Object pound,
                               StringBuilder out) {
        for (Part part : parts) {
            switch (part) {
                case Text t -> out.append(t.text());
                case Pound ignored -> out.append(pound instanceof BigDecimal d ? number(d, locale) : String.valueOf(pound));
                case Arg a -> {
                    if (!args.containsKey(a.name()) || args.get(a.name()) == null) {
                        out.append('{').append(a.name()).append('}');
                    } else {
                        out.append(argument(args.get(a.name()), a.style(), locale));
                    }
                }
                case Plural p -> plural(p, args, locale, out);
                case Select s -> {
                    Object v = args.get(s.name());
                    List<Part> chosen = v == null ? null : s.cases().get(String.valueOf(v));
                    render(chosen == null ? s.cases().get("other") : chosen, args, locale, pound, out);
                }
            }
        }
    }

    private static void plural(Plural p, Map<String, Object> args, Locale locale, StringBuilder out) {
        Object raw = args.get(p.name());
        BigDecimal value = decimal(raw);
        if (value == null) {
            render(p.keywords().get(PluralCategory.OTHER), args, locale, raw == null ? "{" + p.name() + "}" : raw, out);
            return;
        }
        for (Map.Entry<BigDecimal, List<Part>> e : p.exact().entrySet()) {
            if (e.getKey().compareTo(value) == 0) {
                render(e.getValue(), args, locale, value.subtract(p.offset()), out);
                return;
            }
        }
        BigDecimal shifted = value.subtract(p.offset());
        PluralCategory c = PluralRules.forLocale(locale).select(shifted);
        List<Part> chosen = p.keywords().get(c);
        render(chosen == null ? p.keywords().get(PluralCategory.OTHER) : chosen, args, locale, shifted, out);
    }

    private static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? BigDecimal.valueOf((long) d) : BigDecimal.valueOf(d);
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (v instanceof CharSequence s) {
            try {
                return new BigDecimal(s.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String argument(Object v, NumberStyle style, Locale locale) {
        if (style == NumberStyle.NONE && !(v instanceof Number)) {
            return String.valueOf(v);
        }
        BigDecimal d = decimal(v);
        if (d == null) {
            return String.valueOf(v);
        }
        return switch (style) {
            case INTEGER -> NumberFormat.getIntegerInstance(locale).format(d);
            case PERCENT -> NumberFormat.getPercentInstance(locale).format(d);
            default -> number(d, locale);
        };
    }

    private static String number(BigDecimal d, Locale locale) {
        return NumberFormat.getInstance(locale).format(d);
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    private static final class Parser {
        private final String s;
        private final Set<String> names = new LinkedHashSet<>();
        private int pos;
        /** Plural sub-messages being parsed: {@code #} is the plural number while this is > 0. */
        private int pluralDepth;

        Parser(String s) {
            this.s = s;
        }

        /** Parses until end, or until the closing '}' of a sub-message (left unconsumed). */
        List<Part> message(boolean nested) {
            List<Part> parts = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\'') {
                    quote(text);
                } else if (c == '{') {
                    flush(text, parts);
                    parts.add(argument());
                } else if (c == '}') {
                    if (!nested) {
                        throw new MessageFormatException("unmatched '}'", pos);
                    }
                    break;
                } else if (c == '#' && pluralDepth > 0) {
                    flush(text, parts);
                    parts.add(new Pound());
                    pos++;
                } else {
                    text.append(c);
                    pos++;
                }
            }
            if (nested && pos >= s.length()) {
                throw new MessageFormatException("unterminated sub-message", pos);
            }
            flush(text, parts);
            return List.copyOf(parts);
        }

        private void quote(StringBuilder text) {
            if (pos + 1 < s.length() && s.charAt(pos + 1) == '\'') {
                text.append('\'');
                pos += 2;
                return;
            }
            if (pos + 1 < s.length() && "{}#".indexOf(s.charAt(pos + 1)) >= 0) {
                int start = pos;
                pos++;
                while (true) {
                    if (pos >= s.length()) {
                        throw new MessageFormatException("unterminated quote", start);
                    }
                    char c = s.charAt(pos);
                    if (c == '\'') {
                        if (pos + 1 < s.length() && s.charAt(pos + 1) == '\'') {
                            text.append('\'');
                            pos += 2;
                            continue;
                        }
                        pos++;
                        return;
                    }
                    text.append(c);
                    pos++;
                }
            }
            text.append('\'');
            pos++;
        }

        private static void flush(StringBuilder text, List<Part> parts) {
            if (!text.isEmpty()) {
                parts.add(new Text(text.toString()));
                text.setLength(0);
            }
        }

        private Part argument() {
            int open = pos;
            pos++; // '{'
            skipSpace();
            String name = identifier("argument name");
            names.add(name);
            skipSpace();
            if (peek() == '}') {
                pos++;
                return new Arg(name, NumberStyle.NONE);
            }
            expect(',');
            skipSpace();
            int typeAt = pos;
            String type = identifier("argument type");
            skipSpace();
            Part part = switch (type) {
                case "number" -> new Arg(name, numberStyle());
                case "plural" -> plural(name);
                case "select" -> select(name);
                default -> throw new MessageFormatException("unsupported argument type '" + type + "'", typeAt);
            };
            skipSpace();
            if (peek() != '}') {
                throw new MessageFormatException("expected '}' closing argument opened", open);
            }
            pos++;
            return part;
        }

        private NumberStyle numberStyle() {
            if (peek() == '}') {
                return NumberStyle.PLAIN;
            }
            expect(',');
            skipSpace();
            int at = pos;
            String style = identifier("number style");
            return switch (style) {
                case "integer" -> NumberStyle.INTEGER;
                case "percent" -> NumberStyle.PERCENT;
                default -> throw new MessageFormatException("unsupported number style '" + style + "'", at);
            };
        }

        private Part plural(String name) {
            expect(',');
            skipSpace();
            BigDecimal offset = BigDecimal.ZERO;
            if (s.startsWith("offset:", pos)) {
                pos += "offset:".length();
                skipSpace();
                offset = number();
                skipSpace();
            }
            Map<BigDecimal, List<Part>> exact = new LinkedHashMap<>();
            Map<PluralCategory, List<Part>> keywords = new LinkedHashMap<>();
            int start = pos;
            while (peek() != '}' && pos < s.length()) {
                int at = pos;
                if (peek() == '=') {
                    pos++;
                    BigDecimal v = number();
                    for (BigDecimal k : exact.keySet()) {
                        if (k.compareTo(v) == 0) {
                            throw new MessageFormatException("duplicate selector =" + v.toPlainString(), at);
                        }
                    }
                    exact.put(v, subMessage(true));
                } else {
                    String kw = identifier("plural selector");
                    PluralCategory c = PluralCategory.fromWire(kw);
                    if (c == null) {
                        throw new MessageFormatException("unknown plural selector '" + kw + "'", at);
                    }
                    if (keywords.containsKey(c)) {
                        throw new MessageFormatException("duplicate selector " + kw, at);
                    }
                    keywords.put(c, subMessage(true));
                }
                skipSpace();
            }
            if (!keywords.containsKey(PluralCategory.OTHER)) {
                throw new MessageFormatException("plural needs an 'other' case", start);
            }
            return new Plural(name, offset, exact, keywords);
        }

        private Part select(String name) {
            expect(',');
            skipSpace();
            Map<String, List<Part>> cases = new LinkedHashMap<>();
            int start = pos;
            while (peek() != '}' && pos < s.length()) {
                int at = pos;
                String kw = identifier("select case");
                if (cases.containsKey(kw)) {
                    throw new MessageFormatException("duplicate selector " + kw, at);
                }
                cases.put(kw, subMessage(false));
                skipSpace();
            }
            if (!cases.containsKey("other")) {
                throw new MessageFormatException("select needs an 'other' case", start);
            }
            return new Select(name, cases);
        }

        private List<Part> subMessage(boolean plural) {
            skipSpace();
            expect('{');
            if (plural) {
                pluralDepth++;
            }
            List<Part> body = message(true);
            if (plural) {
                pluralDepth--;
            }
            pos++; // '}'
            return body;
        }

        private BigDecimal number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new MessageFormatException("expected a number", start);
            }
        }

        private String identifier(String what) {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new MessageFormatException("expected " + what, start);
            }
            return s.substring(start, pos);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new MessageFormatException("expected '" + c + "'", pos);
            }
            pos++;
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }

    @Override
    public String toString() {
        return "MessagePattern[" + source + "]";
    }
}
