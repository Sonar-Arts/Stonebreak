package com.openmason.main.systems.uiPreview.graph;

import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.ui.graph.PortType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Literal text conversions of the graph editor (pure): the compact form drawn on a node,
 * the text an inspector field shows, and parsing what the author typed back into a value that
 * fits the port. Invalid text parses to {@code null}, which fields treat as "do not commit".
 */
public final class GraphValues {

    private static final int COMPACT_LIMIT = 16;

    private GraphValues() {
    }

    /** A short single-line rendering for a node's unconnected input. */
    public static String compact(UiValue v) {
        String s = switch (v) {
            case UiValue.Null n -> "nil";
            case UiValue.Bool b -> Boolean.toString(b.value());
            case UiValue.Num n -> number(n);
            case UiValue.Str str -> '"' + str.value().replace('\n', ' ') + '"';
            case UiValue.Arr a -> "[" + a.items().size() + "]";
            case UiValue.Obj o -> "{" + o.fields().size() + "}";
        };
        return s.length() > COMPACT_LIMIT ? s.substring(0, COMPACT_LIMIT - 1) + "~" : s;
    }

    /** The text an inspector field starts with: strings raw, everything else as JSON. */
    public static String editText(UiValue v) {
        return switch (v) {
            case null -> "";
            case UiValue.Null n -> "";
            case UiValue.Bool b -> Boolean.toString(b.value());
            case UiValue.Num n -> number(n);
            case UiValue.Str s -> s.value();
            default -> new String(CanonicalJson.write(v), StandardCharsets.UTF_8);
        };
    }

    /**
     * Parses typed text as a literal of {@code type}; {@code null} when it does not fit. An empty
     * string for a string-like port is the empty string, anything else empty is invalid.
     */
    public static UiValue parse(PortType type, String text) {
        String t = text == null ? "" : text;
        try {
            switch (type) {
                case BOOL:
                    return t.strip().equalsIgnoreCase("true") ? UiValue.TRUE
                        : t.strip().equalsIgnoreCase("false") ? UiValue.FALSE : null;
                case INT: {
                    UiValue v = UiValue.of(Long.parseLong(t.strip()));
                    return v;
                }
                case NUMBER:
                    return UiValue.of(Double.parseDouble(t.strip()));
                case STRING:
                case ASSET:
                    return UiValue.of(t);
                case COLOR: {
                    UiValue v = UiValue.of(t.strip());
                    return type.acceptsLiteral(v) ? v : null;
                }
                case LIST:
                case OBJECT: {
                    UiValue v = json(t);
                    return v != null && type.acceptsLiteral(v) ? v : null;
                }
                case ANY:
                    return any(t);
                default:
                    return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UiValue any(String t) {
        String s = t.strip();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return UiValue.of(Boolean.parseBoolean(s.toLowerCase(Locale.ROOT)));
        }
        try {
            return UiValue.of(Double.parseDouble(s));
        } catch (NumberFormatException ignored) {
            // not a number: structured JSON or plain text
        }
        if (s.startsWith("[") || s.startsWith("{")) {
            UiValue v = json(s);
            if (v != null) {
                return v;
            }
        }
        return UiValue.of(t);
    }

    private static UiValue json(String text) {
        UiDiagnostics d = new UiDiagnostics();
        UiValue v = CanonicalJson.parse(text.getBytes(StandardCharsets.UTF_8), "literal", d);
        return d.hasErrors() ? null : v;
    }

    private static String number(UiValue.Num n) {
        return n.isIntegral() ? Long.toString((long) n.value()) : Double.toString(n.value());
    }
}
