package com.openmason.engine.format.omui;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, language-neutral JSON value used for free-form data: widget properties, style
 * declarations, literal defaults, editor metadata and preserved unknown fields.
 *
 * <p>Numbers are a single kind, IEEE-754 binary64 ({@link Num}); integer-typed schema
 * fields are range-checked where they are decoded, never here. {@code -0.0} is normalized
 * to {@code 0.0}; NaN and infinities cannot be represented. Object equality ignores key
 * order; objects decoded from JSON are key-sorted so they always re-encode canonically.
 */
public sealed interface UiValue {

    /**
     * Key and entry-name order of the wire contract: Unicode code-point order (equivalently,
     * UTF-8 byte order) — not Java's UTF-16 {@code String.compareTo}.
     */
    Comparator<String> KEY_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    Null NULL = new Null();
    Bool TRUE = new Bool(true);
    Bool FALSE = new Bool(false);

    static Str of(String value) {
        return new Str(value);
    }

    static Num of(double value) {
        return new Num(value);
    }

    static Bool of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** Short wire name of this value's JSON type, for diagnostics. */
    default String typeName() {
        return switch (this) {
            case Null n -> "null";
            case Bool b -> "boolean";
            case Num n -> "number";
            case Str s -> "string";
            case Arr a -> "array";
            case Obj o -> "object";
        };
    }

    record Null() implements UiValue {
    }

    record Bool(boolean value) implements UiValue {
    }

    record Num(double value) implements UiValue {
        public Num {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("UI values cannot hold " + value);
            }
            if (value == 0.0) {
                value = 0.0; // folds -0.0
            }
        }

        /** @return true when the value is integral and exactly representable as a long */
        public boolean isIntegral() {
            return value == Math.rint(value) && Math.abs(value) <= (1L << 53);
        }
    }

    record Str(String value) implements UiValue {
        public Str {
            requireText(value);
        }
    }

    /** Rejects unpaired surrogates: they have no UTF-8 encoding and no code-point order. */
    static String requireText(String s) {
        Objects.requireNonNull(s, "string");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired surrogate U+" + Integer.toHexString(c).toUpperCase()
                        + " in string");
            }
        }
        return s;
    }

    /**
     * {@code value} with every nested object key-sorted, so equal values always encode to the
     * same bytes regardless of how they were built.
     */
    static UiValue canonical(UiValue value) {
        return switch (value) {
            case Arr a -> new Arr(a.items().stream().map(UiValue::canonical).toList());
            case Obj o -> {
                TreeMap<String, UiValue> sorted = new TreeMap<>(KEY_ORDER);
                o.fields().forEach((k, v) -> sorted.put(k, canonical(v)));
                yield new Obj(sorted);
            }
            default -> value;
        };
    }

    record Arr(List<UiValue> items) implements UiValue {
        public Arr {
            items = List.copyOf(items);
        }
    }

    /**
     * A JSON object. Iteration order is the encoding order: schema order for records built by
     * the codecs, code-point key order for free-form objects ({@link #sorted}).
     */
    record Obj(Map<String, UiValue> fields) implements UiValue {
        public static final Obj EMPTY = new Obj(Map.of());

        public Obj {
            Map<String, UiValue> copy = new LinkedHashMap<>();
            fields.forEach((k, v) -> copy.put(requireText(k), Objects.requireNonNull(v, k)));
            fields = Collections.unmodifiableMap(copy);
        }

        /** Copy {@code fields} into an object whose keys (at every depth) iterate in code-point order. */
        public static Obj sorted(Map<String, UiValue> fields) {
            return (Obj) canonical(new Obj(fields));
        }

        public UiValue get(String key) {
            return fields.get(key);
        }

        public boolean isEmpty() {
            return fields.isEmpty();
        }
    }
}
