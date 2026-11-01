package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.io.CanonicalJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canonical JSON encoding and the strict decoder, pinned as wire-contract examples. */
@Tag("regression")
class CanonicalJsonTest {

    @Test
    void numbersUseEcmaScriptShortestForm() {
        assertEquals("0", CanonicalJson.number(0.0));
        assertEquals("0", CanonicalJson.number(-0.0));
        assertEquals("1", CanonicalJson.number(1.0));
        assertEquals("-20", CanonicalJson.number(-20));
        assertEquals("0.1", CanonicalJson.number(0.1));
        assertEquals("0.25", CanonicalJson.number(0.25));
        assertEquals("1.25", CanonicalJson.number(1.25f)); // float widened exactly
        assertEquals("0.30000000000000004", CanonicalJson.number(0.1 + 0.2));
        assertEquals("9007199254740992", CanonicalJson.number(9007199254740992.0));
        assertEquals("123456789012345680000", CanonicalJson.number(1.2345678901234568e20));
        assertEquals("1e+21", CanonicalJson.number(1e21));
        assertEquals("0.000001", CanonicalJson.number(1e-6));
        assertEquals("1e-7", CanonicalJson.number(1e-7));
        assertEquals("-1.5e-7", CanonicalJson.number(-1.5e-7));
    }

    @Test
    void numbersRoundTripExactly() {
        double[] samples = {0.1, 1.0 / 3, Math.PI, 1e-300, 123.456, -0.000123, 4.35, 1.7976931348623157e308};
        for (double v : samples) {
            UiValue back = parse("[" + CanonicalJson.number(v) + "]");
            assertEquals(v, ((UiValue.Num) ((UiValue.Arr) back).items().getFirst()).value(), "value " + v);
        }
    }

    @Test
    void layoutIsFixed() {
        UiValue.Obj obj = new UiValue.Obj(Map.of());
        assertEquals("{}\n", text(obj));
        UiValue v = UiValue.Obj.sorted(Map.of(
                "b", new UiValue.Arr(List.of(UiValue.of(1), UiValue.NULL, UiValue.TRUE)),
                "a", UiValue.of("x\"\\\n\u0001é"),
                "c", new UiValue.Arr(List.of())));
        assertEquals("""
                {
                  "a": "x\\"\\\\\\n\\u0001é",
                  "b": [
                    1,
                    null,
                    true
                  ],
                  "c": []
                }
                """, text(v));
    }

    @Test
    void keysSortByCodePointNotUtf16() {
        // U+FF5E (BMP, high) must sort before U+1F600 (supplementary) in code-point order,
        // although Java's UTF-16 compareTo puts the surrogate pair first.
        String bmp = "～";
        String astral = new String(Character.toChars(0x1F600));
        UiValue.Obj sorted = UiValue.Obj.sorted(Map.of(astral, UiValue.TRUE, bmp, UiValue.FALSE));
        assertEquals(List.of(bmp, astral), List.copyOf(sorted.fields().keySet()));
    }

    @Test
    void decodingSortsObjectsAndIsStableUnderReencoding() {
        byte[] messy = "{ \"z\":1,\"a\" : {\"y\":true, \"b\":[ 1.50, 2e0 ]} }".getBytes(StandardCharsets.UTF_8);
        UiValue v = CanonicalJson.parse(messy, "t", new UiDiagnostics());
        byte[] canonical = CanonicalJson.write(v);
        assertEquals(new String(canonical, StandardCharsets.UTF_8),
                new String(CanonicalJson.write(CanonicalJson.parse(canonical, "t", new UiDiagnostics())), StandardCharsets.UTF_8));
        assertTrue(new String(canonical, StandardCharsets.UTF_8).startsWith("{\n  \"a\": {\n    \"b\": [\n      1.5,\n      2\n"));
    }

    @Test
    void rejectsMalformedAndAmbiguousInput() {
        assertRejected("{\"a\":1,\"a\":2}", Code.DUPLICATE_KEY);
        assertRejected("{\"a\":1} {}", Code.MALFORMED_JSON);
        assertRejected("{\"a\":", Code.MALFORMED_JSON);
        assertRejected("", Code.MALFORMED_JSON);
        assertRejected("[9007199254740993]", Code.NUMBER_PRECISION);
        assertRejected("[1e400]", Code.NUMBER_PRECISION);
        assertRejected("[".repeat(CanonicalJson.MAX_DEPTH + 1) + "]".repeat(CanonicalJson.MAX_DEPTH + 1), Code.LIMIT_EXCEEDED);
        assertRejected("{'a':1}", Code.MALFORMED_JSON);
        assertRejected("[NaN]", Code.MALFORMED_JSON);
    }

    @Test
    void rejectsBomAndInvalidUtf8() {
        UiDiagnostics d = new UiDiagnostics();
        assertNull(CanonicalJson.parse(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}, "t", d));
        assertEquals(Code.INVALID_TEXT, d.list().getFirst().code());
        d = new UiDiagnostics();
        assertNull(CanonicalJson.parse(new byte[]{'"', (byte) 0xC3, '"'}, "t", d));
        assertEquals(Code.INVALID_TEXT, d.list().getFirst().code());
    }

    private static void assertRejected(String json, Code code) {
        UiDiagnostics d = new UiDiagnostics();
        assertNull(CanonicalJson.parse(json.getBytes(StandardCharsets.UTF_8), "t", d), json);
        assertTrue(d.list().stream().anyMatch(x -> x.code() == code), json + " -> " + d.list());
    }

    private static UiValue parse(String json) {
        UiDiagnostics d = new UiDiagnostics();
        UiValue v = CanonicalJson.parse(json.getBytes(StandardCharsets.UTF_8), "t", d);
        assertTrue(d.list().isEmpty(), d.list().toString());
        return v;
    }

    private static String text(UiValue v) {
        return new String(CanonicalJson.write(v), StandardCharsets.UTF_8);
    }
}
