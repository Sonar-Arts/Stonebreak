package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schema of a value the host exposes to UI (#289): a scalar of a format {@link ValueType}, an
 * object with named fields, or a list whose items have an identity field. Bindings, scripts
 * and actions are checked against these, so a mismatch is a diagnostic with context rather
 * than a wrong value on screen.
 *
 * <p>{@code null} only fits a {@link #nullable()} type. {@link #ANY} accepts everything (fixture
 * data whose shape nobody declared).
 */
public sealed interface DataType {

    DataType ANY = new Any();

    boolean nullable();

    /** The same type, accepting {@code null}. */
    DataType orNull();

    /** @return {@code null} when {@code value} fits, else what is wrong with it (a path-qualified message) */
    default String problem(UiValue value) {
        return problem(value, "");
    }

    String problem(UiValue value, String at);

    /** Wire-like description for messages: {@code number}, {@code {online: bool}}, {@code list<…>}. */
    String describe();

    /** Type of a field or item reached by {@code segment} (String field or Integer index), or {@code null}. */
    default DataType child(Object segment) {
        return this == ANY ? ANY : null;
    }

    static DataType of(ValueType kind) {
        return new Scalar(kind, false);
    }

    static DataType bool() {
        return of(ValueType.BOOL);
    }

    static DataType integer() {
        return of(ValueType.INT);
    }

    static DataType number() {
        return of(ValueType.NUMBER);
    }

    static DataType string() {
        return of(ValueType.STRING);
    }

    /** An object; field order is declaration order. */
    static Obj object(Map<String, DataType> fields) {
        return new Obj(fields, false);
    }

    static Obj object(Object... nameTypePairs) {
        Map<String, DataType> fields = new LinkedHashMap<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) {
            fields.put((String) nameTypePairs[i], (DataType) nameTypePairs[i + 1]);
        }
        return object(fields);
    }

    /**
     * A list of {@code item}s identified by their {@code identity} field ({@code null}: by index,
     * so rows cannot keep selection or state across reorders).
     */
    static ListOf list(DataType item, String identity) {
        return new ListOf(item, identity, false);
    }

    /** Best-effort type of a literal (fixtures): numbers stay {@code number}, containers recurse. */
    static DataType infer(UiValue value) {
        return switch (value) {
            case UiValue.Null n -> ANY;
            case UiValue.Bool b -> bool();
            case UiValue.Num n -> number();
            case UiValue.Str s -> string();
            case UiValue.Arr a -> new ListOf(ANY, null, false);
            case UiValue.Obj o -> {
                Map<String, DataType> fields = new LinkedHashMap<>();
                o.fields().forEach((k, v) -> fields.put(k, infer(v).orNull()));
                yield new Obj(fields, false);
            }
        };
    }

    record Scalar(ValueType kind, boolean nullable) implements DataType {
        public Scalar {
            Objects.requireNonNull(kind, "kind");
            if (kind == ValueType.LIST || kind == ValueType.OBJECT) {
                throw new IllegalArgumentException("use DataType.list/object for " + kind.wire());
            }
        }

        @Override
        public DataType orNull() {
            return new Scalar(kind, true);
        }

        @Override
        public String problem(UiValue value, String at) {
            if (value instanceof UiValue.Null) {
                return nullable ? null : at(at) + "expects " + describe() + ", got null";
            }
            return kind.accepts(value) ? null : at(at) + "expects " + describe() + ", got " + value.typeName();
        }

        @Override
        public String describe() {
            return kind.wire() + (nullable ? "?" : "");
        }
    }

    record Obj(Map<String, DataType> fields, boolean nullable) implements DataType {
        public Obj {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public DataType orNull() {
            return new Obj(fields, true);
        }

        @Override
        public String problem(UiValue value, String at) {
            if (value instanceof UiValue.Null) {
                return nullable ? null : at(at) + "expects " + describe() + ", got null";
            }
            if (!(value instanceof UiValue.Obj o)) {
                return at(at) + "expects " + describe() + ", got " + value.typeName();
            }
            for (Map.Entry<String, DataType> f : fields.entrySet()) {
                UiValue v = o.get(f.getKey());
                String p = f.getValue().problem(v == null ? UiValue.NULL : v, join(at, f.getKey()));
                if (p != null) {
                    return p;
                }
            }
            for (String k : o.fields().keySet()) {
                if (!fields.containsKey(k)) {
                    return at(join(at, k)) + "is not a field of " + describe();
                }
            }
            return null;
        }

        @Override
        public String describe() {
            StringBuilder sb = new StringBuilder("{");
            fields.forEach((k, v) -> sb.append(sb.length() > 1 ? ", " : "").append(k).append(": ").append(v.describe()));
            return sb.append('}').append(nullable ? "?" : "").toString();
        }

        @Override
        public DataType child(Object segment) {
            return segment instanceof String s ? fields.get(s) : null;
        }
    }

    record ListOf(DataType item, String identity, boolean nullable) implements DataType {
        public ListOf {
            Objects.requireNonNull(item, "item");
        }

        @Override
        public DataType orNull() {
            return new ListOf(item, identity, true);
        }

        @Override
        public String problem(UiValue value, String at) {
            if (value instanceof UiValue.Null) {
                return nullable ? null : at(at) + "expects " + describe() + ", got null";
            }
            if (!(value instanceof UiValue.Arr a)) {
                return at(at) + "expects " + describe() + ", got " + value.typeName();
            }
            List<UiValue> items = a.items();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                String p = item.problem(items.get(i), at + "[" + i + "]");
                if (p != null) {
                    return p;
                }
                if (identity != null) {
                    String id = identityOf(items.get(i), identity);
                    if (id == null) {
                        return at(at + "[" + i + "]") + "has no identity field '" + identity + "'";
                    }
                    if (!seen.add(id)) {
                        return at(at + "[" + i + "]") + "repeats identity " + id;
                    }
                }
            }
            return null;
        }

        @Override
        public String describe() {
            return "list<" + item.describe() + (identity == null ? "" : " by " + identity) + ">" + (nullable ? "?" : "");
        }

        @Override
        public DataType child(Object segment) {
            return segment instanceof Integer ? item : null;
        }
    }

    record Any() implements DataType {
        @Override
        public boolean nullable() {
            return true;
        }

        @Override
        public DataType orNull() {
            return this;
        }

        @Override
        public String problem(UiValue value, String at) {
            return null;
        }

        @Override
        public String describe() {
            return "any";
        }
    }

    /**
     * Identity key of a list item: the {@code identity} field's string or number text, or
     * {@code null} when absent or not a scalar.
     */
    static String identityOf(UiValue item, String identity) {
        if (!(item instanceof UiValue.Obj o)) {
            return null;
        }
        return switch (o.get(identity)) {
            case UiValue.Str s -> "s:" + s.value();
            case UiValue.Num n -> "n:" + n.value();
            case UiValue.Bool b -> "b:" + b.value();
            case null, default -> null;
        };
    }

    private static String at(String at) {
        return at.isEmpty() ? "" : at + ": ";
    }

    private static String join(String at, String field) {
        return at.isEmpty() ? field : at + "." + field;
    }
}
