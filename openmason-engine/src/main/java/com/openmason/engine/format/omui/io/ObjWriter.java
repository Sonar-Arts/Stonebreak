package com.openmason.engine.format.omui.io;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.WireEnum;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Builds a record's JSON object in schema order. Optional fields equal to their default and
 * empty collections are <em>omitted</em> (canonical rule), so absent-on-read and
 * default-on-write agree and re-serialization is stable. Unknown preserved fields are
 * appended last in key order.
 */
public final class ObjWriter {

    private final Map<String, UiValue> fields = new LinkedHashMap<>();

    public ObjWriter put(String key, UiValue value) {
        if (value != null) {
            fields.put(key, value);
        }
        return this;
    }

    /** Always written, even when empty. */
    public ObjWriter put(String key, String value) {
        return value == null ? this : put(key, UiValue.of(value));
    }

    public ObjWriter putIfNot(String key, String value, String defaultValue) {
        return value == null || value.equals(defaultValue) ? this : put(key, value);
    }

    public ObjWriter put(String key, long value) {
        return put(key, UiValue.of((double) value));
    }

    public ObjWriter putIfNot(String key, long value, long defaultValue) {
        return value == defaultValue ? this : put(key, value);
    }

    public ObjWriter putNumber(String key, double value) {
        return put(key, UiValue.of(value));
    }

    public ObjWriter putNumberIfNot(String key, double value, double defaultValue) {
        return value == defaultValue ? this : putNumber(key, value);
    }

    public ObjWriter putIfNot(String key, boolean value, boolean defaultValue) {
        return value == defaultValue ? this : put(key, UiValue.of(value));
    }

    public ObjWriter putEnumIfNot(String key, WireEnum value, WireEnum defaultValue) {
        return value == null || value == defaultValue ? this : put(key, value.wire());
    }

    public ObjWriter putEnum(String key, WireEnum value) {
        return value == null ? this : put(key, value.wire());
    }

    public ObjWriter putStrings(String key, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        List<UiValue> items = new ArrayList<>(values.size());
        values.forEach(v -> items.add(UiValue.of(v)));
        return put(key, new UiValue.Arr(items));
    }

    public <T> ObjWriter putList(String key, List<T> values, Function<T, UiValue> encoder) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        List<UiValue> items = new ArrayList<>(values.size());
        values.forEach(v -> items.add(encoder.apply(v)));
        return put(key, new UiValue.Arr(items));
    }

    /** A free-form map, written key-sorted; omitted when empty. */
    public ObjWriter putMap(String key, Map<String, UiValue> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        return put(key, UiValue.Obj.sorted(values));
    }

    public ObjWriter putUnknown(Map<String, UiValue> unknown) {
        if (unknown != null) {
            Map<String, UiValue> sorted = new TreeMap<>(UiValue.KEY_ORDER);
            sorted.putAll(unknown);
            sorted.forEach(fields::putIfAbsent);
        }
        return this;
    }

    public UiValue.Obj build() {
        return new UiValue.Obj(fields);
    }
}
