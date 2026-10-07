package com.openmason.engine.format.omui.io;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Strict JSON decoding into {@link UiValue} and the canonical encoding every UI entry uses.
 *
 * <p><b>Decoding</b> accepts strict UTF-8 without a byte-order mark, rejects duplicate keys,
 * trailing content, nesting deeper than {@link #MAX_DEPTH}, integer literals beyond
 * &plusmn;2<sup>53</sup> (they would not survive binary64) and numbers that overflow binary64.
 * Objects come back key-sorted.
 *
 * <p><b>Canonical encoding</b> (the wire contract, reproduced byte for byte by any
 * conforming writer): UTF-8, no BOM; two-space indentation; {@code "key": value}; LF line
 * ends and a final LF; empty containers as {@code {}} and {@code []}; object members in the
 * order the {@link UiValue.Obj} iterates; strings escape {@code "} {@code \} and U+0000–U+001F
 * (as backslash-b, -f, -n, -r, -t, otherwise backslash-u00xx in lowercase hex) and nothing else
 * (unpaired surrogates are not representable and are rejected on decode); numbers in
 * ECMAScript {@code Number.prototype.toString} form (shortest round-trip digits, no
 * exponent in [1e-6, 1e21), integral values without a fraction).
 */
public final class CanonicalJson {

    /**
     * JSON nesting bound. Sized so the deepest tree {@link
     * com.openmason.engine.format.omui.ArchiveLimits#DEFAULT} allows fits with room for its
     * values: every level of slot content costs four JSON levels (instance, slots, slot list,
     * node), so a 48-deep tree of slot content puts its last node at depth 190; the remaining
     * levels are free-form values (props, overrides). Writers apply the same bound (§3.5).
     */
    public static final int MAX_DEPTH = 256;
    public static final int MAX_STRING_CHARS = 1 << 20;
    private static final long MAX_SAFE_INTEGER = 1L << 53;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DEPTH)
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNumberLength(64)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private CanonicalJson() {
    }

    /**
     * @return the decoded value, or {@code null} after recording a diagnostic against
     * {@code entry}
     */
    public static UiValue parse(byte[] bytes, String entry, UiDiagnostics diagnostics) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            diagnostics.error(Code.INVALID_TEXT, entry, "", "UTF-8 byte-order mark is not allowed");
            return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            diagnostics.error(Code.INVALID_TEXT, entry, "", "Not valid UTF-8");
            return null;
        }
        try (JsonParser parser = FACTORY.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                diagnostics.error(Code.MALFORMED_JSON, entry, "", "Empty JSON document");
                return null;
            }
            UiValue value = readValue(parser, first, entry, diagnostics);
            if (value != null && parser.nextToken() != null) {
                diagnostics.error(Code.MALFORMED_JSON, entry, "", "Trailing content after the JSON value");
                return null;
            }
            return value;
        } catch (IllegalArgumentException e) {
            diagnostics.error(Code.INVALID_TEXT, entry, "", e.getMessage());
            return null;
        } catch (StreamConstraintsException e) {
            diagnostics.error(Code.LIMIT_EXCEEDED, entry, "", e.getOriginalMessage());
            return null;
        } catch (JsonParseException e) {
            String msg = e.getOriginalMessage();
            Code code = msg != null && msg.startsWith("Duplicate field") ? Code.DUPLICATE_KEY : Code.MALFORMED_JSON;
            diagnostics.error(code, entry, "", msg + " at line " + e.getLocation().getLineNr());
            return null;
        } catch (IOException e) {
            diagnostics.error(Code.MALFORMED_JSON, entry, "", e.getMessage());
            return null;
        }
    }

    private static UiValue readValue(JsonParser p, JsonToken token, String entry, UiDiagnostics d) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                Map<String, UiValue> fields = new TreeMap<>(UiValue.KEY_ORDER);
                JsonToken t;
                while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                    String key = p.currentName();
                    UiValue v = readValue(p, p.nextToken(), entry, d);
                    if (v == null) {
                        return null;
                    }
                    fields.put(key, v);
                }
                return new UiValue.Obj(fields);
            }
            case START_ARRAY -> {
                List<UiValue> items = new ArrayList<>();
                JsonToken t;
                while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                    UiValue v = readValue(p, t, entry, d);
                    if (v == null) {
                        return null;
                    }
                    items.add(v);
                }
                return new UiValue.Arr(items);
            }
            case VALUE_STRING -> {
                return new UiValue.Str(p.getText());
            }
            case VALUE_NUMBER_INT -> {
                BigInteger big = p.getBigIntegerValue();
                if (big.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) > 0) {
                    d.error(Code.NUMBER_PRECISION, entry, "", "Integer " + big + " is outside +/-2^53");
                    return null;
                }
                return new UiValue.Num(big.longValue());
            }
            case VALUE_NUMBER_FLOAT -> {
                double v = p.getDoubleValue();
                if (!Double.isFinite(v)) {
                    d.error(Code.NUMBER_PRECISION, entry, "", "Number " + p.getText() + " overflows binary64");
                    return null;
                }
                return new UiValue.Num(v);
            }
            case VALUE_TRUE -> {
                return UiValue.TRUE;
            }
            case VALUE_FALSE -> {
                return UiValue.FALSE;
            }
            case VALUE_NULL -> {
                return UiValue.NULL;
            }
            default -> {
                d.error(Code.MALFORMED_JSON, entry, "", "Unexpected token " + token);
                return null;
            }
        }
    }

    /** Canonical UTF-8 bytes of {@code value}. */
    public static byte[] write(UiValue value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValue(StringBuilder sb, UiValue value, int depth) {
        switch (value) {
            case UiValue.Null n -> sb.append("null");
            case UiValue.Bool b -> sb.append(b.value());
            case UiValue.Num n -> sb.append(number(n.value()));
            case UiValue.Str s -> string(sb, s.value());
            case UiValue.Arr a -> {
                if (a.items().isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append('[');
                for (int i = 0; i < a.items().size(); i++) {
                    sb.append(i == 0 ? "\n" : ",\n");
                    indent(sb, depth + 1);
                    writeValue(sb, a.items().get(i), depth + 1);
                }
                sb.append('\n');
                indent(sb, depth);
                sb.append(']');
            }
            case UiValue.Obj o -> {
                if (o.fields().isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, UiValue> e : o.fields().entrySet()) {
                    sb.append(first ? "\n" : ",\n");
                    first = false;
                    indent(sb, depth + 1);
                    string(sb, e.getKey());
                    sb.append(": ");
                    writeValue(sb, e.getValue(), depth + 1);
                }
                sb.append('\n');
                indent(sb, depth);
                sb.append('}');
            }
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.repeat("  ", depth);
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * ECMAScript {@code Number.prototype.toString} rendering of a finite binary64: the fewest
     * significant digits that round-trip, and among those the decimal closest to the value
     * (found by rounding the exact value half-even at increasing precision — unlike
     * {@code Double.toString}, which can prefer a farther two-digit form for subnormals).
     */
    public static String number(double v) {
        if (v == 0.0) {
            return "0";
        }
        double abs = Math.abs(v);
        if (v == Math.rint(v) && abs < MAX_SAFE_INTEGER) {
            return Long.toString((long) v);
        }
        BigDecimal exact = new BigDecimal(v);
        BigDecimal digits = exact;
        for (int precision = 1; precision <= 17; precision++) {
            digits = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (digits.doubleValue() == v) {
                break;
            }
        }
        digits = digits.stripTrailingZeros();
        if (abs >= 1e-6 && abs < 1e21) {
            return digits.toPlainString();
        }
        String unscaled = digits.unscaledValue().abs().toString();
        int exponent = unscaled.length() - 1 - digits.scale();
        String mantissa = unscaled.length() == 1 ? unscaled : unscaled.charAt(0) + "." + unscaled.substring(1);
        return (v < 0 ? "-" : "") + mantissa + "e" + (exponent >= 0 ? "+" : "-") + Math.abs(exponent);
    }
}
