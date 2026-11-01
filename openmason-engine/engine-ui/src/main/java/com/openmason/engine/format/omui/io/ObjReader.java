package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.WireEnum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Typed field access over one decoded JSON object, recording a diagnostic (with a JSON
 * pointer) for every type mismatch or missing required field and then returning a
 * placeholder so decoding continues and reports everything wrong in one pass.
 *
 * <p>Tracks which keys the codec consumed; {@link #unknown()} returns the rest so records
 * can carry them back out unchanged (the "preserve everywhere" rule).
 */
public final class ObjReader {

    private final UiValue.Obj obj;
    private final String entry;
    private final String pointer;
    private final UiDiagnostics diagnostics;
    private final Set<String> consumed = new HashSet<>();

    private ObjReader(UiValue.Obj obj, String entry, String pointer, UiDiagnostics diagnostics) {
        this.obj = obj;
        this.entry = entry;
        this.pointer = pointer;
        this.diagnostics = diagnostics;
    }

    /** @return a reader over {@code value}, or over an empty object after recording a type error */
    public static ObjReader of(UiValue value, String entry, String pointer, UiDiagnostics diagnostics) {
        if (value instanceof UiValue.Obj o) {
            return new ObjReader(o, entry, pointer, diagnostics);
        }
        diagnostics.error(Code.WRONG_TYPE, entry, pointer,
                "Expected an object, found " + (value == null ? "nothing" : value.typeName()));
        return new ObjReader(UiValue.Obj.EMPTY, entry, pointer, diagnostics);
    }

    public String entry() {
        return entry;
    }

    public String pointer() {
        return pointer;
    }

    public UiDiagnostics diagnostics() {
        return diagnostics;
    }

    public String pointer(String key) {
        return pointer + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    public boolean has(String key) {
        return obj.fields().containsKey(key);
    }

    /** Raw access that also marks the key consumed. */
    public UiValue raw(String key) {
        consumed.add(key);
        return obj.get(key);
    }

    // ── strings ──

    public String requiredString(String key) {
        String s = optionalString(key, null);
        if (s == null && !has(key)) {
            missing(key);
        }
        return s == null ? "" : s;
    }

    public String optionalString(String key, String fallback) {
        UiValue v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof UiValue.Str s) {
            return s.value();
        }
        wrongType(key, "string", v);
        return fallback;
    }

    public List<String> stringList(String key) {
        List<String> out = new ArrayList<>();
        List<UiValue> items = array(key);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof UiValue.Str s) {
                out.add(s.value());
            } else {
                diagnostics.error(Code.WRONG_TYPE, entry, pointer(key) + "/" + i,
                        "Expected a string, found " + items.get(i).typeName());
            }
        }
        return out;
    }

    // ── numbers ──

    public int requiredInt(String key, int min, int max) {
        if (!has(key)) {
            raw(key);
            missing(key);
            return min;
        }
        return optionalInt(key, min, min, max);
    }

    public int optionalInt(String key, int fallback, int min, int max) {
        UiValue v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof UiValue.Num n && n.isIntegral() && n.value() >= min && n.value() <= max) {
            return (int) n.value();
        }
        diagnostics.error(Code.INVALID_VALUE, entry, pointer(key),
                "Expected an integer in [" + min + ", " + max + "], found " + render(v));
        return fallback;
    }

    public long optionalLong(String key, long fallback, long min) {
        UiValue v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof UiValue.Num n && n.isIntegral() && n.value() >= min) {
            return (long) n.value();
        }
        diagnostics.error(Code.INVALID_VALUE, entry, pointer(key),
                "Expected an integer >= " + min + ", found " + render(v));
        return fallback;
    }

    public double optionalNumber(String key, double fallback, double min, double max) {
        UiValue v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof UiValue.Num n && n.value() >= min && n.value() <= max) {
            return n.value();
        }
        diagnostics.error(Code.INVALID_VALUE, entry, pointer(key),
                "Expected a number in [" + min + ", " + max + "], found " + render(v));
        return fallback;
    }

    public double requiredNumber(String key, double min, double max) {
        if (!has(key)) {
            raw(key);
            missing(key);
            return min;
        }
        return optionalNumber(key, min, min, max);
    }

    public boolean optionalBool(String key, boolean fallback) {
        UiValue v = raw(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof UiValue.Bool b) {
            return b.value();
        }
        wrongType(key, "boolean", v);
        return fallback;
    }

    // ── enums ──

    /** Decode a wire-named enum; unknown names are errors (new values need a {@code requires} feature). */
    public <E extends Enum<E> & WireEnum> E optionalEnum(String key, Class<E> type, E fallback) {
        String name = optionalString(key, null);
        if (name == null) {
            return fallback;
        }
        for (E e : type.getEnumConstants()) {
            if (e.wire().equals(name)) {
                return e;
            }
        }
        diagnostics.error(Code.UNKNOWN_ENUM, entry, pointer(key),
                "'" + name + "' is not one of " + WireEnum.names(type));
        return fallback;
    }

    public <E extends Enum<E> & WireEnum> E requiredEnum(String key, Class<E> type, E fallback) {
        if (!has(key)) {
            raw(key);
            missing(key);
            return fallback;
        }
        return optionalEnum(key, type, fallback);
    }

    // ── containers ──

    public List<UiValue> array(String key) {
        UiValue v = raw(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof UiValue.Arr a) {
            return a.items();
        }
        wrongType(key, "array", v);
        return List.of();
    }

    /** One reader per element of an array of objects. */
    public List<ObjReader> objects(String key) {
        List<UiValue> items = array(key);
        List<ObjReader> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            out.add(ObjReader.of(items.get(i), entry, pointer(key) + "/" + i, diagnostics));
        }
        return out;
    }

    /** @return a reader for a nested object, or {@code null} when absent or {@code null} */
    public ObjReader optionalObject(String key) {
        UiValue v = raw(key);
        if (v == null || v instanceof UiValue.Null) {
            return null;
        }
        return ObjReader.of(v, entry, pointer(key), diagnostics);
    }

    /** A free-form object as a key-sorted map (absent → empty). */
    public Map<String, UiValue> freeMap(String key) {
        UiValue v = raw(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof UiValue.Obj o) {
            Map<String, UiValue> sorted = new TreeMap<>(UiValue.KEY_ORDER);
            sorted.putAll(o.fields());
            return sorted;
        }
        wrongType(key, "object", v);
        return Map.of();
    }

    /** @return every key the codec never consumed, key-sorted */
    public Map<String, UiValue> unknown() {
        Map<String, UiValue> out = new TreeMap<>(UiValue.KEY_ORDER);
        for (Map.Entry<String, UiValue> e : obj.fields().entrySet()) {
            if (!consumed.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
                diagnostics.info(Code.UNKNOWN_FIELD_PRESERVED, entry, pointer(e.getKey()),
                        "Unknown field preserved");
            }
        }
        return out;
    }

    public void error(Code code, String key, String message) {
        diagnostics.error(code, entry, key == null ? pointer : pointer(key), message);
    }

    private void missing(String key) {
        diagnostics.error(Code.MISSING_FIELD, entry, pointer(key), "Required field '" + key + "' is missing");
    }

    private void wrongType(String key, String expected, UiValue found) {
        diagnostics.error(Code.WRONG_TYPE, entry, pointer(key),
                "Expected " + expected + ", found " + found.typeName());
    }

    private static String render(UiValue v) {
        return v instanceof UiValue.Num n ? CanonicalJson.number(n.value()) : v.typeName();
    }
}
