package com.openmason.engine.ui.graph;

import com.openmason.engine.format.omui.UiValue;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lua literals, quoting and identifiers for generated code. */
public final class LuaText {

    static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "global", "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
        "until", "while");

    private LuaText() {
    }

    /** A double-quoted Lua string; control and non-ASCII characters are escaped, so the line stays one line. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte raw : bytes) {
            int c = raw & 0xFF;
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c >= 0x7F) {
                        sb.append(String.format(Locale.ROOT, "\\%03d", c));
                    } else {
                        sb.append((char) c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A Lua literal for a format value ({@code null} is {@code nil}). */
    public static String literal(UiValue v) {
        return switch (v) {
            case null -> "nil";
            case UiValue.Null n -> "nil";
            case UiValue.Bool b -> b.value() ? "true" : "false";
            case UiValue.Num n -> number(n.value());
            case UiValue.Str s -> quote(s.value());
            case UiValue.Arr a -> {
                StringBuilder sb = new StringBuilder("{");
                for (int i = 0; i < a.items().size(); i++) {
                    sb.append(i == 0 ? " " : ", ").append(literal(a.items().get(i)));
                }
                yield sb.append(a.items().isEmpty() ? "}" : " }").toString();
            }
            case UiValue.Obj o -> {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, UiValue> e : o.fields().entrySet()) {
                    sb.append(first ? " " : ", ").append(field(e.getKey())).append(" = ").append(literal(e.getValue()));
                    first = false;
                }
                yield sb.append(o.fields().isEmpty() ? "}" : " }").toString();
            }
        };
    }

    /** Integral values within ±2^53 print as Lua integers ({@code 3}), others in round-trip form. */
    /**
     * A number literal that is one primary expression wherever it lands: negatives are
     * parenthesized ({@code -2 ^ x} is {@code -(2 ^ x)} in Lua) and non-finite values are
     * arithmetic, never identifiers ({@code Infinity} would be a global lookup).
     */
    static String number(double d) {
        if (Double.isNaN(d)) {
            return "(0/0)";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "(1/0)" : "(-1/0)";
        }
        String s;
        if (d == Math.rint(d) && Math.abs(d) <= 9.007199254740992E15) {
            s = Long.toString((long) d);
        } else {
            s = Double.toString(d);
            s = s.contains("E") ? String.format(Locale.ROOT, "%.17g", d) : s;
        }
        return s.startsWith("-") ? "(" + s + ")" : s;
    }

    /** A table key: {@code name} when it is a plain identifier, else {@code ["na-me"]}. */
    public static String field(String key) {
        return isName(key) ? key : "[" + quote(key) + "]";
    }

    /** {@code t.name} or {@code t["na-me"]}. */
    static String index(String table, String key) {
        return isName(key) ? table + "." + key : table + "[" + quote(key) + "]";
    }

    public static boolean isName(String s) {
        return s.matches("[A-Za-z_][A-Za-z0-9_]*") && !KEYWORDS.contains(s);
    }

    /** A readable identifier fragment from a node id or port name ({@code on-resume} → {@code on_resume}). */
    static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 40; i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 0x80 ? c : '_');
        }
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
